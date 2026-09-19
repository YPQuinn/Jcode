# agent-core 模块知识库

通用 Agent Runtime。唯一公开运行入口是 `Agent`；内部循环/状态保持 package-private。仅依赖 `ai`。

## WHERE TO LOOK

| 任务 | 位置 |
|------|------|
| 理解 agent loop 主干编排 | `AgentLoop.java` → `runLoop()`（线性编排子方法：prepareTurn/invokeModelSafely/dispatchToolCalls/recordOutcomes 等；取消边界 1-4 注释保留） |
| 理解流式消费 | `AgentLoop.java` → `consumeStream()`（Start → deltas → Done/Error，emit MessageStarted/MessageUpdated） |
| 理解工具三阶段管道 | `ToolCallExecutor.java` → `prepareCall()`/`executeAndFinalize()`（prepare → execute → finalize；并行批次双排序） |
| 工具执行编排 | `ToolCallExecutor.java`（pkg-private，三阶段管道 + 顺序/并行分发 + `ExecutorCompletionService` 完成序投递 + `LoopToolUpdateSink` close-and-drain + `ToolOutcome`） |
| 公开 API | `Agent.java`：`prompt()`/`continueRun()`/`steer()`/`followUp()`/`abort()`/`context()`/`state()`/`close()` |
| 实时状态快照 | `AgentState.java`（public record：streaming/streamingMessage/pendingToolCalls/errorMessage） |
| 事件归约器 | `Agent.java` → `reduceState()`（`AtomicReference<AgentState>` + CAS；先归约 AgentState，再委托用户 sink） |
| 构造 Agent | `AgentConfig.java`（record，含 beforeToolCall/afterToolCall 默认 noop，以及固定 `ModelRequestOptions`） |
| transcript 状态 | `AgentContext.java`（不可变，`append` 返回新实例） |
| 一次 run 可变状态 | `LoopState.java`（pkg-private，核心层唯一可变消息集合） |
| 工具契约 | `tool/AgentTool.java`（`prepareArguments` + `constraint`/`spec` + `execute(ToolUpdateSink)` + `executionMode`；结果 content 可为 `Text`/`Image`） |
| 工具进度更新 | `tool/ToolUpdateSink.java`（`update(Content)` → `CompletionStage<Void>`；settle 后忽略迟到 update） |
| 工具 prepare hook | `tool/BeforeToolCall.java`（sealed Decision: Proceed/Block） |
| 工具 finalize hook | `tool/AfterToolCall.java`（可 patch `ToolExecutionResult`） |
| 工具执行模式 | `tool/ToolExecutionMode.java`（PARALLEL 默认，SEQUENTIAL 强制整批） |
| Schema 校验 | `ToolSchemaValidator.java`（pkg-private，最小 type/required/properties） |
| 事件投递 | `RunEventEmitter.java`（pkg-private per-run adapter：`void emit(AgentEvent)` 等待 sink 的 `CompletionStage` 完成后再返回；loop 和 `LoopToolUpdateSink` 唯一的事件投递出口） |
| 生命周期事件 | `event/AgentEvent.java`（sealed，10 个 record：AgentStarted/TurnStarted/MessageStarted/MessageUpdated/MessageCompleted/ToolStarted/ToolUpdate/ToolCompleted/TurnCompleted/AgentCompleted） |
| steering/follow-up | `queue/PendingMessageQueue.java`（ConcurrentLinkedQueue，`QueueMode` volatile） |
| 取消 | `concurrent/CancellationSource.java`（`signal()` 返回私有 `SignalView`，不可 cast 回；`onCancellation` 在 `cancel()` 线程同步通知，listener 失败隔离） |
| 消息桥接 | `message/StandardAgentMessage.java`（包装 `ai.Message` 进开放 `AgentMessage`） |
| Context 投影 | `message/ContextTransformer.java`（异步 transform seam，默认 identity）→ `message/MessageProjector.java`（同步 project seam，默认 standard） |
| turn 间更新 | `turn/PrepareNextTurn.java`（默认 noop）→ `turn/NextTurnUpdate.java`（Optional 补丁，替换 context/model/thinking） |
| 优雅停止 | `turn/ShouldStopAfterTurn.java`（Decision CONTINUE/STOP，默认 never） |
| turn 快照 | `turn/TurnContext.java`（assistant + 源顺序 toolResults + context + newMessages） |
| 工具管道契约测试 | `src/test/.../ToolPipelineTest`（schema/before/after/update/settle/ToolUpdate 阻塞语义）；`ParallelToolOrderingTest`（并行双排序/并发结算/失败状态机/取消） |
| 流式事件契约测试 | `src/test/.../StreamingEventTest`（text/thinking/toolcall start-delta-end 顺序、状态归约、provider 错误、start/update sink 三种失败模式、异常取消清理与再次运行） |
| Context 投影契约测试 | `src/test/.../ContextProjectionTest`（transform-before-project、每轮重复、transcript 隔离、source/replayState 原样进入 ModelRequest、失败归一、取消） |

