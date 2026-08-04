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
| Responses 流 adapter | `openai/OpenAiResponsesAdapter.java`（package-private：HTTP/SSE 读取、事件映射、错误归一、producer 生命周期） |
| 请求映射 | `openai/OpenAiRequestMapper.java`（transcript replay + tools + thinking policy） |
| SSE 解析 | `openai/OpenAiSseParser.java`（纯 SSE 行解析，不含语义） |
| 事件映射 | `openai/OpenAiEventMapper.java`（Responses events → `AssistantMessageEvent`，按 output_index slot） |
| tool-call id | `openai/OpenAiToolCallIds.java`（`oai1:` 版本化 base64url codec） |
| 组合 | `new DefaultModels(List.of(openAiProvider))` 作为 `ModelClient` 传给 `AgentConfig` |

## CONVENTIONS

- 模块内每个 provider 一个子包：`site.pplee.jcode.aiproviders.<provider>`。新增 provider 时新建子包，不新增 Maven 模块。
- 每个 provider 子包的公开主入口是该 provider 的 runtime 类（如 `OpenAiProvider`）；HTTP/SSE adapter、mapper/parser/codec 全部 package-private。
- provider runtime 不实现 `ModelClient`；provider 是 runtime 单元，adapter 是内部实现。
- 不在模块内读取环境变量/配置文件；凭证通过各 provider 的显式配置类型传入。
- 所有失败（归属校验/配置/HTTP/JSON/SSE/provider/取消）归一为 stream `Start -> Error`，不同步抛、不用 failed stage。
- `stream()` 同步 push `Start` 后立即返回，HTTP/SSE 在 provider-owned virtual-thread executor 上异步执行。
- 取消是协作式：dispatch 前、请求前、每个 SSE 事件边界检查；长静默 HTTP read 只依赖 request timeout。
- `OpenAiToolCallIds` 把 `call_id|item_id` 编码为 `oai1:<base64url>`，replay 时 decode；raw id 直接按 call_id 处理。
- `ToolSpec.parameters()` 为 `NullNode` 时规范化为空 object schema；非 object schema 转 terminal Error。
- `ThinkingLevel` 非默认值必须经 `OpenAiModelCapabilities.reasoningEfforts` 显式映射，缺失映射转 terminal Error。
- `response.completed` → `STOP`（含 tool call 时 `TOOL_CALL`）；`response.incomplete` → `LENGTH`；`response.failed`/`error` → `ERROR`；EOF/`[DONE]` 早于 terminal → `ERROR`。
- usage 公式：`input = max(0, input_tokens - cached - cacheWrite)`，`cacheRead = cached_tokens`，`cacheWrite = cache_write_tokens`。

## ANTI-PATTERNS

- 不在 provider runtime 之外公开 adapter/mapper/parser。
- 不把 API key 放进 record component 或 `toString()`。
- 不在 provider 配置/凭证类型中读取环境变量。
- 不在 adapter 中同步抛 provider/网络异常。
- 不把 provider 特有字段塞进 `ai.Message`/`ai.Model`。
- 不引入 provider SDK；HTTP 走 JDK `HttpClient`。
- 不为每个 provider 新建 Maven 模块；provider 实现都放在本模块内。
