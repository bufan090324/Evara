# 安卓 0.1.1 验证结果（2026-10-07）

- 实际执行 Gradle Wrapper：`:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`，BUILD SUCCESSFUL，36 秒。
- 安卓 JVM 测试 31 项，全部通过：CoreTest 19、TlsTest 4、WindowPolicyTest 8，零失败/错误/跳过。新增覆盖常驻系统栏过滤、未知薄条仍拦截、通知栏展开、焦点/活动窗口、键盘/无障碍覆盖窗口、横屏导航栏裁剪、已内缩应用区域、异常边界。
- Lint：零错误，11 个警告（历史警告仍存在）。
- 包名 cn.personal.phonebridge，版本 0.1.1 / code 2，最低 Android 11 / SDK 30，target/compile SDK 35。
- apksigner verify --verbose --print-certs 通过 v2 签名校验；zipalign -c 4 通过。签名证书 SHA-256 为 2d3414bafc167f73011cf65d636819e7b73495cf202a371ed203075fd59d0aae，与 0.1.0 一致，可覆盖升级。仍为 debug 签名。
- APK 与源码归档 SHA-256 见本次交付的安卓-0.1.1-SHA256SUMS.txt。
- Windows 程序和通信协议未修改，本次未重复 Windows 全套测试；此前桌面测试结果见 WINDOWS_TEST_RESULTS.md。
- 没有连接模拟器或用户真机。本次未完成新版真机安装/权限/系统栏判断/真实睡眠读取验证。用户提供的旧版截图只确认 WSS 已认证连接、无障碍开启，以及 OVERLAY 拦截；未确认拦截窗口身份。

复测步骤和已知边界见 MIUI_FIX.md。若设备的系统栏名称或形状不在保守识别范围内，仍会暂停，需根据新增窗口诊断进一步适配，不将本次修补描述为 MIUI 真机适配已完成。
