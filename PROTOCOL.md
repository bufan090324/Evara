# Evara协议 v1

传输：手机主动连接 `wss://RFC1918-IPv4:port/bridge`，文本 UTF-8 JSON。单台配对电脑、单台手机；无公网/云端/自动重放。电脑证书作为唯一可信锚，必须 SAN 匹配 IP、在有效期内、DER 与绑定证书一致，并匹配 SPKI pin；保留 OkHttp 默认 hostname 验证。首次绑定须用户独立核对电脑端完整 SHA-256 指纹。

## 配对与鉴权

粘贴或手动填写：

```json
{"protocol":1,"host":"192.168.1.5","port":8765,"name":"我的电脑","certificate_der":"真实DER的Base64","certificate_sha256":"64位十六进制SHA256","token":"至少32位的随机URL-safe凭证"}
```

TLS 成功后电脑发随机一次性 32 字节 nonce（64 位小写 hex），手机返回 HMAC-SHA256。计算 UTF-8 `HMAC(token, "phonebridge-v1:" + nonce)`，输出小写 hex，电脑常量时间比较，10 秒内必须完成。不会将 token 放进 WebSocket URL、日志或每条命令。

```json
{"type":"challenge","protocol":1,"nonce":"64位随机小写hex"}
{"type":"auth","protocol":1,"proof":"64位HMAC小写hex"}
{"type":"auth_ok","protocol":1}
```

## 请求、响应

```json
{"type":"request","protocol":1,"id":"unique-123","timeout_ms":10000,"method":"get_ui_tree","params":{}}
{"type":"response","protocol":1,"id":"unique-123","ok":true,"data":{"snapshot_id":"uuid","revision":42,"package":"com.huawei.health","nodes":[]}}
{"type":"response","protocol":1,"id":"unique-124","ok":false,"error":{"code":"STALE_SNAPSHOT","message":"页面已改变，请重新读取"}}
```

id：1–64 位 `[A-Za-z0-9_-]`，同一连接不能重复；不重放响应缓存。每次会话最多 4096 个编号，然后需重新连接。timeout_ms：100–30000（包含排队时间，按手机单调时钟计时）。正常指令串行执行，最多等待 8 条；status/task/cancel 在主线程立即处理，不进入操作队列。断线时队列和正在等待的响应全部作废，旧会话回调无法提交。电脑应立即使本地未返回请求失败，不跨连接重发动作。

请求最大 65536 UTF-8 字节，返回最大 2.2 MB；结构最大 1500 遍历节点、深度 35、输出 200 KB，节点文字最多 500 字符，只保留可见有文字/描述/点击/滚动能力节点。图片 JPEG 原始编码最大 1.5 MB，再 Base64。超过限制返回失败，不发送静默截断的树。文字在单节点长度处截断，解析需对异常长文本谨慎处理。Bridge 对返回消息限制大小/队列。APK 拒绝二进制指令。

## 能力

| method | params | 返回 / 约束 |
|---|---|---|
| device_status | `{}` | 连接、会话、无障碍实际连接、截图授权、手机带时区时间、包名、锁屏、任务、`ocr_available:false` |
| get_ui_tree | `{}` | 允许页面过滤树、snapshot_id、revision、window_id；父节点是最近保留的祖先 |
| get_screenshot | `{}` | JPEG base64、mime、region `[left,top,right,bottom]`、与图像对应的 snapshot_id；仅在当前已认证会话授权时执行。APK 0.2.0 从手机保存的“始终允许”选择生成会话授权，停止/断线撤销会话 |
| go_home | `{}` | 从允许页面返回桌面；`accepted:true` 仅表示系统接受，不代表后续加载完成 |
| click_node | `snapshot_id,node_id` | 重新检查快照和节点文字/描述/边界/状态；节点或最多三个祖先 click，失败才考虑当前已认证会话授权的手势 |
| scroll | `snapshot_id,node_id,direction` | direction 为 `forward` / `backward`，必须是当前可滚动节点 |
| go_back | `{}` | 从允许页面返回；之后越界页面拒绝进一步操作 |
| coordinate_gesture | `snapshot_id,x,y,[to_x,to_y,duration_ms]` | 授权备用能力；绝对屏幕像素，限制在根窗口，duration 50–1500，默认点击 80 ms；滑动必须同时给终点 x/y |
| start_sleep_task | `date` | 明确 YYYY-MM-DD，立即返回新 task_id，最多运行 90 秒。APK 默认按包名打开，手机可切换桌面点击；0.2.0 手机授权启用 Root 重启后先停止健康应用并检测进程；消息格式不变，无任意包或 shell 参数 |
| extract_current_sleep | `date` | 独立提取当前睡眠详情，不执行桌面导航，立即返回 task_id |
| get_task_status | `{}` | 当前任务 ID、state、step、target_date、reason、result、必要时 page；仅保留最近任务 |
| cancel_task | `{}` | 即时取消当前睡眠任务；不自动重发、恢复或继续点击 |

