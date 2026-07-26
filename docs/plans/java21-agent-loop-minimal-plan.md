# Java 21 最小 Agent Loop 实现计划

## 目标

在现有 `agent-core` Maven 模块内实现一个可独立运行和测试的 agent loop，完成以下闭环：

```text
Agent.prompt
  -> 调用模型
  -> 识别 tool call
  -> 执行工具并写回 tool result
  -> 再次调用模型
  -> 消费 follow-up
  -> 返回最终上下文
```

首批实现只提供 Java 库入口，不增加 CLI、HTTP、Spring、持久化、provider SDK、重试、上下文压缩或插件系统。

## 已确定的技术约束

- Java 21，使用 `record`、`sealed interface`、模式匹配和虚拟线程。
- 继续使用现有 Maven 聚合结构，只修改 `agent-core`。
- 根包固定为 `site.pplee.jcode.agentcore`。
- 公开模型保持不可变；一次运行中的可变状态仅存在于 package-private 的 `LoopState`。
- `Agent` 是业务调用入口；调用方不直接拼装或调用内部循环。
- `Agent` 实现 `AutoCloseable` 并持有一个 `Executors.newVirtualThreadPerTaskExecutor()`；公开 API 返回 `CompletionStage`，内部 loop 在虚拟线程上使用顺序控制流。
- 模型适配器负责把 provider 流聚合为最终 `AssistantMessage`；核心循环只消费最终消息，同时通过事件接口转发增量。
- 工具参数边界使用 Jackson `JsonNode`，具体工具将其转换为自己的强类型参数。
- 多工具默认并行执行；任一工具声明顺序执行时，整批按原顺序执行。
- steering 在当前 assistant turn 后注入，follow-up 仅在 agent 原本准备结束时注入。
- 每个 `Agent` 同时只允许一个 active run；重复调用 `prompt()` 或 `continueRun()` 必须失败。

## Maven 调整

### 根 `pom.xml`

增加集中版本属性：

- `jackson.version`
- `junit.version`
- `maven.surefire.version`

### `agent-core/pom.xml`

增加：

- `com.fasterxml.jackson.core:jackson-databind`，编译依赖。
- `org.junit.jupiter:junit-jupiter`，test scope。
- `maven-surefire-plugin`，启用 JUnit 5。

所有外部依赖版本由根 POM 属性固定，不使用版本范围。

## 最小包结构

```text
agent-core/src/main/java/site/pplee/jcode/agentcore/
├── Agent.java
├── AgentConfig.java
├── AgentLoop.java
├── AgentLoopConfig.java
├── LoopState.java
├── concurrent/
│   ├── CancellationSource.java
│   └── CancellationToken.java
├── event/
│   ├── AgentEvent.java
│   └── AgentEventSink.java
├── model/
│   ├── AgentContext.java
│   ├── AgentMessage.java
│   ├── Content.java
│   ├── LoopResult.java
│   ├── ModelRef.java
│   ├── StopReason.java
│   ├── ToolExecutionMode.java
│   └── ToolResult.java
├── queue/
│   ├── PendingMessageQueue.java
│   ├── PendingMessageSource.java
│   └── QueueMode.java
└── spi/
    ├── AgentTool.java
    ├── LlmClient.java
    ├── LlmEventSink.java
    └── LlmRequest.java

agent-core/src/test/java/site/pplee/jcode/agentcore/
├── AgentLoopTest.java
├── AgentTest.java
└── support/
    ├── RecordingEventSink.java
    ├── ScriptedLlmClient.java
    └── TestTools.java
```

## 首批领域类型

### `model/Content.java`

定义 sealed 消息内容模型，并将实现作为嵌套 record，减少首批文件数量：

```java
public sealed interface Content {
    record Text(String text) implements Content {}
    record Thinking(String text) implements Content {}
    record ToolCall(String id, String name, JsonNode arguments) implements Content {}
}
```

构造时要求 `text`、`id`、`name`、`arguments` 非空；核心层不解析工具参数。

### `model/AgentMessage.java`

定义三种消息：

