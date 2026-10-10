package cn.personal.phonebridge

import org.junit.Assert.*
import org.junit.Test

class CalendarDateTest {
    private fun close(id:String="com.huawei.health:id/sheet_indicate_container",enabled:Boolean=true)=CalendarControl("0/12",id,"关闭","android.widget.Button",enabled,true)
    @Test fun actualCloseControlIsSelectedWithoutUnderlyingDateBar() {assertEquals("0/12",CalendarDate.closePath(listOf(close())))}
    @Test fun disabledOrUnrelatedCloseIsRejected() {assertNull(CalendarDate.closePath(listOf(close(enabled=false))));assertNull(CalendarDate.closePath(listOf(close(id="other:id/close"))))}
    @Test fun duplicateCloseControlsAreRejected() {assertNull(CalendarDate.closePath(listOf(close(),close().copy(path="0/13"))))}
    @Test fun observedWearBetaHandleCanCloseCalendar() {
        assertEquals("0/2",CalendarDate.closePath(listOf(CalendarControl("0/2","com.huawei.health:id/sheet_indicate","","",true,true))))
    }
    @Test fun handleMustMatchObservedPropertiesAndBeUnique() {
        val handle=CalendarControl("0/2","com.huawei.health:id/sheet_indicate","","",true,true)
        for(node in listOf(handle.copy(enabled=false),handle.copy(clickable=false),handle.copy(type="android.widget.TextView"),handle.copy(description="other"),handle.copy(id="other:id/sheet_indicate")))assertNull(CalendarDate.closePath(listOf(node)))
        assertNull(CalendarDate.closePath(listOf(handle,close())))
    }
    @Test fun alreadyOpenCalendarUsesSelectedMonthDay() {assertEquals("10月7日",CalendarDate.selectedHeading(listOf(n("",true,600,"10月7日"),n("",false,600,"10月8日 今天"))))}
    @Test fun closedReportHeadingAllowsWeekdayButNotDateChanges() {assertTrue(CalendarDate.sameHeading("10月7日","10月7日 周三"));assertFalse(CalendarDate.sameHeading("10月7日","10月8日 周四"))}
    private fun n(text:String,selected:Boolean=false,top:Int=100,description:String="")=CalendarText("node/$top",text,description,selected,top,top+20)
    @Test fun multipleMonthTitlesUseSelectedDaySection() {
        val r=CalendarDate.resolve(listOf(n("2026年9月",top=100),n("2026年10月",top=500),n("7",true,600)),"10月7日 周三")!!
        assertEquals("2026-10-07",r.date);assertTrue(r.evidence.any {it.contains("2026年10月")})
    }
    @Test fun reversedNodeOrderDoesNotChangeDate() {
        assertEquals("2026-10-07",CalendarDate.resolve(listOf(n("7",true,600),n("2026年10月",top=500)),"10月7日")!!.date)
    }
    @Test fun unrelatedMonthCannotSupplyYear() {
        assertNull(CalendarDate.resolve(listOf(n("2026年9月"),n("7",true,600)),"10月7日"))
    }
    @Test fun noSelectedDayNeedsAdaptation() {
        assertNull(CalendarDate.resolve(listOf(n("2026年10月"),n("7",false,600)),"10月7日"))
    }
    @Test fun multipleSelectedDaysAreAmbiguous() {
        assertNull(CalendarDate.resolve(listOf(n("2026年10月"),n("7",true,600),n("7",true,700)),"10月7日"))
    }
    @Test fun selectedDescriptionWithFullDateCanVerify() {
        assertEquals("2026-10-07",CalendarDate.resolve(listOf(n("7",true,600,"2026年10月7日 已选中")),"10月7日")!!.date)
    }
    @Test fun selectedDifferentMonthDayCannotVerify() {
        assertNull(CalendarDate.resolve(listOf(n("8",true,600,"2026年10月8日")),"10月7日"))
    }
    @Test fun invalidDateCannotVerify() {
        assertNull(CalendarDate.resolve(listOf(n("2026年2月"),n("30",true,600)),"2月30日"))
    }
    @Test fun crossYearUsesCalendarYear() {
        assertEquals("2025-12-31",CalendarDate.resolve(listOf(n("2025年12月"),n("31",true,600)),"12月31日")!!.date)
    }
    @Test fun fullHeadingMustBeStandaloneValidDate() {
        assertEquals("2026-10-07",CalendarDate.fullHeading("2026年10月7日 周三"))
        assertNull(CalendarDate.fullHeading("参考2026年10月7日"));assertNull(CalendarDate.fullHeading("2026年2月30日"))
    }
    @Test fun actualCalendarMonthDayDescriptionDoesNotRequireSelectedFlag() {
        val r=CalendarDate.resolve(listOf(n("2026年9月",top=1193),n("2026年10月",top=2011),n("",false,2235,"10月7日"),n("",false,2235,"10月8日 今天")),"10月7日 周三")!!
        assertEquals("2026-10-07",r.date);assertTrue(r.evidence.any {it.contains("description=10月7日")})
    }
    @Test fun sameMonthDayInTwoYearsIsAmbiguous() {
        assertNull(CalendarDate.resolve(listOf(n("2025年10月",top=100),n("10月7日",top=200),n("2026年10月",top=500),n("10月7日",top=600)),"10月7日"))
    }
    @Test fun unrelatedCellsDoNotVerifyReportDate() {
        assertNull(CalendarDate.resolve(listOf(n("2026年10月"),n("10月8日",top=600)),"10月7日"))
    }
    @Test fun report6SelectedTodaySuffixUsesObservedCalendarYear() {
        // Date-only excerpt from the supplied Evara task/report 6; no health values.
        val nodes=listOf(
            CalendarText("0/1","","10月10日 今天",false,622,754),
            CalendarText("0/10/0","2026年9月","",false,1193,1252),
            CalendarText("0/11/0","2026年10月","",false,2011,2070),
            CalendarText("0/11/1/0","","10月10日 今天",true,2235,2333))
        val result=CalendarDate.resolve(nodes,"10月10日 周六")!!
        assertEquals("2026-10-10",result.date)
        assertTrue(result.evidence.any {it.contains("10月10日 今天") && it.contains("selected=true")})
        assertEquals("10月10日",CalendarDate.selectedHeading(nodes))
    }
    @Test fun todaySuffixNeverSuppliesMissingYear() {
        assertNull(CalendarDate.resolve(listOf(n("",true,600,"10月10日 今天")),"10月10日"))
    }
    @Test fun todaySuffixKeepsExplicitCalendarYearAcrossMidnight() {
        assertEquals("2025-12-31",CalendarDate.resolve(listOf(n("2025年12月"),n("",true,600,"12月31日 今天")),"12月31日")!!.date)
    }
    @Test fun conflictingSelectedDateCannotUseUnselectedMatchingCell() {
        assertNull(CalendarDate.resolve(listOf(n("2026年10月"),n("",true,600,"10月10日 今天"),n("",false,600,"10月7日")),"10月7日"))
    }
    @Test fun unknownSuffixIsNotSilentlyDiscarded() {
        assertNull(CalendarDate.resolve(listOf(n("2026年10月"),n("",true,600,"10月10日 猜测")),"10月10日"))
    }
    @Test fun multipleSelectedMonthDaysCannotBeResolved() {
        assertNull(CalendarDate.resolve(listOf(n("2026年10月"),n("",true,600,"10月10日 今天"),n("",true,700,"10月9日")),"10月10日"))
    }
}
