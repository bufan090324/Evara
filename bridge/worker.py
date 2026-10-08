import asyncio
import concurrent.futures
import datetime as dt
import threading
from PySide6.QtCore import QThread, Signal
from core import BridgeService, load_pairing
from storage import PairingStore
from ai_settings import AISettings
from ai_client import ResponsesClient
from ai_agent import PhoneAgent
from updates import UpdateManager


def public_pairing(store):
    p = store.load(allow_expired=True)
    if not p:
        return {"exists": False, "acl": store.acl_status}
    _, cert = load_pairing(store.folder, allow_expired=True)
    return {"exists": True, "name": p["name"], "host": p["host"], "port": p["port"],
            "fingerprint": p["certificate_sha256"], "expires": cert.not_valid_after_utc.isoformat(),
            "expired": not cert.not_valid_before_utc <= dt.datetime.now(dt.timezone.utc) <= cert.not_valid_after_utc,
            "acl": store.acl_status}


class NetworkWorker(QThread):
    event = Signal(dict)
    result = Signal(str, object)
    failure = Signal(str, str)
    ai_event = Signal(str, dict)
    update_event = Signal(dict)
    def __init__(self, directory=None):
        super().__init__()
        self.directory = directory
        self.loop = None
        self.store = None
        self.service = None
        self.generation = 0
        self.tasks = set()
        self.shutdown_event = None
        self.administration = None
        self.ai_task = None
        self.ai_history = []
        self.ai_capabilities = {}
        self.update_task = None

    def run(self):
        async def lifetime():
            self.loop = asyncio.get_running_loop()
            self.shutdown_event = asyncio.Event()
            self.administration = asyncio.Lock()
            self.store = PairingStore(self.directory)
            self.ai_settings = AISettings(self.store.directory)
            self.updates = UpdateManager(self.store.directory)
            self.service = BridgeService(self.network_event)
            self.event.emit({"kind": "worker_ready", "detail": "后台网络线程已就绪"})
            await self.shutdown_event.wait()
            for task in list(self.tasks):
                task.cancel()
            await asyncio.gather(*self.tasks, return_exceptions=True)
            await self.service.stop()
        asyncio.run(lifetime())
        self.loop = None

    def network_event(self, kind, detail):
        if kind in {"connected", "disconnected", "stopped"}:
            self.generation += 1
            if self.ai_task and not self.ai_task.done(): self.ai_task.cancel()
        self.event.emit({"kind": kind, "detail": detail, "generation": self.generation})

    def submit(self, identifier, action, params=None):
        loop = self.loop
        if loop is None:
            self.failure.emit(identifier, "后台服务尚未就绪")
            return
        def schedule():
            if len(self.tasks) >= 12:
                self.failure.emit(identifier, "后台请求已达上限，请等待；不会自动重试")
                return
            task = asyncio.create_task(self.execute(identifier, action, params or {}))
            self.tasks.add(task)
            task.add_done_callback(self.tasks.discard)
        loop.call_soon_threadsafe(schedule)

    async def execute(self, identifier, action, params):
        generation = self.generation
        try:
            if action.startswith("update_"):
                if action == "update_cancel":
                    if self.update_task and not self.update_task.done(): self.update_task.cancel()
                    self.result.emit(identifier, {}); return
                if self.update_task and not self.update_task.done(): raise ValueError("正在检查或下载更新，请先取消")
                if action == "update_load": data = {"source": self.updates.source(), "direct": self.updates.direct()}
                elif action == "update_save": data = self.updates.save_source(params["url"], params.get("direct", False))
                else:
                    self.update_task = asyncio.current_task()
                    try:
                        if action == "update_check":
                            from desktop import VERSION
                            data = await self.updates.check(VERSION)
                        elif action == "update_download": data = await self.updates.download(lambda done, total: self.update_event.emit({"done": done, "total": total}))
                        else: raise ValueError("未知更新动作")
                    finally: self.update_task = None
                self.result.emit(identifier, data); return
            if action.startswith("ai_"):
                if action == "ai_cancel":
                    if self.ai_task and not self.ai_task.done(): self.ai_task.cancel()
                    self.result.emit(identifier, {})
                    return
                if self.ai_task and not self.ai_task.done(): raise ValueError("AI 正在运行，请先停止")
                if action == "ai_load": data = self.ai_settings.public()
                elif action == "ai_save":
                    data = self.ai_settings.save(params["endpoint"], params["model"], params.get("key", "")); self.ai_history.clear(); self.ai_capabilities.clear()
                elif action == "ai_delete": data = self.ai_settings.delete(); self.ai_history.clear(); self.ai_capabilities.clear()
                elif action == "ai_clear": self.ai_history.clear(); data = {}
                elif action in {"ai_test", "ai_chat"}:
                    self.ai_task = asyncio.current_task()
                    client = ResponsesClient(self.ai_settings.load())
                    try:
                        if action == "ai_test":
                            from capabilities import probe
                            data = await probe(client, lambda event: self.ai_event.emit(identifier, event))
                            self.ai_capabilities = data["capabilities"]
                        else:
                            if not params.get("consent"): raise ValueError("需要同意将对话及读取的健康数据发送给所选 AI 服务")
                            if params.get("screenshot") and self.ai_capabilities.get("image", {}).get("status") != "verified":
                                raise ValueError("图片能力未实测通过，请先检测或关闭截图发送授权")
                            agent = PhoneAgent(self.service, client, lambda: self.generation,
                                              lambda event: self.ai_event.emit(identifier, event), self.ai_history)
                            data = {"answer": await agent.run(params["text"], params.get("control", False), params.get("screenshot", False))}
                    finally:
                        await client.close()
                        self.ai_task = None
                else: raise ValueError("未知 AI 设置动作")
                self.result.emit(identifier, data)
                return
            if self.ai_task and not self.ai_task.done() and action not in {"device_status", "get_task_status", "cancel_task", "stop", "delete"}:
                raise ValueError("AI 正在操作手机，请先停止 AI 再手动操作")
            if action in {"load", "generate", "import", "delete", "start", "stop", "copy_pairing", "view_pairing", "qr_pairing"}:
                async with self.administration:
                    if action == "load":
                        self.store.prepare()
                        data = public_pairing(self.store)
                    elif action == "generate":
                        self.store.generate(params["host"], params["port"], params["name"])
                        data = public_pairing(self.store)
                    elif action == "import":
                        self.store.import_existing(params["folder"])
                        data = public_pairing(self.store)
                    elif action == "delete":
                        await self.service.stop()
                        self.store.delete()
                        data = public_pairing(self.store)
                    elif action == "start":
                        self.store.prepare()
                        data = await self.service.start(self.store.folder)
                    elif action == "stop":
                        await self.service.stop()
                        data = {}
                    else:
                        self.store.prepare()
                        data = self.store.load()
                        if data is None:
                            raise ValueError("没有配对资料")
                self.result.emit(identifier, data)
                return
            response = await self.service.request(action, params)
            if generation != self.generation:
                raise ConnectionError("连接已改变，迟到结果已丢弃")
            if not response.get("ok"):
                error = response.get("error") or {}
                self.failure.emit(identifier, f"{error.get('code', 'REMOTE_ERROR')}：{error.get('message', '手机拒绝操作')}")
            else:
                self.result.emit(identifier, response.get("data") or {})
        except asyncio.CancelledError:
            self.failure.emit(identifier, "请求已取消或连接已停止，不会重发")
        except TimeoutError:
            message = "UPDATE_TIMEOUT：更新下载超时；未启动新版，临时文件已清理，请检查网络后主动重试" if action.startswith("update_") else "TIMEOUT：未收到及时响应；动作可能已执行，请核对手机，勿盲目重试"
            self.failure.emit(identifier, message)
        except OSError as error:
            code = getattr(error, "winerror", None) or getattr(error, "errno", None)
            if code in {10048, 98}:
                message = "端口已占用：关闭旧测试桥/另一个Evara程序，或删除配对后换端口重新配对"
            elif code in {10049, 99}:
                message = "绑定 IP 不在本机：刷新网卡地址，删除旧配对并重新生成"
            else:
                message = f"系统/网络错误（代码 {code}）：检查资料访问权限、局域网和防火墙"
            from failures import display
            self.failure.emit(identifier, display(error) if hasattr(error, "category") else message)
        except (ValueError, KeyError, RuntimeError) as error:
            # Known validation errors contain no credential data; JSON decode messages are replaced.
            import json
            message = "配对资料 JSON 损坏，请删除后重新生成" if isinstance(error, json.JSONDecodeError) else str(error)
            from failures import display
            self.failure.emit(identifier, display(error) if hasattr(error, "category") else message)
        except Exception:
            self.failure.emit(identifier, "内部错误：请查看脱敏诊断；未自动重试")

    def shutdown(self):
        if self.loop and self.shutdown_event:
            self.loop.call_soon_threadsafe(self.shutdown_event.set)
