# AgentLoop Java 设计感重构：从 TS 直译到 Java 习惯表达

> 状态：重构方案  
> Jcode 基线：`c0eda94`（2026-07-30）  
> 涉及模块：`agent-core`  
> 路径：L1（纯结构重构）+ L2（抽取 `ToolCallExecutor` 协作对象）

## 1. 决策摘要

`AgentLoop` 当前实现忠实复刻了 pi `agent-loop.ts` 的控制流，但把 TypeScript 的表达习惯直接搬到 Java，导致方法过长、参数透传、错误处理内联、重复构造散落。本方案在**不改变运行时语义、控制流顺序、事件序列、取消边界和错误归一策略**的前提下，把 `AgentLoop` 重构为 Java 习惯的表达：

1. 拆解 `runLoop`（~120 行双嵌套 while）为命名子方法，方法名即步骤，去掉 `step N` 注释。
2. 把 model 调用 + 异常归一、stop reason 三分支、outcomes 写回等内联逻辑提取为单一职责方法。
3. 抽取 package-private `ToolCallExecutor`，承载工具三阶段管道与顺序/并行分发，`AgentLoop` 退化为调度器。
4. 用静态工厂消除 `ToolOutcome` 与失败 `Message.Assistant` 的重复构造。
5. stream 化 `extractToolCalls` / `buildNameToTool` / `projectMessages` / `toolSpecs` 等手写循环。

不引入新的运行时类型到公开 API；所有新增类保持 package-private，与 `LoopState` / `RunEventEmitter` 的薄协作风格一致。

## 2. 问题诊断：当前 TS 直译痕迹

| 位置 | 症状 | TS 习惯 / Java 期望 |
|---|---|---|
| `runLoop` ~120 行双嵌套 while | 注释硬编码 `step 5` / `step 6` / `step 9-10` | TS 把算法描述嵌进代码；Java 期望方法名承载步骤语义 |
| model 调用块 | `try {...} catch (IE) {...} catch (RE) {...}` 内联在主流程，失败时就地构造 zero-usage `Message.Assistant` | TS `try/catch + fallback value`；Java 期望 `invokeModelSafely()` 返回归一化结果，主干线性 |
| `executeOneToolCallTyped` ~70 行 | prepare/execute/finalize 三阶段 + 4 处重复 `var internal = ...failure(...); return new ToolOutcome(internal, toToolResultMessage(...))` | TS 内联三阶段；Java 期望三阶段是一等管道，错误结果走工厂 |
| `executeSequential` / `executeParallel` / `executeOneToolCall` | `state, config, cancellation` 一路透传 | TS 静态方法链；Java 期望封装成持有依赖的协作对象 |
| `extractToolCalls` / `buildNameToTool` | 手写 `for + instanceof + add` / `for + putIfAbsent` | TS 命令式循环；Java 期望 stream `toList` / `Collectors.toMap` |
| model 失败 + `abortRun` | 同样的 `new Message.Assistant(List.of(), reason, msg, zero(), now())` 出现 3+ 次 | 缺 `Messages.aborted/errored` 工厂 |
| `runLoop` 内 stop reason 三分支 | 空 / LENGTH / 正常裸写在主流程 | 可封进 `dispatchToolCalls()` 单方法 |

证据见 `../../../agent-core/src/main/java/site/pplee/jcode/agentcore/AgentLoop.java`（基线 `c0eda94`）。

## 3. 设计原则与不变量

重构必须保持以下不变量，违反即视为改变语义：

