# Evara 1.0.1

个人使用的 Android 11+ 与 Windows x64 局域网客户端，针对华为运动健康中文版的单日报告。Android 不包含模型密钥；可选 AI 服务由 Windows 调用。

[下载安装包](https://github.com/bufan090324/Evara/releases/latest) · [当前能力清单](FEATURES.md) · [通信协议](PROTOCOL.md) · [AI 操作](AI_GUIDE.md) · [更新与发布](UPDATES.md) · [验证记录](TEST_RESULTS_1.0.1.md)

## 开始使用

1. Windows 完整解压便携包，双击 `Evara.exe`，无需安装 Python。
2. 两端连接同一可互通局域网。电脑选择合适 IPv4、生成配对并启动服务；手机扫码或粘贴资料，独立核对完整证书指纹。
3. 手机按系统设置开启无障碍与必要通知权限，解锁并主动开启控制会话；按需截图选择可保存。
4. 在电脑读取界面或截图；睡眠报告读取手机当前详情，历史日期由用户在手机选择。缺失、冲突或日期不确定需核对，不能当作已确认。
5. 可选 AI：在 Windows 保存服务地址、模型和密钥，独立检测四项能力。发送手机健康数据前勾选授权；云端处理会离开局域网。
6. 更新页主动检查并下载。Windows 解压验证后确认启动新版本；Android 由系统安装器确认覆盖安装。保持签名、包名和用户资料目录不变。

Windows 用户资料保留在 `%LOCALAPPDATA%\PhoneBridge`；名称用于旧版兼容。不要删除资料目录，否则配对与 AI 设置会丢失。安卓包名仍是 `cn.personal.phonebridge`。遇到网络阻断，检查专用网络防火墙和路由器 AP 隔离；程序不自动改防火墙。

## 构建与测试

Android：JDK 17，SDK Platform 35 / Build Tools 35.0.0，设置 `JAVA_HOME`、`ANDROID_HOME`，在工程根目录运行：

```powershell
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

APK 在 `app/build/outputs/apk/debug/app-debug.apk`。升级必须使用同一签名密钥，私钥不在源码中。Gradle Wrapper 与依赖锁定已包含。

Windows：Python 3.13 x64，在工程根目录运行：

```powershell
python -m venv .venv
.venv/Scripts/python.exe -m pip install -r bridge/requirements-build.txt
.venv/Scripts/python.exe -m unittest discover -s bridge -p "test*.py"
.venv/Scripts/python.exe -m PyInstaller --noconfirm --clean bridge/PhoneBridge.spec
.venv/Scripts/python.exe bridge/package_windows.py --dist dist/Evara --output dist
```

保留整个 `dist/Evara` 目录。命令行桥仍可用：`bridge.py init --ip <局域网IPv4> --name <电脑名称>`、`bridge.py serve`，使用同一 WSS 协议；不通过终端模拟驱动桌面程序。

历史记录仅用于追溯；旧 README 保存在 `docs/history/README-before-1.0.1.md`。当前功能与限制只以 `FEATURES.md` 为准，实际测试结论只以对应版本验证记录为准。
