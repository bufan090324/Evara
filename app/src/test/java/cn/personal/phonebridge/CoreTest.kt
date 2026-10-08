package cn.personal.phonebridge

import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    private val detail=listOf("睡眠详情","2026年10月6日","总睡眠时长 7小时30分钟","深睡 1小时30分钟","浅睡 4小时","快速眼动 2小时","清醒 10分钟","睡眠评分 86分","入睡时间 23:30","醒来时间 07:10")
    private fun fails(code:String,block:()->Unit) {try{block();fail("Expected $code")}catch(e:BridgeError){assertEquals(code,e.code)}}
    @Test fun completeEvidence() {val r=SleepParser.parse(detail,"2026-10-06");assertTrue(r.success);assertEquals(450,r.fields["total"]!!.value);assertEquals("minute",r.fields["deep"]!!.unit);assertEquals("23:30",r.fields["bedtime"]!!.value);assertTrue(r.fields["score"]!!.raw.isNotEmpty())}
    @Test fun mismatchNeverSuccess(){val r=SleepParser.parse(detail,"2026-10-07");assertFalse(r.success);assertEquals("mismatch",r.date.state)}
    @Test fun monthDayDoesNotInventYear(){val r=SleepParser.parse(detail.map{it.replace("2026年10月6日","10月6日")},"2026-10-06");assertFalse(r.success);assertEquals("uncertain",r.date.state)}
    @Test fun invalidDateAndLeapDay(){fails("BAD_DATE"){Rules.date("2026-02-29")};assertEquals("2024-02-29",Rules.date("2024-02-29").toString());fails("BAD_DATE"){Rules.date("2026-2-01")}}
    @Test fun midnightDoesNotInferReportDate(){val r=SleepParser.parse(detail.map{it.replace("23:30","00:30")},"2026-10-06");assertEquals("2026-10-06",r.date.value);assertEquals("00:30",r.fields["bedtime"]!!.value)}
    @Test fun absentValuesStayNull(){val r=SleepParser.parse(detail.filterNot{it.startsWith("清醒") || it.startsWith("睡眠评分")},"2026-10-06");assertNull(r.fields["awake"]!!.value);assertEquals("missing",r.fields["score"]!!.state)}
    @Test fun conflictingValues(){val r=SleepParser.parse(detail+"总睡眠时长 8小时","2026-10-06");assertFalse(r.success);assertNull(r.fields["total"]!!.value);assertEquals("conflict",r.fields["total"]!!.state)}
    @Test fun conflictingDates(){val r=SleepParser.parse(detail+"2026年10月7日","2026-10-06");assertFalse(r.success);assertEquals("conflict",r.date.state)}
    @Test fun stageSumConflict(){val r=SleepParser.parse(detail.map{it.replace("浅睡 4小时","浅睡 5小时")}+"夜间睡眠 7小时30分钟","2026-10-06");assertFalse(r.success);assertEquals("conflict",r.fields["night"]!!.state)}
    @Test fun unitsAndNaps(){assertEquals(120,SleepParser.minutes("深睡 2小时"));assertEquals(45,SleepParser.minutes("45分钟"));assertNull(SleepParser.minutes("25%"));assertNull(SleepParser.minutes("深睡25％ 1小时"));assertNull(SleepParser.minutes("午睡 30分钟"));assertNull(SleepParser.minutes("1小时70分钟"));assertNull(SleepParser.minutes("1.5小时"));assertNull(SleepParser.minutes("7:30"));assertNull(SleepParser.minutes("1小时 2小时"))}
    @Test fun summaryCardNotDetail(){assertFalse(SleepParser.parse(detail.filterNot{it=="睡眠详情"},"2026-10-06").success)}
    @Test fun staleAndTimeoutRequests(){val g=Gate();val t=g.accept("a",500,100);g.valid(t,599);fails("TIMEOUT"){g.valid(t,600)};g.reset();fails("STALE_SESSION"){g.valid(t,110)};g.accept("a",500,200)}
    @Test fun duplicateIdsAndBounds(){val g=Gate();g.accept("x",100,0);fails("DUPLICATE_ID"){g.accept("x",100,0)};fails("BAD_TIMEOUT"){g.accept("y",30001,0)};fails("BAD_ID"){g.accept("../../",100,0)}}
    @Test fun privateLiteralAddressesOnly(){listOf("10.0.0.1","172.16.0.1","172.31.255.254","192.168.1.8").forEach{assertTrue(Rules.lan(it))};listOf("8.8.8.8","127.0.0.1","169.254.1.1","172.32.0.1","192.168.999.1","example.com","::1","10.0.0.1@evil").forEach{assertFalse(Rules.lan(it))}}
    @Test fun authProofKnownVector(){assertEquals("66e85cecf299cc607151565fe349759e25765bddf4b4dd6ed2fa55ec4cad1d86",Rules.proof("test-token","nonce"))}
    @Test fun cancelledTaskCannotCommitLateState(){val fence=TaskFence();val first=fence.invalidate();assertTrue(fence.current(first));fence.invalidate();assertFalse(fence.current(first));val next=fence.invalidate();assertFalse(fence.current(first));assertTrue(fence.current(next))}
    @Test fun oldPageAndCoordinatesExpire(){assertTrue(SnapshotLease.valid("a","a",1,1,9999));assertFalse(SnapshotLease.valid("a","a",1,2,10));assertFalse(SnapshotLease.valid("a","b",1,1,10));assertFalse(SnapshotLease.valid("a","a",1,1,10000))}
    @Test fun unrelatedDateCannotVerifyReport(){val r=SleepParser.parse(detail.map{if(it=="2026年10月6日")"上次更新于2026年10月6日" else it},"2026-10-06");assertFalse(r.success);assertEquals("uncertain",r.date.state)}
    @Test fun noDataDoesNotClaimSuccess(){assertFalse(SleepParser.parse(detail+"暂无睡眠数据","2026-10-06").success)}
}
