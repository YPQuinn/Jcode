# Wave 5：并行工具双排序实施计划

> 状态：已完成（Wave 5 实现已落地并通过全部验证）
> 上位提案：[`../pi-inspired-module-boundaries.md`](../pi-inspired-module-boundaries.md)
> 实施范围：`agent-core` 的工具 prepare/execute/finalize 调度、完成事件排序、transcript 排序与实时状态归约

## 1. 目标

固定并行工具批次的双排序契约：

```text
ToolStarted / prepare             -> assistant 中的 tool call 源顺序
execute / finalize                -> 可并行
ToolCompleted                     -> execute + finalize 的实际完成顺序
Tool-result MessageStarted/MessageCompleted -> assistant 中的 tool call 源顺序
AgentContext transcript           -> assistant 中的 tool call 源顺序
TurnCompleted.toolResults         -> assistant 中的 tool call 源顺序
```

这里的 Jcode 事件名是 `AgentEvent.ToolCompleted`；上位提案中的
`ToolExecutionCompleted` 表示同一语义，不新增或重命名公开事件类型。

完成后应满足：

1. UI 可以在每个工具真正完成后尽快收到 `ToolCompleted`，不等待更早的慢工具。
2. 模型请求、持久 transcript、turn hook 和回放看到稳定的源顺序工具结果。
3. `ToolStarted` 与完整 prepare 阶段严格按源顺序运行，`BeforeToolCall` 不发生并发竞态。
4. execute 与 finalize 保持并行能力，任一工具声明 `SEQUENTIAL` 时整批仍降级串行。
5. 并行生命周期事件不会使 `AgentState.pendingToolCalls` 发生 lost update。
6. execute stage settle 前已接纳的 `ToolUpdate` 必须投递完成，之后才能 finalize 和发 `ToolCompleted`。
7. sink 或任务异常发生后，已提交工具任务在 `runBatch()` 返回或抛出前全部结算，不泄漏到后续 run。
8. 不扩大 public interface，不改变 `ai -> agent-core` 的模块依赖方向。

设计参照：

- `docs/references/pi/packages/agent/src/agent-loop.ts`
- `docs/references/pi/packages/agent/test/agent-loop.test.ts`
- `docs/references/pi-book/src/ch09-tool-execution.md`

采用其“串行 prepare、并行 execute/finalize、完成事件按完成顺序、tool-result 工件按源顺序”的设计意图；不照搬 JavaScript 单线程事件循环与 `Promise.all` 的字面实现。

## 2. 当前基线与缺口

当前 `ToolCallExecutor.runParallel()` 的时序是：

```text
for source call:
  emit ToolStarted
  submit runOne(prepare + execute + finalize)

await all futures

for source future:
  join outcome
  emit ToolCompleted
```

它已经保证：

- `ToolStarted` 的调用顺序是源顺序；
- `AgentLoop.recordOutcomes()` 按 `List<ToolOutcome>` 顺序发 tool-result 消息并写入 context；
- `TurnCompleted.toolResults` 使用同一个 outcome 列表；
- 任一 `SEQUENTIAL` 工具令整批串行；
- `LENGTH` tool call 不执行并按源顺序失败；
- 并行 outcome 在写回前恢复源顺序。

但仍有三个 Wave 5 缺口：

1. `runOne()` 被整体提交，导致 prepare 也在 worker 中并行执行。
2. `ToolCompleted` 在 `allOf()` 后按 future 源顺序回放，不代表实际完成顺序，也不能及时服务 UI。
3. 提前投递 `ToolCompleted` 后，它会与其他 worker 的 `ToolUpdate` 并发归约；当前 `volatile AgentState` 的 read-modify-write 不是原子的，可能把已移除的 pending tool call 写回。

## 3. 核心排序定义

### 3.1 源顺序

源顺序是 `AgentLoop.extractToolCalls()` 从 assistant content 中提取 tool call 的顺序。每个进入工具生命周期的 call 获得稳定 source index。

以下数据必须按 source index 排列：

- `ToolCallExecutor.runBatch()` 返回的 `List<ToolOutcome>`；
- tool-result `MessageStarted` / `MessageCompleted`；
- `LoopState` / `AgentContext` 中的 tool-result message；
- 下一次 `ModelRequest.messages()` 中的 tool-result message；
- `TurnCompleted.toolResults`；
- `TurnContext.toolResults`。

### 3.2 完成顺序

