# agent-core 模块知识库

通用 Agent Runtime。唯一公开运行入口是 `Agent`；内部循环/状态保持 package-private。仅依赖 `ai`。

## WHERE TO LOOK

| 任务 | 位置 |
|------|------|
| 理解 agent loop 15 步序列 | `AgentLoop.java` → `runLoop()` 私有方法（注释标 step 1-15） |
| 公开 API | `Agent.java`：`prompt()`/`continueRun()`/`steer()`/`followUp()`/`abort()`/`context()`/`close()` |
| 构造 Agent | `AgentConfig.java`（record，compact constructor 校验+默认值） |
| transcript 状态 | `AgentContext.java`（不可变，`append` 返回新实例） |
| 一次 run 可变状态 | `LoopState.java`（pkg-private，核心层唯一可变消息集合） |
| 工具契约 | `tool/AgentTool.java`（`spec()` 默认 minimal，`execute()` 返回 stage） |
| 工具执行模式 | `tool/ToolExecutionMode.java`（PARALLEL 默认，SEQUENTIAL 强制整批） |
| 生命周期事件 | `event/AgentEvent.java`（sealed，7 个 record） |
| steering/follow-up | `queue/PendingMessageQueue.java`（ConcurrentLinkedQueue，`QueueMode` volatile） |
| 取消 | `concurrent/CancellationSource.java`（`signal()` 返回私有 `SignalView`，不可 cast 回） |
| 消息桥接 | `message/StandardAgentMessage.java`（包装 `ai.Message` 进开放 `AgentMessage`） |

## CONVENTIONS

- `AgentLoop`/`AgentLoopConfig`/`LoopState` 保持 package-private；唯一公开运行入口是 `Agent`。
- `AgentContext` 不可变；可变状态只在 `LoopState`（核心层唯一例外）。
- `Agent` 持有 `Executors.newVirtualThreadPerTaskExecutor()`，实现 `AutoCloseable`；公开 API 返回 `CompletionStage`，内部 loop 在虚拟线程上顺序控制流。
- 每个 `Agent` 同时最多一个 active run（`AtomicReference<ActiveRun>` CAS 保护）；`close()` 协作式 abort + drain executor。
- 事件发射 `emit()` 返回 `CompletionStage`，loop `.join()` 等待——慢 sink 阻塞 run。
- 工具参数用共享 `ObjectMapper.treeToValue(arguments, argumentType())`；转换失败转 error result。
- 默认值：`toolExecution=PARALLEL`、`eventSink=noop()`、`steeringMode/followUpMode=ONE_AT_A_TIME`。

## ANTI-PATTERNS

- executor 已关闭时（`RejectedExecutionException`）不伪造 ABORTED assistant——loop 从未运行，context 不变。
- `continueRun()` 在最后消息是 assistant 时失败（要求用 `prompt()`）。
- 成功时先赋 context 再清 activeRun ref（Oracle F 顺序），保证并发可见性。
- 通用工具/取消/并行排序约束见根 AGENTS.md ANTI-PATTERNS。