1. **事件序列不变**：用户/toolResult 消息发 `MessageStarted → MessageCompleted`；assistant 消息发 `MessageStarted → MessageUpdated... → MessageCompleted`；工具发 `ToolStarted → ToolUpdate... → ToolCompleted`。`AgentStarted → TurnStarted... → TurnCompleted → AgentCompleted` 的外层顺序不变。
2. **取消边界不变**：boundary 1（turn 前 / model 调用前）、boundary 2（stream 错误归一）、boundary 3（工具批次前）、boundary 4（inner loop 后 / follow-up turn 前）四处 `isCancelled()` 检查的位置和语义不变。
3. **错误归一策略不变**：model 调用 `InterruptedException` / `RuntimeException` → zero-usage ABORTED/ERROR assistant；工具 prepare/schema/before/execute/after 失败 → error `ToolExecutionResult`，不抛进 loop；after hook 失败保留原 result。
4. **工具管道顺序不变**：prepare → execute → finalize；任一 SEQUENTIAL 工具令整批串行；并行结果恢复源顺序后写回；LENGTH 响应中所有 tool call 转 failure 不执行；`terminate` 不进入标准 transcript。
5. **流式消费不变**：`Start` 发 `MessageStarted`，delta 发 `MessageUpdated`，`Done`/`Error` 返回最终 `Message.Assistant`；partial 不进入 context，final 才 append。
6. **可变状态边界不变**：核心层可变消息集合仍只在 `LoopState`；新增 `ToolCallExecutor` 不持有跨 run 状态，每次 run 构造新实例。
7. **可见性不变**：`AgentLoop` / `ToolCallExecutor` / `LoopState` / `RunEventEmitter` 保持 package-private；唯一公开运行入口仍是 `Agent`。

## 4. 目标结构

```text
agent-core/src/main/java/site/pplee/jcode/agentcore/
├── Agent.java
├── AgentConfig.java
├── AgentState.java
├── AgentLoop.java          # 退化为调度器：编排 turn / model / dispatch / outcomes
├── AgentLoopConfig.java
├── AgentContext.java
├── LoopState.java
├── LoopResult.java
├── RunEventEmitter.java
├── ToolCallExecutor.java  # 新增（pkg-private）：三阶段管道 + 顺序/并行分发
├── ToolSchemaValidator.java
└── ...
```

`ToolCallExecutor` 与 `RunEventEmitter` / `LoopState` 同属 package-private 薄协作层，符合 AGENTS.md 的「显式装配、薄协作、深模块」取向。

## 5. 改造点详解

### 5.1 L1：`AgentLoop` 内部结构重构

#### 5.1.1 拆解 `runLoop`

把当前 `runLoop` 的内联逻辑提取为以下 package-private / private 方法，主干只编排：

| 新方法 | 职责 | 取代当前内联段 |
|---|---|---|
| `prepareTurn(state, events, pending, firstTurn)` | 非 first turn 发 `TurnStarted`；把 pending 消息写进 state（每条 `MessageStarted → append → MessageCompleted`）；返回 `false`（新 firstTurn） | `runLoop` 内 `if (!firstTurn) events.emit(TurnStarted)` + pending 写回块 |
| `invokeModelSafely(state, config, cancellation)` | 构造 `ModelRequest`（含 `projectMessages` + `toolSpecs`）；调 `modelClient.stream` + `consumeStream`；`IE` / `RE` 两种 catch 归一化为 zero-usage ABORTED/ERROR `Message.Assistant`；返回之 | `runLoop` 内 step 5 的 request 构造 + try/catch 块 |
| `recordAssistant(state, events, assistantMessage)` | `StandardAgentMessage.of` + `state.append` + `MessageCompleted` | step 6 |
| `handleTerminalFailure(state, events, assistantMessage)` | `TurnCompleted(assistant, List.of())` + `state.result()` + `AgentCompleted` + 返回 `LoopResult` | step 7 |
| `dispatchToolCalls(assistantMessage, toolCalls, state, config, cancellation)` | 三分支：空 → `List.of()`；`LENGTH` → `failTruncatedToolCalls`；正常 → `executeToolCalls` | step 9-10 的 if/else if/else |
| `recordOutcomes(state, events, outcomes)` | 每条 outcome 包装 `StandardAgentMessage`，`MessageStarted → append → MessageCompleted` | step 10 写回块 |
| `shouldContinueInnerLoop(outcomes)` | `!outcomes.isEmpty() && !allTerminated(outcomes)` | step 12 判续 |
| `drainSteering(config)` / `drainFollowUp(config)` | 复用现有 `drain(PendingMessageSource)`，命名更贴合语义 | `drain(config.steeringMessages())` 等 |

