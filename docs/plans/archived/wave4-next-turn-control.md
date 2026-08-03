# Wave 4：下一 Turn 控制点实施计划

> 状态：已完成
> 上位提案：[`../pi-inspired-module-boundaries.md`](../pi-inspired-module-boundaries.md)
> 实施范围：`ai` 的 thinking 请求协议，以及 `agent-core` 的 turn 间更新与优雅停止

## 1. 目标

在每个正常完成的 model turn 与下一次循环调度之间增加两个稳定 seam：

```text
TurnCompleted
  -> PrepareNextTurn
  -> apply context/model/thinking update
  -> ShouldStopAfterTurn
  -> steering drain
  -> follow-up drain（仅原本要结束时）
```

完成后应满足：

1. 上层可以在同一次 run 内替换后续 turn 使用的 `AgentContext`、`ModelRef` 和 thinking level。
2. 上层可以在当前 assistant 与工具批次完整结束后优雅停止 run。
3. `STOP` 在 post-turn steering/follow-up drain 前生效，不取消已经运行的 provider stream 或工具。
4. 两个 hook 都获得不可变快照和只读取消信号：`PrepareNextTurn` 收到包含完整 turn 的 pre-update 快照；`ShouldStopAfterTurn` 收到 post-update 快照，若 replacement 裁剪 transcript 则可能不含本 turn 消息。
5. hook 失败沿用 runtime 的 terminal assistant 归一策略，不使公开 run future 异常失败。
6. `ai` 继续保持零 Jcode 内部依赖，`agent-core` 继续只依赖 `ai`。

设计参照：

- `docs/references/pi/packages/agent/src/agent-loop.ts`
- `docs/references/pi/packages/agent/src/types.ts`
- `docs/references/pi/packages/agent/src/agent.ts`
- `docs/references/pi-book/src/ch08-agent-loop.md`

采用其“turn 完成后先更新、再判断停止、最后轮询队列”的控制顺序；不照搬 TypeScript 的可变 config、双 `prepareNextTurn` 入口或 `undefined` 多义语义。

## 2. 非目标

本波次不实现：

- provider adapter 或 thinking level 的厂商映射；
- model capability catalog、thinking level clamp 或自动降级；
- 自动 retry、compaction 算法、session 持久化或 checkpoint；
- 在 turn update 中替换 `ModelClient`；
- model/thinking update 跨独立 run 的持久化；
- 新的 `AgentEvent` 变体；
- Wave 5 的并行工具完成顺序与 transcript 源顺序拆分；
- `AgentConfig` builder 或通用 request-options map。

仅需 request-local 裁剪或注入时，继续使用 Wave 3 的 `ContextTransformer`，而不是持久替换 `AgentContext`。

## 3. Public interface 决策

### 3.1 Provider-neutral `ThinkingLevel`

在 `ai.model` 新增绝对请求值：

```java
public enum ThinkingLevel {
    PROVIDER_DEFAULT,
    OFF,
    MINIMAL,
    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
    MAX
}
```

语义：

- `PROVIDER_DEFAULT`：adapter 不主动指定 thinking/reasoning 参数，保持 provider 默认；
- `OFF`：明确请求关闭 thinking；若 provider/model 无法满足，由 adapter 映射或通过错误事件拒绝；
- 其余值：provider-neutral 的相对推理强度；
- enum 不包含 `KEEP`，因为每个 `ModelRequest` 必须携带自包含的绝对值。

`ModelRequest` 增加 `ThinkingLevel thinkingLevel`。保留原四参数构造器并委托到 `PROVIDER_DEFAULT`，避免现有调用点和已编译消费者因构造器签名变化立即失效。

### 3.2 `TurnContext`

在 `agentcore.turn` 新增不可变 turn 快照：

```java
public record TurnContext(
        Message.Assistant assistant,
        List<Message.ToolResultMessage> toolResults,
        AgentContext context,
        List<AgentMessage> newMessages
) {}
```

契约：

