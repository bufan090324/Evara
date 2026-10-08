# 验证结果（2026-10-07，Asia/Hong_Kong）

## 已编译和检查

- 实际执行官方 Gradle Wrapper 8.11.1，`:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` 最终 `BUILD SUCCESSFUL`。本次最终运行 35 秒（缓存已准备）；初次成功运行 2 分 51 秒。不是空 APK 或改名文件。
- APK 5,199,502 字节；包名 `cn.personal.phonebridge`，版本 `0.1.0` / code 1；minSdk 30（Android 11），target/compile SDK 35；`MainActivity` 启动入口，应用标签“Evara”。
- `apksigner verify --verbose --print-certs` 通过；RSA 2048 Android Debug 签名，APK Signature Scheme v2 验证通过，适用于本应用最低版本。v1/v3/v4 没有启用，非签名失败。
- 签名证书 SHA-256：`2d3414bafc167f73011cf65d636819e7b73495cf202a371ed203075fd59d0aae`。
- `aapt dump badging` 包信息检查通过；`zipalign -c -v 4` 检查通过。
- Wrapper JAR 对官方 SHA-256 校验通过；发行 ZIP SHA-256：`f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6`，Wrapper 自身也实际运行成功。
- 固定版本和 `app/gradle.lockfile` 随源码交付。APK 文件本身的 SHA-256 见交付目录 `SHA256SUMS.txt`。

## 已自动化测试：37 项通过

Android JVM 测试 **23 项**（19 CoreTest + 4 TlsTest），0 失败、0 错误、0 跳过：

- 睡眠证据解析、分钟换算、完整日期匹配/不匹配、闰年与格式、跨午夜不反推日期。
- 月日不补年份、叙述中的无关日期不当报告日期、缺失值为空、午睡和百分比拒绝、多个值/多个日期/阶段合计冲突、摘要与暂无数据不完成。
- 请求超时和断线会话代号失效、重复编号、参数范围、局域网字面 IP 限制。
- 取消代号使旧任务不能提交新状态、页面版本/编号/10 秒期限使旧坐标快照失效。
- 实际本地 HTTPS 握手：绑定证书与 SAN 正确通过；其他证书、错误 IP SAN、过期证书拒绝。使用应用中的 Pairing.client 构造 TLS 客户端，未使用 trust-all 或 hostnameVerifier 绕过。

Windows Python 测试 **14 项**，0 失败：

- 证书生成/指纹核对、随机凭证、局域网范围、HMAC nonce/token 对应关系。
- 模拟手机的 WebSocket 鉴权成功/失败、10 秒鉴权超时、相关请求响应、断线清空未返回请求。
- 超时后迟到响应不影响新请求、取消等待后迟到回复被忽略、长任务 ID 后仍可发取消查询、8 个未返回请求限制、消息与参数限长、第二台手机拒绝。
- 本地真实 **WSS** 连接及鉴权通过，未受信任的自签名 TLS 证书被默认信任库拒绝。

**这些是 JVM/本机网络自动化测试，不是真机 Android 行为测试。** 任务取消/旧快照测试覆盖实际使用的代号与有效期辅助逻辑；桥测试使用模拟手机回复，没有验证真实 Android AccessibilityService、完整任务状态机或设备手势。没有把合成睡眠样本写成真实健康报告。

原始报告在 `test-reports/`：JUnit XML、Gradle 最终构建日志、Lint XML/HTML、桥测试输出、签名与包信息、对齐输出。

## 静态检查

Lint **0 错误，11 警告**，没有整体禁用检查。保留项：同步 `SharedPreferences.commit`（用于先确认凭证落盘）、自定义 X509TrustManager（委托默认 TrustManagerFactory 校验，再加证书有效期/DER 绑定/SPKI pin；有负面握手测试）、`stopService(Intent)` 的 ImplicitSamInstance 提示，以及中文字符串未整理成完整可翻译资源。Kotlin 编译保留 Android 旧版节点回收 API 的弃用警告，以兼容 Android 11/12。仍建议真机验证和后续代码审查，警告不能当作安全证明。

## 未经设备验证

`adb devices -l` 无设备；已安装的 AndrowsBox 查询没有运行中的虚拟机，本次 SDK 未配置模拟器镜像。因此以下未执行：

- APK 安装/启动、中文 UI 布局、无障碍启用/受限设置、Keystore 在真实系统上的保存/删除、通知停止入口和后台生命周期。
- 手机到 Windows 的真实无线 WSS 配对与网络异常处理。
- 屏幕树获取、点击/滚动/返回/坐标手势、截图权限、系统安全截图、厂商窗口覆盖/分屏/锁屏识别。
- 真实华为运动健康的桌面→首页→睡眠→日期→字段提取闭环，以及真实任务取消响应时间。

**没有成功读取任何真实睡眠数据。** 设备验收步骤见 `DEVICE_TEST_CHECKLIST.md`。需要真实设备的页面结构与用户核对来完成版本适配。

## 已知限制与未完成事项

1. 不支持原生鸿蒙，Android 兼容层及不同厂商系统未经测试。
2. 华为运动健康适配目前为保守中文标签基础路径；没有手机型号/软件版本样本。标签、控件关系、加载页面、日期归属可能需要调整。
3. 自动日期控件选择、年份缺失时可靠补证、本地 OCR 未实现；不支持“昨晚”。需要用户手动选日期后重新提取，年份无法核对仍不能完成。
4. 无持续录屏、通用跨应用自动化、扫码、IPv6、服务发现、自动重连、云端模型、MCP server 或 ChatGPT 账户接入。
5. 仅对可访问窗口作保守检查，无法证明识别所有厂商非无障碍覆盖层。截图使用显示截图后裁剪根窗口，并非 Android 14 的单窗口截图接口；重要隐私边界需实测。
6. 系统已提交的瞬间点击/手势不能撤回；取消不再发起后续动作，真实取消和页面变化行为还需测量。
7. 缺失、矛盾或不明确字段会为空/needs_user，部分版本只有视觉内容时需要电脑 AI 辅助。不会访问华为数据库、私有文件、推断图表精确数字或声称设备已同步。
8. DEBUG APK，发行加固、正式发行签名、商店审核未完成；重建使用新 debug key 时可能需要卸载旧版再安装。

## 构建环境处理

正常配置了工作目录内 JDK、Android SDK、Gradle；没有安装 Android Studio。JDK 下载的一个官方分发入口返回 403，改用 Azul 官方 CDN。当前工具运行环境的 Java Unix-domain selector 报错通过最小程序复现，配置 JDK 自带 TCP 回退后构建成功。相关说明与复现构建命令在 README；没有修改 APK TLS 校验或用假的构建产物替代。
