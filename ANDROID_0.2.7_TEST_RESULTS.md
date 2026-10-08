# Android 0.2.7 验证

新增 12 个 OCR 证据规则测试，覆盖带标签时间、全角冒号、跨午夜钟点、裸刻度拒绝、清醒次数/比例排除、明确零时长、冲突时间、无障碍优先、相邻标签值、歧义/不同排拒绝、非法时间和失败说明。测试使用合成文字与坐标。

ML Kit 中文识别依赖采用官方随包模型版本 16.0.1，Android 11+ 满足 API 要求。依赖通过 Gradle lockfile 固定。截图调用仍使用现有无障碍截图及本次授权，新增识别不保存原图、不传输原图给电脑。

JVM 测试不运行实际 Android OCR 引擎。无连接的设备/模拟器，未验证安装、识别实际截图、手机引擎兼容性及完整任务。编译与规则测试通过不等同于 OCR 真机成功。

最终锁定依赖构建执行 testDebugUnitTest、assembleDebug、lintDebug 成功，140 个 JVM 测试通过，0 失败。Lint 0 错误、18 警告（包含引入 AndroidX 后新增的样式建议等）；没有将警告称为零。APK 版本 0.2.7 / versionCode 14、minSdk 30、targetSdk 35。apksigner 与 zipalign 检查通过，签名证书与既有版本一致。
