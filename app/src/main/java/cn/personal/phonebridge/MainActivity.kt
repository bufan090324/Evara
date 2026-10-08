package cn.personal.phonebridge

import android.Manifest
import androidx.activity.ComponentActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity:ComponentActivity() {
    private var updateSection:UpdateSection?=null
    private val scanLauncher=registerForActivityResult(ScanContract()) {result ->
        if(result.contents==null)show("扫码已取消；如相机未获授权，请在应用信息中允许相机后重试")
        else try {trust(Pairing.parse(result.contents))}catch(_:Exception){show("二维码不是有效Evara配对资料：请检查证书、指纹、地址及有效期。未保存资料。")}
    }
    private val app get()=application as BridgeApp
    private val ink=Color.rgb(27,39,60)
    private val muted=Color.rgb(102,116,138)
    private val blue=Color.rgb(37,99,235)
    private lateinit var body:LinearLayout
    private lateinit var header:TextView
    private lateinit var footer:Button
    private val nav=mutableListOf<Button>()
    private var selected=0
    private var debug="尚未读取界面"
    private var reading=false
    private var rootBusy=false
    private var updating=false
    private var connection:TextView?=null
    private var permissions:TextView?=null
    private var cameraInfo:TextView?=null
    private var task:TextView?=null
    private var pairInfo:TextView?=null
    private var captureInfo:TextView?=null
    private var captureSwitch:Switch?=null
    private var launchSwitch:Switch?=null
    private var connectButton:Button?=null
    private var cancelButton:Button?=null
    private var fingerprintButton:Button?=null
    private var debugButton:Button?=null
    private var rootInfo:TextView?=null
    private var rootButton:Button?=null
    private var rootSwitch:Switch?=null
    private fun dp(n:Int)=(n*resources.displayMetrics.density+0.5f).toInt()
    private fun bg(color:Int,radius:Int=16,stroke:Int?=null)=GradientDrawable().apply {setColor(color);cornerRadius=dp(radius).toFloat();stroke?.let {setStroke(dp(1),it)}}
    private fun label(parent:LinearLayout,value:String,size:Float=15f,color:Int=ink,bold:Boolean=false)=TextView(this).apply {
        text=value;textSize=size;setTextColor(color);if(bold)setTypeface(typeface,Typeface.BOLD)
        setLineSpacing(dp(3).toFloat(),1f);setPadding(0,0,0,dp(10));parent.addView(this)
    }
    private fun card(title:String):LinearLayout=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL;background=bg(Color.WHITE,20);setPadding(dp(18),dp(18),dp(18),dp(8))
        body.addView(this,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(14)})
        label(this,title,18f,ink,true)
    }
    private fun action(parent:LinearLayout,value:String,primary:Boolean=false,danger:Boolean=false,run:()->Unit)=Button(this).apply {
        text=value;textSize=15f;isAllCaps=false;gravity=Gravity.CENTER;minHeight=dp(52);setPadding(dp(12),dp(12),dp(12),dp(12))
        setTextColor(if(primary)Color.WHITE else if(danger)Color.rgb(190,45,54) else blue)
        backgroundTintList=null
        background=RippleDrawable(ColorStateList.valueOf(Color.argb(35,70,100,170)),bg(if(primary)blue else if(danger)Color.rgb(255,242,243) else Color.rgb(240,245,255),12),null)
        parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(10)})
        setOnClickListener {try {run()}catch(e:Exception){show(if(e is BridgeError)e.message else "操作失败，请检查资料及系统设置")}}
    }
    private fun toggle(parent:LinearLayout,value:String,checked:Boolean,run:(Boolean)->Unit)=Switch(this).apply {
        text=value;textSize=15f;setTextColor(ink);setSingleLine(false);minHeight=dp(64);switchPadding=dp(12)
        isChecked=checked;parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(10)})
        setOnCheckedChangeListener {_,on -> if(!updating)run(on)}
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=Color.rgb(245,247,251);window.navigationBarColor=Color.WHITE
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val shell=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(245,247,251))}
        shell.setOnApplyWindowInsetsListener {view,insets ->
            val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left,bars.top,bars.right,bars.bottom);insets
        }
        val top=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(18),dp(22),dp(4))}
        header=label(top,"Evara",28f,ink,true)
        label(top,"个人手机与电脑之间的连接",13f,muted)
        shell.addView(top)
        body=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(8),dp(18),dp(6))}
        shell.addView(ScrollView(this).apply {isFillViewport=true;clipToPadding=false;addView(body)},LinearLayout.LayoutParams(-1,0,1f))
        val stopArea=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(6),dp(18),0)}
        footer=action(stopArea,"停止控制 · 断开所有操作",danger=true){app.stop()}
        shell.addView(stopArea)
        val bottom=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;setPadding(dp(8),dp(8),dp(8),dp(8));setBackgroundColor(Color.WHITE)}
        listOf("首页","配对","设置","更新").forEachIndexed {i,title ->
            val b=Button(this).apply {text=title;textSize=14f;isAllCaps=false;minHeight=dp(54);setPadding(dp(4),dp(8),dp(4),dp(8));setOnClickListener {render(i)}}
            nav.add(b);bottom.addView(b,LinearLayout.LayoutParams(0,-2,1f).apply {marginStart=dp(3);marginEnd=dp(3)})
        }
        shell.addView(bottom);setContentView(shell)
        render(savedInstanceState?.getInt("page",0) ?: 0)
    }
    private fun captureControls(c:LinearLayout) {
        captureSwitch=toggle(c,"始终允许按需截图及坐标手势",app.alwaysAllowCapture){app.alwaysAllowCapture=it}
        captureInfo=label(c,"",13f,muted)
        label(c,"记住你的选择，仅在主动开启的已认证控制会话内有效。关闭后立即撤销；不持续录屏。",13f,muted)
    }
    private fun render(page:Int) {
        updateSection?.close();updateSection=null
        selected=page.coerceIn(0,3);body.removeAllViews()
        connection=null;permissions=null;cameraInfo=null;task=null;pairInfo=null;captureInfo=null;captureSwitch=null;launchSwitch=null
        connectButton=null;cancelButton=null;fingerprintButton=null;debugButton=null
        rootInfo=null;rootButton=null;rootSwitch=null
        header.text=listOf("Evara","连接与配对","设置与调试","软件更新")[selected]
        nav.forEachIndexed {i,b -> b.isSelected=i==selected;b.setTextColor(if(i==selected)blue else muted);b.backgroundTintList=null;b.background=bg(if(i==selected)Color.rgb(234,241,255) else Color.WHITE,12)}
        when(selected) {
            0 -> {
                val c=card("电脑连接")
                connection=label(c,"",16f)
                connectButton=action(c,"开启控制会话",primary=true){
                    requireBridge(notificationsAllowed(),"NOTIFICATION_REQUIRED","请先在设置页开启通知权限，以显示停止入口")
                    app.connect();moveTaskToBack(true)
                }
                action(c,"管理配对"){render(1)}
                val p=card("权限与授权")
                permissions=label(p,"",14f)
                cameraInfo=label(p,"",14f,muted)
                captureControls(p)
                action(p,"检查与设置权限"){render(2)}
                val t=card("当前任务")
                task=label(t,"",15f)
                cancelButton=action(t,"取消当前任务"){app.task.cancel();refresh()}
                action(t,"查看任务结果与证据"){show(app.task.status().toString(2))}
            }
            1 -> {
                val p=card("已配对电脑")
                pairInfo=label(p,"",15f)
                fingerprintButton=action(p,"查看完整证书指纹"){val pair=app.store.load() ?: throw BridgeError("NO_PAIRING","没有可用配对");show("电脑：${pair.name}\n${pair.host}:${pair.port}\nSHA-256：\n${pair.fingerprint}\n请与自己的电脑独立核对。")}
                val add=card("添加或更新配对")
                label(add,"从自己的电脑复制配对资料，在手机验证并核对证书指纹后保存。",14f,muted)
                action(add,"扫描电脑配对二维码",primary=true){
                    requireBridge(!app.active && !app.task.running,"BUSY","请先停止当前控制会话再配对")
                    scanLauncher.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("扫描自己电脑上的Evara配对二维码").setBeepEnabled(false).setBarcodeImageEnabled(false).setOrientationLocked(true).addExtra("CHARACTER_SET","UTF-8"))
                }
                action(add,"粘贴配对资料"){pairDialog()}
                action(add,"手动填写配对"){manualDialog()}
                val manage=card("配对管理")
                label(manage,"删除配对将断开当前连接，需要重新配对才能连接。",13f,muted)
                action(manage,"删除配对",danger=true){
                    AlertDialog.Builder(this).setTitle("删除配对？").setMessage("将停止当前控制会话并删除手机保存的配对资料。").setNegativeButton("保留",null).setPositiveButton("删除") {_,_ -> app.stop("配对已删除");app.store.delete();refresh()}.show()
                }
            }
            2 -> {
                val p=card("系统权限")
                permissions=label(p,"",14f)
                action(p,"打开无障碍设置"){startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}
                action(p,"开启或设置通知权限"){
                    if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),10)
                    else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName))
                }
                action(p,"打开应用信息与后台权限"){startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName")))}
                label(p,"MIUI 请核对自启动、无限制省电和“后台弹出界面”。后台限制与无障碍开关是不同的设置。",13f,muted)
                val capture=card("截图与手势")
                captureControls(capture)
                label(capture,"读取任务缺少图表时间时，会在已授权会话内按需截取一次，使用随 APK 安装的中文模型在手机本地识别。原图不保存，返回字段标注 OCR 来源；未授权截图则保持缺失。",13f,muted)
                val launch=card("运动健康启动方式")
                launchSwitch=toggle(launch,"直接打开运动健康",app.launchByPackage){app.launchByPackage=it}
                label(launch,"开启：直接打开应用。关闭：返回桌面寻找并点击图标。运行中的任务完成或取消后可更改。",13f,muted)
                val restart=card("Root 重启运动健康")
                rootInfo=label(restart,"",13f,muted)
                rootSwitch=toggle(restart,"打开前强制停止运动健康",app.restartHealthWithRoot){app.restartHealthWithRoot=it}
                rootButton=action(restart,"手动检查 Root 并启用重启（可选）"){
                    requireBridge(!app.task.running && !rootBusy,"BUSY","请先结束任务或等待 Root 检查完成")
                    rootBusy=true;refresh()
                    app.scope.launch {
                        try {app.rootControl.request();app.restartHealthWithRoot=true;show("Root 检查通过，已启用打开前强制停止运动健康。")}
                        catch(e:Exception){show(if(e is BridgeError)e.message else "Root 检查失败")}
                        finally {rootBusy=false;if(!isDestroyed)refresh()}
                    }
                }
                label(restart,"仅针对运动健康，可能中断其同步和后台服务。启用的模式会保存，更新 APK 后无需手动重新检查；任务会自动验证实际 Root 权限。首次或权限撤销后由 Root 管理器授权，建议记住允许。失败不会继续启动；读取当前详情页不强制停止。",13f,muted)
                val d=card("调试与适配")
                debugButton=action(d,"读取当前界面元素"){
                    requireBridge(!app.task.running,"TASK_BUSY","请先取消任务再调试")
                    requireBridge(!reading,"BUSY","正在读取界面")
                    reading=true;refresh();moveTaskToBack(true)
                    app.scope.launch {
                        try {
                            delay(2000);debug=try{app.service().tree().json().toString(2)}catch(e:Exception){if(e is BridgeError)e.message else "读取失败"}
                            if(!isDestroyed){startActivity(Intent(this@MainActivity,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));show(debug)}
                        } finally {reading=false;if(!isDestroyed)refresh()}
                    }
                }
                label(d,"读取时暂回到后台，2 秒后读取允许页面，再返回展示。结构仅保留在内存。",13f,muted)
                action(d,"查看最近调试结构"){show(debug)}
                action(d,"导出脱敏日志"){startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain").putExtra(Intent.EXTRA_TITLE,"Evara-脱敏日志.txt"),21)}
                val about=card("关于Evara · ${packageManager.getPackageInfo(packageName,0).versionName}")
                label(about,"页面和截图先发送给配对电脑；电脑若交给云端 AI，相关内容会离开局域网。默认不保存原始截图；日志不包含凭证、界面原文或截图。",14f,muted)
                label(about,"Android 11+ · 单台手机与单台电脑 · 桌面及华为运动健康。局域网使用 INTERNET，不需要 Wi-Fi 定位权限。",13f,muted)
                label(about,"图表时间缺失时，在已授权会话内按需截图并使用本地 OCR；模型随 APK 安装，原图不保存。识别结果请核对。自动日期选择尚未实现，不声称手表已同步。原生鸿蒙不提供 Android APK 兼容性保证。",13f,muted)
            }
            3 -> {updateSection=UpdateSection(this,app);body.addView(updateSection!!.view())}
        }
        refresh()
    }
    override fun onResume() {super.onResume();app.listener={refresh()};refresh()}
    override fun onPause() {app.listener=null;super.onPause()}
    override fun onDestroy() {updateSection?.close();super.onDestroy()}
    override fun onSaveInstanceState(outState:Bundle) {outState.putInt("page",selected);super.onSaveInstanceState(outState)}
    private fun notificationsAllowed()=getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled() && (Build.VERSION.SDK_INT<33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
    private fun refresh() {
        if(!::footer.isInitialized)return
        val pair=try{app.store.load()}catch(_:Exception){null}
        val connected=BridgeAccessibility.instance!=null
        connection?.text="${if(app.connected)"● 已连接" else if(app.active)"● 连接中" else "○ 未连接"}\n${pair?.name ?: "尚未配对电脑"}${pair?.let {" · ${it.host}:${it.port}"} ?: ""}\n${app.connection}"
        permissions?.text=if(selected==0) "无障碍：${if(connected)"已连接" else "未连接"}   ·   通知：${if(notificationsAllowed())"已允许" else "未允许"}"
            else "系统无障碍开关：${if(app.accessibilityEnabled())"已开启" else "已关闭"}\n服务实际连接：${if(connected)"已连接" else "未连接"}\n通知权限：${if(notificationsAllowed())"已允许" else "未允许"}\n系统截图能力：${if(BridgeAccessibility.instance?.screenshotCapable()==true)"支持（实际截图仍需系统接受）" else "未启用"}"
        pairInfo?.text=pair?.let {"${it.name}\n${it.host}:${it.port}\n状态：${app.connection}"} ?: "尚无可用配对。证书过期或资料无法解密时，也需要检查或重新配对。"
        val t=app.task
        val names=mapOf("waiting" to "等待电脑指令","running" to "运行中","needs_user" to "需要用户处理","completed" to "已完成","failed" to "失败","cancelled" to "已取消")
        task?.text="${names[t.state] ?: t.state}\n目标日期：${t.target.ifBlank {"未指定"}}\n步骤：${t.step}${t.reason.takeIf {it.isNotBlank()}?.let {"\n$it"} ?: ""}"
        captureInfo?.text="保存的选择：${if(app.alwaysAllowCapture)"始终允许" else "未允许"} · 当前会话：${if(app.screenshotAllowed)"已授权" else "未授权"}"
        updating=true
        try {captureSwitch?.isChecked=app.alwaysAllowCapture;launchSwitch?.isChecked=app.launchByPackage;rootSwitch?.isChecked=app.restartHealthWithRoot} finally {updating=false}
        launchSwitch?.isEnabled=!t.running
        rootSwitch?.isEnabled=!t.running && !rootBusy
        rootButton?.isEnabled=!t.running && !rootBusy
        cameraInfo?.text="扫码相机权限：${if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)"已允许" else "未允许（扫码时请求）"} · 仅扫码页使用"
        rootInfo?.text=if(rootBusy)"等待 Root 管理器响应…" else if(app.rootControl.verified)"最近 Root 操作已通过 · 重启模式：${if(app.restartHealthWithRoot)"开启" else "关闭"}" else "Root 重启模式：${if(app.restartHealthWithRoot)"开启，任务时自动验证权限" else "关闭"}"
        connectButton?.isEnabled=!app.active && pair!=null && connected
        connectButton?.text=if(app.active)"控制会话已开启" else "开启控制会话"
        cancelButton?.isEnabled=t.running || t.state=="needs_user"
        fingerprintButton?.isEnabled=pair!=null
        debugButton?.isEnabled=!reading && !t.running && connected
        listOf(connectButton,cancelButton,fingerprintButton,debugButton,rootButton).forEach {it?.alpha=if(it?.isEnabled==true)1f else 0.45f}
        footer.visibility=if(app.active)View.VISIBLE else View.GONE
    }
    private fun show(message:String) {if(isFinishing || isDestroyed)return;val t=TextView(this).apply{text=message;setTextIsSelectable(true);setPadding(20,20,20,20)};AlertDialog.Builder(this).setTitle("Evara").setView(ScrollView(this).apply{addView(t)}).setPositiveButton("关闭",null).show()}
    private fun pairDialog() {
        val edit=EditText(this).apply {hint="粘贴电脑生成的 pairing.json 内容";minLines=6;inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE}
        val layout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(20,10,20,10);addView(edit);addView(Button(this@MainActivity).apply{text="从剪贴板粘贴";setOnClickListener{edit.setText(getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity))}})}
        val dialog=AlertDialog.Builder(this).setTitle("配对资料").setView(layout).setNegativeButton("取消",null).setPositiveButton("验证资料",null).create()
        dialog.setOnShowListener{dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{try{val p=Pairing.parse(edit.text.toString());dialog.dismiss();trust(p)}catch(_:Exception){show("配对资料无效：请核对协议、局域网 IPv4、端口、证书 SAN/有效期/指纹及凭证")}}};dialog.show()
    }
    private fun manualDialog() {
        val layout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(20,10,20,10)}
        val fields=listOf("host" to "电脑局域网 IPv4", "port" to "端口（默认 8765）", "name" to "电脑名称", "certificate_der" to "证书 DER 的 Base64", "certificate_sha256" to "证书 SHA-256 十六进制", "token" to "配对凭证").associate{(key,hint)-> key to EditText(this).apply{this.hint=hint;layout.addView(this);if(key=="port")setText("8765");if(key=="token")inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD}}
        val d=AlertDialog.Builder(this).setTitle("手动配对").setView(ScrollView(this).apply{addView(layout)}).setNegativeButton("取消",null).setPositiveButton("验证资料",null).create()
        d.setOnShowListener{d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{try{val o=JSONObject().put("protocol",1);fields.forEach{(k,v)->o.put(k,if(k=="port")v.text.toString().toInt() else v.text.toString().trim())};val p=Pairing.parse(o.toString());d.dismiss();trust(p)}catch(_:Exception){show("填写的配对资料无效，请检查所有字段")}}};d.show()
    }
    private fun trust(p:Pairing) {
        val layout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(20,10,20,10)}
        layout.addView(TextView(this).apply{text="首次信任：${p.name}\n${p.host}:${p.port}\nSHA-256:\n${p.fingerprint}\n请在电脑“连接与配对”页面或二维码窗口独立核对完整指纹。保存将替换已有配对并断开会话；证书变更后需重新配对。";setTextIsSelectable(true)})
        val check=CheckBox(this).apply{text="我已在电脑核对完整指纹并信任该电脑";layout.addView(this)}
        val d=AlertDialog.Builder(this).setTitle("绑定电脑证书").setView(layout).setNegativeButton("取消",null).setPositiveButton("保存配对",null).create()
        d.setOnShowListener{d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{if(!check.isChecked)show("必须先在电脑核对证书指纹")else try{app.stop("已保存新配对");app.store.save(p);d.dismiss();refresh()}catch(_:Exception){show("Android Keystore 保存失败")}}};d.show()
    }
    @Deprecated("Platform activity result")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {super.onActivityResult(requestCode,resultCode,data);if(requestCode==21 && resultCode==RESULT_OK)data?.data?.let {uri->try{contentResolver.openOutputStream(uri)?.use{it.write(app.redactedLog().toByteArray())};show("已导出脱敏日志")}catch(_:Exception){show("导出失败")}}}
}
