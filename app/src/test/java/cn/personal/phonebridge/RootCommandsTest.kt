package cn.personal.phonebridge

import org.junit.Assert.*
import org.junit.Test

class RootCommandsTest {
    private val nonce="01234567-89ab-cdef-0123-456789abcdef"
    private val path="/data/user/0/cn.personal.phonebridge/files/root-$nonce"
    private fun fails(block:()->Unit) {try{block();fail("Expected ROOT_PARAMETERS")}catch(e:BridgeError){assertEquals("ROOT_PARAMETERS",e.code)}}
    @Test fun forceStopIsFixedToHealthAndCurrentUser() {
        val s=RootCommands.script(path,nonce,2000000000,0,true)
        assertTrue(s.contains("am force-stop --user 0 com.huawei.health"));assertFalse(s.contains("pm clear"));assertFalse(s.contains("killall"))
    }
    @Test fun operationLeaseAndExpiryPrecedeIrreversibleAction() {
        val s=RootCommands.script(path,nonce,2000000000,0,true)
        assertTrue(s.indexOf("cat '")<s.indexOf("am force-stop"));assertTrue(s.indexOf("date +%s")<s.indexOf("am force-stop"))
        assertTrue(s.contains("exit 73"));assertTrue(s.contains("exit 74"))
    }
    @Test fun processVerificationIsRequiredBeforeSuccessMarker() {
        val s=RootCommands.script(path,nonce,2000000000,0,true)
        assertTrue(s.contains("ps -A -o NAME"));assertTrue(s.contains("health(:.*)?$"));assertTrue(s.contains("exit 76"));assertTrue(s.contains("= 1 ] || exit 77"))
        assertTrue(s.indexOf("exit 77")<s.indexOf("echo PHONE_BRIDGE_STOPPED"))
    }
    @Test fun permissionProbeDoesNotStopAnything() {
        val s=RootCommands.script(path,nonce,2000000000,0,false)
        assertTrue(s.endsWith("/system/bin/id -u"));assertFalse(s.contains("force-stop"))
    }
    @Test fun injectedPathsAndNonceAreRejected() {
        fails{RootCommands.script("$path'; rm -rf /",nonce,2000000000,0,true)}
        fails{RootCommands.script(path,"'; echo bad",2000000000,0,true)}
        fails{RootCommands.script("/tmp/root-$nonce",nonce,2000000000,0,true)}
    }
    @Test fun invalidUserOrExpiryAreRejected() {
        fails{RootCommands.script(path,nonce,2000000000,-1,true)};fails{RootCommands.script(path,nonce,0,0,true)}
    }
}
