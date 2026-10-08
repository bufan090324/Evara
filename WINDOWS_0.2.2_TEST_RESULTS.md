# Windows 0.2.2 验证结果

2026-10-08，Windows x64，Python 3.13.5、PySide6-Essentials 6.11.2、PyInstaller 6.22.3。

- Python / Qt 自动化测试 38 个通过，包括真实 WSS 模拟手机鉴权、请求、超时、断线、取消、任务状态以及界面展示和导出测试。
- 新增测试确认总睡眠、夜间睡眠、小睡分别显示，不用缺失值填零。
- PyInstaller onedir 构建成功。
- 已启动打包后的 EXE：仅使用 Windows 系统 PATH，退出码 0；Qt、四个页面、后台网络线程、内置 Python、资料 ACL 与无配对首次启动检查通过。
- 便携包不包含配对资料、私钥、用户健康 JSON、调试截图或用户机器路径元数据。

没有连接手机。本次未验证新 APK 与桌面程序的真机读取闭环；用户需手动复测。EXE 未做 Authenticode 发行签名。
