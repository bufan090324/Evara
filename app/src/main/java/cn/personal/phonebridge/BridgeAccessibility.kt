package cn.personal.phonebridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class UiNode(val path: String, val parent: String?, val text: String, val description: String, val id: String, val type: String, val bounds: Rect, val clickable: Boolean, val scrollable: Boolean, val enabled: Boolean,val selected:Boolean=false) {
    fun json() = JSONObject().put("node_id",path).put("parent", parent ?: JSONObject.NULL).put("text",text).put("description",description).put("view_id",id).put("class",type).put("bounds",JSONArray(listOf(bounds.left,bounds.top,bounds.right,bounds.bottom))).put("clickable",clickable).put("scrollable",scrollable).put("enabled",enabled).put("visible",true).put("selected",selected)
}
data class UiSnapshot(val id: String, val revision: Long, val time: Long, val pkg: String, val window: Int, val nodes: List<UiNode>, val bounds: Rect) {
    fun json() = JSONObject().put("snapshot_id",id).put("revision",revision).put("expires_in_ms",Rules.SNAPSHOT_MS).put("package",pkg).put("window_id",window).put("nodes",JSONArray(nodes.map { it.json() }))
    fun reading()=SleepEvidence.extract(nodes.map {SleepTextNode(it.path,it.text,it.description,it.id)})
    fun evidenceLines():List<String> = reading().lines
}
class BridgeAccessibility : AccessibilityService() {
    companion object { var instance: BridgeAccessibility? = null; private set; const val HEALTH = "com.huawei.health" }
    var revision = 0L; private set
    private var snapshot: UiSnapshot? = null
    private val app get() = application as BridgeApp
    override fun onServiceConnected() { instance = this; app.note("无障碍服务已连接") }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { revision++; snapshot = null }
    override fun onInterrupt() { snapshot=null;revision++;app.note("无障碍反馈中断（不是授权关闭），已使旧快照失效") }
    override fun onUnbind(intent:Intent?):Boolean { if(instance===this){instance=null;app.stop("无障碍服务已解绑，请核对系统开关")};return super.onUnbind(intent) }
    override fun onDestroy() { if(instance===this){instance = null; app.stop("无障碍服务关闭")};super.onDestroy() }
    fun locked() = getSystemService(KeyguardManager::class.java).isKeyguardLocked
    fun screenshotCapable() = (serviceInfo.capabilities and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0
    fun launchHealth() {
        HealthLaunch.once(
            resolve={packageManager.getLaunchIntentForPackage(HealthLaunch.PACKAGE)},
            packageOf={it.component?.packageName},
            check={app.checkOperation();guard().recycle()},
            send={intent ->
                intent.setPackage(HealthLaunch.PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {startActivity(intent)}
                catch(_:android.content.ActivityNotFoundException) {throw BridgeError("APP_NOT_FOUND","运动健康启动入口已不可用，请核对手机应用")}
                catch(_:SecurityException) {throw BridgeError("LAUNCH_DENIED","系统拒绝打开运动健康，请核对后台弹出界面限制或手动打开；不会自动重试")}
            })
        snapshot=null
    }
    fun homePackage(): String? = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
    fun currentPackage(): String? = rootInActiveWindow?.let { n -> val p=n.packageName?.toString(); n.recycle(); p }
    private fun Rect.box() = WindowBox(left,top,right,bottom)
    private fun screenBox() = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds.box()
    private fun windowMeta(w: AccessibilityWindowInfo): WindowMeta {
        val b=Rect();w.getBoundsInScreen(b)
        val n=w.root
        val pkg=try {n?.packageName?.toString()} finally {n?.recycle()}
        return WindowMeta(w.type,w.title?.toString() ?: "",pkg,w.isActive,w.isFocused,b.box())
    }
    fun guard(): AccessibilityNodeInfo {
        requireBridge(!locked(),"LOCKED","手机已锁屏，请解锁并重新发起任务")
        val root = rootInActiveWindow ?: throw BridgeError("NO_WINDOW","无可读取页面")
        try {
            val pkg = root.packageName?.toString() ?: ""
            requireBridge(pkg == HEALTH || pkg == homePackage(),"OUT_OF_SCOPE","当前应用不在桌面或华为运动健康范围内")
            var count=0
            fun unsafe(n:AccessibilityNodeInfo,depth:Int):Boolean {
                if(++count>1500 || depth>35)throw BridgeError("TREE_LIMIT","页面过大，暂停检查")
                val t=n.text?.toString()?.trim() ?: ""
                if(n.isPassword || t.contains("验证码") || t.contains("登录") || t in setOf("允许","拒绝","仅在使用中允许","始终允许","同意并继续","同意并使用","授权"))return true
                for(i in 0 until n.childCount)n.getChild(i)?.let{child->try{if(unsafe(child,depth+1))return true}finally{child.recycle()}}
                return false
            }
            requireBridge(!unsafe(root,0),"USER_REQUIRED","检测到登录或权限页面，请用户手动处理")
            val all = windows
            try {
                val apps = all.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                requireBridge(apps.size == 1 && apps[0].id == root.windowId,"MULTI_WINDOW","多窗口或页面覆盖，暂停操作")
                val area=Rect();root.getBoundsInScreen(area)
                val screen=screenBox()
                val blocked=all.map {windowMeta(it)}.filter {WindowPolicy.blocks(it,area.box(),screen)}
                if(blocked.isNotEmpty()) {
                    val details=blocked.take(6).joinToString("；") {w -> "type=${w.type}, title=${w.title.take(60).replace('\n',' ')}, package=${w.pkg ?: "未知"}, bounds=${w.bounds}, active=${w.active}, focused=${w.focused}"}
                    throw BridgeError("OVERLAY","检测到 ${blocked.size} 个覆盖窗口，请收起通知栏、键盘或悬浮窗后刷新；$details")
                }
            } finally { all.forEach { it.recycle() } }
            return root
        } catch(e:Exception) { root.recycle(); throw e }
    }
    fun tree(): UiSnapshot {
        val root=guard(); val nodes=mutableListOf<UiNode>(); var visited=0
        fun walk(n:AccessibilityNodeInfo,path:String,parent:String?,depth:Int) {
            if(++visited>1500 || depth>35) throw BridgeError("TREE_LIMIT","页面结构过大，请使用更具体的详情页")
            val b=Rect(); n.getBoundsInScreen(b)
            val text=if(n.isPassword) "[已隐藏密码]" else n.text?.toString()?.take(500) ?: ""
            val desc=if(n.isPassword) "" else n.contentDescription?.toString()?.take(500) ?: ""
            val useful=n.isVisibleToUser && (text.isNotBlank() || desc.isNotBlank() || n.isClickable || n.isScrollable)
            if(useful) nodes.add(UiNode(path,parent,text,desc,n.viewIdResourceName ?: "",n.className?.toString() ?: "",b,n.isClickable,n.isScrollable,n.isEnabled,n.isSelected))
            for(i in 0 until n.childCount) n.getChild(i)?.let { child -> try { walk(child,"$path/$i",if(useful) path else parent,depth+1) } finally { child.recycle() } }
        }
        try {
            val bounds=Rect(); root.getBoundsInScreen(bounds)
            val all=windows
            val content=try {WindowPolicy.contentArea(bounds.box(),all.map {windowMeta(it)},screenBox())} finally {all.forEach {it.recycle()}}
            requireBridge(content.width>0 && content.height>0,"NO_WINDOW","有效应用区域为空")
            bounds.set(content.left,content.top,content.right,content.bottom)
            walk(root,"0",null,0)
            val s=UiSnapshot(UUID.randomUUID().toString(),revision,SystemClock.elapsedRealtime(),root.packageName.toString(),root.windowId,nodes,bounds)
            requireBridge(s.json().toString().toByteArray().size < 200000,"TREE_LIMIT","页面内容过大")
            snapshot=s; return s
        } finally { root.recycle() }
    }
    fun check(id:String): UiSnapshot {
        val s=snapshot ?: throw BridgeError("STALE_SNAPSHOT","页面已改变，请重新读取")
        requireBridge(SnapshotLease.valid(s.id,id,s.revision,revision,SystemClock.elapsedRealtime()-s.time),"STALE_SNAPSHOT","快照过期或页面改变")
        val r=guard(); try { requireBridge(r.packageName.toString()==s.pkg && r.windowId==s.window,"STALE_SNAPSHOT","页面已切换") } finally {r.recycle()}; return s
    }
    private fun locate(path:String): AccessibilityNodeInfo {
        requireBridge(path.matches(Regex("0(?:/\\d{1,4}){0,35}")),"BAD_NODE","控件路径无效")
        var n=guard()
        try { for(p in path.split('/').drop(1)) { val child=n.getChild(p.toInt()) ?: throw BridgeError("STALE_NODE","控件已消失"); n.recycle(); n=child }; return n } catch(e:Exception) {n.recycle();throw e}
    }
    suspend fun click(id:String,path:String) {
        val s=check(id); val expected=s.nodes.find { it.path==path } ?: throw BridgeError("BAD_NODE","控件不在快照中")
        val node=locate(path); var acted=false
        try {
            val b=Rect();node.getBoundsInScreen(b)
            requireBridge(node.isVisibleToUser && node.isEnabled && b==expected.bounds && (node.text?.toString() ?: "")==expected.text && (node.contentDescription?.toString() ?: "")==expected.description,"STALE_NODE","控件状态已改变")
            var candidate:AccessibilityNodeInfo?=AccessibilityNodeInfo.obtain(node)
            try { repeat(4) { if(!acted && candidate!=null) { if(candidate!!.isClickable && candidate!!.isEnabled) {app.checkOperation();acted=candidate!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)}; if(!acted) { val parent=candidate!!.parent;candidate!!.recycle();candidate=parent } } } } finally { candidate?.recycle() }
        } finally { node.recycle() }
        if(!acted) { requireBridge(app.screenshotAllowed,"GESTURE_NOT_AUTHORIZED","节点动作失败；坐标备用需本会话截图/手势授权"); check(id); gesture(expected.bounds.centerX().toFloat(),expected.bounds.centerY().toFloat(),null,null,80) }
        snapshot=null
    }
    fun scroll(id:String,path:String,forward:Boolean) {
        val s=check(id); val expected=s.nodes.find {it.path==path && it.scrollable} ?: throw BridgeError("BAD_NODE","需要可滚动控件")
        val n=locate(path);try { val b=Rect();n.getBoundsInScreen(b);requireBridge(n.isVisibleToUser && n.isEnabled && n.isScrollable && b==expected.bounds && (n.viewIdResourceName ?: "")==expected.id && (n.text?.toString() ?: "")==expected.text && (n.contentDescription?.toString() ?: "")==expected.description,"STALE_NODE","滚动控件已改变");app.checkOperation();requireBridge(n.performAction(if(forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD),"ACTION_FAILED","页面未接受滚动") } finally { n.recycle() };snapshot=null
    }
    fun global(action:Int) { val n=guard();n.recycle();app.checkOperation();requireBridge(performGlobalAction(action),"ACTION_FAILED","系统动作失败");snapshot=null }
    suspend fun coordinates(id:String,x:Float,y:Float,toX:Float?,toY:Float?,duration:Long) {
        requireBridge(app.screenshotAllowed,"GESTURE_NOT_AUTHORIZED","需要本次截图与手势授权");val s=check(id)
        requireBridge(x.isFinite() && y.isFinite() && (toX==null)==(toY==null) && (toX==null || (toX.isFinite() && toY!!.isFinite())) && duration in 50..1500 && s.bounds.contains(x.toInt(),y.toInt()) && (toX==null || (toY!=null && s.bounds.contains(toX.toInt(),toY.toInt()))),"BAD_COORDINATES","手势必须位于当前应用区域内")
        gesture(x,y,toX,toY,duration);snapshot=null
    }
    private suspend fun gesture(x:Float,y:Float,toX:Float?,toY:Float?,duration:Long) = suspendCancellableCoroutine<Unit> { c ->
        app.checkOperation()
        val p=Path();p.moveTo(x,y);if(toX!=null && toY!=null)p.lineTo(toX,toY)
        val g=GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p,0,duration)).build()
        val accepted=dispatchGesture(g,object:GestureResultCallback(){override fun onCompleted(gestureDescription:GestureDescription?) {if(c.isActive)c.resume(Unit)};override fun onCancelled(gestureDescription:GestureDescription?) {if(c.isActive)c.resumeWithException(BridgeError("GESTURE_CANCELLED","系统取消手势"))}},null)
        if(!accepted && c.isActive)c.resumeWithException(BridgeError("GESTURE_FAILED","系统未接受手势"))
    }
    suspend fun screenshot(jpegQuality:Int=75): JSONObject {
        requireBridge(app.screenshotAllowed,"SCREENSHOT_NOT_AUTHORIZED","请在手机允许本次会话截图")
        val s=tree();val epoch=app.gate.epoch
        app.checkOperation()
        return suspendCancellableCoroutine { c ->
            takeScreenshot(Display.DEFAULT_DISPLAY,mainExecutor,object:TakeScreenshotCallback {
                override fun onSuccess(result:ScreenshotResult) {
                    val buffer=result.hardwareBuffer
                    try {
                        if(!c.isActive)return
                        requireBridge(app.active && app.screenshotAllowed && app.gate.epoch==epoch,"STALE_SESSION","会话已结束")
                        check(s.id)
                        val hardware=Bitmap.wrapHardwareBuffer(buffer,result.colorSpace) ?: throw BridgeError("SCREENSHOT_FAILED","无法读取图像")
                        val bitmap=try { hardware.copy(Bitmap.Config.ARGB_8888,false) ?: throw BridgeError("SCREENSHOT_FAILED","无法复制图像") } finally { hardware.recycle() }
                        val b=Rect(s.bounds)
                        if(!b.intersect(0,0,bitmap.width,bitmap.height) || b.width()<=0 || b.height()<=0) {bitmap.recycle();throw BridgeError("SCREENSHOT_FAILED","截图区域无效")}
                        val crop=Bitmap.createBitmap(bitmap,b.left,b.top,b.width(),b.height());if(crop!==bitmap)bitmap.recycle()
                        val out=ByteArrayOutputStream();try {crop.compress(Bitmap.CompressFormat.JPEG,jpegQuality,out)}finally{crop.recycle()}
                        requireBridge(out.size()<=1500000,"SCREENSHOT_LIMIT","截图超过 1.5 MB")
                        c.resume(JSONObject().put("snapshot_id",s.id).put("mime","image/jpeg").put("region",JSONArray(listOf(b.left,b.top,b.right,b.bottom))).put("base64",android.util.Base64.encodeToString(out.toByteArray(),android.util.Base64.NO_WRAP)))
                    } catch(e:Exception) {if(c.isActive)c.resumeWithException(e)} finally {buffer.close()}
                }
                override fun onFailure(errorCode:Int) { if(c.isActive)c.resumeWithException(BridgeError("SCREENSHOT_FAILED","系统截图失败 ($errorCode)，可能为禁止截图、频率限制或系统不兼容")) }
            })
        }
    }
}
