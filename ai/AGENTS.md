# ai 模块知识库

provider-neutral 模型调用协议层。零 Jcode 内部依赖；无 provider SDK、无 Agent、无 Session、无 coding 工具、无 UI。

## WHERE TO LOOK

| 任务 | 位置 |
|------|------|
| 接入 model provider | `client/ModelClient.java`（SPI，`stream()` 返回 `AssistantMessageStream`） |
| 模型调用请求 | `client/ModelRequest.java`（model + systemPrompt + messages + tools + thinkingLevel + options） |
| 请求控制 | `client/ModelRequestOptions.java`（maxOutputTokens/temperature/`ToolChoice`/`PromptCacheOptions`，`defaults()`） |
| 工具选择 | `client/ToolChoice.java`（sealed：`Mode.AUTO/NONE/REQUIRED` + `Specific(toolName)`） |
| Prompt cache | `client/PromptCacheOptions.java` + `client/CacheRetention.java`（PROVIDER_DEFAULT/NONE/SHORT/LONG） |
| 思考级别 | `model/ThinkingLevel.java`（PROVIDER_DEFAULT/OFF/MINIMAL/LOW/MEDIUM/HIGH/XHIGH/MAX 绝对请求值） |
| 模型身份 | `model/ModelRef.java`（provider/api/modelId 三维度轻量引用）/ `Model.java`（含 name） |
| 标准 LLM 消息 | `message/Message.java`（sealed：User/Assistant/ToolResultMessage；Assistant 含可选 `sourceModel` 与 `ResponseMetadata`） |
| 消息内容块 | `message/Content.java`（sealed：Text/Thinking/ToolCall/Image；Text/Thinking 含可选 `replayState`；Image 为 base64 + `image/*`，`toString()` 只报 media type 与编码长度） |
| 不透明重放状态 | `message/ModelReplayState.java`（format + payload；`toString()` redacts payload） |
| 响应相关元数据 | `message/ResponseMetadata.java`（optional response/request id 与 raw terminal reason；有长度上限；`empty()`；`toString()` 只报 present/absent） |
| 停止原因 | `message/StopReason.java`（enum，`isTerminalFailure()` = ERROR/ABORTED） |
| token 元数据 | `message/Usage.java`（input/output/cacheRead/cacheWrite/totalTokens + `reasoningTokens` + optional `CostEstimate`） |
| 成本估算 | `message/CostEstimate.java`（currency + 四组件 + total；`BigDecimal` + DECIMAL128；estimate 而非 invoice） |
| 可声明工具 | `tool/ToolSpec.java`（name/description/parameters JsonNode + `ToolInputConstraint`，给模型看） |
| 约束采样 | `tool/ToolInputConstraint.java`（sealed：`None` / `JsonSchema(Requirement)` / `Grammar(variants, Requirement)`）+ `Requirement`（PREFER/REQUIRE）+ `GrammarSyntax`（LARK/REGEX，无 provider wire 映射） |
| 只读取消 | `concurrent/CancellationSignal.java`（isCancelled/throwIfCancelled/onCancellation）+ `CancellationRegistration` |
| 流式事件协议 | `stream/AssistantMessageEvent.java`（sealed，12 变体：Start/Text/Thinking/ToolCall start-delta-end/Done/Error） |
| 流式消费契约 | `stream/AssistantMessageStream.java`（push-pull 阻塞队列；`push` 线性化 terminal，最多一个 Done/Error） |
| provider runtime | `provider/ModelProvider.java`（provider 运行时单元）/ `provider/Models.java`（集合 + ModelClient 路由视图）/ `provider/DefaultModels.java`（不可变集合）/ `provider/CopyOnWriteModels.java`（可变 copy-on-write 集合）/ `provider/ProviderAuth.java` + `AuthCheck`（认证状态，无 secret） |
| 合成失败流 | `stream/AssistantMessageStreams.java`（`failed(...)` 统一 `Start -> Error` 生命周期） |
| Unicode 清洗 | `util/UnicodeSanitizer.java`（无状态；删除 unpaired UTF-16 surrogate；合法 pair 原样；已合法字符串返回原实例；无 normalization） |

## CONVENTIONS

