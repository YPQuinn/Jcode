# OpenAI Responses 正确性首批修复方案

> 状态：已实施（2026-09-16）
>
> 问题基线：[`docs/issues/openai-responses-protocol-parity.md`](../issues/openai-responses-protocol-parity.md)
>
> 参考实现：pi `origin/main@588915ec71714688cee8b7153339e8bdebb3e82e`
>
> 实施范围：OAI-001～OAI-009 的正确性主干，拆为 5 个串行 PR
>
> 涉及模块：`ai`、`agent-core`、`ai-providers`

## 1. 决策摘要

首批交付不追求覆盖 pi 的全部 OpenAI 能力，只建立以下正确性主干：

1. `response.incomplete` 不再把内容过滤或未知限制误报成正常截断。
2. 取消和 provider 关闭可以立即终止 connect/send/静默 SSE read，并且 terminal event 恰好一次。
3. `ai` 增加最小、provider-neutral 的模型来源和逐 content opaque replay state。
4. OpenAI provider 在 package-private transcript planner 中完成 reasoning replay、模型切换和 tool-call 配对修复。
5. 补齐 reasoning summary、refusal、done-only item、流式工具参数和 SSE event type 的协议适配。

严格按以下依赖顺序实施：

```text
PR 1  incomplete terminal semantics
  ↓
PR 2  cancellation + active HTTP lifecycle
  ↓
PR 3  provider-neutral source/replay seam
  ↓
PR 4  OpenAI replay capture + transcript planner
  ↓
PR 5  stream/event fidelity
```

每个 PR 必须独立保持 reactor 绿色。禁止并行修改同一工作树中的这些 PR，因为 `OpenAiEventMapper`、`OpenAiResponsesAdapter` 和公共消息类型存在明确的串行依赖。

## 2. 委派分析结论

方案由多个只读子 agent 分工分析后由主 agent 统一裁决：

- cancellation lane：确认仅靠 `CancellationSignal.isCancelled()` 无法立即中断静默 I/O，必须增加只读 cancellation registration seam；同时指出 `AssistantMessageStream.push()` 的 terminal 线性化不足。
- replay interface lane：比较 assistant-level 大 envelope、逐 content opaque state 和旁路 registry 后，推荐逐 content state + assistant source `ModelRef`，避免 provider payload 成为第二套 transcript 事实来源。
- provider replay lane：给出 same-model / model handoff / foreign transcript、orphan call/result、reasoning capture/backfill 和 id 降级规则。
- event fidelity lane：确认 OAI-005～OAI-009 的最小修改点和事件排序约束，并指出 message id/phase 必须复用 PR 3 的 replay seam。

主方案采纳逐 content replay state；不采用 assistant-level 整段 `response.output` envelope，也不采用 session/旁路 metadata registry。

## 3. 目标

首批完成后必须满足：

1. 官方 OpenAI Responses 的普通 text/function tool 路径保持兼容。
2. `store:false` reasoning + tool call 可以在同一模型下一轮安全重放。
3. 同 provider 不同模型或来源未知时，不发送会触发 reasoning pairing 校验的 function-call item id。
4. `ERROR`/`ABORTED` assistant 和无配对 tool result 不会生成非法 Responses input 序列。
5. 取消或 provider 关闭不依赖下一条 SSE event 到达。
6. 所有 provider、网络、映射、取消失败继续通过 `Start -> Error` 表达，不同步抛出。
7. `ai` 仍不出现 OpenAI 命名字段；`agent-core` 仍只依赖 `ai`。
8. 不引入 provider SDK、轮询、`Thread.sleep()`、`Map<String,Object>` 或 session 存储。

## 4. 非目标

首批不实现：

- 用户图片和 tool-result 图片；
- prompt cache、session affinity、自定义 headers；
- temperature、service tier、通用 sampling params；
- strict/grammar/custom/deferred tools；
- 自动重试；
- provider cost 计算；
- 新 session 文件或持久化格式；
- OpenAI-compatible endpoint 的所有非标准扩展；
- 把 OpenAI transcript 规则移动到 `AgentLoop`、`ContextTransformer` 或 `MessageProjector`。

## 5. 核心 seam 设计

### 5.1 可观察取消 seam

在 `ai.concurrent` 新增：

```java
@FunctionalInterface
public interface CancellationRegistration extends AutoCloseable {
    @Override
    void close();
}
```

扩展：

```java
public interface CancellationSignal {
    boolean isCancelled();
    void throwIfCancelled();
    CancellationRegistration onCancellation(Runnable listener);
}
```

接口契约：

