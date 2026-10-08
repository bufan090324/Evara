package cn.personal.phonebridge

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class UpdateManifestTest {
    private val key=KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair()
    private fun payload():JSONObject=JSONObject().put("schema",1).put("product","Evara").put("releases",JSONObject().put("android",JSONObject()
        .put("version","1.0.1").put("version_code",20).put("url","https://example.invalid/Evara.apk").put("size",1234).put("sha256","a".repeat(64)).put("notes","合成发布说明")))
    private fun signed(data:JSONObject):ByteArray {
        val raw=data.toString().toByteArray()
        val signature=Signature.getInstance("SHA256withRSA").run{initSign(key.private);update(raw);sign()}
        return JSONObject().put("payload",Base64.getEncoder().encodeToString(raw)).put("signature",Base64.getEncoder().encodeToString(signature)).toString().toByteArray()
    }
    private fun parse(raw:ByteArray)=UpdateManifest.parse(raw,Base64.getEncoder().encodeToString(key.public.encoded))
    private fun rejected(block:()->Unit) {try{block();fail("should reject")}catch(_:IllegalArgumentException){}}
    @Test fun signedUpdateAccepted() {val result=parse(signed(payload()));assertEquals("1.0.1",result.version);assertEquals(20L,result.code);assertEquals(1234L,result.size)}
    @Test fun changedPayloadAndMissingSignatureRejected() {
        val envelope=JSONObject(String(signed(payload()))).put("payload",Base64.getEncoder().encodeToString("{}".toByteArray()))
        rejected{parse(envelope.toString().toByteArray())};rejected{parse("{}".toByteArray())}
    }
    @Test fun httpCredentialsAndOversizedManifestRejected() {
        listOf("http://example.invalid/a","https://user:pass@example.invalid/a","file:///a","https://example.invalid/a\n").forEach{url->rejected{UpdateManifest.https(url)}}
        rejected{parse(ByteArray(65537))}
    }
    @Test fun wrongProductRejected() {rejected{parse(signed(payload().put("product","Other")))}}
    @Test fun invalidFieldsRejected() {
        listOf("version" to "bad","sha256" to "bad","size" to 0,"size" to 999999999,"version_code" to 0,"url" to "http://example.invalid/a").forEach{(field,value)->
            val data=payload();data.getJSONObject("releases").getJSONObject("android").put(field,value)
            rejected{parse(signed(data))}
        }
    }
}
