package cn.personal.phonebridge

/** Read-only OCR lease; never authorizes a click or renews an old action snapshot. */
internal data class OcrPageNode(val path:String,val id:String,val text:String,val description:String,val bounds:WindowBox,val enabled:Boolean,val selected:Boolean,val type:String,val clickable:Boolean,val scrollable:Boolean)
internal data class OcrPageState(val pkg:String,val window:Int,val bounds:WindowBox,val nodes:List<OcrPageNode>)
internal object OcrPageGuard {
    private const val CAROUSEL_TITLE="com.huawei.health:id/chart_title_date"
    fun matches(before:OcrPageState,after:OcrPageState,ageMs:Long):Boolean {
        if(ageMs !in 0..Rules.SNAPSHOT_MS || before.pkg!="com.huawei.health" || before.pkg!=after.pkg || before.window!=after.window || before.bounds!=after.bounds)return false
        // The weekly chart rotates its heading while the daily report stays still.
        // Exclude only this known heading, not dates, measurements or arbitrary nodes.
        fun stable(page:OcrPageState)=page.nodes.filter {it.id!=CAROUSEL_TITLE}
        return stable(before)==stable(after)
    }
}