- 注册监听只允许观察取消，不能触发取消。
- signal 已取消时，listener 在注册调用返回前执行一次。
- 正常注册的 listener 最多执行一次。
- registration 在取消线性化点之前成功关闭，则 listener 不执行。
- registration 关闭幂等。
- 一个 listener 失败不得阻止其他 listener，也不得让 `CancellationSource.cancel()` 失败。
- listener 在调用 `cancel()` 的线程上同步执行（通常是 `Agent.abort()` 的调用方线程），因此 listener **必须非阻塞**：只允许做标记、完成 future、`cancel()` future、关闭 body、向队列 push 等 O(1) 且不等待的操作；禁止在 listener 内等待其他线程、做网络 I/O 或调用可能阻塞的用户回调。
- `CancellationSignal` 仍是 private view，不能 cast 回 `CancellationSource`。

这是真正立即中断静默 I/O 的前置条件；定时轮询不符合项目约束。该方法是有意的 public source-breaking change，不提供只能观察“已经取消”而无法观察未来取消的弱 default 实现；PR 2 必须一次更新仓库内全部实现者和匿名测试双。

### 5.2 Provider-neutral replay state

在 `ai.message` 新增：

```java
public record ModelReplayState(
        String format,
        String payload
) {
}
```

约束：

- `format` 非空，表示 dialect 与格式版本，不是 public Java 字段中的 provider 特有结构。
- `payload` 非空、不可变，只由写入它的 adapter 解释。
- `toString()` 必须 redacted，不输出 payload。
- 不提供 map、任意属性访问或跨 provider 解析接口。

修改 standard content：

```java
record Text(String text, ModelReplayState replayState) implements Content {
    public Text(String text) { this(text, null); }
}

record Thinking(String text, ModelReplayState replayState) implements Content {
    public Thinking(String text) { this(text, null); }
}
```

`replayState == null` 表示没有可重放的 provider 状态。兼容构造器确保现有调用方继续只传文本。

修改 assistant：

```java
record Assistant(
        List<Content> content,
        StopReason stopReason,
        String errorMessage,
        Usage usage,
        Instant timestamp,
        ModelRef sourceModel
) implements Message {
    public Assistant(
        List<Content> content,
        StopReason stopReason,
        String errorMessage,
        Usage usage,
        Instant timestamp
    ) {
        this(content, stopReason, errorMessage, usage, timestamp, null);
    }
}
```

`sourceModel == null` 表示 synthetic、legacy 或来源未知。它是 provider-neutral 的标准来源身份，不是 OpenAI capability。

### 5.3 Replay state 不变量

1. 只有 `assistant.sourceModel().equals(targetModel)` 时，provider 才能解释 content replay state。
2. 未知 `format`、格式错误或内容不匹配时必须忽略 state，并使用安全降级路径。
3. 重建/改写 `Content.Text` 或 `Content.Thinking` 时，调用方若不显式保留 state，就自然清除 state。
4. `agent-core` 只原样保存和投影，不解析 state。
5. `ModelReplayState` 不是第二套消息正文；visible text/thinking/tool call 仍以 `Content` 为唯一事实来源。
6. `Content.ToolCall` 继续使用现有 `OpenAiToolCallIds` 保存 `call_id + item_id`，本批不引入第二套 tool-call metadata。**这是过渡态**：首批完成后 provider replay 数据实际上有两条通路（tool call 走 `oai1:` id 编码，text/thinking 走 `ModelReplayState`）。后续统一方向是让 `Content.ToolCall` 也携带 `replayState`、`id` 回归纯 `call_id`，并在引入第二个 provider（anthropic 等）之前完成迁移，避免长出第三套机制。

### 5.4 OpenAI provider-local deep module

新增 package-private：

```text
OpenAiReplayStateCodec       # PR 4
OpenAiTranscriptPlanner      # PR 4
OpenAiPartialJsonParser      # 仅 PR 5 引入
```

建议 planner interface：

```java
final class OpenAiTranscriptPlanner {
    OpenAiTranscriptPlan plan(List<Message> messages, ModelRef targetModel);
}

record OpenAiTranscriptPlan(
        List<ObjectNode> inputItems,
        boolean replayedReasoning
) {
}
```

所有 OpenAI sequence、reasoning pairing、handoff、orphan 和 foreign-id 规则收敛在 planner 内。`OpenAiRequestMapper` 只负责组合 system prompt、planned input、tools 和 reasoning request policy。

不建立公共 transcript-normalization SPI：当前只有具体 provider dialect 规则，package-private seam 已足够。

## 6. PR 1 — 修正 incomplete terminal semantics

对应：OAI-003。

### 6.1 行为规则

将 `response.completed` 与 `response.incomplete` 分开处理。

`response.incomplete`：

| `incomplete_details.reason` | 结果 |
| --- | --- |
| `max_output_tokens` | `Done(LENGTH)` |
| `content_filter` | `Error(ERROR)` |
| 缺失/null/blank | `Error(ERROR)`，说明 provider 未给 reason |
| 其他值 | `Error(ERROR)`，保留原 reason 到错误文本 |

必须先映射 usage，再构造 terminal assistant，使 incomplete error 保留 provider 已上报的 usage。

### 6.2 修改文件

