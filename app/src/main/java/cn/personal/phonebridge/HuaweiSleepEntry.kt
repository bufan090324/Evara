package cn.personal.phonebridge

data class HealthEntryNode(val entry:EntryNode,val text:String,val description:String,val viewId:String)
object HuaweiSleepEntry {
    private const val PREFIX="com.huawei.health:id/"
    private fun contains(a:WindowBox,b:WindowBox)=a.width>0 && a.height>0 && b.width>0 && b.height>0 && a.left<=b.left && a.top<=b.top && a.right>=b.right && a.bottom>=b.bottom
    fun choose(matches:List<HealthEntryNode>,nodes:List<HealthEntryNode>):String? {
        val all=nodes.associateBy {it.entry.path}
        val reports=matches.filter {title ->
            if(!title.entry.enabled || title.text.trim()!="睡眠" || title.viewId!=PREFIX+"function_set_items_title")return@filter false
            var path=title.entry.path
            var card:HealthEntryNode?=null
            repeat(3) {
                if(path.contains('/'))path=path.substringBeforeLast('/')
                val candidate=all[path]
                if(card==null && candidate?.viewId==PREFIX+"function_set_card_view" && candidate.entry.enabled && candidate.entry.clickable && contains(candidate.entry.bounds,title.entry.bounds))card=candidate
            }
            val owner=card ?: return@filter false
            nodes.any {data -> data.entry.enabled && data.viewId==PREFIX+"function_set_items_data" &&
                data.entry.path.startsWith(owner.entry.path+"/") && contains(owner.entry.bounds,data.entry.bounds)}
        }
        // A matched title + known clickable report card + its data field is
        // stronger evidence than the non-actionable clover overview label.
        if(reports.isNotEmpty())return EntrySelector.choose(reports.map {it.entry},nodes.map {it.entry})
        return EntrySelector.choose(matches.map {it.entry},nodes.map {it.entry})
    }
}
