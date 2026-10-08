# Evara 1.0.0

个人使用的 Android / Windows 局域网手机控制客户端。**两端当前版本统一为 1.0.0**，保留安卓包名、签名和既有配对；Windows 可选 AI 对话支持标准 Responses API 与 DeepSeek 官方兼容配置。

- [GitHub 项目](https://github.com/bufan090324/Evara)
- [最新安装包与源码](https://github.com/bufan090324/Evara/releases/latest)
- [在线更新操作与发布说明](UPDATES.md)
- [AI 配置与授权](AI_GUIDE.md)
- [1.0.0 测试结果与限制](TEST_RESULTS_1.0.0.md)

Windows 下载并完整解压便携包，双击 Evara.exe；Android 下载 APK 覆盖安装。首次选择局域网 IPv4，在电脑生成配对，手机扫码并独立核对证书指纹，然后启动电脑服务并在手机主动开启控制会话。日常无需 Python、PowerShell、USB 或 ADB。两端“软件更新”已内置此仓库签名清单地址，主动检查、下载并确认更新。

下面保留原工程的构建和调试说明；历史版本的测试记录不代表新功能已通过真机验证。

个人使用的 Android 11+ 原生 Kotlin 客户端和最小 Windows WSS 测试桥。APK 不包含 OpenAI API、API Key、云数据库或第三方登录。仅处理桌面与 `com.huawei.health` 中文版的单日报告。

开发环境没有直接连接真机/模拟器。用户已反馈此前睡眠读取成功；0.2.9 相机扫码和更新后实际 Root 流程仍需真机验证。当前验证见 [ANDROID_0.2.9_TEST_RESULTS.md](ANDROID_0.2.9_TEST_RESULTS.md)，[TEST_RESULTS.md](TEST_RESULTS.md) 为首版历史记录。不同运动健康版本仍需适配。

## Windows 测试桥

需要 Python 3.13（本次测试版本；3.11+ 理论兼容，其他版本未测）。在工程根目录 PowerShell 运行：

```powershell
python -m venv bridge/.venv
bridge/.venv/Scripts/python.exe -m pip install -r bridge/requirements.txt
ipconfig
# 替换为电脑实际局域网 IPv4；电脑使用有线网口也可以。
bridge/.venv/Scripts/python.exe bridge/bridge.py init --ip 192.168.1.5 --name "我的电脑"
bridge/.venv/Scripts/python.exe bridge/bridge.py serve
```

`bridge/private/pairing.json` 包含敏感配对凭证，`key.pem` 是电脑 TLS 私钥。只在自己的电脑保存，不上传、不加入版本控制、不把内容发给云端 AI。建议将该目录放在自己的 Windows 账户私有目录并限制其他用户读取；桥不会自动更改账户 ACL。终端只打印公开证书指纹，不打印凭证。证书默认 365 天，过期或电脑 IP 改变需在新目录重新生成、删除手机旧配对后重新绑定。`init` 不覆盖已有资料。

Windows 防火墙如阻止连接，只允许 Python 在专用网络访问。需要手动建规则时可在管理员 PowerShell 运行（按实际端口/程序路径设置）：

```powershell
New-NetFirewallRule -DisplayName "Evara个人局域网测试" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8765 -RemoteAddress LocalSubnet -Profile Private
```

服务绑定明确的 RFC1918 IPv4，拒绝外部来源和第二台手机。不监听所有网卡。手机与电脑需在同一路由器且无 AP 隔离；不设置路由器公网映射，不使用代理转发。首版不支持 IPv6、域名、mDNS、扫码。

## 手机安装与授权

1. 把 `Evara-debug.apk` 传到手机，使用系统安装器安装；按系统要求允许该文件来源安装。日常操作不需要 USB/ADB/root。
2. 开启Evara，点击“打开无障碍设置”，由你手动开启“Evara”服务。侧载应用在 Android 13+ 可能还要求在应用信息右上角“允许受限设置”；仅在你核对来源和 APK 后按系统提示操作，不提供绕过。
3. Android 13+ 点击“开启通知权限”，允许持续通知，以保留停止入口。Android 11/12 不需要通知运行时权限。
4. 把电脑 `pairing.json` 完整内容粘贴到手机的“粘贴/编辑配对资料”，或使用“手动填写配对”。手动模式所有六个字段都必须填写，协议默认为 1。
5. 在**电脑终端独立核对完整 SHA-256 证书指纹**，与手机首次信任对话框一致后勾选确认并保存。不能只相信同一份粘贴资料中的指纹。手机号、账户密码无需填写。
6. 解锁手机、关闭分屏/悬浮窗，确保华为运动健康已登录并正常同步（由你自行核对；客户端不声称已同步）。将“运动健康”图标放到当前主桌面。
7. 在首页或设置页开启“始终允许按需截图及坐标手势”，会保存选择，不必每次勾选；首次未选择时默认关闭。首页点击“开启控制会话”，应用退到后台；如仍停在Evara界面，请手动返回桌面。电脑输入 `status` 确认已认证。
8. 通知中的“停止会话”或应用底部“停止控制”会断开、作废队列、取消任务、撤销当前授权。已保存的始终允许选择保留，关闭该开关即可撤销并保存为不允许。停止后不会自动恢复控制；重新连接必须由手机用户主动操作，旧点击不会重发。
9. 需要强制停止运动健康后再打开时，在“设置与调试 → Root 重启运动健康”主动申请并检查 Root，检查通过会启用重启模式。默认不开启，不影响普通无 Root 路径；Evara进程重启后需再次检查。Root 方式及新版界面尚需真机复测，详见 [0.2.0 操作说明](ANDROID_0.2.0_UI.md)。

Android 11+ 无障碍截图 API 是唯一截图机制，不使用 MediaProjection、持续录屏或后台截图循环。截图前后检查允许应用、窗口、锁屏、会话与快照，裁剪到应用根窗口范围；无法保证识别所有厂商非无障碍覆盖层，实测见设备清单。系统禁止截图时返回失败，不尝试绕过。默认手机和桥均不保存图片。电脑可显式用 `serve --save-screenshots .\exports` 开启本地导出；此目录含敏感健康信息。截图与结构首先发给配对电脑，电脑交给云端 AI 后相关内容会离开局域网。

## 执行单日报告

桥终端输入（替换为你要读取的实际日期）：

```text
status
sleep 2026-10-06
task
```

`sleep` 立即返回 `task_id`，用 `task` 查询。任务最多 90 秒：检查→按包名直接打开运动健康→确认应用和睡眠入口→等待详情→日期核对→提取校验。手机端关闭“直接打开运动健康”后才改为返回桌面、有限查找图标并点击。界面等待每 200 ms 检查，并要求连续至少 400 ms 稳定，普通页面最多 8 秒，打开应用及加载睡眠入口最多 20 秒，不用固定坐标硬编码流程。桌面方式只尝试最多三次有语义的滚动；若桌面未暴露滚动能力，要求把图标移到当前页。**两种启动方式均需真机复测，发起启动不等于页面打开成功。**

基础标签规则采用唯一“运动健康/华为运动健康”图标、“睡眠”入口及中文阶段标签。标题、阶段和完整日期不够明确时返回 `needs_user`，可能并未进入详情。此时不要连续发送点击猜测位置。

首版仅支持明确 `YYYY-MM-DD`；不提供“昨晚”映射。以手机时区显示和核对报告，跨午夜不根据入睡/起床时间反推报告日期。只显示月日、没有可核对年份的页面不会成功。**自动日期控件选择未完成。** 用户手动选好日期并留在睡眠详情后输入：

```text
extract 2026-10-06
task
```

当前详情提取是独立新任务，替换之前暂停结果；并非恢复旧点击。日期仍无年份时保持不确定，需要后续针对真实日期控件适配。完成后不离开详情。

`ui` 返回当前有效快照；`shot` 按需截图；`cancel` 取消任务；`home`/`back` 执行受范围限制的系统动作。具体节点和手势示例见 [PROTOCOL.md](PROTOCOL.md)。Evara自身界面不属于远程范围，查看结果后需手动回到桌面/运动健康。任务期间手动控制命令返回 `TASK_BUSY`，`status`、`task`、`cancel` 不受长任务阻塞。

## 数据与适配入口

解析器仅接受带明确标签的时长、评分、时间；相邻标签/值仅在相同父节点、直接邻接且值格式唯一时合并。返回数值、单位、原始文字、来源和确定性状态；缺失不填零，百分比不当作分钟，午睡不参与夜间总时长。阶段合计与总时长冲突时不完成。摘要卡片缺少详情标题/至少两种阶段标签时不完成。局部或无法关联的值可能被保守地留空。

真实版本适配入口：

- `BridgeAccessibility.kt`：窗口读取、可见节点过滤、`UiSnapshot.evidenceLines()` 标签与值的证据关联。
- `SleepTask.kt`：桌面、首页、详情状态转换与唯一入口匹配。新增日期选择必须基于真实控件并有后置验证。
- `Core.kt / SleepParser`：字段标签、日期证据和一致性判断。应把脱敏真机页面样本变成回归测试，勿用固定坐标推测数字。
- 应用“读取当前界面元素”：主动退后台 2 秒读取最近允许页面，然后回到应用显示结构。调试结构不自动落盘，文本可手动复制；包含真实健康文字，分享前脱敏。日志导出不含页面、证书、凭证或截图，只有状态和错误码。

0.2.7 已实现三个缺失字段的本地中文 OCR 备用路径，依赖本次截图授权，实际识别仍需真机验证，见 ANDROID_0.2.7_UI.md。自动云端视觉解析未实现；电脑可按需取得截图供自己的 AI 识别。APK 不连接云端模型。原生鸿蒙无法运行 Android APK 时明确不兼容；兼容层、HarmonyOS 各版本和厂商后台限制均未经验证。

## 从源码构建

锁定：AGP 8.9.2、Gradle Wrapper 8.11.1、Kotlin 2.1.20、JDK 17、compile/target SDK 35、min SDK 30、Build Tools 35.0.0、OkHttp 4.12.0、coroutines 1.9.0。`gradle.lockfile` 锁定解析的依赖；Wrapper JAR 来自 Gradle 官方源并核对官方 SHA-256，发行 ZIP 配置 SHA-256 校验。Python 依赖也固定版本。

安装 JDK 17 和官方 Android SDK command-line tools，将平台 35 与 Build Tools 35.0.0 安装到自己的 SDK 路径，并接受对应 SDK 许可。PowerShell：

```powershell
$env:JAVA_HOME = 'C:\你的路径\jdk-17'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$env:ANDROID_HOME = 'C:\你的路径\android-sdk'
sdkmanager.bat --licenses
sdkmanager.bat 'platforms;android-35' 'build-tools;35.0.0' 'platform-tools'
# 在工程根目录运行；首次构建需要访问 Google Maven / Maven Central / Gradle。
.\gradlew.bat --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

可在 `local.properties` 设置 `sdk.dir=C:/你的路径/android-sdk`，不要提交机器路径。构建产物 `app/build/outputs/apk/debug/app-debug.apk`；默认由本机 Android debug keystore 签名，不包含发行密钥。换机器重建的签名可能不同，升级安装前核对签名，必要时卸载旧版（会删除配对）。这是可侧载的 debug APK，不用于应用商店发行。

本次 Codex Windows 命令环境的 JDK Unix-domain selector 初始化曾报 `Unable to establish loopback connection / Invalid argument: connect`。用独立的 `Selector.open()` 程序复现后，在**当前进程**设置 `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=<工作目录内一个不存在的目录>`，使 JDK 内置实现回退 TCP，随后成功运行 Gradle。普通 Windows 终端无需预设这项环境调整；它不是 APK 的配置或网络协议改动，不应写进项目全局 Gradle 配置。初始 Adoptium 下载返回 403，因此改用 Azul 官方 CDN 的 JDK 17。

运行桥测试：

```powershell
Push-Location bridge
.venv/Scripts/python.exe -m unittest -v test_bridge
Pop-Location
```

## 核查的官方资料

- [AGP 8.9 兼容矩阵：API 35、Gradle 8.11.1、JDK 17](https://developer.android.com/build/releases/agp-8-9-0-release-notes)
- [Kotlin 2.1.20 的 Gradle 兼容范围](https://kotlinlang.org/docs/whatsnew2120.html)
- [无障碍截图、手势、窗口 API](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [Android 前台服务类型与 specialUse](https://developer.android.com/about/versions/14/changes/fgs-types-required)
- [局域网权限：target SDK 35 使用 INTERNET；后续升级需重新检查](https://developer.android.com/privacy-and-security/local-network-permission)
- [OkHttp CertificatePinner](https://square.github.io/okhttp/4.x/okhttp/okhttp3/-certificate-pinner/)
- [websockets 15.0.1 asyncio server](https://websockets.readthedocs.io/en/15.0.1/reference/asyncio/server.html)
