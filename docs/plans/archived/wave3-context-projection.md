# Wave 3：Context 投影 seam 执行方案

> 状态：已完成  
> 上位提案：[`../pi-inspired-module-boundaries.md`](../pi-inspired-module-boundaries.md)  
> 实施范围：`agent-core` 的 context transform 与 LLM message projection

## 1. 目标

在每次模型调用前建立两段职责独立的消息处理 seam：

```text
AgentMessage[]
  -> ContextTransformer
  -> AgentMessage[]
  -> MessageProjector
  -> ai.Message[]
  -> ModelRequest
```

`ContextTransformer` 负责 Agent 语义下的裁剪和临时注入，`MessageProjector` 负责过滤产品消息并投影为模型可理解的标准消息。两段处理都只生成本次请求视图，不修改持久 transcript。

完成后应满足：

1. 每个 model turn 都先 transform、再 project。
2. 产品消息既可以被默认过滤，也可以由产品层自定义投影。
3. 裁剪和注入只影响当前 `ModelRequest`。
4. callback 失败、等待和取消具有确定的运行时语义。
5. `ai` 继续保持零 Jcode 内部依赖，`agent-core` 继续只依赖 `ai`。

## 2. 非目标

本波次不实现：

- 持久化 compaction 或具体裁剪算法；
- system prompt、tools 或 model 的转换；
- provider-specific 消息顺序校验；
- 新的 Agent lifecycle event；
- Wave 4 的 next-turn 控制点；
- Wave 5 的并行工具双排序；
- `AgentConfig` builder 或兼容构造器。

## 3. 接口决策

### 3.1 `ContextTransformer`

新增公开函数式接口：

```java
@FunctionalInterface
public interface ContextTransformer {
    CompletionStage<List<AgentMessage>> transform(
            List<AgentMessage> messages,
            CancellationSignal cancellation
    );

    static ContextTransformer identity() { ... }
}
```

接口契约：

- 输入是当前 turn 的完整、有序、不可修改消息快照；
- 输出是用于本次请求的 `AgentMessage` 视图；
- loop 等待返回的 `CompletionStage` 完成，慢 transformer 会阻塞当前 run；
- 接收本次 run 的只读 `CancellationSignal`，取消保持协作式；
- 不得原地修改输入消息或其内部状态；
- 返回值、stage 和列表元素均不得为 null；
- 默认 `identity()` 返回原消息视图，不进行裁剪或注入。

采用异步接口是因为 context 裁剪和外部上下文注入可能包含异步工作。loop 运行于虚拟线程，可按现有 hook 模式等待 stage，而不引入额外线程池。

### 3.2 `MessageProjector`

新增公开函数式接口：

```java
@FunctionalInterface
public interface MessageProjector {
    List<Message> project(List<AgentMessage> messages);

    static MessageProjector standard() { ... }
}
```

接口契约：

- 输入必须是 `ContextTransformer` 的最终输出；
- 输出只能包含 `ai.message.Message`；
- 输出必须保持调用方期望的模型消息顺序；
- 返回列表及元素均不得为 null；
- projector 是同步、无 I/O 的格式投影；需要异步获取的数据应在 transformer 阶段完成；
- 默认 `standard()` 展开 `StandardAgentMessage`，按原顺序保留标准消息，静默过滤未知产品消息。

本波次只做 provider-neutral 结构校验：Java 类型约束、非 null 列表和非 null 元素。是否允许空 context、assistant/tool-result 配对和最终消息角色由 projector 与 provider adapter 负责，`agent-core` 不编码某一家 provider 的规则。

### 3.3 配置位置

在 `AgentConfig` 中加入：

```java
ContextTransformer contextTransformer,
MessageProjector messageProjector
```

字段放在 `ObjectMapper` 之后、工具执行配置之前。compact constructor 应用以下默认值：

```text
contextTransformer == null -> ContextTransformer.identity()
messageProjector == null    -> MessageProjector.standard()
```

