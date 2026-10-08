"""Small LAN WSS test bridge. No cloud API, no remote HTTP UI, no automatic replay."""
import asyncio
import base64
import datetime as dt
import hashlib
import hmac
import ipaddress
import json
import pathlib
import secrets
import ssl
import uuid
import time
import math
import re
from websockets.exceptions import ConnectionClosed

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID
from websockets.asyncio.server import serve

MAX_REQUEST = 65536
MAX_RESPONSE = 2200000


def lan(host):
    try:
        address = ipaddress.IPv4Address(host)
        return any(address in ipaddress.IPv4Network(n) for n in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"))
    except ValueError:
        return False


def proof(token, nonce):
    return hmac.new(token.encode(), ("phonebridge-v1:" + nonce).encode(), hashlib.sha256).hexdigest()


def initialize(folder, host, port, name):
    if not lan(host) or not 1024 <= port <= 65535:
        raise ValueError("必须使用 RFC1918 IPv4 和 1024–65535 端口")
    folder.mkdir(parents=True, exist_ok=True)
    if (folder / "pairing.json").exists():
        raise ValueError("已有配对资料；请使用 serve，或显式指定新的目录生成新配对")
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, name[:80])])
    now = dt.datetime.now(dt.timezone.utc)
    certificate = (x509.CertificateBuilder().subject_name(subject).issuer_name(subject)
                   .public_key(key.public_key()).serial_number(x509.random_serial_number())
                   .not_valid_before(now - dt.timedelta(minutes=5)).not_valid_after(now + dt.timedelta(days=365))
                   .add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address(host))]), critical=False)
                   .add_extension(x509.BasicConstraints(ca=True, path_length=0), critical=True)
                   .sign(key, hashes.SHA256()))
    (folder / "key.pem").write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    (folder / "cert.pem").write_bytes(certificate.public_bytes(serialization.Encoding.PEM))
    pairing = dict(protocol=1, host=host, port=port, name=name,
                   certificate_der=base64.b64encode(certificate.public_bytes(serialization.Encoding.DER)).decode(),
                   certificate_sha256=certificate.fingerprint(hashes.SHA256()).hex(), token=secrets.token_urlsafe(32))
    (folder / "pairing.json").write_text(json.dumps(pairing, ensure_ascii=False, indent=2), encoding="utf-8")
    return pairing


class Bridge:
    def __init__(self, token, allow_loopback_for_tests=False, on_event=None):
        self.on_event = on_event or (lambda kind, detail: None)
        self.epoch = 0
        self.operation_lock = asyncio.Lock()
        self.queued = 0
        self.token = token
        self.phone = None
        self.pending = {}
        self.claimed = False
        self.allow_loopback_for_tests = allow_loopback_for_tests
        self.ready = asyncio.Event()

    async def handler(self, ws):
        peer = ws.remote_address[0]
        if ws.request.path != "/bridge" or not (lan(peer) or self.allow_loopback_for_tests and peer == "127.0.0.1"):
            await ws.close(1008, "LAN only")
            return
        if self.claimed:
            await ws.close(1008, "one phone only")
            return
        self.claimed = True
        self.on_event("authenticating", "设备正在鉴权，尚未连接")
        try:
            nonce = secrets.token_hex(32)
            await ws.send(json.dumps(dict(type="challenge", protocol=1, nonce=nonce)))
            auth_raw = await asyncio.wait_for(ws.recv(), 10)
            if not isinstance(auth_raw, str) or len(auth_raw.encode()) > MAX_REQUEST:
                await ws.close(1008, "auth size")
                return
            auth = json.loads(auth_raw)
            if not isinstance(auth, dict):
                raise ValueError("auth format")
            if not (auth.get("type") == "auth" and auth.get("protocol") == 1 and isinstance(auth.get("proof"), str)
                    and hmac.compare_digest(auth["proof"], proof(self.token, nonce))):
                self.on_event("rejected", "配对凭证无效，已拒绝连接")
                await ws.close(1008, "auth rejected")
                return
            self.phone = ws
            await ws.send(json.dumps(dict(type="auth_ok", protocol=1)))
            self.ready.set()
            self.epoch += 1
            self.on_event("connected", "手机已通过配对鉴权")
            async for raw in ws:
                if not isinstance(raw, str) or len(raw.encode()) > MAX_RESPONSE:
                    await ws.close(1009, "response size")
                    break
                obj = json.loads(raw)
                if not isinstance(obj, dict) or obj.get("type") != "response" or obj.get("protocol") != 1 or not isinstance(obj.get("id"), str) or not isinstance(obj.get("ok"), bool):
                    await ws.close(1008, "protocol")
                    break
                future = self.pending.pop(obj.get("id"), None)
                if future is not None and not future.done():
                    future.set_result(obj)
        except ConnectionClosed:
            pass
        except (TimeoutError, ValueError, TypeError):
            self.on_event("rejected", "鉴权超时或协议无效")
            await ws.close(1008, "auth timeout or invalid message")
        finally:
            if self.phone is ws:
                self.phone = None
                self.epoch += 1
                self.ready.clear()
                for future in self.pending.values():
                    if not future.done():
                        future.set_exception(ConnectionError("手机断线；旧请求不重发"))
                self.pending.clear()
                self.on_event("disconnected", "手机断线，旧操作不重发")
            self.claimed = False

    async def _request(self, method, params=None, timeout_ms=10000):
        if self.phone is None:
            raise ConnectionError("尚无已认证手机")
        if not 100 <= timeout_ms <= 30000:
            raise ValueError("timeout_ms 必须为 100–30000")
        if len(self.pending) >= 8:
            raise ValueError("最多 8 个未返回请求")
        identifier = uuid.uuid4().hex
        payload = json.dumps(dict(type="request", protocol=1, id=identifier, timeout_ms=timeout_ms,
                                  method=method, params=params or {}), ensure_ascii=False)
        if len(payload.encode()) > MAX_REQUEST:
            raise ValueError("请求超过 64 KiB")
        future = asyncio.get_running_loop().create_future()
        self.pending[identifier] = future
        try:
            await self.phone.send(payload)
            return await asyncio.wait_for(future, timeout_ms / 1000 + 1)
        finally:
            self.pending.pop(identifier, None)



    async def request(self, method, params=None, timeout_ms=10000):
        if method in {"device_status", "get_task_status", "cancel_task"}:
            return await self._request(method, params, timeout_ms)
        if not 100 <= timeout_ms <= 30000:
            raise ValueError("timeout_ms 必须为 100–30000")
        if self.phone is None:
            raise ConnectionError("尚无已认证手机")
        if self.queued >= 8:
            raise ValueError("操作队列已满，最多 8 条")
        self.queued += 1
        epoch = self.epoch
        deadline = time.monotonic() + timeout_ms / 1000
        acquired = False
        try:
            await asyncio.wait_for(self.operation_lock.acquire(), timeout_ms / 1000)
            acquired = True
            if self.epoch != epoch or self.phone is None:
                raise ConnectionError("会话已改变；旧操作不重发")
            remaining = math.ceil((deadline - time.monotonic()) * 1000)
            if remaining < 100:
                raise TimeoutError("排队已超时，动作未发出")
            return await self._request(method, params, remaining)
        finally:
            self.queued -= 1
            if acquired:
                self.operation_lock.release()


