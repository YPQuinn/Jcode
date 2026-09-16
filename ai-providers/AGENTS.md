# ai-providers 模块知识库

共享的具体 provider 模块。每个 provider 一个子包（`openai`、后续 `anthropic`、`google`……），公开 provider runtime，低层 API dialect adapter 保持 package-private。唯一 Jcode 内部依赖：`ai`。

当前包含 OpenAI provider（Responses API）：公开主入口是 `OpenAiProvider implements ModelProvider`；低层 Responses API adapter 保持 package-private。

## WHERE TO LOOK

| 任务 | 位置 |
|------|------|
| OpenAI provider runtime | `openai/OpenAiProvider.java`（public，implements `ai.provider.ModelProvider`） |
| OpenAI 显式配置 | `openai/OpenAiProviderConfig.java`（final class，`toString()` redacted；`responses(credentials, models)` 工厂 + `builder()`） |
| 密钥持有 | `openai/OpenAiCredentials.java`（redacted；API key 仅 package-private 访问） |
| 模型能力 | `openai/OpenAiModelCapabilities.java`（reasoning + `ThinkingLevel` → effort 映射，按 modelId 配置） |
| auth 状态 | `openai/OpenAiApiKeyAuth.java`（package-private，显式 API key 状态，无 secret） |
| Responses 流 adapter | `openai/OpenAiResponsesAdapter.java`（package-private：`sendAsync` + ActiveExchange，取消/close 立即中断 HTTP/SSE，terminal-once） |
| 请求映射 | `openai/OpenAiRequestMapper.java`（组合 system prompt、planned input、tools、reasoning include/summary policy） |
| transcript 规划 | `openai/OpenAiTranscriptPlanner.java`（same-model reasoning replay、handoff、ERROR/ABORTED 过滤、tool pairing/synthetic output） |
| replay envelope | `openai/OpenAiReplayStateCodec.java`（`openai-responses/reasoning-item-v1` 与 `message-item-v1`；digest 不匹配则忽略） |
| SSE 解析 | `openai/OpenAiSseParser.java`（纯 SSE 行解析，不含语义） |
| 事件映射 | `openai/OpenAiEventMapper.java`（Responses events → `AssistantMessageEvent`；done-only recovery、summary/refusal、sourceModel + replayState） |
| partial JSON | `openai/OpenAiPartialJsonParser.java`（严格 parse 失败后只闭合 object root，不猜字段） |
| tool-call id | `openai/OpenAiToolCallIds.java`（`oai1:` 版本化 base64url codec；unsafe raw id 哈希为 `call_jcode_<sha256>`） |
| 组合 | `new DefaultModels(List.of(openAiProvider))` 作为 `ModelClient` 传给 `AgentConfig` |

## CONVENTIONS

- 模块内每个 provider 一个子包：`site.pplee.jcode.aiproviders.<provider>`。新增 provider 时新建子包，不新增 Maven 模块。
- 每个 provider 子包的公开主入口是该 provider 的 runtime 类（如 `OpenAiProvider`）；HTTP/SSE adapter、mapper/parser/codec 全部 package-private。
- provider runtime 不实现 `ModelClient`；provider 是 runtime 单元，adapter 是内部实现。
- 不在模块内读取环境变量/配置文件；凭证通过各 provider 的显式配置类型传入。
- 所有失败（归属校验/配置/HTTP/JSON/SSE/provider/取消）归一为 stream `Start -> Error`，不同步抛、不用 failed stage。
- `stream()` 同步 push `Start` 后立即返回，HTTP/SSE 在 provider-owned virtual-thread executor 上异步执行。
- 取消通过 `CancellationSignal.onCancellation` 绑定 ActiveExchange：`sendAsync` future `cancel(true)` + 关闭 response body；caller cancel → `ABORTED`，provider close → `ERROR("provider is closed")`。terminal 恰好一次。
- SSE 语义名：`event:` 为 blank/`message` 时回退 JSON `type`；两者都在且冲突则 terminal `ERROR`，不静默选择。
- `OpenAiTranscriptPlanner` 只规划 request-local 视图：`ERROR`/`ABORTED` assistant 及其 tool result 丢弃；未配对 call 补 `"No result provided"`；孤立 tool result 丢弃。same-model 仅在 reasoning 含 `encrypted_content` 时保留 function-call item id。
- `OpenAiToolCallIds` 把 `call_id|item_id` 编码为 `oai1:<base64url>`；畸形 `oai1:` 仍 mapping error。safe raw id 作 call id；unsafe raw id 确定性哈希，匹配的 tool result 共用同一 id。
- `ToolSpec.parameters()` 为 `NullNode` 时规范化为空 object schema；非 object schema 转 terminal Error。
- `ThinkingLevel`：`PROVIDER_DEFAULT` 不发送 effort/summary；capabilities 标明 reasoning 或 same-model 正在 replay reasoning 时发送 `include:["reasoning.encrypted_content"]`。显式非 OFF 另发 effort + `summary:"auto"` + include。`OFF` 不发 summary/include。
- `response.completed` → `STOP`（含 tool call 时 `TOOL_CALL`）；`response.incomplete` 仅 `max_output_tokens` → `LENGTH`，`content_filter`/缺失/未知 reason → `ERROR`；`response.failed`/`error` → `ERROR`；EOF/`[DONE]` 早于 terminal → `ERROR`。
- reasoning final：`item.summary`（`\n\n` join）→ `item.content` → deltas。message final 读 `output_text.text` 与 `refusal.refusal`，多 block 直接拼接。
- usage 公式：`input = max(0, input_tokens - cached - cacheWrite)`，`cacheRead = cached_tokens`，`cacheWrite = cache_write_tokens`。

## ANTI-PATTERNS

- 不在 provider runtime 之外公开 adapter/mapper/parser。
- 不把 API key 放进 record component 或 `toString()`。
- 不在 provider 配置/凭证类型中读取环境变量。
- 不在 adapter 中同步抛 provider/网络异常。
- 不把 provider 特有字段名塞进 `ai.Message`/`ai.Model`；OpenAI item 只进 `ModelReplayState` payload 或 `oai1:` tool-call id。
- 不把 Responses transcript 规则放到 `AgentLoop` / `ContextTransformer` / `MessageProjector`。
- 不引入 provider SDK；HTTP 走 JDK `HttpClient`。
- 不为每个 provider 新建 Maven 模块；provider 实现都放在本模块内。
