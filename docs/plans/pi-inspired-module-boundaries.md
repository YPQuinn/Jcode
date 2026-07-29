# Jcode 模块边界：基于 pi 的分包与极简核心思想

> 状态：架构提案  
> Jcode 基线：`83b1feb`（2026-07-29）  
> pi 源码基线：`cee5ff7`（2026-07-26，`@earendil-works/pi-*` 0.82.1）  
> pi-book 基线：`c01f38d`（2026-07-27）

## 1. 决策摘要

Jcode 采用下面的稳定主干：

```text
ai  <-  agent-core  <-  coding-agent  <-  server（需要时再建）
 ^          ^               ^
 |          |               |
 |          +---------------+
 |               coding-agent 同时直接使用 ai 的模型/消息类型
 |
ai-provider-*（有真实 provider 后按需创建）

coding-agent  ->  tui（需要终端产品时再建；tui 自身零 Jcode 内部依赖）
```

箭头 `A -> B` 表示 **A 依赖 B**。必须满足：

1. `ai` 不知道 `agent-core`。
2. `agent-core` 的唯一 Jcode 内部依赖是 `ai`。
3. `coding-agent` 可以同时依赖 `agent-core`、`ai` 和 `tui`。
4. 依赖图无环，只能从产品层指向内核层。
5. 当前只创建 `ai` 与 `agent-core`；没有独立使用者的未来模块不提前创建。

这不是机械复制 pi 的目录，而是应用它的判断标准：**只有代码拥有不同使用者、不同依赖重量或独立发布边界时，才拆成 Maven 模块**。pi 的包数量虽然经历了多次变化，但该标准没有变化 [B2]。

## 2. 从 pi 提炼出的设计约束

### 2.1 Maven 模块按使用者拆，不按领域名词拆

`message`、`event`、`tool`、`queue`、`concurrent` 是源码包，不是独立 Maven 模块。它们没有独立使用者，拆出去只会制造浅模块和版本协调成本。

pi 的直接依赖验证为：

- `pi-ai`：零内部依赖；
- `pi-agent-core`：唯一内部依赖为 `pi-ai`；
- `pi-coding-agent`：依赖 `pi-agent-core`、`pi-ai`、`pi-tui`；
- `pi-tui`：零内部依赖 [P-PACKAGES]。

### 2.2 内核提供协议，不提供产品功能

内核只负责：

1. 调模型的统一协议；
2. Agent 循环和工具执行管道；
3. 运行中状态及生命周期事件。

会话、压缩、Prompt 装配、具体编码工具、权限 UI、Extension、Skill、CLI 都属于产品层 [B30]。如果某能力能由 `transformContext`、工具 hook 或事件订阅组合出来，就不进入内核 [B31]。

### 2.3 无状态循环与有状态 Agent 分开

- 循环实现只消费调用参数和 turn 快照，不保存跨 run 状态。
- `Agent` 管理 transcript、活动 run、队列、取消、流式状态和订阅者。
- 所有 Agent 状态变更由事件归约器集中处理。

这是 pi `agent-loop.ts` 与 `agent.ts` 的核心分工 [B8][B10][P-LOOP][P-AGENT]。

### 2.4 在模型调用 seam 才投影消息

循环内部使用可扩展的 `AgentMessage`；仅在调用模型前执行：

```text
AgentMessage[]
  -> transformContext
  -> convertToLlm
  -> ai.Message[]
  -> ModelClient
```

`transformContext` 管 Agent 语义下的裁剪和注入，`convertToLlm` 管“哪些消息可发送给模型”以及格式投影。两者不能揉成一个回调 [B8][P-LOOP]。

### 2.5 工具执行是固定三阶段管道

```text
prepare -> execute -> finalize
```

- prepare：查工具、预处理参数、schema 校验、`beforeToolCall`；
- execute：实际执行并上报增量；
- finalize：`afterToolCall`、错误归一、完成事件、生成消息。

