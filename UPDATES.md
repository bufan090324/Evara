# Evara 1.0.0 软件更新

Windows 和 Android 均为 1.0.0，安卓 versionCode 19，保留 cn.personal.phonebridge 包名、原有 APK 签名、协议 v1 和配对/AI 设置目录。内置更新源：

`https://github.com/bufan090324/Evara/releases/latest/download/update.json`

首个 1.0.0 版本需要手动安装/启动一次，以后在客户端“软件更新”板块检查。更新不会自动启动，不请求管理员权限、不修改防火墙。发布内容为源码与安装包，不包含用户资料。两个客户端检查同一份签名清单，分别选择自己的平台。版本相同或较低不会提示升级。

## Windows

打开“软件更新” → “检查新版” → 查看版本和说明 → “下载并校验新版” → 确认下载。校验通过后解压到 %LOCALAPPDATA%/PhoneBridge/updates 下的新目录，不覆盖正在运行的目录。点击“结束当前会话并启动新版”，确认后停止 AI、手机任务和网络服务，退出旧程序、释放单实例锁，再启动新版 Evara.exe。配对和 AI 密钥仍使用当前用户资料。旧目录保留，用户可自行清理；不宣称已执行原目录的自动替换或安装器升级。

下载允许取消；校验失败不解压或执行，取消不补发。下载时界面保持响应。旧版未安装成功时仍可从旧目录启动。便携版需要保持 Evara.exe 与 _internal 完整。EXE 未进行商业 Authenticode 签名，更新包真实性由内置的更新清单公钥验证。

## Android

底部“更新” → “检查新版” → “下载并校验新版” → “停止会话并安装新版”。如尚未授权，系统会要求允许 Evara 安装未知来源应用；返回后再点击安装。客户端校验更新清单、大小、SHA-256、APK 包名、versionName/versionCode、当前设备最低系统要求及与已安装 APK 相同的签名证书。失败或降级均拒绝安装。通过后使用 FileProvider 临时只读 URI 交给系统安装器，仍由用户确认覆盖安装，不调用 Root、不静默安装、不卸载旧版本。

APK 下载文件保存在应用私有缓存，下一次下载替换缓存；取消删除未完成文件。离开更新页会取消正在检查/下载的操作，不在后台偷偷续传。系统安装取消、来源权限拒绝、ROM 限制、安装后保留设置和 Root 授权情况需要真机验证。提供安装入口不等于已确认系统完成安装。

## 校验和发布

- 使用标准 HTTPS/TLS 证书校验；GitHub 资产的重定向只允许 HTTPS，最多 6 跳，不向更新请求附加 API Key、配对凭证或健康内容。
- update.json 包含 Base64 编码的 payload 和 RSA/SHA-256 signature。payload 指定 schema=1、product=Evara 以及两平台的版本、下载 URL、文件字节数、SHA-256 和更新说明。
- 更新清单最多 64 KiB，包文件最多 150 MiB；Windows ZIP 最大解压 400 MiB，最多 10000 项，拒绝绝对路径、路径穿越、符号链接与重复路径，要求 Evara/Evara.exe。ZIP 哈希校验在解压前完成。
- 内置公钥为 update-public-key.der，代码分别在 bridge/update_key.py 与 UpdateKey.kt。**更新私钥不在源码/仓库/安装包内**。发布者应离线安全备份私钥；丢失时不能继续发布旧客户端认可的清单。私钥轮换和多签名尚未实现。HTTPS、清单签名和 APK 原签名是不同的检查，不能相互代替。
- APK 仍为 debug 侧载签名：发布更新必须保留原安卓签名私钥，换机器默认生成的新 debug 签名会被当前客户端拒绝。该 Android 私钥同样不得提交仓库。

每次发布按 README.md/AI_GUIDE.md 构建 Windows 与 APK，发布完整便携 ZIP 和 APK。使用 `publish_update.py`（在电脑构建环境中）：

```powershell
python publish_update.py --windows Evara-Windows-1.0.1-x64.zip --apk Evara-Android-1.0.1.apk --base-url https://github.com/bufan090324/Evara/releases/download/v1.0.1 --version 1.0.1 --version-code 20 --private-key 安全目录/update-signing-key.pem --output update.json --notes "更新说明"
```

将生成的 update.json 和对应文件上传同一个 GitHub Release，并设为最新正式版本。不发布预发行版作为日常更新。手动可修改客户端中的更新源，但新地址的清单仍需由内置公钥对应的私钥签名；不能通过更换地址绕过验证。只使用 GitHub Releases，无需云端数据库或客户端 GitHub 登录。
