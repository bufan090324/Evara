package cn.personal.phonebridge

data class SleepTextNode(val path:String,val text:String,val description:String,val viewId:String)
data class SleepReading(val lines:List<String>,val originals:Map<String,List<String>>)
object SleepEvidence {
    private const val PREFIX="com.huawei.health:id/"
    private fun parent(n:SleepTextNode)=n.path.substringBeforeLast('/',"")
    fun extract(nodes:List<SleepTextNode>):SleepReading {
        val lines=mutableListOf<String>();val originals=linkedMapOf<String,MutableList<String>>()
        fun emit(line:String,sources:List<SleepTextNode>) {
            if(line.isBlank())return
            lines.add(line)
            originals.getOrPut(line){mutableListOf()}.addAll(sources.map {"node=${it.path}, id=${it.viewId}, text=${it.text}, description=${it.description}"})
        }
        nodes.forEach {n ->
            if(n.text.isNotBlank())emit(n.text.trim(),listOf(n))
            if(n.description.isNotBlank() && n.description!=n.text)emit(n.description.trim(),listOf(n))
            if(n.viewId==PREFIX+"night_and_noon_hybrid_time") n.text.split('|','｜').forEach {emit(it.trim(),listOf(n))}
        }
        val names=setOf("总睡眠","总睡眠时长","睡眠总时长","夜间睡眠","夜间睡眠时长","零星小睡","深睡","浅睡","快速眼动","清醒时长","清醒","睡眠评分","入睡时间","醒来时间","起床时间")
        val value=Regex("(?:\\d{1,2}\\s*小时(?:\\s*\\d{1,2}\\s*分钟)?|\\d{1,3}\\s*分钟|\\d{1,3}\\s*分?|\\d{1,2}:\\d{2})")
        // Pair only a unique plain value in the actual immediate container.
        // The filtered JSON parent can be a distant ancestor; original paths
        // retain the real hierarchy. Node order is never used as evidence.
        nodes.filter {it.text.trim() in names}.forEach {label ->
            val p=parent(label)
            val siblings=nodes.filter {parent(it)==p && it.text.isNotBlank()}
            val knownLabels=siblings.count {it.text.trim() in names}
            if(p.contains('/') && knownLabels==1 && siblings.size<=4 && !label.viewId.startsWith(PREFIX+"legend_")) {
                val values=nodes.filter {it.path!=label.path && parent(it)==p && it.description.isBlank() && value.matches(it.text.trim())}
                if(values.size==1)emit("${label.text.trim()} ${values.single().text.trim()}",listOf(label,values.single()))
            }
        }
        nodes.filter {it.viewId==PREFIX+"ring_chart_legend_title" && it.text.trim() in setOf("深睡","浅睡","快速眼动","清醒")}.forEach {label ->
            val values=nodes.filter {it.viewId==PREFIX+"ring_chart_item_value" && parent(it)==parent(label)}
            if(values.size==1)emit("${label.text.trim()} ${values.single().text.trim()}",listOf(label,values.single()))
        }
        nodes.filter {it.viewId==PREFIX+"day_time_period" && it.text.trim()=="夜间睡眠"}.forEach {label ->
            val values=nodes.filter {it.viewId==PREFIX+"common_sleep_sleep_time" && parent(it)==parent(label)}
            if(values.size==1)emit("夜间睡眠 ${values.single().text.trim()}",listOf(label,values.single()))
        }
        nodes.filter {it.viewId==PREFIX+"scoring_value"}.forEach {score ->
            val units=nodes.filter {it.viewId==PREFIX+"scoring_unit" && it.text.trim()=="分" && parent(it)==parent(score)}
            if(units.size==1 && score.text.trim().matches(Regex("\\d{1,3}")))emit("睡眠评分 ${score.text.trim()}分",listOf(score,units.single()))
        }
        return SleepReading(lines.distinct(),originals.mapValues {it.value.distinct()})
    }
}
