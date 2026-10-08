package cn.personal.phonebridge

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.util.UUID

object RootCommands {
    fun script(ticket:String,nonce:String,expires:Long,user:Int,stop:Boolean):String {
        requireBridge(ticket.matches(Regex("/data/(?:user/[0-9]+|user_de/[0-9]+|data)/cn\\.personal\\.phonebridge/files/root-[a-f0-9-]+")) && nonce.matches(Regex("[a-f0-9-]{36}")) && user in 0..999999 && expires>0,"ROOT_PARAMETERS","Root 内部参数无效")
        val lease="[ \"$(/system/bin/cat '$ticket')\" = '$nonce' ] || exit 73; [ \"$(/system/bin/date +%s)\" -lt $expires ] || exit 74; "
        if(!stop)return lease+"/system/bin/id -u"
        return lease+"[ \"$(/system/bin/id -u)\" = 0 ] || exit 78; /system/bin/am force-stop --user $user com.huawei.health || exit 75; "+
            "names=$(/system/bin/ps -A -o NAME) || exit 77; "+
            "printf '%s\\n' \"${'$'}names\" | /system/bin/grep -E '^com[.]huawei[.]health(:.*)?$' >/dev/null; status=$?; "+
            "[ \"${'$'}status\" = 0 ] && exit 76; [ \"${'$'}status\" = 1 ] || exit 77; "+
            "echo PHONE_BRIDGE_STOPPED"
    }
}
class RootActions(private val execute:suspend (Boolean,Long)->Pair<Int,String>) {
    var verified=false;private set
    suspend fun request() {
        verified=false
        val result=execute(false,30000)
        verified=result.first==0 && result.second.trim()=="0"
        requireBridge(verified,"ROOT_REQUIRED","没有取得 Root 权限，请在手机 Root 管理器中允许Evara")
    }
    suspend fun forceStop() {
        verified=false
        val result=execute(true,10000)
        verified=result.first==0 && result.second.trim()=="PHONE_BRIDGE_STOPPED"
        requireBridge(verified,"ROOT_STOP_NOT_CONFIRMED","自动 Root 验证或停止未成功；请检查 Root 管理器是否允许Evara，不会继续启动")
    }
}
class RootControl(private val context:Context) {
    private val mutex=Mutex()
    private val actions=RootActions {stop,timeout->execute(stop,timeout)}
    val verified get()=actions.verified
    suspend fun request()=actions.request()
    suspend fun forceStop()=actions.forceStop()
    private suspend fun execute(stop:Boolean,timeout:Long):Pair<Int,String> {
        requireBridge(mutex.tryLock(),"BUSY","已有 Root 操作正在进行")
        try {
            return withContext(Dispatchers.IO) {
                val nonce=UUID.randomUUID().toString()
                val ticket=File(context.filesDir,"root-$nonce")
                var process:Process?=null
                try {
                    ticket.writeText(nonce)
                    val command=RootCommands.script(ticket.absolutePath,nonce,(System.currentTimeMillis()+timeout)/1000,android.os.Process.myUid()/100000,stop)
                    val running=try{ProcessBuilder("su","-c",command).redirectErrorStream(true).start()}catch(_:Exception){throw BridgeError("ROOT_REQUIRED","未找到可用 su，请检查 Root 管理器")}
                    process=running
                    withTimeout(timeout) {
                        while(running.isAlive){currentCoroutineContext().ensureActive();delay(100)}
                    }
                    val buffer=ByteArray(4096)
                    val out=StringBuilder()
                    running.inputStream.use {input -> var left=4096;while(left>0){val count=input.read(buffer,0,minOf(left,buffer.size));if(count<0)break;out.append(String(buffer,0,count,Charsets.UTF_8));left-=count}}
                    running.exitValue() to out.toString()
                } catch(e:TimeoutCancellationException) {
                    throw BridgeError("ROOT_TIMEOUT","Root 操作超时，已撤销操作凭据；请核对手机，不自动重试")
                } finally {
                    // A late su authorization cannot execute a deleted/expired
                    // lease, even when killing su does not kill its root child.
                    ticket.delete();process?.let {it.destroy();if(it.isAlive)it.destroyForcibly()}
                }
            }
        } finally {mutex.unlock()}
    }
}

