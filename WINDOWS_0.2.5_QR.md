# Windows 0.2.5 扫码配对

连接页增加“显示配对二维码”，由后台核心读取现有配对资料，在内存生成二维码。原复制/查看/删除和 WSS 服务保留。配对凭证平时不展示；二维码由用户主动点击显示，2 分钟自动关闭，收到认证连接时隐藏。

使用 Android 0.2.9 扫码。详细步骤见 ANDROID_0.2.9_UI.md。保留整个便携目录，双击 PhoneBridge.exe，无需安装 Python；原资料仍在 %LOCALAPPDATA%\PhoneBridge。

依赖锁定 qrcode==8.2、colorama==0.4.6，使用 QR 矩阵和 Qt 渲染，不依赖 Pillow，不把二维码存 PNG。容量不足时返回明确原因并使用粘贴。qrcode 官方 Python 3.13 支持说明：https://pypi.org/project/qrcode/ 。

二维码仅传输原协议 v1 的配对 JSON。扫码不等于手机已连接，连接仍由手机主动开启并通过 WSS 鉴权；服务必须启动，证书仍核对，单台手机限制保留。

46 项 Python / Qt 测试通过，包含二维码矩阵/留白、敏感字段白名单、容量错误、窗口显示及关闭、认证连接时隐藏。原 WSS、权限状态、任务和导出测试保留。PyInstaller onedir 构建成功；打包 EXE 仅 Windows 系统 PATH 下启动，退出码 0，四页界面、网络线程、内置 Python、ACL 与二维码生成检查通过。尚未连接手机验证相机扫码。
