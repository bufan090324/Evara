# Evara APK 模拟器可用性测试（2026-10-08）

结论：基础连接、读取、截图、控制和安全拒绝机制可在本次模拟器环境运行；华为运动健康的完整睡眠报告闭环尚未通过。不能将这些结果等同于红米真机验证。

## 环境及方式

- Android 官方模拟器 Evara_Test_API30：Android 11 / API 30，Google APIs x86_64，ARM 应用通过系统翻译层运行，中文，Asia/Shanghai。
- Evara：先测试发布版 1.0.2，发现日历关闭控件差异后编译并安装本地 1.0.3-test（versionCode 22）。测试版尚未发布 GitHub。
- 华为运动健康：15.0.8.371-wearBeta；HMS Core：6.16.2.300。
- 使用实际 APK 与现有 Python BridgeService，经 LAN WSS、证书验证、HMAC 鉴权连接；使用独立测试配对和 18765 端口，未改动用户的日常电脑配对。
- 初始测试配对由先前的开发专用 Instrumentation 建立。本次未验证相机扫码或首次手动配对确认流程。
- 未调用云端 AI；健康内容仅用于本机测试。截图和原始响应留在受当前 Windows 用户 ACL 保护的 work/emulator-usability 目录，未加入发布源码。

## 实测结果

| 检查 | 结果 | 实际证据 / 限制 |
|---|---|---|
| APK 安装、启动、中文首页 | 通过 | 实际安装并打开 1.0.2 与 1.0.3-test |
| 无障碍真实连接 | 通过，有初始异常 | 首次检查系统开关开启但服务未连接，重新启用后连接成功。UIAutomator dump 在测试过程中会干扰服务状态；后续使用应用接口及 screencap。未确定初始异常唯一原因 |
| WSS / 证书绑定 / 配对鉴权 | 通过 | 实际手机客户端接入，connected / session_active / accessibility_connected 均为 true |
| 错误凭证 | 通过 | 使用正确 TLS 验证但错误 HMAC 的客户端被关闭，1008 |
| 第二台连接 | 通过 | 已有手机连接时，第二客户端被拒绝，1008 |
| 设备状态 | 通过 | 返回手机包名、授权及 Asia/Shanghai 时区 |
| 返回桌面、桌面结构 | 通过 | go_home accepted，页面稳定后读取 Nexus Launcher，7 个过滤节点；切换瞬间 MULTI_WINDOW 拒绝属安全拒绝 |
| 按需截图 | 通过 | 实际 JPEG 可解码显示，返回区域 [0,83,1080,2148]，与画面一致 |
| 关闭截图授权 | 通过 | 手机取消开关后状态为 false，截图返回 SCREENSHOT_NOT_AUTHORIZED；结束前已恢复原保存选择 |
| 有效节点点击、返回 | 通过 | 点击日期面板控件、上一日箭头有实际页面变化；go_back 被系统接受并返回健康首页 |
| 滚动 | 部分通过 | forward 被接受；后续 backward 遇到 STALE_SNAPSHOT 被拒绝，未自动重试。未验证完整双向滚动闭环 |
| 旧快照 | 通过 | 等待超过 10 秒后点击被 STALE_SNAPSHOT 拒绝 |
| 越界页面 | 通过 | Evara 自身页面不在远程允许范围，get_ui_tree 返回 OUT_OF_SCOPE |
| 长任务及取消 | 通过 | 返回 task_id 并可查询；取消约 102 ms 返回 cancelled，2 秒后仍 cancelled |
| 错误日期、错误参数 | 通过 | 请求日期与页面月日不符：needs_user、mismatch、success=false；无效日期 BAD_DATE |
| 睡眠入口导航 | 部分通过 | 从健康首页实际进入睡眠详情。已停留在详情时重发进入任务可能点击标题后等待超时；庆祝弹窗也会阻塞入口，需处理后重新发起 |
| 日期面板关闭 | 修复后通过 | 该 Health 版本暴露 sheet_indicate，class / description 均为空，enabled / clickable 为 true；增加精确 ID 和属性匹配，不使用固定坐标。重新提取实际打开并关闭面板 |
| 日历年份确认 | 未通过，安全暂停 | 此 wearBeta 版本的日历未暴露单独日期节点，也没有 selected 证据；已收起面板并返回需要适配，未推断年份 |
| 历史报告结构提取 | 部分通过 | 10 月 7 日页面：总睡眠、夜间睡眠、小睡、深睡、浅睡和评分有 observed 值，已与截图核对；快速眼动位于下方未读到，保持 missing |
| 本地 OCR | 通过已测字段 | 历史页面入睡、醒来时间由 OCR 读到，与截图一致；未读取的清醒时长保持 missing，不以清醒次数代替 |
| 今天只有小睡的页面 | 安全暂停 | 不能当作完整夜间睡眠报告；初始提取缺少核心字段，success=false。它不是“完全无数据”样本 |
| 更新后保留 | 通过本次本地升级 | install -r 后测试电脑配对及截图保存选择保留，无障碍实际重新绑定；控制会话未自动恢复，由点击重新发起 |
| 手机停止会话 | 通过 | 点击手机停止入口，Bridge 收到 disconnected，后续请求 ConnectionError；最后服务停止并释放测试端口 |

## 修复与验证

CalendarDate.closePath 增加本次观察到的日历关闭控件变体，保留原 Redmi 关闭 Button 适配。候选必须唯一；不接受任意名为“关闭”的节点。增加有效、禁用、不可点击、错类型、错 ID 及多候选测试。

实际执行 :app:testDebugUnitTest 与 :app:assembleDebug：159 个 JVM 测试通过，编译成功。安装到模拟器并复测关闭面板。APK 签名校验通过，与 1.0.2 使用同一签名，包名 cn.personal.phonebridge。

本地 APK：outputs/Evara-Android-1.0.3-test.apk（相对工作区）。当前正式 GitHub 版本仍为 1.0.2；没有覆盖已发布安装包。

## 尚未验证或待解决

- 不能声称完成整个睡眠报告读取：日历年份证据不足，快速眼动没有跨滚动合并，结果未标记成功。
- 已在睡眠详情页时的再次进入路径，以及健康首页庆祝弹窗的提示与处理，需进一步适配。
- 真实无数据样本、真实冲突页面、Root 停止与重启、锁屏、扫码相机、Android 13+ 通知权限、多厂商后台权限与真机长期稳定性，未在本轮验证。
- 没有执行云端 AI 工具闭环、Windows 桌面 GUI 全流程或两端在线更新安装器流程。
- 自动化证据摘要：verification/emulator-usability-2026-10-08/summary.json；不含配对凭证、截图、账号或健康原文。

## 结束状态

模拟器仍运行，Evara 已安装本地测试版，截图保存选择已恢复，控制会话和测试桥均已停止。华为账号未退出，应用数据未清除；睡眠页面的日期曾切换至 10 月 7 日用于核对。
