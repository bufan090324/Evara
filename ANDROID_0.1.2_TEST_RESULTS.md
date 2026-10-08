# 安卓 0.1.2 验证结果（2026-10-07）

- Gradle Wrapper 实际执行 `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`，BUILD SUCCESSFUL，33 秒。
- 安卓 JVM 测试 36 项全部通过：CoreTest 19、TlsTest 4、WindowPolicyTest 13，零失败/错误/跳过。本次新增 5 项 MIUI 底部窄条识别边界测试，包含用户截图元数据，非模拟真机界面成功读取。
- Lint 零错误，11 个警告（原有警告）。
- 包名 cn.personal.phonebridge，版本 0.1.2 / code 3，minSdk 30、target/compile SDK 35。
- apksigner 校验通过 APK v2 签名；zipalign -c 4 校验通过。证书 SHA-256：2d3414bafc167f73011cf65d636819e7b73495cf202a371ed203075fd59d0aae，与旧 APK 一致，支持覆盖安装。仍为 debug 签名。
- APK 和完整源码 SHA-256 见 安卓-0.1.2-SHA256SUMS.txt。源码包含 Gradle Wrapper、锁定依赖、Windows 桌面及 CLI 源码、构建/操作说明，排除运行时私钥和凭证、构建缓存。
- Windows 程序及协议未修改；未重复桌面全套测试，历史结果见 WINDOWS_TEST_RESULTS.md。
- 没有连接真机或模拟器。用户旧版截图证实了无线鉴权、无障碍、截图会话授权，并给出实际拦截窗口元数据。新版安装、页面读取、截图、通知栏/覆盖窗口拦截和睡眠任务待用户复测，未声称已成功读取真实健康数据。

已知限制：MIUI 特例仅限竖屏全宽底部不超过屏幕高度 3% 的无标题 com.miui.home 系统窗口；该窗口身份根据位置与元数据推断，非确认的系统 API 角色。其他形状/名称继续暂停。OCR 与自动日期选择仍未实现。
