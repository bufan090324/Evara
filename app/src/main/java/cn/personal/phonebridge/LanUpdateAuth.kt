package cn.personal.phonebridge

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object LanUpdateAuth {
    const val PREFIX="/evara-update/"
    const val CHUNK=1024*1024
    fun proof(token:String,nonce:String,path:String):String {
        require(nonce.matches(Regex("[a-f0-9]{64}"))){"电脑更新挑战无效"}
        require(path==PREFIX+"manifest" || path.matches(Regex("/evara-update/chunk/[a-f0-9]{32}/(?:0|[1-9][0-9]{0,9})"))){"电脑更新路径无效"}
        val mac=Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8),"HmacSHA256"))
        return mac.doFinal(("evara-update-v1:"+nonce+":"+path).toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it)}
    }
}
