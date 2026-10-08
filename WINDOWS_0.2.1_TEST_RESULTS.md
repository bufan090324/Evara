# Windows 0.2.1 验证结果（2026-10-07）

- 实际执行 unittest discover：37 项通过，16.594 秒；原 33 项 + 4 项导出回归测试。
- 新测试通过实际 Qt 按钮触发导出、写入临时 JSON，并核对暂停任务完整数据（result=null、page 节点/坐标/父关系保留）；空任务不弹窗；拒绝确认不打开保存；确认对话框期间新任务到达，文件仍保存点击时的原任务。
- 原 WSS 鉴权、取消、断线、串行与超时、过期状态、ACL、证书及 Qt 合成手机联调测试仍通过。不是用户真机数据测试。
- PyInstaller 实际构建 Windows x64 onedir 完成，包含 Python/Qt/网络依赖；不要求用户安装 Python。本次没有新增依赖。
- 新 EXE 在 PATH 仅含 Windows 系统目录、无 PYTHONHOME/PYTHONPATH、原生 Qt Windows 环境启动并退出，exit code 0。probe 返回 version=0.2.1、qt_ui_started/network_thread/bundled_python/acl_verified/no_pairing 均为 true、page_count=4。测试数据目录独立，不读取实际用户配对。
- ZIP 敏感文件检查与完整性校验由 package_windows.py 执行；不含个人配对、私钥、健康数据、测试截图/日志。哈希见 Windows-0.2.1-SHA256SUMS.txt。
- APK/协议未修改；健康数据默认仍只保存在内存，用户主动确认导出才写文件。普通诊断日志仍不含远端原文。

尚未由用户真机联调新版导出；是否返回完整 page 取决于手机暂停时能否读取允许页面。导出功能不会补造未读取的节点或数据。详细操作见 WINDOWS_0.2.1_EXPORT_FIX.md，0.2.0 历史报告保留于 WINDOWS_TEST_RESULTS.md。
