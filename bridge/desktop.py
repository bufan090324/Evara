"""PhoneBridge Windows desktop client, compatible with Android protocol v1."""
import base64
import datetime as dt
import json
import os
import pathlib
import sys
import time
import uuid
from PySide6.QtCore import Qt, QTimer, QDate, QLockFile
from PySide6.QtGui import QPixmap, QCloseEvent, QIcon, QDesktopServices, QImage, QPainter, QColor
from PySide6.QtCore import QUrl
from PySide6.QtWidgets import (QApplication, QMainWindow, QWidget, QLabel, QPushButton, QVBoxLayout,
    QHBoxLayout, QFormLayout, QLineEdit, QComboBox, QSpinBox, QStackedWidget, QListWidget,
    QSplitter, QTreeWidget, QTreeWidgetItem, QPlainTextEdit, QGroupBox, QDateEdit,
    QTableWidget, QTableWidgetItem, QHeaderView, QMessageBox, QFileDialog, QScrollArea, QCheckBox, QDialog)
from pairing_qr import pairing_matrix
from core import lan
from network_info import interfaces, recommendation
from presentation import FIELDS, STATES, rows, needs_review
from storage import user_directory
from worker import NetworkWorker
from ai_ui import AIPage
from update_ui import UpdatePage

VERSION = "1.0.2"


class ImageView(QLabel):
    def __init__(self):
        super().__init__("尚未截图\n截图仅按需读取，不持续录屏")
        self.image = QPixmap()
        self.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.setMinimumSize(220, 260)
        self.setStyleSheet("background:#eef2f6; border-radius:8px; color:#596779")

    def set_image(self, content):
        image = QPixmap()
        if not image.loadFromData(content, "JPG") or image.width() * image.height() > 30000000:
            raise ValueError("截图编码或尺寸无效")
        self.image = image
        self.render()

    def clear_image(self, reason="截图已失效，请重新获取"):
        self.image = QPixmap()
        self.clear()
        self.setText(reason)

    def render(self):
        if not self.image.isNull():
            self.setPixmap(self.image.scaled(self.size(), Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation))

    def resizeEvent(self, event):
        super().resizeEvent(event)
        self.render()


