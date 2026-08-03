# 00. 项目全局视图

Jcode 是一套用 Java 21 表达 Coding Agent 内核的架构设计。它把模型调用协议、Agent 运行循环、流式消息、工具生命周期、事件状态归约、并发取消边界拆分为清晰的模块与协议。

理解 Jcode 的关键，不是从“如何调用某个模型 API”开始，而是从 Coding Agent 的运行结构开始：模型只是外部能力之一，真正需要稳定下来的，是消息协议、流式事件、工具执行管道、上下文推进方式，以及 runtime 与 provider、产品层之间的边界。

## Jcode 解决的架构问题

Coding Agent 看起来像是“模型 + 工具”的组合，但真正复杂的地方并不在单次模型调用，而在这些问题如何同时成立：

- 模型 provider 可以替换，但 Agent Runtime 不应该感知厂商细节。
- 模型输出是流式的，但上下文里只能写入稳定的最终消息。
- 工具调用来自模型，但工具生命周期不能交给模型或工具自己随意控制。
- 运行状态需要被外部观察，但状态不应该散落在 UI、事件回调或工具实现里。
- 用户可以取消运行，但取消所有权不能泄露给只应读取取消状态的组件。
- 一次 Agent run 会推进多轮模型与工具交互，但公共 API 需要保持简单。

Jcode 的设计重心就是把这些问题拆开：协议层定义“模型世界的边界”，运行时定义“Agent 如何推进”，工具管道定义“外部动作如何被安全执行”，事件模型定义“运行过程如何被观察和归约”。

## 总体分层

从依赖方向看，Jcode 的内核分为两层：

```text
agent-core
    │
    ▼
ai
```

`ai` 位于更底层。它不理解 Agent，也不理解产品形态，只定义模型调用所需的通用协议：模型身份、请求、消息、内容块、工具声明、流式事件和取消信号。

`agent-core` 位于运行时层。它使用 `ai` 提供的协议来驱动 Agent loop：接收用户输入、构造模型请求、消费 assistant 流、归约运行状态、执行工具管道，并把稳定消息写回上下文。

更外层的 provider adapter、产品交互层、服务接口、终端或前端界面，都应该围绕这个内核装配，而不是反向侵入内核。

```text
product / adapter layer
          │
          ▼
      agent-core
          │
          ▼
          ai
```

这个分层的核心含义是：

- `ai` 不依赖任何 Jcode 内部模块。
- `agent-core` 只依赖 `ai`。
- provider、UI、server、具体产品行为都不应该成为内核的隐式前提。
- 模块之间通过稳定协议协作，而不是通过全局注册表或 classpath 自动发现协作。

## 核心抽象地图

Jcode 的核心抽象可以按职责分成四组。

### 模型协议抽象

模型协议抽象位于 `ai` 模块中，负责描述“如何与模型通信”。

| 抽象 | 作用 |
| --- | --- |
| `ModelClient` | 模型调用 SPI，负责发起流式请求 |
| `ModelRequest` | 一次模型请求的边界对象 |
| `Model` / `ModelRef` | 模型身份描述 |
| `ThinkingLevel` | provider-neutral 思考强度请求值 |
| `Message` / `Content` | 标准 LLM transcript 类型 |
| `StopReason` / `Usage` | 模型完成原因与用量信息 |
| `AssistantMessageEvent` | assistant 流式输出事件 |
| `AssistantMessageStream` | push-pull 结合的 assistant 流 |
| `ToolSpec` | 可暴露给模型的工具声明 |
| `CancellationSignal` | 只读取消协议 |

这一层的重点不是封装某一家 provider，而是提供一套 provider-neutral 的事实来源。不同 provider 的 API 差异应该被 adapter 消化，不能泄露进 Agent Runtime。

### Agent 运行时抽象

运行时抽象位于 `agent-core` 模块中，负责描述“Agent 如何运行”。

| 抽象 | 作用 |
| --- | --- |
| `Agent` | 对外运行门面 |
| `AgentConfig` | 构造 Agent 的配置 |
| `AgentContext` | 不可变 transcript 与工具上下文 |
| `ContextTransformer` | 模型调用前的异步消息裁剪与注入 seam |
| `MessageProjector` | 模型调用前的同步标准消息投影 seam |
| `PrepareNextTurn` | turn 间替换 context/model/thinking 的 hook |
| `ShouldStopAfterTurn` | turn 后优雅停止 hook |
| `TurnContext` / `NextTurnUpdate` | turn 快照与 next-turn 补丁 |
| `AgentLoop` | package-private 的运行循环主干 |
| `LoopState` | 一次 run 内部唯一可变状态 |
| `LoopResult` | 一次 run 的结束结果 |
| `AgentState` | 对外可观察的状态快照 |

这里的关键设计是：公开 API 尽量保持不可变，运行过程中的可变状态收敛在内部。外部看到的是事件和状态快照，而不是直接操作 loop 内部变量。

### 事件与状态抽象

