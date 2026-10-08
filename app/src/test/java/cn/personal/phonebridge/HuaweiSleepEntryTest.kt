package cn.personal.phonebridge

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class HuaweiSleepEntryTest {
    private fun fixture():List<HealthEntryNode> {
        val raw=javaClass.getResourceAsStream("/huawei_home_sleep_entry.json")!!.bufferedReader().use {it.readText()}
        val nodes=JSONObject(raw).getJSONArray("nodes")
        return (0 until nodes.length()).map {i ->
            val n=nodes.getJSONObject(i);val b=n.getJSONArray("bounds")
            HealthEntryNode(EntryNode(n.getString("node_id"),WindowBox(b.getInt(0),b.getInt(1),b.getInt(2),b.getInt(3)),n.getBoolean("clickable"),n.getBoolean("enabled")),n.getString("text"),n.getString("description"),n.getString("view_id"))
        }
    }
    private fun matches(nodes:List<HealthEntryNode>)=nodes.filter {it.text.trim()=="睡眠"}
    private fun ambiguous(nodes:List<HealthEntryNode>) {try{HuaweiSleepEntry.choose(matches(nodes),nodes);fail("Expected ambiguity")}catch(e:BridgeError){assertEquals("AMBIGUOUS_TARGET",e.code)}}
    @Test fun actualSanitizedStructureChoosesReportInsteadOfOverview() {
        val nodes=fixture();assertEquals(2,matches(nodes).size)
        assertEquals("0/3/17/0/0/0/0",HuaweiSleepEntry.choose(matches(nodes),nodes))
    }
    @Test fun twoActualReportCardsStillPause() {
        val base=fixture()
        val second=base.filter {it.entry.path.startsWith("0/3/17/0/0/0")}.map {it.copy(entry=it.entry.copy(path=it.entry.path.replace("0/3/17/0/0/0","0/3/17/0/1/0")))}
        ambiguous(base+second)
    }
    @Test fun unrelatedCardDataCannotValidateReport() {
        val base=fixture().map {if(it.viewId.endsWith("/function_set_items_data"))it.copy(entry=it.entry.copy(path="0/3/18/0")) else it}
        ambiguous(base)
    }
    @Test fun disabledReportCardCannotBeUsed() {
        ambiguous(fixture().map {if(it.viewId.endsWith("/function_set_card_view"))it.copy(entry=it.entry.copy(enabled=false)) else it})
    }
    @Test fun changedResourceIdentityDoesNotGuessPosition() {
        ambiguous(fixture().map {if(it.viewId.endsWith("/function_set_items_title"))it.copy(viewId="unknown:id/title") else it})
    }
    @Test fun coordinatesMayChangeWithoutHardcodedPathOrPosition() {
        val moved=fixture().map {n -> val b=n.entry.bounds;n.copy(entry=n.entry.copy(path=n.entry.path.replace("0/3/17","0/3/24"),bounds=WindowBox(b.left*2,b.top*2,b.right*2,b.bottom*2)))}
        assertEquals("0/3/24/0/0/0/0",HuaweiSleepEntry.choose(matches(moved),moved))
    }
    @Test fun genericSingleEntranceRemainsSupported() {
        val n=HealthEntryNode(EntryNode("0/0",WindowBox(0,0,100,100),true,true),"睡眠","","another:id/title")
        assertEquals("0/0",HuaweiSleepEntry.choose(listOf(n),listOf(n)))
    }
    @Test fun dataOutsideCardDoesNotValidateIt() {
        ambiguous(fixture().map {if(it.viewId.endsWith("/function_set_items_data"))it.copy(entry=it.entry.copy(bounds=WindowBox(1000,0,1080,100))) else it})
    }
}
