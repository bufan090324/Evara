# Windows 0.2.4 当前报告模式

睡眠页面移除电脑日期选择器和确认复选框。两个读取按钮发送 report_date_mode=current，由 APK 0.2.5 读取当前报告和日历日期。保留状态、取消、证据和导出按钮。

用法见 ANDROID_0.2.5_UI.md。报告日期不再依赖电脑今日或手机前一天。历史日期由用户在手机选择，程序不会点击某一天或自动搜索最新报告。

41 项 Python / Qt 测试通过，覆盖无日期控件、current 请求参数及断线清理，原 WSS 鉴权、请求和导出测试保留。PyInstaller onedir 构建成功，打包 EXE 在仅 Windows 系统 PATH 环境启动，退出码 0；四页界面、网络线程、内置 Python 和 ACL 探针通过。新版 APK 与电脑真实联调尚未完成。
