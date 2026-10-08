package cn.personal.phonebridge

import org.json.JSONObject
import java.net.URI
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal const val DEFAULT_UPDATE_SOURCE = "https://github.com/bufan090324/Evara/releases/latest/download/update.json"
internal data class UpdateRelease(val version:String,val code:Long,val url:String,val size:Long,val sha:String,val notes:String)
internal object UpdateManifest {
    fun https(url:String):String {
        require(url.length in 1..2048 && url.none {it.code<33}) {"更新地址无效"}
        val uri=try{URI(url)}catch(_:Exception){throw IllegalArgumentException("更新地址无效")}
        require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.userInfo==null && uri.fragment==null && uri.port!=0) {"更新地址必须为 HTTPS，不能包含凭证"}
        return url
    }
    fun parse(raw:ByteArray,key:String=UPDATE_PUBLIC_KEY):UpdateRelease {
        require(raw.size<=65536){"更新清单超过 64 KiB"}
        val data=try {
            val envelope=JSONObject(String(raw,Charsets.UTF_8))
            val payload=Base64.getDecoder().decode(envelope.getString("payload"))
            val signature=Base64.getDecoder().decode(envelope.getString("signature"))
            val publicKey=KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(key)))
            require(Signature.getInstance("SHA256withRSA").run {initVerify(publicKey);update(payload);verify(signature)})
            JSONObject(String(payload,Charsets.UTF_8))
        }catch(_:Exception){throw IllegalArgumentException("更新清单签名无效或损坏，未信任更新")}
        require(data.getInt("schema")==1 && data.getString("product")=="Evara"){"更新清单产品/协议不匹配"}
        val item=data.getJSONObject("releases").getJSONObject("android")
        val version=item.getString("version");require(version.matches(Regex("\\d{1,4}\\.\\d{1,4}\\.\\d{1,4}"))){"更新版本无效"}
        val code=item.getLong("version_code");require(code in 1..2100000000L){"APK versionCode 无效"}
        val size=item.getLong("size");require(size in 1..150L*1024*1024){"APK 更新大小无效"}
        val sha=item.getString("sha256");require(sha.matches(Regex("[a-f0-9]{64}"))){"更新哈希无效"}
        val notes=item.getString("notes");require(notes.length<=4000){"更新说明过长"}
        return UpdateRelease(version,code,https(item.getString("url")),size,sha,notes)
    }
}
