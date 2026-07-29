# ai 模块知识库

provider-neutral 模型调用协议层。零 Jcode 内部依赖；无 provider SDK、无 Agent、无 Session、无 coding 工具、无 UI。

## WHERE TO LOOK

| 任务 | 位置 |
|------|------|
| 接入 model provider | `client/ModelClient.java`（SPI，`generate()` 返回 `CompletionStage`） |
| 模型调用请求 | `client/ModelRequest.java`（model + systemPrompt + messages + tools） |
| 模型身份 | `model/ModelRef.java`（provider/api/modelId 三维度轻量引用）/ `Model.java`（含 name） |
| 标准 LLM 消息 | `message/Message.java`（sealed：User/Assistant/ToolResultMessage） |
| 消息内容块 | `message/Content.java`（sealed：Text/Thinking/ToolCall） |
| 停止原因 | `message/StopReason.java`（enum，`isTerminalFailure()` = ERROR/ABORTED） |
| 可声明工具 | `tool/ToolSpec.java`（name/description/parameters JsonNode，给模型看） |
| 只读取消 | `concurrent/CancellationSignal.java`（isCancelled/throwIfCancelled） |
| token 元数据 | `message/Usage.java`（Wave 1 才接入 Assistant） |

## CONVENTIONS

- `Message.Assistant.errorMessage` 仅允许在 `stopReason.isTerminalFailure()` 时非空（compact constructor 校验）。
- `ModelClient` adapter 不得同步抛异常；provider/网络失败编码进 failed `CompletionStage`。
- `CancellationSignal` 是只读协议，住在 `ai`（最低共享层），因为 Java 无 `AbortSignal` 等价物；model adapter 和工具都读同一个信号。创建/触发取消的 `CancellationSource` 在 `agent-core`。
- `ToolSpec` 只携带可声明形状（给模型看）；执行能力在 `agent-core.AgentTool`。
- 所有 list 在 compact constructor 执行 `List.copyOf()`。
- `ModelRequest` 只携带 `ai` 类型，不含 `AgentMessage` 或可执行工具。

## ANTI-PATTERNS

- `ModelClient.generate()` 不得同步抛；adapter 自行聚合流式为最终 `Message.Assistant`，失败编码进 failed stage。
- 模块边界与不含类型约束见根 AGENTS.md ANTI-PATTERNS（模块边界节）。
