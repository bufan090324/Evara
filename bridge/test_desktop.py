import asyncio
import base64
import json
import os
import pathlib
import socket
import ssl
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import datetime as dt
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
from PySide6.QtWidgets import QApplication, QMessageBox
from PySide6.QtGui import QImage, QColor
from PySide6.QtCore import QBuffer, QByteArray, QIODevice, QDate
from websockets.asyncio.client import connect
from core import load_pairing, proof, Bridge
from desktop import MainWindow
from storage import PairingStore, protect_directory
from network_info import classify, recommendation, interfaces
from presentation import rows, needs_review


class StorageTests(unittest.TestCase):
    def test_acl_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as d:
            store = PairingStore(pathlib.Path(d) / "appdata")
            p = store.generate("192.168.1.4", 8765, "测试电脑")
            self.assertIn("已验证", store.acl_status)
            self.assertEqual(p, store.load())
            with self.assertRaises(ValueError): store.generate("192.168.1.5", 8766, "覆盖")
            import win32security as sec
            for path in [store.directory, store.folder, *(store.folder / n for n in ["key.pem", "cert.pem", "pairing.json"])]:
                sd = sec.GetNamedSecurityInfo(str(path), sec.SE_FILE_OBJECT, sec.DACL_SECURITY_INFORMATION)
                self.assertEqual(1, sd.GetSecurityDescriptorDacl().GetAceCount())
                self.assertTrue(sd.GetSecurityDescriptorControl()[0] & sec.SE_DACL_PROTECTED)
            store.delete(); self.assertIsNone(store.load())

    def test_certificate_and_pairing_mismatch_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            store = PairingStore(pathlib.Path(d) / "appdata"); store.generate("192.168.1.4", 8765, "test")
            p = store.load(); p["certificate_sha256"] = "0" * 64
            (store.folder / "pairing.json").write_text(json.dumps(p))
            with self.assertRaises(ValueError): load_pairing(store.folder)

    def test_import_retains_original_credentials(self):
        with tempfile.TemporaryDirectory() as d:
            a = PairingStore(pathlib.Path(d) / "a"); b = PairingStore(pathlib.Path(d) / "b")
            a.generate("192.168.1.4", 8765, "test"); b.import_existing(a.folder)
            self.assertEqual(a.load(), b.load())
            self.assertEqual((a.folder / "key.pem").read_bytes(), (b.folder / "key.pem").read_bytes())

    def test_deletion_refuses_outside_directory(self):
        with tempfile.TemporaryDirectory() as d:
            store = PairingStore(pathlib.Path(d) / "a"); outside = pathlib.Path(d) / "outside"; outside.mkdir()
            with self.assertRaises(RuntimeError): store._remove(outside)
            self.assertTrue(outside.exists())

    def test_expired_certificate_is_not_started(self):
        with tempfile.TemporaryDirectory() as d:
            store = PairingStore(pathlib.Path(d) / "appdata"); store.generate("192.168.1.4", 8765, "test")
            p = store.load(); old = x509.load_pem_x509_certificate((store.folder / "cert.pem").read_bytes())
            key = serialization.load_pem_private_key((store.folder / "key.pem").read_bytes(), None)
            now = dt.datetime.now(dt.timezone.utc)
            cert = (x509.CertificateBuilder().subject_name(old.subject).issuer_name(old.issuer).public_key(key.public_key())
                    .serial_number(x509.random_serial_number()).not_valid_before(now - dt.timedelta(days=2))
                    .not_valid_after(now - dt.timedelta(days=1)).add_extension(old.extensions.get_extension_for_class(x509.SubjectAlternativeName).value, False).sign(key, hashes.SHA256()))
            p["certificate_der"] = base64.b64encode(cert.public_bytes(serialization.Encoding.DER)).decode(); p["certificate_sha256"] = cert.fingerprint(hashes.SHA256()).hex()
            (store.folder / "cert.pem").write_bytes(cert.public_bytes(serialization.Encoding.PEM)); (store.folder / "pairing.json").write_text(json.dumps(p))
            with self.assertRaises(ValueError): load_pairing(store.folder)
            self.assertEqual(p, store.load(allow_expired=True))


