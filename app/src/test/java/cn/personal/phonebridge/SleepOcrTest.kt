package cn.personal.phonebridge

import org.junit.Assert.*
import org.junit.Test

class SleepOcrTest {
    @Test fun traditionalLabelAndDecorationsKeepOriginalEvidence() {val r=read(line("|醒來11:26"));assertEquals("11:26",r.fields["wake_time"]!!.value);assertTrue(r.fields["wake_time"]!!.raw.any {it.contains("OCR=|醒來11:26")})}
    @Test fun missingHourDigitIsNotZeroPaddedOrGuessed() {assertNull(read(line("|醒來1:26")).fields["wake_time"]!!.value);assertNull(read(line("入睡5:44")).fields["bedtime"]!!.value)}
    @Test fun focusedRecognitionCanSupplyDigitsWithoutAlteringFirstPass() {val r=read(line("|醒來1:26"),line("醒來11:26").copy(pass="focused_x3"));assertEquals("11:26",r.fields["wake_time"]!!.value);assertTrue(r.fields["wake_time"]!!.raw.any {it.contains("focused_x3")})}
    @Test fun cropMapsEnlargedCoordinatesBackToSource() {val crop=OcrCrop(850,1050,1070,1150);val mapped=crop.line("醒来11:26",153,90,486,165);assertEquals(901,mapped.left);assertEquals(1080,mapped.top);assertEquals(1012,mapped.right);assertEquals(1105,mapped.bottom)}
    @Test fun focusMustBeUniqueAndCropIsBounded() {assertNull(SleepOcr.wakeFocus(listOf(line("醒来1:26"),line("醒来2:26"))));assertNull(SleepOcr.crop(line("醒来"),0,0));val crop=SleepOcr.crop(OcrLine("醒来",990,90,1010,110),1000,1000)!!;assertEquals(1000,crop.right)}
    @Test fun focusedConflictingCompleteTimesRemainConflict() {val r=read(line("醒来11:26"),line("醒來12:26").copy(pass="focused_x3"));assertEquals("conflict",r.fields["wake_time"]!!.state);assertFalse(r.success)}
    private fun base(extra:List<String> = emptyList())=SleepParser.parse(listOf("睡眠","2026年10月7日","总睡眠 6小时31分钟","深睡 1小时40分钟","浅睡 2小时52分钟","快速眼动 1小时10分钟")+extra,"2026-10-07")
    private fun line(text:String,x:Int=20,y:Int=100)=OcrLine(text,x,y,x+100,y+20)
    private fun read(vararg lines:OcrLine)=SleepOcr.supplement(base(),lines.toList(),"snapshot",0,96)
    @Test fun readsExplicitChartTimes() {val r=read(line("入睡05:44"),line("醒来11:26"));assertEquals("05:44",r.fields["bedtime"]!!.value);assertEquals("11:26",r.fields["wake_time"]!!.value);assertEquals("ocr",r.fields["bedtime"]!!.source);assertTrue(r.fields["bedtime"]!!.raw.any {it.contains("bounds=[20,196")})}
    @Test fun chineseColonAndMidnightArePreserved() {val r=read(line("入睡 23：58"),line("醒来 00：20"));assertEquals("23:58",r.fields["bedtime"]!!.value);assertEquals("00:20",r.fields["wake_time"]!!.value)}
    @Test fun nakedGraphTicksDoNotBecomeTimes() {val r=read(line("05:44"),line("11:26"));assertNull(r.fields["bedtime"]!!.value);assertTrue(r.fields["bedtime"]!!.raw.any {it.contains("OCR=05:44")})}
    @Test fun countsAndPercentagesDoNotBecomeAwakeDuration() {assertNull(read(line("清醒次数0次"),line("清醒20%"),line("清醒次数 0次 参考10分钟")).fields["awake"]!!.value)}
    @Test fun explicitZeroDurationIsAllowedButNeverInvented() {assertEquals(0,read(line("清醒时长0分钟")).fields["awake"]!!.value);assertNull(read().fields["awake"]!!.value)}
    @Test fun conflictingTimesRemainConflict() {val r=read(line("入睡05:44"),line("入睡06:44"));assertEquals("conflict",r.fields["bedtime"]!!.state);assertFalse(r.success)}
    @Test fun nodeEvidenceTakesPriority() {val r=SleepOcr.supplement(base(listOf("入睡时间05:44")),listOf(line("入睡06:44")),"s",0,0);assertEquals("05:44",r.fields["bedtime"]!!.value);assertEquals("accessibility",r.fields["bedtime"]!!.source)}
    @Test fun horizontalUniqueLabelValuePair() {assertEquals("05:44",read(line("入睡"),line("05:44",130)).fields["bedtime"]!!.value)}
    @Test fun multipleAdjacentValuesDoNotPair() {assertNull(read(line("入睡"),line("05:44",130),line("06:44",135)).fields["bedtime"]!!.value)}
    @Test fun unrelatedRowsDoNotPair() {assertNull(read(line("入睡"),line("05:44",130,200)).fields["bedtime"]!!.value)}
    @Test fun invalidTimeOrUnrelatedLabelDoesNotPass() {assertNull(read(line("入睡25:44"),line("入睡潜伏期00:20")).fields["bedtime"]!!.value)}
    @Test fun failureKeepsOriginalFieldsAndExplainsMissing() {val r=SleepOcr.unavailable(base(),"SCREENSHOT_FAILED");assertEquals(391,r.fields["total"]!!.value);assertEquals("SCREENSHOT_FAILED",r.fields["bedtime"]!!.reason);assertNull(r.fields["awake"]!!.value)}
}
