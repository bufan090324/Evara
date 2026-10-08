"""Native Qt AI page, with explicit per-run cloud and phone consent."""
import time
from PySide6.QtWidgets import QLabel, QLineEdit, QFormLayout, QHBoxLayout, QPlainTextEdit, QCheckBox, QMessageBox


class AIPage:
    def make_ai_page(self):
        self.ai_busy = False
        self.ai_run_id = None
        self.ai_ready = False
        layout = self.page("AI 对话", "兼容 Responses API。对话及授权读取的健康数据会发送到所选服务，离开局域网；截图另行授权。API 费用及数据保留由服务提供商决定。")
        form = QFormLayout()
        self.ai_endpoint = QLineEdit("https://api.openai.com/v1"); self.ai_endpoint.setMaxLength(500)
        self.ai_model = QLineEdit(); self.ai_model.setPlaceholderText("填写服务支持工具调用的模型名称"); self.ai_model.setMaxLength(128)
        self.ai_key = QLineEdit(); self.ai_key.setEchoMode(QLineEdit.EchoMode.Password); self.ai_key.setMaxLength(8192)
        self.ai_key.setPlaceholderText("未设置；密钥只在此输入，保存后清空输入框")
        form.addRow("API 基础地址", self.ai_endpoint); form.addRow("模型", self.ai_model); form.addRow("API Key", self.ai_key)
        layout.addLayout(form)
        row = QHBoxLayout(); layout.addLayout(row)
        self.ai_save = self.button(row, "加密保存设置", self.save_ai_settings)
        self.ai_test = self.button(row, "测试 API 与工具调用", self.test_ai)
        self.ai_delete = self.button(row, "删除 AI 设置", self.delete_ai_settings)
        self.ai_info = QLabel("正在检查 AI 设置…"); self.ai_info.setWordWrap(True); layout.addWidget(self.ai_info)
        self.ai_consent = QCheckBox("允许将本轮对话及读取到的健康数据发送给所选 AI 服务")
        self.ai_control = QCheckBox("允许 AI 发起手机读取/取消任务（本次运行）")
        self.ai_screenshot = QCheckBox("允许将必要的手机截图发送给 AI（本次运行，需要视觉模型）")
        for checkbox in [self.ai_consent, self.ai_control, self.ai_screenshot]:
            layout.addWidget(checkbox); checkbox.toggled.connect(self.ai_authorization_changed)
        self.ai_transcript = QPlainTextEdit(); self.ai_transcript.setReadOnly(True)
        self.ai_transcript.setPlaceholderText("示例：读取当前睡眠详情并解释结果；从桌面打开运动健康读取当前报告。\n工具结果中的缺失、日期不确定和冲突会保留，不能以 AI 的解释替代手机原文。")
        layout.addWidget(self.ai_transcript, 1)
        self.ai_progress = QLabel("等待消息"); self.ai_progress.setWordWrap(True); layout.addWidget(self.ai_progress)
        self.ai_input = QPlainTextEdit(); self.ai_input.setMaximumHeight(90); self.ai_input.setPlaceholderText("输入消息（最多 8000 字符）")
        layout.addWidget(self.ai_input)
        row = QHBoxLayout(); layout.addLayout(row)
        self.ai_send = self.button(row, "发送给 AI", self.send_ai_message)
        self.ai_stop = self.button(row, "停止 AI 与本轮手机任务", lambda: self.send("ai_cancel"))
        self.ai_clear = self.button(row, "清除内存对话", lambda: self.send("ai_clear"))

    def ai_authorization_changed(self):
        if self.ai_busy: self.send("ai_cancel")
        self.update_buttons()

    def save_ai_settings(self):
        self.send("ai_save", {"endpoint": self.ai_endpoint.text(), "model": self.ai_model.text(), "key": self.ai_key.text()})
        self.ai_key.clear()

    def test_ai(self):
        if QMessageBox.question(self, "测试 AI 服务", "测试将向已保存服务发送一段不含健康数据的文本与测试工具，可能产生 API 费用，不操作手机。是否继续？") == QMessageBox.StandardButton.Yes:
            self.send("ai_test")

    def delete_ai_settings(self):
        if QMessageBox.question(self, "删除 AI 设置", "删除当前用户加密保存的服务设置与密钥，并清除内存对话。是否继续？") == QMessageBox.StandardButton.Yes:
            self.send("ai_delete")

    def send_ai_message(self):
        text = self.ai_input.toPlainText().strip()
        if self.ai_busy or not self.ai_ready or not self.ai_consent.isChecked(): return
        if not text or len(text) > 8000:
            self.ai_progress.setText("消息不能为空且不能超过 8000 字符"); return
        if self.pending or self.task_data.get("state") == "running":
            self.ai_progress.setText("请等待手动操作/手机任务结束后再发送"); return
        self.ai_transcript.appendPlainText("你：" + text + "\n")
        self.ai_input.clear(); self.ai_busy = True
        self.ai_run_id = self.send("ai_chat", {"text": text, "consent": True, "control": self.ai_control.isChecked(), "screenshot": self.ai_screenshot.isChecked()})
        self.update_buttons()

    def on_ai_event(self, identifier, event):
        if identifier != self.ai_run_id or not self.ai_busy: return
        if event.get("kind") == "progress": self.ai_progress.setText(event.get("text", ""))
        elif event.get("kind") == "task":
            task = event["data"]
            if self.task_id != task.get("task_id"):
                self.task_generation += 1; self.task_started = time.monotonic(); self.task_finished = 0
            self.task_id = task.get("task_id"); self.poll_enabled = False
            self.show_task(task); self.invalidate_snapshot("AI 任务执行期间旧页面已失效")
            self.ai_progress.setText("手机任务：" + str(task.get("step", "")))
        self.update_buttons()

    def ai_result(self, identifier, method, data):
        if method in {"ai_load", "ai_save", "ai_delete"}:
            self.ai_ready = data.get("key_set", False)
            self.ai_endpoint.setText(data.get("endpoint", "")); self.ai_model.setText(data.get("model", ""))
            self.ai_key.clear(); self.ai_key.setPlaceholderText("已加密保存；留空保留旧密钥" if self.ai_ready else "请填写 API Key")
            self.ai_info.setText("当前用户 DPAPI 加密设置已加载；请测试兼容性。修改后先保存，测试和对话均使用已保存配置。" if self.ai_ready else "尚未设置 API Key；手机手动功能仍可使用")
            if method == "ai_delete": self.ai_transcript.clear()
        elif method == "ai_test": self.ai_info.setText(data.get("message", "测试完成") + ("；DeepSeek 使用非思考兼容配置，手机操作仍由 Evara 校验" if self.ai_endpoint.text().rstrip("/") in {"https://api.deepseek.com", "https://api.deepseek.com/v1"} else ""))
        elif method == "ai_clear": self.ai_transcript.clear(); self.ai_progress.setText("对话已清除（仅内存）")
        elif method == "ai_chat" and identifier == self.ai_run_id:
            self.ai_busy = False; self.ai_run_id = None
            self.ai_transcript.appendPlainText("AI：" + data.get("answer", "") + "\n")
            self.ai_progress.setText("本轮已结束；结构化证据可在“睡眠报告”核对")

    def ai_failure(self, identifier, method, error):
        if method == "ai_chat" and identifier == self.ai_run_id:
            self.ai_busy = False; self.ai_run_id = None
            self.ai_progress.setText(error)
            self.ai_transcript.appendPlainText("系统：" + error + "\n")
        else: self.ai_info.setText(error)

    def update_ai_buttons(self):
        pending = self.has_pending("ai_load", "ai_save", "ai_delete", "ai_test", "ai_clear")
        editing = not self.ai_busy and not pending and not self.closing
        for widget in [self.ai_endpoint, self.ai_model, self.ai_key, self.ai_save, self.ai_delete, self.ai_clear]: widget.setEnabled(editing)
        self.ai_test.setEnabled(editing and self.ai_ready)
        self.ai_send.setEnabled(editing and self.ai_ready and self.ai_consent.isChecked() and not self.pending and self.task_data.get("state") != "running")
        self.ai_stop.setEnabled(self.ai_busy and not self.has_pending("ai_cancel") and not self.closing)
