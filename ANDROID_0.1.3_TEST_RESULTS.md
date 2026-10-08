# 安卓 0.1.3 验证结果（2026-10-07）

- 实际执行 Gradle Wrapper `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`，BUILD SUCCESSFUL，37 秒。
- 安卓 JVM 测试 43 项全部通过：CoreTest 19、TlsTest 4、WindowPolicyTest 20，零失败/错误/跳过。新增 7 项测试覆盖无标题 SystemUI 系统栏和用户反馈元数据；不是实际手机读取测试。
- Lint 零错误，11 个原有警告。
- 包名 cn.personal.phonebridge，版本 0.1.3 / code 4，minSdk 30，target/compile SDK 35。
- APK v2 签名验证、zipalign -c 4 均通过。签名证书 SHA-256：2d3414bafc167f73011cf65d636819e7b73495cf202a371ed203075fd59d0aae，与旧版一致，支持覆盖安装，仍为 debug 签名。
- APK 与完整源码 SHA-256 见 安卓-0.1.3-SHA256SUMS.txt。源码归档包含 Gradle Wrapper、固定依赖、Windows 与 Android 源码及说明；排除运行时私钥/凭证和构建缓存。
- 协议 v1 与 Windows 0.2.0 保持兼容，电脑端未修改，本次未重复 Windows 全套测试。
- 用户 0.1.2 截图确认无线鉴权、无障碍和截图会话授权开启，仍在页面读取阶段被顶部无标题 SystemUI 窗口拦截。
- 开发环境没有连接真机/模拟器。新版安装、系统栏过滤、通知栏/键盘/悬浮窗保护、页面读取、截图与真实睡眠数据读取尚待用户复测。不能将元数据匹配测试描述为真机功能通过。

实现及复测见 MIUI_0.1.3_FIX.md。未知系统窗口仍暂停；空标题 SystemUI 窄条身份依据包名、类型、非活动非焦点、屏幕边缘形状推断，并裁除相关区域，未取得 OEM 专用角色信息。OCR 和自动日期选择仍未实现。
