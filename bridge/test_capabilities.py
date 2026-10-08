import json
import unittest
from unittest.mock import patch
import httpx
from ai_client import ResponsesClient, AIError
from capabilities import probe, read_stream
from failures import task_failure

SETTINGS = {"endpoint": "https://example.invalid/v1", "model": "synthetic", "key": "synthetic-key"}


class CapabilityTests(unittest.IsolatedAsyncioTestCase):
    async def test_tool_success_does_not_verify_image(self):
        class Client:
            settings = SETTINGS
            async def test(self): return {"message": "通过"}
            async def respond(self, *args, **kwargs): raise AIError("网络错误")
        with patch("capabilities.synthetic_image", return_value="data:image/png;base64,synthetic"):
            result = await probe(Client())
        self.assertEqual("verified", result["capabilities"]["tools"]["status"])
        for kind in ["text", "image", "stream"]:
            self.assertEqual("unconfirmed", result["capabilities"][kind]["status"])

    async def test_image_http_success_without_recognition_is_unconfirmed(self):
        class Client:
            settings = SETTINGS
            async def test(self): pass
            async def respond(self, *args, **kwargs):
                return [{"type": "message", "content": [{"type": "output_text", "text": "无法查看图片"}]}]
        with patch("capabilities.synthetic_image", return_value="synthetic"):
            result = await probe(Client())
        self.assertEqual("IMAGE_UNCONFIRMED", result["capabilities"]["image"]["code"])

    async def test_complete_stream_and_no_health_headers(self):
        def handle(request):
            self.assertTrue(json.loads(request.content)["stream"])
            self.assertNotIn("phone", request.headers)
            content = 'data: {"type":"response.output_text.delta","delta":"OK"}\n\n'
            content += 'data: {"type":"response.completed","response":{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"OK"}]}]}}\n\n'
            return httpx.Response(200, headers={"content-type": "text/event-stream"}, content=content)
        client = ResponsesClient(SETTINGS, httpx.MockTransport(handle))
        try: self.assertEqual("OK", (await client.respond([], [], "", stream=True))[0]["content"][0]["text"])
        finally: await client.close()

    async def test_delta_without_completed_rejected(self):
        response = httpx.Response(200, headers={"content-type": "text/event-stream"}, content='data: {"type":"response.output_text.delta","delta":"OK"}\n\n')
        with self.assertRaisesRegex(AIError, "结束"): await read_stream(response)

    async def test_non_sse_not_verified_as_stream(self):
        with self.assertRaisesRegex(AIError, "SSE"):
            await read_stream(httpx.Response(200, json={"output": []}))

    async def test_failed_event_not_success(self):
        response = httpx.Response(200, headers={"content-type": "text/event-stream"}, content='data: {"type":"response.failed"}\n\n')
        with self.assertRaises(AIError): await read_stream(response)

    async def test_conflicting_stream_not_verified(self):
        content='data: {"type":"response.output_text.delta","delta":"A"}\n\n'
        content+='data: {"type":"response.completed","response":{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"B"}]}]}}\n\n'
        with self.assertRaisesRegex(AIError, "不一致"):
            await read_stream(httpx.Response(200, headers={"content-type": "text/event-stream"}, content=content))


class FailureTests(unittest.TestCase):
    def test_boundaries(self):
        self.assertEqual("phone_task", task_failure({"state": "failed"})["category"])
        self.assertEqual("parsing", task_failure({"state": "completed", "result": {"fields": {"deep": {"state": "conflict"}}}})["category"])
        self.assertEqual("DATE_UNCONFIRMED", task_failure({"state": "needs_user", "result": {"report_date": {"state": "mismatch"}}})["code"])
        self.assertIsNone(task_failure({"state": "completed", "result": {"fields": {"deep": {"state": "observed"}}}}))
        self.assertNotIn("key", AIError("网络错误").public())
