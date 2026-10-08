# Android 0.2.10 验证

本次为二维码显示尺寸和扫描方向修改。保留原有自动化测试，重新编译 APK 并核对最终合并清单的 CaptureActivity 方向，避免只修改源清单却未覆盖库设置。

152 个 JVM 测试通过，0 失败；testDebugUnitTest、assembleDebug、lintDebug 构建成功，Lint 0 错误、23 警告。APK versionName=0.2.10 / versionCode=17，apksigner 与 zipalign 检查通过，签名证书与旧版一致。直接检查 APK 内二进制清单：CaptureActivity.screenOrientation=0x1（portrait）。

无连接的真机/模拟器，未验证手机摄像头、旋转状态或小二维码识别距离。QR 编码、扫码解码、Root 和睡眠规则测试仍为自动化验证。