- `ai-providers/src/main/java/site/pplee/jcode/aiproviders/openai/OpenAiEventMapper.java`
- `ai-providers/src/test/java/site/pplee/jcode/aiproviders/openai/OpenAiEventMapperTest.java`
- `ai-providers/src/test/java/site/pplee/jcode/aiproviders/openai/OpenAiResponsesAdapterTest.java`

### 6.3 TDD 顺序

1. 将现有 incomplete 测试明确命名为 `maxOutputTokensIncompleteMapsToLength`。
2. 新增 content filter → Error 测试。
3. 新增未知 reason → Error 测试。
4. 新增缺失 reason → Error 测试。
5. 新增 adapter 级 `Start -> Error(ERROR)` 序列测试。
6. 最后拆分 mapper 分支，不提前引入 raw metadata 字段。

### 6.4 验收

- 只有 `max_output_tokens` 产生 LENGTH。
- incomplete error 的 partial content 和 usage 不丢失。
- terminal event 恰好一个。

### 6.5 回滚点

本 PR 不改公开类型；在后续 PR 尚未继续改写 event mapper 前可直接独立回滚。后续 PR 合并后，应做语义回滚或基于当时 mapper 结构重新应用旧规则，不能机械 revert 覆盖后续改动。

## 7. PR 2 — 可中断 HTTP/SSE 生命周期

对应：OAI-004。

### 7.1 CancellationSource 实现

`CancellationSource` 使用一个内部锁或等价线性化机制管理：

- cancelled 状态；
- listener registration 集合；
- cancel 时复制并清空 listener；
- listener 在锁外调用；
- registration/cancel 并发时，listener 要么被成功注销，要么执行一次，不能丢失或重复。

更新所有测试用 `MutableCancellationSignal` 和匿名 signal 实现。

### 7.2 AssistantMessageStream 线性化

当前 `volatile done` 的 check-then-act 不能保证多 producer 下 terminal 与 delta 的严格顺序。将 `push()` 的以下操作置于同一同步/锁区：

1. 检查 `done`；
2. 入队 event；
3. terminal 时设置 `done` 并完成 result future。

保证：

- terminal 后不可能再入队 delta/end；
- 并发 terminal 最多一个进入 queue；
- `resultStage()` 与 queue 中的 terminal 对应同一 assistant。

### 7.3 OpenAI ActiveExchange

每次 `stream()` 创建 provider-local `ActiveExchange`，内部持有：

- `CompletableFuture<HttpResponse<InputStream>>`；
- active response body；
- 最近一次不可变 partial content snapshot；
- cancellation registration；
- terminal-once guard；
- abort cause：caller cancellation 或 provider closed。

HTTP 改用：

```java
httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
```

producer 仍运行在 provider-owned virtual thread，可等待 future；不引入共享平台线程池。

取消 callback：

1. 通过 `completeOnce` 推送 `Error(ABORTED)`；
2. cancel 尚未完成的 HTTP future；
3. 关闭 active body；
4. 不读取 mapper 内部可变 list，而读取最近一次已提交 event 的不可变 partial snapshot。

`ActiveExchange.emitNonTerminal(event)` 与 `completeOnce(...)` 必须共享同一个线性化锁：前者在锁内 push event 并提交 `event.partial().content()` snapshot，后者在同一锁内读取 snapshot 并 push terminal。这样 cancellation 不可能观察到“event 已入队但 snapshot 未更新”或“snapshot 已更新但 event 未入队”的中间状态。

`OpenAiResponsesAdapter.close()`：

1. 原子标记 closed；
2. 将 active exchanges 完成为 `Error(ERROR, "provider is closed")`；只有 caller cancellation 使用 `ABORTED`；
3. cancel futures、关闭 bodies；
4. `shutdownNow()` 仅作为资源关闭后的中断兜底；
5. close 后的新请求仍返回 `Start -> Error(ERROR, "provider is closed")`。

### 7.4 竞态不变量

1. `Start` 必须在注册 cancellation callback 前 push。
2. callback、provider terminal、HTTP failure、SSE failure、provider close 全部经过一个 `completeOnce`。
3. provider terminal 先赢时，后续 cancel/close 被忽略。
4. cancel 先赢时，后续 provider Done/Error/IOException 被忽略。
5. cancel 发生在 future/body attach 前时，attach 后立即观察 abort cause 并取消/关闭。
6. cancel 发生在 body attach 后时，callback 立即关闭 body。
7. producer `finally` 注销 listener、移除 active exchange，并关闭 body。
8. callback 不能同步抛到 `CancellationSource.cancel()`。

### 7.5 修改文件

新增：

- `ai/src/main/java/site/pplee/jcode/ai/concurrent/CancellationRegistration.java`

修改：

