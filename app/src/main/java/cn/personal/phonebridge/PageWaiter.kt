package cn.personal.phonebridge

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

// Read-only transition polling. Never swallows permission, lock or overlay errors.
object PageWaiter {
    suspend fun <T> await(
        description: String, read: () -> T, matches: (T) -> Boolean, key: (T) -> Any,
        now: () -> Long, pause: suspend (Long) -> Unit, check: () -> Unit,
        timeoutMs: Long = 8000, stableMs: Long = 400
    ): T {
        val started = now()
        var stableKey: Any? = null
        var stableSince = started
        while(now() - started < timeoutMs) {
            currentCoroutineContext().ensureActive(); check()
            val value = try { read() } catch(e: BridgeError) {
                if(e.code != "NO_WINDOW") throw e
                stableKey = null
                pause(200)
                continue
            }
            if(matches(value)) {
                val next = key(value)
                if(stableKey == next && now() - stableSince >= stableMs) return value
                if(stableKey != next) { stableKey = next; stableSince = now() }
            } else stableKey = null
            pause(200)
        }
        throw BridgeError("UNADAPTED_PAGE", "$description：页面未稳定、未识别或加载超时，需要用户处理")
    }
}