对于成功通过 prepare 的调用，“完成”定义为：

```text
tool.execute stage settled
-> ToolUpdateSink 关闭接纳并等待已接纳 update 投递完成
-> AfterToolCall settled
-> final ToolOutcome created
```

完成任务进入 completion queue 的顺序，就是 `ToolCompleted` 的投递顺序。`ToolCompleted` 发出时 transcript 尚未按源顺序写回，因此其 Javadoc 不得继续声称结果“已经 appended”。

如果多个 worker 在不可区分的同一时刻完成，completion queue 的入队顺序构成该 run 内可观察的总顺序；测试通过 latch 明确制造先后，不依赖线程调度概率。

### 3.3 Immediate prepare failure

未知工具、`prepareArguments` 失败、schema 失败、`BeforeToolCall` 阻止或参数转换失败属于 immediate outcome。其固定时序是：

```text
ToolStarted(call N)
-> prepare(call N)
-> ToolCompleted(failure N)
-> ToolStarted(call N + 1)
```

即 immediate `ToolCompleted` 在串行 prepare 循环内当场投递：

- happens-before 后续 call 的 `ToolStarted`；
- happens-before 所有 prepared task 的 `ToolCompleted`；
- 仍占据自己的 source index slot；
- 最终 transcript 不因其提前完成而改变源顺序。

## 4. Public interface 决策

本波次不新增、删除或重命名 public 类型、方法或 record component：

- 继续使用 `AgentEvent.ToolStarted` / `ToolUpdate` / `ToolCompleted`；
- 继续返回 `LoopResult`；
- 继续由 `AgentState.pendingToolCalls` 暴露 pending id；
- `Prepared`、completion entry、source index 和 `ToolOutcome` 保持 `ToolCallExecutor` 的内部实现细节；
- `AgentLoop` 仍只通过 `List<ToolOutcome>` 接收工具批次结果。

公开行为变化仅是 Wave 5 明确要求的事件时序：并行批次中的 `ToolCompleted` 从“全部完成后按源顺序回放”改为“每个 execute+finalize 完成后按实际完成顺序投递”。

## 5. 工具管道内部拆分

`ToolCallExecutor` 把当前 `runOne()` / `runTyped()` 拆为两个内部阶段。

### 5.1 `prepareCall`

按当前契约依次执行：

```text
find tool
-> AgentTool.prepareArguments
-> ToolSchemaValidator.validate
-> BeforeToolCall
-> ObjectMapper.treeToValue
```

返回内部判别结果：

```text
Prepared  -> 持有 call、tool、类型化 arguments、context/source index
Immediate -> 持有已经完成的 ToolOutcome/source index
```

Java 实现可以使用 private sealed interface + private record，或用封装泛型关系的 deferred callable；选择标准是：

- 不泄漏到 `AgentLoop`；
- 不使用 raw type；
- 保持 `AgentTool<A>` 与类型化参数 `A` 的关联；
- execute/finalize 尚未在 prepare 阶段启动。

### 5.2 `executeAndFinalize`

仅接收 `Prepared`，依次执行：

```text
AgentTool.execute
-> settle LoopToolUpdateSink
-> AfterToolCall
-> create ToolOutcome
```

错误归一矩阵固定为：

| 阶段 | 同步抛出 / failed stage | null stage / null result | 结果 |
| --- | --- | --- | --- |
| `prepareArguments` / schema / 参数转换 | immediate failure | immediate failure | 不执行工具 |
| `BeforeToolCall` | immediate failure | immediate failure | 不执行工具 |
| `AgentTool.execute` | error `ToolExecutionResult` | error `ToolExecutionResult` | 继续 finalize |
| `AfterToolCall` | 保留 execute 的原 result | 保留 execute 的原 result | 正常生成 outcome |
| event sink delivery | 基础设施异常 | 基础设施异常 | 不归一为 tool error，进入 drain-before-rethrow |

其中 event delivery failure 需要通过 package-private 异常标记与普通 tool/hook failure 区分，避免 `AgentTool.execute` 的兜底 catch 把 sink 故障错误编码成模型可重试的 tool result。

其余策略保持不变：

- `AfterToolCall` 失败保留原 result，不使工具任务以业务异常结束；
- `terminate` 只保留在 runtime `ToolExecutionResult`；
- transcript `Message.ToolResultMessage` 不携带 `terminate`。

### 5.3 `LoopToolUpdateSink` 结算

