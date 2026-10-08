# Android 0.2.8 验证

新增六项规则测试：繁体标签/装饰符及原文证据、漏小时数字不补猜、局部识别提供完整数字、放大坐标回映、候选唯一性和裁剪边界、完整时间冲突。

用户报告5证实 0.2.7 引擎能在实际设备运行并读到入睡字段，醒来识别有数字缺失。原始健康 JSON 不打包到源码，新增测试采用文字/位置合成输入。

146 个 JVM 测试通过，0 失败；testDebugUnitTest、assembleDebug、lintDebug 构建成功。Lint 0 错误、19 警告。APK versionName 0.2.8 / versionCode 15、minSdk 30、targetSdk 35，apksigner 与 zipalign 检查通过，签名与既有版本一致。依赖锁定版本保持不变。

开发环境没有真机或模拟器，未运行新版真实裁剪图识别。JVM 规则测试不等同于 ML Kit 图像识别准确率验证。
