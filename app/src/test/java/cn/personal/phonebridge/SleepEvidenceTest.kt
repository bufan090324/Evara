package cn.personal.phonebridge

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class SleepEvidenceTest {
    private fun nodes(index:Int):List<SleepTextNode> {
        val raw=javaClass.getResourceAsStream("/huawei_sleep_detail_$index.json")!!.bufferedReader().use {it.readText()}
        val array=JSONObject(raw).getJSONArray("nodes")
        return (0 until array.length()).map {i -> val n=array.getJSONObject(i);SleepTextNode(n.getString("node_id"),n.getString("text"),n.getString("description"),n.getString("view_id"))}
    }
    private fun parse(n:List<SleepTextNode>,target:String="2026-10-06")=SleepParser.parseReading(SleepEvidence.extract(n),target)
    @Test fun actualTopStructureReadsSeparateFieldsAndScore() {
        val r=parse(nodes(1));assertEquals(450,r.fields["total"]!!.value);assertEquals(420,r.fields["night"]!!.value);assertEquals(30,r.fields["naps"]!!.value)
        assertEquals(70,r.fields["deep"]!!.value);assertEquals(260,r.fields["light"]!!.value);assertEquals(90,r.fields["rem"]!!.value);assertEquals(88,r.fields["score"]!!.value)
        assertEquals("uncertain",r.date.state);assertFalse(r.success);assertNull(r.fields["bedtime"]!!.value);assertNull(r.fields["wake_time"]!!.value)
        assertTrue(r.fields["score"]!!.raw.any {it.contains("scoring_value") && it.contains("text=88")})
    }
    @Test fun actualBottomStructureReadsTotalWithoutInventingOffscreenFields() {
        val r=parse(nodes(2));assertEquals(450,r.fields["total"]!!.value);assertEquals(420,r.fields["night"]!!.value);assertEquals(30,r.fields["naps"]!!.value)
        assertNull(r.fields["deep"]!!.value);assertNull(r.fields["score"]!!.value);assertNull(r.fields["awake"]!!.value)
    }
    @Test fun traversalOrderDoesNotChangeAssociations() {
        val original=parse(nodes(1));val reversed=parse(nodes(1).reversed())
        original.fields.forEach {(key,e)->assertEquals(e.value,reversed.fields[key]!!.value)}
    }
    @Test fun visibleMonthDayMismatchCannotSucceed() {val r=parse(nodes(1),"2026-10-08");assertEquals("mismatch",r.date.state);assertFalse(r.success);assertNull(r.date.value)}
    @Test fun totalWithNapsDoesNotConflictWithNightStages() {val r=parse(nodes(1));assertEquals("observed",r.fields["total"]!!.state);assertEquals("observed",r.fields["night"]!!.state)}
    @Test fun changedStageDurationConflictsWithNight() {
        val r=parse(nodes(1).map {if(it.text=="4 小时 20 分钟")it.copy(text="5 小时 20 分钟") else it})
        assertEquals("conflict",r.fields["night"]!!.state);assertFalse(r.success)
    }
    @Test fun missingNightDoesNotAssumeTotalExcludesNaps() {
        val r=SleepParser.parse(listOf("睡眠","2026-10-06","总睡眠 7小时30分钟","深睡 1小时10分钟","浅睡 4小时20分钟","快速眼动 1小时30分钟"),"2026-10-06")
        assertEquals("observed",r.fields["total"]!!.state);assertNull(r.fields["night"]!!.value)
    }
    @Test fun wrongUnitsAndCountsAreNotDurationsOrScores() {
        val r=SleepParser.parse(listOf("睡眠","2026-10-06","深睡比例 29%","浅睡比例 50%","清醒次数 0次","深睡连续性 83分","呼吸质量 100分"),"2026-10-06")
        listOf("deep","light","awake","score").forEach {assertNull(r.fields[it]!!.value)}
    }
    @Test fun twoPlainValuesInOneContainerAreNotPairedByOrder() {
        val n=listOf(SleepTextNode("0/1/0","深睡","",""),SleepTextNode("0/1/1","1小时","",""),SleepTextNode("0/1/2","2小时","",""))
        assertNull(parse(n).fields["deep"]!!.value)
    }
    @Test fun extraScoreElsewhereCannotReplaceKnownScoringWidget() {
        val n=nodes(1)+SleepTextNode("0/8/0","83 分","","com.huawei.health:id/left_top_value_text")
        assertEquals(88,parse(n).fields["score"]!!.value)
    }
    @Test fun graphLegendCannotBorrowHeaderNightDuration() {
        val n=listOf(SleepTextNode("0/4/10","深睡","","com.huawei.health:id/legend_one_text"),SleepTextNode("0/4/7","7 小时","","com.huawei.health:id/common_sleep_sleep_time"))
        assertNull(parse(n).fields["deep"]!!.value)
    }
}
