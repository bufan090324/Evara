"""Verified APK cache and bounded paired HTTPS transfer, separate from controls."""
import asyncio
import hashlib
import hmac
import ipaddress
import json
import pathlib
import re
import secrets
import time
from http import HTTPStatus
from websockets.datastructures import Headers
from websockets.http11 import Response
from updates import parse_manifest, stream_url, update_client
from storage import protect_directory

CHUNK = 1024 * 1024
PREFIX = "/evara-update/"

def transfer_proof(token, nonce, path):
    return hmac.new(token.encode(), ("evara-update-v1:" + nonce + ":" + path).encode(), hashlib.sha256).hexdigest()

class PhoneUpdateCache:
    def __init__(self, directory, public_key=None):
        self.directory = pathlib.Path(directory) / "phone-updates"
        self.public_key = public_key
        self.ready = None

    def parse(self, raw):
        return parse_manifest(raw, self.public_key) if self.public_key else parse_manifest(raw)

    def status(self):
        item = self.ready
        return {"ready": item is not None, **({"version": item["item"]["version"], "size": item["item"]["size"]} if item else {})}

    def clear(self):
        self.ready = None
        for name in ["update.json", "phone.apk", "download.part"]:
            (self.directory / name).unlink(missing_ok=True)
        return self.status()

    def verified_file(self, raw, path):
        item = self.parse(raw)["android"]
        path = pathlib.Path(path)
        if path.is_symlink() or not path.is_file() or path.stat().st_size != item["size"]:
            raise ValueError("手机更新缓存大小或路径无效")
        digest = hashlib.sha256()
        with path.open("rb") as source:
            for block in iter(lambda: source.read(CHUNK), b""): digest.update(block)
        if digest.hexdigest() != item["sha256"]: raise ValueError("手机更新缓存 SHA-256 校验失败")
        return {"id": secrets.token_hex(16), "manifest": raw, "path": path, "item": item}

    async def prepare(self, url, direct, progress):
        self.ready = None
        protect_directory(self.directory)
        async with update_client(url, direct=direct) as client:
            async with asyncio.timeout(45):
                response = await stream_url(client, url, 65536)
                try:
                    raw = bytearray()
                    async for chunk in response.aiter_bytes():
                        raw.extend(chunk)
                        if len(raw) > 65536: raise ValueError("更新清单超过 64 KiB")
                finally: await response.aclose()
        raw = bytes(raw)
        item = self.parse(raw)["android"]
        path = self.directory / "phone.apk"
        partial = self.directory / "download.part"
        if path.is_file() and not path.is_symlink():
            try:
                ready = await asyncio.to_thread(self.verified_file, raw, path)
                self.ready = ready
                (self.directory / "update.json").write_bytes(raw)
                progress(item["size"], item["size"])
                return {**self.status(), "reused": True}
            except ValueError: pass
        partial.unlink(missing_ok=True)
        try:
            count = 0; digest = hashlib.sha256()
            async with update_client(item["url"], direct=direct) as client:
                async with asyncio.timeout(1800):
                    response = await stream_url(client, item["url"], item["size"])
                    try:
                        with partial.open("wb") as target:
                            async for chunk in response.aiter_bytes():
                                count += len(chunk)
                                if count > item["size"]: raise ValueError("手机更新文件超过签名清单大小")
                                target.write(chunk); digest.update(chunk); progress(count, item["size"])
                    finally: await response.aclose()
            if count != item["size"] or digest.hexdigest() != item["sha256"]:
                raise ValueError("手机更新大小/SHA-256 校验失败，未共享")
            partial.replace(path)
            self.ready = {"id": secrets.token_hex(16), "manifest": raw, "path": path, "item": item}
            (self.directory / "update.json").write_bytes(raw)
            return {**self.status(), "reused": False}
        finally: partial.unlink(missing_ok=True)

    def chunk(self, identifier, offset):
        ready = self.ready
        if not ready or ready["id"] != identifier: raise ValueError("CACHE_CHANGED")
        size = ready["item"]["size"]
        if offset < 0 or offset >= size or offset % CHUNK: raise ValueError("BAD_OFFSET")
        path = ready["path"]
        if path.is_symlink() or path.stat().st_size != size: raise ValueError("CACHE_CHANGED")
        with path.open("rb") as source:
            source.seek(offset); data = source.read(min(CHUNK, size-offset))
        if len(data) != min(CHUNK, size-offset) or self.ready is not ready: raise ValueError("CACHE_CHANGED")
        return data

class PairedUpdateGateway:
    def __init__(self, cache, token, allow_loopback_for_tests=False):
        self.cache, self.token = cache, token
        self.allow_loopback_for_tests = allow_loopback_for_tests
        self.nonces = {}
        self.active_chunks = 0

    def reply(self, code, body=b"", content_type="application/json", cache_id=None):
        headers = Headers({"Content-Type": content_type, "Content-Length": str(len(body)), "Cache-Control": "no-store", "Connection": "close"})
        if cache_id: headers["X-Evara-Cache-Id"] = cache_id
        return Response(code, HTTPStatus(code).phrase, headers, body)

    async def process_request(self, connection, request):
        if not request.path.startswith(PREFIX): return None
        peer = connection.remote_address[0]
        try:
            address = ipaddress.IPv4Address(peer)
            allowed = any(address in ipaddress.IPv4Network(n) for n in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"))
        except ValueError: allowed = False
        if not allowed and not (self.allow_loopback_for_tests and peer == "127.0.0.1"):
            return self.reply(403, b'{"error":"LAN_ONLY"}')
        now = time.monotonic()
        self.nonces = {k:v for k,v in self.nonces.items() if v[1] > now}
        if request.path == PREFIX + "challenge":
            if len(self.nonces) >= 32 or sum(v[0] == peer for v in self.nonces.values()) >= 8:
                return self.reply(429, b'{"error":"BUSY"}')
            nonce = secrets.token_hex(32); self.nonces[nonce] = (peer, now+30)
            return self.reply(200, json.dumps({"protocol":1,"nonce":nonce}).encode())
        try:
            nonce = request.headers.get("X-Evara-Nonce", "")
            proof = request.headers.get("X-Evara-Proof", "")
        except Exception: return self.reply(401, b'{"error":"AUTH"}')
        issued = self.nonces.pop(nonce, None)
        if not issued or issued[0] != peer or not re.fullmatch(r"[a-f0-9]{64}", proof) or not hmac.compare_digest(proof, transfer_proof(self.token, nonce, request.path)):
            return self.reply(401, b'{"error":"AUTH"}')
        ready = self.cache.ready
        if not ready: return self.reply(409, b'{"error":"CACHE_NOT_READY"}')
        if request.path == PREFIX + "manifest":
            return self.reply(200, ready["manifest"], cache_id=ready["id"])
        match = re.fullmatch(r"/evara-update/chunk/([a-f0-9]{32})/(0|[1-9][0-9]{0,9})", request.path)
        if not match: return self.reply(404, b'{"error":"UNKNOWN_PATH"}')
        if self.active_chunks >= 2: return self.reply(429, b'{"error":"BUSY"}')
        self.active_chunks += 1
        try:
            data = await asyncio.to_thread(self.cache.chunk, match[1], int(match[2]))
            return self.reply(200, data, "application/octet-stream", match[1])
        except (ValueError, OSError): return self.reply(409, b'{"error":"CACHE_CHANGED_OR_BAD_OFFSET"}')
        finally: self.active_chunks -= 1
