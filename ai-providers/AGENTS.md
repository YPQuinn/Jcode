# ai-providers 模块知识库

共享的具体 provider 模块。每个 provider 一个子包（`openai`、后续 `anthropic`、`google`……），公开 provider runtime，低层 API dialect adapter 保持 package-private。唯一 Jcode 内部依赖：`ai`。

当前包含 OpenAI provider（Responses API）：公开主入口是 `OpenAiProvider implements ModelProvider`；低层 Responses API adapter 保持 package-private。

## WHERE TO LOOK

| 任务 | 位置 |
|------|------|
| OpenAI provider runtime | `openai/OpenAiProvider.java`（public，implements `ai.provider.ModelProvider`） |
| OpenAI 显式配置 | `openai/OpenAiProviderConfig.java`（final class，`toString()` redacted；`responses(credentials, models)` 工厂 + `builder()`；可选 `serviceTier` / `OpenAiHeaders` / `OpenAiPricing` / `OpenAiRetryPolicy`） |
| 价格表 | `openai/OpenAiPricing.java`（按 model id；每百万 input/output/cache-read/cache-write；可选 context threshold 与 service-tier multiplier；DECIMAL128；默认 absent） |
| 重试策略 | `openai/OpenAiRetryPolicy.java`（公开不可变；`maxRetries` 默认 0；`maxServerDelay` 默认 60s；指数退避上限默认 8s；jitter 0.75–1.0；可选 totalBudget；旧 builder 默认 disabled） |
| 结构化 HTTP 错误 | `openai/OpenAiHttpError.java`（package-private；status + retry allowlist 头 + `x-request-id` + ≤4KiB body + envelope code/message/type；`toString` redacted） |
| 密钥持有 | `openai/OpenAiCredentials.java`（redacted；API key 仅 package-private 访问） |
| 模型能力 | `openai/OpenAiModelCapabilities.java`（reasoning + `imageInput` + `developerRolePreferred` + `temperature` + `toolChoice` + `ThinkingLevel` → effort 映射；两/四参数构造默认无 image、不优先 developer、无 sampling extras） |
| endpoint 兼容 | `openai/OpenAiResponsesCompatibility.java`（developer role、`OpenAiEndpointProfile`、maxOutputTokens/promptCacheKey/longCacheRetention/`strictTools`/`grammarTools`；`openai()` / `forceSystem()` / `openaiNoSession()` 开启官方能力；`openRouter()` 与一/五参数构造为保守自定义；不从 URL 推断） |
| 自定义头 | `openai/OpenAiHeaders.java`（builder；普通/sensitive header；`toString()` 只报名称与数量；reserved 头不可覆盖；`copyValuesForRedaction` 仅 package-private） |
| 请求本地清洗 | `openai/OpenAiSecretRedactor.java`（package-private；收集本请求实际发送的敏感值，terminal Error 统一 replace 为 `[redacted]`；`toString` 只报数量） |
| auth 状态 | `openai/OpenAiApiKeyAuth.java`（package-private，显式 API key 状态，无 secret） |
| Responses 流 adapter | `openai/OpenAiResponsesAdapter.java`（package-private：`sendAsync` + ActiveExchange，取消/close 立即中断 HTTP/SSE/backoff timer，opt-in retry，terminal-once） |
| 请求映射 | `openai/OpenAiRequestMapper.java`（组合 system/developer prompt、planned input、function/custom tools、reasoning include/summary policy、max token clamp、temperature/tool choice、prompt cache、service tier、request-local Unicode） |
| 请求 Unicode | `openai/OpenAiRequestUnicode.java`（package-private；文本清洗 + 身份字段 well-formed 失败 + JsonNode request-local deep copy） |
| 约束采样 | `openai/OpenAiConstrainedSampling.java`（strict JSON Schema 转换、LARK/REGEX 选择、grammar JSON delta、grammar input property 表） |
| transcript 规划 | `openai/OpenAiTranscriptPlanner.java`（same-model reasoning replay、handoff、ERROR/ABORTED 过滤、tool pairing/synthetic output、空工具结果与 vision 图片映射、grammar custom_tool_call replay） |
| replay envelope | `openai/OpenAiReplayStateCodec.java`（`openai-responses/reasoning-item-v1` 与 `message-item-v1`；digest 不匹配则忽略） |
| SSE 解析 | `openai/OpenAiSseParser.java`（纯 SSE 行解析，不含语义） |
| 事件映射 | `openai/OpenAiEventMapper.java`（Responses events → `AssistantMessageEvent`；done-only recovery、summary/refusal、sourceModel + replayState、custom-tool → 标准 ToolCall 事件、response metadata / reasoning usage / optional cost） |
| partial JSON | `openai/OpenAiPartialJsonParser.java`（严格 parse 失败后只闭合 object root，不猜字段） |
| tool-call id | `openai/OpenAiToolCallIds.java`（`oai1:` 版本化 base64url codec；unsafe raw id 哈希为 `call_jcode_<sha256>`） |
| 组合 | `new DefaultModels(List.of(openAiProvider))` 作为 `ModelClient` 传给 `AgentConfig` |