```java
public sealed interface AgentMessage {
    record User(List<Content> content, Instant timestamp) implements AgentMessage {}
    record Assistant(
        List<Content> content,
        StopReason stopReason,
        String errorMessage,
        Instant timestamp
    ) implements AgentMessage {}
    record ToolResult(
        String toolCallId,
        String toolName,
        List<Content> content,
        boolean error,
        boolean terminate,
        Instant timestamp
    ) implements AgentMessage {}
}
```

所有列表在 compact constructor 中执行 `List.copyOf()`。`errorMessage` 仅允许在 `ERROR` 或 `ABORTED` 时非空。

### `model/StopReason.java`

使用 Java enum：

```text
STOP, TOOL_CALL, LENGTH, ERROR, ABORTED
```

提供 `isTerminalFailure()`，仅 `ERROR` 和 `ABORTED` 返回 true。

### `model/ModelRef.java`

字段：

- `String provider`
- `String modelId`

compact constructor 拒绝 blank 值。

### `model/AgentContext.java`

字段：

- `String systemPrompt`
- `List<AgentMessage> messages`
- `List<AgentTool<?>> tools`

提供纯函数：

- `append(AgentMessage)`
- `appendAll(List<? extends AgentMessage>)`

每次返回新 `AgentContext`，不暴露可变列表。

### `model/ToolResult.java`

工具执行的内部返回值：

- `List<Content> content`
- `boolean error`
- `boolean terminate`

提供静态工厂 `success(...)` 和 `failure(String)`。

### `model/LoopResult.java`

字段：

- `AgentContext context`：本次执行后的完整上下文。
- `List<AgentMessage> newMessages`：本次 run 新增的消息。

### `model/ToolExecutionMode.java`

值：`SEQUENTIAL`、`PARALLEL`。

## 首批 SPI

### `spi/LlmRequest.java`

字段：

- `ModelRef model`
- `String systemPrompt`
- `List<AgentMessage> messages`
- `List<AgentTool<?>> tools`

它是 core 到 provider adapter 的稳定边界。

### `spi/LlmClient.java`

```java
public interface LlmClient {
    CompletionStage<AgentMessage.Assistant> generate(
        LlmRequest request,
        CancellationToken cancellation,
        LlmEventSink events
    );
}
```

要求 provider adapter：

- 自行聚合流式响应。
- 通过 `LlmEventSink` 上报文本、thinking 和 tool-call 增量。
- 正常完成时返回最终且完整的 `Assistant`。
- provider 异常通过 failed `CompletionStage` 返回，由 `AgentLoop` 转换为 `ERROR` assistant 消息并正常结束本次 run。

### `spi/LlmEventSink.java`

首批只定义一个方法：

```java
void onDelta(Content delta);
```

不在首批设计 provider 特有事件类型。

### `spi/AgentTool.java`

```java
public interface AgentTool<A> {
    String name();
    Class<A> argumentType();
    default ToolExecutionMode executionMode() { return PARALLEL; }
    CompletionStage<ToolResult> execute(
        String toolCallId,
        A arguments,
        CancellationToken cancellation
    );
}
```

`AgentLoop` 使用一个共享 `ObjectMapper` 执行 `treeToValue(arguments, argumentType())`。转换失败产生 error tool result，不抛出循环。

## 事件模型

### `event/AgentEvent.java`

使用一个 sealed interface 和嵌套 record，首批包含：

- `AgentStarted`
- `TurnStarted`
- `MessageCompleted(AgentMessage message)`
- `ToolStarted(Content.ToolCall call)`
- `ToolCompleted(AgentMessage.ToolResult result)`
- `TurnCompleted(AgentMessage.Assistant assistant, List<AgentMessage.ToolResult> toolResults)`
- `AgentCompleted(LoopResult result)`

首批不保存 partial assistant message；模型增量走 `LlmEventSink`。

### `event/AgentEventSink.java`

```java
@FunctionalInterface
public interface AgentEventSink {
    CompletionStage<Void> emit(AgentEvent event);
}
```

提供 `AgentEventSink.noop()`。循环必须等待每个事件完成，保证状态观察顺序确定。

## 队列与取消

### `queue/PendingMessageSource.java`

```java
public interface PendingMessageSource {
    List<AgentMessage> drain();
}
```

### `queue/QueueMode.java`

