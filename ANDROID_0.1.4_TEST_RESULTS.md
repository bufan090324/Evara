# 安卓 0.1.4 验证结果（2026-10-07）

- 最终 Gradle Wrapper 命令 `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` 实际通过，BUILD SUCCESSFUL，缓存准备后 17 秒。期间修正测试泛型推断编译错误后重新执行，最终无失败。
- 安卓 JVM 53 项测试全部通过：CoreTest 19、TlsTest 4、WindowPolicyTest 20、PageWaiterTest 10。零失败/错误/跳过。
- 新增测试使用与生产任务共用的 PageWaiter：首次匹配不足以继续，暂时 NO_WINDOW 恢复、桌面和 Health 模拟切换只发送一次启动、持续变化/错误包名/空窗口有界超时、窗口丢失重置稳定时间、锁屏/覆盖/权限立即暂停、取消和断线结束读取。使用虚拟时间，不是实际 AccessibilityService 或华为应用测试。
- Lint 零错误，11 个原有警告。
- 包名 cn.personal.phonebridge，版本 0.1.4 / code 5，minSdk 30，target/compile SDK 35。
- apksigner 验证 APK v2 签名通过；zipalign -c 4 通过。证书 SHA-256 2d3414bafc167f73011cf65d636819e7b73495cf202a371ed203075fd59d0aae，与旧版一致，支持覆盖安装，仍为 debug 签名。
- APK/源码哈希见 安卓-0.1.4-SHA256SUMS.txt。源码归档包含锁定依赖、Gradle Wrapper、桌面与 CLI 源码及说明；排除运行时私钥/凭证和构建缓存。
- Windows 和协议 v1 未修改，本次未重复桌面全套测试。
- 没有连接真机或模拟器。新版本安装、一次启动运动健康、无障碍 onInterrupt/onUnbind 实际生命周期、MIUI 后台设置效果及真实睡眠读取待用户复测。不能保证两次启动现象已彻底解决或无障碍权限不会关闭。

用户反馈首次只回主界面、没有显示失败；尚未取得完整任务状态和日志。代码修补针对已经发现的切换等待及回调语义问题。操作/后台排查/复测见 ANDROID_0.1.4_FIX.md。
