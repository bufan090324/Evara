package cn.personal.phonebridge

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID

fun Evidence.json()=JSONObject().put("value",value ?: JSONObject.NULL).put("unit",unit ?: JSONObject.NULL).put("source",source).put("evidence",org.json.JSONArray(raw)).put("state",state).put("reason",reason ?: JSONObject.NULL)
fun SleepReport.json()=JSONObject().put("target_date",target).put("report_date",date.json()).put("success",success).put("fields",JSONObject().apply {fields.forEach{(k,v)->put(k,v.json())}}).put("sync_state","unknown_no_evidence")
class SleepTask(private val app:BridgeApp) {
    var state="waiting";private set
    var id="";private set
    var target="";private set
    var step="未开始";private set
    var reason="";private set
    var result:JSONObject?=null;private set
    var page:JSONObject?=null;private set
    private var job:Job?=null
    private val fence=TaskFence()
    private var deadline=0L
    val running get()=state=="running"
    fun checkDeadline() {if(running)requireBridge(SystemClock.elapsedRealtime()<deadline,"TASK_TIMEOUT","任务超过 90 秒")}
    fun status()=JSONObject().put("task_id",id).put("state",state).put("target_date",target).put("step",step).put("reason",reason).put("result",result ?: JSONObject.NULL).put("page",page ?: JSONObject.NULL)
    fun cancel(why:String="用户取消") { fence.invalidate();job?.cancel();job=null;if(state=="running" || state=="needs_user") {state="cancelled";reason=why;step="已停止";app.changed()} }
    private fun update(g:Long,newStep:String) { requireBridge(fence.current(g) && app.active && app.connected,"STALE_SESSION","会话结束");step=newStep;app.changed() }
    fun start(date:String,extractOnly:Boolean,dateConfirmed:Boolean=false,currentReport:Boolean=false):String {
        if(!currentReport)Rules.date(date);requireBridge(!running,"BUSY","已有任务运行中")
        requireBridge(app.active && app.connected,"NOT_CONNECTED","需要主动开启已认证会话")
        requireBridge(BridgeAccessibility.instance!=null,"ACCESSIBILITY_REQUIRED","请启用无障碍服务")
        val g=fence.invalidate();deadline=SystemClock.elapsedRealtime()+90000;id=UUID.randomUUID().toString();target=date;state="running";result=null;page=null;reason="";step="检查连接、解锁与权限"
        val packageLaunch=app.launchByPackage
        val rootRestart=app.restartHealthWithRoot
        job=app.scope.launch(Dispatchers.Main) {
            try {
                withTimeout(90000) { run(g,extractOnly,packageLaunch,rootRestart,dateConfirmed,currentReport) }
            } catch(e:CancellationException) {
                if(e is TimeoutCancellationException && fence.current(g)) {state="failed";reason="任务超过 90 秒";app.changed()}
            } catch(e:Exception) {
                if(fence.current(g)) {
                    state=if(e is BridgeError && e.code !in setOf("ACTION_FAILED","SCREENSHOT_FAILED","TASK_TIMEOUT")) "needs_user" else "failed"
                    reason=if(e is BridgeError)e.message else "任务异常，请查看脱敏日志"
                    if(page==null)page=try{BridgeAccessibility.instance?.tree()?.json()}catch(_:Exception){null};app.note("任务暂停/失败：${if(e is BridgeError)e.code else "INTERNAL"}");app.changed()
                }
            }
        };app.changed();return id
    }
    private suspend fun waitPage(g:Long,description:String,timeoutMs:Long=8000,predicate:(UiSnapshot)->Boolean):UiSnapshot {
        update(g,description)
        return PageWaiter.await(description,
            read={app.service().tree()},matches=predicate,
            key={s -> listOf(s.pkg,s.window,s.bounds.toShortString(),s.nodes.map {listOf(it.path,it.text,it.description,it.bounds.toShortString(),it.enabled,it.clickable,it.scrollable,it.selected)})},
            now={SystemClock.elapsedRealtime()},pause={delay(it)},
            check={update(g,description);checkDeadline()},timeoutMs=timeoutMs)
    }
    private suspend fun clickAndChange(g:Long,s:UiSnapshot,n:UiNode):UiSnapshot {
        val a=app.service();val before=s.nodes.map {it.text+it.description+it.bounds.toShortString()}
        clickFresh(g,s,n)
        return waitPage(g,"等待页面变化") {it.pkg!=s.pkg || it.nodes.map {n2->n2.text+n2.description+n2.bounds.toShortString()}!=before}
    }
    private suspend fun clickFresh(g:Long,original:UiSnapshot,node:UiNode) {
        var s=original;var n=node
        repeat(3) {attempt ->
            update(g,step)
            try {app.service().click(s.id,n.path);return} catch(e:BridgeError) {
                // These checks happen before an accepted click/gesture. Never
                // retry ACTION_FAILED, unknown results or an accepted action.
                if(e.code !in setOf("STALE_SNAPSHOT","STALE_NODE") || attempt==2) throw e
                s=waitPage(g,"刷新变化中的入口") {it.pkg==original.pkg && it.nodes.any {candidate -> candidate.text==node.text && candidate.description==node.description && candidate.enabled}}
                val candidates=s.nodes.filter {it.text==node.text && it.description==node.description && it.enabled}
                n=selectEntry(s,candidates) ?: throw BridgeError("STALE_NODE","变化后入口已消失，请刷新")
            }
        }
    }
    private fun unique(s:UiSnapshot,labels:Set<String>):UiNode? {
        val matches=s.nodes.filter {it.enabled && (it.text.trim() in labels || it.description.trim() in labels)}
        return selectEntry(s,matches)
    }
    private fun selectEntry(s:UiSnapshot,matches:List<UiNode>):UiNode? {
        fun entry(n:UiNode)=EntryNode(n.path,WindowBox(n.bounds.left,n.bounds.top,n.bounds.right,n.bounds.bottom),n.clickable,n.enabled)
        fun health(n:UiNode)=HealthEntryNode(entry(n),n.text,n.description,n.id)
        val sleep=s.pkg==BridgeAccessibility.HEALTH && matches.isNotEmpty() && matches.all {it.text.trim()=="睡眠" || it.description.trim()=="睡眠"}
        val path=(if(sleep)HuaweiSleepEntry.choose(matches.map {health(it)},s.nodes.map {health(it)})
            else EntrySelector.choose(matches.map {entry(it)},s.nodes.map {entry(it)})) ?: return null
        return matches.first {it.path==path}
    }
    private suspend fun run(g:Long,extractOnly:Boolean,packageLaunch:Boolean,rootRestart:Boolean,dateConfirmed:Boolean,currentReport:Boolean) {
        val a=BridgeAccessibility.instance!!;a.guard().recycle()
        if(!extractOnly) {
            if(rootRestart) {
                update(g,"Root 强制停止运动健康并检查进程");app.checkOperation()
                app.rootControl.forceStop()
                waitPage(g,"等待停止后的页面稳定") {it.pkg==a.homePackage() || it.pkg==BridgeAccessibility.HEALTH}
                update(g,"已确认未检出运动健康相关进程，准备重新打开")
            }
            if(packageLaunch) {
                update(g,"按包名直接打开运动健康");a.launchHealth()
            } else {
            update(g,"返回桌面");a.global(AccessibilityService.GLOBAL_ACTION_HOME)
            var desktop=waitPage(g,"等待桌面稳定并定位运动健康图标") {it.pkg==a.homePackage() && it.nodes.any {n -> n.text.isNotBlank() || n.description.isNotBlank()}}
            var icon=unique(desktop,setOf("运动健康","华为运动健康"))
            var attempts=0
            while(icon==null && attempts<3) {
                val scroller=desktop.nodes.firstOrNull {it.scrollable && it.enabled} ?: break
                val before=desktop.nodes.map {it.text+it.description+it.bounds.toShortString()}
                a.scroll(desktop.id,scroller.path,true);attempts++
                desktop=waitPage(g,"有限查找桌面图标 ($attempts/3)") {it.pkg==a.homePackage() && it.nodes.map {n->n.text+n.description+n.bounds.toShortString()}!=before}
                icon=unique(desktop,setOf("运动健康","华为运动健康"))
            }
            requireBridge(icon!=null,"ICON_NOT_FOUND","未找到图标，请将运动健康放到当前桌面后重试")
            update(g,"点击桌面运动健康图标");clickFresh(g,desktop,icon!!)
            }
            var health=try {
                waitPage(g,"确认运动健康打开与睡眠入口加载",20000) {it.pkg==BridgeAccessibility.HEALTH && it.nodes.any {n->n.text.trim()=="睡眠" || n.description.trim()=="睡眠"}}
            } catch(e:BridgeError) {
                if(packageLaunch && e.code=="UNADAPTED_PAGE") throw BridgeError("LAUNCH_NOT_CONFIRMED","已发起打开运动健康，但未在 20 秒内确认应用及睡眠入口；可能被系统限制或页面未适配。请核对手机，不会自动重发或点击图标")
                throw e
            }
            ensureNotLogin(health)
            val sleep=unique(health,setOf("睡眠")) ?: throw BridgeError("UNADAPTED_PAGE","未找到唯一睡眠入口，请手动进入睡眠详情")
            update(g,"进入睡眠详情");health=clickAndChange(g,health,sleep);ensureNotLogin(health)
        }
        var s=waitPage(g,"核对睡眠详情与报告日期") {it.pkg==BridgeAccessibility.HEALTH && ((currentReport && calendarOpen(it)) || it.nodes.any {n->n.text.contains("深睡") || n.text.contains("睡眠评分") || n.id=="com.huawei.health:id/fitness_detail_time_date_tv"})}
        var calendarDate:CalendarReportDate?=null
        if(currentReport) {
            val dated=readCurrentReportDate(g,s);s=dated.first;calendarDate=dated.second
            target=calendarDate.date
        }
        ensureNotLogin(s);update(g,"提取并校验数据")
        val reading=s.reading()
        val datedReading=if(calendarDate==null)reading else SleepReading(reading.lines+calendarDate.date,reading.originals+(calendarDate.date to calendarDate.evidence))
        var report=SleepParser.parseReading(datedReading,target,dateConfirmed)
        if(SleepOcr.keys.any {report.fields[it]?.state=="missing"}) {
            if(!app.screenshotAllowed)report=SleepOcr.unavailable(report,"未授权本次截图，无法使用本地 OCR 读取图表文字")
            else {
                update(g,"按需截图，本地识别入睡、醒来及明确清醒时长")
                try {
                    val shot=a.screenshot(95)
                    val captured=a.check(shot.getString("snapshot_id"))
                    requireBridge(captured.pkg==s.pkg && captured.nodes.map {listOf(it.path,it.text,it.description,it.bounds.toShortString(),it.selected)}==s.nodes.map {listOf(it.path,it.text,it.description,it.bounds.toShortString(),it.selected)},"STALE_SNAPSHOT","截图前页面已变化，请重新读取")
                    var firstLines:List<OcrLine>?=null
                    val lines=withTimeoutOrNull(8000) {
                        val first=LocalOcr.recognize(shot)
                        firstLines=first
                        val initialReading=SleepOcr.supplement(report,first,shot.getString("snapshot_id"),0,0)
                        val focus=SleepOcr.wakeFocus(first)
                        if(initialReading.fields["wake_time"]?.state=="missing" && focus!=null) {
                            update(g,"放大醒来文字区域，重新识别完整数字")
                            app.checkOperation();requireBridge(app.screenshotAllowed,"SCREENSHOT_NOT_AUTHORIZED","截图授权已撤销")
                            a.check(shot.getString("snapshot_id"))
                            try {first+LocalOcr.recognize(shot,focus)}catch(e:BridgeError) {
                                if(e.code !in setOf("OCR_FAILED","OCR_BUSY"))throw e
                                first+focus.copy(text="醒来局部OCR失败：${e.code}，原文=${focus.text}",pass="focused_error")
                            }
                        } else first
                    }
                    update(g,"核对 OCR 页面与本次授权")
                    app.checkOperation();requireBridge(app.screenshotAllowed,"SCREENSHOT_NOT_AUTHORIZED","本次截图授权已撤销")
                    a.check(shot.getString("snapshot_id"))
                    val usable=lines ?: firstLines
                    report=if(usable==null)SleepOcr.unavailable(report,"本地 OCR 超过 8 秒，图表字段未读取；不会自动重试")
                        else SleepOcr.supplement(report,usable,shot.getString("snapshot_id"),shot.getJSONArray("region").getInt(0),shot.getJSONArray("region").getInt(1))
                    if(lines==null && usable!=null)report=SleepOcr.unavailable(report,"局部 OCR 超过总计 8 秒，保留首轮已读字段；未补猜数字")
                } catch(e:BridgeError) {
                    if(e.code !in setOf("OCR_FAILED","OCR_BUSY","SCREENSHOT_FAILED","SCREENSHOT_LIMIT","SCREENSHOT_NOT_AUTHORIZED"))throw e
                    report=SleepOcr.unavailable(report,"${e.code}: ${e.message}")
                }
            }
        }
        requireBridge(fence.current(g) && app.active && app.connected,"STALE_SESSION","会话结束")
        result=report.json();page=if(report.success)null else s.json()
        state=if(report.success)"completed" else "needs_user"
        step=if(report.success)"已完成，停留在睡眠详情" else "需要用户选择日期或适配字段"
        reason=if(report.success)"" else report.date.reason ?: "未提取到可核对的总时长，或字段存在冲突；请查看界面结构进行适配"
        app.changed()
    }
    private fun ensureNotLogin(s:UiSnapshot) {requireBridge(s.nodes.none {it.text.contains("登录") || it.text.contains("验证码") || it.text.contains("同意并")},"USER_REQUIRED","检测到登录或授权页面，请手动处理")}