`Agent` 在创建每次 run 的 `AgentLoopConfig` 时复制这两个值。`AgentLoopConfig` 同样应用默认值，使 package-private loop 测试可安全直接构造配置。

添加 record component 会改变 `AgentConfig` 的 canonical constructor。`agent-core` 的 public interface 在 Wave 0-5 完成前尚未冻结，本波次直接更新仓库内调用点，不添加旧参数列表的兼容重载。

## 4. 运行时顺序

投影发生在 `AgentLoop.invokeModelSafely()` 的模型请求入口。严格顺序为：

```text
cancellation boundary 1
-> append pending steering/follow-up messages
-> read state.context().messages()
-> await ContextTransformer.transform(messages, cancellation)
-> defensive copy and null validation
-> cancellation check
-> MessageProjector.project(transformedMessages)
-> defensive copy and null validation
-> cancellation check
-> collect ToolSpec
-> construct ModelRequest
-> ModelClient.stream(request, cancellation)
```

这段管道必须在每个 model turn 重新执行。工具调用、steering 或 follow-up 产生下一轮请求时，transformer 必须看到刚刚写入真实 context 的 assistant、tool result 和 pending message。

### 4.1 Transcript 不变量

- transformer 输入来自 `state.context().messages()`；
- transformer 和 projector 输出只保存在局部变量；
- 不调用 `LoopState.append()` 写入临时消息；
- 不用 transformed list 替换 `AgentContext`；
- request-only 注入消息不进入 `LoopResult.newMessages()`；
- request-only 注入消息不产生 `MessageStarted`、`MessageCompleted` 等 transcript 事件；
- 最终 assistant 和 tool result 仍按现有路径写入真实 context。

### 4.2 防御复制

transformer 完成后使用 `List.copyOf()` 固定输出，再将其交给 projector。projector 完成后再次使用 `List.copyOf()`，再构造 `ModelRequest`。这样同时实现：

- 拒绝 null list 和 null element；
- 防止后续修改调用方返回的可变 list；
- 保持当前公开不可变模型约束。

复制是浅复制。产品层提供的自定义 `AgentMessage` 仍应自身保持不可变；本波次不尝试深拷贝未知产品消息或可变 `JsonNode`。

## 5. 失败与取消语义

投影 callback 属于模型请求准备阶段。失败统一归一为 terminal assistant，而不是让 `Agent.prompt()` 或 `continueRun()` 的 future 异常失败。

| 场景 | 运行结果 |
|---|---|
| 调用 transformer 前已取消 | 不调用两个 seam，沿现有 abort 路径结束 |
| transformer 同步抛异常 | 生成 `ERROR` assistant；信号已取消时生成 `ABORTED` |
| transformer stage 异常完成 | 同上 |
| transformer 返回 null stage/list/element | 同上，错误前缀为 `context transformer failed` |
| transformer 执行期间取消 | 等待其协作式结束；结束后生成 `ABORTED`，不调用 projector/model |
| projector 抛异常 | 生成 `ERROR` assistant；信号已取消时生成 `ABORTED` |
| projector 返回 null list/element | 同上，错误前缀为 `message projector failed` |
| event sink 失败 | 保持现有传播行为，不被投影错误处理吞掉 |

自定义 callback 失败后不回退到原始 messages 或默认 projector。静默回退可能把调用方明确要求过滤的敏感或超窗消息发送给模型。

### 5.1 失败生命周期

投影失败发生在 provider stream 产生 `Start` 之前，因此 loop 必须显式补齐 assistant lifecycle：

```text
AgentStarted
TurnStarted
已有 prompt message lifecycle
MessageStarted(failure assistant)
MessageCompleted(failure assistant)
TurnCompleted(failure assistant, [])
AgentCompleted
```

失败结果满足：

- `ModelClient` 不被调用；
- failure assistant 写入真实 transcript；
- `LoopResult` 正常返回；
- `AgentState.errorMessage` 保存错误信息；
- run 结束后可以通过新的 `prompt()` 继续运行；
- 不执行工具，不 drain 后续 steering/follow-up。

### 5.2 取消边界

