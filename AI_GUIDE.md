# Evara AI 对话使用与构建

本版 Windows 1.0.1 / Android 1.0.1。项目原名“手机桥”，新程序为 Evara.exe。Android 包名仍为 cn.personal.phonebridge，电脑资料仍在 %LOCALAPPDATA%\PhoneBridge，以保留更新前的配对、Root 选择和权限设置。不要卸载旧 APK；覆盖安装。电脑先退出旧程序，再解压新便携包，保留整个 Evara 目录与 _internal。无需安装 Python，无需日常使用 PowerShell。

DeepSeek 官方接口专门使用非思考模式兼容配置，见 [0.3.1 修复与复测](WINDOWS_0.3.1_DEEPSEEK.md)。其他服务仍使用标准 Responses 请求。

## 开始使用

1. 双击 Evara.exe，在“连接与配对”启动服务，手机解锁并开启控制会话。现有配对无需重新生成；首次用户生成资料并在手机扫码，独立核对完整证书指纹。
2. 进入“AI 对话”。填写提供商的 **Responses API 基础地址**、支持工具调用的模型名称、API Key。例 https://你的服务/v1，程序会追加 /responses。只支持 Responses 协议；仅支持 /chat/completions 的服务暂不兼容。云端必须 HTTPS，本机 localhost/127.0.0.1/::1 服务可用 HTTP。不会跟随重定向，不读取环境代理，不自动重试。
3. 点击“加密保存设置”。设置与密钥由当前 Windows 用户 DPAPI 加密，并存放在当前用户专属 ACL 的 ai 子目录。输入框随即清空，之后留空表示保留该服务的旧密钥。更换地址必须重新输入密钥；保存配置同时清除旧对话上下文。不能把密钥发到聊天或诊断里。
4. 点击“检测文本 / 工具 / 图片 / 流式”。确认后分别发送四次合成测试请求，不读取手机或健康内容，可能产生 API 费用。每项结果独立：随机文本回显、标准 function_call、随机图片识别、SSE 文本增量与完成事件。失败显示未确认，不能推断模型不支持。当前图片测试通过后才允许勾选截图发送；修改配置或重启后需要重新检测。流式测试通过不意味着聊天界面已实现逐字显示。
5. 勾选“允许将本轮对话及读取到的健康数据发送给所选 AI 服务”。要启动手机任务，再勾选“允许 AI 发起手机读取/取消任务”。截图另行勾选；默认关闭。重新打开程序后这些授权均为关闭。运行中更改授权会取消本轮。
6. 输入例如“从桌面打开运动健康，读取当前睡眠报告并解释结果”，点击“发送给 AI”。页面显示步骤，睡眠报告页保留原始字段与证据；不要将 AI 的文字解释当成日期已确认或真实数值的替代。
7. 手机已经在睡眠详情时，可说“读取当前详情页”。只读取手机当前报告，不猜测今天/昨晚或自动选择历史日期。需要历史日期时先在手机选好。
8. “停止 AI 与本轮手机任务”中断等待模型和后续工具操作，并尝试取消本轮发起的手机任务。断线后不会重发；超时可能发生在动作执行之后，请核对手机再发起新一轮。

健康数据和图片先通过既有 WSS 通道送到电脑。AI 授权后，这些内容会送到你填写的服务；第三方服务与 OpenAI 的账户、费用、数据政策各不相同。标准请求使用 store:false；DeepSeek 官方接口按其不存储响应的协议省略该未支持参数。均不代表提供商不保留日志。APK 不持有 API Key、不访问模型 API。

## 本版工具范围

