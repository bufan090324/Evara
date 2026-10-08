package cn.personal.phonebridge

import org.junit.Assert.*
import org.junit.Test

class HealthLaunchTest {
    private data class Target(val pkg:String?)
    private fun fails(code:String,block:()->Unit) {try{block();fail("Expected $code")}catch(e:BridgeError){assertEquals(code,e.code)}}
    @Test fun correctPackageIsLaunchedExactlyOnce() {
        var sent=0;var checks=0
        HealthLaunch.once(resolve={Target("com.huawei.health")},packageOf={it.pkg},send={sent++},check={checks++})
        assertEquals(1,sent);assertEquals(2,checks)
    }
    @Test fun missingApplicationNeverSendsAction() {
        var sent=0
        fails("APP_NOT_FOUND"){HealthLaunch.once<Target>(resolve={null},packageOf={it.pkg},send={sent++},check={})}
        assertEquals(0,sent)
    }
    @Test fun anotherPackageCannotBeLaunched() {
        var sent=0
        fails("BAD_LAUNCH_TARGET"){HealthLaunch.once(resolve={Target("another.app")},packageOf={it.pkg},send={sent++},check={})}
        assertEquals(0,sent)
    }
    @Test fun unresolvedComponentNeverSendsAction() {
        var sent=0
        fails("BAD_LAUNCH_TARGET"){HealthLaunch.once(resolve={Target(null)},packageOf={it.pkg},send={sent++},check={})}
        assertEquals(0,sent)
    }
    @Test fun lockedOrOutsideScopeFailsBeforeResolution() {
        listOf("LOCKED","OUT_OF_SCOPE","OVERLAY").forEach {code ->
            var resolved=0;var sent=0
            fails(code){HealthLaunch.once(resolve={resolved++;Target("com.huawei.health")},packageOf={it.pkg},send={sent++},check={throw BridgeError(code,"暂停")})}
            assertEquals(0,resolved);assertEquals(0,sent)
        }
    }
    @Test fun sessionEndedDuringResolutionPreventsLaunch() {
        var active=true;var sent=0
        fails("STALE_SESSION"){HealthLaunch.once(resolve={active=false;Target("com.huawei.health")},packageOf={it.pkg},send={sent++},check={requireBridge(active,"STALE_SESSION","会话结束")})}
        assertEquals(0,sent)
    }
    @Test fun expiredRequestCannotSendLaunch() {
        val gate=Gate();val t=gate.accept("launch",100,0);var clock=0L;var sent=0
        fails("TIMEOUT"){HealthLaunch.once(resolve={clock=100;Target("com.huawei.health")},packageOf={it.pkg},send={sent++},check={gate.valid(t,clock)})}
        assertEquals(0,sent)
    }
    @Test fun rejectedLaunchIsNotRepeatedOrReplacedByClick() {
        var attempts=0
        try {HealthLaunch.once(resolve={Target("com.huawei.health")},packageOf={it.pkg},send={attempts++;throw SecurityException("blocked")},check={});fail("Expected rejection")}catch(_:SecurityException){}
        assertEquals(1,attempts)
    }
}
