# 安卓 0.2.0 验证结果（2026-10-07）

## 已完成

- 实际 Gradle Wrapper 执行 `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`，最终 BUILD SUCCESSFUL，34 秒。初步 UI 和授权构建已通过；新增 Root 后修正 SDK 不公开的 UserHandle 标识访问，最终改为按 Android UID 用户范围推导并重新构建。
- JVM 测试共 75 项，零失败/错误/跳过：CoreTest 19、TlsTest 4、WindowPolicyTest 20、PageWaiterTest 10、HealthLaunchTest 8、CaptureConsentTest 8、RootCommandsTest 6。
- CaptureConsentTest 使用与生产相同的策略和可替换存储，验证首次不授权、仅记住不启动会话、主动会话授权、停止保留选择但撤销授权、模拟新实例不恢复会话、关闭立即撤销、会话内开启、旧停止实例不恢复授权。不是 Android SharedPreferences 真机写入/进程重启测试。
- RootCommandsTest 只验证生产命令构造：固定健康包和当前用户参数、操作凭据/期限先于停止、进程检查先于成功标记、su 检查不停止应用、路径/编号注入及非法参数拒绝。没有执行真实 su、am force-stop 或设备进程检查；未测试 Root 管理器交互和取消时的真实子进程行为。
- Lint 零错误，9 个警告；界面使用原生 Kotlin/Android 控件、dp/sp、滚动内容、系统栏 inset；没有新增依赖。
- aapt：cn.personal.phonebridge，versionName 0.2.0、versionCode 7，minSdk 30、target/compile SDK 35。
- apksigner v2 验证通过，zipalign -c 4 通过。签名证书 SHA-256 为 2d3414bafc167f73011cf65d636819e7b73495cf202a371ed203075fd59d0aae，与旧版一致，可覆盖安装，仍为 debug 签名。
- WSS 协议 v1 和 Windows 0.2.0 未修改，本次未重复桌面全套测试。新增本地配置不会增加远程任意命令能力。
- APK/源码 SHA-256 见 安卓-0.2.0-SHA256SUMS.txt。源码包含 Gradle Wrapper、固定依赖、Android/Windows/CLI 源码及说明，排除真实配对、私钥、构建缓存。

## 未验证

`adb devices` 没有连接设备，也没有安装的模拟器系统镜像。新版未安装启动验证，未进行实际屏幕渲染/视觉检查、不同分辨率/字体缩放、三页交互、SAF 导出或 Android 设置持久性测试。不能把编译和静态检查描述为界面真机验收通过。

Root 授权、su/ps 命令兼容性、强制停止与重新打开、取消/断线时真正的子进程终止、多用户和 MIUI 行为未经真机验证。命令凭据和时间检查阻止尚未开始的撤销/过期操作，已经开始的强制停止无法撤回；不保证所有 OEM 特殊进程名都被列出，也不保证最近任务卡片立即消失。

用户此前反馈旧版直接打开路径已运行成功，本次 UI/持久授权/Root 新版本不能沿用该反馈宣称已验证。真实健康字段读取、OCR、自动日期选择、乱序字段适配仍存在原限制。

功能保留清单、首次授权和 Root 设置、详细手动测试见 ANDROID_0.2.0_UI.md。