- `assistant` 是刚完成且非 terminal failure 的 assistant；
- `toolResults` 是该 assistant 对应的最终工具结果，顺序与 assistant 中 tool call 的源顺序一致；
- `context` 是 hook 调用时刻的权威 run context：`PrepareNextTurn` 的 pre-update 快照包含该 assistant 和全部工具结果；`ShouldStopAfterTurn` 的 post-update 快照可能已被 replacement 裁剪，不保证包含本 turn 消息；
- `newMessages` 是本次 run 到当前为止产生并发出生命周期事件的消息日志；
- 两个列表均执行 `List.copyOf()`，所有字段拒绝 null。

### 3.3 `PrepareNextTurn`

```java
@FunctionalInterface
public interface PrepareNextTurn {
    CompletionStage<NextTurnUpdate> prepareNextTurn(
            TurnContext turn,
            CancellationSignal cancellation
    );

    static PrepareNextTurn noop() { ... }
}
```

loop 等待 stage 完成。默认实现返回 `NextTurnUpdate.keep()`。

### 3.4 `NextTurnUpdate`

```java
public record NextTurnUpdate(
        Optional<AgentContext> context,
        Optional<ModelRef> model,
        Optional<ThinkingLevel> thinkingLevel
) {}
```

这里的 `Optional.empty()` 只表示“本次 update 保持原值”。因此 thinking 具有明确的三类相对操作：

```text
Optional.empty()                    -> KEEP
Optional.of(PROVIDER_DEFAULT)       -> RESET TO PROVIDER DEFAULT
Optional.of(OFF / LOW / HIGH / ...) -> SET ABSOLUTE LEVEL
```

record 组件及 Optional 内值都不得为 null。提供 `keep()` 静态工厂，不使用 null update 或多层 nullable 表达补丁。

### 3.5 `ShouldStopAfterTurn`

```java
@FunctionalInterface
public interface ShouldStopAfterTurn {
    CompletionStage<Decision> shouldStopAfterTurn(
            TurnContext turn,
            CancellationSignal cancellation
    );

    static ShouldStopAfterTurn never() { ... }

    enum Decision { CONTINUE, STOP }
}
```

`Decision` 避免 boxed `Boolean` 的 null 结果。默认始终 `CONTINUE`。

`CONTINUE` 表示允许既有 scheduler 继续判断，不强制产生下一次模型请求；如果没有剩余工具续轮、steering 或 follow-up，run 仍自然结束。

`STOP` 表示：

- 当前 assistant 与工具已完整完成；
- 不再发起自动模型续轮；
- 不 drain post-turn steering/follow-up；
- 不修改 assistant 的 `StopReason`；
- 通过正常 `AgentCompleted` 结束，不等同于 `abort()`。

停止条件由上层显式提供，例如单次 run 预算、context 水位、graceful shutdown 或业务完成策略；`agent-core` 不内置产品判断。

## 4. 配置与兼容性

`AgentConfig` 增加：

```java
ThinkingLevel thinkingLevel,
PrepareNextTurn prepareNextTurn,
ShouldStopAfterTurn shouldStopAfterTurn
```

默认值：

```text
thinkingLevel       -> PROVIDER_DEFAULT
prepareNextTurn     -> PrepareNextTurn.noop()
shouldStopAfterTurn -> ShouldStopAfterTurn.never()
```

保留当前十二参数 `AgentConfig` 构造器，委托新 canonical constructor。新增字段会改变 record metadata、`equals/hashCode/toString`，但不删除现有构造入口。

package-private `AgentLoopConfig` 携带相同初值和 hook；保留旧参数构造器以减少现有低层测试的机械改动。

不修改 `AgentState`、`LoopResult` 或 `AgentEvent` 的 record component。

## 5. Run 状态所有权

`LoopState` 扩展为本次 run 的唯一可变 turn 状态持有者：

```text
AgentContext context
ModelRef model
ThinkingLevel thinkingLevel
List<AgentMessage> newMessages
```

`AgentLoopConfig` 仅提供初值和稳定依赖，不在循环内重建或修改 config。

`NextTurnUpdate` 必须作为一个补丁原子应用：完成全部 null/Optional 校验后，才同时替换 `LoopState` 中出现的字段。后续行为全部读取新状态：