枚举，控制单次 `drain()` 取出多少消息：

- `ALL`：取出当前队列全部消息。
- `ONE_AT_A_TIME`：仅取最旧的一条，其余留待下一次 drain。

对齐 pi 的 `QueueMode`（types.ts:50）。默认值在 `AgentConfig` 提供（`ONE_AT_A_TIME`，对齐 pi 的 `?? "one-at-a-time"`，agent.ts:224-225）。

### `queue/PendingMessageQueue.java`

- 基于 `ConcurrentLinkedQueue<AgentMessage>`，构造时接收 `QueueMode`。
- `mode()` 可读，`mode(QueueMode)` 可运行时改（字段 `volatile`，跨虚拟线程可见）。
- `enqueue()` 拒绝 null。
- `drain()`：`ALL` 取全部并返回不可变快照；`ONE_AT_A_TIME` 取最旧一条（空则空 list）。返回的 list 始终不可变。
- 不阻塞、不轮询、不使用 sleep。
- steering 和 follow-up 各持有独立实例，各自带独立 mode。

### `concurrent/CancellationToken.java`

公开：

- `boolean isCancelled()`
- `void throwIfCancelled()`

### `concurrent/CancellationSource.java`

- 内部使用 `AtomicBoolean`。
- **不直接实现 `CancellationToken`**；`token()` 返回私有内部 `TokenView`（实现 `CancellationToken`）的稳定引用，消费者无法 cast 回 `CancellationSource` 调 `cancel()`。
- 自身公开 `isCancelled()`、`throwIfCancelled()`、`cancel()`、`token()` 供持有者使用。
- `cancel()` 幂等（`AtomicBoolean.set(true)` 天然幂等）。
- `throwIfCancelled()` 抛 `java.util.concurrent.CancellationException`。

工具和模型 adapter 必须在自己的可中断边界检查 token；核心循环在模型调用前、工具批次前和下一轮开始前检查。

## 核心运行时

### `AgentLoopConfig.java`

字段：

- `ModelRef model`
- `LlmClient llmClient`
- `ObjectMapper objectMapper`
- `ToolExecutionMode toolExecution`
- `PendingMessageSource steeringMessages`
- `PendingMessageSource followUpMessages`
- `AgentEventSink eventSink`
- `LlmEventSink llmEventSink`

compact constructor 将可选 sink/source 替换为 noop/empty 实现，但 `model`、`llmClient`、`objectMapper` 必填。

### `LoopState.java`

package-private，仅由 `AgentLoop` 使用：

- 当前 `AgentContext context`
- 当前 run 的 `List<AgentMessage> newMessages`
- `append()` 同时更新两者
- `result()` 返回不可变 `LoopResult`

它是核心层唯一允许持有可变消息集合的类型。

### `AgentLoop.java`

定义为 package-private，构造函数接收 `ExecutorService`，该实例与 `Agent` 自有执行器相同。提供两个同步方法，它们只会被 `Agent` 调度到该虚拟线程执行器上：

```java
LoopResult runPrompt(
    List<AgentMessage> prompts,
    AgentContext context,
    AgentLoopConfig config,
    CancellationToken cancellation
)

LoopResult continueRun(
    AgentContext context,
    AgentLoopConfig config,
    CancellationToken cancellation
)
```

两者只负责初始化事件和 `LoopState`，共同进入 private `runLoop()`。调用模型、事件 sink 和工具返回的 `CompletionStage` 均在当前虚拟线程上等待完成，不使用 common pool，也不构造递归 future 链。

`runLoop()` 必须严格采用以下顺序：

```text
1. drain steering，保存为 pendingMessages
2. 进入外层 follow-up loop
3. 进入内层 tool/steering loop
4. 将 pendingMessages 写入 context 和 newMessages
5. 调 LlmClient.generate()
6. 将最终 assistant 写入 context 和 newMessages
7. ERROR/ABORTED 立即结束
8. 提取 assistant 中全部 ToolCall
9. 顺序或并行执行工具
10. 按原 tool-call 顺序写入 ToolResult 消息
11. 发出 TurnCompleted
12. drain steering；有工具结果或 steering 时继续内层循环
13. 内层结束后 drain follow-up
14. 有 follow-up：设为 pendingMessages，继续外层循环
15. 无 follow-up：发出 AgentCompleted 并返回 LoopResult
```

