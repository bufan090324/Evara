"""Resize the approved transparent icon; run with the desktop build environment."""
from pathlib import Path
import struct
from PySide6.QtGui import QImage
from PySide6.QtCore import Qt, QBuffer, QByteArray, QIODevice

root = Path(__file__).resolve().parents[1]
source = QImage(str(root / "assets/evara-master.png"))
if source.isNull() or not source.hasAlphaChannel():
    raise ValueError("A transparent evara-master.png is required")

def resized(size):
    return source.scaled(size, size, Qt.KeepAspectRatio, Qt.SmoothTransformation)

if not resized(512).save(str(root / "assets/evara.png")):
    raise OSError("PNG write failed")
sizes = [16, 24, 32, 48, 64, 128, 256]
offset = 6 + 16 * len(sizes)
entries, payloads = [], []
for size in sizes:
    raw = QByteArray()
    buffer = QBuffer(raw)
    buffer.open(QIODevice.WriteOnly)
    if not resized(size).save(buffer, "PNG"):
        raise OSError("ICO frame write failed")
    buffer.close()
    data = bytes(raw)
    entries.append(struct.pack("<BBBBHHII", size if size < 256 else 0,
                               size if size < 256 else 0, 0, 0, 1, 32, len(data), offset))
    payloads.append(data)
    offset += len(data)
(root / "assets/evara.ico").write_bytes(struct.pack("<HHH", 0, 1, len(sizes)) + b"".join(entries) + b"".join(payloads))
for density, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    path = root / "app/src/main/res" / ("mipmap-" + density) / "ic_launcher.png"
    path.parent.mkdir(exist_ok=True)
    if not resized(size).save(str(path)):
        raise OSError("Android icon write failed")
print("Generated transparent PNG, seven ICO frames and five Android densities")