class MainWindow(QMainWindow, AIPage, UpdatePage):
    def __init__(self, directory=None):
        super().__init__()
        self.directory = pathlib.Path(directory) if directory else user_directory()
        self.worker = NetworkWorker(directory)
        self.pending = {}
        self.connected = False
        self.listening = False
        self.status = {}
        self.pairing = {}
        self.qr_dialog = None
        self.snapshot = None
        self.snapshot_time = 0.0
        self.image_time = 0.0
        self.task_data = {}
        self.task_id = None
        self.task_generation = 0
        self.task_started = 0.0
        self.task_finished = 0.0
        self.task_poll_deadline = 0.0
        self.poll_enabled = False
        self.task_observation_expired = False
        self.date_edited = False
        self.closing = False
        self.update_launch_requested = False
        self.clipboard_value = None
        self.logs = []
        self.setWindowTitle(f"Evara · Windows {VERSION}")
        self.resize(1120, 800)
        self.setMinimumSize(780, 580)
        self.setStyleSheet("""
            QMainWindow,QWidget {font-family:'Microsoft YaHei UI';font-size:10pt;color:#172a43;}
            QMainWindow {background:#f6f8fb;} QPushButton {padding:8px 14px;min-height:22px;}
            QGroupBox {font-weight:600;margin-top:12px;padding-top:12px;}
            QLineEdit,QComboBox,QSpinBox,QDateEdit {padding:6px;}
            QListWidget {background:#eaf0f9;border:0;padding:8px;}
            QListWidget::item {padding:14px 10px;border-radius:6px;}
            QListWidget::item:selected {background:#155eef;color:white;}
            QPlainTextEdit,QTreeWidget,QTableWidget {background:white;}
        """)
        outer = QWidget(); self.setCentralWidget(outer)
        layout = QHBoxLayout(outer); layout.setContentsMargins(0, 0, 12, 0)
        self.navigation = QListWidget(); self.navigation.addItems(["连接与配对", "手机页面", "睡眠报告", "设置与诊断", "AI 对话", "软件更新"]); self.navigation.setFixedWidth(160)
        self.pages = QStackedWidget(); layout.addWidget(self.navigation); layout.addWidget(self.pages, 1)
        self.navigation.currentRowChanged.connect(self.pages.setCurrentIndex)
        self.control_buttons = []
        self.cancel_buttons = []
        self.make_pair_page(); self.make_phone_page(); self.make_sleep_page(); self.make_diagnostics_page(); self.make_ai_page(); self.make_update_page()
        self.navigation.setCurrentRow(0)
        self.banner = QLabel("未连接 · 仅局域网 · AI 按授权发送"); self.statusBar().addWidget(self.banner, 1)
        self.worker.event.connect(self.on_event); self.worker.result.connect(self.on_result); self.worker.failure.connect(self.on_failure)
        self.worker.ai_event.connect(self.on_ai_event)
        self.worker.update_event.connect(self.on_update_progress)
        self.worker.finished.connect(self.on_worker_finished)
        self.timer = QTimer(self); self.timer.setInterval(1000); self.timer.timeout.connect(self.tick); self.timer.start()
        self.worker.start()
        self.update_buttons()

    def page(self, title, subtitle):
        container = QWidget(); content = QVBoxLayout(container); content.setContentsMargins(20, 18, 16, 14)
        heading = QLabel(title); heading.setStyleSheet("font-size:20pt;font-weight:600"); content.addWidget(heading)
        label = QLabel(subtitle); label.setWordWrap(True); content.addWidget(label)
        self.pages.addWidget(container)
        return content

    def button(self, layout, text, callback, control=False, cancel=False):
        b = QPushButton(text); b.clicked.connect(callback); layout.addWidget(b)
        if control: self.control_buttons.append(b)
        if cancel: self.cancel_buttons.append(b)
        return b

    def raw_box(self, layout, title):
        group = QGroupBox(title); group.setCheckable(True); group.setChecked(False)
        content = QVBoxLayout(group); raw = QPlainTextEdit(); raw.setReadOnly(True); raw.setMaximumHeight(180); raw.hide()
        content.addWidget(raw); group.toggled.connect(raw.setVisible); layout.addWidget(group)
        return raw

    def make_pair_page(self):
        layout = self.page("连接与配对", "电脑启动服务后，手机主动连接。请仅将配对资料粘贴到自己的手机，并独立核对完整证书指纹。")
        form = QFormLayout(); self.address = QComboBox(); self.address.setEditable(True); self.address.setMinimumWidth(300)
        self.computer = QLineEdit(os.environ.get("COMPUTERNAME", "我的 Windows 电脑")); self.computer.setMaxLength(80)
        self.port = QSpinBox(); self.port.setRange(1024, 65535); self.port.setValue(8765)
        form.addRow("局域网 IPv4", self.address); form.addRow("电脑名称", self.computer); form.addRow("端口", self.port); layout.addLayout(form)
        self.address_hint = QLabel(); self.address_hint.setWordWrap(True); layout.addWidget(self.address_hint)
        row = QHBoxLayout(); layout.addLayout(row)
        self.button(row, "刷新网卡地址", self.refresh_interfaces)
        self.generate_button = self.button(row, "生成首次配对", self.generate)
        self.import_button = self.button(row, "导入旧测试桥配对", self.import_pairing)
        self.pair_info = QLabel("检查配对资料中…"); self.pair_info.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse); self.pair_info.setWordWrap(True); layout.addWidget(self.pair_info)
        self.fingerprint = QLineEdit(); self.fingerprint.setReadOnly(True); self.fingerprint.setPlaceholderText("完整证书 SHA-256 指纹（不是配对凭证）"); layout.addWidget(self.fingerprint)
        row = QHBoxLayout(); layout.addLayout(row)
        self.copy_button = self.button(row, "复制配对资料", self.copy_pairing)
        self.qr_button = self.button(row, "显示配对二维码", lambda: self.send("qr_pairing"))
        self.view_button = self.button(row, "主动查看配对资料", self.view_pairing)
        self.delete_button = self.button(row, "删除配对 / 重新配对", self.delete_pairing)
        row = QHBoxLayout(); layout.addLayout(row)
        self.start_button = self.button(row, "启动服务，等待手机", lambda: self.send("start"))
        self.stop_button = self.button(row, "停止服务并断开", lambda: self.send("stop"))
        self.service_info = QLabel("服务未启动 / 手机未连接"); self.service_info.setWordWrap(True); layout.addWidget(self.service_info)
        self.pair_error = QLabel(); self.pair_error.setWordWrap(True); self.pair_error.setStyleSheet("color:#a43b19"); layout.addWidget(self.pair_error)
        layout.addStretch()
        self.refresh_interfaces()

    def make_phone_page(self):
        layout = self.page("手机页面", "仅支持桌面和华为运动健康。页面操作后会重新读取确认，不自动重复点击。截图只在内存显示。")
        row = QHBoxLayout(); layout.addLayout(row)
        self.status_button = self.button(row, "刷新设备状态", lambda: self.send("device_status"))
        self.button(row, "读取界面元素", lambda: self.send("get_ui_tree"), control=True)
        self.shot_button = self.button(row, "按需截图", lambda: self.send("get_screenshot"), control=True)
        row = QHBoxLayout(); layout.addLayout(row)
        self.button(row, "返回桌面", lambda: self.operation("go_home"), control=True)
        self.button(row, "返回上一页", lambda: self.operation("go_back"), control=True)
        self.button(row, "取消当前任务", self.cancel_task, cancel=True)
        self.device_info = QLabel("尚无手机状态"); self.device_info.setWordWrap(True); layout.addWidget(self.device_info)
        splitter = QSplitter(); layout.addWidget(splitter, 1)
        self.tree = QTreeWidget(); self.tree.setColumnCount(4); self.tree.setHeaderLabels(["文字 / 描述", "控件类型", "位置", "操作状态"]); self.tree.setWordWrap(True)
        self.tree.header().setSectionResizeMode(QHeaderView.ResizeMode.Interactive); self.tree.setColumnWidth(0, 260); self.tree.setColumnWidth(1, 180)
        splitter.addWidget(self.tree); self.image = ImageView(); splitter.addWidget(self.image); splitter.setSizes([650, 350])
        row = QHBoxLayout(); layout.addLayout(row)
        self.node_click = self.button(row, "点击选中控件", self.click_selected)
        self.scroll_forward = self.button(row, "选中控件向前滚动", lambda: self.scroll_selected("forward"))
        self.scroll_backward = self.button(row, "选中控件向后滚动", lambda: self.scroll_selected("backward"))
        self.snapshot_info = QLabel("请先读取界面；快照最多有效 10 秒"); layout.addWidget(self.snapshot_info)
        self.tree.itemSelectionChanged.connect(self.update_buttons)
        self.phone_error = QLabel(); self.phone_error.setWordWrap(True); self.phone_error.setStyleSheet("color:#a43b19"); layout.addWidget(self.phone_error)
        self.ui_raw = self.raw_box(layout, "原始 JSON（含页面内容，仅内存；主动展开）")

    def make_sleep_page(self):
        layout = self.page("睡眠报告", "读取手机当前报告。自动展开日期栏核对日历中的年份和选中日期，再收起；如需历史日期，请先在手机选择。不会声明手表已同步。")
        row = QHBoxLayout(); layout.addLayout(row)
        self.button(row, "从桌面进入并读取", lambda: self.start_task("start_sleep_task"), control=True)
        self.button(row, "读取当前详情页", lambda: self.start_task("extract_current_sleep"), control=True)
        row = QHBoxLayout(); layout.addLayout(row)
        self.button(row, "取消任务", self.cancel_task, cancel=True)
        self.query_task = self.button(row, "刷新任务状态", lambda: self.send("get_task_status"))
        self.export_button = self.button(row, "导出任务与报告 JSON", self.export_report)
        self.phone_time = QLabel("手机时间/时区尚未读取；报告日期将从手机当前详情及日历读取。"); self.phone_time.setWordWrap(True); layout.addWidget(self.phone_time)
        self.task_label = QLabel("等待任务"); self.task_label.setWordWrap(True); layout.addWidget(self.task_label)
        self.review_label = QLabel("未读取"); self.review_label.setWordWrap(True); self.review_label.setStyleSheet("font-weight:600;color:#a43b19"); layout.addWidget(self.review_label)
        self.table = QTableWidget(len(FIELDS), 7); self.table.setHorizontalHeaderLabels(["字段", "数值", "单位", "来源", "确定性", "原始证据", "原因"])
        self.table.setEditTriggers(QTableWidget.EditTrigger.NoEditTriggers); self.table.setWordWrap(True)
        self.table.horizontalHeader().setSectionResizeMode(QHeaderView.ResizeMode.Interactive); self.table.setColumnWidth(0, 110); self.table.setColumnWidth(5, 230); self.table.setColumnWidth(6, 250)
        layout.addWidget(self.table, 1)
        self.sleep_raw = self.raw_box(layout, "任务和报告原始 JSON（含健康数据，主动展开）")
        self.show_task({})

    def make_diagnostics_page(self):
        layout = self.page("设置与诊断", "不请求管理员权限，不修改防火墙，不开机自启；普通诊断不包含凭证、截图和睡眠原文。")
        self.storage_info = QLabel(f"运行资料：{self.directory}\nACL 检查中…"); self.storage_info.setWordWrap(True); layout.addWidget(self.storage_info)
        instructions = QLabel("连接失败排查：\n1. 手机与电脑连接同一路由器，电脑可以有线。检查 Wi-Fi 的 AP 隔离。\n2. 选择真实有线/无线地址，VPN 和虚拟网卡通常不适合。多张真实网卡时请手动选择。\n3. Windows 防火墙仅允许本程序在专用网络访问；不要开放公网映射。\n4. IP 改变或证书过期：停止服务，删除旧配对，重新生成，再在手机扫码或粘贴新资料。\n5. 端口占用：退出旧命令行桥/另一个实例，或换端口重新配对。\n6. 手机需解锁、启用无障碍、主动开启会话；截图需要本次单独授权。\n7. 超时可能发生在动作执行之后，请核对手机，不自动重试。\n\n支持扫码配对，图表 OCR 由新版 APK 本地执行；AI 对话页使用兼容 Responses API 的服务，由用户设置并授权。未提供 MCP 账户接入。")
        instructions.setWordWrap(True); layout.addWidget(instructions)
        row = QHBoxLayout(); layout.addLayout(row)
        self.button(row, "打开网络设置（检查专用网络）", lambda: QDesktopServices.openUrl(QUrl("ms-settings:network-status")))
        self.button(row, "打开资料目录", lambda: QDesktopServices.openUrl(QUrl.fromLocalFile(str(self.directory))))
        self.button(row, "导出脱敏诊断", self.export_diagnostics)
        self.log_view = QPlainTextEdit(); self.log_view.setReadOnly(True); layout.addWidget(self.log_view, 1)

    def refresh_interfaces(self):
        previous = self.address.currentData() or self.address.currentText().strip()
        self.address.clear(); self.address.addItem("请选择局域网 IPv4，或手动输入", "")
        found = interfaces(); suggested = recommendation(found)
        for item in found:
            self.address.addItem(f"{item['ip']} · {item['name']} · {item['kind']}" + ("（推荐）" if item['ip'] == suggested else ""), item['ip'])
        target = previous if lan(previous) else suggested
        if target:
            index = self.address.findData(target)
            if index >= 0: self.address.setCurrentIndex(index)
            else: self.address.setEditText(target)
        self.address_hint.setText("仅一个明确的有线/无线候选，已标注推荐；请核对同路由器。" if suggested else "没有唯一可靠候选，请手动选择或输入。网卡类型按系统信息及名称判断，可能存在误分类。")

    def generate(self):
        host = self.address.currentData() if self.address.currentIndex() >= 0 else None
        host = host or self.address.currentText().strip()
        if not lan(host) or not self.computer.text().strip():
            self.pair_error.setText("请选择/填写 RFC1918 局域网 IPv4 和电脑名称。")
            return
        self.send("generate", {"host": host, "port": self.port.value(), "name": self.computer.text().strip()})

    def import_pairing(self):
        folder = QFileDialog.getExistingDirectory(self, "选择含 pairing.json、cert.pem、key.pem 的旧 private 目录")
        if folder: self.send("import", {"folder": folder})

    def copy_pairing(self):
        if QMessageBox.question(self, "复制敏感资料", "配对资料包含控制凭证。只粘贴到自己的手机，不发给他人或云端 AI。剪贴板 60 秒后在内容未被改变时清除；系统剪贴板历史可能仍保留。是否复制？") == QMessageBox.StandardButton.Yes:
            self.send("copy_pairing")

    def hide_pairing_qr(self):
        if self.qr_dialog is not None: self.qr_dialog.reject()

    def show_pairing_qr(self, data):
        try: matrix = pairing_matrix(data)
        except ValueError as error:
            self.pair_error.setText(str(error)); return
        self.hide_pairing_qr()
        dialog = QDialog(self); dialog.setWindowTitle("扫码配对 · 含敏感凭证，仅给自己的手机")
        self.qr_dialog = dialog
        layout = QVBoxLayout(dialog)
        message = QLabel("Evara → 配对 → 扫描电脑配对二维码。\n扫码后在两端独立核对下方指纹，再保存并开启控制会话。\n二维码含配对凭证，请勿截图分享；2分钟后自动关闭，关闭不会撤销凭证。")
        message.setWordWrap(True); layout.addWidget(message)
        available = self.screen().availableGeometry()
        scale = 2
        image = QImage(len(matrix)*scale, len(matrix)*scale, QImage.Format.Format_RGB32); image.fill(QColor("white"))
        painter = QPainter(image)
        for y, row in enumerate(matrix):
            for x, black in enumerate(row):
                if black: painter.fillRect(x*scale,y*scale,scale,scale,QColor("black"))
        painter.end()
        display = QLabel(); display.setAlignment(Qt.AlignmentFlag.AlignCenter); display.setPixmap(QPixmap.fromImage(image)); layout.addWidget(display)
        display.setFixedSize(image.size())
        large_scale = min(4, max(2, (available.height()-270)//len(matrix)))
        zoom = QPushButton("放大二维码（难以识别时）"); layout.addWidget(zoom)
        def toggle_zoom():
            enlarged = display.width() == image.width() and large_scale > scale
            side = len(matrix) * (large_scale if enlarged else scale)
            display.setPixmap(QPixmap.fromImage(image).scaled(side, side, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.FastTransformation))
            display.setFixedSize(side,side)
            zoom.setText("缩小二维码" if enlarged else "放大二维码（难以识别时）")
            dialog.adjustSize()
        zoom.clicked.connect(toggle_zoom)
        zoom.setEnabled(large_scale > scale)
        fingerprint = QLabel("SHA-256："+data["certificate_sha256"]); fingerprint.setWordWrap(True); fingerprint.setTextInteractionFlags(Qt.TextInteractionFlag.TextSelectableByMouse); layout.addWidget(fingerprint)
        self.button(layout, "关闭二维码", dialog.reject)
        timer = QTimer(dialog); timer.setSingleShot(True); timer.timeout.connect(dialog.reject); timer.start(120000)
        try: dialog.exec()
        finally:
            display.clear(); image.fill(QColor("white")); self.qr_dialog = None; dialog.deleteLater()

    def view_pairing(self):
        if QMessageBox.question(self, "查看敏感资料", "资料含控制凭证。确认周围无人查看，并避免截图/分享。是否显示？") == QMessageBox.StandardButton.Yes:
            self.send("view_pairing")

    def delete_pairing(self):
        if QMessageBox.question(self, "撤销旧配对", "将取消任务、关闭服务并删除电脑旧证书和凭证。手机也需要删除旧配对后重新粘贴。是否继续？") == QMessageBox.StandardButton.Yes:
            self.send("delete")

    def send(self, method, params=None, context=None):
        if method in {"update_check", "update_save"}:
            self.update_launch_path = None; self.update_available = False
        identifier = uuid.uuid4().hex
        self.pending[identifier] = {"method": method, "context": context, "generation": self.task_generation, "sent": time.monotonic()}
        self.worker.submit(identifier, method, params)
        self.update_buttons()
        return identifier

    def has_pending(self, *methods):
        return any(v["method"] in methods for v in self.pending.values())

    def operation(self, method, params=None):
        self.invalidate_snapshot("操作已提交，等待重新读取确认")
        self.send(method, params)

    def valid_snapshot(self):
        return self.snapshot is not None and time.monotonic() - self.snapshot_time < 10

    def selected(self):
        item = self.tree.currentItem()
        return item.data(0, Qt.ItemDataRole.UserRole) if item else None

    def click_selected(self):
        node = self.selected()
        if not self.valid_snapshot() or not node: return
        self.operation("click_node", {"snapshot_id": self.snapshot["snapshot_id"], "node_id": node["node_id"]})

    def scroll_selected(self, direction):
        node = self.selected()
        if not self.valid_snapshot() or not node or not node.get("scrollable"): return
        self.operation("scroll", {"snapshot_id": self.snapshot["snapshot_id"], "node_id": node["node_id"], "direction": direction})

    def invalidate_snapshot(self, reason):
        self.snapshot = None
        self.snapshot_info.setText(reason)
        self.image.clear_image(reason)
        self.image_time = 0

    def start_task(self, method):
        self.task_generation += 1
        self.task_id = None
        self.task_data = {}
        self.poll_enabled = False
        self.task_observation_expired = False
        self.task_started = time.monotonic()
        self.task_finished = 0.0
        self.invalidate_snapshot("任务运行中，旧页面不能用于操作")
        self.show_task({"state": "waiting", "step": "正在提交新任务"})
        self.send(method, {"report_date_mode": "current"})

    def cancel_task(self):
        self.send("cancel_task")

    def on_event(self, event):
        kind = event["kind"]
        if kind == "worker_ready": self.send("load"); self.send("ai_load"); self.send("update_load")
        elif kind == "listening": self.listening = True
        elif kind == "connected":
            self.hide_pairing_qr()
            self.connected = True; self.status = {}; self.task_generation += 1
            self.send("device_status")
        elif kind in {"disconnected", "stopped"}:
            self.connected = False; self.status = {}; self.poll_enabled = False; self.task_generation += 1
            self.invalidate_snapshot("手机已断开，旧页面已清除")
            self.tree.clear(); self.ui_raw.clear()
            self.task_id = None; self.task_data = {}; self.show_task({})
            self.task_started = 0.0; self.task_finished = 0.0
            if kind == "stopped": self.listening = False
        detail = event.get("detail", "")
        self.service_info.setText(detail)
        self.banner.setText(f"{'已认证连接' if self.connected else '未连接'} · {'服务运行中' if self.listening else '服务停止'}")
        if kind != "worker_ready": self.log(kind)
        self.update_buttons()

    def on_result(self, identifier, data):
        record = self.pending.pop(identifier, None)
        if not record: return
        method = record["method"]
        if method.startswith("update_"):
            self.update_result(method, data); self.update_buttons(); return
        if method.startswith("ai_"):
            self.ai_result(identifier, method, data); self.update_buttons(); return
        if method in {"load", "generate", "import", "delete"}:
            self.hide_pairing_qr()
            self.pairing = data; self.pair_error.clear()
            self.pair_info.setText(f"电脑：{data.get('name', '未配对')}\n绑定：{data.get('host', '—')}:{data.get('port', '—')}\n证书到期：{data.get('expires', '—')}" + ("\n证书已过期/时间不正确，请重新配对" if data.get('expired') else ""))
            self.fingerprint.setText(data.get("fingerprint", ""))
            self.storage_info.setText(f"运行资料：{self.directory}\nACL：{data.get('acl', '未验证')}")
        elif method == "qr_pairing": self.show_pairing_qr(data)
        elif method in {"copy_pairing", "view_pairing"}:
            raw = json.dumps(data, ensure_ascii=False, indent=2)
            if method == "copy_pairing":
                QApplication.clipboard().setText(raw); self.clipboard_value = raw
                QTimer.singleShot(60000, self.clear_clipboard)
                self.pair_error.setText("已复制。仅粘贴到自己的手机；仍需独立核对证书指纹。")
            else:
                dialog = QMessageBox(self); dialog.setWindowTitle("敏感配对资料"); dialog.setText("只用于你自己的手机。不要截图或分享。"); dialog.setDetailedText(raw); dialog.exec()
        elif method == "device_status":
            if not self.connected or record["generation"] != self.task_generation: self.update_buttons(); return
            old_package = self.status.get("package"); self.status = data
            if old_package and old_package != data.get("package") or data.get("locked") or not data.get("session_active"):
                self.invalidate_snapshot("手机页面/锁屏/会话状态已改变，请刷新")
            self.device_info.setText(f"应用：{data.get('package', '未知')} · 锁屏：{data.get('locked', '未知')} · 无障碍：{data.get('accessibility_connected', False)} · 本次截图授权：{data.get('screenshot_authorized', False)}")
            self.phone_time.setText(f"手机时间：{data.get('phone_time', '未知')}\n手机时区：{data.get('timezone', '未知')}；读取手机当前报告日期，不自动映射“昨晚”。")
            task = data.get("task") or {}
            if not self.task_id and task.get("task_id"):
                self.task_id = task["task_id"]; self.task_started = time.monotonic()
                self.task_poll_deadline = time.monotonic() + 110
                self.poll_enabled = task.get("state") == "running"
            if task.get("task_id") == self.task_id and not self.task_observation_expired: self.show_task(task)
        elif method == "get_ui_tree":
            if self.connected and record["generation"] == self.task_generation: self.show_tree(data)
        elif method == "get_screenshot":
            try:
                encoded = data.get("base64", "")
                if len(encoded) > 2100000 or data.get("mime") != "image/jpeg": raise ValueError()
                content = base64.b64decode(encoded, validate=True)
                self.image.set_image(content); self.image_time = record["sent"]
                self.phone_error.setText(f"截图已读取 · 区域 {data.get('region')} · 非实时画面，最多展示 10 秒")
            except Exception: self.phone_error.setText("SCREENSHOT_FAILED：无效图像返回，未显示")
        elif method in {"start_sleep_task", "extract_current_sleep"}:
            if record["generation"] != self.task_generation: self.update_buttons(); return
            self.task_id = data.get("task_id")
            if not self.task_id: self.phone_error.setText("返回缺少 task_id，不能确认任务启动")
            else:
                self.poll_enabled = True; self.task_poll_deadline = time.monotonic() + 110
                self.send("get_task_status")
        elif method in {"get_task_status", "cancel_task"}:
            if record["generation"] == self.task_generation and (not self.task_id or data.get("task_id") == self.task_id):
                self.task_id = data.get("task_id"); self.show_task(data)
        elif method in {"go_home", "go_back", "click_node", "scroll"}:
            self.phone_error.setText("手机已接受动作，正在读取页面确认；请核对实际效果，不自动重复。")
            QTimer.singleShot(350, lambda: self.send("get_ui_tree") if self.connected and not self.task_data.get("state") == "running" else None)
        self.update_buttons()

    def on_failure(self, identifier, error):
        record = self.pending.pop(identifier, None)
        if not record: return
        method = record["method"]
        if method.startswith("update_"):
            self.update_info.setText(error); self.log("request_failed:" + method); self.update_buttons(); return
        if method.startswith("ai_"):
            self.ai_failure(identifier, method, error); self.log("request_failed:" + method); self.update_buttons(); return
        admin_methods = {"load", "generate", "import", "delete", "start", "stop", "copy_pairing", "view_pairing", "qr_pairing"}
        if method not in admin_methods and record["generation"] != self.task_generation:
            self.update_buttons(); return
        if method in {"load", "generate", "import", "delete", "start", "stop", "copy_pairing", "view_pairing", "qr_pairing"}:
            self.pair_error.setText(error)
            if method == "load":
                self.pairing = {"exists": (self.directory / "pairing").exists()}
                self.storage_info.setText(f"资料访问/ACL 检查失败：{error}")
        else:
            self.phone_error.setText(error)
            if method in {"start_sleep_task", "extract_current_sleep", "get_task_status", "cancel_task"}:
                self.poll_enabled = False; self.review_label.setText("需要核对：" + error)
            if any(code in error for code in ("STALE", "LOCKED", "OUT_OF_SCOPE", "USER_REQUIRED", "OVERLAY", "MULTI_WINDOW", "TIMEOUT")):
                self.invalidate_snapshot("操作被拒绝或结果未知，请核对手机并刷新")
            if "LOCKED" in error: self.status["locked"] = True
        self.log("request_failed:" + method)  # Never log remote messages or evidence.
        self.update_buttons()

    def show_tree(self, data):
        if not data.get("snapshot_id") or not isinstance(data.get("nodes"), list):
            self.phone_error.setText("界面结构无效"); return
        self.snapshot = data; self.snapshot_time = time.monotonic(); self.tree.clear()
        nodes = {}
        for node in data["nodes"][:1500]:
            label = (node.get("text") or "") + (" / " + node["description"] if node.get("description") else "")
            flags = "、".join(name for key, name in [("clickable", "可点击"), ("scrollable", "可滚动"), ("enabled", "可用"), ("visible", "可见")] if node.get(key))
            item = QTreeWidgetItem([label or "（操作节点）", node.get("class", ""), str(node.get("bounds", "")), flags])
            item.setData(0, Qt.ItemDataRole.UserRole, node)
            parent = nodes.get(node.get("parent"))
            if parent: parent.addChild(item)
            else: self.tree.addTopLevelItem(item)
            nodes[node.get("node_id")] = item
        self.tree.expandToDepth(1)
        self.snapshot_info.setText(f"快照 {data['snapshot_id']} · 应用 {data.get('package')} · 10 秒内且页面未改变才可操作")
        self.ui_raw.setPlainText(json.dumps(data, ensure_ascii=False, indent=2)); self.phone_error.clear()

    def show_task(self, task):
        terminal = {"completed", "cancelled", "failed", "needs_user"}
        if self.task_data.get("task_id") and task.get("task_id") == self.task_data.get("task_id") and self.task_data.get("state") in terminal and task.get("state") == "running":
            return
        self.task_data = task
        state = task.get("state", "waiting")
        if state != "running": self.poll_enabled = False
        if state in terminal and task.get("task_id") and not self.task_finished: self.task_finished = time.monotonic()
        elapsed = max(0, int((self.task_finished or time.monotonic()) - self.task_started)) if self.task_started else 0
        self.task_label.setText(f"状态：{STATES.get(state, state)} · 耗时（电脑观测）：{elapsed} 秒\n任务：{task.get('task_id') or '—'}\n步骤：{task.get('step', '未开始')}\n{task.get('reason') or ''}")
        self.review_label.setText("需要核对 / 未读取完整证据" if needs_review(task) else "已完成：手机返回可核对证据，请与手机报告核对")
        from failures import task_failure, LABELS
        fault = task_failure(task)
        if fault: self.review_label.setText("[" + LABELS[fault["category"]] + "/" + fault["code"] + "] " + fault["message"])
        for i, record in enumerate(rows(task)):
            for j, value in enumerate(record):
                item = QTableWidgetItem(value); item.setToolTip(value); self.table.setItem(i, j, item)
        self.table.resizeRowsToContents()
        self.sleep_raw.setPlainText(json.dumps(task, ensure_ascii=False, indent=2))
        self.export_button.setEnabled(bool(task.get("task_id") or task.get("result") or task.get("page")))

    def tick(self):
        now = time.monotonic()
        if self.image_time and now - self.image_time >= 10:
            self.image.clear_image("截图已过期，已清除；请按需重新获取"); self.image_time = 0
        if self.snapshot and not self.valid_snapshot():
            self.snapshot = None; self.snapshot_info.setText("快照已过期，请重新读取")
        if self.connected and not self.closing and not self.ai_busy:
            if self.poll_enabled:
                if now >= self.task_poll_deadline:
                    self.poll_enabled = False; self.review_label.setText("查询超过 110 秒，已停止自动查询。任务结果未知，请手动刷新或取消。")
                    self.task_observation_expired = True
                elif not self.has_pending("get_task_status", "device_status", "cancel_task"):
                    self.send("get_task_status")
            elif int(now) % 3 == 0 and not self.has_pending("device_status", "get_task_status"):
                self.send("device_status")
        self.update_buttons()

    def update_buttons(self):
        administrative = self.has_pending("load", "generate", "import", "delete", "start", "stop", "copy_pairing", "view_pairing", "qr_pairing")
        exists = self.pairing.get("exists", False)
        self.generate_button.setEnabled(not exists and not administrative and not self.listening)
        self.import_button.setEnabled(not exists and not administrative and not self.listening)
        self.copy_button.setEnabled(exists and not administrative)
        self.view_button.setEnabled(exists and not administrative)
        self.qr_button.setEnabled(exists and not self.pairing.get("expired",False) and not administrative)
        self.delete_button.setEnabled(exists and not administrative)
        self.start_button.setEnabled(exists and not self.pairing.get("expired", False) and not self.listening and not administrative)
        self.stop_button.setEnabled(self.listening and not administrative)
        self.status_button.setEnabled(self.connected and not self.has_pending("device_status"))
        self.query_task.setEnabled(self.connected and not self.has_pending("get_task_status"))
        busy = self.ai_busy or self.task_data.get("state") == "running" or any(v["method"] not in {"device_status", "get_task_status", "cancel_task"} for v in self.pending.values())
        safe = self.connected and bool(self.status.get("session_active")) and self.status.get("locked") is False and bool(self.status.get("accessibility_connected")) and not busy and not self.closing
        for b in self.control_buttons: b.setEnabled(safe)
        self.shot_button.setEnabled(safe and bool(self.status.get("screenshot_authorized")))
        for b in self.cancel_buttons: b.setEnabled(self.connected and not self.has_pending("cancel_task") and not self.closing)
        node = self.selected(); valid = safe and self.valid_snapshot() and bool(node and node.get("enabled") and node.get("visible"))
        self.node_click.setEnabled(valid and bool(node.get("clickable")))
        self.scroll_forward.setEnabled(valid and bool(node.get("scrollable")))
        self.scroll_backward.setEnabled(valid and bool(node.get("scrollable")))

        self.update_ai_buttons(); self.update_update_buttons()

    def clear_clipboard(self):
        if self.clipboard_value and QApplication.clipboard().text() == self.clipboard_value: QApplication.clipboard().clear()
        self.clipboard_value = None

    def export_report(self):
        if not (self.task_data.get("task_id") or self.task_data.get("result") or self.task_data.get("page")): return
        payload = json.loads(json.dumps(self.task_data))
        if QMessageBox.question(self, "导出任务与健康数据", "文件包含任务状态、日期、失败原因，以及实际返回的报告或页面节点，可能含健康原文。导出会保存到磁盘，请勿分享给无关人员。是否继续？") != QMessageBox.StandardButton.Yes: return
        path, _ = QFileDialog.getSaveFileName(self, "导出任务与报告", "Evara-任务与报告.json", "JSON (*.json)")
        if path:
            try: pathlib.Path(path).write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
            except OSError: QMessageBox.warning(self, "保存失败", "无法写入选择的文件")

    def log(self, code):
        self.logs.append(dt.datetime.now().isoformat(timespec="seconds") + " " + code)
        self.logs = self.logs[-200:]; self.log_view.setPlainText("\n".join(self.logs))

    def export_diagnostics(self):
        path, _ = QFileDialog.getSaveFileName(self, "导出脱敏诊断", "Evara-诊断.txt", "文本 (*.txt)")
        if path:
            try: pathlib.Path(path).write_text(f"Evara Desktop {VERSION}\n" + "\n".join(self.logs), encoding="utf-8")
            except OSError: QMessageBox.warning(self, "保存失败", "无法写入选择的文件")

    def closeEvent(self, event):
        self.hide_pairing_qr()
        if not self.closing and (self.listening or self.connected or self.task_data.get("state") == "running" or self.ai_busy):
            if QMessageBox.question(self, "结束控制会话", "服务/控制会话仍在运行。退出将尝试取消任务、关闭连接并释放端口；未收到取消确认时也会断开。是否退出？") != QMessageBox.StandardButton.Yes:
                event.ignore(); return
        if self.worker.isRunning():
            self.closing = True; self.timer.stop(); self.clear_clipboard(); self.worker.shutdown()
            self.banner.setText("正在关闭网络线程与连接，请稍候…"); self.centralWidget().setEnabled(False)
            event.ignore(); return
        self.clear_clipboard(); event.accept()

    def on_worker_finished(self):
        if self.closing: self.close()


def main():
    QApplication.setHighDpiScaleFactorRoundingPolicy(Qt.HighDpiScaleFactorRoundingPolicy.PassThrough)
    app = QApplication(sys.argv); app.setWindowIcon(QIcon(str((pathlib.Path(__file__).resolve().parent / "assets" / "evara.ico") if getattr(sys, "frozen", False) else (pathlib.Path(__file__).resolve().parent.parent / "assets" / "evara.ico")))); app.setApplicationName("Evara"); app.setOrganizationName("PhoneBridge")
    # Optional package smoke probe writes only to an explicitly supplied test directory.
    probe = pathlib.Path(sys.argv[2]).resolve() if len(sys.argv) == 3 and sys.argv[1] == "--package-probe" else None
    directory = (probe / "appdata") if probe else user_directory(); directory.mkdir(parents=True, exist_ok=True)
    lock = QLockFile(str(directory / "desktop.lock")); lock.setStaleLockTime(30000)
    if not lock.tryLock(100):
        QMessageBox.information(None, "Evara已运行", "当前用户已有Evara实例，请使用已打开的窗口。")
        return 0
    window = MainWindow(directory if probe else None); window.show()
    if probe:
        def verify():
            try:
                probe.mkdir(parents=True, exist_ok=True)
                window.grab().save(str(probe / "startup.png"))
                window.navigation.setCurrentRow(4)
                window.grab().save(str(probe / "ai-page.png"))
                from ai_settings import AISettings
                import httpx
                ai_probe = AISettings(probe / "crypto-probe")
                ai_probe.save("https://example.invalid/v1", "synthetic-model", "synthetic-package-probe-key")
                encrypted_ok = ai_probe.load()["key"] == "synthetic-package-probe-key" and b"synthetic-package-probe-key" not in ai_probe.path.read_bytes()
                ai_probe.delete()
                report = {"version": VERSION, "qt_ui_started": True, "network_thread": window.worker.isRunning(),
                          "bundled_python": bool(getattr(sys, "frozen", False)),
                          "acl_verified": bool(window.worker.store and window.worker.store.acl_status.startswith("已验证")),
                          "no_pairing": not window.pairing.get("exists", False), "page_count": window.pages.count(),
                          "ai_http_runtime": httpx.__version__, "ai_dpapi_verified": encrypted_ok,
                          "ai_cloud_consent_default_off": not window.ai_consent.isChecked(),
                          "ai_screenshot_consent_default_off": not window.ai_screenshot.isChecked(),
                          "qr_matrix_generated": bool(pairing_matrix(dict(protocol=1,host="192.168.1.4",port=8765,name="测试电脑",certificate_der="A"*1100,certificate_sha256="1"*64,token="synthetic_test_token_"+"A"*44)))}
                (probe / "probe.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
            finally:
                window.closing = True; window.close()
        QTimer.singleShot(3000, verify)
    result = app.exec(); lock.unlock()
    if window.update_launch_path and window.update_launch_requested:
        import subprocess
        subprocess.Popen([window.update_launch_path], cwd=str(pathlib.Path(window.update_launch_path).parent))
    return result


if __name__ == "__main__":
    raise SystemExit(main())