工具执行规则：

- 找不到工具：生成 failure tool result。
- 参数转换失败：生成 failure tool result。
- 工具 future 失败：生成 failure tool result。
- assistant 的 `StopReason.LENGTH` 且包含 tool call：所有调用都转换为 failure，禁止执行。
- 并行工具任务提交到 `Agent` 自有的 virtual-thread-per-task executor；该 executor 在 `Agent.close()` 时关闭。
- 并行结果必须恢复为原 tool-call 顺序后写入上下文。
- 只有该批所有结果都设置 `terminate=true` 时才终止工具链。

### `AgentConfig.java`

用于构造公开 `Agent`：

- 初始 `AgentContext`
- `ModelRef`
- `LlmClient`
- `ObjectMapper`
- tool execution mode
- event sinks
- steering queue mode（默认 `ONE_AT_A_TIME`）
- follow-up queue mode（默认 `ONE_AT_A_TIME`）

不提供 builder；使用 record compact constructor 校验。

### `Agent.java`

公开 API：

```java
CompletionStage<LoopResult> prompt(AgentMessage.User message)
CompletionStage<LoopResult> continueRun()
void steer(AgentMessage.User message)
void followUp(AgentMessage.User message)
void abort()
AgentContext context()
boolean isRunning()
QueueMode steeringMode()
void steeringMode(QueueMode mode)
QueueMode followUpMode()
void followUpMode(QueueMode mode)
```

职责：

- 持有当前不可变 `AgentContext`。
- 持有 steering/follow-up 队列。
- 实现 `AutoCloseable`，持有并关闭一个 virtual-thread-per-task `ExecutorService`。
- 每次运行创建新的 `CancellationSource`。
- 使用 `AtomicReference` 或锁保证同时只有一个 active run。
- 将 `AgentLoop.runPrompt()` 或 `continueRun()` 提交给自有执行器，并在成功或失败后清理 active 状态。
- 成功后用 `LoopResult.context()` 替换当前 context。
- `continueRun()` 要求上下文最后一条消息不是 assistant，否则失败。

`Agent` 不负责：provider 认证、重试、session 文件、prompt 模板或上下文压缩。

## 公开入口调用链

```text
应用代码
  -> new Agent(AgentConfig)
  -> agent.prompt(User)
  -> AgentLoop.runPrompt(...)
  -> AgentLoop.runLoop(...)
  -> LlmClient.generate(...)
  -> AgentTool.execute(...)
  -> ToolResult 写回 context
  -> LlmClient.generate(...)
  -> drain follow-up
  -> LoopResult
```

`AgentLoop`、`AgentLoopConfig` 和 `LoopState` 均保持 package-private；唯一公开运行入口是 `Agent`。

## 首批测试支撑

### `support/ScriptedLlmClient.java`

- 构造时接收 assistant 响应队列。
- 每次 `generate()` 保存收到的 `LlmRequest` 并弹出一个响应。
- 响应耗尽时返回 failed future。
- 不调用真实网络或模型。

### `support/TestTools.java`

提供：

- `EchoTool`：返回收到的文本。
- `FailingTool`：返回 failed future。
- `TerminatingTool`：返回 `terminate=true`。
- `BlockingTool`：使用 latch 验证并行行为，不使用 sleep。

### `support/RecordingEventSink.java`

按 emit 顺序保存事件，供测试断言生命周期顺序。

## 必须覆盖的首批行为

### `AgentLoopTest`

1. 模型直接返回最终回答时，只调用一次模型并结束。
2. 模型返回一个 tool call 时，执行工具、写入 tool result，再次调用模型。
3. 模型返回多个并行工具时，同时开始执行，但 tool result 按调用顺序写回。
4. 任一工具要求 sequential 时，整批按顺序执行。
5. 工具不存在、参数无效或执行失败时，产生 error tool result，循环仍可让模型处理错误。
6. `LENGTH` 响应中的工具调用不会实际执行。
7. steering 在当前 turn 后、下一次模型调用前注入。
8. follow-up 仅在无剩余工具调用和 steering 时注入，并触发下一次模型调用。
9. 无 follow-up 时仅发出一次 `AgentCompleted`。
10. cancellation 在边界处产生 `ABORTED` 结果且不再启动新工具。