## CONVENTIONS

- 模块内每个 provider 一个子包：`site.pplee.jcode.aiproviders.<provider>`。新增 provider 时新建子包，不新增 Maven 模块。
- `pom.xml` 声明本模块自己的 `enforce-module-boundaries` execution：唯一允许的 Jcode 内部依赖是 `ai`；根 POM 只管理插件版本。
- 每个 provider 子包的公开主入口是该 provider 的 runtime 类（如 `OpenAiProvider`）；HTTP/SSE adapter、mapper/parser/codec 全部 package-private。
- provider runtime 不实现 `ModelClient`；provider 是 runtime 单元，adapter 是内部实现。
- 不在模块内读取环境变量/配置文件；凭证通过各 provider 的显式配置类型传入。
- 所有失败（归属校验/配置/HTTP/JSON/SSE/provider/取消）归一为 stream `Start -> Error`，不同步抛、不用 failed stage。
- `stream()` 同步 push `Start` 后立即返回，HTTP/SSE 在 provider-owned virtual-thread executor 上异步执行。
- 取消通过 `CancellationSignal.onCancellation` 绑定 ActiveExchange：`sendAsync` future `cancel(true)` + 关闭已接受的 2xx SSE body 与非 2xx error body + 取消 retry timer/future；caller cancel → `ABORTED`，provider close → `ERROR("provider is closed")`。listener 只取消 I/O 与 timer，不阻塞。terminal 恰好一次。close 后不再提交 retry。error-body 读取期间的 IO 先按 abort cause 归一，不得继续调度 retry。每个非 2xx attempt 在 bounded read 完成（成功或失败）后立即 `releaseBody`（identity-safe detach），不得把剩余 body 拖过 backoff/下一 attempt。
- Opt-in retry：`OpenAiRetryPolicy.maxRetries` 默认 0（一次请求）。仅在成功 2xx SSE 被接受前重试；一旦进入 SSE 消费，EOF/parse/mapping/read failure 不重试。优先级：`x-should-retry` true/false 覆盖 → 无 status 的 connect/send transport failure → HTTP 408/409/429/500–599；499 与 600+ 不按 5xx 重试；其他 4xx 不重试。delay：`retry-after-ms` → `Retry-After` 秒 → HTTP 日期 → 指数退避+jitter；非法 header 安全回退；负 delay 归零；服务端 delay 超过 `maxServerDelay` 立即最终失败。`totalBudget` 从首次 attempt 起的 wall-clock 总预算；wait 完成后与下一 attempt 发送前复查 deadline；clock 回拨视为 elapsed=0；Duration 运算溢出视为预算耗尽；已超预算时即使 planned delay 为 0 也不再发送。`sendAsync` 同步 `RuntimeException` 使用固定 `request failed`，仅 `UncheckedIOException` 按 transport 规则重试。每次 attempt 重建 `HttpRequest`/`BodyPublisher`，复用不可变请求 JSON bytes。attempt 间新建 `OpenAiEventMapper`，不保留 partial。clock/random/scheduler 为 package-private seam，不进入公开 API。
- `OpenAiHttpError`：只读 status、retry allowlist 头、`x-request-id`、最多 4KiB body、标准 envelope `code`/`message`/`type`。生产 parse 必须传入 request-local redactor，retained body/envelope/requestId/diagnostic 在对象内即已清洗；无 redactor 的 overload 仅测试便利。retry headers/status 保持原始，判断绝不依赖文本。非 2xx body 中的 id 不得进入 `ResponseMetadata`。
- `OpenAiSecretRedactor`：每个 stream 请求收集 credentials、org/project、全部 custom header values、原始及 clamp 后 cache key、session-affinity id；所有含外部/provider 文本的 terminal Error（含 mapper 直接生成的 Error）统一清洗 message 与 metadata（`responseId`/`providerRequestId`/`rawTerminalReason`），不改 usage/content/sourceModel。synthetic `completeOnce(reason,message,metadata)` 同样清洗 metadata。纯内部异常（mapping/SSE read/safety net/`sendAsync` 编程错误）使用固定稳定消息，不拼 `RuntimeException`/`IOException` message。SSE event-name/type conflict 使用固定 `SSE event name and JSON type conflict`，不把 provider 原文写入异常。敏感值不进入异常、`toString` 或公开 header accessor。
- SSE 语义名：`event:` 为 blank/`message` 时回退 JSON `type`；两者都在且冲突则 terminal `ERROR`，不静默选择。
- `OpenAiTranscriptPlanner` 只规划 request-local 视图：`ERROR`/`ABORTED` assistant 及其 tool result 丢弃；未配对 call 补 `"No result provided"`；孤立 tool result 丢弃。same-model 仅在 reasoning 含 `encrypted_content` 时保留 function-call item id。空或全空白 tool result 输出 `"(no tool output)"`。vision 开启时用户/工具图片映射为 `input_image`（data URL 仅在 mapper 内组装）并留在 `function_call_output`；否则按原顺序变成 `"[Image omitted: image/png]"`。tool result 中的 Thinking/ToolCall 与 assistant Image 是 mapping error，不静默丢弃。
- system prompt：model `developerRolePreferred` 且 endpoint `developerRole` 时发 `developer`，否则 `system`；空 prompt 省略。老配置与 null capabilities 保持 `system`。`forceSystem()` 可在不改 transcript 的情况下强制 `system`。
- 请求控制：`maxOutputTokens` 未设置不发送；1–15 clamp 为 16；`>=16` 原值发送。temperature / 非 AUTO tool choice 仅在 model capability 支持时发送；`Specific` 必须在本次 tools 中，且 `type` 跟随同一 `resolveGrammar` 事实（custom 或 function，PREFER 降级仍为 function）；`REQUIRED` 在无工具时失败。显式 `OpenAiServiceTier` 写入 `service_tier`。不支持的显式选项 terminal mapping error，不静默忽略。
- Prompt cache：`NONE` 不写 cache body 且省略 affinity；`PROVIDER_DEFAULT` 不发送 retention 覆盖，但显式 cache key 在 endpoint 支持时仍发 `prompt_cache_key`；`SHORT` 需要 `promptCacheKey`；`LONG` 需要 `longCacheRetention` 并发送 `prompt_cache_retention=24h`。cache key 按 Unicode code point 截断 64，不按 UTF-16 切断 emoji。cache key 是 cache identity：非法 UTF-16 失败（`malformed UTF-16 in prompt cache key`），不静默清洗。cache key 与 session id 不进入 transcript。`buildRequest` 失败（非法 API key/session/org/project/custom header）使用固定 `request headers could not be applied`，不把 header value 写入事件。
- Session affinity：`OPENAI` 发 `session_id` + `x-client-request-id`；`OPENAI_NO_SESSION` 只发 `x-client-request-id`；`OPENROUTER` 发 `x-session-id`。retention `NONE` 或无 session id 时全部省略。不按 URL 推断 profile。
- `OpenAiHeaders`：additive merge，不可覆盖 Host/Content-Length/Content-Type/Accept/Authorization/组织项目头/cache-session 保留头。`Authorization` 只来自 `OpenAiCredentials`。`Proxy-Authorization` 必须走 sensitive header。builder 拒绝 NUL/CR/LF/DEL 等非法控制字符，错误不带 value。header value 不进入异常、事件或 config diagnostics。
- `OpenAiToolCallIds` 把 `call_id|item_id` 编码为 `oai1:<base64url>`；畸形 `oai1:` 仍 mapping error。safe raw id 作 call id；unsafe raw id 确定性哈希，匹配的 tool result 共用同一 id。
- `ToolSpec.parameters()` 为 `NullNode` 时规范化为空 object schema；非 object schema 转 terminal Error。普通 function payload 不发送 `strict`。`JsonSchema` 仅在 endpoint `strictTools` 且 schema 可转为 OpenAI strict subset 时发 `strict: true` 与转换后的 schema；`PREFER` 在能力或 schema 不支持时降级普通 function，`REQUIRE` 为 mapping error。`Grammar` 在 `grammarTools` 开启时映射 Responses custom tool（LARK 优先于 REGEX，`lark`/`regex` wire token 只在本模块翻译）；schema 必须是恰好一个 required string property 的 object。能力开启但 variant/schema malformed 一律失败；能力关闭时 `PREFER` 降级、`REQUIRE` 失败。Custom-tool 流归一为 `ToolCallStart → ToolCallDelta(JSON string field) → ToolCallEnd(arguments object)`；mapper 必须使用该工具自己的 input property，不得硬编码 `input`。累计输入只允许单调追加；present 但非 textual 的 custom `input`/`delta` 是 protocol error；`input.delta` 必须是 textual；added/done 缺 `input` 时用空值或累计值。`response.completed`/`incomplete` 的 `response.output` 对仍 open 的 custom slot 做权威 finalization，已完成项不重复，无 added 时仍 Start→Delta→End。close 后变化与 malformed event 失败。scratch 不进入 transcript。下一 turn：grammar call/result 走 `custom_tool_call` / `custom_tool_call_output`，plain tools 保持 `function_call`。deferred tool loading 不在本批。
- `ThinkingLevel`：`PROVIDER_DEFAULT` 不发送 effort/summary；capabilities 标明 reasoning 或 same-model 正在 replay reasoning 时发送 `include:["reasoning.encrypted_content"]`。显式非 OFF 另发 effort + `summary:"auto"` + include。`OFF` 不发 summary/include。
- `response.completed` → `STOP`（含 tool call 时 `TOOL_CALL`）；`response.incomplete` 仅 `max_output_tokens` → `LENGTH`，`content_filter`/缺失/未知 reason → `ERROR`；`response.failed`/`error` → `ERROR`；EOF/`[DONE]` 早于 terminal → `ERROR`。
- reasoning final：`item.summary`（`\n\n` join）→ `item.content` → deltas。message final 读 `output_text.text` 与 `refusal.refusal`，多 block 直接拼接。
- usage 公式：`input = input_tokens - cached - cacheWrite`，`cacheRead = cached_tokens`，`cacheWrite = cache_write_tokens`，`reasoningTokens = output_tokens_details.reasoning_tokens`。token 字段做 exact integral long 转换；超 long / 小数 / 负数、以及 cache/total/billed-input 的 checked 加减溢出均为 protocol mapping error，不 saturate/截断。`input_tokens_details`/`output_tokens_details` present 且非 null 时必须是 object，否则 mapping error；absent/null 视为无 detail。不要求 `totalTokens` 等于拆分组件（provider `input_tokens` 已含 cache）。未配置价格时 `cost` absent。
- response metadata：`response.created` 有界记录 correlation id，不写入 partial metadata；terminal `response.id` 优先，缺 id 时回退 created。`correlationSnapshot()` 供 adapter 在每次 `onEvent` 成功或抛错后同步到 `ActiveExchange`（exchange lock）；synthetic ERROR/ABORTED 沿用 created/terminal id、`x-request-id`，若 terminal metadata 已构造则沿用 raw reason。raw reason 用 `status`，incomplete 有原因时为 `status.reason`；provider request id 只从响应头 `x-request-id`（大小写不敏感）提取。request headers、credential、error body 与任意 header 不得进入 metadata。非 2xx 的 metadata 只保留 `x-request-id`，不把 error body 的 id 或其他字段写入 metadata；诊断文本由 `OpenAiHttpError` 单独解析。HTTP error envelope、`response.failed` 和 SSE `error` 仅在结构化 code 精确为 `context_length_exceeded` 时映射 `CONTEXT_OVERFLOW`，不得按状态码或错误文案猜测。OpenAI message id/phase 仍只在 `ModelReplayState`。
- `OpenAiPricing` 只由 `OpenAiProviderConfig` 显式注入，默认 absent，按 model id 查找。调用方提供价格，不硬编码。阈值：billed input（checked `input + cacheRead + cacheWrite`）**严格大于** `inputTokensAbove` 时启用该档，多档取最高阈值。service multiplier：仅当 terminal `service_tier` absent/null/blank 时回退显式请求 tier；明确返回的未知非空 tier 不套用 requested multiplier（视为未配置，multiplier=1）；present 非字符串是 mapping error。金额 DECIMAL128，总额为组件和。pricing/config `toString()` 不含 credential。
- metadata/pricing 不改变默认 request payload。adapter 只把 allowlisted 响应头传给 mapper；Start → terminal、terminal-once、取消与 SSE 生命周期不变。
- Unicode：仅清洗 request-local 序列化文本（system/developer prompt、user/assistant/tool-result 可见文本、tool description、JSON Schema 与 tool-call arguments 的 textual node、grammar/custom input）。image base64 与 opaque replay/encrypted payload 不清洗。tool name（在任何 constraint resolution / 诊断引用前校验）、tool-call id、model/provider/api id、header name/value（含 Authorization API key、session/org/project）、JSON object field name、prompt cache key、assistant replay item id 与非 null phase 非法 UTF-16 失败，诊断不含原值。请求期身份失败走 Start → Error（mapper → `request mapping failed`；model identity 在 `OpenAiProvider.stream` 先于 unsupported-model 诊断；API key/session/org/project 走 `request headers could not be applied`）。`OpenAiHeaders` builder 仍在配置期拒绝畸形 header。不修改 Message/ToolSpec/AgentContext/replay state。

## ANTI-PATTERNS

- 不在 provider runtime 之外公开 adapter/mapper/parser。
- 不把 API key 或 custom header value 放进 record component、`toString()`、异常或事件。
- 不在 provider 配置/凭证类型中读取环境变量。
- 不在 adapter 中同步抛 provider/网络异常。
- 不把 provider 特有字段名塞进 `ai.Message`/`ai.Model`；OpenAI item 只进 `ModelReplayState` payload 或 `oai1:` tool-call id。
- 不把 Responses transcript 规则放到 `AgentLoop` / `ContextTransformer` / `MessageProjector`。
- 不引入 provider SDK；HTTP 走 JDK `HttpClient`。
- 不为每个 provider 新建 Maven 模块；provider 实现都放在本模块内。
