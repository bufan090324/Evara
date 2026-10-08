package cn.personal.phonebridge

// Remembered choice and live session are separate: remembering permission does
// not create or restore a control session after disconnect/process restart.
class CaptureConsent(private val read:()->Boolean, private val write:(Boolean)->Unit) {
    private var session=false
    val remembered get()=read()
    val allowed get()=session && remembered
    fun remember(value:Boolean) {write(value)}
    fun begin() {session=true}
    fun end() {session=false}
}