class PresentationTests(unittest.TestCase):
    def test_night_and_naps_remain_distinct_from_total(self):
        task={"state":"needs_user","result":{"success":False,"fields":{key:{"value":value,"unit":"minute","state":"observed","source":"accessibility"} for key,value in [("total",450),("night",420),("naps",30)]}}}
        records={r[0]:r[1] for r in rows(task)}
        self.assertEqual("450 分钟",records["总睡眠时长"]);self.assertEqual("420 分钟",records["夜间睡眠时长"]);self.assertEqual("30 分钟",records["零星小睡时长"])

    def test_missing_not_zero_and_evidence_retained(self):
        task = dict(state="completed", result={"success": True, "report_date": {"value": "2026-10-06", "state": "verified"}, "fields": {"total": {"value": 450, "unit": "minute", "state": "observed", "source": "accessibility", "evidence": ["合成时长 7小时30分钟"]}}})
        r = rows(task); self.assertEqual("450 分钟", r[1][1]); self.assertEqual("未读取", r[-1][1]); self.assertIn("合成", r[1][5]); self.assertTrue(needs_review(task))

    def test_uncertain_or_conflict_never_green(self):
        for state in ["uncertain", "conflict", "mismatch", "missing"]:
            self.assertTrue(needs_review({"state": "completed", "result": {"success": True, "report_date": {"value": "10月6日", "state": state}}}))

    def test_interface_classification_and_ambiguity(self):
        self.assertIn("VPN", classify("Oray VPN Adapter", "Ethernet")); self.assertEqual("虚拟网卡", classify("VMware", "Ethernet"))
        self.assertEqual("无线", classify("WLAN", "Wifi"))
        self.assertIsNone(recommendation([{"kind": "有线", "ip": "10.0.0.1"}, {"kind": "无线", "ip": "10.0.1.1"}]))
        self.assertEqual("10.0.0.1", recommendation([{"kind": "有线", "ip": "10.0.0.1"}, {"kind": "VPN", "ip": "10.1.0.1"}]))


class SerialTests(unittest.IsolatedAsyncioTestCase):
    async def test_old_queued_operation_cannot_cross_connection(self):
        bridge = Bridge("token")
        bridge.phone = object()
        await bridge.operation_lock.acquire()
        queued = asyncio.create_task(bridge.request("go_home"))
        await asyncio.sleep(0)
        bridge.epoch += 1
        bridge.operation_lock.release()
        with self.assertRaises(ConnectionError): await queued
        self.assertEqual(0, bridge.queued)

    async def test_cancel_bypasses_serial_operations(self):
        bridge = Bridge("token"); bridge.phone = object()
        async def fake(method, params=None, timeout_ms=10000): return {"ok": True, "data": {"method": method}}
        bridge._request = fake
        await bridge.operation_lock.acquire()
        result = await asyncio.wait_for(bridge.request("cancel_task"), .2)
        self.assertEqual("cancel_task", result["data"]["method"])
        bridge.operation_lock.release()

    async def test_operation_queue_bound(self):
        bridge = Bridge("token"); bridge.phone = object(); await bridge.operation_lock.acquire()
        tasks = [asyncio.create_task(bridge.request("go_home")) for _ in range(8)]
        await asyncio.sleep(.01)
        with self.assertRaises(ValueError): await bridge.request("go_home")
        for task in tasks: task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        self.assertEqual(0, bridge.queued); bridge.operation_lock.release()


