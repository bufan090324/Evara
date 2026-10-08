package cn.personal.phonebridge

import java.time.LocalDate

data class CalendarText(val path:String,val text:String,val description:String,val selected:Boolean,val top:Int,val bottom:Int)
data class CalendarReportDate(val date:String,val evidence:List<String>)
data class CalendarControl(val path:String,val id:String,val description:String,val type:String,val enabled:Boolean,val clickable:Boolean)
object CalendarDate {
    fun closePath(nodes:List<CalendarControl>):String?=nodes.filter {
        it.id=="com.huawei.health:id/sheet_indicate_container" && it.description.trim()=="关闭" && it.type=="android.widget.Button" && it.enabled && it.clickable
    }.singleOrNull()?.path
    fun selectedHeading(nodes:List<CalendarText>):String?=nodes.filter {it.selected}.flatMap {listOf(it.text,it.description)}
        .map {it.trim()}.filter {it.matches(Regex("\\d{1,2}月\\d{1,2}日"))}.distinct().singleOrNull()
    fun sameHeading(first:String,second:String):Boolean {
        fun normalized(s:String)=s.trim().replace(Regex("\\s*(?:周[一二三四五六日天]|星期[一二三四五六日天])$"),"")
        return normalized(first)==normalized(second)
    }
    private val full=Regex("(?<!\\d)(20\\d{2})[年/-](\\d{1,2})[月/-](\\d{1,2})日?(?!\\d)")
    private val month=Regex("^(20\\d{2})年\\s*(\\d{1,2})月$")
    fun fullHeading(text:String):String? {
        val heading=text.trim().replace(Regex("\\s*(?:周[一二三四五六日天]|星期[一二三四五六日天])$"),"")
        val match=full.matchEntire(heading) ?: return null
        return date(match.groupValues[1],match.groupValues[2],match.groupValues[3])
    }
    private fun date(y:String,m:String,d:String):String?=try {LocalDate.of(y.toInt(),m.toInt(),d.toInt()).toString()}catch(_:Exception){null}
    fun resolve(nodes:List<CalendarText>,reportHeading:String):CalendarReportDate? {
        val md=Regex("^(\\d{1,2})月(\\d{1,2})日(?:\\s*(?:周[一二三四五六日天]|星期[一二三四五六日天]))?$").matchEntire(reportHeading.trim()) ?: return null
        val expectedMonth=md.groupValues[1].toInt();val expectedDay=md.groupValues[2].toInt()
        val selected=nodes.filter {it.selected}
        val explicit=selected.flatMap {n -> listOf(n.text,n.description).mapNotNull {s -> full.find(s)?.let {m->date(m.groupValues[1],m.groupValues[2],m.groupValues[3])}?.let {it to n}}}.distinctBy {it.first}
        if(explicit.size>1)return null
        if(explicit.size==1) {
            val (value,node)=explicit.single();val d=LocalDate.parse(value)
            if(d.monthValue!=expectedMonth || d.dayOfMonth!=expectedDay)return null
            return CalendarReportDate(value,listOf("已选中日期 node=${node.path}, text=${node.text}, description=${node.description}"))
        }
        // Associate a selected day with its visible month section using current
        // node bounds. Never use a fixed screen coordinate or the first year.
        val cell=Regex("^(\\d{1,2})月(\\d{1,2})日$")
        val days=nodes.filter {n -> n.top>=0 && n.bottom>n.top &&
            ((n.selected && n.text.trim().toIntOrNull()==expectedDay) || listOf(n.text,n.description).any {s ->
                cell.matchEntire(s.trim())?.let {it.groupValues[1].toInt()==expectedMonth && it.groupValues[2].toInt()==expectedDay}==true
            })}
        if(days.count {it.selected && it.text.trim().toIntOrNull()==expectedDay}>1)return null
        val headers=nodes.mapNotNull {n -> listOf(n.text,n.description).firstNotNullOfOrNull {s->month.matchEntire(s.trim())}?.let {Triple(n,it.groupValues[1],it.groupValues[2])}}
            .filter {it.first.bottom>it.first.top}
        val candidates=days.mapNotNull {day ->
            val above=headers.filter {it.first.bottom<=day.top}
            val nearest=above.maxOfOrNull {it.first.bottom} ?: return@mapNotNull null
            val closest=above.filter {it.first.bottom==nearest}
            if(closest.map {it.second to it.third}.distinct().size!=1)return null
            val header=closest.first()
            if(header.third.toInt()!=expectedMonth)return@mapNotNull null
            val value=date(header.second,header.third,expectedDay.toString()) ?: return@mapNotNull null
            CalendarReportDate(value,listOf("年月 node=${header.first.path}, text=${header.first.text}, description=${header.first.description}","与报告月日对应的日历日期 node=${day.path}, text=${day.text}, description=${day.description}, selected=${day.selected}"))
        }
        // A uniquely dated cell can supply the year even if the custom View
        // does not expose selected=true. It must match both month and day of
        // the unchanged report heading; multiple years remain ambiguous.
        if(candidates.map {it.date}.distinct().size!=1)return null
        return CalendarReportDate(candidates.first().date,candidates.flatMap {it.evidence}.distinct())
    }
}
