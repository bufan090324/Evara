package cn.personal.phonebridge

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RootActionsTest {
    @Test fun freshProcessDoesNotRequireManualCheckBeforeTask()=runBlocking {
        val calls=mutableListOf<Boolean>()
        val root=RootActions {stop,timeout->calls.add(stop);assertEquals(10000L,timeout);0 to "PHONE_BRIDGE_STOPPED"}
        assertFalse(root.verified);root.forceStop();assertTrue(root.verified);assertEquals(listOf(true),calls)
    }
    @Test fun rebootedAppCanUseExistingManagerGrant()=runBlocking {
        val first=RootActions {_,_->0 to "PHONE_BRIDGE_STOPPED"};first.forceStop()
        val afterUpdate=RootActions {_,_->0 to "PHONE_BRIDGE_STOPPED"}
        afterUpdate.forceStop();assertTrue(afterUpdate.verified)
    }
    @Test fun cachedSuccessNeverBypassesNewFailure()=runBlocking {
        var revoked=false
        val root=RootActions {_,_->if(revoked)78 to "" else 0 to "PHONE_BRIDGE_STOPPED"}
        root.forceStop();revoked=true
        try {root.forceStop();fail()}catch(e:BridgeError){assertEquals("ROOT_STOP_NOT_CONFIRMED",e.code)}
        assertFalse(root.verified)
    }
    @Test fun latePermissionTimeoutDoesNotMarkVerified()=runBlocking {
        val root=RootActions {_,_->throw BridgeError("ROOT_TIMEOUT","timeout")}
        try{root.forceStop();fail()}catch(e:BridgeError){assertEquals("ROOT_TIMEOUT",e.code)}
        assertFalse(root.verified)
    }
    @Test fun forceStopVerifiesUidBeforeStopping() {
        val script=RootCommands.script("/data/user/0/cn.personal.phonebridge/files/root-01234567-89ab-cdef-0123-456789abcdef","01234567-89ab-cdef-0123-456789abcdef",2000000000,0,true)
        assertTrue(script.indexOf("id -u")<script.indexOf("am force-stop"));assertTrue(script.contains("exit 78"))
    }
}
