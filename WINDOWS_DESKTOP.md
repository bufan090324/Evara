# Evara Windows 桌面版 1.0.0

兼容 **Evara Android APK 协议 v1**。电脑 0.2.5 配合安卓 0.2.9 新增扫码配对，见 [扫码和 Root 操作](ANDROID_0.2.9_UI.md)。读取当前报告、日历年份、夜间睡眠/小睡展示和失败/暂停任务导出保留。配对消息、HMAC、证书和任务机制不变。新增 AI 对话页，兼容 Responses API；设置和授权见 [AI_GUIDE.md](AI_GUIDE.md)。没有 MCP 服务；按钮和 AI 复用共享程序接口，不模拟终端或网页。

## 日常使用：无需 Python 或 PowerShell

1. 解压 Windows x64 便携包到自己的目录。**保持整个 Evara 文件夹完整**，双击 `Evara.exe`；不要只移动 EXE，`_internal` 包含 Python、Qt 和运行库。
2. 首次启动选择局域网 IPv4、电脑名称和端口，点击“生成首次配对”。只在一个明确的有线/无线候选时标注推荐；多候选/未知类型需要你自己选。VPN/虚拟网卡按系统类型和名称识别，不能保证所有驱动分类准确。
3. 点击“显示配对二维码”，Evara 0.2.9 的配对页扫码，核对两端**完整证书 SHA-256 指纹**后保存。也保留“复制配对资料”与手动填写。二维码含敏感凭证，仅给自己的手机看，不截图分享；2 分钟自动隐藏，隐藏不撤销凭证。剪贴板复制主动确认，60 秒后未改变的内容清除；系统剪贴板历史不受程序控制。
4. 点击“启动服务，等待手机”。手机解锁，手动开启无障碍和通知权限；需要截图时允许本次截图，再点击手机“连接并开启控制会话”。显示“已认证连接”才代表鉴权成功。
5. “手机页面”可刷新状态、读取结构、按需截图、返回桌面/上一页。选中树里的可点击/可滚动控件后可点击或滚动。快照最多 10 秒，手机发生页面变化会拒绝旧节点。截图保持比例，10 秒后清除；**本版没有截图坐标点击/滑动**，无需把旧图当实时画面。
6. “睡眠报告”点击“从桌面进入并读取”或“读取当前详情页”。读取手机当前报告，自动展开日期栏核对年份和对应日期，再点击日期栏收起。历史日期由你先在手机选择。手机时间/时区从手机状态读取，不提供“昨晚”映射；见 [当前报告说明](ANDROID_0.2.5_UI.md)。自动流程尚需真机复测。
7. 可随时取消任务；停止服务会尝试取消并断开。关闭程序时如有服务或会话会提示，随后关闭网络线程和端口。没有开机自启或自动重新发送动作。

程序是未做 Authenticode 发行签名的个人便携 EXE，不需要管理员权限。已在 Windows 11 x64 构建和运行；Qt 官方支持 Windows 10 1809+ x64，其他 Windows 版本未实测。

## 沿用旧测试桥配对

如果已经在手机保存旧配对，点击“导入旧测试桥配对”，选择旧的 `bridge/private` 目录，必须同时含 `pairing.json`、`cert.pem`、`key.pem`。验证证书有效期、DER/指纹、IP SAN、私钥与公钥匹配后复制到用户资料目录，保持旧凭证与证书，手机无需重新粘贴。已有桌面配对时不覆盖；先明确删除才可重新配对。

**导入前退出旧命令行桥**，避免同端口占用。旧 IP 已改变、证书过期或资料不一致时，不能继续导入；在桌面重新生成并删除手机旧配对后粘贴。删除桌面配对会先关闭旧连接并删除证书/凭证；旧凭证无法再连接新生成的服务。不会自动删除你选择导入的源目录。

## 运行资料与隐私

固定使用 `%LOCALAPPDATA%\PhoneBridge\pairing`，与程序解压目录、工程目录、当前工作目录无关。没有把私钥、配对资料、健康数据或截图放进便携包。保存格式仍为兼容 APK 的 JSON 与 PEM。

通过 Windows 安全 API 设置资料目录和文件的**保护 DACL**：关闭继承，仅允许当前用户 SID 访问；执行后读回核对。若 ACL 设置失败，显示具体状态并拒绝生成/启动，而不静默以未保护的文件继续。管理员仍可取得所有权，ACL 不等于对管理员加密；不同用户账户、域策略和其他文件系统尚未实测。私钥是 PEM 文件，由 ACL 保护，不虚称已使用 DPAPI 加密。

健康结果和截图默认仅在内存保留，断线清除页面与报告。报告只有点击“主动导出报告 JSON”并确认健康数据提示后才保存；包含原文证据。普通诊断只记录事件和方法名，不记录凭证、节点文本、截图、睡眠原文或用户机器路径。主动查看结构或分享健康截图前请自行脱敏。

