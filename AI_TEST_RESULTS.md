当前 Windows 0.3.1 修复与验证见 [AI_0.3.1_TEST_RESULTS.md](AI_0.3.1_TEST_RESULTS.md)。下文为 0.3.0 初始交付记录。

# Evara 0.3.0 / Android 0.2.11 验证记录

日期：2026-10-08。Windows x64，Python 3.13.5，Qt/PySide6 6.11.2，PyInstaller 6.22.3，HTTPX 0.28.1；安卓 JDK 17、AGP 8.9.2、Kotlin 2.1.20、Gradle Wrapper 8.11.1、compile/targetSdk 35、minSdk 30。

## 已验证

- 电脑 Python/Qt 自动化测试 **66 项，0 失败，0 跳过**，包含既有鉴权、WSS、连接代次、排队、导出和截图显示测试，以及新增 AI 测试。
- Windows 当前用户 DPAPI 实际加解密、凭证文件不含明文密钥、当前用户访问 ACL、设置损坏拒绝加载、地址变更禁止沿用旧服务密钥。
- HTTPX MockTransport 验证实际 Responses 请求字段、Bearer 鉴权、store:false、串行 function_call；保留 reasoning 项并回传 function_call_output。401/403/404/429/500 与重定向不泄漏响应原文或密钥，不重试；无效 JSON、未完成和超大响应被拒绝。
- **实际本机 HTTP 测试服务 + 真实 TLS/WSS 通道 + 合成手机对端 + Qt 界面**完成：AI 返回 read_sleep → 发起当前报告任务 → 查询进度 → 原始证据返回模型 → 文字答复与报告页展示。第二次任务中取消，手机收到 cancel_task；旧 AI 回调被丢弃。没有调用真实模型，没有使用真实健康数据。
- AI 权限默认关闭；未授权、手机锁屏、未知工具、额外参数、并行工具、重复 call_id 和重复任务启动被拒绝。截图仅明确授权时提供工具、单轮最多一次，不进入后续文字历史。超时的启动不重试，并尝试取消已发起任务。
- PyInstaller 已生成 **Evara.exe 目录便携包**，通过移除开发环境 PATH（仅保留 Windows 系统目录）的启动探针：退出码 0，Qt 页面 5 个，后台线程正常，内置 Python/HTTPX 可用，DPAPI 实际加解密通过，ACL 验证通过；无配对资料，云端/截图授权默认关闭，二维码生成通过。已检查实际 AI 页截图，无截断或重叠。探针截图和合成加密资料仅在构建工作目录，不进入交付包。
- Android 0.2.11 **实际编译成功**；152 项 JVM 单元测试通过；Lint **0 错误、23 警告**（既有警告，未当作修复完成）。本次安卓修改仅品牌文字与递增版本，未改变协议或操作逻辑。
- APK 包信息检查：应用名 Evara，cn.personal.phonebridge，versionCode 18，versionName 0.2.11，Android 11+。APK v2 签名验证通过，签名证书 SHA-256：`2d3414bafc167f73011cf65d636819e7b73495cf202a371ed203075fd59d0aae`，与既有安装版本相同。
- 打包脚本拒绝配对凭证、私钥、AI credentials.bin、截图、日志和机器元数据；发布包附完整源码、锁定依赖、第三方许可、构建及操作说明。最终 ZIP 完整性与 SHA-256 另附。

## 尚未验证 / 未实现

- **没有真实模型 API Key，未验证用户的第三方服务。**仅声明支持标准 Responses API；不声明兼容所有代理服务、Chat Completions、所有模型或图片输入。测试按钮可用于用户自行验证服务的工具调用支持。
- **没有直接连接真机或模拟器，未安装此次 APK。**先前用户反馈的睡眠读取成功不等同于此次新 AI 闭环真机通过。需按 AI_GUIDE.md 复测覆盖安装、原配对保留、Root 自动验证、真实手机报告和真实模型结合。
- 不是 MCP/ChatGPT 账户接入；不能让未配置工具的现有网页会话直接控制手机。没有 Root shell、通用任意点击、多设备、自动日期选择、跨日猜测、持续录屏或云端数据库。
- 每轮模型请求、任务时限和图片次数有界；错误不盲目重试。健康内容授权后会离开局域网，第三方服务的保存与计费政策需用户自行了解，store:false 不承诺零保留。
- Windows EXE 未使用商业代码签名证书；APK 为同签名 debug 侧载版，不是商店发行版。

源码复现：见 AI_GUIDE.md 与 README.md。原 CLI、配对文件格式、WSS 鉴权和协议 v1 保持兼容；为保留旧资料，内部 PhoneBridge 用户资料目录、协议签名前缀及安卓包名保留。AI 提示词与用户可见品牌统一为 Evara。