当前单个 volatile `settled` 检查无法覆盖“update 已通过接纳检查、但其 sink stage 仍阻塞”的 in-flight update。Wave 5 将其收敛为每个工具调用私有的 close-and-drain 协议：

1. `update()` 在短临界区内检查 accepting，并登记 in-flight 计数；不在持锁时调用 event sink。
2. 投递完成或失败后，在 finally 中减少 in-flight 计数并唤醒 settle waiter。
3. `settle()` 原子关闭 accepting，并等待所有已经登记的 update 完成。
4. 关闭后新到达的 late update 仍返回 completed stage 并静默丢弃。
5. 已接纳 update 的首个 delivery failure 被记录；全部 in-flight update drain 后由 `settle()` 重新抛出，使工具批次走基础设施失败路径。
6. 不建立跨工具 update 顺序；该协议只保证单个 tool call 的 `ToolUpdate* -> ToolCompleted` 生命周期。

等待使用 condition/latch 等阻塞同步，不忙等、不 `Thread.sleep()`。

## 6. 并行批次算法

使用 `ExecutorCompletionService<IndexedOutcome>` 将“完成顺序”和“源顺序”分成两条内部数据流。

伪代码：

```text
sourceEntries = []

for call with sourceIndex:
  if cancellation already observed:
    break

  emit ToolStarted(call)
  preparation = prepareCall(call, sourceIndex)

  if preparation is Immediate:
    emit ToolCompleted(preparation.outcome.message)
    sourceEntries.add(resolved immediate slot)
  else:
    sourceEntries.add(prepared slot)

resultSlots = slots sized to sourceEntries
completionService = new ExecutorCompletionService(executor)
submitted = 0

for sourceEntry:
  if immediate:
    resultSlots[index] = outcome
  else:
    submit executeAndFinalize(entry) -> IndexedOutcome(index, outcome)
    submitted++

drain submitted tasks in completion order:
  completed = completionService.take/get
  resultSlots[completed.index] = completed.outcome
  if no prior delivery/task failure:
    emit ToolCompleted(completed.outcome.message)

if first failure exists:
  rethrow after every submitted task has settled

return immutable outcomes from resultSlots in source order
```

关键不变量：

1. prepared task 只在串行 prepare pass 结束后开始提交。
2. completion queue 只承载 execute+finalize 已结束的 indexed outcome。
3. `ToolCompleted` 由控制 `runBatch()` 的 loop 线程按 completion queue 顺序串行投递；worker 不直接投递该生命周期事件。
4. `ToolUpdate` 仍从 worker 实时投递，不建立跨工具的全局 update 顺序。
5. `resultSlots` 只用于恢复源顺序，不用于决定完成事件顺序。
6. `runParallel()` 返回前所有 submitted task 都已 settle。

### 6.1 Prepare/submit 异常状态机

“正常或取消结算”与“基础设施失败”必须区分：

| 失败时点 | 已 prepared 但未提交 | 已成功提交 | 对外结果 |
| --- | --- | --- | --- |
| `ToolStarted` / immediate `ToolCompleted` delivery 失败 | 丢弃，不执行 | 此时尚无任务 | run exceptional；不伪造 tool result |
| 未归一的 prepare 基础设施异常 | 丢弃，不执行 | 此时尚无任务 | run exceptional |
| 第 N 个 task submit 被拒绝 | 当前及后续 entry 丢弃，不执行 | 全量 drain | drain 后传播首个 rejection |
| completion task / `ToolCompleted` delivery 失败 | 不适用 | 全量 drain | 停止后续 lifecycle emit，drain 后传播首个失败 |
| cooperative cancellation | 已进入 started/prepared 生命周期的前缀仍提交并结算 | 全量 drain | 正常 outcome + 既有 turn 后 ABORTED 路径 |

基础设施失败时不承诺每个已发 `ToolStarted` 都再有 `ToolCompleted`：sink 已失败或 executor 已拒绝继续执行，runtime 不制造虚假 transcript。`Agent.submit()` 的原子收尾必须清空 `pendingToolCalls`。真正的硬不变量是：

- 未提交 entry 从未执行；
- 已提交 task 在 run future settle 前全部 drain；
- run future settle 后没有该批次已接纳 update 或 worker 继续投递事件；
- 首个基础设施异常最终传播，不被 tool error 归一吞掉。

