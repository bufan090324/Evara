import pathlib
from PySide6.QtWidgets import QLineEdit, QLabel, QHBoxLayout, QProgressBar, QMessageBox


class UpdatePage:
    def make_update_page(self):
        self.update_available = False; self.update_launch_path = None
        layout = self.page("软件更新", "只从已配置的 HTTPS 地址检查 Evara 签名清单；下载与安装需主动确认，更新保留当前用户的配对和 AI 设置。")
        from desktop import VERSION
        layout.addWidget(QLabel("当前 Windows 版本：" + VERSION))
        self.update_source = QLineEdit(); self.update_source.setMaxLength(2048); self.update_source.setPlaceholderText("签名 update.json 的固定 HTTPS 地址")
        layout.addWidget(self.update_source)
        row = QHBoxLayout(); layout.addLayout(row)
        self.update_save = self.button(row, "保存更新源", lambda: self.send("update_save", {"url": self.update_source.text().strip()}))
        self.update_check = self.button(row, "检查新版", lambda: self.send("update_check"))
        self.update_download = self.button(row, "下载并校验新版", self.download_update)
        self.update_cancel = self.button(row, "取消检查 / 下载", lambda: self.send("update_cancel"))
        self.update_info = QLabel("等待检查；不会自动下载或安装"); self.update_info.setWordWrap(True); layout.addWidget(self.update_info)
        self.update_progress = QProgressBar(); self.update_progress.setRange(0, 100); layout.addWidget(self.update_progress)
        self.update_start = self.button(layout, "结束当前会话并启动新版", self.launch_update)
        layout.addStretch()

    def download_update(self):
        if QMessageBox.question(self, "下载 Evara 更新", "下载签名清单指定的新版并核对 SHA-256，解压到当前用户专属更新目录。不会覆盖当前程序或自动启动。是否继续？") == QMessageBox.StandardButton.Yes:
            self.update_launch_path = None; self.update_progress.setValue(0); self.send("update_download")

    def launch_update(self):
        if not self.update_launch_path or not pathlib.Path(self.update_launch_path).is_file(): return
        if QMessageBox.question(self, "启动 Evara 新版", "将结束 AI 和手机控制会话，退出当前程序后启动已校验的新版。配对和设置保留，当前旧目录仍可使用。是否继续？") != QMessageBox.StandardButton.Yes: return
        self.update_launch_requested = True; self.closing = True; self.close()

    def on_update_progress(self, event):
        if self.has_pending("update_download"):
            self.update_progress.setValue(event["done"] * 100 // max(event["total"], 1))

    def update_result(self, method, data):
        if method in {"update_load", "update_save"}:
            self.update_source.setText(data.get("source", "")); self.update_available = False
            self.update_info.setText("更新源已加载/保存；请主动检查新版")
        elif method == "update_check":
            self.update_available = data.get("available", False)
            self.update_info.setText(("发现新版：" if self.update_available else "没有高于当前版本的更新：") + data.get("version", "") + "\n" + data.get("notes", ""))
        elif method == "update_download":
            self.update_launch_path = data["executable"]; self.update_progress.setValue(100)
            self.update_info.setText("新版 " + data["version"] + " 下载、签名清单和文件哈希校验完成。点击下方按钮结束会话并启动；旧目录未覆盖。")
        elif method == "update_cancel": self.update_info.setText("已请求取消；不会自动恢复下载")

    def update_update_buttons(self):
        busy = self.has_pending("update_check", "update_download", "update_save", "update_load")
        self.update_save.setEnabled(not busy and not self.closing)
        self.update_check.setEnabled(bool(self.update_source.text()) and not busy and not self.closing)
        self.update_download.setEnabled(self.update_available and not busy and not self.closing)
        self.update_cancel.setEnabled(busy and not self.closing)
        self.update_start.setEnabled(bool(self.update_launch_path) and not busy and not self.closing)
        self.update_source.setEnabled(not busy)