## CONVENTIONS

- `AgentLoop`/`ToolCallExecutor`/`AgentLoopConfig`/`LoopState`/`ToolSchemaValidator`/`RunEventEmitter` 保持 package-private；唯一公开运行入口是 `Agent`。
- `pom.xml` 声明本模块自己的 `enforce-module-boundaries` execution：仅允许依赖 `ai`，禁止 `ai-providers`、`coding-agent` 及更高产品模块；根 POM 只管理插件版本。
- `AgentContext` 不可变；可变状态只在 `LoopState`（核心层唯一例外）。`AgentState` 是公开不可变快照，由 `Agent` 的归约器在每次事件时原子替换。
- `Agent` 持有 `Executors.newVirtualThreadPerTaskExecutor()`，实现 `AutoCloseable`；公开 API 返回 `CompletionStage`，内部 loop 在虚拟线程上顺序控制流。
- 每个 `Agent` 同时最多一个 active run（`AtomicReference<ActiveRun>` CAS 保护）；`close()` 协作式 abort + drain executor。
- 事件归约：`Agent` 内部包装用户 `AgentEventSink` 为归约 sink。归约器先 `reduceState(event)` 以 `AtomicReference<AgentState>` CAS 更新 `AgentState`，再委托用户 sink。用户 sink 看到事件时状态已完成归约。`AgentCompleted` 事件的 sink 完成前 loop 不返回（`emit().join()` 保证）。归约原子化不锁用户 sink；并行工具下 `ToolUpdate` 与生命周期事件可并发归约。
- 流式消费：`AgentLoop.consumeStream()` 消费 `AssistantMessageStream`，在 `Start` 事件发 `MessageStarted`，在 delta 事件发 `MessageUpdated`，在 `Done`/`Error` 返回最终 `Message.Assistant`。partial 不进入 context；final 才 append。
- 事件投递：`AgentLoopConfig` 持有 `RunEventEmitter`（持有一个 `AgentEventSink`）。loop 和 `ToolCallExecutor` 内的 `LoopToolUpdateSink` 通过 `RunEventEmitter.emit(event)` 投递事件——该方法 `delegate.emit(event).toCompletableFuture().join()`，等待 sink 的 `CompletionStage` 完成后才返回。慢 sink 阻塞 run。异常不捕获不包装，沿 `.join()` 传播；`invokeModelSafely` 的 RuntimeException 归一边界仅覆盖模型调用，不包含流式事件投递。sink 同步抛出、failed stage、null stage 均使 run 异常退出，不提交 transcript、不合成 model error。`Agent` 异常退出先取消在途 provider，再重置 state/activeRun 并异常完成 future；取消清理异常以 suppressed 保留，不覆盖原始失败。
- 工具三阶段管道（prepare/execute/finalize）：并行批次中 `prepareCall()` 与 `ToolStarted` 按 tool call 源顺序串行；immediate failure 在 prepare pass 当场发 `ToolCompleted`；`executeAndFinalize()` 并行，`ToolCompleted` 由 loop 线程按实际完成顺序投递（`ExecutorCompletionService`）；tool-result 消息、context、`TurnCompleted.toolResults` 按源顺序写回。
  - prepare：`prepareArguments` → `ToolSchemaValidator.validate` → `BeforeToolCall` → `ObjectMapper.treeToValue`
  - execute：`tool.execute(id, args, ToolUpdateSink, cancellation)` → 异常转 error result → `LoopToolUpdateSink.settle()`（关闭接纳并 drain 已接纳 update；delivery failure 在此重抛，不归一为 tool error）
  - finalize：`AfterToolCall.afterToolCall` → 生成 `Message.ToolResultMessage`