在 transformer 后和 projector 后各检查一次 `CancellationSignal`：

1. transformer 观察到取消并结束后，不再执行 projector；
2. projector 执行期间无法被强制中断，因此要求 projector 保持同步、快速和无 I/O；
3. projector 完成后若已取消，不构造或发送模型请求；
4. callback 若忽略取消且 stage 永不完成，run 仍会等待，这是现有协作式取消模型的固有限制。

## 6. 代码改动清单

### 6.1 新增文件

| 文件 | 内容 |
|---|---|
| `agent-core/src/main/java/site/pplee/jcode/agentcore/message/ContextTransformer.java` | 公开异步 transformer 与 `identity()` 默认实现 |
| `agent-core/src/main/java/site/pplee/jcode/agentcore/message/MessageProjector.java` | 公开同步 projector 与 `standard()` 默认实现 |
| `agent-core/src/test/java/site/pplee/jcode/agentcore/ContextProjectionTest.java` | Wave 3 集成契约测试 |

### 6.2 修改文件

| 文件 | 改动 |
|---|---|
| `AgentConfig.java` | 增加两个配置字段、默认值和 Javadoc |
| `Agent.java` | 将两个 seam 传入每次 run 的 `AgentLoopConfig` |
| `AgentLoopConfig.java` | 携带两个 seam 并应用默认值 |
| `AgentLoop.java` | 替换内联 `projectMessages()`，实现 transform/project/cancel/failure 管道 |
| `AgentContext.java` | 明确真实 transcript 与 request view 的区别 |
| `message/AgentMessage.java` | 用正式 seam 替换 Wave 3 占位描述 |
| `message/StandardAgentMessage.java` | 链接 `MessageProjector.standard()` |
| `ai/message/Message.java` | 删除“Wave 3 将实现”的过期措辞，不引用上层 Java 类型 |
| `AgentLoopTest.java` | 更新 `AgentLoopConfig` 构造参数 |
| `ToolPipelineTest.java` | 更新 `AgentLoopConfig` 构造参数 |
| `AgentTest.java` | 更新 `AgentConfig` 构造参数并验证默认值 |
| `StreamingEventTest.java` | 更新 `AgentConfig` 构造参数 |
| `docs/architecture/src/00-overview.md` | 将 context 投影加入抽象地图和主运行路径 |
| `AGENTS.md` | 更新 Wave 进度与仓库级投影约束 |
| `agent-core/AGENTS.md` | 记录 seam 位置、调用顺序、失败和取消语义 |

### 6.3 保持不变

- `ai.client.ModelRequest`；
- `LoopState` 与 `LoopResult`；
- `AgentEvent` 类型集合；
- Maven 模块及依赖；
- provider adapter 约束；
- 工具三阶段管道。

## 7. `AgentLoop` 实现步骤

### 步骤 1：建立 seam 和默认实现

新增两个接口及英文 Javadoc。默认 projector 迁移现有 `AgentLoop.projectMessages()` 的行为，确保默认请求内容不变。

### 步骤 2：完成配置传递

按以下路径传递配置：

```text
AgentConfig
  -> Agent.submit()
  -> AgentLoopConfig
  -> AgentLoop.invokeModelSafely()
```

完成所有 production 和 test constructor 调用点更新。

### 步骤 3：替换模型调用入口

在 `invokeModelSafely()` 中增加私有线性子方法：

```text
transformMessages()
-> projectMessages()
-> build ModelRequest
```

transformer 与 projector 使用独立 try/catch，以生成准确的错误前缀。只捕获新增 seam 的调用和输出校验失败，避免顺带改变 `AgentTool.spec()`、event sink 等既有错误行为。

不新增 `ContextProjectionResult` 等只做字段传递的公开或 package-private 类型。

### 步骤 4：补齐失败 assistant lifecycle

当 transform/project 在 stream 开始前失败时：

1. 创建空 content、零 usage 的 `ERROR` 或 `ABORTED` assistant；
2. 发出 `MessageStarted`；
3. 交由现有 `recordAssistant()` 写入 context 并发出 `MessageCompleted`；
4. 通过现有 terminal failure 分支发出 `TurnCompleted` 与 `AgentCompleted`。

