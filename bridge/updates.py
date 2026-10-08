"""Signed HTTPS updates, independent of AI and phone credentials."""
import asyncio
import base64
import hashlib
import json
import pathlib
import re
import tempfile
import zipfile
from urllib.parse import urlsplit
import httpx
from cryptography.hazmat.primitives import serialization, hashes
from cryptography.hazmat.primitives.asymmetric import padding
from update_key import PUBLIC_KEY_DER
from storage import protect_directory

DEFAULT_UPDATE_SOURCE = "https://github.com/bufan090324/Evara/releases/latest/download/update.json"

MAX_PACKAGE = 150 * 1024 * 1024


def https_url(value):
    if not isinstance(value, str) or len(value) > 2048 or any(ord(c) < 33 for c in value):
        raise ValueError("更新地址无效")
    try:
        uri = urlsplit(value)
        if uri.scheme != "https" or not uri.hostname or uri.username or uri.password or uri.fragment or uri.port == 0:
            raise ValueError()
    except ValueError: raise ValueError("更新地址必须为不含凭证的 HTTPS 地址") from None
    return value


def version(value):
    if not isinstance(value, str) or not re.fullmatch(r"\d{1,4}\.\d{1,4}\.\d{1,4}", value): raise ValueError("更新版本格式无效")
    return tuple(map(int, value.split(".")))


def parse_manifest(raw, public_key=PUBLIC_KEY_DER):
    if len(raw) > 65536: raise ValueError("更新清单超过 64 KiB")
    try:
        envelope = json.loads(raw)
        payload = base64.b64decode(envelope["payload"], validate=True)
        signature = base64.b64decode(envelope["signature"], validate=True)
        key = serialization.load_der_public_key(base64.b64decode(public_key, validate=True))
        key.verify(signature, payload, padding.PKCS1v15(), hashes.SHA256())
        data = json.loads(payload)
    except Exception: raise ValueError("更新清单签名无效或内容损坏，未信任更新") from None
    if data.get("schema") != 1 or data.get("product") != "Evara": raise ValueError("更新清单产品/协议不匹配")
    result = {}
    for platform in ("windows-x64", "android"):
        item = data.get("releases", {}).get(platform)
        if not isinstance(item, dict): raise ValueError("更新清单缺少平台版本")
        version(item.get("version"))
        https_url(item.get("url"))
        if not isinstance(item.get("size"), int) or isinstance(item["size"], bool) or not 1 <= item["size"] <= MAX_PACKAGE:
            raise ValueError("更新文件大小不在允许范围")
        if not re.fullmatch(r"[a-f0-9]{64}", str(item.get("sha256", ""))): raise ValueError("更新哈希无效")
        if not isinstance(item.get("notes"), str) or len(item["notes"]) > 4000: raise ValueError("更新说明无效")
        if platform == "android" and (not isinstance(item.get("version_code"), int) or not 1 <= item["version_code"] <= 2100000000):
            raise ValueError("APK versionCode 无效")
        result[platform] = item
    return result


async def stream_url(client, url, limit):
    # Github Releases redirects assets. Follow only bounded, validated HTTPS hops.
    for _ in range(6):
        https_url(url)
        request = client.build_request("GET", url, headers={"Accept": "application/octet-stream"})
        response = await client.send(request, stream=True)
        if response.status_code in {301, 302, 303, 307, 308}:
            location = response.headers.get("location")
            await response.aclose()
            if not location: raise ValueError("更新地址重定向缺少目标")
            url = str(response.url.join(location)); continue
        if response.status_code != 200:
            await response.aclose(); raise ValueError(f"更新服务 HTTP {response.status_code}：检查地址、仓库可见性或网络")
        try:
            declared = response.headers.get("content-length")
            if declared and int(declared) > limit: raise ValueError("更新文件超过大小限制")
        except Exception:
            await response.aclose(); raise
        return response
    raise ValueError("更新地址重定向过多")


