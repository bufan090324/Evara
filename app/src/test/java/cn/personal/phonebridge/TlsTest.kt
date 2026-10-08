package cn.personal.phonebridge

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test

class TlsTest {
    private fun withServer(cert:HeldCertificate,block:(MockWebServer)->Unit) {
        val s=MockWebServer();val tls=HandshakeCertificates.Builder().heldCertificate(cert).build();s.useHttps(tls.sslSocketFactory(),false);s.enqueue(MockResponse().setBody("ok"));s.start();try{block(s)}finally{s.shutdown()}
    }
    private fun request(s:MockWebServer,pinned:HeldCertificate):String {
        val p=Pairing("127.0.0.1",s.port,"test",pinned.certificate,"unused","unused")
        val client=p.client()
        try{return client.newCall(Request.Builder().url("https://127.0.0.1:${s.port}/").build()).execute().use{it.body!!.string()}}finally{client.connectionPool.evictAll();client.dispatcher.executorService.shutdown()}
    }
    @Test fun correctCertificateAndSan(){val cert=HeldCertificate.Builder().commonName("bridge").addSubjectAlternativeName("127.0.0.1").build();withServer(cert){assertEquals("ok",request(it,cert))}}
    @Test fun wrongCertificateRejected(){val cert=HeldCertificate.Builder().commonName("bridge").addSubjectAlternativeName("127.0.0.1").build();val other=HeldCertificate.Builder().commonName("other").addSubjectAlternativeName("127.0.0.1").build();withServer(cert){try{request(it,other);fail("Wrong certificate trusted")}catch(_:javax.net.ssl.SSLException){}}}
    @Test fun wrongHostRejected(){val cert=HeldCertificate.Builder().commonName("bridge").addSubjectAlternativeName("192.168.1.1").build();withServer(cert){try{request(it,cert);fail("Wrong SAN trusted")}catch(_:javax.net.ssl.SSLException){}}}
    @Test fun expiredCertificateRejected(){val now=System.currentTimeMillis();val cert=HeldCertificate.Builder().commonName("bridge").addSubjectAlternativeName("127.0.0.1").validityInterval(now-86400000,now-10000).build();withServer(cert){try{request(it,cert);fail("Expired cert trusted")}catch(_:javax.net.ssl.SSLException){}}}
}