- `ai/src/main/java/site/pplee/jcode/ai/concurrent/CancellationSignal.java`
- `ai/src/main/java/site/pplee/jcode/ai/stream/AssistantMessageStream.java`
- `agent-core/src/main/java/site/pplee/jcode/agentcore/concurrent/CancellationSource.java`
- `agent-core/src/test/java/site/pplee/jcode/agentcore/concurrent/CancellationSourceTest.java`
- `ai/src/test/java/site/pplee/jcode/ai/stream/AssistantMessageStreamTest.java`
- `ai/src/test/java/site/pplee/jcode/ai/support/MutableCancellationSignal.java`
- `ai-providers/src/main/java/site/pplee/jcode/aiproviders/openai/OpenAiResponsesAdapter.java`
- `ai-providers/src/test/java/site/pplee/jcode/aiproviders/openai/support/MutableCancellationSignal.java`
- `ai-providers/src/test/java/site/pplee/jcode/aiproviders/openai/support/FakeOpenAiServer.java`
- `ai-providers/src/test/java/site/pplee/jcode/aiproviders/openai/OpenAiResponsesAdapterTest.java`
- `ai/src/test/java/site/pplee/jcode/ai/AiModuleTest.java` 中的匿名 signal；
- `ai-providers/src/test/java/site/pplee/jcode/aiproviders/openai/OpenAiResponsesAdapterTest.java` 中的 `CancelOnSecondCheck`；
- `rg "implements CancellationSignal|new CancellationSignal"` 找到的其他实现者。

### 7.6 TDD 顺序

1. `CancellationSourceTest`：执行一次、late registration、提前注销、并发 cancel/register、listener 异常隔离、signal 不可 cast。
2. `AssistantMessageStreamTest`：并发 terminal 只有一个；terminal 后无 delta。
3. Fake server 增加 request received、headers sent、body closed/gated body 的确定性 latch。
4. **（强制）** cancel while waiting headers。该用例验证 JDK `HttpClient.sendAsync` future 的 `cancel(true)` 确实中止在途交换（JDK 16 起 JDK-8245462 修复后的行为，JDK 21 可用）；不得靠 server 主动关闭连接让测试通过。
5. **（强制）** cancel silent body before first SSE event，不释放 server gate。该用例验证跨线程 `close()` `BodyHandlers.ofInputStream()` 的 body 能唤醒阻塞中的 `read()`（JDK 13 起修复的行为）；同样不得靠 server gate 释放通过。
6. partial text 后静默取消，ABORTED 保留 partial。
7. provider close 中止 silent stream，且 terminal 只有一个。
8. 实现 signal registration、stream 线性化和 adapter ActiveExchange。

### 7.7 回滚点

该 PR 同时修改 `ai` cancellation interface 与所有实现者，必须作为一个原子 PR 回滚。不得只回滚 adapter 或只回滚 signal。

## 8. PR 3 — Provider-neutral source/replay seam

对应 OAI-001/OAI-002 的公共类型前置。

### 8.1 修改文件

新增：

- `ai/src/main/java/site/pplee/jcode/ai/message/ModelReplayState.java`

修改：

- `ai/src/main/java/site/pplee/jcode/ai/message/Content.java`
- `ai/src/main/java/site/pplee/jcode/ai/message/Message.java`
- `ai/src/test/java/site/pplee/jcode/ai/AiModuleTest.java`
- `agent-core/src/test/java/site/pplee/jcode/agentcore/ContextProjectionTest.java`
- 受 canonical record constructor 影响的编译调用点。

### 8.2 实施规则

1. `ModelReplayState` compact constructor 校验 format/payload 非空。
2. `toString()` 只显示 format 和 redacted payload。
3. `Content.Text(String)` 与 `Content.Thinking(String)` 兼容构造器默认 state 为 null。
4. `Message.Assistant` 在 canonical components 末尾增加 `sourceModel`，保留原五参数构造器。
5. `Message.Assistant.of(...)` 保持原行为，sourceModel 为 null。
6. `StandardAgentMessage`、默认 projector 和 context transformer 不解释或重写 metadata。
7. 新增 projector round-trip 测试，证明 source/state 原样进入 `ModelRequest` view，但不修改 transcript。

### 8.3 验收

- 仓库现有五参数 assistant 和单参数 Text/Thinking 构造继续编译。
- `List.copyOf` 等不可变约束保持。
- replay payload 不出现在 `toString()`。
- `ai` 公共类型中没有 OpenAI 命名字段。
- `agent-core` 主循环无 provider-specific 分支。

### 8.4 回滚点

PR 4 合并前可独立回滚；PR 4 合并后必须与 PR 4 一起回滚。

## 9. PR 4 — OpenAI replay capture 与 transcript planner

对应 OAI-001、OAI-002。

### 9.1 Replay state formats

`OpenAiReplayStateCodec` 只接受版本化 format：

```text
openai-responses/reasoning-item-v1
openai-responses/message-item-v1
```

