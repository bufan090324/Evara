# Build on Windows x64 using the pinned requirements-build.txt.
from pathlib import Path
import sys
import PySide6
import shiboken6
import pefile
root = Path(SPECPATH)
qt = Path(PySide6.__file__).parent
a = Analysis([str(root / 'desktop.py')], pathex=[str(root)],
    binaries=[], datas=[], hiddenimports=['win32timezone'],
    excludes=['PySide6.QtQml', 'PySide6.QtQuick', 'PySide6.QtOpenGL', 'PySide6.QtPdf',
              'PySide6.QtSql', 'PySide6.QtTest', 'PySide6.QtXml', 'tkinter', 'unittest'],
    noarchive=False, optimize=1)
# PySide/Shiboken/Python wheels can contain different MSVC runtime builds.
# Windows loads DLLs by basename; an older first-loaded copy breaks newer Qt imports.
# Use the newest wheel-supplied copy of each runtime at every collected destination.
runtime_copies = {}
for folder in [qt, Path(shiboken6.__file__).parent]:
    for pattern in ['msvcp140*.dll', 'vcruntime140*.dll', 'concrt140.dll']:
        for candidate in folder.glob(pattern):
            info = pefile.PE(str(candidate)).VS_FIXEDFILEINFO[0]
            version = (info.FileVersionMS, info.FileVersionLS)
            key = candidate.name.lower()
            if key not in runtime_copies or version > runtime_copies[key][0]:
                runtime_copies[key] = (version, str(candidate))
a.binaries = [(dest, runtime_copies.get(Path(dest).name.lower(), (None, source))[1], kind)
              for dest, source, kind in a.binaries]
# Qt's unversioned ICU import uses the Windows 10+ system ICU API. An unrelated
# ICU on a developer's PATH can have versioned exports and must not be bundled.
if not list(qt.glob('icu*.dll')):
    a.binaries = [(dest, source, kind) for dest, source, kind in a.binaries
                  if not Path(dest).name.lower().startswith('icu')]
pyz = PYZ(a.pure)
exe = EXE(pyz, a.scripts, [], exclude_binaries=True, name='Evara',
    debug=False, bootloader_ignore_signals=False, strip=False, upx=False,
    console=False, uac_admin=False, disable_windowed_traceback=True)
coll = COLLECT(exe, a.binaries, a.datas, strip=False, upx=False, name='Evara')
