# AgentLoop 运行事件投递收敛方案

> 状态：已实施
> 范围：`agent-core`
> 基线：`039c068`

## 1. 目的

将一次 Agent run 的生命周期事件投递收敛为一个内部深模块 `RunEventEmitter`。该模块是 `AgentEventSink` 异步 seam 与 `AgentLoop` 同步控制流之间唯一的 adapter：它等待每个事件的 `CompletionStage` 完成，再允许调用方继续。

本方案消除两处重复的等待实现：

- `AgentLoop.emit(AgentEvent, AgentLoopConfig)`；
- `LoopToolUpdateSink.update(Content)` 中直接调用 sink 并等待。

## 2. 已确认的约束

1. `AgentEventSink` 保持既有 public interface：`CompletionStage<Void> emit(AgentEvent)`。
2. `RunEventEmitter` 是 package-private 模块，位于 `site.pplee.jcode.agentcore` 根包；不在 `event` 包新增 public seam。
3. `RunEventEmitter` 是每次 run 的内部依赖，由 `AgentLoopConfig` 持有。
4. `RunEventEmitter` 的 interface 只有同步的 `void emit(AgentEvent)`；等待规则不能被调用方绕过。
5. sink 同步抛出的异常和 returned stage 的失败不捕获、不包装，保持现有 `.join()` 传播语义。
6. `ToolUpdateSink.update()` 仍在投递完成后返回已完成 stage；`settle()` 后的迟到更新仍静默丢弃。
7. 本方案不串行化并行工具的 `ToolUpdate` 投递，不改变跨工具更新的并发语义；该问题属于独立的后续方案。

## 3. 现状与问题

当前普通生命周期事件通过下列 helper 投递：

```java
private static void emit(AgentEvent event, AgentLoopConfig config) {
    config.eventSink().emit(event).toCompletableFuture().join();
}
```

该 helper 只使用完整 `AgentLoopConfig` 中的一个依赖，interface 比 implementation 所需信息更宽。

工具渐进更新则绕过该 helper：

```java
config.eventSink().emit(new AgentEvent.ToolUpdate(call, update))
        .toCompletableFuture()
        .join();
```

因此，“等待事件投递完成”这一运行语义分散在两个位置。删除任一处都会让复杂度回流到调用方，说明应当将其收敛到一个模块，而不是继续增加转发 helper。

## 4. 目标结构

```text
Agent
  │  reducerSink（先归约 AgentState，再委托用户 sink）
  ▼
AgentLoopConfig ──持有──> RunEventEmitter
                                │
                                │ 等待 CompletionStage
                                ▼
                           AgentEventSink seam

AgentLoop ──────────────────────┘
LoopToolUpdateSink ─────────────┘
```

`RunEventEmitter` 为内部 implementation；`AgentEventSink` 仍是唯一供用户、`noop`、归约 sink 等 adapter 实现的 public seam。

## 5. 模块设计

### 5.1 `RunEventEmitter`

新增文件：

```text
agent-core/src/main/java/site/pplee/jcode/agentcore/RunEventEmitter.java
```

职责：持有一个 `AgentEventSink`，把异步事件投递转换成 AgentLoop 所需的同步完成语义。

预期形状：

```java
final class RunEventEmitter {
    private final AgentEventSink delegate;

    RunEventEmitter(AgentEventSink delegate) { /* null -> AgentEventSink.noop() */ }

    static RunEventEmitter noop() { /* delegate to AgentEventSink.noop() */ }

    void emit(AgentEvent event) {
        delegate.emit(event).toCompletableFuture().join();
    }
}
```

规则：

- constructor 将 `null` delegate 归一化为 `AgentEventSink.noop()`；
- `emit` 不添加 `try/catch`、错误映射或重试；
- 不使用锁、队列或共享可变状态；
- Javadoc 用英文，说明其是 per-run synchronous delivery adapter。

### 5.2 `AgentLoopConfig`

将 record 的最后一个成员从：

```java
AgentEventSink eventSink
```

替换为：

```java
RunEventEmitter events
```

compact constructor 保持 record 的不可变性，并将 `null` emitter 归一化为 `RunEventEmitter.noop()`。

不保留同参数数目的 `AgentEventSink` 重载 constructor。该重载会使最后一个实参为字面量 `null` 的调用产生 Java 重载歧义。所有现有内部构造点改为显式创建 `RunEventEmitter`。