Agent 运行过程中会产生事件，事件既用于外部观察，也用于内部状态归约。

| 抽象 | 作用 |
| --- | --- |
| `AgentEvent` | Agent 运行事件 |
| `AgentEventSink` | 事件输出边界 |
| `AgentState` | 由事件归约出的状态快照 |

Jcode 的状态不是由 UI 或调用方临时拼出来的，而是由 runtime 在事件发出前先完成归约。这样用户 sink 收到事件时，`Agent.state()` 已经反映了对应事件之后的状态。

### 工具执行抽象

工具系统位于 runtime 内部边界上，负责把模型产生的 tool call 转成受控的外部动作。

| 抽象 | 作用 |
| --- | --- |
| `AgentTool<A>` | 具体工具实现接口 |
| `ToolExecutionResult` | 工具运行结果 |
| `ToolExecutionMode` | 工具执行模式 |
| `ToolUpdateSink` | 工具运行过程中的增量更新 |
| `BeforeToolCall` | 工具执行前 hook |
| `AfterToolCall` | 工具执行后 hook |
| `ToolSchemaValidator` | 最小 JSON Schema 校验器 |

工具调用并不是“模型说调用就直接调用”。Jcode 把工具生命周期固定为 prepare、execute、finalize 三个阶段，由 runtime 统一保证顺序、校验、hook 和结果写回。

并行工具批次使用双排序契约：`ToolStarted` 与完整 prepare 阶段（参数准备、schema 校验、before hook、类型转换）严格按 tool call 的源顺序串行执行；execute 与 finalize 并行运行；`ToolCompleted` 事件按实际完成顺序由 loop 线程串行投递，让 UI 尽快看到完成结果；而 tool-result 消息事件、context transcript、下一次模型请求与 `TurnCompleted.toolResults` 始终按源顺序写回，保证模型与存储看到确定的 transcript。事件投递失败、executor 拒绝等基础设施失败不归一为工具错误：已提交任务先全部结算，再传播首个异常，未提交的调用不会执行。

## 一次 Agent 运行的主路径

从外部看，一次运行通常从 `Agent.prompt()` 开始：

```text
User prompt
  │
  ▼
Agent.prompt()
  │
  ▼
AgentContext + AgentLoopConfig
  │
  ▼
ContextTransformer → MessageProjector → ModelRequest
  │
  ▼
AssistantMessageStream
  │
  ├─ stream events
  │  └─ AgentEvent -> AgentState
  │
  └─ final assistant message
        │
        ├─ no tool call
        │    └─ LoopResult
        │
        └─ tool calls
             │
             ▼
        prepare -> execute -> finalize
             │
             ▼
        tool result messages
             │
             ▼
        next model turn
```

这条路径体现了几个重要约束：

1. 模型调用通过 `ModelClient` 进入，runtime 不直接依赖具体 provider。
2. 每次模型调用前，`ContextTransformer` 和 `MessageProjector` 依次将开放 transcript 投影为标准请求视图，不修改持久 transcript。
3. assistant 流式输出先以事件形式被消费，partial 消息不会直接进入上下文。
4. 只有完成后的 assistant message 才能成为 transcript 的一部分。
5. 如果 assistant message 包含 tool call，runtime 进入工具管道。
6. 工具结果被转成标准 tool result message 后，再写回上下文。
7. 上下文推进后，runtime 可以继续发起下一轮模型请求。
8. 每个正常 turn 结束后，`PrepareNextTurn` 可以先替换下一轮的 context/model/thinking，`ShouldStopAfterTurn` 再决定是否优雅停止；两者都发生在 steering/follow-up 队列消费之前。

## 贯穿代码的设计思想

### Provider-neutral first

模型厂商差异不应该污染内核。`ai` 模块定义的是统一协议，而不是某一家 API 的 Java 映射。provider adapter 的职责是把外部 API 转换成 Jcode 的标准模型。

### Explicit assembly

Jcode 避免隐藏的全局注册机制。provider、tools、hooks 都应该显式传入。这样模块依赖关系清晰，测试替身也更容易构造。

### Runtime owns the loop

Agent 的推进逻辑集中在 runtime 中。模型只负责产生 assistant 输出，工具只负责执行具体动作；是否继续、如何写回上下文、如何处理工具失败，都由 runtime 统一决定。

### Events reduce state

运行状态由事件归约得到，而不是由多个组件各自维护。事件既是观察边界，也是状态变化的来源。

### Centralized tool pipeline

工具生命周期由 runtime 统一编排。参数准备、schema 校验、hook、执行、异常转换、结果修正、消息写回，都在一条固定管道中完成。

### Immutable public model

公开类型尽量不可变。一次 run 中必须存在的可变状态，被限制在 package-private 的内部对象里，避免调用方直接依赖运行细节。

### Cancellation has ownership

取消能力分为拥有方和观察方。`CancellationSource` 拥有取消权，`CancellationSignal` 只暴露读取能力，避免任意组件都能中止运行。