    private fun dateBar(s:UiSnapshot):UiNode {
        val bars=s.nodes.filter {it.id=="com.huawei.health:id/fitness_detail_time_date_tv" && it.enabled}
        requireBridge(bars.size==1,"REPORT_DATE_REQUIRED","未找到唯一报告日期栏，请回到睡眠详情顶部")
        return bars.single()
    }
    private fun calendarOpen(s:UiSnapshot)=s.nodes.any {it.text.trim()=="选择时间"}
    private suspend fun readCurrentReportDate(g:Long,initial:UiSnapshot):Pair<UiSnapshot,CalendarReportDate> {
        val initiallyOpen=calendarOpen(initial)
        val heading=if(initiallyOpen)CalendarDate.selectedHeading(initial.nodes.map {CalendarText(it.path,it.text,it.description,it.selected,it.bounds.top,it.bounds.bottom)})
            ?: throw BridgeError("CALENDAR_DATE_UNADAPTED","已展开日历未暴露唯一选中月日，请先收起后重新读取") else dateBar(initial).text
        val full=CalendarDate.fullHeading(heading)
        if(full!=null && !calendarOpen(initial))return initial to CalendarReportDate(full,listOf("报告日期 node=${dateBar(initial).path}, text=$heading"))
        val calendar=if(calendarOpen(initial))initial else {
            update(g,"展开日期栏，读取日历年份与选中日期")
            clickFresh(g,initial,dateBar(initial))
            waitPage(g,"等待日期面板加载") {it.pkg==BridgeAccessibility.HEALTH && calendarOpen(it)}
        }
        ensureNotLogin(calendar)
        val resolved=CalendarDate.resolve(calendar.nodes.map {CalendarText(it.path,it.text,it.description,it.selected,it.bounds.top,it.bounds.bottom)},heading)
        page=calendar.json()
        update(g,"点击日期面板的关闭按钮")
        val closePath=CalendarDate.closePath(calendar.nodes.map {CalendarControl(it.path,it.id,it.description,it.type,it.enabled,it.clickable)})
        requireBridge(closePath!=null,"CALENDAR_CLOSE_UNADAPTED","未找到唯一且可用的日期面板关闭按钮，请手动收起")
        clickFresh(g,calendar,calendar.nodes.single {it.path==closePath})
        val closed=waitPage(g,"确认日期面板已收起，报告日期未改变") {it.pkg==BridgeAccessibility.HEALTH && !calendarOpen(it) && it.nodes.any {n->n.id=="com.huawei.health:id/fitness_detail_time_date_tv"}}
        requireBridge(CalendarDate.sameHeading(heading,dateBar(closed).text),"REPORT_DATE_CHANGED","收起日历后报告日期已改变，请重新读取")
        requireBridge(resolved!=null,"CALENDAR_DATE_UNADAPTED","日期面板未暴露唯一选中日期及对应年月，已收起；请导出本次任务中的日期面板节点用于适配，不猜年份")
        page=null
        return closed to resolved!!.copy(evidence=listOf("收起后报告月日 node=${dateBar(closed).path}, text=${dateBar(closed).text}")+resolved.evidence)
    }
}