具体工具做什么属于产品层，但这条管道及排序必须由 `agent-core` 统一保证 [B9][P-LOOP]。

### 2.6 显式装配，不使用全局注册表

pi 已从全局 provider 注册表迁移为显式 `Models` 集合，原因是隔离状态、提高可测试性并避免导入副作用 [B4]。Jcode 同样要求：

- provider、工具、hook 都通过构造参数或配置对象显式传入；
- 不使用静态可变注册表；
- 不使用扫描 classpath 后自动注册的隐式行为；
- 可提供聚合工厂，但聚合过程必须显式可见。

## 3. 目标模块

## 3.1 `ai`：模型调用协议与运行时

**独立使用者**：只需要统一 LLM 调用、不需要 Agent Loop 的应用。

### 负责

- 模型身份：provider、api、model 三个独立维度；
- 标准 LLM 消息与内容块；
- `Usage`、停止原因、错误消息等模型结果元数据；
- 可发送给模型的 `Context`；
- 可序列化的工具声明 `ToolSpec`（名称、描述、参数 JSON Schema）；
- assistant 流式事件协议和最终结果；
- 模型调用接口；
- 将来实现显式的 `Provider` / `Models` 运行时集合。

pi 把 `Model`、`Context`、标准 `Message`、`Tool` 和 assistant stream 事件放在 `pi-ai`，`agent-core` 直接复用这些契约 [P-AI-TYPES]。

### 不负责

- Agent Loop；
- 工具执行；
- steering / follow-up；
- Agent 状态；
- 会话和压缩；
- 具体 coding 工具；
- UI。

### 当前阶段最小目录

```text
ai/src/main/java/site/pplee/jcode/ai/
├── model/
│   ├── Model.java
│   └── ModelRef.java
├── message/
│   ├── Message.java
│   ├── Content.java
│   ├── Usage.java
│   └── StopReason.java
├── tool/
│   └── ToolSpec.java
├── stream/
│   ├── AssistantMessageEvent.java
│   └── AssistantMessageStream.java
├── client/
│   ├── ModelClient.java
│   └── ModelRequest.java
└── concurrent/
    └── CancellationSignal.java
```

`CancellationSignal` 位于最低共享层，是因为 Java 没有与 JavaScript `AbortSignal` 等价的平台协议；model adapter 和 Agent 工具都需要读取同一个取消信号。创建与触发取消的 `CancellationSource` 仍由 `agent-core` 持有。

### 流式契约

`AssistantMessageStream` 应同时支持：

- 按序消费 `AssistantMessageEvent`；
- 获取最终 `AssistantMessage`；
- 成功、模型错误、网络错误和主动取消均产生最终消息；
- 只有协议实现 bug 才异常结束。

低层事件至少表达：

```text
start
text_start / text_delta / text_end
thinking_start / thinking_delta / thinking_end
tool_call_start / tool_call_delta / tool_call_end
done / error
```

这对应 pi “流和最终结果使用同一个底层协议”的设计 [B6][P-AI-TYPES]。

## 3.2 `agent-core`：通用 Agent Runtime

**独立使用者**：需要 Agent Loop，但不需要 coding 产品功能的应用。

### 负责

- 无状态循环实现；
- 有状态 `Agent` 壳；
- Agent transcript 与当前运行态；
- agent / turn / message / tool 生命周期事件；
- steering / follow-up 队列；
- active-run 互斥和取消源；
- prepare / execute / finalize 工具管道；
- context transform 与 LLM message projection；
- turn 后配置刷新与优雅停止；
- 并行工具的事件顺序和消息顺序。

### 不负责

- provider SDK、认证和模型目录；
- Session、JSONL、数据库；
- Compaction 的算法或触发策略；
- System Prompt 装配；
- Extension / Skill；
- read / edit / bash 等具体工具；
- CLI / TUI / RPC / HTTP。

pi 明确将 compaction 放在产品层，因为它依赖会话结构、配置、工具语义和扩展策略 [B12]。Jcode 不复制 pi 当前仍处于并行演进中的“厚 `AgentHarness`”到 `agent-core`；当前目标是稳定薄内核 [B10]。