重构后 `runLoop` 主干（示意）：

```java
private LoopResult runLoop(LoopState state, AgentLoopConfig config, CancellationSignal cancellation) {
    boolean firstTurn = true;
    var pending = drainSteering(config);
    var events = config.events();

    while (true) {
        var hasMoreToolCalls = true;
        while (hasMoreToolCalls || !pending.isEmpty()) {
            if (cancellation.isCancelled()) return abortRun(state, events);
            firstTurn = prepareTurn(state, events, pending, firstTurn);
            pending = List.of();

            var assistant = invokeModelSafely(state, config, cancellation);
            recordAssistant(state, events, assistant);
            if (assistant.stopReason().isTerminalFailure()) {
                return handleTerminalFailure(state, events, assistant);
            }

            var toolCalls = extractToolCalls(assistant);
            if (!toolCalls.isEmpty() && cancellation.isCancelled()) {
                return abortRun(state, events);
            }
            var outcomes = dispatchToolCalls(assistant, toolCalls, state, config, cancellation);
            recordOutcomes(state, events, outcomes);
            events.emit(new AgentEvent.TurnCompleted(assistant, toolResultMessagesOf(outcomes)));

            pending = drainSteering(config);
            hasMoreToolCalls = shouldContinueInnerLoop(outcomes);
        }

        if (cancellation.isCancelled()) return abortRun(state, events);
        var followUp = drainFollowUp(config);
        if (followUp.isEmpty()) break;
        pending = followUp;
    }

    var result = state.result();
    events.emit(new AgentEvent.AgentCompleted(result));
    return result;
}
```

`step N` 注释全部删除——方法名即步骤。

#### 5.1.2 静态工厂消除重复构造

- `ToolOutcome.failure(Content.ToolCall call, String reason)`：消除 `executeOneToolCallTyped` 内 4 处 `var internal = ...failure(...); return new ToolOutcome(internal, toToolResultMessage(call, internal))`。
- `ToolOutcome.success(...)` / `ToolOutcome.of(internal, call)`：按需补充。
- `AgentLoop.abortedAssistant(String msg)` / `erroredAssistant(String msg)`（私有静态）：消除 `invokeModelSafely` 与 `abortRun` 内 `new Message.Assistant(List.of(), reason, msg, Usage.zero(), Instant.now())` 的 3+ 次重复。

`ToolOutcome` 工厂放在 record 内：

```java
private record ToolOutcome(ToolExecutionResult internal, Message.ToolResultMessage message) {
    static ToolOutcome failure(Content.ToolCall call, String reason) {
        var r = ToolExecutionResult.failure(reason);
        return new ToolOutcome(r, toToolResultMessage(call, r));
    }
}
```

#### 5.1.3 stream 化手写循环

```java
// extractToolCalls
private static List<Content.ToolCall> extractToolCalls(Message.Assistant a) {
    return a.content().stream()
            .filter(Content.ToolCall.class::isInstance)
            .map(Content.ToolCall.class::cast)
            .toList();
}

// buildNameToTool
private static Map<String, AgentTool<?>> buildNameToTool(List<AgentTool<?>> tools) {
    return tools.stream().collect(Collectors.toMap(
            AgentTool::name, Function.identity(), (a, b) -> a));
}

// projectMessages
private static List<Message> projectMessages(List<AgentMessage> messages) {
    return messages.stream()
            .filter(StandardAgentMessage.class::isInstance)
            .map(StandardAgentMessage.class::cast)
            .map(StandardAgentMessage::message)
            .toList();
}

// toolSpecs
private static List<ToolSpec> toolSpecs(List<AgentTool<?>> tools) {
    return tools.stream().map(AgentTool::spec).toList();
}
```

注意：`.toList()` 返回不可修改 `List`，与原 `List.copyOf` 语义一致。