- reasoning payload：版本 envelope，保存 visible thinking 的 SHA-256 digest 与完整 reasoning output item JSON；item 包含 id、summary/content 和可用的 `encrypted_content`。
- message payload：版本 envelope，保存 visible text 的 SHA-256 digest、item id 与可选 phase；visible text 仍来自 `Content.Text.text()`。
- planner 只有在当前 content 文本 digest 与 envelope 一致时才复用 state，防止调用方修改正文后错误复用旧 id/phase/reasoning。
- digest 与 visible text 的推导规则耦合：PR 5 会把 thinking finalizer 改为 `summary` → `content` → deltas 优先级，之后任何 finalizer 规则变更都会让此前生成的 state digest 失效并走降级路径。当前无持久化因此无实际影响，但未来若引入 session 持久化，必须把 finalizer 规则视为 replay state 格式的一部分并升级 format 版本。
- codec 返回 defensive copy/新 JsonNode，不暴露内部可变对象。
- malformed/unknown/stale replay state 不抛到整个 request；planner 忽略 state 并走安全降级。

### 9.2 Response capture

`OpenAiEventMapper` 构造时接收当前 `ModelRef`。

处理规则：

1. 所有 provider-produced partial/final assistant 设置 `sourceModel`，包括 `OpenAiResponsesAdapter.stream()` 在 mapper 之外同步 push 的 `Start` partial，以及 adapter `fail(...)` 构造的 terminal error assistant。
2. reasoning `output_item.done`：保存完整 item 为 Thinking replay state。
3. message `output_item.done`：保存 item id/phase 为 Text replay state。
4. terminal `response.output` 中存在同 reasoning id 的 `encrypted_content` 时，回填到 state；已有 encrypted content 不覆盖。
5. `response.failed`/adapter error 可以带 source model，但 planner 仍按 stop reason 跳过失败 turn。

### 9.3 Request reasoning policy

保持 `ThinkingLevel.PROVIDER_DEFAULT` 不主动发送 reasoning effort。

- 显式非 OFF reasoning：发送 effort、`summary:"auto"` 和 `include:["reasoning.encrypted_content"]`。这与 pi 一致（pi 只在发送 `reasoning` 对象时附带 include）。
- PROVIDER_DEFAULT 不设置 reasoning object 或 summary；当 capabilities 明确模型支持 reasoning 时，仍发送 `include:["reasoning.encrypted_content"]`，它只要求返回可重放状态，不改变 reasoning effort。**这是对 pi 的有意偏离且尚未经 live API 验证**（pi 不会在没有 `reasoning` 对象时单独发 include，xai 除外）。OpenAI 文档将 `include` 定义为独立顶层参数，理论可行。
- capabilities 未知且 PROVIDER_DEFAULT 时不猜测，不发送 include。
- same-model plan 正在 replay reasoning 时，无论 capabilities catalog 是否缺失，都发送 include。
- OFF 不发送 summary/include。
- **决策点与回退**：standalone include 是否被官方 API 接受，由 PR 4 的可选 live probe（§11.3）验证。若被拒绝，回退为"只在显式非 OFF reasoning 或 same-model replay 时发送 include"，即完全对齐 pi。回退的后果是 PROVIDER_DEFAULT 下 reasoning item 没有 `encrypted_content` → planner 按 §9.4 规则丢弃 reasoning 并删除 function-call item id，仍是安全路径，只损失 reasoning 连续性。live probe 不作为 worker 决策或合并门槛；若需回退，必须回到 parent 修改本方案，不能由 worker 临场改变策略。

### 9.4 Transcript planning algorithm

planner 按顺序扫描 request-local `List<Message>`，绝不修改持久 transcript。

#### 来源分类

| 分类 | 条件 |
| --- | --- |
| SAME_MODEL | sourceModel 与 target `ModelRef` 完全相等 |
| SAME_PROVIDER_DIFFERENT_MODEL | provider/api 相同，modelId 不同 |
| FOREIGN_OR_UNKNOWN | provider/api 不同或 sourceModel 缺失 |

#### Assistant 规则

- `ERROR`/`ABORTED`：整个 assistant 不发；记录其中 tool-call ids 为 suppressed。
- SAME_MODEL：
  - 只重放 format 已知、JSON 合法且含可用 encrypted content 的 reasoning item；
  - Text state 合法时保留 message id/phase；否则生成稳定 request-local message id；
  - function-call item id 只在同一个 assistant turn 已重放有效 reasoning state 时保留，否则删除。**这是对 pi 的有意简化**：pi 同模型一律保留 `fc_` id；Jcode 只在 id 真正参与 reasoning pairing 时保留，非 reasoning 模型（如 gpt-4.1）同模型重放时 `fc_` id 会一律省略。OpenAI 接受无 `id` 的 `function_call` input item，因此这是安全的；代价是 `OpenAiToolCallIds` 保存的 item_id 只在 reasoning 场景被使用。
- SAME_PROVIDER_DIFFERENT_MODEL / FOREIGN_OR_UNKNOWN：
  - 不解释或发送 replay state；
  - 首批保守丢弃 Thinking，不自动提升为 assistant visible text；后续若引入明确 redacted/visibility 语义再评估转换；
  - Text 使用新稳定 id，不复用 phase；
  - function-call item id 一律删除。

