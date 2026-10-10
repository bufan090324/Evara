"""Deterministic alpha-only cutout of the user-provided icon. No recoloring."""
from array import array
from collections import deque
import hashlib
import json
from pathlib import Path
from PySide6.QtGui import QImage

root = Path(__file__).resolve().parents[1]
source_path = root / "assets/evara-original.png"
image = QImage(str(source_path)).convertToFormat(QImage.Format_RGBA8888)
if image.isNull():
    raise ValueError("Original icon could not be read")
w, h = image.width(), image.height()
raw = bytes(image.constBits())
assert image.bytesPerLine() == w * 4
pixels = w * h
# This source has a dark exterior and a colored, connected central tile.
# Thresholding determines the alpha mask only: source RGB bytes are untouched.
candidate = bytearray(max(raw[i:i+3]) > 50 for i in range(0, len(raw), 4))
seed = (h // 2) * w + w // 2
if not candidate[seed]:
    raise ValueError("Central icon was not found")
mask = bytearray(pixels)
mask[seed] = 1
queue = deque([seed])
while queue:
    p = queue.popleft()
    x, y = p % w, p // w
    neighbors = []
    if x: neighbors.append(p-1)
    if x+1 < w: neighbors.append(p+1)
    if y: neighbors.append(p-w)
    if y+1 < h: neighbors.append(p+w)
    for n in neighbors:
        if candidate[n] and not mask[n]:
            mask[n] = 1
            queue.append(n)
if sum(mask) < pixels // 2:
    raise ValueError("Unexpected icon area; review source before changing threshold")

# A 3/4 chamfer distance removes the original thin exterior highlight.
# Only the outer four source pixels are trimmed, with one pixel of feathering.
distance = array("H", (30000 if v else 0 for v in mask))
for y in range(h):
    for x in range(w):
        p = y*w+x
        if not mask[p]: continue
        d = distance[p]
        if x: d = min(d, distance[p-1]+3)
        if y:
            d = min(d, distance[p-w]+3)
            if x: d = min(d, distance[p-w-1]+4)
            if x+1 < w: d = min(d, distance[p-w+1]+4)
        distance[p] = d
for y in range(h-1, -1, -1):
    for x in range(w-1, -1, -1):
        p = y*w+x
        if not mask[p]: continue
        d = distance[p]
        if x+1 < w: d = min(d, distance[p+1]+3)
        if y+1 < h:
            d = min(d, distance[p+w]+3)
            if x: d = min(d, distance[p+w-1]+4)
            if x+1 < w: d = min(d, distance[p+w+1]+4)
        distance[p] = d

output = bytearray(raw)
opaque = 0
for p in range(pixels):
    alpha = max(0, min(255, (distance[p]-12)*85)) if mask[p] else 0
    output[p*4+3] = alpha
    opaque += alpha == 255
result = QImage(output, w, h, w*4, QImage.Format_RGBA8888).copy()
destination = root / "assets/evara-master.png"
if not result.save(str(destination)):
    raise OSError("Master icon write failed")
reloaded = QImage(str(destination)).convertToFormat(QImage.Format_RGBA8888)
saved = bytes(reloaded.constBits())
rgb_changes = sum(raw[i:i+3] != saved[i:i+3] for i in range(0, len(raw), 4))
assert rgb_changes == 0, "PNG roundtrip changed source colors"
assert all(reloaded.pixelColor(x, y).alpha() == 0 for x, y in [(0,0), (w-1,0), (0,h-1), (w-1,h-1)])
verification = {
    "method": "deterministic alpha-only mask; no generative edit",
    "original_sha256": hashlib.sha256(source_path.read_bytes()).hexdigest(),
    "master_sha256": hashlib.sha256(destination.read_bytes()).hexdigest(),
    "original_size": [w,h], "master_size": [w,h],
    "rgb_changed_pixels": rgb_changes, "fully_opaque_pixels": opaque,
    "edge_trim_source_pixels": 4, "edge_feather_source_pixels": 1,
    "transparent_corners": True,
    "note": "Deployment sizes are resampled; the master retains original RGB and resolution."
}
(root / "assets/icon-verification.json").write_text(json.dumps(verification, indent=2), "utf-8")
print(json.dumps(verification))
