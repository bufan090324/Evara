package cn.personal.phonebridge

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.json.JSONObject

internal class AppUpdater(private val context:Context) {
    private val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).connectTimeout(15,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).callTimeout(120,TimeUnit.SECONDS).build()
    @Volatile private var call:Call?=null
    var candidate:UpdateRelease?=null;private set
    private var lanPair:Pairing?=null
    private var lanCacheId:String?=null
    private var lanClient:OkHttpClient?=null
    private fun resetCandidate(){candidate=null;lanPair=null;lanCacheId=null;lanClient=null}
    fun source()=context.getSharedPreferences("updates",Context.MODE_PRIVATE).getString("source",DEFAULT_UPDATE_SOURCE) ?: DEFAULT_UPDATE_SOURCE
    fun save(url:String) {UpdateManifest.https(url);context.getSharedPreferences("updates",Context.MODE_PRIVATE).edit().putString("source",url).apply();resetCandidate()}
    fun cancel(){call?.cancel()}
    private fun response(initial:String,limit:Long,seconds:Long=45):Response {
        var url=initial
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds)
        repeat(6) {
            UpdateManifest.https(url)
            val remaining=deadline-System.nanoTime()
            require(remaining>0){"更新请求超时"}
            val next=client.newBuilder().callTimeout(remaining,TimeUnit.NANOSECONDS).build().newCall(Request.Builder().url(url).get().build());call=next
            val response=next.execute()
            if(response.code in listOf(301,302,303,307,308)) {
                val resolved=response.header("Location")?.let {response.request.url.resolve(it)}?.toString()
                response.close();require(resolved!=null){"更新重定向无效"};url=resolved
            }else {
                if(response.code!=200){response.close();throw IllegalArgumentException("更新服务 HTTP ${response.code}：检查地址和网络")}
                if((response.body?.contentLength() ?: -1)>limit){response.close();throw IllegalArgumentException("更新文件超过大小限制")}
                return response
            }
        }
        throw IllegalArgumentException("更新重定向过多")
    }
    suspend fun check():UpdateRelease=withContext(Dispatchers.IO) {
        resetCandidate()
        val result=response(source(),65536).use {response ->
            val stream=requireNotNull(response.body).byteStream()
            val bytes=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){currentCoroutineContext().ensureActive();val n=stream.read(buffer);if(n<0)break;require(bytes.size()+n<=65536){"更新清单超过 64 KiB"};bytes.write(buffer,0,n)}
            UpdateManifest.parse(bytes.toByteArray())
        }
        candidate=result;result
    }
    private fun pairedGet(pair:Pairing,path:String,proof:Pair<String,String>?=null):Response {
        require(PairStore(context).load()?.raw==pair.raw){"配对已改变，请重新检查电脑更新"}
        val request=Request.Builder().url("https://${pair.host}:${pair.port}$path")
        if(proof!=null)request.header("X-Evara-Nonce",proof.first).header("X-Evara-Proof",proof.second)
        val next=requireNotNull(lanClient){"请重新检查电脑更新"}.newCall(request.get().build());call=next
        val response=next.execute()
        if(response.code!=200) {
            val code=response.code;response.close()
            throw IllegalArgumentException(when(code) {
                401 -> "电脑更新鉴权失败，请核对配对资料"
                409 -> "电脑未准备缓存或缓存已改变，请在电脑重新准备后检查"
                429 -> "电脑更新通道忙，请稍后主动重试"
                else -> "电脑更新 HTTP $code：请确认 Windows 已启动服务并支持局域网更新"
            })
        }
        return response
    }
    private fun limitedBytes(response:Response,limit:Int):ByteArray {
        require((response.body?.contentLength() ?: -1)<=limit){"电脑更新响应过大"}
        val bytes=java.io.ByteArrayOutputStream()
        requireNotNull(response.body).byteStream().use {input ->
            val buffer=ByteArray(8192)
            while(true){val n=input.read(buffer);if(n<0)break;require(bytes.size()+n<=limit){"电脑更新响应过大"};bytes.write(buffer,0,n)}
        }
        return bytes.toByteArray()
    }
    private fun authenticatedGet(pair:Pairing,path:String):Response {
        val nonce=pairedGet(pair,LanUpdateAuth.PREFIX+"challenge").use {r ->
            val challenge=JSONObject(String(limitedBytes(r,1024),Charsets.UTF_8))
            require(challenge.getInt("protocol")==1){"电脑更新协议不兼容"}
            challenge.getString("nonce")
        }
        return pairedGet(pair,path,nonce to LanUpdateAuth.proof(pair.token,nonce,path))
    }
    suspend fun checkComputer():UpdateRelease=withContext(Dispatchers.IO) {
        resetCandidate()
        val pair=PairStore(context).load() ?: throw IllegalArgumentException("请先配对电脑")
        lanClient=pair.client().newBuilder().retryOnConnectionFailure(false).readTimeout(15,TimeUnit.SECONDS).callTimeout(30,TimeUnit.SECONDS).build()
        val result=authenticatedGet(pair,LanUpdateAuth.PREFIX+"manifest").use {r ->
            val id=r.header("X-Evara-Cache-Id") ?: ""
            require(id.matches(Regex("[a-f0-9]{32}"))){"电脑更新缓存标识无效"}
            val release=UpdateManifest.parse(limitedBytes(r,65536))
            currentCoroutineContext().ensureActive()
            lanCacheId=id;release
        }
        lanPair=pair;candidate=result;result
    }
    suspend fun download(progress:(Long,Long)->Unit):File=withContext(Dispatchers.IO) {
        val item=requireNotNull(candidate){"请先检查新版"}
        val folder=File(context.cacheDir,"updates").apply {mkdirs()}
        val partial=File(folder,"download.part");val complete=File(folder,"Evara-update.apk")
        partial.delete();complete.delete()
        try {
            var total=0L;val digest=MessageDigest.getInstance("SHA-256")
            val pair=lanPair
            if(pair!=null) {
                val identifier=requireNotNull(lanCacheId){"请重新检查电脑更新"}
                val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1800)
                partial.outputStream().use {output ->
                    while(total<item.size) {
                        currentCoroutineContext().ensureActive()
                        require(System.nanoTime()<deadline){"局域网更新下载超时"}
                        val expected=minOf(LanUpdateAuth.CHUNK.toLong(),item.size-total).toInt()
                        val bytes=authenticatedGet(pair,"${LanUpdateAuth.PREFIX}chunk/$identifier/$total").use {r ->
                            require(r.header("X-Evara-Cache-Id")==identifier){"电脑更新缓存已改变"}
                            limitedBytes(r,expected)
                        }
                        currentCoroutineContext().ensureActive()
                        require(bytes.size==expected){"电脑更新分块大小不符"}
                        output.write(bytes);digest.update(bytes);total+=bytes.size;progress(total,item.size)
                    }
                }
            } else response(item.url,item.size,1800).use {response ->
                requireNotNull(response.body).byteStream().use {input -> partial.outputStream().use {output ->
                    val buffer=ByteArray(65536)
                    while(true) {
                        currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break
                        total+=n;require(total<=item.size){"下载大小与签名清单不符"}
                        digest.update(buffer,0,n);output.write(buffer,0,n);progress(total,item.size)
                    }
                }}
            }
            currentCoroutineContext().ensureActive()
            if(pair!=null)require(PairStore(context).load()?.raw==pair.raw){"配对已改变，更新文件已丢弃"}
            require(total==item.size && digest.digest().joinToString(""){"%02x".format(it)}==item.sha){"APK SHA-256/大小校验失败，未安装"}
            validateApk(partial,item)
            require(partial.renameTo(complete)){"无法保存已校验更新文件"}
            complete
        }finally {partial.delete()}
    }
    fun validateApk(file:File,item:UpdateRelease) {
        val manager=context.packageManager
        val archive=requireNotNull(manager.getPackageArchiveInfo(file.absolutePath,PackageManager.GET_SIGNING_CERTIFICATES)){"文件不是有效 APK"}
        val installed=manager.getPackageInfo(context.packageName,PackageManager.GET_SIGNING_CERTIFICATES)
        require(archive.packageName==context.packageName && archive.longVersionCode==item.code && archive.versionName==item.version && archive.longVersionCode>installed.longVersionCode){"APK 包名/版本不匹配或不是升级"}
        require((archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE)<=Build.VERSION.SDK_INT){"更新 APK 不支持当前 Android 版本"}
        val actual=archive.signingInfo?.apkContentsSigners?.map {it.toCharsString()}?.toSet()
        val trusted=installed.signingInfo?.apkContentsSigners?.map {it.toCharsString()}?.toSet()
        require(!actual.isNullOrEmpty() && actual==trusted){"APK 签名与当前安装不一致，拒绝安装"}
    }
}