### 5.2 L2：抽取 `ToolCallExecutor`

#### 5.2.1 职责

`ToolCallExecutor` 承载当前 `AgentLoop` 内全部工具执行相关逻辑：

- 三阶段管道（prepare → execute → finalize）；
- 顺序 / 并行分发（含「任一 SEQUENTIAL 令整批串行」判定）；
- `LoopToolUpdateSink` settle 语义；
- `failTruncatedToolCalls`（LENGTH 失败）；
- `ToolOutcome` 构造（含 `ToolOutcome.failure` 工厂）。

`AgentLoop` 只负责编排 turn 生命周期 + 调用 `ToolCallExecutor.runBatch`。

#### 5.2.2 目标骨架

```java
/**
 * Package-private tool execution orchestrator. Owns the three-phase
 * prepare/execute/finalize pipeline and sequential/parallel dispatch.
 * Constructed per run; holds no cross-run state.
 */
final class ToolCallExecutor {
    private final AgentLoopConfig config;
    private final LoopState state;
    private final CancellationSignal cancellation;
    private final ExecutorService executor;
    private final Map<String, AgentTool<?>> toolMap;

    private ToolCallExecutor(AgentLoopConfig config, LoopState state,
                             CancellationSignal cancellation, ExecutorService executor) {
        this.config = config;
        this.state = state;
        this.cancellation = cancellation;
        this.executor = executor;
        this.toolMap = buildNameToTool(state.context().tools());
    }

    static ToolCallExecutor of(LoopState state, AgentLoopConfig config,
                               CancellationSignal cancellation, ExecutorService executor) {
        return new ToolCallExecutor(config, state, cancellation, executor);
    }

    /** Dispatch a batch: SEQUENTIAL flag -> serial; else parallel (LENGTH handled by caller). */
    List<ToolOutcome> runBatch(List<Content.ToolCall> calls) {
        return shouldRunSequentially(calls) ? runSequential(calls) : runParallel(calls);
    }

    /** Per-batch dynamic dispatch: config or any tool SEQUENTIAL -> serial. */
    private boolean shouldRunSequentially(List<Content.ToolCall> calls) {
        return config.toolExecution() == ToolExecutionMode.SEQUENTIAL
                || calls.stream().anyMatch(this::isSequentialTool);
    }
    private boolean isSequentialTool(Content.ToolCall tc) {
        var t = toolMap.get(tc.name());
        return t != null && t.executionMode() == ToolExecutionMode.SEQUENTIAL;
    }

    /** LENGTH-truncated calls: all turned into failure outcomes, no execution. */
    List<ToolOutcome> failTruncated(List<Content.ToolCall> calls) { ... }

    private List<ToolOutcome> runSequential(List<Content.ToolCall> calls) { ... }
    private List<ToolOutcome> runParallel(List<Content.ToolCall> calls) { ... }
    private ToolOutcome runOne(Content.ToolCall call) { ... }
    private <A> ToolOutcome runTyped(AgentTool<A> tool, Content.ToolCall call) { ... }

    private record ToolOutcome(ToolExecutionResult internal, Message.ToolResultMessage message) {
        static ToolOutcome failure(Content.ToolCall call, String reason) { ... }
        static ToolOutcome of(Content.ToolCall call, ToolExecutionResult r) { ... }
    }

    private static final class LoopToolUpdateSink implements ToolUpdateSink { ... }
}
```

`AgentLoop` 侧的调用收窄为：

```java
private List<ToolOutcome> dispatchToolCalls(
        Message.Assistant assistant, List<Content.ToolCall> toolCalls,
        LoopState state, AgentLoopConfig config, CancellationSignal cancellation) {
    if (toolCalls.isEmpty()) return List.of();
    var exec = ToolCallExecutor.of(state, config, cancellation, executor);
    if (assistant.stopReason() == StopReason.LENGTH) {
        return exec.failTruncated(toolCalls);
    }
    return exec.runBatch(toolCalls);
}
```

#### 5.2.3 迁移清单

