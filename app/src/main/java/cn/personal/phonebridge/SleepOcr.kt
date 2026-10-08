package cn.personal.phonebridge

data class OcrLine(val text:String,val left:Int,val top:Int,val right:Int,val bottom:Int,val pass:String="full")
data class OcrCrop(val left:Int,val top:Int,val right:Int,val bottom:Int,val scale:Int=3) {
    fun line(text:String,l:Int,t:Int,r:Int,b:Int)=OcrLine(text,left+l/scale,top+t/scale,left+(r+scale-1)/scale,top+(b+scale-1)/scale,"focused_x$scale")
}
object SleepOcr {
    val keys=setOf("bedtime","wake_time","awake")
    private val labels=setOf("入睡","入睡时间","醒来","醒来时间","起床时间","清醒","清醒时长")
    fun normalize(text:String)=text.replace('：',':').replace('來','来').trim().trimStart('|','｜','•','·','丨',' ').trim()
    fun wakeFocus(lines:List<OcrLine>):OcrLine?=lines.filter {normalize(it.text).startsWith("醒来") || normalize(it.text).startsWith("起床时间")}.singleOrNull()
    fun crop(line:OcrLine,width:Int,height:Int):OcrCrop? {
        if(width<=0 || height<=0 || line.right<=line.left || line.bottom<=line.top)return null
        val padding=maxOf(32,(line.bottom-line.top)*2)
        val crop=OcrCrop((line.left-padding).coerceIn(0,width),(line.top-padding).coerceIn(0,height),(line.right+padding).coerceIn(0,width),(line.bottom+padding).coerceIn(0,height))
        if(crop.right<=crop.left || crop.bottom<=crop.top || (crop.right-crop.left).toLong()*(crop.bottom-crop.top)*9>2000000)return null
        return crop
    }
    fun supplement(report:SleepReport,lines:List<OcrLine>,snapshot:String,originX:Int,originY:Int):SleepReport {
        val normalized=lines.map {it.copy(text=normalize(it.text))}
        val originals=normalized.zip(lines).toMap()
        val evidence=linkedMapOf<String,MutableList<String>>()
        fun emit(text:String,source:List<OcrLine>) {evidence.getOrPut(text){mutableListOf()}.addAll(source.map {
            "snapshot=$snapshot, OCR=${originals[it]?.text ?: it.text}, pass=${it.pass}, bounds=[${it.left+originX},${it.top+originY},${it.right+originX},${it.bottom+originY}]"
        })}
        normalized.forEach {emit(it.text,listOf(it))}
        val diagnostics=normalized.filter {it.text.contains("入睡") || it.text.contains("醒来") || it.text.contains("清醒") || Regex("\\d{1,2}:\\d{2}").containsMatchIn(it.text)}.take(20)
            .flatMap {evidence[it.text] ?: emptyList()}.distinct()
        // Separate label/value lines are paired only when there is a unique
        // value on the same row immediately next to the label. No graph colors,
        // zero counts, percentages or naked times are interpreted as durations.
        normalized.filter {it.text in labels}.forEach {label ->
            val candidates=normalized.filter {v -> v!==label && v.left>=label.right && v.left-label.right <= (label.bottom-label.top)*6 &&
                minOf(v.bottom,label.bottom)>maxOf(v.top,label.top) &&
                (v.text.matches(Regex("(?:[01]?\\d|2[0-3]):[0-5]\\d")) || v.text.matches(Regex("(?:\\d{1,2}\\s*小时(?:\\s*\\d{1,2}\\s*分钟)?|\\d{1,3}\\s*分钟)"))) }
            if(candidates.size==1)emit("${label.text} ${candidates.single().text}",listOf(label,candidates.single()))
        }
        val timeLabel=Regex("^(?:入睡(?:时间)?|醒来(?:时间)?|起床时间)")
        val completeTime=Regex("^(?:入睡(?:时间)?|醒来(?:时间)?|起床时间)\\s*:?\\s*(?:[01]\\d|2[0-3]):[0-5]\\d$")
        val parsed=SleepParser.parse(evidence.keys.filter {!timeLabel.containsMatchIn(it) || completeTime.matches(it)},report.target)
        val fields=report.fields.toMutableMap()
        for(key in keys) {
            if(fields[key]?.state!="missing")continue
            val value=parsed.fields.getValue(key)
            fields[key]=value.copy(raw=if(value.raw.isEmpty())diagnostics else value.raw.flatMap {evidence[it] ?: listOf(it)}.distinct(),source="ocr",
                reason=if(value.state=="observed")"本地 OCR 识别文字，请与手机图表核对" else if(value.state=="conflict")value.reason else "本地 OCR 未找到明确标签及完整数字；小时必须两位，不补猜缺失数字。清醒次数不能换算为清醒时长")
        }
        return report.copy(fields=fields,success=report.success && fields.values.none {it.state=="conflict"})
    }
    fun unavailable(report:SleepReport,reason:String)=report.copy(fields=report.fields.mapValues {(key,value)->
        if(key in keys && value.state=="missing")value.copy(reason=reason) else value
    })
}
