package cn.personal.phonebridge

import org.junit.Assert.*
import org.junit.Test

class ReportDateTest {
    private val detail=listOf("睡眠","总睡眠 7小时30分钟","夜间睡眠 7小时","零星小睡 30分钟","深睡 1小时","浅睡 4小时","快速眼动 2小时")
    @Test fun matchingPartialRequiresExplicitConfirmation() {
        val r=SleepParser.parse(detail+"10月7日 周三","2026-10-07")
        assertFalse(r.success);assertEquals("uncertain",r.date.state);assertNull(r.date.value)
    }
    @Test fun userConfirmedYearRemainsDistinctFromPageVerification() {
        val r=SleepParser.parseReading(SleepReading(detail+"10月7日 周三",emptyMap()),"2026-10-07",true)
        assertTrue(r.success);assertEquals("user_confirmed",r.date.state);assertEquals("user_confirmation",r.date.source)
        assertEquals("2026-10-07",r.date.value);assertTrue(r.date.raw.any {it.contains("用户明确确认")})
    }
    @Test fun confirmationCannotOverrideMonthDayMismatch() {
        val r=SleepParser.parse(detail+"10月7日 周三","2026-10-08",true)
        assertFalse(r.success);assertEquals("mismatch",r.date.state)
    }
    @Test fun confirmationCannotFillMissingDate() {
        val r=SleepParser.parse(detail,"2026-10-07",true)
        assertFalse(r.success);assertNull(r.date.value)
    }
    @Test fun confirmationCannotOverrideFullDateOrMultipleDates() {
        assertEquals("mismatch",SleepParser.parse(detail+"2025年10月7日","2026-10-07",true).date.state)
        assertEquals("conflict",SleepParser.parse(detail+listOf("10月7日","10月8日"),"2026-10-07",true).date.state)
    }
    @Test fun confirmationDoesNotBypassMissingData() {
        assertFalse(SleepParser.parse(listOf("睡眠","10月7日 周三","暂无睡眠数据"),"2026-10-07",true).success)
    }
}
