"""In-memory QR of the existing pairing JSON. No private key or new protocol."""
import json
import qrcode
from qrcode.constants import ERROR_CORRECT_M

FIELDS = ("protocol", "host", "port", "name", "certificate_der", "certificate_sha256", "token")


def pairing_matrix(pairing):
    if any(key not in pairing for key in FIELDS):
        raise ValueError("配对资料不完整，无法生成二维码")
    payload = json.dumps({key: pairing[key] for key in FIELDS}, ensure_ascii=False, separators=(",", ":"))
    if len(payload.encode("utf-8")) > 2300:
        raise ValueError("配对资料超出二维码容量，请使用粘贴配对或缩短电脑名称")
    code = qrcode.QRCode(error_correction=ERROR_CORRECT_M, border=4)
    code.add_data(payload.encode("utf-8"), optimize=0)
    try:
        code.make(fit=True)
    except qrcode.exceptions.DataOverflowError:
        raise ValueError("配对资料超出二维码容量，请使用粘贴配对") from None
    return code.get_matrix()