#### Tool-call id 规则

- 合法 `oai1:`：严格 decode；同模型按上述 pairing 规则决定是否保留 item id。
- `oai1:` 前缀但 payload 损坏：继续视为内部数据损坏并 mapping error，不静默当 raw id。
- safe raw id：作为 call id，item id 为空。
- foreign/unknown unsafe raw id：确定性映射为 `call_jcode_<sha256-prefix>`，长度不超过 64。
- 同一原始 id 的 tool result 必须复用同一个 normalized call id。
- 首批不为 foreign call 人工生成 `fc_*` item id；省略 item id 更安全。

#### Tool-call sequence 规则

- accepted assistant 的 tool calls 进入 pending set。
- 后续匹配 tool results 按源顺序写入并 fulfill pending。
- 遇到下一个 user/assistant 或 transcript 结束前，对未 fulfill call 写入 synthetic error output：`"No result provided"`。
- 属于 skipped assistant 的 tool results 丢弃。
- 没有任何已接受 call 的孤立 tool result 丢弃，不提升为 user role，避免 tool output 获得更高指令优先级。

### 9.5 修改文件

新增：

- `ai-providers/src/main/java/site/pplee/jcode/aiproviders/openai/OpenAiReplayStateCodec.java`
- `ai-providers/src/main/java/site/pplee/jcode/aiproviders/openai/OpenAiTranscriptPlanner.java`
- `ai-providers/src/test/java/site/pplee/jcode/aiproviders/openai/OpenAiReplayStateCodecTest.java`
- `ai-providers/src/test/java/site/pplee/jcode/aiproviders/openai/OpenAiTranscriptPlannerTest.java`

修改：

- `OpenAiRequestMapper.java`
- `OpenAiEventMapper.java`
- `OpenAiResponsesAdapter.java`
- `OpenAiToolCallIds.java`
- 对应 request/event/adapter/id tests。
- **测试 fixture 改造（必须计入本 PR 工作量）**：mapper 开始为 text/thinking 写入 `replayState`、为 assistant 写入 `sourceModel` 后，`OpenAiEventMapperTest`/`OpenAiResponsesAdapterTest` 中所有形如 `assertEquals(List.of(new Content.Text("x")), ...)` 或直接比较 `Message.Assistant` 的断言都会因 record equals 不等而失败。需要改为按字段断言（`text()`/`stopReason()`/`usage()` 等）或提供 test-support 的 "strip replay metadata" helper 后再比较；不得通过让 mapper 在测试模式下不写 metadata 来回避。

### 9.6 TDD 场景

1. same-model reasoning item 在 function call 前重放，tool output 继续用匹配 call id。
2. terminal response 对 encrypted reasoning 做 backfill。
3. output-item done 已有 encrypted content 时不覆盖。
4. same-provider different-model 不发 reasoning state、不发 item id。
5. sourceModel 缺失时走 FOREIGN_OR_UNKNOWN。
6. error/aborted assistant 及其 tool results 被过滤。
7. user boundary 和 transcript end 前分别补 synthetic output。
8. 孤立 tool result 被过滤。
9. foreign unsafe raw call id 被确定性 hash，matching result 同步更新。
10. malformed replay state 被忽略且 item id 被删除。
11. malformed `oai1:` 继续安全失败。
12. mapper 输出 reasoning include/summary policy 符合 ThinkingLevel 契约。
13. fake-server 两轮 payload：首轮 reasoning + function call，第二轮 input 顺序为 reasoning → function_call → function_call_output。

### 9.7 回滚点

PR 4 可与 PR 3 成对回滚。不得只保留 provider 对 replay state 的解释而回滚 public state seam。

## 10. PR 5 — Stream/event fidelity

对应 OAI-005～OAI-009。PR 4 已负责保存 replay state；本 PR 负责 visible content 和事件生命周期保真。

### 10.1 OAI-005 reasoning summary

- 处理 `response.reasoning_summary_part.done`，追加并发出确定性的 `"\n\n"` ThinkingDelta。
- reasoning final 优先：`item.summary`（`\n\n` join）→ `item.content` → accumulated deltas。
- replay state 仍保存完整原始 item，不由 visible finalizer 重建。

### 10.2 OAI-006 message/refusal/phase

- message final 分别读取 `output_text.text` 和 `refusal.refusal`。
- 多个 message content blocks 直接拼接，不由 adapter 插入换行。
- canonical final 内容缺失时才回退 accumulated deltas。
- item id/phase 已通过 PR 3/4 的 Text replay state 保存；本 PR 验证 finalizer 不丢失它。

### 10.3 OAI-007 done without added

抽取 slot 创建 helper。`output_item.done` 无 active slot 时：

1. 从 done item 创建对应 slot；
2. emit `*Start`；
3. canonical finalize；
4. emit `*End`。

