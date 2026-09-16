# ai 模块知识库

provider-neutral 模型调用协议层。零 Jcode 内部依赖；无 provider SDK、无 Agent、无 Session、无 coding 工具、无 UI。

## WHERE TO LOOK

| 任务 | 位置 |
|------|------|
| 接入 model provider | `client/ModelClient.java`（SPI，`stream()` 返回 `AssistantMessageStream`） |
| 模型调用请求 | `client/ModelRequest.java`（model + systemPrompt + messages + tools + thinkingLevel） |
| 思考级别 | `model/ThinkingLevel.java`（PROVIDER_DEFAULT/OFF/MINIMAL/LOW/MEDIUM/HIGH/XHIGH/MAX 绝对请求值） |
| 模型身份 | `model/ModelRef.java`（provider/api/modelId 三维度轻量引用）/ `Model.java`（含 name） |
| 标准 LLM 消息 | `message/Message.java`（sealed：User/Assistant/ToolResultMessage；Assistant 含可选 `sourceModel`） |
| 消息内容块 | `message/Content.java`（sealed：Text/Thinking/ToolCall；Text/Thinking 含可选 `replayState`） |
| 不透明重放状态 | `message/ModelReplayState.java`（format + payload；`toString()` redacts payload） |
| 停止原因 | `message/StopReason.java`（enum，`isTerminalFailure()` = ERROR/ABORTED） |
| token 元数据 | `message/Usage.java`（已接入 `Message.Assistant.usage`） |
| 可声明工具 | `tool/ToolSpec.java`（name/description/parameters JsonNode，给模型看） |
| 只读取消 | `concurrent/CancellationSignal.java`（isCancelled/throwIfCancelled/onCancellation）+ `CancellationRegistration` |
| 流式事件协议 | `stream/AssistantMessageEvent.java`（sealed，12 变体：Start/Text/Thinking/ToolCall start-delta-end/Done/Error） |
| 流式消费契约 | `stream/AssistantMessageStream.java`（push-pull 阻塞队列；`push` 线性化 terminal，最多一个 Done/Error） |
| provider runtime | `provider/ModelProvider.java`（provider 运行时单元）/ `provider/Models.java`（集合 + ModelClient 路由视图）/ `provider/DefaultModels.java`（不可变集合）/ `provider/CopyOnWriteModels.java`（可变 copy-on-write 集合）/ `provider/ProviderAuth.java` + `AuthCheck`（认证状态，无 secret） |
| 合成失败流 | `stream/AssistantMessageStreams.java`（`failed(...)` 统一 `Start -> Error` 生命周期） |

## CONVENTIONS

- `Message.Assistant` 携带 `Usage`（token 元数据）和可选 `sourceModel`（null = synthetic/legacy/未知来源）；`errorMessage` 仅允许在 `stopReason.isTerminalFailure()` 时非空（compact constructor 校验）。兼容五参数构造器与 `of(...)` 将 `sourceModel` 置为 null。
- `Content.Text` / `Content.Thinking` 携带可选 `ModelReplayState`；单参数构造器将 state 置为 null。`format + payload` 只由写入它的 adapter 解释；重建 content 若不显式保留 state 即清除。
- `ModelReplayState.toString()` 必须 redact payload；公共类型中不得出现 provider 特有字段名。
- `Message.ToolResultMessage` 不含 `terminate` 字段；terminate 是运行时概念，仅存在于 `agent-core.ToolExecutionResult`，不进入标准 LLM transcript。
- `ModelClient.stream()` 不得同步抛异常；成功、模型错误、网络错误、主动取消均通过流终止事件（`Done`/`Error`）产生最终 `Message.Assistant`。
- `AssistantMessageEvent` 每个非终止变体携带 `partial`（累积中的半成品 Assistant）；终止变体 `Done`/`Error` 携带最终消息。`partial()` 方法在接口上声明，终止变体显式实现。
- `AssistantMessageStream` 为 push-pull 阻塞队列：adapter 调 `push(event)`，loop 调 `take()` 阻塞获取。`push` 将入队、`done` 与 `resultStage` 线性化为一步；终止事件后 `push` 被静默忽略，并发 terminal 最多一个。
- `CancellationSignal` 是只读协议，住在 `ai`（最低共享层），因为 Java 无 `AbortSignal` 等价物；model adapter 和工具都读同一个信号。创建/触发取消的 `CancellationSource` 在 `agent-core`。`onCancellation` 观察未来取消：已取消时 listener 在注册返回前执行一次；listener 必须非阻塞。
- `ToolSpec` 只携带可声明形状（给模型看）；执行能力在 `agent-core.AgentTool`。
- 所有 list 在 compact constructor 执行 `List.copyOf()`。
- `ModelRequest` 只携带 `ai` 类型，不含 `AgentMessage` 或可执行工具。
- `ModelRequest` 携带绝对 `ThinkingLevel`：`PROVIDER_DEFAULT` 表示 adapter 不主动指定思考参数；`OFF` 明确请求关闭；其余为 provider-neutral 相对强度。厂商映射与不支持值的处理由 adapter 负责，通过流错误协议表达。
- Provider runtime：`Models` 是唯一公开 provider 集合；`DefaultModels` 不可变；`CopyOnWriteModels` 可变（mutation 序列化，`stream()` call-start 快照）。`ModelProvider` 不实现 `ModelClient`；`Models` 实现 `ModelClient` 作为路由视图。`ProviderAuth` 只表达认证状态，不暴露 secrets。

## ANTI-PATTERNS

- `ModelClient.stream()` 不得同步抛；请求/模型/运行时失败编码进返回的 stream（立即 push `Error` 事件）。
- `Done` 的 `reason` 不得是 terminal failure（ERROR/ABORTED）；用 `Error` 代替。
- `Error` 的 `reason` 必须是 terminal failure（ERROR/ABORTED）。
- `Models.stream()` / `ModelProvider.stream()` 不抛同步 provider/网络异常；unknown provider/unsupported model 用 `AssistantMessageStreams.failed(...)` 生成 `Start -> Error`。
- provider runtime 不读取环境变量、不使用 `ServiceLoader`/静态注册表。
- 模块边界与不含类型约束见根 AGENTS.md ANTI-PATTERNS（模块边界节）。