| AI 工具 | 原手机接口 | 行为 |
|---|---|---|
| device_status | device_status | 时间、时区、权限、锁屏；不包含配对凭证 |
| read_sleep(mode=home/current) | start_sleep_task / extract_current_sleep | report_date_mode=current，每轮最多一次启动；查询进度直到结束或需要用户处理 |
| get_current_report | get_task_status | 查询现有任务与原始证据，不另启动任务 |
| get_ui_tree | get_ui_tree | 读取 APK 允许页面；需要手机读取授权 |
| get_screenshot | get_screenshot | 仅单独授权时提供；每轮最多一张，需要手机截图权限和视觉模型 |
| cancel_task | cancel_task | 取消任务；需要手机操作授权 |

没有通用点击、坐标手势、任意包启动或 Root shell 工具。现有手动手机页面功能保留。Root 重启运动健康仍由 APK 本地限定逻辑和用户已有选择决定，AI 不能下发 shell。

单轮最多 6 次模型请求，总限时 200 秒；单次模型 HTTP 限时 65 秒、响应最大 3 MB；手机任务查询最多 100 秒。网络后台线程最多 12 个请求，原桥的操作队列仍有界且串行。一次只接受一个模型工具调用；未知工具、额外参数、重复调用编号、并行指令被拒绝。迟到信号按本轮编号与连接代次过滤。失败不会盲目重试。超过限制时结束当前轮，必要时核对手机。

聊天与健康结果仅在内存保留，不自动写入磁盘；最近最多 10 轮文字上下文，最多 64000 字符。截图只参与当前轮上下文，不进入后续历史。不提供自动记忆、数据库、MCP、ChatGPT 账户接入或网页模拟。聊天框会包含模型解释，普通诊断只记录错误代码。主动报告导出仍会提示文件包含健康数据。

## 开发与构建

Windows x64 / Python 3.13.5，依赖锁定在 bridge/requirements-desktop.txt 和 bridge/requirements-build.txt：

```powershell
python -m venv .venv
.venv/Scripts/python.exe -m pip install -r bridge/requirements-build.txt
.venv/Scripts/python.exe -m unittest discover -s bridge -p 'test*.py'
.venv/Scripts/python.exe -m PyInstaller --noconfirm --clean bridge/PhoneBridge.spec
.venv/Scripts/python.exe bridge/package_windows.py --dist dist/Evara --output release
```

主程序 dist/Evara/Evara.exe；源码运行 .venv/Scripts/python.exe bridge/desktop.py。原 bridge.py init/serve 保留。安卓构建仍见 README.md，Gradle Wrapper 与锁文件均包含在源码中。

ai_settings.py 管理加密配置；ai_client.py 提供 TLS 验证的 Responses 调用；ai_agent.py 将模型工具映射到现有 BridgeService.request；ai_ui.py 提供原生 Qt 页面；worker.py 在既有异步线程内执行。网络操作不阻塞 UI，UI 更新经 Qt 信号；可继续在这些接口基础上开发 MCP，不需要重新实现 APK 协议。

## 你需要手动验证

- 覆盖安装 Evara APK 后，图标、界面、通知名称更新，原配对与 Root 选择保留。
- 旧电脑程序退出后，新程序直接加载原配对；二维码和手机竖屏扫码仍正常。
- 用实际提供商完成 API 测试。失败时核对基础地址、模型、额度和 Responses 工具调用支持；不要发送密钥截图。
- 首先不勾选手机读取授权，问一个普通问题；确认不操作手机。随后授权读取，从桌面/当前详情各测一次，并逐字段对比真实手机报告。
- 手机锁屏、停止会话、断 Wi-Fi 时，AI 不继续旧动作；取消应及时结束。再次连接须重新发送消息。
- 保持截图分享关闭验证不发送图片；之后使用支持视觉的模型勾选测试一次，取消授权后不能继续发图。
- 无数据、日期不明、字段缺失/冲突应保留“需要核对”，不能让 AI 填零或声称已同步。

开发环境没有可用的真实模型密钥或直接连接的安卓设备。实际提供商和新 APK 真机验证仍需你反馈；反馈服务名称、错误码、模型是否支持 Responses（不要包含密钥）及脱敏任务结果即可。
