# 安卓 0.2.2 验证结果（2026-10-07）

- 实际 Gradle Wrapper：`:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`，BUILD SUCCESSFUL，36 秒。
- JVM 共 93 项全部通过，零失败/错误/跳过；原 85 项 + HuaweiSleepEntryTest 8 项。
- 新测试使用用户真实首页 JSON 的五节点脱敏回放：正确选择报告标题，两个报告卡片仍暂停，其他卡片数据/禁用卡片/未知资源 ID/越界数据不会验证目标，节点路径和分辨率改变仍识别，通用单入口保留。只是实际结构回放，未执行该机点击或页面切换。
- 入口适配没有根据固定坐标、原始路径或健康数值作判断。测试资源保留必要结构并移除具体时长，没有完整原始任务或其他健康字段。
- Lint 零错误，9 个警告。
- aapt：cn.personal.phonebridge，versionName 0.2.2、versionCode 9，minSdk 30，target/compile SDK 35。
- apksigner v2 签名验证和 zipalign -c 4 均通过。签名证书 SHA-256 2d3414bafc167f73011cf65d636819e7b73495cf202a371ed203075fd59d0aae，与原版一致，支持覆盖升级。
- 协议 v1、Windows 0.2.1、本地 Root/始终允许配置未变；本次未重复电脑端测试。
- APK/完整源码哈希见 安卓-0.2.2-SHA256SUMS.txt。源码包括固定依赖、Gradle Wrapper 和 Android/电脑代码及说明，排除私钥、真实配对和构建缓存。

未连接真机/模拟器，尚未确认新版能在用户手机进入睡眠详情或读取真实睡眠字段。日期年份、总睡眠与夜间睡眠分离、跨页字段匹配等问题没有在此版扩展，仍需详情 JSON 适配；不以首页结构回放替代这些验证。操作与反馈方式见 ANDROID_0.2.2_UI.md。