### 建议目录

```text
agent-core/src/main/java/site/pplee/jcode/agentcore/
├── Agent.java
├── AgentConfig.java
├── AgentState.java
├── AgentLoop.java                 # 实现保持 package-private
├── LoopState.java                 # package-private
├── message/
│   ├── AgentMessage.java          # 开放接口，可由产品层实现
│   ├── StandardAgentMessage.java  # 包装 ai.message.Message
│   ├── ContextTransformer.java
│   └── MessageProjector.java
├── event/
│   ├── AgentEvent.java
│   └── AgentEventSink.java
├── tool/
│   ├── AgentTool.java
│   ├── ToolExecutionMode.java
│   ├── ToolExecutionResult.java
│   ├── ToolUpdateSink.java
│   ├── BeforeToolCall.java
│   └── AfterToolCall.java
├── turn/
│   ├── TurnSnapshot.java
│   └── NextTurnControl.java
├── queue/
│   ├── PendingMessageQueue.java
│   └── QueueMode.java
└── concurrent/
    └── CancellationSource.java
```

`AgentLoop` 暂不因“可能有人直接调用”而扩大公开接口。等出现第二个真实调用者，再从实现中提炼公开的低层 interface；这比先发布一个假想 seam 更稳定。

### Java 中的可扩展消息

TypeScript 可以用 declaration merging 扩展 `AgentMessage`，Java 不具备该机制。采用以下等价设计：

```text
agent-core.AgentMessage（开放接口）
  ├── StandardAgentMessage(ai.Message)
  └── coding-agent 自己定义的消息类型
```

默认 `MessageProjector` 只展开 `StandardAgentMessage`。产品层可替换 projector，将自己的消息过滤或投影成 `ai.Message`。这样：

- `ai` 不依赖 `agent-core`；
- `agent-core` 不依赖 `coding-agent`；
- 产品消息仍保持强类型；
- 不使用 `Map<String, Object>` 或无约束 `Object` 作为 transcript。

## 3.3 `coding-agent`：产品内核（未来）

**创建条件**：开始实现第一个 coding 产品能力时创建。

### 负责

- Session、分支和持久化策略；
- Compaction；
- System Prompt / AGENTS.md 装配；
- coding 工具定义与实现；
- Extension、Skill、Prompt Template；
- 配置、项目信任与产品级权限策略；
- 将产品消息投影为 `ai.Message`；
- 订阅 `AgentEvent` 完成持久化、UI 适配和审计。

### 依赖

```text
coding-agent -> agent-core
coding-agent -> ai
coding-agent -> tui      # 只有终端产品需要时
```

不提前把 `session`、`compaction`、`extensions` 拆成 Maven 模块。pi 的判断标准是“是否存在只使用该部分的独立使用者”，而不是“它是否有一个清晰领域名称” [B2]。

## 3.4 `tui`：独立 UI 基础库（未来）

创建条件：开始实现终端 UI，且该 UI 引擎能脱离 coding-agent 独立使用。

- 零 Jcode 内部依赖；
- 不认识 `Agent`、模型或工具；
- `coding-agent` 通过事件适配它。

这对应 pi-tui 的独立消费者和零内部依赖 [B2][P-PACKAGES]。

## 3.5 `ai-provider-*`：Java 特有的可选 Adapter（未来）

pi 通过 provider 子路径、lazy import 和 tree-shaking 避免无关 SDK 被加载 [B4]。Maven 依赖没有相同的 tree-shaking 语义，因此 Java 更适合：

```text
ai-provider-openai     -> ai
ai-provider-anthropic  -> ai
ai-provider-google     -> ai
```

约束：

- 每个 adapter 显式返回 `Provider`；
- composition root 显式加入 `Models`；
- 不用静态注册器或隐式 `ServiceLoader` 扫描；
- 第一个真实 provider 开始实现时再创建，不在本轮创建空模块。

