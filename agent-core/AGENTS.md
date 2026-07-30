# agent-core 模块知识库

通用 Agent Runtime。唯一公开运行入口是 `Agent`；内部循环/状态保持 package-private。仅依赖 `ai`。

## WHERE TO LOOK

| 任务 | 位置 |
|------|------|
| 理解 agent loop 15 步序列 | `AgentLoop.java` → `runLoop()`（注释标 step 1-15） |
| 理解流式消费 | `AgentLoop.java` → `consumeStream()`（Start → deltas → Done/Error，emit MessageStarted/MessageUpdated） |
| 理解工具三阶段管道 | `AgentLoop.java` → `executeOneToolCallTyped()`（prepare → execute → finalize） |
| 公开 API | `Agent.java`：`prompt()`/`continueRun()`/`steer()`/`followUp()`/`abort()`/`context()`/`state()`/`close()` |
| 实时状态快照 | `AgentState.java`（public record：streaming/streamingMessage/pendingToolCalls/errorMessage） |
| 事件归约器 | `Agent.java` → `reduceState()`（先归约 AgentState，再委托用户 sink） |
| 构造 Agent | `AgentConfig.java`（record，含 beforeToolCall/afterToolCall 默认 noop） |
| transcript 状态 | `AgentContext.java`（不可变，`append` 返回新实例） |
| 一次 run 可变状态 | `LoopState.java`（pkg-private，核心层唯一可变消息集合） |
| 工具契约 | `tool/AgentTool.java`（`prepareArguments` + `spec` + `execute(ToolUpdateSink)` + `executionMode`） |
| 工具进度更新 | `tool/ToolUpdateSink.java`（`update(Content)` → `CompletionStage<Void>`；settle 后忽略迟到 update） |
| 工具 prepare hook | `tool/BeforeToolCall.java`（sealed Decision: Proceed/Block） |
| 工具 finalize hook | `tool/AfterToolCall.java`（可 patch `ToolExecutionResult`） |
| 工具执行模式 | `tool/ToolExecutionMode.java`（PARALLEL 默认，SEQUENTIAL 强制整批） |
| Schema 校验 | `ToolSchemaValidator.java`（pkg-private，最小 type/required/properties） |
| 生命周期事件 | `event/AgentEvent.java`（sealed，10 个 record：AgentStarted/TurnStarted/MessageStarted/MessageUpdated/MessageCompleted/ToolStarted/ToolUpdate/ToolCompleted/TurnCompleted/AgentCompleted） |
| steering/follow-up | `queue/PendingMessageQueue.java`（ConcurrentLinkedQueue，`QueueMode` volatile） |
| 取消 | `concurrent/CancellationSource.java`（`signal()` 返回私有 `SignalView`，不可 cast 回） |
| 消息桥接 | `message/StandardAgentMessage.java`（包装 `ai.Message` 进开放 `AgentMessage`） |
| 工具管道契约测试 | `src/test/.../ToolPipelineTest`（schema/before/after/update/settle） |
| 流式事件契约测试 | `src/test/.../StreamingEventTest`（text/thinking/toolcall start-delta-end 顺序、状态归约、provider 错误） |

## CONVENTIONS

- `AgentLoop`/`AgentLoopConfig`/`LoopState`/`ToolSchemaValidator` 保持 package-private；唯一公开运行入口是 `Agent`。
- `AgentContext` 不可变；可变状态只在 `LoopState`（核心层唯一例外）。`AgentState` 是公开不可变快照，由 `Agent` 的归约器在每次事件时原子替换。
- `Agent` 持有 `Executors.newVirtualThreadPerTaskExecutor()`，实现 `AutoCloseable`；公开 API 返回 `CompletionStage`，内部 loop 在虚拟线程上顺序控制流。
- 每个 `Agent` 同时最多一个 active run（`AtomicReference<ActiveRun>` CAS 保护）；`close()` 协作式 abort + drain executor。
- 事件归约：`Agent` 内部包装用户 `AgentEventSink` 为归约 sink。归约器先 `reduceState(event)` 更新 `volatile AgentState`，再委托用户 sink。用户 sink 看到事件时状态已完成归约。`AgentCompleted` 事件的 sink 完成前 loop 不返回（`emit().join()` 保证）。
- 流式消费：`AgentLoop.consumeStream()` 消费 `AssistantMessageStream`，在 `Start` 事件发 `MessageStarted`，在 delta 事件发 `MessageUpdated`，在 `Done`/`Error` 返回最终 `Message.Assistant`。partial 不进入 context；final 才 append。
- 事件发射 `emit()` 返回 `CompletionStage`，loop `.join()` 等待——慢 sink 阻塞 run。
- 工具三阶段管道（prepare/execute/finalize）由 `AgentLoop.executeOneToolCallTyped()` 统一保证顺序。
  - prepare：`prepareArguments` → `ToolSchemaValidator.validate` → `BeforeToolCall` → `ObjectMapper.treeToValue`
  - execute：`tool.execute(id, args, ToolUpdateSink, cancellation)` → 异常转 error result → settle sink
  - finalize：`AfterToolCall.afterToolCall` → 生成 `Message.ToolResultMessage`
- `terminate` 仅存在于运行时 `ToolExecutionResult`；`Message.ToolResultMessage` 不含 terminate 字段。
- 工具参数用共享 `ObjectMapper.treeToValue(arguments, argumentType())`；转换失败转 error result。
- 默认值：`toolExecution=PARALLEL`、`beforeToolCall=noop()`、`afterToolCall=noop()`、`eventSink=noop()`、`steeringMode/followUpMode=ONE_AT_A_TIME`。
- 消息事件序列：用户/toolResult 消息发 `MessageStarted → MessageCompleted`；assistant 消息发 `MessageStarted → MessageUpdated... → MessageCompleted`。

## ANTI-PATTERNS

- executor 已关闭时（`RejectedExecutionException`）不伪造 ABORTED assistant——loop 从未运行，context 不变。
- `continueRun()` 在最后消息是 assistant 时失败（要求用 `prompt()`）。
- 成功时先赋 context 再清 activeRun ref（Oracle F 顺序），保证并发可见性。
- Schema 失败/Before hook 阻止 → error result，不执行工具。
- After hook 失败 → 保留原 result，不抛进 loop。
- `ToolUpdateSink` settle 后的迟到 update 被静默丢弃。
- `terminate` 不进入标准 LLM transcript。
- 通用工具/取消/并行排序约束见根 AGENTS.md ANTI-PATTERNS。