def load_pairing(folder, allow_expired=False):
    folder = pathlib.Path(folder)
    raw = (folder / "pairing.json").read_bytes()
    if len(raw) > 16000:
        raise ValueError("配对资料过大")
    p = json.loads(raw)
    if not isinstance(p, dict):
        raise ValueError("配对资料必须为 JSON 对象")
    if p.get("protocol") != 1 or not lan(p.get("host", "")) or not isinstance(p.get("port"), int) or not 1024 <= p["port"] <= 65535:
        raise ValueError("配对协议、局域网地址或端口无效")
    if not isinstance(p.get("name"), str) or not 1 <= len(p["name"]) <= 80 or not re.fullmatch(r"[A-Za-z0-9_-]{32,128}", p.get("token", "")):
        raise ValueError("电脑名称或配对凭证无效")
    cert = x509.load_pem_x509_certificate((folder / "cert.pem").read_bytes())
    if cert.fingerprint(hashes.SHA256()).hex() != p["certificate_sha256"] or base64.b64decode(p["certificate_der"], validate=True) != cert.public_bytes(serialization.Encoding.DER):
        raise ValueError("证书与配对资料不一致，请删除后重新配对")
    if ipaddress.ip_address(p["host"]) not in cert.extensions.get_extension_for_class(x509.SubjectAlternativeName).value.get_values_for_type(x509.IPAddress):
        raise ValueError("证书 SAN 与 IP 不符，请重新配对")
    key = serialization.load_pem_private_key((folder / "key.pem").read_bytes(), None)
    if key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo) != cert.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo):
        raise ValueError("TLS 私钥与证书不一致")
    now = dt.datetime.now(dt.timezone.utc)
    if not allow_expired and not cert.not_valid_before_utc <= now <= cert.not_valid_after_utc:
        raise ValueError("证书已过期或电脑时间不正确，请重新配对/检查系统时间")
    return p, cert


class BridgeService:
    """Async public API for CLI, GUI and future local MCP wrappers."""
    def __init__(self, on_event=None):
        self.on_event = on_event or (lambda kind, detail: None)
        self.bridge = None
        self.server = None

    async def start(self, folder):
        if self.server is not None:
            raise ValueError("服务已经启动")
        p, cert = load_pairing(folder)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_2
        context.load_cert_chain(pathlib.Path(folder) / "cert.pem", pathlib.Path(folder) / "key.pem")
        bridge = Bridge(p["token"], on_event=self.on_event)
        server = await serve(bridge.handler, p["host"], p["port"], ssl=context,
                             max_size=MAX_RESPONSE, max_queue=8, ping_interval=15, ping_timeout=15, close_timeout=2)
        self.bridge, self.server = bridge, server
        self.on_event("listening", f"{p['host']}:{p['port']} 等待手机主动连接")
        return {k:v for k,v in p.items() if k not in {"token", "certificate_der"}}

    async def request(self, method, params=None, timeout_ms=10000):
        if self.bridge is None:
            raise ConnectionError("服务未启动")
        return await self.bridge.request(method, params, timeout_ms)

    async def stop(self):
        if self.bridge and self.bridge.phone:
            try:
                await asyncio.wait_for(self.bridge.request("cancel_task", timeout_ms=1000), 1.5)
            except Exception:
                pass
        if self.server:
            self.server.close()
            await self.server.wait_closed()
        self.server = None
        self.bridge = None
        self.on_event("stopped", "服务已停止，连接和端口已释放")
