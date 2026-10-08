# Android 0.2.9 验证

新增五项 Root 测试：全新进程无需先手动检查、更新后新对象使用管理器已有授权、历史成功不绕过撤权、新操作超时不标记成功、执行前必须 UID=0。使用合成执行器，不运行真实 su。

新增桌面 Python QR 生成 → 安卓生产 ZXing 核心解码测试：验证二维码数据与原 JSON 完全相同，中文电脑名称及 UTF-8 正确，且不含私钥。样例仅为合成无效凭证，不是实际配对资料；该测试不启动摄像头或保存配对。

扫码库 com.journeyapps:zxing-android-embedded:4.3.0 官方要求 Android API 24+，本项目 minSdk 30 满足；使用 Activity Result ScanContract，扫码图像保存关闭。依赖及传递版本由 Gradle lockfile 固定。官方说明：https://github.com/journeyapps/zxing-android-embedded 。

最终执行 testDebugUnitTest、assembleDebug、lintDebug 构建成功，152 个 JVM 测试通过，0 失败。Lint 0 错误、21 警告。版本 0.2.9 / versionCode 16、minSdk 30、targetSdk 35。apksigner 与 zipalign 检查通过，签名证书与旧 APK 一致。

开发环境没有真机/模拟器，未验证相机授权和实际扫码、Root 管理器弹窗、真实强制停止或 APK 覆盖安装。Root 模拟测试不代表实际权限已授予。
