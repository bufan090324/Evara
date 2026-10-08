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

internal class AppUpdater(private val context:Context) {
    private val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).connectTimeout(15,TimeUnit.SECONDS).readTimeout(15,TimeUnit.SECONDS).callTimeout(120,TimeUnit.SECONDS).build()
    @Volatile private var call:Call?=null
    var candidate:UpdateRelease?=null;private set
    fun source()=context.getSharedPreferences("updates",Context.MODE_PRIVATE).getString("source",DEFAULT_UPDATE_SOURCE) ?: DEFAULT_UPDATE_SOURCE
    fun save(url:String) {UpdateManifest.https(url);context.getSharedPreferences("updates",Context.MODE_PRIVATE).edit().putString("source",url).apply();candidate=null}
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
        candidate=null
        val result=response(source(),65536).use {response ->
            val stream=requireNotNull(response.body).byteStream()
            val bytes=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true){currentCoroutineContext().ensureActive();val n=stream.read(buffer);if(n<0)break;require(bytes.size()+n<=65536){"更新清单超过 64 KiB"};bytes.write(buffer,0,n)}
            UpdateManifest.parse(bytes.toByteArray())
        }
        candidate=result;result
    }
    suspend fun download(progress:(Long,Long)->Unit):File=withContext(Dispatchers.IO) {
        val item=requireNotNull(candidate){"请先检查新版"}
        val folder=File(context.cacheDir,"updates").apply {mkdirs()}
        val partial=File(folder,"download.part");val complete=File(folder,"Evara-update.apk")
        partial.delete();complete.delete()
        try {
            var total=0L;val digest=MessageDigest.getInstance("SHA-256")
            response(item.url,item.size,1800).use {response ->
                requireNotNull(response.body).byteStream().use {input -> partial.outputStream().use {output ->
                    val buffer=ByteArray(65536)
                    while(true) {
                        currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0)break
                        total+=n;require(total<=item.size){"下载大小与签名清单不符"}
                        digest.update(buffer,0,n);output.write(buffer,0,n);progress(total,item.size)
                    }
                }}
            }
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