### 6.2 为什么不让 worker 直接发 `ToolCompleted`

参考实现可以在并发 thunk 内直接 emit，是因为 JavaScript 事件循环天然提供单线程调用语义。Java worker 直接 emit 会新增多个生命周期事件生产者：

- 用户 sink 可能被多个 `ToolCompleted` 并发调用；
- 慢 sink 会使“调用先后”和“观察完成先后”分裂；
- `AgentState` 的 pending set 归约更容易发生 lost update；
- 事件流不再有一个清晰的 completion-order 总序。

`ExecutorCompletionService` 保留“谁先完成谁先通知”的设计意图，同时让 `ToolCompleted` 保持 loop 线程单写者。

## 7. 串行与 LENGTH 路径

### 7.1 Sequential batch

以下任一条件成立时整批串行：

```text
AgentLoopConfig.toolExecution == SEQUENTIAL
OR
任一已知工具 executionMode == SEQUENTIAL
```

固定工具生命周期时序保持：

```text
for each source call:
  ToolStarted N
  -> prepare N
  -> execute N
  -> settle accepted updates
  -> finalize N
  -> ToolCompleted N

after the whole batch:
  tool-result messages are recorded in source order
```

即不新增“每个 tool-result message 必须在下一个 source call 前写回”的行为。串行路径复用新的 prepare / execute+finalize helper，但不使用 completion service；`AgentLoop.recordOutcomes()` 仍在完整批次返回后统一写回。

### 7.2 LENGTH batch

`StopReason.LENGTH` 继续走 `failTruncated()`：

- 所有已提取 tool call 按源顺序发 `ToolStarted -> ToolCompleted(failure)`；
- 不调用 prepare hook 或工具；
- tool-result messages 和 `TurnCompleted.toolResults` 按源顺序；
- 不进入并行 completion service。

## 8. AgentState 原子归约

Wave 5 允许一个工具完成时其他工具仍在发送 `ToolUpdate`。`Agent.reduceState()` 必须从 volatile read-modify-write 改为原子更新。

选择：

```text
AtomicReference<AgentState>
+ CAS reduction loop
```

而不是扩大事件投递锁。

实施要点：

1. `Agent.state` 字段改为 `AtomicReference<AgentState>`。
2. 构造器通过 `new AtomicReference<>(AgentState.initial(context))` 初始化。
3. `Agent.state()` 返回 `state.get()`。
4. `reduceState(event)`：
   - 从 `state.get()` 读取 immutable snapshot；
   - 纯函数计算 next snapshot；
   - 无状态变化的事件直接返回；
   - 有状态变化时通过 CAS 重试直到成功。
5. `submit()` 任务收尾的 streaming-state reset 改为原子 update，并从同一 snapshot 保留 `errorMessage`。
6. 归约完成后再调用用户 `AgentEventSink`，维持“用户 sink 收到事件时该事件已归约”的契约。

明确不做：

- 不在等待 sink stage 时持锁；
- 不把 CAS 扩大到用户 sink；
- 不将 `ToolUpdate` 与 `ToolCompleted` 放入全局 delivery queue；
- 不承诺并发 `ToolUpdate` 与其他工具生命周期事件进入用户 sink 的跨线程调用顺序，只承诺每个事件在委托前已完成自身状态归约；
- `AgentEvent` / `AgentEventSink` Javadoc 必须删除“所有观察者看到全局确定顺序”的过强表述，改为：`ToolCompleted` 彼此按 completion order 串行、单个 tool call 生命周期有序、跨工具 update/lifecycle 无全局总序；
- 用户 sink 在启用并行工具时必须支持并发调用，这一既有事实需要显式文档化。

## 9. 失败结算与 drain-before-rethrow

### 9.1 首个失败

正常工具、schema、hook 失败按 §5.2 矩阵归一；event delivery、executor rejection、interruption 等运行时基础设施失败保持 exceptional。

completion drain 记录第一个未归一异常：

- 已接纳 `ToolUpdate` 的 delivery failure（由 per-tool sink settle 重新抛出）；
- submitted task exceptional；
- `CompletionService.take/get` 的执行异常；
- `ToolCompleted` 投递失败；
- 提交中途的 `RejectedExecutionException`；
- 等待过程被中断。

首个失败被保留，次生失败不覆盖它。

### 9.2 Drain 规则

一旦出现首个失败：

