package cn.personal.phonebridge

data class EntryNode(val path:String,val bounds:WindowBox,val clickable:Boolean,val enabled:Boolean)
object EntrySelector {
    private fun ancestor(a:String,b:String)=b.startsWith("$a/")
    private fun contains(a:WindowBox,b:WindowBox)=a.width>0 && a.height>0 && b.width>0 && b.height>0 &&
        a.left<=b.left && a.top<=b.top && a.right>=b.right && a.bottom>=b.bottom
    private fun owner(n:EntryNode,all:Map<String,EntryNode>):String? {
        var path=n.path
        repeat(4) {
            val candidate=all[path]
            if(candidate!=null && candidate.enabled && candidate.clickable && contains(candidate.bounds,n.bounds))return path
            if(!path.contains('/'))return null
            path=path.substringBeforeLast('/')
        }
        return null
    }
    fun choose(matches:List<EntryNode>,nodes:List<EntryNode>):String? {
        val candidates=matches.filter {it.enabled}.distinctBy {it.path}
        if(candidates.isEmpty())return null
        val all=nodes.associateBy {it.path}
        // Same nearest accepted click target is one entrance, even if its
        // text, icon and container are all exposed as semantic nodes.
        val groups=candidates.groupBy {owner(it,all) ?: it.path}
        val chain=candidates.all {a -> candidates.all {b ->
            a.path==b.path || (ancestor(a.path,b.path) && contains(a.bounds,b.bounds)) ||
                (ancestor(b.path,a.path) && contains(b.bounds,a.bounds))
        }}
        requireBridge(groups.size==1 || chain,"AMBIGUOUS_TARGET","发现 ${groups.size} 个独立入口（已合并同一可点击容器内的重复节点），请手动打开详情")
        return candidates.sortedWith(compareByDescending<EntryNode> {it.clickable}.thenByDescending {it.path.count {c->c=='/'}}).first().path
    }
}
