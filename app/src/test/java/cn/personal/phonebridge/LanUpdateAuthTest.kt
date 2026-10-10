package cn.personal.phonebridge

import org.junit.Assert.*
import org.junit.Test

class LanUpdateAuthTest {
    @Test fun requestProofMatchesPcProtocolVector() {
        assertEquals("6c9eeb0b087db1a877351fdc688ea49ac8dba6e0bc788daea08b5243aadf04c9",LanUpdateAuth.proof("synthetic-token", "a".repeat(64), "/evara-update/manifest"))
    }
    @Test fun pathAndNonceAreBoundToProof() {
        val first=LanUpdateAuth.proof("token","a".repeat(64),"/evara-update/manifest")
        assertNotEquals(first,LanUpdateAuth.proof("token","b".repeat(64),"/evara-update/manifest"))
        assertNotEquals(first,LanUpdateAuth.proof("token","a".repeat(64),"/evara-update/chunk/"+"b".repeat(32)+"/0"))
        assertNotEquals(first,LanUpdateAuth.proof("other-token","a".repeat(64),"/evara-update/manifest"))
    }
    @Test fun arbitraryPathAndMalformedNonceCannotBeSigned() {
        for(path in listOf("/bridge","https://other.invalid/a","/evara-update/chunk/../0","/evara-update/chunk/"+"a".repeat(32)+"/-1")) {
            try{LanUpdateAuth.proof("token","a".repeat(64),path);fail()}catch(_:IllegalArgumentException){}
        }
        try{LanUpdateAuth.proof("token","bad","/evara-update/manifest");fail()}catch(_:IllegalArgumentException){}
    }
}