| 当前位置（`AgentLoop`） | 迁移到 `ToolCallExecutor` |
|---|---|
| `executeToolCalls` | `runBatch` |
| `executeSequential` | `runSequential` |
| `executeParallel` | `runParallel` |
| `executeOneToolCall` | `runOne` |
| `executeOneToolCallTyped` | `runTyped` |
| `failTruncatedToolCalls` | `failTruncated` |
| `buildNameToTool` | 构造器内 |
| `toToolResultMessage` | `ToolOutcome` 工厂内 |
| `allTerminated` | `runBatch` 判续用，留在 `ToolCallExecutor`（package-private static，`AgentLoop.shouldContinueInnerLoop` 引用） |
| `causeMessage` | `ToolCallExecutor` package-private static；`AgentLoop.invokeModelSafely` 引用 `ToolCallExecutor.causeMessage`（消除双份重复；迁移清单原遗漏，现补） |
| `LoopToolUpdateSink` | 私有内部类 |
| `ToolOutcome` record | package-private 内部 record（`AgentLoop` 需引用 `ToolCallExecutor.ToolOutcome`：`dispatchToolCalls` 返回值、`recordOutcomes`/`toolResultMessagesOf`/`shouldContinueInnerLoop` 参数；方案文字原写"私有"与 §6.1 主干示意矛盾，取骨架的必然解） |

`AgentLoop` 保留：`runPrompt` / `continueRun` / `runLoop` 及 L1 拆出的子方法、`abortRun`、`consumeStream`、`projectMessages`、`toolSpecs`、`drainSteering/FollowUp`、静态工厂 `abortedAssistant/erroredAssistant`。

#### 5.2.4 构造时机

每次 `runLoop` 开始时构造一个 `ToolCallExecutor`，整个 run 复用；`toolMap` 只 build 一次。不在 turn 之间重建（工具列表在 run 内不变）。

## 6. 不变量校验对照

| 不变量（§3） | 重构后承载点 |
|---|---|
| 事件序列 | `prepareTurn` / `recordAssistant` / `recordOutcomes` / `ToolCallExecutor.runBatch` 内的 `ToolStarted/ToolCompleted` |
| 取消边界 1-4 | `runLoop` 主干保留 4 处 `isCancelled()`；`ToolCallExecutor.runSequential/runParallel` 内的工具间取消检查保留 |
| 错误归一 | `invokeModelSafely`（model）+ `ToolCallExecutor.runTyped`（工具三阶段失败 → `ToolOutcome.failure`） |
| 工具管道顺序 | `ToolCallExecutor.runTyped` 三阶段 + `runBatch` 顺序/并行判定 |
| 流式消费 | `consumeStream` 不动 |
| 可变状态边界 | `LoopState` 仍唯一可变集合；`ToolCallExecutor` 字段 final，无跨 run 状态 |
| 可见性 | `ToolCallExecutor` package-private |

## 7. 实施波次

每波次结束跑 `mvn -pl agent-core -am test`，必须全绿才进下一波。

### Wave R1：L1 纯结构重构（不引入新类）

1. **R1.1** 提取 `prepareTurn` / `recordAssistant` / `handleTerminalFailure` / `recordOutcomes` / `shouldContinueInnerLoop` / `drainSteering` / `drainFollowUp`，`runLoop` 主干改调用。跑测试。
2. **R1.2** 提取 `invokeModelSafely`（含两种 catch 归一）。跑 `StreamingEventTest` + 全量。
3. **R1.3** 提取 `dispatchToolCalls`（封空/LENGTH/正常三分支）。跑 `ToolPipelineTest` + 全量。
4. **R1.4** 加 `ToolOutcome.failure` / `abortedAssistant` / `erroredAssistant` 工厂，替换内联构造。删 `step N` 注释。跑全量。
5. **R1.5** stream 化 `extractToolCalls` / `buildNameToTool` / `projectMessages` / `toolSpecs`。跑全量。