长任务期间其他页面命令返回 `TASK_BUSY`，避免多个操作者并发点击。task/status/cancel 可处理。采用查询而非事件推送；建议 task 每秒查询一次。任务状态：`waiting` 等待、`running` 运行中、`needs_user` 需要用户处理、`completed` 完成、`failed` 失败、`cancelled` 已取消。取消代号保证旧任务迟到结果不能覆盖新任务。系统已接受的瞬间点击/手势无法撤回；停止保证不再发起后续动作，设备上的最终行为仍需实测。

## 节点与视觉备用示例

```json
{"type":"request","protocol":1,"id":"n1","timeout_ms":5000,"method":"click_node","params":{"snapshot_id":"从刚读取的树获取","node_id":"0/2/1"}}
{"type":"request","protocol":1,"id":"g1","timeout_ms":5000,"method":"coordinate_gesture","params":{"snapshot_id":"从刚截图返回获取","x":120,"y":400,"to_x":120,"to_y":200,"duration_ms":350}}
```

示例坐标只是协议演示，不能直接用于华为运动健康。快照有效期最多 10 秒；任何窗口状态/内容/滚动事件都会作废。截图回调前再次验证快照，已变化返回错误。操作后必须重新获取页面，不能沿用旧节点/图像进行下一点击。图像默认不保存，过期内容不能用来操作。

## 结果证据示例（合成数据，未读取真实设备）

```json
{"task_id":"uuid","state":"completed","target_date":"2026-10-06","step":"已完成，停留在睡眠详情","reason":"","result":{"target_date":"2026-10-06","report_date":{"value":"2026-10-06","unit":"date","source":"accessibility","evidence":["2026年10月6日"],"state":"verified","reason":null},"success":true,"fields":{"total":{"value":450,"unit":"minute","source":"accessibility","evidence":["总睡眠时长 7小时30分钟"],"state":"observed","reason":null},"awake":{"value":null,"unit":null,"source":"accessibility","evidence":[],"state":"missing","reason":"没有唯一且明确关联标签的值；可能未暴露、未加载或需要版本适配"}},"sync_state":"unknown_no_evidence"}}
```

真实返回包含 total/deep/light/rem/awake/score/bedtime/wake_time 所有字段。state 可为 observed/missing/conflict/uncertain/verified/mismatch；没有模型置信分数。日期仅接受独立完整日期标题或明确“报告日期/睡眠日期”标签，不从其他叙述中的年份拼凑。只显示月日保持 uncertain。可验证详情标题、至少两种睡眠阶段、日期匹配、总时长有效且无冲突才完成；其他字段允许缺失并解释。日期不匹配、字段冲突或无法识别详情返回 needs_user 与 page，用户处理后重新发 `extract_current_sleep`。

## 0.2.3 睡眠证据扩展（兼容 v1）

`result.fields` 增加可选 `night`（夜间睡眠）和 `naps`（零星小睡），单位 minute，证据结构与 total 相同。旧客户端可忽略未知键；Windows 0.2.2 单独展示它们。total 不再接受夜间睡眠作为同义标签。只有明确读到夜间睡眠才核对三阶段之和；明确读到 total/night/naps 才核对总睡眠等式。原始证据可包含节点路径和控件 ID。

解析当前可见节点，不跨任务合并滚动前后页面。只含月日时不验证年份；月日与目标不同返回 mismatch，月日相同仍 uncertain。不会根据手机年份或星期补年份。未暴露的入睡、醒来或清醒时长保持 missing，清醒次数不是清醒时长。

## 常见错误与停止

Android 0.2.5 增加当前报告模式：start_sleep_task / extract_current_sleep 参数 `{"report_date_mode":"current"}`，此模式不传 date，也不允许 date_confirmed=true。先读取报告栏月日，再展开日历，核对唯一年月/对应日期，收起并确认报告栏不变，之后提取。task.target_date 在日期核对前为空字符串，核对后为实际 YYYY-MM-DD。date 证据来自 accessibility，未可靠确认日期时暂停。新增节点 selected 布尔字段；旧客户端可忽略。已有显式 date 调用兼容。

REPORT_DATE_REQUIRED：未找到唯一报告日期栏；CALENDAR_DATE_UNADAPTED：年月/对应日期无法可靠确认，任务 page 保留展开的日历节点；UNADAPTED_PAGE：面板打开或关闭未观察到预期变化。不会猜固定坐标或改变选中的日期。

Android 0.2.6 通过唯一 sheet_indicate_container（关闭 Button）收起日期面板，不要求展开时背景报告日期栏仍可见。支持已展开面板读取选中月日；关闭后与详情报告月日再次核对。CALENDAR_CLOSE_UNADAPTED 表示关闭控件未可靠匹配，REPORT_DATE_CHANGED 表示关闭后报告月日改变。协议请求格式不变。

Android 0.2.7 的 device_status.ocr_available=true 表示已编入本地中文 OCR 功能，不等同于引擎已真机验证。读取任务只在本次截图已授权且 bedtime/wake_time/awake 缺失时按需截图一次。只补 missing 字段，source=ocr，evidence 含快照号、文字和屏幕区域；识别图像不随任务结果返回或落盘。截图前后页面/授权/快照检查仍适用，取消后的回调不能覆盖状态。识别 8 秒超时或 OCR_FAILED 返回字段缺失原因；不推测清醒时长或年份。协议方法和参数不变。

