package cn.personal.phonebridge

import android.app.Application
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import org.json.JSONObject
import org.json.JSONArray
import java.time.ZonedDateTime

class BridgeApp:Application() {
    val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    val gate=Gate()
    lateinit var store:PairStore;private set
    lateinit var task:SleepTask;private set
    val rootControl by lazy {RootControl(this)}
    var restartHealthWithRoot:Boolean
        get()=getSharedPreferences("control_settings",MODE_PRIVATE).getBoolean("root_restart_health",false)
        set(value){getSharedPreferences("control_settings",MODE_PRIVATE).edit().putBoolean("root_restart_health",value).apply();changed()}
    var active=false;private set
    var connected=false;private set
    private val capture by lazy {CaptureConsent(
        read={getSharedPreferences("control_settings",MODE_PRIVATE).getBoolean("always_allow_capture",false)},
        write={value -> getSharedPreferences("control_settings",MODE_PRIVATE).edit().putBoolean("always_allow_capture",value).apply()})}
    val screenshotAllowed get()=active && connected && capture.allowed
    var alwaysAllowCapture:Boolean
        get()=capture.remembered
        set(value){capture.remember(value);changed()}
    var connection="未连接";private set
    var listener:(()->Unit)?=null
    var launchByPackage:Boolean
        get()=getSharedPreferences("control_settings",MODE_PRIVATE).getBoolean("launch_by_package",true)
        set(value){getSharedPreferences("control_settings",MODE_PRIVATE).edit().putBoolean("launch_by_package",value).apply()}
    private var socket:WebSocket?=null
    private var client:OkHttpClient?=null
    private var queue:Channel<Pair<Ticket,JSONObject>>?=null
    private var worker:Job?=null
    private var action:Job?=null
    private var handshake:Job?=null
    private var currentTicket:Ticket?=null
    private val logs=ArrayDeque<String>()
    override fun onCreate() {super.onCreate();store=PairStore(this);task=SleepTask(this)}
    fun note(text:String) {if(logs.size>=100)logs.removeFirst();logs.addLast("${ZonedDateTime.now()} $text");changed()}
    fun redactedLog()=logs.joinToString("\n")+"\n任务状态=${task.state}；不导出配对凭证、界面文字或截图。"
    fun changed() {listener?.invoke()}
    fun service():BridgeAccessibility=BridgeAccessibility.instance ?: throw BridgeError("ACCESSIBILITY_REQUIRED","无障碍服务未连接")
    fun accessibilityEnabled():Boolean {
        val expected=android.content.ComponentName(this,BridgeAccessibility::class.java)
        val enabled=android.provider.Settings.Secure.getString(contentResolver,android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any {android.content.ComponentName.unflattenFromString(it)==expected}
    }
    fun checkOperation() {
        requireBridge(active && connected,"STALE_SESSION","会话已结束或尚未认证")
        currentTicket?.let {gate.valid(it,SystemClock.elapsedRealtime())}
        task.checkDeadline()
    }
    fun stop(why:String="用户停止") {
        active=false;connected=false;capture.end();currentTicket=null;gate.reset();task.cancel(why);action?.cancel();worker?.cancel();queue?.close();queue=null
        handshake?.cancel();proofSent=false;socket?.cancel();socket=null;client?.dispatcher?.cancelAll();client?.connectionPool?.evictAll();client=null
        connection=why;stopService(Intent(this,SessionService::class.java));note("会话已停止")
    }
    fun connect() {
        stop("新会话准备中")
        val p=store.load() ?: throw BridgeError("NO_PAIRING","请先保存配对资料")
        requireBridge(BridgeAccessibility.instance!=null,"ACCESSIBILITY_REQUIRED","请先开启无障碍服务")
        requireBridge(!service().locked(),"LOCKED","请解锁手机")
        active=true;capture.begin();connection="连接中";val epoch=gate.epoch
        startForegroundService(Intent(this,SessionService::class.java))
        queue=Channel(Rules.QUEUE)
        worker=scope.launch {
            for((t,o) in queue!!) {
                if(!active || t.epoch!=gate.epoch)continue
                action=launch {
                    try {
                        gate.valid(t,SystemClock.elapsedRealtime())
                        currentTicket=t
                        val data=withTimeout((t.deadline-SystemClock.elapsedRealtime()).coerceAtLeast(1)) { dispatch(o) }
                        gate.valid(t,SystemClock.elapsedRealtime());reply(t.id,data,null)
                    } catch(e:CancellationException) {if(e is TimeoutCancellationException && active && t.epoch==gate.epoch)reply(t.id,null,BridgeError("TIMEOUT","请求超时"))}
                    catch(e:Exception) {if(active && t.epoch==gate.epoch)reply(t.id,null,e)}
                    finally {if(currentTicket==t)currentTicket=null}
                };action!!.join();action=null
            }
        }
        client=p.client()
        socket=client!!.newWebSocket(Request.Builder().url("wss://${p.host}:${p.port}/bridge").build(),object:WebSocketListener(){
            override fun onOpen(webSocket:WebSocket,response:Response) {scope.launch {if(epoch==gate.epoch) {connection="TLS 已验证，等待鉴权";changed()}}}
            override fun onMessage(webSocket:WebSocket,text:String) {scope.launch {if(epoch==gate.epoch && active)receive(text,p,webSocket)}}
            override fun onMessage(webSocket:WebSocket,bytes:okio.ByteString) {scope.launch {if(epoch==gate.epoch)stop("不支持二进制指令")}}
            override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?) {scope.launch {if(epoch==gate.epoch)stop("连接失败：请检查局域网、防火墙、证书有效期与电脑服务")}}
            override fun onClosed(webSocket:WebSocket,code:Int,reason:String) {scope.launch {if(epoch==gate.epoch)stop("电脑连接已关闭 ($code)")}}
            override fun onClosing(webSocket:WebSocket,code:Int,reason:String) {webSocket.close(code,null);scope.launch {if(epoch==gate.epoch)stop("电脑连接已断开 ($code)")}}
        })
        handshake=scope.launch {delay(10000);if(epoch==gate.epoch && !connected)stop("配对鉴权超时")};changed()
    }
    private var proofSent=false
    private fun receive(text:String,p:Pairing,ws:WebSocket) {
        try {
            requireBridge(text.toByteArray().size<=Rules.MAX_MESSAGE,"MESSAGE_LIMIT","指令不得超过 64 KiB")
            val o=JSONObject(text)
            if(!connected) {
                when(o.optString("type")) {
                    "challenge" -> {
                        val nonce=o.getString("nonce");requireBridge(nonce.matches(Regex("[a-f0-9]{64}")),"BAD_AUTH","电脑挑战无效")
                        ws.send(JSONObject().put("type","auth").put("protocol",1).put("proof",Rules.proof(p.token,nonce)).toString());proofSent=true
                    }
                    "auth_ok" -> {requireBridge(proofSent && o.getInt("protocol")==1,"BAD_AUTH","无有效鉴权过程");connected=true;handshake?.cancel();connection="已认证：${p.name}";note("已建立加密会话")}
                    else -> stop("电脑拒绝配对或协议不兼容")
                };return
            }
            requireBridge(o.optString("type")=="request" && o.optInt("protocol")==1,"BAD_PROTOCOL","请求协议不兼容")
            val id=o.getString("id");val t=gate.accept(id,o.getLong("timeout_ms"),SystemClock.elapsedRealtime())
            when(o.getString("method")) {
                "cancel_task" -> {task.cancel();reply(id,task.status(),null)}
                "get_task_status" -> reply(id,task.status(),null)
                "device_status" -> reply(id,status(),null)
                else -> {if(queue?.trySend(t to o)?.isSuccess!=true)reply(id,null,BridgeError("QUEUE_FULL","队列已满，最多等待 8 条指令"))}
            }
        }catch(e:Exception){
            if(!connected)stop("鉴权失败")
            else {val id=try{JSONObject(text).optString("id").take(64)}catch(_:Exception){""};reply(id,null,e)}
        }
    }
    private fun reply(id:String,data:JSONObject?,error:Exception?) {
        if(!connected)return
        val o=JSONObject().put("type","response").put("protocol",1).put("id",id).put("ok",error==null)
        if(error==null)o.put("data",data ?: JSONObject()) else o.put("error",JSONObject().put("code",if(error is BridgeError)error.code else "BAD_REQUEST").put("message",if(error is BridgeError)error.message else "请求参数无效或系统异常"))
        if(socket?.send(o.toString())!=true)stop("发送失败，连接已关闭")
    }
    fun status()=JSONObject().put("connected",connected).put("session_active",active).put("screenshot_authorized",screenshotAllowed).put("screenshot_capability",BridgeAccessibility.instance?.screenshotCapable() ?: false).put("accessibility_connected",BridgeAccessibility.instance!=null).put("notifications_enabled",getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()).put("phone_time",ZonedDateTime.now().toString()).put("timezone",java.time.ZoneId.systemDefault().id).put("package",BridgeAccessibility.instance?.currentPackage() ?: JSONObject.NULL).put("locked",BridgeAccessibility.instance?.locked() ?: true).put("task",task.status()).put("ocr_available",true)
    private suspend fun dispatch(o:JSONObject):JSONObject {
        requireBridge(active && connected,"NOT_CONNECTED","会话未授权")
        val p=o.optJSONObject("params") ?: JSONObject();val method=o.getString("method")
        requireBridge(!task.running,"TASK_BUSY","任务运行中，请先取消再发送其他页面指令")
        return when(method) {
            "get_ui_tree" -> service().tree().json()
            "get_screenshot" -> service().screenshot()
            "go_home" -> {service().global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);JSONObject().put("accepted",true)}
            "go_back" -> {service().global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);JSONObject().put("accepted",true)}
            "click_node" -> {service().click(p.getString("snapshot_id"),p.getString("node_id"));JSONObject().put("accepted",true)}
            "scroll" -> {requireBridge(p.getString("direction") in setOf("forward","backward"),"BAD_PARAMS","direction 为 forward 或 backward");service().scroll(p.getString("snapshot_id"),p.getString("node_id"),p.getString("direction")=="forward");JSONObject().put("accepted",true)}
            "coordinate_gesture" -> {val toX=if(p.has("to_x"))p.getDouble("to_x").toFloat() else null;val toY=if(p.has("to_y"))p.getDouble("to_y").toFloat() else null;service().coordinates(p.getString("snapshot_id"),p.getDouble("x").toFloat(),p.getDouble("y").toFloat(),toX,toY,p.optLong("duration_ms",80));JSONObject().put("accepted",true)}
            "start_sleep_task", "extract_current_sleep" -> {
                requireBridge(!p.has("date_confirmed") || p.get("date_confirmed") is Boolean,"BAD_REQUEST","date_confirmed 必须为布尔值")
                requireBridge(!p.has("report_date_mode") || p.optString("report_date_mode")=="current","BAD_REQUEST","report_date_mode 只支持 current")
                val current=p.optString("report_date_mode")=="current"
                requireBridge(!current || (!p.has("date") && !p.optBoolean("date_confirmed",false)),"BAD_REQUEST","当前报告模式不能同时指定或确认目标日期")
                JSONObject().put("task_id",task.start(if(current)"" else p.getString("date"),method=="extract_current_sleep",p.optBoolean("date_confirmed",false),current))
            }
            else -> throw BridgeError("UNKNOWN_METHOD","不支持的指令")
        }
    }
}
