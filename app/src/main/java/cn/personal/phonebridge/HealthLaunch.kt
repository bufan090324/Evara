package cn.personal.phonebridge

// A narrow launch operation, not an arbitrary-package remote command.
object HealthLaunch {
    const val PACKAGE = "com.huawei.health"
    fun <T> once(resolve: () -> T?, packageOf: (T) -> String?, send: (T) -> Unit, check: () -> Unit) {
        check()
        val target=resolve() ?: throw BridgeError("APP_NOT_FOUND","未安装运动健康或没有可用启动入口，请在手机核对")
        requireBridge(packageOf(target)==PACKAGE,"BAD_LAUNCH_TARGET","启动入口不属于华为运动健康，已停止")
        check()
        send(target) // No repeat or automatic icon fallback after this point.
    }
}