- `ContextTransformer` / `MessageProjector` 输入；
- `ModelRequest.model`、system prompt、messages、tools、thinking level；
- 后续工具解析和执行；
- steering/follow-up 消息追加；
- 最终 `LoopResult.context()`。

model/thinking 只在当前 run 的后续 turn 中保持。下一次独立 `prompt()`/`continueRun()` 重新从 `AgentConfig` 初值开始。context 属于 transcript，因此通过 `LoopResult.context()` 持久更新到 `Agent.context()`。

切换 `ModelRef` 时复用当前 `ModelClient`。需要跨 provider/api 路由的调用方应提供可处理多个 `ModelRef` 的 routing client。

## 6. Context replacement 与 `newMessages`

replacement context 定义为权威、持久的 run context。它可以重写 system prompt、tools 和完整消息历史，也可以裁剪本次 run 已产生的早期消息。

`newMessages` 定义为本次 run 的追加日志：只记录 runtime 实际完成并发出消息生命周期事件的消息。context replacement 不重写该日志。

因此允许：

```text
LoopResult.newMessages() 不是 LoopResult.context().messages() 的后缀或子集
```

这一区分服务两个消费者：

- `context`：下一次模型请求和 Agent 后续运行需要的权威状态；
- `newMessages`：本次调用产生了哪些消息的增量观察结果。

后续 pending message、assistant 和 tool result 仍同时 append 到 replacement context 与 `newMessages`。

## 7. 精确运行顺序

每个正常 turn：

```text
invoke model
-> append completed assistant
-> execute/finalize tools
-> append tool results in source order
-> emit and await TurnCompleted
-> cancellation check
-> await PrepareNextTurn(pre-update TurnContext, signal)
-> validate update
-> cancellation check
-> atomically apply update to LoopState
-> build post-update TurnContext
-> await ShouldStopAfterTurn(post-update TurnContext, signal)
-> cancellation check
-> STOP ? complete run : drain steering
-> if no tool continuation and no steering: drain follow-up
```

关键语义：

1. `PrepareNextTurn` 看到 update 前、已包含完整 turn 的 pre-update context。
2. `ShouldStopAfterTurn` 看到 update 后的 post-update context；replacement 裁剪 transcript 时该 context 可能不含本 turn 消息。
3. `TurnCompleted` sink 已完成后才调用 hook。
4. `STOP` 在任何 post-turn queue drain 前生效，队列内容保留。
5. run 启动时已有的 initial steering poll 保持现有行为；“stop before drain”约束针对本 turn 完成后的 poll。
6. assistant 为 `ERROR`/`ABORTED` 时，沿现有 terminal failure 路径立即结束，不调用 turn hook。
7. `ToolExecutionResult.terminate` 保持工具所有权语义；`ShouldStopAfterTurn` 是宿主所有权语义，两者不互相替代。

## 8. 失败与取消

turn hook 属于 runtime 控制阶段。以下失败统一归一：

- 同步抛出 `RuntimeException`；
- 返回 exceptional stage；
- 返回 null stage、null result 或非法 update；
- `ShouldStopAfterTurn` 返回 null decision。

结果：

- 未取消时生成 terminal `ERROR` assistant；
- 已取消时生成 terminal `ABORTED` assistant；
- 保留此前正常完成的 assistant、工具结果和已经成功应用的 replacement context；
- 不 drain steering/follow-up；
- `LoopResult` 正常返回，公开 future 不异常失败。

由于原 turn 已经 `TurnCompleted`，hook failure 作为合成的终止 turn 发出：

```text
TurnStarted
-> MessageStarted(failure assistant)
-> MessageCompleted(failure assistant)
-> TurnCompleted(failure assistant, [])
-> AgentCompleted
```

取消是协作式的：

- hook 获得本次 run 的只读 `CancellationSignal`；
- loop 在调用前和 stage settle 后检查取消；
- hook 忽略取消且 stage 永不完成时，run 仍会等待，与现有 `ContextTransformer` 契约一致；
- cancellation 与 `STOP` 同时发生时 cancellation 优先；
- event sink 异常保持现有传播行为，不被 hook 失败归一逻辑吞掉。