完成标志：`AgentLoop` 行数下降、`runLoop` 主干线性、无 `step N` 注释、无重复 `Message.Assistant` 构造、测试全绿。

### Wave R2：L2 抽取 `ToolCallExecutor`

1. **R2.1** 新建 `ToolCallExecutor.java`（pkg-private），迁移 `executeToolCalls` → `runBatch`、`executeSequential` → `runSequential`、`executeParallel` → `runParallel`、`executeOneToolCall` → `runOne`、`executeOneToolCallTyped` → `runTyped`。`AgentLoop` 临时保留转发方法。跑 `ToolPipelineTest` + 全量。
2. **R2.2** 迁移 `failTruncatedToolCalls` → `failTruncated`、`buildNameToTool` 到构造器、`toToolResultMessage` 到 `ToolOutcome` 工厂、`allTerminated` 到 `ToolCallExecutor`、`LoopToolUpdateSink` 为内部类。跑全量。
3. **R2.3** `AgentLoop` 删除已迁移的转发方法，`dispatchToolCalls` 直接构造 `ToolCallExecutor.of(...)` 并调用。跑全量。
4. **R2.4** `ToolOutcome` record 迁为 `ToolCallExecutor` 私有内部 record，补 `failure` / `of` 工厂。跑全量。

完成标志：`AgentLoop` 不再含工具三阶段实现细节；`ToolCallExecutor` 单文件承载全部工具管道；`ToolPipelineTest` 契约不变且全绿。

## 8. 验收条件

1. `runLoop` 主干为线性编排，无 `step N` 注释，每个步骤对应一个命名方法。
2. `AgentLoop` 不再出现 `var internal = ...failure(...); return new ToolOutcome(internal, toToolResultMessage(...))` 重复模式。
3. `AgentLoop` 不再出现 `new Message.Assistant(List.of(), reason, msg, Usage.zero(), Instant.now())` 字面量（统一走工厂）。
4. `extractToolCalls` / `buildNameToTool` / `projectMessages` / `toolSpecs` 使用 stream，无手写 `for + add/put`。
5. 工具三阶段管道（prepare/execute/finalize）、顺序/并行分发、`LoopToolUpdateSink`、`ToolOutcome` 全部位于 `ToolCallExecutor`。
6. `ToolCallExecutor` package-private，构造入口仅 `of(...)`，无跨 run 状态，字段 `final`。
7. 事件序列、取消边界、错误归一、工具管道顺序、流式消费行为与基线 `c0eda94` 逐项一致（由 `ToolPipelineTest` / `StreamingEventTest` 兜底）。
8. `mvn -pl agent-core -am test` 全绿；`mvn verify`（根 reactor）通过 enforcer 与全测试。
9. `../../../agent-core/AGENTS.md` 的 WHERE TO LOOK 表更新：工具三阶段指向 `ToolCallExecutor` 而非 `AgentLoop.executeOneToolCallTyped`。

## 9. 明确拒绝的划分

### 拒绝：为 stop reason 三分支引入 sealed `TurnDisposition`

L1.3 的 `dispatchToolCalls` 方法提取已足够表达。引入 sealed 类型会让一个本可线性化的三分支变成类型分发，增加间接层，与项目「薄协作」风格冲突。

### 拒绝：引入 `TurnContext` value object 包装 `(state, config, cancellation)`

会让方法签名更短，但多一层间接，且 `state` 可变、`config` 不可变、`cancellation` 只读三者语义不同，强行打包反而模糊。保持显式参数更符合「显式装配」原则。

### 拒绝：把 `consumeStream` 的 `default` 改为显式枚举所有 delta 变体

`AssistantMessageEvent` 是 sealed 12 变体，显式枚举让 switch 膨胀且收益有限。`consumeStream` 本身已薄，L1 不动其结构（`default` 兜底所有 delta 变体保留）。如未来新增 delta 变体需要编译期提醒，再单独评估。

### 拒绝：把 `ToolCallExecutor` 升为 public

