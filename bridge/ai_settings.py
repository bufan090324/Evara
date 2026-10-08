"""Windows current-user DPAPI credentials; no plaintext credential files."""
import json
import pathlib
import os
import tempfile
from urllib.parse import urlsplit
from storage import protect_directory, user_directory


def validate_endpoint(value):
    if not isinstance(value, str) or any(ord(c) < 32 for c in value):
        raise ValueError("AI 服务地址不能包含控制字符")
    value = value.strip().rstrip("/")
    try:
        parsed = urlsplit(value)
        port = parsed.port
    except ValueError:
        raise ValueError("AI 服务地址无效") from None
    if (not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment
            or any(c.isspace() or ord(c) < 32 for c in value)
            or len(value) > 500 or port == 0):
        raise ValueError("AI 地址不能含凭证、查询参数或空白")
    if parsed.scheme != "https" and not (parsed.scheme == "http" and parsed.hostname in {"localhost", "127.0.0.1", "::1"}):
        raise ValueError("AI 服务必须使用 HTTPS；仅本机服务允许 HTTP")
    if parsed.path.endswith("/responses"):
        raise ValueError("请填写 API 基础地址（通常以 /v1 结尾），不要填写 /responses")
    return value


class AISettings:
    def __init__(self, directory=None):
        self.folder = pathlib.Path(directory or user_directory()) / "ai"
        self.path = self.folder / "credentials.bin"

    def load(self):
        if not self.path.exists():
            return {"endpoint": "https://api.openai.com/v1", "model": "", "key": ""}
        try:
            import win32crypt
            protect_directory(self.folder)
            if self.path.is_symlink() or self.path.stat().st_size > 20000:
                raise ValueError()
            clear = win32crypt.CryptUnprotectData(self.path.read_bytes(), None, None, None, 1)[1]
            value = json.loads(clear)
            self.validate(value)
            return value
        except Exception:
            raise ValueError("AI 设置无法由当前 Windows 用户解密，请删除设置后重新填写") from None

    @staticmethod
    def validate(value):
        value["endpoint"] = validate_endpoint(value["endpoint"])
        if not isinstance(value["model"], str) or not value["model"].strip() or len(value["model"]) > 128 or any(ord(c) < 32 for c in value["model"]):
            raise ValueError("请填写服务实际支持的模型名称（最多 128 字符）")
        if not isinstance(value["key"], str) or not value["key"] or len(value["key"]) > 8192 or any(ord(c) < 32 for c in value["key"]):
            raise ValueError("请填写有效 API Key；不能包含换行")

    def public(self):
        value = self.load()
        return {"endpoint": value["endpoint"], "model": value["model"], "key_set": bool(value["key"])}

    def save(self, endpoint, model, key):
        endpoint = validate_endpoint(endpoint)
        previous = self.load()
        if not key:
            if previous["endpoint"] != endpoint:
                raise ValueError("更换服务地址时必须重新填写密钥，防止旧密钥发送给其他服务")
            key = previous["key"]
        value = {"endpoint": endpoint, "model": model.strip(), "key": key.strip()}
        self.validate(value)
        import win32crypt
        protect_directory(self.folder)
        if self.path.is_symlink():
            raise ValueError("AI 设置文件不能是符号链接")
        encrypted = win32crypt.CryptProtectData(json.dumps(value).encode(), "Evara AI", None, None, None, 1)
        descriptor, name = tempfile.mkstemp(prefix="new-", dir=self.folder)
        try:
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(encrypted)
            os.replace(name, self.path)
        finally:
            if pathlib.Path(name).exists(): pathlib.Path(name).unlink()
        return self.public()

    def delete(self):
        if self.path.is_symlink(): raise ValueError("拒绝删除符号链接设置")
        if self.path.exists(): self.path.unlink()
        return self.public()
