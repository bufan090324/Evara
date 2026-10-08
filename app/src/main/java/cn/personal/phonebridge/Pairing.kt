package cn.personal.phonebridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

data class Pairing(val host:String,val port:Int,val name:String,val certificate:X509Certificate,val token:String,val raw:String) {
    val fingerprint get() = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") {"%02x".format(it)}
    companion object {
        fun parse(raw:String): Pairing {
            requireBridge(raw.toByteArray().size<=16000,"BAD_PAIRING","配对资料过大")
            val o=JSONObject(raw)
            requireBridge(o.getInt("protocol")==1,"BAD_PROTOCOL","协议版本不兼容")
            val host=o.getString("host");requireBridge(Rules.lan(host),"NOT_LAN","只接受 RFC1918 局域网 IPv4 地址")
            val port=o.getInt("port");requireBridge(port in 1024..65535,"BAD_PORT","端口范围 1024–65535")
            val token=o.getString("token");requireBridge(token.matches(Regex("[A-Za-z0-9_-]{32,128}")),"BAD_TOKEN","配对凭证格式无效")
            val name=o.getString("name");requireBridge(name.length in 1..80,"BAD_NAME","电脑名称过长或为空")
            val cert=CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(Base64.decode(o.getString("certificate_der"),Base64.DEFAULT))) as X509Certificate
            cert.checkValidity()
            val p=Pairing(host,port,name,cert,token,raw)
            requireBridge(p.fingerprint.equals(o.getString("certificate_sha256"),true),"BAD_FINGERPRINT","证书指纹与资料不符")
            requireBridge(cert.subjectAlternativeNames?.any { it[0]==7 && it[1]==host } == true,"BAD_SAN","证书必须含对应 IP 的 SAN")
            return p
        }
    }
    fun client():OkHttpClient {
        val store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null);store.setCertificateEntry("paired-computer",certificate)
        val factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(store)
        val delegate=factory.trustManagers.filterIsInstance<X509TrustManager>().single()
        val pinned=object:X509TrustManager {
            override fun getAcceptedIssuers()=delegate.acceptedIssuers
            override fun checkClientTrusted(chain:Array<X509Certificate>,authType:String)=delegate.checkClientTrusted(chain,authType)
            override fun checkServerTrusted(chain:Array<X509Certificate>,authType:String) {
                chain.forEach {it.checkValidity()};delegate.checkServerTrusted(chain,authType)
                requireBridge(chain.first().encoded.contentEquals(certificate.encoded),"CERT_CHANGED","电脑证书已改变，需删除后重新配对")
            }
        }
        val ssl=SSLContext.getInstance("TLS");ssl.init(null,arrayOf(pinned),null)
        return OkHttpClient.Builder().sslSocketFactory(ssl.socketFactory,pinned)
            .certificatePinner(CertificatePinner.Builder().add(host,CertificatePinner.pin(certificate)).build())
            .followRedirects(false).followSslRedirects(false).proxy(java.net.Proxy.NO_PROXY)
            .connectTimeout(10,TimeUnit.SECONDS).readTimeout(0,TimeUnit.SECONDS).pingInterval(15,TimeUnit.SECONDS).build()
        // OkHttp's default hostname verification remains enabled; SAN must match the literal IP.
    }
}
class PairStore(context:Context) {
    private val prefs=context.getSharedPreferences("pairing",Context.MODE_PRIVATE)
    private val alias="phonebridge.pairing.v1"
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore");store.load(null)
        (store.getKey(alias,null) as? SecretKey)?.let {return it}
        val generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }
    fun save(pairing:Pairing) {
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());c.updateAAD(alias.toByteArray())
        requireBridge(prefs.edit().putString("iv",Base64.encodeToString(c.iv,Base64.NO_WRAP)).putString("data",Base64.encodeToString(c.doFinal(pairing.raw.toByteArray()),Base64.NO_WRAP)).commit(),"STORAGE_FAILED","无法保存配对资料")
    }
    fun load():Pairing? {
        val data=prefs.getString("data",null) ?: return null
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(prefs.getString("iv",null),Base64.DEFAULT)));c.updateAAD(alias.toByteArray())
        return Pairing.parse(String(c.doFinal(Base64.decode(data,Base64.DEFAULT))))
    }
    fun delete() { prefs.edit().clear().commit() }
}