## 9. TDD 纵向切片

测试 seam 以公开接口为主：

- `ai.client.ModelRequest`；
- `AgentConfig -> Agent.prompt()/continueRun()`；
- `AgentEventSink` 和测试 `ModelClient` 作为可观察输出。

package-private `AgentLoop` 测试只补严格低层时序，不把私有 helper 作为测试接口。

### Slice 1：Thinking 请求协议

1. 红：四参数 `ModelRequest` 默认为 `PROVIDER_DEFAULT`；显式值被保留。
2. 绿：新增 enum、record component 和兼容构造器。
3. 验证 `ai` 模块独立测试。

### Slice 2：Next-turn update

1. 红：首个请求使用初始 context/model/thinking，第二个请求使用 hook update。
2. 绿：新增 turn 类型、配置装配与 `LoopState` 状态。
3. 断言 update 不追溯影响当前已完成请求。

### Slice 3：Context replacement

1. 红：`ShouldStopAfterTurn` 看到 replacement context；后续请求和最终 `Agent.context()` 使用 replacement。
2. 绿：原子应用 update。
3. 断言 `newMessages` 保留被 replacement 裁掉的本 run 消息日志。

### Slice 4：Graceful stop 与队列顺序

1. 红：turn 中新入队的 steering/follow-up 在 `STOP` 后不被消费。
2. 绿：将 stop 判断放在 drain 前。
3. 断言无额外 model call、原 assistant stop reason 不变、`AgentCompleted` 仅一次。

### Slice 5：Payload、等待、取消与失败

覆盖：

- hook 收到完整 assistant、源顺序工具结果、context、newMessages；
- `TurnCompleted` sink 先于两个 hook；
- async stage 被等待；
- cancellation signal 传入 hook，取消优先；
- prepare/stop hook 的同步异常、exceptional/null 输出归一；
- terminal model failure 不调用 hook；
- 默认 hook 完全保持 Wave 0-3 行为。

## 10. 预计改动文件

### 新增

- `ai/src/main/java/site/pplee/jcode/ai/model/ThinkingLevel.java`
- `agent-core/src/main/java/site/pplee/jcode/agentcore/turn/TurnContext.java`
- `agent-core/src/main/java/site/pplee/jcode/agentcore/turn/NextTurnUpdate.java`
- `agent-core/src/main/java/site/pplee/jcode/agentcore/turn/PrepareNextTurn.java`
- `agent-core/src/main/java/site/pplee/jcode/agentcore/turn/ShouldStopAfterTurn.java`
- `agent-core/src/test/java/site/pplee/jcode/agentcore/NextTurnControlTest.java`

### 修改

- `ai/.../client/ModelRequest.java`
- `ai/.../client/ModelClient.java`（Javadoc）
- `ai/src/test/.../AiModuleTest.java`
- `agent-core/.../AgentConfig.java`
- `agent-core/.../AgentLoopConfig.java`
- `agent-core/.../LoopState.java`
- `agent-core/.../AgentLoop.java`
- `agent-core/.../Agent.java`
- `agent-core/.../LoopResult.java`（澄清 context/newMessages 契约）
- 必要的现有测试 helper/构造调用点
- `docs/architecture/src/00-overview.md`
- 根、`ai`、`agent-core` 的 `AGENTS.md`

## 11. 验证

逐层执行：

```bash
mvn -pl ai test
mvn -pl agent-core -am test
mvn verify
mdbook build docs/architecture
```

若本机未安装 mdBook，记录该项未执行及原因，但 Maven reactor 必须通过。

验收标准：

1. 新增测试覆盖严格时序、更新作用域、停止、取消和失败归一。
2. Wave 0-3 全部测试保持通过。
3. `ModelRequest` 是自包含 provider-neutral 请求，不出现通用 options map。
4. 可变 turn 状态集中于 `LoopState`。
5. 不新增 Jcode 模块依赖或 provider SDK。
6. 代码与非文档配置不显式提及设计参照项目。
7. 实施完成后，将本文档移入 `docs/plans/archived/` 并更新状态为“已完成”。