- 并行批次失败状态机：事件投递失败/executor 拒绝/中断等基础设施失败不归一为 tool result；出现首个失败后不再发后续 `ToolCompleted`，已提交任务全量 drain 后传播首个异常，未提交 entry 不执行。取消（非基础设施失败）时已 started/prepared 的 entry 仍提交并结算，后续 call 不启动。
- `terminate` 仅存在于运行时 `ToolExecutionResult`；`Message.ToolResultMessage` 不含 terminate 字段。
- 工具参数用共享 `ObjectMapper.treeToValue(arguments, argumentType())`；转换失败转 error result。
- 默认值：`toolExecution=PARALLEL`、`beforeToolCall=noop()`、`afterToolCall=noop()`、`contextTransformer=identity()`、`messageProjector=standard()`、`events=RunEventEmitter.noop()`、`steeringMode/followUpMode=ONE_AT_A_TIME`、`thinkingLevel=PROVIDER_DEFAULT`、`prepareNextTurn=noop()`、`shouldStopAfterTurn=never()`、`modelRequestOptions=defaults()`。
- 消息事件序列：用户/toolResult 消息发 `MessageStarted → MessageCompleted`；assistant 消息发 `MessageStarted → MessageUpdated... → MessageCompleted`。
- Context 投影：每次模型调用前在 `AgentLoop.invokeModelSafely()` 中依次执行 `ContextTransformer`（异步，`.toCompletableFuture().join()` 等待）→ `List.copyOf` 校验 → 取消检查 → `MessageProjector`（同步）→ `List.copyOf` 校验 → 取消检查 → `ModelRequest`。投影输出是 request-only 局部变量，不写回 `LoopState`/`AgentContext`/`LoopResult.newMessages`/事件。transformer 裁剪/注入的消息只影响当前请求。默认 projector/`StandardAgentMessage` 原样传递 `sourceModel` 与 `ModelReplayState`，不解析。回调失败（同步抛异常、exceptional stage、null 输出）归一为 terminal `ERROR`（取消时 `ABORTED`）assistant，model 不被调用，run future 正常完成。`ModelRequestOptions` 从 `AgentLoopConfig` 原样写入每次 `ModelRequest`，本批不可被 `PrepareNextTurn` 替换。
- 下一 Turn 控制：严格顺序为 `TurnCompleted` → `PrepareNextTurn`（pre-update 快照）→ 校验 → 取消检查 → 原子应用 update 到 `LoopState` → `ShouldStopAfterTurn`（post-update 快照）→ 取消检查 → steering drain → follow-up drain。`NextTurnUpdate` 的 `Optional.empty()` 表示保持；thinking 三态为 KEEP / `PROVIDER_DEFAULT`（重置）/ 绝对级别。`STOP` 不 drain 队列、不改 assistant stop reason、不强制额外 model call；cancellation 优先于 STOP。`ModelRequest` 从 `LoopState` 读 model/thinkingLevel，从 config 读固定 request options。hook 失败（同步抛/exceptional/null stage/result/decision）合成终止 turn：`TurnStarted → MessageStarted → MessageCompleted → TurnCompleted → AgentCompleted`，run future 正常完成；sink 异常仍传播。terminal model ERROR/ABORTED 跳过 turn hooks。

## ANTI-PATTERNS

- executor 已关闭时（`RejectedExecutionException`）不伪造 ABORTED assistant——loop 从未运行，context 不变。
- `continueRun()` 在最后消息是 assistant 时失败（要求用 `prompt()`）。
- 成功时先赋 context 再清 activeRun ref（Oracle F 顺序），保证并发可见性。
- Schema 失败/Before hook 阻止 → error result，不执行工具。
- Before hook 同步抛/failed stage/null stage/null decision → immediate failure；execute 同步抛/failed stage/null stage/null result → error result；After hook 同步抛/failed stage/null stage/null result → 保留原 result。
- `ToolUpdateSink` settle 后的迟到 update 被静默丢弃。
- `terminate` 不进入标准 LLM transcript。
- 不解析 `ModelReplayState`，不按 provider 分支处理 `Assistant.sourceModel` 或 `ResponseMetadata`。旧 Assistant 构造器仍默认 empty metadata；模型返回的 metadata 原样进入 context。
- 通用工具/取消/并行排序约束见根 AGENTS.md ANTI-PATTERNS。
