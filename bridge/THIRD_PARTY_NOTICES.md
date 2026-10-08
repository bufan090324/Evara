# Third-party runtime notices

Evara Desktop uses unmodified third-party libraries and a bundled Python runtime.

- Python 3.13.5: Python Software Foundation License. Python source and notices: https://www.python.org/downloads/release/python-3135/ .
- PySide6 Essentials / Shiboken6 / Qt 6.11.2: this distribution uses the LGPL v3 option for the dynamic QtCore, QtGui, QtWidgets and QtNetwork libraries and their Python bindings. The PyPI wheels are offered under LGPL-3.0-only OR GPL-2.0-only OR GPL-3.0-only (and separately commercial terms). Copied upstream LGPL/GPL texts are in licenses/. No Qt/PySide/Shiboken sources were modified. Corresponding source tags: https://code.qt.io/cgit/pyside/pyside-setup.git/tag/?h=v6.11.2 and https://code.qt.io/cgit/qt/qtbase.git/tag/?h=v6.11.2 . Qt third-party notices: https://doc.qt.io/qt-6/licenses-used-in-qt.html ; PySide notices: https://doc.qt.io/qtforpython-6/licenses.html .
- Dynamic Qt/PySide/Shiboken files are separately present under _internal/PySide6 and _internal/shiboken6. You may replace or relink these libraries and use the supplied application source and build instructions to rebuild the application. Reverse engineering for debugging modifications of LGPL libraries is permitted; no application term restricts it.
- websockets 15.0.1: BSD license; https://github.com/python-websockets/websockets/tree/15.0.1 .
- cryptography 44.0.2: Apache 2.0 / BSD; https://github.com/pyca/cryptography/tree/44.0.2 .
- cffi 2.1.1 and pycparser 3.0: MIT/BSD notices copied from the installed distributions.
- pywin32 311: Python/PSF-related license notices copied from its distribution.
- qrcode 8.2 and colorama 0.4.6: BSD license notices copied from installed distributions. QR code source: https://github.com/lincolnloop/python-qrcode .
- PyInstaller 6.22.3 bootloader: GPL with the PyInstaller exception allowing distribution of bundled applications; https://pyinstaller.org/en/stable/license.html .
- OpenSSL and Microsoft VC runtime files are bundled through the Python/Qt wheels; their upstream terms apply. Windows system ICU is used rather than copying a third-party or Windows ICU DLL into the application.

Included license copies retain their original names and text. The application source is delivered separately. Redistribution or changes to third-party components must preserve the applicable upstream terms.

AI network runtime: httpx 0.28.1 and httpcore 1.0.9 (BSD-3-Clause), anyio 4.15.1 (MIT), h11 0.16.0 (MIT), certifi 2026.7.22 (MPL-2.0 CA bundle), idna 3.20 (BSD-3-Clause), typing_extensions 4.16.0 (PSF-2.0). Upstream license copies are included in the portable distribution. No bundled CA source was modified.