- `Message.Assistant` 携带 `Usage`（token 元数据）、可选 `sourceModel`（null = synthetic/legacy/未知来源）和 `ResponseMetadata`（默认 `empty()`）。`errorMessage` 仅允许在 `stopReason.isTerminalFailure()` 时非空（compact constructor 校验）。兼容五/六参数构造器与 `of(...)` 将 `sourceModel` 置为 null、metadata 置为 empty。OpenAI output message id/phase 仍只在 `ModelReplayState`。
- `ResponseMetadata` 只承载 optional response correlation id、provider request id、raw terminal reason；每字段 256 UTF-16 字符上限，越界拒绝；禁止 Map/raw response。`toString()` 不输出原值。
- `Usage` 保留五参数构造器（`reasoningTokens=0`、`cost` absent）。`reasoningTokens` 必须非负且 `<= output`。`cost()` 为 `Optional<CostEstimate>`：未定价必须 absent，不能用全零假装已估算。`Usage.zero()` 的 cost 亦为 absent。
- `CostEstimate` 金额为非负 `BigDecimal`，`total` 必须等于 DECIMAL128 组件和；明确是 estimate。
- `Content.Text` / `Content.Thinking` 携带可选 `ModelReplayState`；单参数构造器将 state 置为 null。`format + payload` 只由写入它的 adapter 解释；重建 content 若不显式保留 state 即清除。
- `Content.Image` 只接受标准 base64 与合法 `image/*` media type，不接受 data URL / 远程 URL / `byte[]`。`Message.User` 与 `Message.ToolResultMessage` 可携带图片；assistant 图片由调用方构造时，OpenAI adapter 作为 mapping error。
- `ModelReplayState.toString()` 必须 redact payload；公共类型中不得出现 provider 特有字段名。
- `Message.ToolResultMessage` 不含 `terminate` 字段；terminate 是运行时概念，仅存在于 `agent-core.ToolExecutionResult`，不进入标准 LLM transcript。
- `ModelClient.stream()` 不得同步抛异常；成功、模型错误、网络错误、主动取消均通过流终止事件（`Done`/`Error`）产生最终 `Message.Assistant`。
- `AssistantMessageEvent` 每个非终止变体携带 `partial`（累积中的半成品 Assistant）；终止变体 `Done`/`Error` 携带最终消息。`partial()` 方法在接口上声明，终止变体显式实现。
- `AssistantMessageStream` 为 push-pull 阻塞队列：adapter 调 `push(event)`，loop 调 `take()` 阻塞获取。`push` 将入队、`done` 与 `resultStage` 线性化为一步；终止事件后 `push` 被静默忽略，并发 terminal 最多一个。
- `CancellationSignal` 是只读协议，住在 `ai`（最低共享层），因为 Java 无 `AbortSignal` 等价物；model adapter 和工具都读同一个信号。创建/触发取消的 `CancellationSource` 在 `agent-core`。`onCancellation` 观察未来取消：已取消时 listener 在注册返回前执行一次；listener 必须非阻塞。
- `ToolSpec` 只携带可声明形状（给模型看）；执行能力在 `agent-core.AgentTool`。三参数构造器默认 `ToolInputConstraint.none()`。`Grammar` variants 做 defensive copy，blank definition 在构造时拒绝。`PREFER` 允许 adapter 安全降级；`REQUIRE` 必须 mapping failure。
- 所有 list 在 compact constructor 执行 `List.copyOf()`。
- `pom.xml` 声明本模块自己的 `enforce-module-boundaries` execution：禁止依赖 `ai-providers`、`agent-core`、`coding-agent` 及更高产品模块；根 POM 只管理插件版本。
- `ModelRequest` 只携带 `ai` 类型，不含 `AgentMessage` 或可执行工具。
- `ModelRequest` 携带绝对 `ThinkingLevel`：`PROVIDER_DEFAULT` 表示 adapter 不主动指定思考参数；`OFF` 明确请求关闭；其余为 provider-neutral 相对强度。厂商映射与不支持值的处理由 adapter 负责，通过流错误协议表达。
- `ModelRequest` 携带 `ModelRequestOptions`：`maxOutputTokens` 未设置或正整数（禁止 0/负数）；`temperature` 未设置或有限非负数；`ToolChoice` 不得用裸字符串同时表达模式和工具名。四参数/五参数构造器使用 `ModelRequestOptions.defaults()`。
- `PromptCacheOptions` 是请求 hint，不进入 transcript 或 assistant message。`PROVIDER_DEFAULT` 不覆盖 retention；显式 `cacheKey` 仍可作为 hint。`NONE` 同时抑制 cache key 与 affinity。Blank cache key / session id 视为 absent。`toString()` 只报 retention 与 present/absent，不输出原值。
- Provider runtime：`Models` 是唯一公开 provider 集合；`DefaultModels` 不可变；`CopyOnWriteModels` 可变（mutation 序列化，`stream()` call-start 快照）。`ModelProvider` 不实现 `ModelClient`；`Models` 实现 `ModelClient` 作为路由视图。`ProviderAuth` 只表达认证状态，不暴露 secrets。
- `UnicodeSanitizer` 只提供字符串 well-formedness / unpaired-surrogate 删除。不读 Message/ToolSpec，不做 normalization。null 由调用方边界处理。provider 在请求序列化时决定清洗文本还是拒绝身份字段。

## ANTI-PATTERNS

- `ModelClient.stream()` 不得同步抛；请求/模型/运行时失败编码进返回的 stream（立即 push `Error` 事件）。
- `Done` 的 `reason` 不得是 terminal failure（ERROR/ABORTED）；用 `Error` 代替。
- `Error` 的 `reason` 必须是 terminal failure（ERROR/ABORTED）。
- `Models.stream()` / `ModelProvider.stream()` 不抛同步 provider/网络异常；unknown provider/unsupported model 用 `AssistantMessageStreams.failed(...)` 生成 `Start -> Error`。
- provider runtime 不读取环境变量、不使用 `ServiceLoader`/静态注册表。
- 跨模块依赖与类型边界见 [`docs/agents/architecture-boundaries.md`](../docs/agents/architecture-boundaries.md)。