维护 active/completed output index 状态：

- duplicate done 不创建重复 block；
- active slot 与 done item type 冲突时 mapping error；
- 每个 block 恰好一次 Start/End。

### 10.4 OAI-008 partial tool arguments

> 范围说明：本节是首批中唯一可整体拆出的项。`OpenAiPartialJsonParser` 只影响 partial 事件中参数的预览质量，不影响最终 transcript 正确性（`function_call_arguments.done`/`output_item.done` 始终权威）。若 PR 5 体量需要控制，可只保留 buffer 播种（第一条），把 parser 及其用例表推迟到后续批次，并同步从 §14 移除 "partial args" 条目。buffer 播种本身不可拆出，因为它同时修复 `onArgumentsDone` 的 `startsWith(previous)` 补 delta 逻辑。

- `ToolCallSlot.buffer` 从 `output_item.added.item.arguments` 初始化。
- 新增 package-private `OpenAiPartialJsonParser`，不引入第三方依赖。
- parser 先尝试严格 Jackson parse；失败后只执行有界、确定性的结构闭合，不猜测新字段。
- repair 只接受 object root，单次 O(n) 扫描；最大嵌套深度 128，超过 1 MiB 的 partial buffer 不做 repair；追加字符不得超过当前未闭合 string/容器所需的闭合字符。
- 无法安全闭合时保留上一个有效 `JsonNode`。
- 最小用例表：

| partial | best-effort 结果 |
| --- | --- |
| `{"city":"Lo` | `{"city":"Lo"}` |
| `{"a":1,` | 去掉尾逗号并闭合为 `{"a":1}` |
| `{"a":{"b":1` | 闭合两层 object |
| `{"a":` | 不猜值，保留上一有效节点 |
| `{"a":tru` | 不补 literal，保留上一有效节点 |
| dangling escape / 错误嵌套 / 非 object root | 保留上一有效节点 |

- `function_call_arguments.done` 与 `output_item.done` 始终是最终权威值。
- scratch buffer 不进入 final transcript 或 replay state。

### 10.5 OAI-009 SSE semantic event name

`OpenAiSseParser` 继续只负责 framing。adapter 在 JSON parse 后解析 semantic name：

1. SSE name 为 blank/`message`：使用 textual JSON `type`。
2. SSE name 非 generic，JSON `type` 缺失：使用 SSE name，兼容现有 fixture。
3. 两者都存在且一致：正常处理。
4. 两者都存在但冲突：terminal `Error(ERROR)`，不静默选择。
5. generic name 且 JSON type 缺失：忽略非语义 heartbeat/object，不制造 provider event。

### 10.6 事件顺序不变量

1. adapter-level Start 永远第一。
2. 每个 content block 恰好一次 block Start，并早于 Delta/End。
3. done-only recovery 在 terminal response 前产生 block Start → End。
4. delta 对应的 partial 已包含该 delta。
5. `function_call_arguments.done` 最多补 ToolCallDelta，不产生 ToolCallEnd。
6. ToolCallEnd 只由 `output_item.done` 产生。
7. terminal 后不再有任何 content event。

### 10.7 修改文件

新增：

- `ai-providers/src/main/java/site/pplee/jcode/aiproviders/openai/OpenAiPartialJsonParser.java`
- `ai-providers/src/test/java/site/pplee/jcode/aiproviders/openai/OpenAiPartialJsonParserTest.java`

修改：

- `OpenAiEventMapper.java`
- `OpenAiResponsesAdapter.java`
- 必要时仅调整 `OpenAiSseParser` framing tests，不在 parser 内放 JSON 语义。
- `OpenAiEventMapperTest.java`
- `OpenAiResponsesAdapterTest.java`
- `OpenAiSseParserTest.java`

### 10.8 TDD 场景

- summary multipart boundary；
- summary > content > deltas precedence；
- refusal-only final；
- message 多 block 无额外 separator；
- done-only message/reasoning/function call；
- duplicate done 与 type conflict；
- initial arguments + later delta；
- incomplete object/string 的 bounded partial parse；
- final args 覆盖 partial；
- data-only SSE type fallback；
- named event + matching type；
- event/type conflict → Error；
- 现有 named event 无 JSON type 路径继续工作。

### 10.9 回滚点

PR 5 不改 public seam，可按 OAI-005～OAI-009 的独立行为提交做语义回滚；若实现集中在同一 mapper commit，回滚时必须保留 PR 4 的 replay state capture/codec 调用，不能整体恢复到 PR 4 前的 mapper。

## 11. 跨 PR 验证契约

### 11.1 每个 PR 的最低验证

PR 1：

```bash
mvn -pl ai-providers -am \
  -Dtest=OpenAiEventMapperTest,OpenAiResponsesAdapterTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
mvn -pl ai-providers -am test
```

PR 2：

```bash
mvn -pl ai test
mvn -pl agent-core -am test
mvn -pl ai-providers -am test
```

PR 3：

