"""Current-user storage; no credentials are included in application distributions."""
import json
import os
import pathlib
import shutil
import tempfile
from core import initialize, load_pairing


def user_directory():
    base = os.environ.get("LOCALAPPDATA")
    if not base or not pathlib.Path(base).is_absolute():
        raise RuntimeError("无法确定当前用户 LOCALAPPDATA，未保存配对资料")
    return pathlib.Path(base) / "PhoneBridge"


def protect_directory(path):
    if os.name != "nt":
        raise RuntimeError("当前用户专属 ACL 仅实现于 Windows")
    import win32api
    import win32con
    import win32security as sec
    path = pathlib.Path(path)
    if path.is_symlink():
        raise RuntimeError("资料目录不能是符号链接")
    path.mkdir(parents=True, exist_ok=True)
    token = sec.OpenProcessToken(win32api.GetCurrentProcess(), win32con.TOKEN_QUERY)
    try:
        sid = sec.GetTokenInformation(token, sec.TokenUser)[0]
    finally:
        token.Close()
    acl = sec.ACL()
    acl.AddAccessAllowedAceEx(sec.ACL_REVISION, sec.OBJECT_INHERIT_ACE | sec.CONTAINER_INHERIT_ACE, 0x1F01FF, sid)
    sec.SetNamedSecurityInfo(str(path), sec.SE_FILE_OBJECT,
                            sec.DACL_SECURITY_INFORMATION | sec.PROTECTED_DACL_SECURITY_INFORMATION,
                            None, None, acl, None)
    descriptor = sec.GetNamedSecurityInfo(str(path), sec.SE_FILE_OBJECT, sec.DACL_SECURITY_INFORMATION)
    actual = descriptor.GetSecurityDescriptorDacl()
    if actual.GetAceCount() != 1 or actual.GetAce(0)[2] != sid or not descriptor.GetSecurityDescriptorControl()[0] & sec.SE_DACL_PROTECTED:
        raise RuntimeError("无法验证当前用户专属 ACL；已停止配对保存")
    return "已验证：禁止继承，仅当前 Windows 用户有访问 ACE（管理员仍可取得所有权）"


class PairingStore:
    def __init__(self, directory=None):
        self.directory = pathlib.Path(directory) if directory else user_directory()
        self.folder = self.directory / "pairing"
        self.acl_status = "尚未检查"

    def prepare(self):
        self.acl_status = protect_directory(self.directory)
        if self.folder.exists():
            protect_directory(self.folder)
            for name in ("key.pem", "cert.pem", "pairing.json"):
                file = self.folder / name
                if file.is_symlink():
                    raise RuntimeError("配对资料不能是符号链接")
                if file.exists():
                    # Apply an explicit protected ACL to files as well as the directory.
                    import win32security as sec
                    dacl = sec.GetNamedSecurityInfo(str(self.folder), sec.SE_FILE_OBJECT, sec.DACL_SECURITY_INFORMATION).GetSecurityDescriptorDacl()
                    sec.SetNamedSecurityInfo(str(file), sec.SE_FILE_OBJECT, sec.DACL_SECURITY_INFORMATION | sec.PROTECTED_DACL_SECURITY_INFORMATION, None, None, dacl, None)
        return self.acl_status

    def load(self, allow_expired=False):
        if not self.folder.exists():
            return None
        return load_pairing(self.folder, allow_expired)[0]

    def generate(self, host, port, name):
        self.prepare()
        if self.folder.exists():
            raise ValueError("已有配对资料；请先明确删除旧配对，不会覆盖")
        staging = pathlib.Path(tempfile.mkdtemp(prefix="pairing-new-", dir=self.directory))
        try:
            protect_directory(staging)
            initialize(staging, host, port, name)
            load_pairing(staging)
            staging.rename(self.folder)
            self.prepare()
            return self.load()
        finally:
            if staging.exists():
                self._remove(staging)

    def _remove(self, folder):
        # Only a direct child of the explicitly managed user directory can be deleted.
        if folder.is_symlink() or folder.resolve().parent != self.directory.resolve():
            raise RuntimeError("拒绝删除资料目录外的路径")
        shutil.rmtree(folder)

    def delete(self):
        if self.folder.exists():
            self._remove(self.folder)

    def import_existing(self, folder):
        self.prepare()
        if self.folder.exists():
            raise ValueError("已有配对资料；不会覆盖")
        folder = pathlib.Path(folder)
        load_pairing(folder)
        staging = pathlib.Path(tempfile.mkdtemp(prefix="pairing-import-", dir=self.directory))
        try:
            protect_directory(staging)
            for name in ("key.pem", "cert.pem", "pairing.json"):
                source = folder / name
                if source.is_symlink() or source.stat().st_size > 32768:
                    raise ValueError("配对文件无效")
                (staging / name).write_bytes(source.read_bytes())
            load_pairing(staging)
            staging.rename(self.folder)
            self.prepare()
        finally:
            if staging.exists():
                self._remove(staging)