### 步骤 5：更新注释和架构文档

删除所有“Wave 3 将 formalize projector”的过渡说明，改成现在时契约。架构文档重点解释两个 seam 的职责差异和 request view 不持久化，不写成源码文件清单。

## 8. 测试矩阵

### 8.1 顺序与默认行为

| 测试 | 断言 |
|---|---|
| `transformerRunsBeforeProjector` | 严格顺序为 transform、project、model |
| `defaultProjectorFiltersCustomMessages` | 未知产品消息被过滤，标准消息原序保留 |
| `customProjectorProjectsProductMessage` | 产品消息可转为 `Message.User` 等标准消息 |
| `projectionRunsBeforeEveryModelCall` | tool-call 两轮中两个 seam 均执行两次 |
| `secondProjectionSeesToolResults` | 第二次 transform 看到 assistant tool call 和 tool result |

### 8.2 Request view 隔离

| 测试 | 断言 |
|---|---|
| `transformedViewDoesNotReplaceTranscript` | 请求被裁剪，但原始及最终 context 保留完整历史 |
| `injectedMessagesAreRequestOnly` | 注入消息进入请求，但不进入 context/newMessages/events |
| `transformerReceivesPendingMessages` | steering/follow-up 在 transform 前已写入当前 context |

### 8.3 等待与取消

| 测试 | 断言 |
|---|---|
| `transformerStageIsAwaited` | stage 完成前 projector 和 model 均不执行 |
| `transformerReceivesRunCancellationSignal` | callback 收到本次 run 的只读信号 |
| `abortDuringTransformProducesAbortedAssistant` | 取消后不执行 projector/model，最终 stop reason 为 `ABORTED` |
| `preCancelledRunSkipsProjection` | 已取消时两个 seam 调用次数均为零 |

### 8.4 错误归一

| 测试 | 断言 |
|---|---|
| `transformerFailureIsNormalized` | failed stage 产生完整 terminal lifecycle，future 正常完成 |
| `synchronousTransformerFailureIsNormalized` | callback 同步抛错遵循相同语义 |
| `projectorFailureIsNormalized` | model 未调用，错误前缀区分 projector |
| `invalidTransformerOutputIsNormalized` | null stage/list/element 不进入 projector |
| `invalidProjectorOutputIsNormalized` | null list/element 不进入 `ModelRequest` |

测试继续使用纯 JUnit 5、`ScriptedModelClient`、`RecordingEventSink`、`CompletableFuture` 和 `CountDownLatch`。并发测试必须使用有界等待，不使用 `Thread.sleep()`，不增加 Mockito。

## 9. 验证命令

按以下顺序执行：

```bash
mvn -pl agent-core -am test -Dtest=ContextProjectionTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -pl agent-core -am test
mvn verify
mdbook build docs/architecture
```

如果本机未安装 mdBook，应明确记录架构文档构建未执行，不能用 Maven 成功替代该验证项。

## 10. 完成标准

Wave 3 只有在以下条件全部满足后才算完成：

1. 两个公开 seam 具备完整英文 Javadoc，说明调用时机、等待、失败、取消和不可变约束。
2. 每个模型请求都严格执行 transform 后 project。
3. 默认配置下的模型请求内容与 Wave 3 前保持一致。
4. 产品消息可由默认 projector 过滤，也可由自定义 projector 投影。
5. transformer 的裁剪和注入不改变真实 transcript。
6. callback 失败形成 terminal assistant，不使 run future 异常失败。
7. 取消期间不继续不必要的 projector 或 model 调用。
8. 不新增事件、模块、依赖或 provider-specific 规则。
9. 根 `mvn verify` 通过。
10. 架构文档和根、模块 `AGENTS.md` 与实现保持一致。

本文档已随实施完成归档至 `docs/plans/archived/`。上位提案仍包含 Wave 4、5，在全部波次完成前继续保留于 `docs/plans/` 根目录。
