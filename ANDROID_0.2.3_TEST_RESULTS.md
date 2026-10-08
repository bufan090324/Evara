# Android 0.2.3 验证结果

2026-10-08，Windows x64，JDK 17、Gradle 8.11.1、Android SDK 35。

- 执行 `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`，最终构建成功。
- 104 个 JVM 单元测试，0 失败；Lint 0 错误、9 警告。
- 新增 11 个睡眠证据测试：真实结构的脱敏回放、节点顺序反转、标签与数值关联、总睡眠/夜间/小睡区分、阶段合计、百分比与次数排除、评分 ID、歧义容器及图表图例错误关联防护。
- 原有连接、取消、超时、快照失效、窗口判断与入口适配测试保留。
- 使用两份用户原始 JSON 在本机运行生产解析器：第一份提取总睡眠、夜间、小睡、三个阶段和评分；日期不匹配，success=false。第二份缺日期、评分、深睡，success=false。未拼接两份数据。发行源码不记录个人原始健康数值。
- 源码中的测试样本保留节点结构但替换健康数值；不打包用户原始导出文件。
- APK 包名 cn.personal.phonebridge，版本 0.2.3 / versionCode 10，minSdk 30、targetSdk 35；apksigner v2 验证通过，zipalign 检查通过。签名证书 SHA-256 为 `2d3414bafc167f73011cf65d636819e7b73495cf202a371ed203075fd59d0aae`，与之前版本相同。

没有连接真机或模拟器。本次没有验证安装、真实无障碍读取、Root 执行或端到端睡眠任务；用户此前确认的进入睡眠板块不代表新版数据解析已真机验证。时间、年份及未暴露字段仍需后续真机适配。