图片和结构先发到这台电脑。AI 对话经用户授权后会向所选服务发送内容，离开局域网。程序不内置密钥；用户在 AI 页填写的密钥由 Windows 当前用户 DPAPI 加密保存。手机 APK 不调用模型 API。

## 状态与错误处理

- 手机未认证或未获取有效设备状态时，页面操作禁用。锁屏、断线、停止后禁用；连接期间设备状态约每三秒单次查询，不重叠。APK 自身仍在动作前检查页面、权限与会话。
- 图片只按需获取；不会定时截图。截图授权、禁止截图、越界应用等错误直接显示手机错误码。
- 正常页面操作在核心中串行，最多 8 条；cancel/status/task 查询独立。UI 同时限制重复提交。
- 长任务返回 task_id 后每秒单次查询，最多观察 110 秒；结束/失败/取消/用户处理/断线后停止任务轮询。超时结果未知时保留提示，用户可主动刷新/取消，不补发旧点击。
- 旧连接代号、任务代号和 task_id 防止迟到结果覆盖新任务，终态不被迟到的 running 覆盖。
- 显示“完成”也不代表手环已同步。缺失、只有月日、冲突或不确定值显示“未读取/需要核对”，保留来源、单位、状态和原文。不填零、不根据图表猜数。
- APK 自动日期选择和 OCR 未实现。请在手机选日期后重新提取；年份不能核对时仍会暂停。

无法互通时检查同路由器、AP 隔离、真实网卡和专用网络防火墙。程序不自动修改防火墙，也不申请管理员权限。可从“设置与诊断”打开网络设置检查专用网络，再在 Windows 安全中心 → 防火墙和网络保护 → 允许应用，允许此程序的专用网络访问；不要开放公共网络或公网端口映射。

端口占用：退出旧桥/另一个实例，或删除配对后改端口。地址变化/证书过期：刷新地址、停止、删除并重新配对。无合适地址时检查网线/Wi-Fi；不自动用回环或 VPN 冒充可用局域网地址。

## 开发与构建（仅开发者需要 Python）

本次使用 Python 3.13.5 x64；PySide6 Essentials 6.11.2（QtCore/Gui/Widgets/Network，省去无关 Addons）、shiboken6 6.11.2、PyInstaller 6.22.3、pywin32 311。保留 websockets 15.0.1 与 cryptography 44.0.2，运行与打包依赖都固定在 requirements 文件。`pip check` 通过。

在 Windows x64，工程根目录执行：

```powershell
python -m venv work/desktop-venv
work/desktop-venv/Scripts/python.exe -m pip install -r bridge/requirements-build.txt
work/desktop-venv/Scripts/python.exe -m unittest discover -s bridge -p 'test_*.py' -v
work/desktop-venv/Scripts/python.exe -m PyInstaller --noconfirm --clean --distpath dist --workpath work/pyinstaller bridge/PhoneBridge.spec
```

产物 `dist/Evara/Evara.exe`，包含所有运行依赖；使用项目脚本 `bridge/package_windows.py` 添加许可说明、检查敏感文件和制作便携 ZIP。源码运行入口 `bridge/desktop.py`。旧 CLI `bridge.py init/serve` 和所有原指令保留，仍可供开发调试；CLI 默认目录保持原 `bridge/private`，桌面默认目录独立。

模块：`core.py` 为共用 WSS/鉴权/配对验证及 BridgeService API；`storage.py` 为用户目录/ACL/导入删除；`worker.py` 为独立线程 asyncio 服务；`desktop.py` 为 Qt UI；`presentation.py` 为证据展示；`network_info.py` 为网卡识别。后续 MCP 可调用 `await BridgeService.request(method, params)`；当前没有把这些接口暴露为未经认证的本地 HTTP 服务。

第三方 Qt/PySide 许可与源码链接随便携包 `THIRD_PARTY_NOTICES.md` 和 `licenses/` 交付。动态 Qt 库保留在 `_internal`，可以按适用许可替换；项目未修改 Qt/Python/第三方库源码。

## 核查与限制

测试详情见 [WINDOWS_TEST_RESULTS.md](WINDOWS_TEST_RESULTS.md)，真机反馈步骤见 [WINDOWS_MANUAL_TEST.md](WINDOWS_MANUAL_TEST.md)。真实安卓连接、厂商后台行为、华为运动健康版本和真实睡眠数据仍未验证，不能以模拟手机测试代替。

官方资料：[Qt/Python 安装与模块](https://doc.qt.io/qtforpython-6/gettingstarted.html)、[Qt Windows 平台](https://doc.qt.io/qtforpython-6/overviews/qtdoc-windows.html)、[PyInstaller Windows 打包](https://www.pyinstaller.org/en/stable/usage.html)、[QNetworkInterface](https://doc.qt.io/qtforpython-6/PySide6/QtNetwork/QNetworkInterface.html)、[Microsoft DACL 安全描述符](https://learn.microsoft.com/en-us/windows/win32/secauthz/security-descriptors)。