如果将来确实需要“一键启用所有 provider”，再新增只负责显式组装的 `ai-providers-all`；它不是核心依赖。

## 3.6 其他候选模块的创建门槛

| 候选模块 | 当前决策 | 何时才拆 |
|---|---|---|
| `session-core` | 不创建 | Session 出现 coding-agent 之外的独立消费者或第二种产品内核 |
| `storage-sqlite` | 不创建 | 已有稳定 Session interface，且 SQLite 是可选 adapter |
| `extensions` | 不创建 | Extension host 被多个产品独立复用 |
| `tools` | 不创建 | 具体工具集被非 coding-agent 产品直接复用 |
| `server` | 不创建 | 真正实现常驻服务宿主时；只依赖 coding-agent |
| `evals` | 不创建 | 有独立评测运行和依赖集合时，保持 private |
| `common` / `shared` | 禁止 | 不以“消除重复”为理由建立无明确使用者的杂物模块 |

## 4. 当前类型迁移归属

| 当前 `agent-core` 类型 | 目标归属 | 说明 |
|---|---|---|
| `ModelRef` | `ai` | 增加独立 `api` 维度；provider/api/model 不能绑死 [B4] |
| `Content` | `ai` | 标准模型内容；补 image 与流式所需元数据 |
| `StopReason` | `ai` | model stream 的标准终止协议 |
| `AgentMessage.User/Assistant/ToolResult` | 标准部分迁至 `ai` | `agent-core` 新增开放 `AgentMessage` + 标准消息包装 |
| `LlmRequest` | `ai` | 改为 provider-neutral `ModelRequest` / `Context` |
| `LlmClient` | `ai` | 模型调用 seam 属于统一调用层 |
| `LlmEventSink` | 删除 | 由一等 `AssistantMessageStream` 取代 |
| `CancellationToken` | `ai` | model adapter 与 AgentTool 共享只读取消协议 |
| `CancellationSource` | `agent-core` | active run 拥有取消权 |
| `AgentTool` | `agent-core` | 组合 `ai.ToolSpec`，增加执行与更新能力 |
| `ToolResult` | `agent-core` | 工具执行期结果；最终标准 ToolResultMessage 属于 `ai` |
| `ToolExecutionMode` | `agent-core` | Agent 运行时调度语义 |
| `AgentContext` / `LoopResult` | `agent-core` | Agent 运行态与一次 run 的结果 |
| `AgentEvent` / `AgentEventSink` | `agent-core` | 高层生命周期事件 |
| queue 类型 | `agent-core` | steering/follow-up 是 Agent 语义 |
| `Agent` / `AgentConfig` / `AgentLoop` | `agent-core` | 状态壳与循环实现 |

迁移应一次性完成，不在两个模块长期维护重复的 Message / Content / StopReason 类型，否则 provider 和 Agent 会形成两套事实来源。

## 5. 本轮只实施 1–5 的波次

## Wave 0：建立模块 seam

1. 根 reactor 顺序改为：`ai` → `agent-core`。
2. 新建最小 `ai` 模块，不加入任何 provider SDK。
3. `agent-core` 增加唯一 Jcode 内部依赖 `ai`。
4. 按上一节迁移标准模型与调用协议。
5. 增加构建检查，禁止 `ai -> agent-core` 和 `agent-core -> coding-agent/provider-*`。

完成标准：现有最小工具闭环迁移后仍通过；仓库中只有一套标准 LLM message 类型。

## Wave 1：流式协议与 Agent 实时状态

实现：

- `AssistantMessageStream` 与完整 assistant 事件；
- Agent 层 `message_start/update/end`；
- `AgentState.streamingMessage`、`pendingToolCalls`、`errorMessage`；
- Agent event reducer：先归约状态，再按注册顺序等待 listener；
- error / aborted 通过流终止事件产生最终 AssistantMessage。

测试：

- 文本、thinking、tool call 的 start/delta/end 顺序；
- partial assistant 随事件推进；
- listener 看到的状态已完成归约；
- `agent_end` listener 完成前 run 不算 settled；
- provider 运行期错误不使 run future 异常失败。