1. 不再向已经失败或不可信的 sink 投递后续 `ToolCompleted`。
2. 未提交 prepared entry 丢弃且不执行；已成功提交的任务继续等待 settle。
3. 每个 submitted task 在完成前先关闭自己的 update sink，并 drain 已接纳 update。
4. 不把异常任务或未提交 entry 伪造成 tool-result message。
5. 全部 submitted task settle 后传播首个失败。
6. `runBatch()` 抛出后，不得再有该批次已接纳 update、worker 或 lifecycle event 到达 sink。

这会把异常的实际抛出时刻从“首次观察到失败”推迟到“工具批次全部 settle”，但不改变异常最终传播到公开 run future 的契约。该时序需要在 `ToolCallExecutor` Javadoc 中说明。

### 9.3 中断

等待 completion 时若线程被中断：

- 记录中断作为首个失败；
- 继续完成必要 drain；
- drain 后恢复线程 interrupt flag；
- 再传播失败。

不得用忙等或 `Thread.sleep()`；工具仍通过 `CancellationSignal` 协作取消。

### 9.4 慢或不合作的依赖

慢 sink、忽略取消的 provider/tool/hook 仍可能使 run 等待；这属于既有协作式取消与同步事件投递契约。Wave 5 不新增超时、强杀或重试策略。

## 10. 取消语义

保留现有 AgentLoop 取消边界，不引入新的 terminal message 规则。

并行 prepare pass：

1. 每个 call 的 `ToolStarted` 前检查 cancellation。
2. 已取消时不再为后续 source call 打开工具生命周期。
3. 在没有基础设施失败的 cooperative cancellation 路径中，已经 `ToolStarted` 且完成 prepare 的 entry 必须结算；即使取消发生在统一提交前，也仍以已取消的 `CancellationSignal` 进入 execute/finalize，使工具有机会协作结束。
4. 已产生的 immediate outcome 已经完成，无需重复处理。
5. 所有 started entries settle 后，outcome 仍按其 source index 写回。
6. 批次 `TurnCompleted` 后由既有 cancellation boundary 生成后续 `ABORTED` assistant；Wave 5 不提前篡改该生命周期。

这一区分避免两类错误：

- 取消后继续启动尚未进入生命周期的后续 call；
- 已发 `ToolStarted` 的 call 永远没有结算且残留在 `pendingToolCalls`。

## 11. TDD 纵向切片

测试 seam 优先使用：

- `AgentConfig -> Agent.prompt()` 的公开运行入口；
- `AgentEventSink` 观察事件顺序和归约后的 `Agent.state()`；
- 测试 `ModelClient` 观察下一次 `ModelRequest`；
- `LoopResult.context()` / `TurnCompleted.toolResults` 观察 transcript。

package-private `AgentLoop` 测试只补难以从公开门面稳定控制的严格时序；不把 private helper 暴露为测试接口。所有并发测试使用 latch、barrier 或 `CompletableFuture`，禁止 `Thread.sleep()`。

### Slice 1：原子状态归约（安全前置切片）

旧实现的 lost-update 窗口位于 private read-modify-write 内部，无法只通过用户 sink latch 做确定性的红测，因此本切片不伪称可以概率无关地复现旧 race。

1. 先实现 `AtomicReference<AgentState>` + CAS reducer，并以代码级不变量验证所有直接 state 读写已迁移。
2. 用受控工具场景做确定性可观察回归：一个工具已 `ToolCompleted`、另一个工具仍发送 `ToolUpdate` 时，用户 sink 看到的 pending set 不含已完成 id；run 完成时 pending set 为空。
3. 断言每个事件仍先归约再委托用户 sink。
4. CAS 线性化正确性作为独立 review 项，不引入只为测试暂停 private CAS 窗口的 production hook。

### Slice 2：串行 start/prepare

1. 红：第一个 `BeforeToolCall` stage 未 settle 时，第二个 `ToolStarted` 和 `BeforeToolCall` 尚未发生。
2. 绿：从 worker task 中移出完整 prepare pass。
3. 断言精确顺序：

```text
ToolStarted(c1)
BeforeToolCall(c1)
ToolStarted(c2)
BeforeToolCall(c2)
```

### Slice 3：核心双排序

用两个并行工具制造 `c2` 明确先于 `c1` 完成：

