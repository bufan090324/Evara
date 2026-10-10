"""Create a portable release from an existing PyInstaller onedir build.

Run in the build venv: python bridge/package_windows.py --dist dist/PhoneBridge --output outputs
Never includes pairing state, screenshots, test artifacts or machine-specific metadata.
"""
import argparse
import hashlib
import pathlib
import shutil
import sys
import zipfile
from importlib.metadata import distribution


def package(dist, output):
    source = pathlib.Path(__file__).resolve().parent
    dist = pathlib.Path(dist).resolve(); output = pathlib.Path(output).resolve(); output.mkdir(parents=True, exist_ok=True)
    if not (dist / "Evara.exe").is_file():
        raise ValueError("找不到 Evara.exe，请先构建")
    for file in dist.rglob("*"):
        if file.is_file() and (file.name.lower() in {"pairing.json", "key.pem", "cert.pem", "credentials.bin", "direct_url.json"} or file.suffix.lower() in {".jpg", ".jpeg", ".png", ".log"}):
            raise ValueError("构建目录含敏感资料/测试文件/机器元数据，拒绝打包")
    # Only license texts are copied from dependency metadata, not direct_url or RECORD paths.
    licenses = dist / "licenses"; licenses.mkdir(exist_ok=True)
    for file in (source / "licenses").glob("*.txt"):
        shutil.copyfile(file, licenses / file.name)
    for name in ["PySide6-Essentials", "shiboken6", "websockets", "cryptography", "cffi", "pycparser", "pywin32", "pyinstaller", "qrcode", "colorama", "httpx", "httpcore", "anyio", "certifi", "h11", "idna", "typing_extensions"]:
        dependency = distribution(name)
        for relative in dependency.files or []:
            text = str(relative).lower()
            if any(term in pathlib.Path(text).name for term in ["license", "copying", "notice"]) and pathlib.Path(text).suffix.lower() in {"", ".txt", ".md", ".apache", ".bsd"}:
                original = pathlib.Path(dependency.locate_file(relative))
                if original.is_file() and original.stat().st_size < 200000:
                    relative_path = pathlib.Path(relative)
                    if ".." in relative_path.parts: continue
                    target = licenses / name / relative_path; target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(original, target)
    python_license = pathlib.Path(sys.base_prefix) / "LICENSE.txt"
    if python_license.exists(): shutil.copyfile(python_license, licenses / "Python-LICENSE.txt")
    shutil.copyfile(source / "THIRD_PARTY_NOTICES.md", dist / "THIRD_PARTY_NOTICES.md")
    for name in ["README.md", "FEATURES.md", "WINDOWS_MANUAL_TEST.md", "AI_GUIDE.md", "UPDATES.md", "TEST_RESULTS_1.0.3.md"]:
        if (source.parent / name).exists(): shutil.copyfile(source.parent / name, dist / name)
    (dist / "双击运行说明.txt").write_text("双击 Evara.exe。请保留整个目录及 _internal。无需安装 Python。\n首次选择局域网地址，生成配对，扫码或粘贴到自己的手机并核对指纹，然后启动服务。\n当前操作见 README.md；能力与限制见 FEATURES.md。\n", encoding="utf-8")
    zip_path = output / "Evara-Windows-x64-便携版.zip"
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for file in dist.rglob("*"):
            if file.is_file(): archive.write(file, pathlib.Path("Evara") / file.relative_to(dist))
    with zipfile.ZipFile(zip_path) as archive:
        if archive.testzip() is not None: raise ValueError("ZIP 完整性失败")
    digest = hashlib.sha256(zip_path.read_bytes()).hexdigest()
    print(zip_path.name, zip_path.stat().st_size, digest)
    return zip_path


if __name__ == "__main__":
    parser = argparse.ArgumentParser(); parser.add_argument("--dist", default="dist/PhoneBridge"); parser.add_argument("--output", default="outputs")
    args = parser.parse_args(); package(args.dist, args.output)