def extract_package(package, directory):
    """Extract a verified portable ZIP to a new private staging directory."""
    directory = pathlib.Path(directory)
    protect_directory(directory)
    destination = pathlib.Path(tempfile.mkdtemp(prefix="ready-", dir=directory))
    try:
        with zipfile.ZipFile(package) as archive:
            total = 0
            entries = archive.infolist()
            if len(entries) > 10000: raise ValueError("更新 ZIP 文件数量过多")
            names = set()
            for entry in entries:
                name = entry.filename
                path = pathlib.PurePosixPath(name)
                if (path.is_absolute() or ".." in path.parts or "\\" in name or ":" in name
                    or not path.parts or path.parts[0] != "Evara" or name.casefold() in names
                    or (entry.external_attr >> 16) & 0o170000 == 0o120000):
                    raise ValueError("更新 ZIP 含不安全路径")
                names.add(name.casefold()); total += entry.file_size
                if total > 400 * 1024 * 1024: raise ValueError("更新 ZIP 解压大小超过限制")
            if "evara/evara.exe" not in names: raise ValueError("更新包没有 Evara.exe")
            archive.extractall(destination)
        executable = destination / "Evara" / "Evara.exe"
        if not executable.is_file() or executable.read_bytes()[:2] != b"MZ": raise ValueError("更新主程序格式无效")
        return str(executable)
    except BaseException:
        import shutil
        if destination.resolve().parent == directory.resolve(): shutil.rmtree(destination)
        raise


class UpdateManager:
    def __init__(self, directory):
        self.directory = pathlib.Path(directory) / "updates"
        self.settings_path = pathlib.Path(directory) / "update-source.json"
        self.candidate = None

    def source(self):
        if not self.settings_path.exists(): return DEFAULT_UPDATE_SOURCE
        try: return https_url(json.loads(self.settings_path.read_text())["url"])
        except Exception: raise ValueError("更新源设置损坏，请重新保存 HTTPS 地址") from None

    def save_source(self, url):
        https_url(url)
        protect_directory(self.settings_path.parent)
        if self.settings_path.is_symlink(): raise ValueError("更新设置不能是符号链接")
        self.settings_path.write_text(json.dumps({"url": url}), encoding="utf-8")
        self.candidate = None
        return {"source": url}

    async def check(self, current):
        url = self.source()
        if not url: raise ValueError("尚未配置更新源；请输入已发布的签名更新清单 HTTPS 地址")
        self.candidate = None
        async with httpx.AsyncClient(trust_env=False, follow_redirects=False, timeout=15) as client:
            async with asyncio.timeout(45):
                response = await stream_url(client, url, 65536)
                try:
                    raw = bytearray()
                    async for chunk in response.aiter_bytes():
                        raw.extend(chunk)
                        if len(raw) > 65536: raise ValueError("更新清单超过 64 KiB")
                finally: await response.aclose()
        item = parse_manifest(raw)["windows-x64"]
        if version(item["version"]) <= version(current): return {"available": False, "version": item["version"], "notes": item["notes"]}
        self.candidate = item
        return {"available": True, **item}

    async def download(self, progress):
        if not self.candidate: raise ValueError("请先检查并确认新版")
        item = dict(self.candidate)
        protect_directory(self.directory)
        descriptor, name = tempfile.mkstemp(prefix="download-", suffix=".part", dir=self.directory)
        import os
        os.close(descriptor)
        temporary = pathlib.Path(name)
        try:
            digest = hashlib.sha256(); count = 0
            async with httpx.AsyncClient(trust_env=False, follow_redirects=False, timeout=15) as client:
                async with asyncio.timeout(600):
                    response = await stream_url(client, item["url"], item["size"])
                    try:
                        with temporary.open("wb") as target:
                            async for chunk in response.aiter_bytes():
                                count += len(chunk)
                                if count > item["size"]: raise ValueError("下载大小与签名清单不符")
                                target.write(chunk); digest.update(chunk)
                                progress(count, item["size"])
                    finally: await response.aclose()
            if count != item["size"] or digest.hexdigest() != item["sha256"]: raise ValueError("更新 SHA-256/大小校验失败，未解压或运行")
            extraction = asyncio.create_task(asyncio.to_thread(extract_package, temporary, self.directory))
            try:
                executable = await asyncio.shield(extraction)
            except asyncio.CancelledError:
                # Let the extractor finish before closing/deleting its input on Windows;
                # discard the staged result and never expose a launch path after cancel.
                import shutil
                executable = await extraction
                destination = pathlib.Path(executable).parent.parent
                if destination.resolve().parent == self.directory.resolve(): shutil.rmtree(destination)
                raise
            return {"executable": executable, "version": item["version"]}
        finally: temporary.unlink(missing_ok=True)
