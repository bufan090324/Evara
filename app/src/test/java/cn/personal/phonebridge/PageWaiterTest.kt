package cn.personal.phonebridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PageWaiterTest {
    private class Clock {var time=0L;suspend fun pause(ms:Long){time+=ms}}
    private fun fails(code:String,block:()->Unit) {try{block();fail("Expected $code")}catch(e:BridgeError){assertEquals(code,e.code)}}
    @Test fun waitsForStablePageInsteadOfFirstMatchingPackage()=runBlocking {
        val c=Clock()
        val result=PageWaiter.await<String>("桌面",read={if(c.time<200)"home:old" else "home:icon"},matches={it.startsWith("home:")},key={it},now={c.time},pause={c.pause(it)},check={})
        assertEquals("home:icon",result);assertEquals(600,c.time)
    }
    @Test fun temporaryNoWindowDoesNotEndFirstTask()=runBlocking {
        val c=Clock()
        val result=PageWaiter.await<String>("桌面",read={if(c.time<400)throw BridgeError("NO_WINDOW","切换") else "icon"},matches={it=="icon"},key={it},now={c.time},pause={c.pause(it)},check={})
        assertEquals("icon",result);assertEquals(800,c.time)
    }
    @Test fun singleLaunchAcrossHomeAndHealthTransition()=runBlocking {
        val c=Clock();var clicks=0
        val desktop=PageWaiter.await<String>("桌面",read={if(c.time<200)throw BridgeError("NO_WINDOW","过渡") else "home:icon"},matches={it=="home:icon"},key={it},now={c.time},pause={c.pause(it)},check={})
        assertEquals("home:icon",desktop)
        clicks++;val acceptedAt=c.time
        val health=PageWaiter.await<String>("运动健康",read={when {c.time<acceptedAt+200 -> "home:icon";c.time<acceptedAt+400 -> throw BridgeError("NO_WINDOW","过渡");else -> "health:sleep"}},matches={it=="health:sleep"},key={it},now={c.time},pause={c.pause(it)},check={})
        assertEquals("health:sleep",health);assertEquals(1,clicks)
    }
    @Test fun changingPageCannotBeTreatedAsStable() {
        val c=Clock()
        fails("UNADAPTED_PAGE"){runBlocking {PageWaiter.await<Long>("桌面",read={c.time},matches={true},key={it},now={c.time},pause={c.pause(it)},check={},timeoutMs=1000)}}
        assertEquals(1000,c.time)
    }
    @Test fun incorrectPackageHasBoundedWait() {
        val c=Clock()
        fails("UNADAPTED_PAGE"){runBlocking {PageWaiter.await<String>("桌面",read={"other"},matches={it=="home"},key={it},now={c.time},pause={c.pause(it)},check={},timeoutMs=1000)}}
        assertEquals(1000,c.time)
    }
    @Test fun overlayLockAndPermissionsAreNeverSwallowed() {
        listOf("OVERLAY","LOCKED","USER_REQUIRED","OUT_OF_SCOPE","ACCESSIBILITY_REQUIRED").forEach {code ->
            val c=Clock()
            fails(code){runBlocking {PageWaiter.await<String>("桌面",read={throw BridgeError(code,"暂停")},matches={true},key={it},now={c.time},pause={c.pause(it)},check={})}}
            assertEquals(0,c.time)
        }
    }
    @Test fun cancellationStopsPollingImmediately() {
        val c=Clock()
        try {runBlocking {PageWaiter.await<String>("桌面",read={"not_ready"},matches={false},key={it},now={c.time},pause={throw CancellationException("取消")},check={})};fail("Expected cancellation")}catch(_:CancellationException){}
        assertEquals(0,c.time)
    }
    @Test fun disconnectPreventsFurtherReads() {
        val c=Clock();var reads=0
        fails("STALE_SESSION"){runBlocking {PageWaiter.await<String>("桌面",read={reads++;"home"},matches={true},key={it},now={c.time},pause={c.pause(it)},check={if(c.time>=200)throw BridgeError("STALE_SESSION","断线")})}}
        assertEquals(1,reads)
    }
    @Test fun missingWindowResetsPreviousStability()=runBlocking {
        val c=Clock()
        PageWaiter.await<String>("桌面",read={if(c.time==200L)throw BridgeError("NO_WINDOW","过渡") else "home"},matches={true},key={it},now={c.time},pause={c.pause(it)},check={})
        assertEquals(800,c.time)
    }
    @Test fun noWindowTimeoutRemainsBounded() {
        val c=Clock()
        fails("UNADAPTED_PAGE"){runBlocking {PageWaiter.await<String>("桌面",read={throw BridgeError("NO_WINDOW","过渡")},matches={true},key={it},now={c.time},pause={c.pause(it)},check={},timeoutMs=1000)}}
        assertEquals(1000,c.time)
    }
}

