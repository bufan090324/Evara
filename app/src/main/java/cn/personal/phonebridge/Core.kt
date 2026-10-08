package cn.personal.phonebridge

import java.time.LocalDate
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class BridgeError(val code: String, override val message: String) : Exception(message)
fun requireBridge(ok: Boolean, code: String, text: String) { if (!ok) throw BridgeError(code, text) }
object Rules {
    const val MAX_MESSAGE = 65536
    const val QUEUE = 8
    const val SNAPSHOT_MS = 10000L
    fun lan(host: String): Boolean {
        if (!host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}"))) return false
        val p = host.split('.').map { it.toInt() }; if (p.any { it !in 0..255 }) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
    }
    fun date(s: String): LocalDate = try { requireBridge(s.matches(Regex("\\d{4}-\\d{2}-\\d{2}")), "BAD_DATE", "日期必须为 YYYY-MM-DD"); LocalDate.parse(s) } catch (e: BridgeError) { throw e } catch (_: Exception) { throw BridgeError("BAD_DATE", "无效日期") }
    fun proof(token: String, nonce: String): String {
        val mac = Mac.getInstance("HmacSHA256"); mac.init(SecretKeySpec(token.toByteArray(), "HmacSHA256"))
        return mac.doFinal(("phonebridge-v1:" + nonce).toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
data class Ticket(val id: String, val epoch: Long, val deadline: Long)
class TaskFence {
    var generation=0L; private set
    fun invalidate():Long {generation++;return generation}
    fun current(token:Long)=token==generation
}
object SnapshotLease {
    fun valid(expectedId:String,actualId:String,expectedRevision:Long,actualRevision:Long,age:Long) = expectedId==actualId && expectedRevision==actualRevision && age in 0 until Rules.SNAPSHOT_MS
}
class Gate {
    var epoch = 0L; private set
    private val seen = LinkedHashSet<String>()
    fun reset() { epoch++; seen.clear() }
    fun accept(id: String, timeout: Long, now: Long): Ticket {
        requireBridge(id.matches(Regex("[A-Za-z0-9_-]{1,64}")), "BAD_ID", "请求编号无效")
        requireBridge(timeout in 100..30000, "BAD_TIMEOUT", "超时范围 100–30000 毫秒")
        requireBridge(!seen.contains(id), "DUPLICATE_ID", "重复编号不会再次执行")
        requireBridge(seen.size < 4096, "SESSION_LIMIT", "本会话已达请求上限，请重新连接")
        seen.add(id); return Ticket(id, epoch, now + timeout)
    }
    fun valid(t: Ticket, now: Long) { requireBridge(t.epoch == epoch, "STALE_SESSION", "会话已失效"); requireBridge(now < t.deadline, "TIMEOUT", "请求已超时") }
}
data class Evidence(val value: Any?, val unit: String?, val raw: List<String>, val state: String, val reason: String?, val source: String = "accessibility")
data class SleepReport(val target: String, val date: Evidence, val fields: Map<String, Evidence>, val success: Boolean)
object SleepParser {
    fun parseReading(reading:SleepReading,target:String,dateConfirmed:Boolean=false):SleepReport {
        val report=parse(reading.lines,target,dateConfirmed)
        fun evidence(e:Evidence)=e.copy(raw=e.raw.flatMap {reading.originals[it] ?: listOf(it)}.distinct())
        return report.copy(date=evidence(report.date),fields=report.fields.mapValues {evidence(it.value)})
    }
    private fun missing(reason: String) = Evidence(null, null, emptyList(), "missing", reason)
    fun minutes(raw: String): Int? {
        if (raw.contains('%') || raw.contains('％') || raw.contains("午睡")) return null
        val r = Regex("(?<![\\d.])(\\d{1,2})\\s*小时(?:\\s*(\\d{1,2})\\s*分钟)?|(?<![\\d.])(\\d{1,3})\\s*分钟").findAll(raw).toList()
        if (r.size != 1) return null
        val m = r[0]; val h = m.groupValues[1].toIntOrNull()
        val v = if (h != null) { val n = m.groupValues[2].toIntOrNull() ?: 0; if(n >= 60) return null; h * 60 + n } else m.groupValues[3].toInt()
        return v.takeIf { it in 0..1440 }
    }
    fun parse(lines: List<String>, target: String,dateConfirmed:Boolean=false): SleepReport {
        val wanted = Rules.date(target)
        // Only standalone date headings or explicit report-date labels qualify as report evidence.
        val dateHeadings=lines.filter {it.trim().matches(Regex("(?:(?:报告日期|睡眠日期)\\s*[:：]?\\s*)?20\\d{2}[年/-]\\d{1,2}[月/-]\\d{1,2}日?(?:\\s*(?:星期[一二三四五六日天]|周[一二三四五六日天]))?"))}
        val dates = dateHeadings.flatMap { line -> Regex("(20\\d{2})[年/-](\\d{1,2})[月/-](\\d{1,2})日?").findAll(line).mapNotNull { m ->
            try { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) to line } catch (_: Exception) { null }
        }.toList() }.distinct()
        val unique = dates.map { it.first }.distinct()
        val date = when {
            unique.size > 1 -> Evidence(null, "date", dates.map { it.second }, "conflict", "页面出现多个日期，无法确认报告归属")
            unique.size == 1 -> Evidence(unique[0].toString(), "date", dates.map { it.second }, if(unique[0] == wanted) "verified" else "mismatch", if(unique[0] == wanted) null else "需要用户选择日期")
            else -> {
                val headings=lines.filter {it.trim().matches(Regex("\\d{1,2}月\\d{1,2}日(?:\\s*(?:周[一二三四五六日天]|星期[一二三四五六日天]))?"))}
                val monthDays=headings.mapNotNull {s -> Regex("(\\d{1,2})月(\\d{1,2})日").find(s)?.let {it.groupValues[1].toInt() to it.groupValues[2].toInt()}}.distinct()
                val mismatch=monthDays.size==1 && monthDays.single()!=(wanted.monthValue to wanted.dayOfMonth)
                val matching=monthDays.size==1 && monthDays.single()==(wanted.monthValue to wanted.dayOfMonth)
                if(dateConfirmed && matching) Evidence(target,"date",headings+"用户明确确认手机报告日期（含年份）为 $target","user_confirmed","月日由页面核对；年份由用户确认，未由页面独立验证","user_confirmation")
                else Evidence(null,"date",headings,if(monthDays.size>1)"conflict" else if(mismatch)"mismatch" else "uncertain",
                    if(monthDays.size>1)"出现多个报告月日，无法确认归属" else if(mismatch)"手机报告月日与请求日期不一致，且年份未确认；请在手机选择正确日期" else "未发现可核对年份的报告日期，需要用户选择并确认页面年份")
            }
        }
        val labels = linkedMapOf("total" to listOf("总睡眠时长", "总睡眠", "睡眠总时长"), "night" to listOf("夜间睡眠时长","夜间睡眠"),"naps" to listOf("零星小睡"), "deep" to listOf("深睡"), "light" to listOf("浅睡"), "rem" to listOf("快速眼动"), "awake" to listOf("清醒时长", "清醒"), "score" to listOf("睡眠评分"), "bedtime" to listOf("入睡时间","入睡"), "wake_time" to listOf("醒来时间", "起床时间","醒来"))
        val fields = labels.mapValues { (key, names) ->
            val candidates = lines.filter { s -> names.any { s.trim().startsWith(it) } && !s.contains("午睡") }
            val pairs = candidates.mapNotNull { s ->
                val value: Any? = when(key) {
                    "score" -> if(s.contains('%') || s.contains('％')) null else Regex("睡眠评分\\s*[:：]?\\s*(\\d{1,3})(?:分)?(?!\\d)").find(s)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..100 }
                    "bedtime", "wake_time" -> Regex("^\\s*(?:${names.joinToString("|"){Regex.escape(it)}})\\s*[:：]?\\s*([01]?\\d|2[0-3]):([0-5]\\d)\\s*$").matchEntire(s)?.let { "%02d:%s".format(it.groupValues[1].toInt(), it.groupValues[2]) }
                    else -> if(key=="awake" && !s.trim().matches(Regex("清醒(?:时长)?\\s*[:：]?\\s*\\d.*")))null else minutes(s)
                }; value?.let { it to s }
            }.distinct()
            when {
                pairs.map { it.first }.distinct().size > 1 -> Evidence(null, null, pairs.map { it.second }, "conflict", "同一字段出现冲突值")
                pairs.isNotEmpty() -> Evidence(pairs[0].first, when(key) { "score" -> "point"; "bedtime", "wake_time" -> "local_time"; else -> "minute" }, pairs.map { it.second }, "observed", null)
                else -> missing("没有唯一且明确关联标签的值；可能未暴露、未加载或需要版本适配")
            }
        }.toMutableMap()
        val total = fields["total"]?.value as? Int
        val night=fields["night"]?.value as? Int;val naps=fields["naps"]?.value as? Int
        val stages = listOf("deep", "light", "rem").mapNotNull { fields[it]?.value as? Int }
        if(night != null && stages.size == 3 && stages.sum() != night) fields["night"] = fields.getValue("night").copy(value = null, state = "conflict", reason = "夜间睡眠与三阶段之和不一致，保留证据供核对")
        if(total != null && night != null && naps != null && total!=night+naps) fields["total"] = fields.getValue("total").copy(value=null,state="conflict",reason="总睡眠与夜间睡眠加零星小睡不一致")
        val detail = lines.any { it.trim() in setOf("睡眠", "睡眠详情", "夜间睡眠") } && listOf("深睡","浅睡","快速眼动").count { label -> lines.any {it.contains(label)} } >= 2
        val noData=lines.any {it.contains("暂无数据") || it.contains("暂无睡眠数据") || it.contains("未同步")}
        return SleepReport(target, date, fields, detail && !noData && date.state in setOf("verified","user_confirmed") && fields["total"]?.state == "observed" && fields.values.none { it.state == "conflict" })
    }
}