1. 红：`ToolCompleted` 必须是 `[c2, c1]`。
2. 绿：indexed task + `ExecutorCompletionService`。
3. 同一测试断言：
   - tool-result `MessageStarted` ids 为 `[c1, c2]`；
   - tool-result `MessageCompleted` ids 为 `[c1, c2]`；
   - `LoopResult.context()` tool results 为 `[c1, c2]`；
   - 首个 `TurnCompleted.toolResults` 为 `[c1, c2]`；
   - 下一次 `ModelRequest.messages()` 中 tool results 为 `[c1, c2]`。

### Slice 4：Finalize 并行

1. 两个 execute 都立即完成。
2. `AfterToolCall(c1)` 被 latch 阻塞，`AfterToolCall(c2)` 先完成。
3. 断言 c2 可以先发 `ToolCompleted`，且 c1 的 `ToolCompleted` 必须发生在其 after hook settle 之后。

### Slice 5：已接纳 update 的结算

1. 从工具创建异步 update 调用，使其通过 accepting 检查后阻塞在受控 sink stage。
2. 让 tool execute stage 先 settle。
3. 红：update stage 未完成时不得 finalize 或发 `ToolCompleted`。
4. 绿：per-tool close-and-drain sink。
5. 释放 update 后，断言 `ToolUpdate -> ToolCompleted`；run future 完成后再调用 captured sink，late update 被忽略。

### Slice 6：Immediate failure

混合一个 prepare failure 与一个 prepared tool：

1. 断言 failure tool 未 execute。
2. 断言事件局部顺序：

```text
ToolStarted(c1)
ToolCompleted(c1 failure)
ToolStarted(c2)
```

3. 断言最终 tool-result、context 与 `TurnCompleted.toolResults` 仍为 `[c1, c2]`。

### Slice 7：基础设施失败状态机与 drain

1. 两个任务已提交，第二个先完成；sink 在对应 `ToolCompleted` 上失败，第一个任务仍被 latch 阻塞。
2. 断言公开 run future 尚未完成；释放第一个任务后 future exceptional 完成，之后无该批次事件。
3. prepare pass 中让 immediate `ToolCompleted` delivery 失败，断言尚未提交的 prepared entry 不执行，公开 future exceptional，最终 `pendingToolCalls` 被清空。
4. 使用受控 package-private executor 在第 N 个 submit 上抛 `RejectedExecutionException`：断言此前 accepted task 被 drain，当前和后续 unsubmitted entry 不执行，首个 rejection 最终传播。
5. 所有测试使用 latch/future，不通过关闭真实共享 executor 制造概率窗口。

### Slice 8：失败归一矩阵、取消与既有路径回归

至少覆盖：

- before hook：同步抛、failed stage、null stage、null decision -> immediate failure；
- execute：同步抛、failed stage、null stage、null result -> error result；
- after hook：同步抛、failed stage、null stage、null result -> 保留 execute result；
- event delivery failure 不被上述 catch 归一为 tool result；
- prepare 中途取消后，不再 `ToolStarted` 后续 call；
- cooperative cancellation 下已 started/prepared call 最终 settle；
- cancellation signal 传入 execute/finalize；
- 串行模式仍无重叠，且 tool-result message 仍在整批后统一写回；
- 任一 per-tool `SEQUENTIAL` 仍强制整批串行；
- `LENGTH` 不执行工具；
- `terminate` 与关闭后 late update 语义不变。

## 12. 测试组织

优先新增：

- `agent-core/src/test/java/site/pplee/jcode/agentcore/ParallelToolOrderingTest.java`

该类集中表达 Wave 5 的双排序与并发结算契约，避免继续膨胀 `AgentLoopTest` 和 `ToolPipelineTest`。

必要时小幅修改：

- `AgentLoopTest.java`：补现有 sequential/cancellation 回归断言；
- `ToolPipelineTest.java`：复用或加强 prepare/finalize/update settle 契约；
- `support/`：增加 latch 驱动、无 sleep 的测试工具双。

不测试 private `Prepared` 或 completion helper；测试应只观察事件、状态、请求和 transcript。

## 13. 预计改动文件

### 新增

- `agent-core/src/test/java/site/pplee/jcode/agentcore/ParallelToolOrderingTest.java`
- 必要的 `agent-core/src/test/java/site/pplee/jcode/agentcore/support/` 测试双

### 修改