## Wave 2：工具声明和三阶段执行

实现：

- `ai.ToolSpec`：name、description、JSON Schema；
- `AgentTool`：参数预处理、强类型映射、execute、进度更新；
- prepare：查找 → prepareArguments → schema validate → `beforeToolCall`；
- execute：异常转 error outcome，settle 后忽略迟到 update；
- finalize：`afterToolCall` 字段级 patch；
- `terminate` 只保留在运行时结果，不进入标准 LLM transcript。

测试：

- schema 失败不执行工具；
- before hook 可阻止；
- after hook 可替换 content/details/error/terminate；
- 工具增量实时到达；
- settle 后增量被忽略；
- 任一 sequential 工具令整批串行。

## Wave 3：Context 投影 seam

实现：

```text
ContextTransformer: AgentMessage[] -> AgentMessage[]
MessageProjector:   AgentMessage[] -> ai.Message[]
```

约束：

- 每次模型调用前依次执行；
- 不修改持久 transcript，只生成本次请求视图；
- 默认 projector 展开标准消息并过滤未知产品消息；
- projector 输出必须满足模型消息结构。

测试：

- transform 必须先于 projector；
- 产品消息可过滤或投影；
- 原始 AgentContext 不被裁剪；
- hook 失败的生命周期和错误归一策略明确。

## Wave 4：下一 Turn 控制点

实现两个稳定 seam：

- `prepareNextTurn`：替换下一 turn 的 context / model / thinking level；
- `shouldStopAfterTurn`：当前 assistant 与工具正常完成后优雅停止。

严格顺序：

```text
TurnCompleted
-> prepareNextTurn
-> shouldStopAfterTurn
-> steering drain
-> follow-up drain（仅原本要结束时）
```

测试：

- 新模型/context 只影响下一次请求；
- stop 在 steering/follow-up drain 前生效；
- hook 收到完整 assistant、按源顺序的工具结果、当前 context 和 newMessages；
- 取消信号传入 turn hook。

## Wave 5：并行工具的双排序契约

固定：

```text
ToolExecutionCompleted event -> 实际完成顺序
ToolResult message            -> assistant 中的源顺序
TurnCompleted.toolResults     -> assistant 中的源顺序
```

prepare 与 `tool_execution_start` 始终按源顺序；execute/finalize 可并行。该拆分服务两个消费者：UI 需要尽快看到完成结果，模型与存储需要确定性 transcript [B9][P-LOOP]。

## 6. 稳定 `agent-core` 的验收条件

完成 Wave 0–5 后才冻结第一版 public interface：

1. `agent-core` 只有一个 Jcode 内部依赖：`ai`。
2. `ai` 不含 Agent、Session、coding 工具或 UI 类型。
3. `agent-core` 不含 provider SDK、认证、Session、Compaction、Extension、Skill 或具体 coding 工具。
4. model stream、Agent event、tool pipeline 的顺序与错误模式有契约测试。
5. 并行执行的完成事件顺序与 transcript 顺序分别有确定性测试。
6. 所有运行期模型失败都能形成最终 assistant failure message；工具失败形成 tool-result error message。
7. 所有可变状态集中于 run state / Agent state，公开快照不泄漏可变集合。
8. 不存在静态可变 provider、tool 或 hook 注册表。
9. 文档明确每个回调的调用时机、是否等待、错误行为和取消行为。
10. `mvn verify` 从根 reactor 验证 `ai -> agent-core` 的构建顺序和全部测试。

## 7. 明确拒绝的划分

### 拒绝：直接把当前 `agent-core` 改名为 `ai`

当前模块同时包含模型协议、循环、队列和状态壳。改名不产生 seam，只是移动混合职责。

### 拒绝：为每个概念建立 Maven 模块

`message-core`、`event-core`、`tool-core`、`queue-core` 没有独立使用者，会让调用方学习的接口数量接近实现复杂度。