```bash
mvn -pl ai test
mvn -pl agent-core -am test
```

PR 4、PR 5：

```bash
mvn -pl ai-providers -am test
```

每个 PR 完成前：

```bash
mvn verify
```

### 11.2 必须返回的实施证据

每个 writer handoff 必须列出：

- changed files；
- 新增/修改 tests；
- 实际执行命令与退出码；
- 失败或跳过的验证；
- residual risks；
- staged files 状态；
- 与本方案不同的实现决策及原因。

### 11.3 可选 live probe

只有在本地凭证可用且用户明确同意时，PR 4 可增加一次最小 live probe：

1. reasoning model 产生 reasoning + function call；
2. `store:false`；
3. 第二轮重放 reasoning state + function output；
4. 观察无 400 且正常完成。

live probe 是补充证据，不能替代 deterministic fake-server tests。

## 12. 风险与处置

### 12.1 Replay state 变成万能 metadata

风险：public state 演变为 provider hints bag。

处置：只允许 `format + payload`，无 map、无属性访问、无公共 decoder；每个 adapter 只解释自身已知 format。

### 12.2 Chain-of-thought 跨模型泄露

风险：把 Thinking 自动转成 Text 会提升可见性。

处置：首批跨模型直接丢弃 Thinking；后续只有在引入明确 visibility/redacted 语义后才允许转换。

### 12.3 Cancellation callback 与 producer 并发

风险：terminal 重复、terminal 后 delta、读取 mapper 可变状态。

处置：AssistantMessageStream push 线性化；ActiveExchange completeOnce；partial 使用 immutable atomic snapshot；callback 只通过统一路径完成。

### 12.4 Record component 变更

风险：equals/hashCode/toString 和 source compatibility 变化。

处置：保留兼容构造器；测试 equality；replay payload toString redacted；在 PR 3 单独完成接口评审。PR 3 本身不写入任何 metadata，因此现有 equality 断言在 PR 3 后仍成立；真正的断言失效发生在 PR 4（见 §9.5），必须在 PR 4 而不是 PR 3 处理。

### 12.5 Partial JSON 猜测过度

风险：partial 中出现模型尚未真正输出的参数。

处置：只闭合已有 token 结构，不补值或字段；无法安全闭合时保留上一有效节点；final done 始终权威。

### 12.6 Compatible endpoint 差异

风险：严格 event/type conflict 可能拒绝 buggy proxy。

处置：首批以安全失败为准；如果后续要支持具体 proxy，通过 provider-local capability 明确放宽，不做静默全局兼容。

## 13. 子 agent 实施与评审方式

每个 PR 使用同一串行 orchestration：

```text
read-only planner/reviewer
  → parent 确认当前 PR scope
  → 一个 async worker（唯一 writer）
  → 三个 fresh reviewer：correctness / tests / simplicity
  → parent 汇总 accepted findings
  → 一个 fix worker
  → focused re-review（如发生非平凡修复）
  → parent final diff + mvn verify
```

约束：

- 不允许多个 writer 同时修改 active worktree。
- reviewer 不修改文件。
- worker 遇到公共接口、产品范围或安全决策偏离方案时必须向 parent 升级。
- PR 3 的 public seam 必须独立 architecture review。
- PR 4 必须增加一个专门检查 reasoning replay/pairing 的 reviewer lane。
- PR 2 必须增加一个专门检查并发竞态和资源关闭的 reviewer lane。

## 14. Definition of Done

首批只有同时满足以下条件才算完成：

- [x] OAI-003 的四类 incomplete reason 均有 deterministic tests。
- [x] cancellation 可以中断 waiting headers 和 silent SSE body。
- [x] provider close 能收敛 active streams。
- [x] terminal event 恰好一次，terminal 后没有 delta/end。
- [x] `ModelReplayState` payload 不出现在日志/toString。
- [x] same-model reasoning + tool result 第二轮 payload 顺序正确。
- [x] same-provider different-model 不发送 reasoning state 或 function-call item id。
- [x] error/aborted turns、missing result、orphan result 均不会形成非法 input sequence。
- [x] reasoning encrypted content 可从 terminal response output 回填。
- [x] reasoning summary/refusal/done-only/partial args/data-only SSE 均有 tests。
- [x] `agent-core` 没有 OpenAI-specific 分支或依赖。
- [x] 没有 provider SDK、轮询、session storage 或 `Map<String,Object>`。
- [x] 最终 `mvn verify` 成功。
- [ ] 每个 PR 的 fresh reviewers：本批在同一工作树串行落地，未拆成 5 个独立 PR 走 reviewer lane。

## 15. 归档规则

本方案实施完成后，将本文件移动到：

```text
docs/plans/archived/openai-responses-correctness-first-batch.md
```

同时更新：

- `AGENTS.md`；
- `ai-providers/AGENTS.md`；
- 已解决的 `docs/issues/openai-responses-protocol-parity.md` 条目状态或对应 GitHub Issues。
