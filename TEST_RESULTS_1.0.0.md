# Evara 1.0.0 验证记录

日期：2026-10-08。Windows 与 Android 版本均为 **1.0.0**；安卓 versionCode 19，协议 v1，cn.personal.phonebridge 包名及旧签名保留。

## 构建与自动化

- Windows Python/Qt **82 项测试通过，0 失败、0 跳过**：既有 WSS 鉴权、串行队列、超时、取消、连接代次、睡眠证据展示与导出、Responses/DeepSeek 请求，以及签名更新清单、篡改/未签名拒绝、HTTPS 跳转限制、文件大小/哈希、ZIP 路径穿越防护、有效解压、取消清理及更新启动授权。
- Android 实际编译 debug APK，**157 项 JVM 测试通过**：新增更新清单 RSA/SHA-256 签名、篡改、错误产品、非法字段、非 HTTPS、凭证 URL 和大小限制测试。Lint **0 错误、35 警告**；包含既有警告和新增安装入口等警告，未声称所有警告已消除。
- APK v2 签名验证通过；包 cn.personal.phonebridge，versionName 1.0.0，versionCode 19，minSdk 30 / targetSdk 35；签名证书 SHA-256 `2d3414bafc167f73011cf65d636819e7b73495cf202a371ed203075fd59d0aae`，与旧版一致。
- Windows PyInstaller 便携版实际构建，保留内置 Python/Qt/HTTPX。只保留 Windows 系统 PATH 的启动探针退出码 0，版本 1.0.0、6 个页面、后台线程、ACL、DPAPI、二维码生成通过，云端与图片授权默认关闭。
- 发布前检查源码/包内不含签名私钥、配对凭证、AI 凭证、真实健康导出、日志与本机路径；Gradle Wrapper、依赖锁、第三方许可和发布脚本随源码提供。文件 SHA-256 和 ZIP 完整性由发布流程核对。

## 必须区分的限制

- 没有直接连接真机/模拟器，未执行新 APK 的真机覆盖安装、未知来源授权、系统安装器确认、更新后的无障碍和 Root 保留测试。用户此前反馈的睡眠读取成功不等于新更新流程验证成功。
- 没有使用用户 AI 密钥调用真实 DeepSeek，既有 400 修复仍需实际服务复测。
- Windows 更新采用已校验的独立目录启动，不覆盖原目录、没有安装器静默替换、没有清理旧目录。Android 安装由系统与用户确认，不能以“已打开安装器”代替“已完成安装”。
- 未实现自动更新、断点续传、差分更新、更新密钥轮换、多个发布通道或应用商店发行。APK 为 debug 侧载签名，EXE 没有商业 Authenticode 签名。
- 首次使用新版需要手动安装/启动 1.0.0；后续通过内置 GitHub 更新源检查。首次发布清单与当前版本同为 1.0.0，“没有高于当前版本的更新”是正确行为。

操作、签名和发布方法见 UPDATES.md；AI 授权见 AI_GUIDE.md；原始协议见 PROTOCOL.md。在线发布后的清单与下载安装文件验证结果另见 RELEASE_VERIFICATION.md（发布完成后生成）。
