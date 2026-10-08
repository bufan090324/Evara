"""Bounded, TLS-verified Responses API client. No automatic retries or redirects."""
import asyncio
import json
import httpx
from urllib.parse import urlsplit
from ai_settings import AISettings


class AIError(ValueError):
    pass


def error_hint(status, content, key):
    """Classify bounded provider errors without echoing prompts, keys or raw bodies."""
    hints = {400: "参数不兼容：请检查模型、工具调用与 API 地址", 401: "密钥无效",
             403: "服务拒绝访问", 404: "检查 API 地址和模型名称", 429: "服务限流或额度不足，请检查服务账户"}
    hint = hints.get(status, "服务请求失败")
    try:
        payload = json.loads(content)
        error = payload.get("error", {})
        if not isinstance(error, dict): return hint
        message = str(error.get("message", "")).replace(key, "[隐藏]").lower()
        if "tool_choice" in message and ("think" in message or "reasoning" in message):
            hint = "服务拒绝思考模式下的指定工具调用；请使用兼容配置或关闭思考模式"
        elif "strict" in message:
            hint = "服务拒绝 strict 工具模式；需要兼容配置或服务支持的 Beta 接口"
        elif "model" in message and any(v in message for v in ["not found", "not exist", "invalid", "unsupported", "does not", "unknown"]):
            hint = "模型不存在、不可用或不支持该接口，请核对服务提供商的模型名称"
        elif "insufficient" in message and any(v in message for v in ["balance", "quota", "credit"]):
            hint = "服务余额或额度不足，请检查服务账户"
        # Fixed vocabularies: never display arbitrary strings that could echo health data.
        labels = []
        for field, allowed in [("type", {"invalid_request_error", "authentication_error", "permission_error", "rate_limit_error", "api_error"}),
                               ("code", {"invalid_api_key", "model_not_found", "invalid_request", "unsupported_parameter", "insufficient_quota", "invalid_parameter"}),
                               ("param", {"model", "tool_choice", "tools", "strict", "reasoning", "include", "store", "input", "max_output_tokens", "parallel_tool_calls"})]:
            value = error.get(field)
            if isinstance(value, str) and value in allowed and key not in value:
                labels.append(field + "=" + value)
        if labels: hint += "（" + "，".join(labels) + "）"
    except (ValueError, TypeError, AttributeError): pass
    return hint


class ResponsesClient:
    def __init__(self, settings, transport=None):
        AISettings.validate(dict(settings))
        self.settings = settings
        # Exact official host only: no implicit changes for arbitrary proxies/providers.
        self.deepseek = urlsplit(settings["endpoint"]).hostname == "api.deepseek.com"
        self.http = httpx.AsyncClient(transport=transport, trust_env=False, follow_redirects=False,
                                    timeout=httpx.Timeout(45, connect=10, write=15, pool=5))

    async def close(self):
        await self.http.aclose()

    async def respond(self, inputs, tools, instructions, tool_choice=None):
        body = {"model": self.settings["model"], "input": inputs, "instructions": instructions,
                "tools": tools, "parallel_tool_calls": False, "store": False,
                "include": ["reasoning.encrypted_content"], "max_output_tokens": 4096}
        if tool_choice: body["tool_choice"] = tool_choice
        if self.deepseek:
            # Official DeepSeek supports plain reasoning, not encrypted reasoning;
            # strict mode is a Beta feature. Local tool validation remains mandatory.
            body.pop("include", None)
            body.pop("store", None)
            body["reasoning"] = {"effort": "none"}
            body["tools"] = [{k: v for k, v in tool.items() if k != "strict"} for tool in tools]
        try:
            async with asyncio.timeout(65):
                async with self.http.stream("POST", self.settings["endpoint"] + "/responses",
                    headers={"Authorization": "Bearer " + self.settings["key"]}, json=body) as response:
                    if response.status_code != 200:
                        content = bytearray()
                        async for chunk in response.aiter_bytes():
                            if len(content) + len(chunk) > 65536:
                                content = bytearray(); break
                            content.extend(chunk)
                        hint = error_hint(response.status_code, content, self.settings["key"])
                        raise AIError(f"AI HTTP {response.status_code}：{hint}；未自动重试")
                    content = bytearray()
                    async for chunk in response.aiter_bytes():
                        content.extend(chunk)
                        if len(content) > 3000000: raise AIError("AI 响应超过 3 MB，已停止")
            value = json.loads(content)
            if not isinstance(value, dict) or not isinstance(value.get("output"), list) or len(value["output"]) > 50:
                raise AIError("服务返回格式不符合 Responses API")
            if value.get("status") in {"failed", "incomplete", "cancelled"}:
                raise AIError("AI 响应未完成，请核对模型/输出限制；未执行未完整返回的指令")
            return value["output"]
        except (httpx.HTTPError, TimeoutError):
            raise AIError("AI 网络/TLS/超时错误：检查 HTTPS 地址与网络；请求未自动重试") from None
        except (json.JSONDecodeError, UnicodeDecodeError):
            raise AIError("AI 服务未返回有效 JSON") from None

    async def test(self):
        tool = {"type": "function", "name": "connection_test", "description": "返回兼容性测试结果，不操作手机。",
                "parameters": {"type": "object", "properties": {}, "required": [], "additionalProperties": False}, "strict": True}
        output = await self.respond([{"role": "user", "content": "请调用 connection_test。"}], [tool],
                                    "只调用测试工具。", {"type": "function", "name": "connection_test"})
        valid = False
        for item in output:
            if item.get("type") == "function_call" and item.get("name") == "connection_test":
                try: valid = json.loads(item.get("arguments", "")) == {} and bool(item.get("call_id"))
                except (TypeError, json.JSONDecodeError): valid = False
        if not valid:
            raise AIError("服务可访问，但未返回标准 function_call；请确认模型支持 Responses 工具调用")
        return {"message": "Responses API 与工具调用测试通过（未操作手机）"}