Android 0.2.8 在同一 OCR 截图上可进行一次醒来区域三倍放大识别，两轮总计 8 秒，evidence 增加 pass=full/focused_x3。原文不被繁简标签规范化替换；OCR 小时必须完整两位，缺位不补猜。局部识别失败保留首轮已读字段。网络截图方法默认 JPEG 质量不变，内部 OCR 截图使用更高质量并保留原消息大小上限。请求格式不变。

Android 0.2.9 / Windows 0.2.5 二维码内容是原有 UTF-8 配对 JSON，包含 protocol、host、port、name、certificate_der、certificate_sha256、token，不含私钥，不新增 URL 或请求消息。扫码仍调用原 Pairing.parse 和首次信任确认。Root 重启设置持久化；更新后无需手动预检查，受限命令执行前实时核对 UID=0，失败停止任务。协议方法与参数不变。

Android 0.2.4 的 start_sleep_task / extract_current_sleep 可选参数 `date_confirmed`（布尔值，默认 false）：由电脑用户本次明确确认所选完整日期。只在页面唯一月日与请求月日匹配、且没有完整年份证据时生效；返回 report_date.state=user_confirmed、source=user_confirmation、value=请求日期，evidence 保留页面月日和用户确认记录，reason 说明年份没有独立验证。它不能覆盖完整日期不匹配、多日期、月日不匹配或无日期，也不能绕过字段验证。省略参数兼容既有协议 v1 行为。

`BAD_PROTOCOL/BAD_REQUEST/BAD_ID/BAD_DATE/BAD_TIMEOUT` 参数错误；`NOT_LAN` 非局域网；`QUEUE_FULL/SESSION_LIMIT` 达到限制；`TIMEOUT` 已过期；`STALE_SESSION/STALE_SNAPSHOT/STALE_NODE` 必须重新读取和发起；`LOCKED/OUT_OF_SCOPE/MULTI_WINDOW/OVERLAY/USER_REQUIRED` 用户处理；`ACCESSIBILITY_REQUIRED` 未授权；`SCREENSHOT_NOT_AUTHORIZED/GESTURE_NOT_AUTHORIZED` 未允许本次能力；`SCREENSHOT_FAILED/SCREENSHOT_LIMIT` 系统截图错误；`ICON_NOT_FOUND/UNADAPTED_PAGE/AMBIGUOUS_TARGET` 需要真机适配；`ACTION_FAILED` 动作未接受。

没有获取私有数据库、私有文件或健康同步状态的协议。status 的包名用于安全诊断，越界应用的界面内容不返回。重连与任务恢复分开：手机主动重连，电脑必须发送新的任务编号；用户处理暂停页面后重新提取，不自动续跑旧点击。接口可由后续电脑 MCP server 包装，本次没有 ChatGPT 账户接入或 MCP 部署。
# 局域网更新扩展（1.0.4）

控制消息协议 v1 不变。Windows 桌面可在既有配对 TLS 端口提供独立 HTTPS 更新路由，不通过控制指令队列传递 APK。命令行桥没有更新缓存界面；原有控制仍兼容。

- GET `/evara-update/challenge` 返回 `{"protocol":1,"nonce":"64位小写十六进制"}`。挑战绑定来源 IP、30 秒有效、只可用一次；最多 32 个，单来源最多 8 个。仅 RFC1918 IPv4 可访问。
- 随后 GET `/evara-update/manifest`，附 `X-Evara-Nonce` 与 `X-Evara-Proof`。Proof 是 HMAC-SHA256(配对 token, UTF-8 字符串 `evara-update-v1:<nonce>:<完整请求路径>`)，小写十六进制。凭证不在 URL 中传输。
- 认证通过返回原样的发布者签名清单，最多 64 KiB；头 `X-Evara-Cache-Id` 为本次已校验缓存的 32 位随机标识。手机使用内置公钥验证，不因信任电脑而跳过发布签名。
- GET `/evara-update/chunk/<cache-id>/<offset>`，每次使用新挑战及对应路径 HMAC。offset 是非负十进制、1 MiB 的整数倍、小于文件大小。返回最多 1 MiB 原始二进制，末块可较短，包含同缓存标识。手机串行拼接，在完成后核验整个 SHA-256/大小及 APK 包名/版本/签名。
- 仅共享固定已校验缓存文件，不接受任意路径。最多两个并行块读取。响应明确 `Connection: close`、`Content-Length`、`Cache-Control: no-store`，不要求 HTTP 长连接。
- 401 鉴权失败、403 非局域网、404 未知路径、409 缓存未准备/已改变/偏移无效、429 繁忙。停止服务释放端口，删除配对关闭连接并撤销旧凭证，清除缓存使旧编号失效。
- 手机继续验证已绑定证书的信任链、有效期、IP SAN 与固定证书；禁止重定向、不使用外网代理、每请求 30 秒预算、15 秒读取超时、全程 30 分钟上限，可取消、不自动重试。更新通道不需要控制会话授权，也不读取健康数据。