测试在 `@BeforeEach` 创建 virtual-thread-per-task executor，在 `@AfterEach` 关闭，禁止泄漏执行器。

### `AgentTest`

1. `prompt()` 完成后更新 agent context。
2. active run 期间第二次 `prompt()` 或 `continueRun()` 失败。
3. active run 期间调用 `followUp()`，消息被当前 run 消费。
4. `abort()` 传播到模型和工具 token。
5. assistant 作为最后一条消息时禁止 `continueRun()`。

所有测试采用 Given/When/Then 分段，不连接真实 provider。

## 实现顺序

- [ ] 1. 在根 POM 和 `agent-core/pom.xml` 中固定 Jackson、JUnit 5 和 Surefire 版本；运行 `mvn -pl agent-core test`，确认空模块可测试。
- [ ] 2. 实现 `model` 包全部不可变 record、sealed interface 和 enum；为构造约束增加单元测试。
- [ ] 3. 实现 cancellation（`CancellationSource` 不直接实现 `CancellationToken`，防 cast-back；`throwIfCancelled()` 抛 `CancellationException`）、pending queue（含 `QueueMode` 的 `ALL`/`ONE_AT_A_TIME` 两种 drain 模式，mode 可运行时改）和 event 类型；测试 drain 快照与顺序、取消幂等、事件顺序与 sink 契约。
- [ ] 4. 实现 `LlmClient`、`LlmRequest`、`AgentTool` 及两个 sink SPI；确保 SPI 不引用 provider SDK。
- [ ] 5. 实现 `AgentLoopConfig`、`LoopState` 和无工具版本 `AgentLoop`；先通过“单次模型回答”测试。
- [ ] 6. 在 `AgentLoop` 中实现工具查找、参数映射、顺序执行、虚拟线程并行执行和有序结果写回。
- [ ] 7. 加入 steering/follow-up 双层循环、终止条件、截断 tool-call 保护和 cancellation 边界。
- [ ] 8. 实现 `AgentConfig` 与公开入口 `Agent`，加入 active-run 保护和上下文提交。
- [ ] 9. 完成所有 faux client/tool 测试，运行 `mvn -pl agent-core test`。
- [ ] 10. 运行 `mvn verify`，确认根聚合项目在 Java 21 下通过。

## 最终验证波次

- [ ] F1. 运行 `mvn -pl agent-core test`，确认领域约束、loop、工具、队列、取消和 `Agent` 生命周期测试全部通过。
- [ ] F2. 运行根目录 `mvn verify`，确认聚合构建使用 Java 21 且退出 0。
- [ ] F3. 使用 `ScriptedLlmClient` 通过公开 `Agent.prompt()` 执行一次“tool call → tool result → final answer → follow-up → final answer”驱动，确认最终 `LoopResult.context()` 中消息顺序符合实际调用顺序。

## 完成标准

- `Agent.prompt()` 能通过 scripted client 完成“模型 → 工具 → 模型”闭环。
- active run 中加入的 follow-up 会在 agent 原本停止时继续执行。
- 工具错误被转换为模型可见的 `ToolResult`，不会意外终止整个 future 链。
- 消息、事件和并行工具结果的顺序确定且有测试证明。
- 核心模块不依赖 CLI、Spring、数据库、provider SDK 或环境变量。
- `mvn -pl agent-core test` 和根目录 `mvn verify` 均退出 0。

## Must-NOT-Have

首批实现不得加入：

- Spring Boot、Guice 或其他 DI 容器。
- provider-specific API 类型进入 `agent-core`。
- 会话文件、数据库或缓存。
- 自动重试、context compaction、prompt template、extension/plugin。
- 阻塞轮询、`Thread.sleep()` 或长期共享的无界平台线程池。
- 用 `Map<String, Object>` 表示工具参数。
- 在 `AgentLoop` 中读取 API key 或环境变量。

这些能力应在核心闭环和 SPI 稳定后，通过独立模块或上层 session 层增加。
