package cn.personal.phonebridge

import org.junit.Assert.*
import org.junit.Test

class OcrPageGuardTest {
    private val box=WindowBox(0,100,1080,2300)
    private fun node(id:String,text:String)=OcrPageNode(id,id,text,"",box,true,false,"TextView",false,false)
    private val page=OcrPageState("com.huawei.health",18,box,listOf(node("date","10月11日"),node("duration","7小时10分钟"),node("com.huawei.health:id/chart_title_date","入睡时间 (10/05-10/11)")))
    @Test fun unrelatedEventCanKeepReadOnlyLease(){assertTrue(OcrPageGuard.matches(page,page.copy(),1000))}
    @Test fun weeklyCarouselDoesNotChangeDailyEvidence(){assertTrue(OcrPageGuard.matches(page,page.copy(nodes=page.nodes.dropLast(1)+node("com.huawei.health:id/chart_title_date","睡眠时长 (10/05-10/11)")),7000))}
    @Test fun changedDateOrMeasurementRejected(){for(index in 0..1){val nodes=page.nodes.toMutableList();nodes[index]=nodes[index].copy(text="changed");assertFalse(OcrPageGuard.matches(page,page.copy(nodes=nodes),100))}}
    @Test fun movedOrChangedTargetRejected(){assertFalse(OcrPageGuard.matches(page,page.copy(nodes=page.nodes.map {it.copy(bounds=WindowBox(0,200,1080,2300))}),100));assertFalse(OcrPageGuard.matches(page,page.copy(nodes=page.nodes.map {it.copy(selected=true)}),100))}
    @Test fun switchedWindowAppAndRegionRejected(){assertFalse(OcrPageGuard.matches(page,page.copy(window=19),100));assertFalse(OcrPageGuard.matches(page,page.copy(pkg="com.miui.home"),100));assertFalse(OcrPageGuard.matches(page,page.copy(bounds=WindowBox(0,0,1080,2300)),100))}
    @Test fun expiredOrIncompleteLeaseRejected(){assertFalse(OcrPageGuard.matches(page,page,10001));assertFalse(OcrPageGuard.matches(page,page,-1));assertFalse(OcrPageGuard.matches(page,page.copy(nodes=page.nodes.drop(1)),100))}
}