class FakePhone:
    """Real WSS peer speaking the APK protocol; all health values are synthetic."""
    def __init__(self, p, cert, picture):
        self.p = p; self.cert = cert; self.picture = picture
        self.task = {"task_id": "", "state": "waiting"}
        self.commands = []; self.quit = threading.Event(); self.ready = threading.Event(); self.error = None
        self.thread = threading.Thread(target=self.run, daemon=True)

    def run(self):
        async def go():
            tls = ssl.create_default_context(cafile=str(self.cert))
            async with connect(f"wss://{self.p['host']}:{self.p['port']}/bridge", ssl=tls, proxy=None) as ws:
                challenge = json.loads(await ws.recv())
                await ws.send(json.dumps(dict(type="auth", protocol=1, proof=proof(self.p["token"], challenge["nonce"]))))
                assert json.loads(await ws.recv())["type"] == "auth_ok"
                self.ready.set()
                while not self.quit.is_set():
                    try: raw = await asyncio.wait_for(ws.recv(), .1)
                    except TimeoutError: continue
                    m = json.loads(raw); method = m["method"]; self.commands.append(m)
                    if method == "device_status":
                        data = dict(connected=True, session_active=True, locked=False, accessibility_connected=True, screenshot_authorized=True,
                                    package="com.huawei.health", phone_time="2026-10-07T12:00:00+08:00[Asia/Hong_Kong]", timezone="Asia/Hong_Kong", task=self.task)
                    elif method == "get_ui_tree":
                        data = dict(snapshot_id="fixture-ui", package="com.huawei.health", nodes=[dict(node_id="0", parent=None, text="合成测试页面", description="", **{"class": "FrameLayout"}, bounds=[0, 0, 40, 80], enabled=True, visible=True, clickable=True, scrollable=True)])
                    elif method == "get_screenshot":
                        data = dict(snapshot_id="fixture-shot", mime="image/jpeg", region=[10, 20, 50, 100], base64=self.picture)
                    elif method in {"start_sleep_task", "extract_current_sleep"}:
                        self.task = {"task_id": m["id"], "state": "running", "step": "合成任务等待"}; data = {"task_id": self.task["task_id"]}
                    elif method == "cancel_task":
                        self.task = {**self.task, "state": "cancelled", "step": "合成任务取消"}; data = self.task
                    elif method == "get_task_status": data = self.task
                    else: data = {"accepted": True}
                    await ws.send(json.dumps(dict(type="response", protocol=1, id=m["id"], ok=True, data=data)))
        try: asyncio.run(go())
        except Exception as error:
            if not self.quit.is_set(): self.error = type(error).__name__


class GuiTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls): cls.app = QApplication.instance() or QApplication([])
    def pump(self, predicate, timeout=8):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            self.app.processEvents()
            if predicate(): return
            time.sleep(.01)
        self.fail("GUI condition timed out")

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.window = MainWindow(pathlib.Path(self.temp.name) / "appdata"); self.window.show()
        self.pump(lambda: not self.window.pending and self.window.worker.store is not None and self.window.worker.store.acl_status.startswith("已验证"))
        self.phone = None

    def tearDown(self):
        if self.phone:
            self.phone.quit.set(); self.phone.thread.join(2)
        self.window.closing = True; self.window.close()
        self.pump(lambda: not self.window.worker.isRunning())
        self.window.worker.wait(1000); self.window.close(); self.temp.cleanup()

    def test_first_use_ui_buttons(self):
        self.assertFalse(self.window.connected); self.assertTrue(self.window.generate_button.isEnabled())
        self.assertFalse(self.window.shot_button.isEnabled()); self.assertFalse(self.window.node_click.isEnabled())
        self.assertFalse(self.window.start_button.isEnabled())
        self.assertFalse(self.window.qr_button.isEnabled())

    def test_qr_dialog_has_image_and_is_cleared_after_close(self):
        from test_pairing_qr import sample_pairing
        from PySide6.QtWidgets import QDialog, QLabel
        def inspect(*args):
            dialog=self.window.qr_dialog
            self.assertIsNotNone(dialog)
            self.assertTrue(any(not label.pixmap().isNull() for label in dialog.findChildren(QLabel) if label.pixmap() is not None))
            return 0
        with patch.object(QDialog,"exec",inspect): self.window.show_pairing_qr(sample_pairing())
        self.assertIsNone(self.window.qr_dialog)

    def test_qr_is_hidden_on_authenticated_connection(self):
        from PySide6.QtWidgets import QDialog
        dialog=QDialog();self.window.qr_dialog=dialog
        with patch.object(dialog,"reject") as reject, patch.object(self.window,"send"):
            self.window.on_event({"kind":"connected"})
            reject.assert_called_once()
        self.window.qr_dialog=None

    def test_current_report_ui_has_no_date_selector(self):
        self.window.connected = True
        record = {"generation": self.window.task_generation}
        self.window.pending["date-test"] = {**record, "method": "device_status"}
        self.window.on_result("date-test", {"phone_time": "2026-01-01T00:05:00+08:00", "timezone": "Asia/Shanghai"})
        self.assertFalse(hasattr(self.window,"date"))
        self.assertFalse(hasattr(self.window,"date_confirmed"))
        self.assertIn("当前报告日期",self.window.phone_time.text())

    def test_task_requests_current_report_without_assumed_date(self):
        with patch.object(self.window,"send") as send:
            self.window.start_task("extract_current_sleep")
            send.assert_called_with("extract_current_sleep",{"report_date_mode":"current"})

    def test_disconnect_clears_current_report_result(self):
        self.window.on_event({"kind":"disconnected"})
        self.assertFalse(self.window.task_data)

    def test_export_empty_task_is_disabled_and_does_not_prompt(self):
        self.window.show_task({})
        self.assertFalse(self.window.export_button.isEnabled())
        with patch("desktop.QMessageBox.question") as question:
            self.window.export_report(); question.assert_not_called()

    def test_export_paused_task_preserves_page_without_report(self):
        task={"task_id":"paused", "state":"needs_user", "reason":"多个入口", "result":None,
              "page":{"snapshot_id":"x", "nodes":[{"node_id":"0/1", "text":"睡眠", "parent":"0", "bounds":[1,2,3,4]}]}}
        self.window.show_task(task)
        self.assertTrue(self.window.export_button.isEnabled())
        target=pathlib.Path(self.temp.name)/"paused.json"
        with patch("desktop.QMessageBox.question",return_value=QMessageBox.StandardButton.Yes), patch("desktop.QFileDialog.getSaveFileName",return_value=(str(target),"JSON")):
            self.window.export_button.click()
        self.assertEqual(task,json.loads(target.read_text(encoding="utf-8")))

    def test_export_declined_does_not_save_health_data(self):
        self.window.show_task({"task_id":"cancelled", "state":"cancelled", "result":None})
        self.assertTrue(self.window.export_button.isEnabled())
        with patch("desktop.QMessageBox.question",return_value=QMessageBox.StandardButton.No), patch("desktop.QFileDialog.getSaveFileName") as save:
            self.window.export_report(); save.assert_not_called()

    def test_export_pins_task_before_modal_late_update(self):
        original={"task_id":"first", "state":"failed", "reason":"入口不唯一", "result":None}
        self.window.show_task(original)
        target=pathlib.Path(self.temp.name)/"first.json"
        def confirm(*args):
            self.window.show_task({"task_id":"new", "state":"running", "result":None})
            return QMessageBox.StandardButton.Yes
        with patch("desktop.QMessageBox.question",side_effect=confirm), patch("desktop.QFileDialog.getSaveFileName",return_value=(str(target),"JSON")):
            self.window.export_report()
        self.assertEqual(original,json.loads(target.read_text(encoding="utf-8")))

    def test_late_task_does_not_replace_cancelled_task(self):
        self.window.task_started = time.monotonic()
        self.window.show_task({"task_id": "new", "state": "cancelled"})
        self.window.show_task({"task_id": "new", "state": "running"})
        self.assertEqual("cancelled", self.window.task_data["state"])

    def test_snapshot_expiry_and_memory_image(self):
        self.window.show_tree({"snapshot_id": "x", "nodes": []})
        self.window.snapshot_time -= 11; self.window.tick(); self.assertIsNone(self.window.snapshot)
        self.assertEqual([], list(pathlib.Path(self.temp.name).rglob("*.jpg")))

    def test_real_wss_gui_task_cancel_and_port_release(self):
        candidates = interfaces()
        if not candidates: self.skipTest("没有可用于实际 WSS 的本机私有 IPv4")
        host = recommendation(candidates) or candidates[0]["ip"]
        with socket.socket() as sock: sock.bind((host, 0)); port = sock.getsockname()[1]
        self.window.send("generate", dict(host=host, port=port, name="合成测试电脑"))
        self.pump(lambda: self.window.pairing.get("exists"))
        self.window.send("start"); self.pump(lambda: self.window.listening)
        p = self.window.worker.store.load()
        image = QImage(40, 80, QImage.Format.Format_RGB32); image.fill(QColor("#155eef"))
        buffer = QBuffer(); buffer.open(QIODevice.OpenModeFlag.WriteOnly); image.save(buffer, "JPG")
        picture = base64.b64encode(bytes(buffer.data())).decode()
        self.phone = FakePhone(p, self.window.worker.store.folder / "cert.pem", picture); self.phone.thread.start()
        self.pump(lambda: self.window.connected and self.window.status.get("session_active"))
        self.assertIsNone(self.phone.error)
        self.window.send("get_ui_tree"); self.pump(lambda: self.window.snapshot is not None)
        self.assertEqual(1, self.window.tree.topLevelItemCount())
        self.window.tree.setCurrentItem(self.window.tree.topLevelItem(0)); self.window.update_buttons()
        self.assertTrue(self.window.node_click.isEnabled())
        self.window.send("get_screenshot"); self.pump(lambda: not self.window.image.image.isNull())
        self.assertEqual(40, self.window.image.image.width()); self.assertEqual(80, self.window.image.image.height())
        self.window.start_task("start_sleep_task"); self.pump(lambda: self.window.task_data.get("state") == "running")
        self.window.cancel_task(); self.pump(lambda: self.window.task_data.get("state") == "cancelled")
        self.assertIn("cancel_task", [m["method"] for m in self.phone.commands])
        self.assertFalse(any(pathlib.Path(self.temp.name).rglob("*.jpg")))
        self.window.send("stop"); self.pump(lambda: not self.window.listening and not self.window.connected)
        with socket.socket() as sock: sock.bind((host, port))

    def test_closed_polling_bounded(self):
        self.window.connected = True; self.window.poll_enabled = True; self.window.task_poll_deadline = time.monotonic() - 1
        self.window.tick(); self.assertFalse(self.window.poll_enabled)

    def test_ai_http_wss_report_and_cancellation(self):
        from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
        calls = []
        mode = {"value": "read"}
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_POST(self):
                request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                calls.append(request)
                last = request["input"][-1]
                if last.get("type") == "function_call_output":
                    output = [{"type": "message", "role": "assistant", "content": [{"type": "output_text", "text": "合成测试报告：总睡眠 450 分钟，缺失字段仍为未知。"}]}]
                else:
                    output = [{"type": "function_call", "name": "read_sleep", "arguments": '{"mode":"home"}', "call_id": "synthetic-call"}]
                data = json.dumps({"output": output, "status": "completed"}).encode()
                self.send_response(200); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(data))); self.end_headers(); self.wfile.write(data)
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
        self.addCleanup(server.server_close); self.addCleanup(server.shutdown)
        candidates = interfaces()
        if not candidates: self.skipTest("没有可用于 WSS 的本机私有 IPv4")
        host = recommendation(candidates) or candidates[0]["ip"]
        with socket.socket() as sock: sock.bind((host, 0)); port = sock.getsockname()[1]
        self.window.send("generate", dict(host=host, port=port, name="合成AI测试")); self.pump(lambda: not self.window.pending)
        self.window.send("start"); self.pump(lambda: self.window.listening)
        self.phone = FakePhone(self.window.worker.store.load(), self.window.worker.store.folder / "cert.pem", "")
        self.phone.thread.start(); self.pump(lambda: self.window.connected and self.window.status.get("session_active") and not self.window.pending)
        self.window.send("ai_save", {"endpoint": f"http://127.0.0.1:{server.server_port}/v1", "model": "synthetic", "key": "synthetic-key"})
        self.pump(lambda: self.window.ai_ready and not self.window.pending)
        self.window.ai_consent.setChecked(True); self.window.ai_control.setChecked(True)
        self.window.ai_input.setPlainText("读取当前报告"); self.window.send_ai_message()
        self.pump(lambda: self.phone.task.get("state") == "running")
        self.assertTrue(self.window.ai_busy); self.assertFalse(self.window.shot_button.isEnabled())
        self.phone.task = {**self.phone.task, "state": "completed", "result": {"success": True, "report_date": {"value": "2026-10-06", "state": "verified"}, "fields": {"total": {"value": 450, "unit": "minute", "source": "accessibility", "state": "observed", "evidence": ["合成时长"]}}}}
        self.pump(lambda: not self.window.ai_busy and not self.window.pending)
        self.assertIn("450 分钟", self.window.ai_transcript.toPlainText())
        self.assertEqual(450, self.window.task_data["result"]["fields"]["total"]["value"])
        self.assertNotIn("合成时长", self.window.log_view.toPlainText())
        self.assertFalse(any(pathlib.Path(self.temp.name).rglob("*.json")) if not self.window.pairing.get("exists") else any(pathlib.Path(self.temp.name).rglob("*report*.json")))
        self.window.ai_input.setPlainText("再读取一次"); self.window.send_ai_message()
        self.pump(lambda: self.phone.task.get("state") == "running")
        old_id = self.window.ai_run_id
        self.window.send("ai_cancel")
        self.pump(lambda: not self.window.ai_busy and self.phone.task.get("state") == "cancelled" and not self.window.pending)
        self.window.on_ai_event(old_id, {"kind": "task", "data": {"task_id": "late", "state": "completed"}})
        self.assertNotEqual("late", self.window.task_data.get("task_id"))
        self.assertEqual(2, sum(c["method"] == "start_sleep_task" for c in self.phone.commands))

    def test_ai_defaults_and_run_id_fence(self):
        self.assertEqual(6, self.window.pages.count())
        self.assertFalse(self.window.ai_consent.isChecked()); self.assertFalse(self.window.ai_screenshot.isChecked())
        self.assertFalse(self.window.ai_send.isEnabled())
        self.window.ai_busy = True; self.window.ai_run_id = "new"
        self.window.on_ai_event("old", {"kind": "task", "data": {"task_id": "late", "state": "completed"}})
        self.assertNotEqual("late", self.window.task_data.get("task_id"))
        self.window.ai_busy = False

    def test_update_requires_verified_download_and_explicit_launch_choice(self):
        self.assertFalse(self.window.update_download.isEnabled())
        self.assertFalse(self.window.update_start.isEnabled())
        self.assertFalse(self.window.update_launch_requested)
        self.window.update_result("update_check", {"available": True,"version":"1.0.1","notes":"合成更新"})
        self.window.update_buttons()
        self.assertTrue(self.window.update_download.isEnabled())
        self.assertFalse(self.window.update_start.isEnabled())
        self.window.update_launch_path="old"
        self.window.send("update_save",{"url":"http://invalid"})
        self.pump(lambda: not self.window.pending)
        self.assertIsNone(self.window.update_launch_path)
        self.assertFalse(self.window.update_start.isEnabled())

    def test_diagnostics_do_not_include_remote_text(self):
        self.window.pending["unit"] = dict(method="get_ui_tree", generation=self.window.task_generation)
        self.window.on_failure("unit", "ERROR: secret-health-original-and-token")
        self.assertNotIn("secret-health", self.window.log_view.toPlainText())
        self.assertIn("request_failed:get_ui_tree", self.window.log_view.toPlainText())

    def test_different_task_id_reply_ignored(self):
        self.window.task_id = "new"; self.window.task_data = {"task_id": "new", "state": "running"}
        self.window.pending["old"] = dict(method="get_task_status", generation=self.window.task_generation)
        self.window.on_result("old", {"task_id": "old", "state": "completed"})
        self.assertEqual("new", self.window.task_data["task_id"])

    def test_occupied_port_error_has_actionable_message(self):
        candidates = interfaces()
        if not candidates: self.skipTest("没有本机私有 IPv4")
        host = recommendation(candidates) or candidates[0]["ip"]
        with socket.socket() as sock:
            sock.bind((host, 0)); sock.listen(); port = sock.getsockname()[1]
            self.window.send("generate", dict(host=host, port=port, name="port-test")); self.pump(lambda: self.window.pairing.get("exists"))
            self.window.send("start"); self.pump(lambda: not self.window.pending)
            self.assertIn("端口已占用", self.window.pair_error.text()); self.assertFalse(self.window.listening)


if __name__ == "__main__": unittest.main()
