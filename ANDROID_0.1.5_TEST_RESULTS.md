# 安卓 0.1.5 验证结果（2026-10-07）

- Gradle Wrapper 实际执行 `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`，BUILD SUCCESSFUL，38 秒。
- 安卓 JVM 61 项测试全部通过：CoreTest 19、TlsTest 4、WindowPolicyTest 20、PageWaiterTest 10、HealthLaunchTest 8，零失败/错误/跳过。
- 新增测试使用生产 HealthLaunch 策略，验证正确包一次发送、未安装/无入口不发送、错误包/未解析组件拒绝、锁屏/范围外/覆盖窗口在解析前停止、解析期间会话结束或超时禁止发送、启动被拒绝不重试。平台 Intent 的实际解析/后台启动和 SharedPreferences 保存不是 JVM 测试涵盖范围。
- Lint 零错误、11 个原有警告。
- aapt 包信息：cn.personal.phonebridge，versionName 0.1.5 / versionCode 6，minSdk 30、target/compile SDK 35。打包后的 Manifest 查询声明包含 com.huawei.health。
- apksigner 验证 APK v2 签名通过；zipalign -c 4 通过。证书 SHA-256：2d3414bafc167f73011cf65d636819e7b73495cf202a371ed203075fd59d0aae，与旧版一致，可覆盖安装，仍为 debug 签名。
- APK/完整源码哈希见 安卓-0.1.5-SHA256SUMS.txt。源码含 Gradle Wrapper、固定依赖、Android/Windows/CLI 源码及文档，排除运行时私钥、配对资料和构建缓存。
- 协议 v1 和 Windows 程序未修改，本次未重复桌面全套测试。现有 PC 按钮名字未变，实际启动方式由手机设置决定。
- 无真机/模拟器连接。默认/桌面两种方式、模式保存、Android Intent 启动、MIUI 后台限制、实际睡眠入口及健康数据读取仍待用户复测。不能把启动策略的单元测试描述为成功打开真实运动健康。

操作说明及限制见 ANDROID_0.1.5_FIX.md。直接启动不绕过授权、登录或锁屏，不使用 root/ADB；OCR、自动日期选择、文字乱序适配不在本次改动中。