### 拒绝：先做 `AgentHarness`

Session、Compaction、Skill、ExecutionEnv 会立即扩大并拖慢 `agent-core` 的稳定。pi 当前源码也明确表明，厚 Harness 与薄 Agent 是并行路径，生产 coding-agent 仍使用薄 Agent [B10]。

### 拒绝：让 `LlmClient` 私下转换上下文

这会使 compaction、产品消息过滤与 provider adapter 耦合，多个 adapter 重复实现同一策略。转换 seam 必须在 Agent Loop 的模型调用入口统一执行 [B8]。

### 拒绝：通过全局注册表发现 provider

显式 `Models` 值对象比全局注册表更容易隔离会话、测试和凭证状态 [B4]。

## 8. 来源

### pi-book

- [B2] [第 2 章：包不是项目](https://zhanghandong.github.io/pi-book/ch02-packages.html) — 分包依据、依赖图、独立使用者原则。
- [B4] [第 4 章：Provider 不是 Adapter](https://zhanghandong.github.io/pi-book/ch04-provider-registry.html) — 显式 Models 集合、core-only 入口、去全局注册表。
- [B6] [第 6 章：统一事件流设计](https://zhanghandong.github.io/pi-book/ch06-event-stream.html) — stream/result 统一、错误进入事件协议。
- [B8] [第 8 章：agentLoop](https://zhanghandong.github.io/pi-book/ch08-agent-loop.html) — 无状态双层循环、消息投影、turn 控制点。
- [B9] [第 9 章：工具执行](https://zhanghandong.github.io/pi-book/ch09-tool-execution.html) — prepare/execute/finalize 与并行双排序。
- [B10] [第 10 章：Agent](https://zhanghandong.github.io/pi-book/ch10-agent-class.html) — 有状态壳、事件归约、薄 Agent 与厚 Harness。
- [B12] [第 12 章：Compaction](https://zhanghandong.github.io/pi-book/ch12-compaction.html) — 为什么压缩属于产品层。
- [B30] [第 30 章：极简核心，能力外置](https://zhanghandong.github.io/pi-book/ch30-minimal-core.html) — 内核只做调模型、跑循环、管状态。
- [B31] [第 31 章：反主流选择](https://zhanghandong.github.io/pi-book/ch31-contrarian-choices.html) — 可组合能力不进入内核。

### pi 0.82.1 源码（固定 commit）

- [P-PACKAGES] [各包直接依赖](https://github.com/earendil-works/pi/tree/cee5ff7520d8828bed9955ef00419e995d1f91e0/packages)；重点见 [agent/package.json](https://github.com/earendil-works/pi/blob/cee5ff7520d8828bed9955ef00419e995d1f91e0/packages/agent/package.json) 与 [coding-agent/package.json](https://github.com/earendil-works/pi/blob/cee5ff7520d8828bed9955ef00419e995d1f91e0/packages/coding-agent/package.json)。
- [P-AI-TYPES] [`packages/ai/src/types.ts`](https://github.com/earendil-works/pi/blob/cee5ff7520d8828bed9955ef00419e995d1f91e0/packages/ai/src/types.ts) — Model、Message、Context、Tool 与 stream 事件。
- [P-LOOP] [`packages/agent/src/agent-loop.ts`](https://github.com/earendil-works/pi/blob/cee5ff7520d8828bed9955ef00419e995d1f91e0/packages/agent/src/agent-loop.ts) — 双层循环、流消费、三阶段工具执行、turn 控制。
- [P-AGENT] [`packages/agent/src/agent.ts`](https://github.com/earendil-works/pi/blob/cee5ff7520d8828bed9955ef00419e995d1f91e0/packages/agent/src/agent.ts) — 状态壳、队列、取消、事件归约。

### Jcode 当前基线

- [`archived/java21-agent-loop-minimal-plan.md`](archived/java21-agent-loop-minimal-plan.md)
- `../../agent-core/src/main/java/site/pplee/jcode/agentcore`