### 5.3 `Agent`

`Agent.submit()` 仍先创建 reducer sink：先调用 `reduceState(event)`，再委托用户配置的 `AgentEventSink`。组装 `AgentLoopConfig` 时，将该 reducer sink 显式交给 `RunEventEmitter`。

这不改变用户 sink 看到事件前状态已完成归约的既有不变量。

### 5.4 `AgentLoop`

删除静态 `emit(AgentEvent, AgentLoopConfig)` helper。该 helper 在新结构中只会转发，属于 shallow 模块。

调整方式：

- 需要 config 的循环和工具执行路径直接调用 `config.events().emit(event)`；
- 仅需投递的私有模块改为接收 `RunEventEmitter`：
  - `abortRun(LoopState, RunEventEmitter)`；
  - `consumeStream(AssistantMessageStream, RunEventEmitter)`；
  - `failTruncatedToolCalls(List<Content.ToolCall>, RunEventEmitter)`；
- `LoopToolUpdateSink` constructor 改为接收 `RunEventEmitter`，不再接收完整 `AgentLoopConfig`；
- `LoopToolUpdateSink.update()` 调用 `events.emit(new AgentEvent.ToolUpdate(...))` 后返回 `CompletableFuture.completedStage(null)`。

普通消息、turn、工具开始/完成、取消与流式增量事件均使用同一个 `RunEventEmitter` implementation。

## 6. 不变量与非目标

### 必须保持的不变量

1. 每次 `AgentEventSink.emit()` 返回的 stage 必须被等待。
2. 慢 sink 必须阻塞对应的 run 或工具执行线程。
3. 用户 sink 观察事件时，`Agent.state()` 已先完成归约。
4. 事件失败沿现有异常路径传播；不得把失败静默转换为成功。
5. `ToolUpdate` 在 `ToolStarted` 后、`ToolCompleted` 前投递。
6. `settle()` 后的 `ToolUpdate` 静默丢弃。

### 非目标

- 不改变 `AgentEventSink` public interface。
- 不改变 `ToolUpdateSink` public interface。
- 不将 `AgentLoop` 改造为全异步状态机。
- 不为并行工具更新建立全局顺序或串行 delivery queue。
- 不增加新的 Maven 模块、DI 容器或全局注册表。

## 7. 测试计划

### 7.1 `RunEventEmitterTest`

新增：

```text
agent-core/src/test/java/site/pplee/jcode/agentcore/RunEventEmitterTest.java
```

覆盖：

1. sink 返回未完成 stage 时，`RunEventEmitter.emit()` 持续阻塞；完成 stage 后才返回。
2. sink 的 failed stage 通过 `CompletionException` 按 `.join()` 语义传播。
3. `null` delegate 与 `RunEventEmitter.noop()` 都可无阻塞投递事件。

阻塞测试使用虚拟线程、`CountDownLatch` 与 `CompletableFuture`，不使用 `Thread.sleep()`。

### 7.2 工具渐进更新回归测试

在 `ToolPipelineTest` 添加受控 `AgentEventSink`：

1. 收到 `ToolUpdate` 时返回受控的未完成 stage；
2. 断言工具尚未完成，且尚未发出 `ToolCompleted`；
3. 完成该 stage；
4. 断言 run 正常结束，并保持 `ToolStarted → ToolUpdate → ToolCompleted` 顺序。

既有流式事件、生命周期顺序、state reducer 与 late update 测试保持不变，作为行为回归覆盖。

## 8. 实施顺序

1. 新增 `RunEventEmitter` 及其直接测试。
2. 修改 `AgentLoopConfig` 的事件依赖成员和默认值。
3. 修改 `Agent` 与测试 helper 的 config 组装。
4. 删除 `AgentLoop.emit`，迁移普通事件、流式事件、取消和 LENGTH 工具失败路径。
5. 迁移 `LoopToolUpdateSink`。
6. 增加受控 `ToolUpdate` 回归测试。
7. 执行验证命令。

## 9. 验证命令

```bash
mvn -pl agent-core -am test
mvn verify
```

## 10. 参考

- `docs/references/pi/packages/agent/src/agent-loop.ts`：事件投递完成后再继续循环的参考实现。
- `docs/references/pi-book/src/ch08-agent-loop.md`：事件流作为循环输出通道的设计解读。
- `CONTEXT.md`：运行事件投递（Run event delivery）的项目术语。
