"""Independent synthetic probes; no phone, health data or persistent conversation."""
import base64
import datetime as dt
import secrets
import json
import hashlib
from PySide6.QtCore import QBuffer, QByteArray, QIODevice
from PySide6.QtGui import QImage, QPainter, QColor, QFont
from ai_client import AIError


def text_of(output):
    return "".join(c.get("text", "") for item in output if item.get("type") == "message"
                   for c in item.get("content", []) if c.get("type") == "output_text").strip()


async def read_stream(response):
    if "text/event-stream" not in response.headers.get("content-type", "").lower():
        raise AIError("服务未返回 SSE 流", code="STREAM_FORMAT")
    # Consume bounded bytes, then complete SSE frames. EOF/delta alone is not success.
    buffer = b""; total = 0; delta = ""; completed = None
    async for chunk in response.aiter_bytes():
        total += len(chunk)
        if total > 3000000: raise AIError("流式响应超过 3 MB", code="STREAM_LIMIT")
        buffer += chunk
        buffer = buffer.replace(b"\r\n", b"\n")
        while b"\n\n" in buffer:
            frame, buffer = buffer.split(b"\n\n", 1)
            data = b"\n".join(line[5:].lstrip() for line in frame.split(b"\n") if line.startswith(b"data:"))
            if not data or data == b"[DONE]": continue
            try: event = json.loads(data)
            except (ValueError, UnicodeError): raise AIError("SSE 事件 JSON 无效", code="STREAM_FORMAT") from None
            if not isinstance(event, dict): raise AIError("SSE 事件结构无效", code="STREAM_FORMAT")
            kind = event.get("type")
            if kind == "response.output_text.delta":
                if not isinstance(event.get("delta"), str): raise AIError("文本增量格式无效", code="STREAM_FORMAT")
                delta += event["delta"]
            if kind in {"error", "response.failed", "response.incomplete"}:
                raise AIError("服务的流式响应失败或未完成", code="STREAM_FAILED")
            if kind == "response.completed": completed = event.get("response")
    if not delta or not isinstance(completed, dict) or completed.get("status") != "completed":
        raise AIError("未观察到文本增量和完整结束事件", code="STREAM_INCOMPLETE")
    if not isinstance(completed.get("output"), list) or text_of(completed["output"]) != delta.strip():
        raise AIError("流式增量与结束结果不一致", code="STREAM_CONFLICT")
    return completed["output"]


def synthetic_image(code):
    image = QImage(512, 256, QImage.Format.Format_RGB32); image.fill(QColor("white"))
    painter = QPainter(image); painter.setPen(QColor("black")); painter.setFont(QFont("Arial", 64))
    painter.drawText(25, 155, code); painter.end()
    raw = QByteArray(); buffer = QBuffer(raw); buffer.open(QIODevice.OpenModeFlag.WriteOnly)
    image.save(buffer, "PNG"); buffer.close()
    return "data:image/png;base64," + base64.b64encode(bytes(raw)).decode()


async def probe(client, progress=lambda data: None):
    results = {}
    for kind in ["text", "tools", "image", "stream"]:
        started = dt.datetime.now(dt.timezone.utc).isoformat()
        try:
            nonce = secrets.token_hex(3).upper()
            if kind == "tools":
                await client.test()
            elif kind == "image":
                output = await client.respond([{"role": "user", "content": [
                    {"type": "input_text", "text": "只输出图片中的六位字符，不添加解释。"},
                    {"type": "input_image", "image_url": synthetic_image(nonce)}]}], [], "识别图片中的文字。")
                if text_of(output) != nonce: raise AIError("图片识别结果未匹配随机测试图，无法确认视觉能力", code="IMAGE_UNCONFIRMED")
            else:
                output = await client.respond([{"role": "user", "content": "只输出这六个字符：" + nonce}], [],
                                              "按要求原样输出。", stream=kind == "stream")
                if text_of(output) != nonce: raise AIError("测试回复不匹配，无法确认能力", code="TEXT_UNCONFIRMED")
            result = {"status": "verified", "message": "本次实际测试通过", "at": started,
                      "evidence": "valid_function_call" if kind == "tools" else "random_content_matched",
                      "challenge_sha256": hashlib.sha256(nonce.encode()).hexdigest() if kind != "tools" else None,
                      "request_id": getattr(client, "last_request_id", None)}
        except AIError as error:
            result = {"status": "unconfirmed", "at": started, **error.public()}
        results[kind] = result; progress({"kind": "capability", "capability": kind, "data": result})
    return {"message": "四项独立检测已结束；未确认项不能视为不支持。", "capabilities": results,
            "model": client.settings["model"], "endpoint": client.settings["endpoint"]}
