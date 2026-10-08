# Evara Windows 0.3.1：DeepSeek 兼容修复

用户反馈 0.3.0 的 API 测试返回 HTTP 400，旧版只显示泛化错误。截图不足以确认服务端拒绝的准确参数，本版针对官方文档中的兼容限制修正请求，并提供不泄漏凭证或健康原文的诊断。

## 升级与复测

1. 退出旧 Evara（如有手机会话，会询问并结束）。解压本版整个目录，双击 Evara.exe，标题应显示 Windows 0.3.1。保留 _internal。不需要更新 APK、不需要重新配对或重新填写已保存密钥。
2. AI 基础地址填写 `https://api.deepseek.com`，模型填写 `deepseek-flash`。保持原密钥输入框为空，点击“加密保存设置”。更换到其他服务地址时必须重新输入对应密钥。
3. 点击“测试 API 与工具调用”。它发送不含健康数据的测试，不操作手机，可能消耗 API 额度。测试通过后先关闭截图分享，发送一条普通文字消息；再连接手机并授权读取，发送“读取当前睡眠详情并解释”。
4. 若仍出现 400，反馈完整脱敏错误提示。无需发送 API Key、配对资料或凭证文件。诊断会显示已知错误类型、参数名称和处理提示，未回显服务原始错误正文，不自动重试。

## 请求变化与边界

- 仅精确主机名 `api.deepseek.com` 启用 DeepSeek 配置，支持根地址或 /v1。其他兼容 Responses 服务和 OpenAI 配置保持原样；没有将密钥转发到其他服务，也没有自动切换协议或重试接口。
- DeepSeek 请求使用 `reasoning: {effort: "none"}`，关闭默认思考模式，避免测试指定工具时的兼容问题；不发送 Beta strict 标记。输入的 JSON Schema 和 APK 端检查保留，客户端仍拒绝未知工具、额外参数、未经授权操作、重复任务和并行指令。
- 去掉 DeepSeek 未支持的 `include` 与 `store` 请求参数；DeepSeek 官方文档说明 Responses 不支持存储响应。此行为不承诺提供商不保留安全日志或缓存。
- 提示词要求逐次调用一个工具。DeepSeek 会忽略 parallel_tool_calls:false，若模型仍返回多个工具，本地仍拒绝执行，保留串行安全边界。
- 非 200 错误正文最多读取 64 KiB；只识别固定错误类别和参数，显示可理解提示。API Key、请求原文、健康原文、完整服务端错误正文不写入诊断。

官方参考：[Responses 兼容说明](https://api-docs.deepseek.com/guides/responses_api/)、[思考模式开关](https://api-docs.deepseek.com/guides/thinking_mode/)、[工具与 Beta strict 模式](https://api-docs.deepseek.com/guides/tool_calls/)。这些说明指导实现，不代替实际账户联调。

## 验证

- 回归测试新增 DeepSeek 官方地址参数配置、其他主机不误识别、400 错误分类/脱敏、错误正文大小限制和不重试检查。
- 使用 HTTPX 模拟服务检查：原配置的思考/strict 条件会返回模拟 400；兼容配置返回标准工具结果。原有真实 WSS + 本机合成 AI 测试服务联调及取消、迟到结果过滤继续覆盖。
- 没有使用用户密钥，没有调用真实 DeepSeek，没有向外部发送健康数据；本次 HTTP 400 的准确服务端原因仍需新版错误提示或实际复测确认。
- 最终测试数量、便携版启动结果及 SHA-256 见本次交付的 Evara-0.3.1-测试结果.md 和 Evara-0.3.1-SHA256SUMS.txt。