- `agent-core/src/main/java/site/pplee/jcode/agentcore/ToolCallExecutor.java`
- `agent-core/src/main/java/site/pplee/jcode/agentcore/Agent.java`
- `agent-core/src/main/java/site/pplee/jcode/agentcore/AgentLoop.java`（Javadoc）
- `agent-core/src/main/java/site/pplee/jcode/agentcore/RunEventEmitter.java`（异常等待契约说明，若必要）
- `agent-core/src/main/java/site/pplee/jcode/agentcore/event/AgentEvent.java`（完成顺序与跨线程投递说明）
- `agent-core/src/main/java/site/pplee/jcode/agentcore/event/AgentEventSink.java`（并发调用契约）
- `agent-core/src/main/java/site/pplee/jcode/agentcore/tool/ToolUpdateSink.java`（accepted/in-flight/late update 契约）
- 必要的现有测试
- `docs/architecture/src/00-overview.md`
- 根 `AGENTS.md`
- `agent-core/AGENTS.md`

`ai` 模块无代码或 interface 变更。

## 14. 非目标

本波次不实现：

- 新的 `AgentEvent` 变体或事件重命名；
- provider adapter、认证、Session、Compaction、Extension、Skill 或具体 coding 工具；
- 全局事件 delivery queue；
- 跨工具 `ToolUpdate` 的全局排序；
- 工具依赖图、分组调度、限流、优先级或最大并发度；
- 自动 retry、tool timeout、hook timeout 或强制终止；
- 更改“任一 SEQUENTIAL 令整批串行”的粗粒度策略；
- 更改 LENGTH、terminate、关闭后 late update 或 turn hook 的所有权语义；
- 为 run 中途关闭 executor 增加 retry 或伪造 transcript；部分 submit rejection 只执行 drain-before-rethrow；
- 将 completion/source index 暴露给调用方。

## 15. 文档更新

实现完成后：

1. 在 `docs/architecture/src/00-overview.md` 的工具执行路径中解释双排序服务的两个消费者：
   - UI 使用完成顺序事件；
   - 模型、存储和回放使用源顺序 transcript。
2. 更新根 `AGENTS.md` 的 Wave 进度为 Wave 0-5 完成。
3. 更新 `agent-core/AGENTS.md`：
   - parallel prepare/start 源顺序；
   - execute/finalize 并行；
   - immediate failure 的事件位置；
   - completion/source 双排序；
   - CAS 状态归约；
   - per-tool accepted update drain；
   - prepare/submit 失败状态机；
   - drain-before-rethrow；
   - 事件 sink 的跨线程调用契约。
4. 将本文状态改为“已完成”，并移动到 `docs/plans/archived/wave5-parallel-tool-ordering.md`。

## 16. 验证

按纵向切片执行定向测试，最终逐层验证：

```bash
mvn -pl agent-core -am test
mvn verify
mdbook build docs/architecture
git diff --check
```

若本机未安装 mdBook，记录未执行原因；Maven reactor 与 `git diff --check` 必须通过。

并发测试不得依赖固定 sleep 或概率性调度，多次本地运行应得到相同结果。

## 17. 验收标准

1. `ToolStarted` 与 prepare 按 tool call 源顺序，有阻塞式确定性测试证明。
2. execute/finalize 可并行，有并发测试证明。
3. `ToolCompleted` 按实际完成顺序，有反转源顺序的确定性测试证明。
4. tool-result 消息、context、下一次 model request、`TurnCompleted.toolResults` 和 `TurnContext.toolResults` 均按源顺序。
5. immediate prepare failure 的事件位置与 source slot 契约有测试。
6. 已接纳 in-flight update 在 finalize/`ToolCompleted` 前完成；关闭后 late update 仍忽略。
7. `AgentState.pendingToolCalls` 在并发 update/completion 下无 lost update。
8. prepare delivery failure、部分 submit rejection、task/completion delivery failure 均符合状态机；已提交任务先 drain，未提交 entry 不执行。
9. run future settle 后无该批次已接纳 update、后台任务或事件。
10. before/execute/after 的同步、stage 与 null 失败矩阵有测试，event delivery failure 不被误归一。
11. sequential、LENGTH、terminate、late update、取消和 turn hook 既有测试全部通过。
12. 不新增 public interface 或模块依赖，不引入 provider/产品层职责。
13. 根 reactor、架构文档构建与 diff 检查通过。
14. 代码和非文档配置不显式提及设计参照项目；参照说明只存在于 `docs/` 与 `AGENTS.md`。
15. 本计划完成后已归档，相关知识库与架构文档已同步。