唯一调用者是 `AgentLoop`（package-private），无第二调用者。提前公开会发布假想 seam，与 `AgentLoop` 暂不扩公开接口的既定决策一致（见 `../pi-inspired-module-boundaries.md` §3.2）。

### 拒绝：把 sequential/parallel 做成策略模式（`ToolDispatchStrategy` 接口 + 两个实现类）

1. **策略非构造时确定**：sequential/parallel 判定依赖每次 `runBatch(calls)` 收到的具体内容（「任一工具声明 SEQUENTIAL 令整批串行」），同一 `ToolCallExecutor` 不同 batch 走不同路径。策略模式的价值场景是构造时选定、贯穿对象生命周期，此处不匹配。
2. **策略对象需访问 executor 私有依赖**：`runSequential`/`runParallel` 都要用 `config.events()`/`executor`/`cancellation`/`toolMap`/`runOne`/`runTyped`。做成独立策略类只有三条路——暴露 executor 字段（破坏封装）、策略持有 executor 引用（循环依赖味道）、依赖透传给 `dispatch(calls, exec)`（回到参数透传）。且两个 dispatch 共享 `runOne` 三阶段管道，是同一实现的两个调度变体，非独立算法，强行分离割裂共享逻辑。
3. **只两种不扩展的模式**：`ToolExecutionMode` enum 只有 `PARALLEL`/`SEQUENTIAL` 两值，工具调度的基本模式就是串/并两种。为不存在的扩展点建抽象。
4. **与项目 anti-patterns 风格冲突**：根 AGENTS.md 明确「禁止静态可变注册表、禁止 ServiceLoader 扫描、禁止 classpath 自动注册」「不长期维护两套事实来源」。策略模式做不谨慎就滑向注册表；做得干净也是为 2 个不扩展策略建接口，与 §9 已拒绝的 `TurnContext` value object、sealed `TurnDisposition` 同类。

**替代**：方法级分离——`runBatch` 一行分发到 `shouldRunSequentially`/`isSequentialTool` → `runSequential`/`runParallel`，共享 `runOne` 三阶段管道。判定逻辑单一职责可单独测，不引入间接层。若未来 `ToolExecutionMode` 扩展第三种真实调度模式（如带优先级的混合、带资源限额的节流），再重新评估——变化点真实存在才有抽象正当性。

### 拒绝：在 `ai` 模块加 `Messages` 工厂

`Message.Assistant` 属 `ai`，但 `abortedAssistant` / `erroredAssistant` 是 agent 运行时的归一策略，属于 `agent-core` 语义。放在 `AgentLoop` 私有静态方法即可，不污染 `ai` 协议层。

## 10. 来源

### Jcode 基线

- `../../../agent-core/src/main/java/site/pplee/jcode/agentcore/AgentLoop.java`（`c0eda94`）
- `../../../agent-core/src/main/java/site/pplee/jcode/agentcore/LoopState.java`
- `../../../agent-core/src/main/java/site/pplee/jcode/agentcore/AgentLoopConfig.java`
- `../../../agent-core/src/main/java/site/pplee/jcode/agentcore/RunEventEmitter.java`
- `../../../agent-core/AGENTS.md`（模块约定、不变量、anti-patterns）

### 仓库内设计参照

- [`../pi-inspired-module-boundaries.md`](../pi-inspired-module-boundaries.md) §2.3（无状态循环与有状态 Agent 分开）、§2.5（工具三阶段管道）、§3.2（`AgentLoop` 保持 package-private）
- [`../../architecture/AGENTS.md`](../../architecture/AGENTS.md)（写作规则：中文正文、标识符英文、技术解读语气）

### pi 参照（设计背景，非实现依据）

- pi-book 第 8 章「agentLoop」——双层循环、流消费、三阶段工具执行、turn 控制。
- pi-book 第 9 章「工具执行」——prepare/execute/finalize 与并行双排序。

本方案参考 pi 的设计意图与不变量，但不照搬其 TypeScript 表达；重构后 `AgentLoop` 应读起来像 Java 调度器，而非 TS 循环的直译。
