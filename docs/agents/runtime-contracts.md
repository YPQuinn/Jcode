# 运行时正确性契约

修改流协议、取消、agent loop、hook、队列、事件或工具执行时读取本文。具体类型的详细约束见对应模块的 `AGENTS.md`。

## 模型流

- `ModelClient.stream()` 必须返回 `AssistantMessageStream`，不得同步抛出 provider 或网络失败。
- 成功、provider 失败、网络失败和取消都通过唯一一个携带最终 `Message.Assistant` 的 `Done` 或 `Error` 事件终止。
- `AgentLoop.consumeStream()` 在 `Start` 时发出 `MessageStarted`，在 delta 时发出 `MessageUpdated`，在 `Done` 或 `Error` 时返回最终消息。
- Partial assistant 不得进入持久 context。

## Agent 生命周期与事件

- 一个 `Agent` 同时最多接纳一个 active run；并发 `prompt()` 或 `continueRun()` 必须 fail fast。
- 归约 event sink 先更新 `AgentState`，再调用用户 sink，确保用户处理事件时看到已归约状态。
- `AgentEventSink.emit()` 返回的 stage 具有 backpressure，必须等待；`RunEventEmitter` 是唯一等待点。
- User 与 tool-result 消息发出 `MessageStarted → MessageCompleted`；assistant 消息发出 `MessageStarted → MessageUpdated… → MessageCompleted`。
- 流式事件投递失败时，取消在途 provider、清理 run 状态，并让 run future 异常完成；不得将 sink failure 改写为模型失败。

## Context 投影与 Turn hook

每次模型调用前，先异步且感知取消地运行 `ContextTransformer`，再同步运行 `MessageProjector`。输出只是本次请求视图，不得修改 transcript，也不得进入 context、`LoopResult.newMessages` 或事件。

每个正常的 `TurnCompleted` 之后按顺序执行：

1. 运行 `PrepareNextTurn`。
2. 原子应用 context/model/thinking 更新。
3. 运行 `ShouldStopAfterTurn`。
4. Drain steering 消息。
5. Drain follow-up 消息。

优雅停止不得重写 stop reason，也不得 drain 已排队的 steering/follow-up。Terminal model failure 跳过这些 hook。Hook 失败归一为 terminal `ERROR` 或 `ABORTED` assistant，而不是使 run 异常完成。

## 取消

- `CancellationSignal` 是只读协议，不得 cast 为 `CancellationSource` 后触发取消。
- `cancel()` 必须幂等；每个 listener 最多执行一次，单个 listener 失败不得阻止其他 listener 或使 `cancel()` 失败。
- Cancellation listener 必须非阻塞。
- 不使用阻塞 sleep 轮询，也不使用长期共享的无界平台线程池。

## 工具管道

每个工具调用都经过 `ToolCallExecutor` 所有的统一管道：

1. **Prepare：**准备参数 → schema 校验 → `BeforeToolCall` → `treeToValue` 转换。
2. **Execute：**使用 cancellation signal 与 update sink 调用工具；工具异常归一为 error result；settle sink。
3. **Finalize：**运行 `AfterToolCall`，再创建 `Message.ToolResultMessage`。

正确性规则：

- 未知工具、schema 失败、参数转换失败、hook 阻止和工具失败都转为 error `ToolExecutionResult`，不得抛入 loop。
- 事件投递失败、executor 拒绝或中断等基础设施失败不是工具失败；必须在 drain 已提交工作后传播。
- `AfterToolCall` 失败时保留原工具结果。
- `ToolUpdateSink` settle 后到达的 update 被静默丢弃。
- `LENGTH` 响应中的所有 tool call 都转为 failure，禁止执行。
- 任一调用声明 `SEQUENTIAL` 时，整批按源顺序执行。
- 并行批次按源顺序 prepare 并发出 `ToolStarted`，按完成顺序发出 `ToolCompleted`，然后恢复源顺序写入 transcript、context 与 `TurnCompleted.toolResults`。
- Finalize 前必须投递所有在 sink settle 前已接纳的 update。
- `terminate` 只存在于运行时，不得进入标准模型 transcript。
