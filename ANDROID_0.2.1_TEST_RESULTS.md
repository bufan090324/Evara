# 安卓 0.2.1 验证结果（2026-10-07）

- Gradle Wrapper 实际执行 `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`，BUILD SUCCESSFUL，38 秒。
- JVM 共 85 项测试全部通过，零失败/错误/跳过：原 75 项 + EntrySelectorTest 10 项。
- 新测试覆盖同一卡片的父容器与标签、同一可点击容器的文字与图标、嵌套可点击节点、两张独立卡片、父容器中两处独立可点击入口、无关系的同位置节点、路径前缀边界、禁用节点、边界不包含以及空/重复节点。使用与任务相同的选择器，但数据为合成结构，未收到该机真实睡眠入口 JSON。
- Lint 零错误，9 个警告。
- aapt 验证包 cn.personal.phonebridge，versionName 0.2.1 / versionCode 8，minSdk 30、target/compile SDK 35。
- apksigner 验证 v2 签名通过，zipalign -c 4 通过。证书 SHA-256 2d3414bafc167f73011cf65d636819e7b73495cf202a371ed203075fd59d0aae，与旧版相同，可覆盖安装。
- 协议 v1、Windows 程序、Root/截图授权机制未变。本次未重复桌面全套测试。
- 完整源码含固定依赖、Gradle Wrapper、Android/Windows/CLI 源码及说明；哈希见 安卓-0.2.1-SHA256SUMS.txt。

开发环境没有连接真机/模拟器。新版未安装验证，不能宣称已点击该机唯一入口或成功读取真实睡眠数据。用户截图确认暂停发生在 AMBIGUOUS_TARGET 阶段；实际节点父子关系尚未取得。UI、Root、日期/字段与 OCR/自动日期选择的原限制保留。操作与反馈方式见 ANDROID_0.2.1_UI.md。
