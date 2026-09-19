# Coding Agent 第一阶段：Headless 产品闭环实施计划

> 状态：已完成（2026-09-18）
>
> 父计划：[`coding-agent-development-roadmap.md`](../coding-agent-development-roadmap.md)
>
> Jcode 基线：`663c3e28ac9219c12aa2bb29c00ffef59c97796c`（2026-09-17）
>
> pi 源码基线：`60e7e76bd7ea25cad1dd6f3f1ce0d18814a42759`（`@earendil-works/pi-coding-agent` 0.85.1，2026-09-16）
>
> 实施范围：新建 `coding-agent` 模块，交付 `CodingAgentSession + SystemPromptBuilder + ReadTool` 的首个纵向切片

## 1. 决策摘要

第一阶段不建立空模块，也不尝试一次实现完整 coding product。交付物必须形成以下真实闭环：

```text
String prompt
  -> CodingAgentSession
  -> Agent.prompt(Message.User)
  -> ModelClient
  -> Content.ToolCall(read)
  -> ReadTool
  -> Message.ToolResultMessage
  -> ModelClient second turn
  -> final assistant
  -> CodingAgentRunResult
```

本阶段做出以下决定：

1. `coding-agent` 首先是可嵌入 library，没有 `main`、Spring Boot、TUI 或 CLI。
2. `CodingAgentSession` 是唯一产品门面，底层 `Agent` 不从 public API 暴露。
3. 调用方显式注入 `ModelClient` 和 `ModelRef`；本阶段不读取 API Key、环境变量或配置文件。
4. 首个内置工具只实现 `read`，因为它足以证明工作目录、工具 schema、执行、结果写回和第二轮模型调用。
5. System Prompt 由纯 builder 构造；文件发现和 AGENTS.md 注入留到阶段三。
6. 普通产品事件封装底层 `AgentEvent` 的防御性快照，完成事件只携带产品结果，不暴露工具实例；保留后续加入 Session/compaction/resource 事件的扩展空间。
7. 所有测试使用 deterministic `ModelClient` 和临时目录，默认不访问真实 OpenAI。
8. 不修改 `ai` 或 `agent-core` 公共契约；若实现中发现阻塞缺口，先回到本计划记录证据和决策。

## 2. 目标

第一阶段完成后必须具备：

- 一个真实存在且受 Maven reactor 管理的 `coding-agent` 模块；
- 一个不可并发运行、可取消、可关闭的 `CodingAgentSession`；
- 结构不可变的 config，以及通过构造和访问时防御性复制保护的 state/result/event 快照；
- 基于实际工具列表生成的最小 coding System Prompt；
- 能读取文本和受支持图片的 `read` 工具；
- 统一的行数/UTF-8 字节数 head truncation；
- 一条完整的 fake-model coding turn 集成测试；
- 清晰的模块边界、错误、事件 backpressure 和资源所有权契约。

## 3. 非目标

本阶段明确不实现：

- `write`、`edit`、`bash`、`grep`、`find`、`ls`；
- Session 文件、Session 树、恢复或分支；
- AGENTS.md、Skill、Prompt Template、Extension；
- config file、credential store、环境变量读取；
- OpenAI Provider 自动装配；
- model/thinking 动态切换；
- compaction 或 token threshold；
- tool approval UI、TUI、CLI、RPC、HTTP Server；
- project trust；
- 图片 resize、OCR 或视频读取；
- 独立 storage/tools/common/shared Maven 模块；
- provider 自动发现、`ServiceLoader` 或静态 registry。

## 4. 当前 seam 评估

### 4.1 可直接复用

本阶段可以直接依赖：

- `Agent`：prompt、steer、followUp、abort、context、state、close；
- `AgentConfig`：模型、client、工具、事件、hook、queue 和请求选项；
- `AgentContext`：system prompt、transcript、工具；
- `AgentEventSink`：已归约状态后的异步事件投递；
- `AgentTool<A>`：强类型参数、schema、执行和进度；
- `ToolExecutionResult`：成功和可反馈给模型的失败；
- `ModelClient`：provider-neutral 模型调用；
- `Message.User` / `Content.Text` / `Content.Image`；
- `LoopResult`：最终 context 与本 run append log。

### 4.2 本阶段不要求下沉的新能力

以下需求可以完全在产品层组合，不应修改 `agent-core`：

- `String` 到 `Message.User` 的构造；
- working directory；
- System Prompt 装配；
- 默认工具选择；
- 产品事件包装；
- 产品状态快照；
- `LoopResult` 到产品结果的转换；
- read 的路径、I/O 和截断。

### 4.3 复用时必须补齐的边界

- `Content.ToolCall.arguments` 与部分 stream delta 携带可变 `JsonNode`。产品事件、状态和结果需要递归快照，不能仅使用 `List.copyOf()` 或 record 包装。
- `AgentCompleted` 携带的 `LoopResult` 包含完整 `AgentContext` 及可执行工具实例；它不能直接作为产品 public payload，须投影为 `CodingAgentRunResult`。
- `ToolSchemaValidator` 只执行 `type/required/properties` 等基础约束，不执行 `minimum`、`maximum` 或 `additionalProperties`。`ReadTool` 的范围和额外字段校验必须在工具准备阶段补齐，不能依赖 ObjectMapper 的未知字段/coercion 设置。
- `Agent.steer/followUp` 不与 run 收尾原子绑定，队列跨 run 保留。首批采用 §8.6 的 Session 级队列语义，不承诺严格绑定当前 run；若未来要求这种保证，应先评估 core 的原子接纳 seam。

### 4.4 已知后续限制

当前 `AgentConfig` 的 model、thinking 和 request options 在 Agent 生命周期内大体固定。第一阶段采用单模型 Session，不提前为阶段五修改内核。后续 idle model switch 可以优先通过保留 context 并重建 Agent 实现；只有证明该方案破坏既有不变量时，才评估新增 core seam。

## 5. Maven 模块与边界

### 5.1 Reactor 调整

根 `pom.xml` 增加：

```xml
<module>coding-agent</module>
```

顺序放在 `agent-core` 之后：

```text
ai -> ai-providers -> agent-core -> coding-agent
```

reactor 顺序不是依赖事实来源，真实顺序仍由 Maven dependencies 决定。

### 5.2 Dependency management

根 POM 为以下内部 artifact 提供 `${project.version}` 管理：

- `ai`
- `ai-providers`
- `agent-core`
- `coding-agent`（供未来 product module 使用）

本阶段 `coding-agent/pom.xml` 直接依赖：

```text
site.pplee:ai
site.pplee:agent-core
com.fasterxml.jackson.core:jackson-databind
org.junit.jupiter:junit-jupiter (test)
```

虽然 `agent-core` 会传递 `ai` 和 Jackson，`coding-agent` 的 public/source API 直接使用这些类型，因此必须声明 direct dependency，避免依赖偶然的 transitive graph。

本阶段不依赖 `ai-providers`。

### 5.3 Enforcer 重构

根 POM 当前的全局 `bannedDependencies` 会让未来产品模块也禁止依赖 `agent-core`。第一阶段必须把它改为按模块生效的规则，而不是简单跳过 enforcer。

目标规则：

| 模块 | 允许的 Jcode 内部依赖 | 明确禁止 |
|---|---|---|
| `ai` | 无 | `ai-providers`、`agent-core`、`coding-agent`、`server`、`tui` |
| `ai-providers` | `ai` | `agent-core`、`coding-agent`、`server`、`tui` |
| `agent-core` | `ai` | `ai-providers`、`coding-agent`、`server`、`tui` |
| `coding-agent` | `ai`、`agent-core`；阶段五可增加 `ai-providers` | `server`；本阶段不依赖 `tui` |

实施方式应选择“根 POM 管理插件版本，各模块声明同 id 的边界 execution”，或等价的可读方案。禁止通过全局 `skip`、删除 enforcer 或只靠注释解决。

### 5.4 模块文档

新增 `coding-agent/AGENTS.md`，至少记录：

- 产品层职责与依赖边界；
- `CodingAgentSession` 是统一入口；
- built-in tools 属于本模块；
- 配置和工具显式装配；
- 不允许 provider wire 逻辑、UI 逻辑和动态 classpath discovery；
- 测试、注释和取消规则。

同步更新根 `AGENTS.md` 的结构、模块状态、命令和 Notes。

## 6. 建议源码结构

第一阶段目标结构：

```text
coding-agent/
├── AGENTS.md
├── pom.xml
└── src/
    ├── main/java/site/pplee/jcode/codingagent/
    │   ├── CodingAgentSession.java
    │   ├── CodingAgentConfig.java
    │   ├── CodingAgentState.java
    │   ├── CodingAgentRunResult.java
    │   ├── event/
    │   │   ├── CodingAgentEvent.java
    │   │   └── CodingAgentEventSink.java
    │   ├── prompt/
    │   │   └── SystemPromptBuilder.java
    │   └── tool/
    │       ├── ReadTool.java
    │       ├── ReadToolArguments.java
    │       ├── OutputTruncator.java
    │       └── TruncatedOutput.java
    └── test/java/site/pplee/jcode/codingagent/
        ├── CodingAgentSessionTest.java
        ├── CodingAgentIntegrationTest.java
        ├── prompt/SystemPromptBuilderTest.java
        ├── tool/ReadToolTest.java
        ├── tool/OutputTruncatorTest.java
        ├── smoke/SmokeTest.java
        └── support/
```

实际实现可以合并纯内部小类型，但不得把 prompt、read I/O 和 event mapping 都堆入 `CodingAgentSession`。

## 7. `CodingAgentConfig`

### 7.1 建议配置面

配置 record 至少包含：

```java
public record CodingAgentConfig(
        Path workingDirectory,
        ModelRef model,
        ModelClient modelClient,
        ObjectMapper objectMapper,
        ThinkingLevel thinkingLevel,
        ModelRequestOptions requestOptions,
        QueueMode steeringMode,
        QueueMode followUpMode,
        String customSystemPrompt,
        String appendSystemPrompt,
        CodingAgentEventSink eventSink,
        Clock clock
) {
}
```

字段命名可在实现前做小幅整理，但必须保持这些事实显式可见，不能从环境变量、用户目录或静态状态隐式推导。

### 7.2 默认值

允许在 compact constructor 中应用：

- `thinkingLevel == null` → `PROVIDER_DEFAULT`；
- `requestOptions == null` → `ModelRequestOptions.defaults()`；
- queue mode 为 null → `ONE_AT_A_TIME`；
- `customSystemPrompt == null` → 使用内建默认 prompt；
- `appendSystemPrompt == null` → 不追加；
- `eventSink == null` → noop；
- `clock == null` → `Clock.systemUTC()`。

不得默认创建：

- API Key；
- OpenAI Provider；
- `ObjectMapper` 全局 singleton；
- 当前进程 cwd；
- 用户主目录配置。

调用方必须显式提供 working directory、model、model client 和 object mapper。

### 7.3 校验

构造时必须校验：

- working directory 非 null、存在且为 directory；
- 解析为 normalized absolute path；
- model/modelClient/objectMapper 非 null；
- prompt 字段保持原文本，不 trim 用户内容；
- config `toString()` 不包含未来可能出现的 secrets。

是否解析 symlink 为 real path 必须固定一种行为。推荐保留 normalized absolute path 作为用户可见 working directory，具体 I/O 交给文件系统解析 symlink，避免 prompt 中路径与用户输入无故变化。

config 的“不可变”指字段绑定及值配置不变，不意味着注入的 `ModelClient`、`ObjectMapper` 或 sink 是深层不可变对象。它们仍是显式运行时依赖：调用方不得在 Session 存活期间重新配置共享 ObjectMapper；client/sink 按其线程安全和所有权契约使用。

## 8. `CodingAgentSession`

### 8.1 所有权

Session 构造时：

1. 根据 config 建立唯一 `ReadTool`；
2. 使用工具真实 spec 构建 System Prompt；
3. 建立空 transcript 的 `AgentContext`；
4. 把产品 event adapter 装入 `AgentConfig.eventSink`；
5. 创建并独占一个 `Agent`。

Session 关闭时：

- 幂等关闭底层 Agent；
- active run 按 Agent 既有契约被取消；
- 后续 prompt/steer/followUp 明确失败；
- 本阶段无 provider 所有权，不能擅自关闭调用方注入的 `ModelClient`。

### 8.2 Public API 草案

```java
public final class CodingAgentSession implements AutoCloseable {
    public CodingAgentSession(CodingAgentConfig config);

    public CompletionStage<CodingAgentRunResult> prompt(String text);
    public void steer(String text);
    public void followUp(String text);
    public void abort();

    public CodingAgentState state();
    public boolean isRunning();
    public Path workingDirectory();

    @Override
    public void close();
}
```

本阶段不暴露：

- `Agent agent()`；
- 可变 messages list；
- `setModel()`；
- `reload()`；
- `compact()`；
- Session file path。

本阶段不增加 image user prompt overload；首批只接受文本用户输入。后续增加时必须使用强类型 `Content`，不能用 `Object` 或 `Map` 接受任意 payload，且简单文本 API 必须保留。

### 8.3 用户消息构造

`prompt(String)`：

- text 非 null；
- 拒绝长度为零的字符串，但保留非空字符串中的全部空白，不做 trim；
- 创建 `Message.User(List.of(new Content.Text(text)), clock.instant())`；
- timestamp 使用 config 中的 `Clock`，不使用静态可变 clock。

`steer` / `followUp` 使用同一用户消息构造路径，避免时间和校验规则漂移。

### 8.4 运行结果

产品结果应复制而不是暴露可变内部对象：

```java
public record CodingAgentRunResult(
        List<AgentMessage> newMessages,
        Message.Assistant finalMessage
) {
    public boolean aborted() {
        return finalMessage.stopReason() == StopReason.ABORTED;
    }
}
```

该结果让调用方无需读取内部 Agent 即可获得本 run 的最终消息和 append log。

规则：

- `newMessages` 集合不可修改；其中的消息及 `finalMessage` 按 §8.7 隔离嵌套可变对象；
- `finalMessage` 是该 run 最后完成的 assistant，正常和 terminal failure 都可表达；
- `aborted` 是派生方法，不保存第二个可能冲突的布尔事实；
- 不把 infrastructure exception 伪装成正常模型结果。

### 8.5 Session state

```java
public record CodingAgentState(
        boolean running,
        AgentMessage streamingMessage,
        Set<String> pendingToolCalls,
        String errorMessage
) {
}
```

`streamingMessage/pendingToolCalls/errorMessage` 从 `Agent.state()` 派生，并按 §8.7 返回防御性快照。`running` 由 Session 接纳/收尾状态机决定，与 `isRunning()` 使用同一事实来源，不直接转发底层 `Agent.isRunning()`。本阶段 transcript 不在 state 中复制；后续 Session 恢复和 branch 由独立 API 提供。

### 8.6 并发与队列归属

- Session 的 run 生命周期从 `prompt()` 成功接纳开始，到底层 stage 完成且产品收尾状态转换结束；并发 `prompt()` fail fast，不排队。底层 Agent 的 active-run guard 仍保留。
- `steer()` / `followUp()` 仅在 Session 的 run 生命周期内、且尚未 close 时允许调用；合法性检查与入队在同一同步边界内完成，禁止先查询 `Agent.isRunning()` 再入队。
- 底层 `AgentCompleted` 到产品 stage 完成之间可能仍处于收尾期。收到完成事件不等于允许重入 `prompt()`；调用方应等待返回的 stage。
- 同步状态机只保证接纳、收尾和 close 的线性化，不能与底层 queue drain 原子化。方法成功只表示已进入 **Session 级队列**，不保证在当前 run 消费；首批不提供 run-bound 或可靠消息投递协议。
- 最后一次 drain 后入队的消息，以及模型失败/abort 时尚未 drain 的消息，保留至同一 Session 后续 run 的相应 drain 点。新 `prompt()` 不隐式清空它们，`abort()` 也不等价于清队列；不承诺这些消息先于新 prompt 执行。
- 已被 drain 的消息不因后续取消或基础设施失败而自动恢复入队。入队不是 transcript 提交或持久化保证；不得通过盲目补发伪造可靠投递。
- `close()` 后拒绝所有新消息，剩余队列随 Session 退役，不交给新的 Session。首批无队列检查/清空 API；需要保证旧队列不再参与后续运行时，应关闭并新建 Session。
- `abort()` 幂等；`close()` 与 active run 并发时不死锁。锁内不等待 run/sink stage、不调用用户 sink，也不执行可能等待 worker 的 `Agent.close()`。
- Session 自身不建立额外无界 executor。

上述保留策略是适配当前 core 的显式取舍。若未来要求“仅投递到当前 run”“结束时返回未消费消息”或队列清空，须先补充通用 core seam 与兼容性设计，不能仅靠产品层锁宣称实现。

### 8.7 防御性快照契约

上述 record 为 API 形状草案，不是可直接采用的空实现。state/result/event 的构造和 accessor 都必须保护内部快照：

- 对 `StandardAgentMessage`、`Message`、`Content` 及 `AssistantMessageEvent` 按已知类型重建必要的嵌套结构；所有可变 `JsonNode` 均使用 `deepCopy()`，集合使用不可修改副本。不可变文本、图片 base64 字符串及其他不可变值可以共享。
- 构造时隔离 runtime 的对象；访问时对包含可变节点的分支再次复制。调用方修改一次 accessor 返回的参数树，不得改变 runtime、快照内部值、同一快照的后续读取或其他事件/state/result。
- 不宣称 Jackson `JsonNode` 本身 immutable；保证的是快照的内部值稳定，以及调用方拿到的可变树与运行时隔离。只在构造时 deep copy、随后直接暴露同一个节点仍不满足契约。
- snapshot 逻辑集中在产品层内部的小型 mapper 中，不通过 JSON 序列化往返，不复制或暴露 `Agent`、`AgentTool`、client、sink 等可执行依赖。
- 首批只生成标准消息，不接受任意自定义 `AgentMessage`。后续扩展消息类型时必须同时定义其快照策略，不以返回未知原对象作为 fallback。
- event/state/result 共用同一消息快照规则，但不共享对外可变节点；快照失败按基础设施失败处理，不伪装为 read 或模型失败。

## 9. 产品事件

### 9.1 事件类型

第一阶段定义最小 sealed event，以下省略 §8.7 要求的防御性构造和 accessor：

```java
public sealed interface CodingAgentEvent
        permits CodingAgentEvent.RuntimeEvent, CodingAgentEvent.RunCompleted {

    record RuntimeEvent(AgentEvent event) implements CodingAgentEvent {}

    record RunCompleted(CodingAgentRunResult result) implements CodingAgentEvent {}
}
```

- 除 `AgentCompleted` 外，底层事件按 §8.7 生成快照后包装为 `RuntimeEvent`，包含 `MessageUpdated.delta` 中的 partial/arguments，不能只复制顶层 message。
- `AgentCompleted` 在原位置转为 `RunCompleted`，只保留 `newMessages/finalMessage`；`RuntimeEvent` 必须拒绝包装 `AgentCompleted`，避免经 `LoopResult.context().tools()` 暴露可执行工具实例。
- 完成事件和返回 stage 的产品结果具有相同消息值，但不共享对外可变节点。完成事件投递失败仍导致 stage 异常完成；该事件不是基础设施失败也必达的提交确认。

后续阶段可以增加 Session、compaction、resource 等产品事件，而不修改底层 `AgentEvent`。不要为 `MessageStarted`、`ToolStarted` 等每个底层事件再复制一套产品 record；重建防御性快照不等于维护第二套运行时事实。

### 9.2 Sink

```java
@FunctionalInterface
public interface CodingAgentEventSink {
    CompletionStage<Void> emit(CodingAgentEvent event);

    static CodingAgentEventSink noop();
}
```

契约：

- sink 可能由 loop virtual thread 或 tool worker 调用；
- 实现必须线程安全；
- Session 等待 returned stage，保持底层 backpressure；
- sink 失败是基础设施失败，不改写成 tool error；
- TUI 将来应快速 enqueue 到 UI event loop，而不是在 sink 内直接做慢渲染；
- 第一阶段不实现全局 event bus 或静态 listener registry。

### 9.3 顺序

产品 adapter 不重排底层事件；`RunCompleted` 占据底层 `AgentCompleted` 的投递位置，不在 future 回调中再发一次。调用方看到产品事件时，底层 `AgentState` 已完成对应归约；此时 Session 仍可能处于 §8.6 定义的收尾期。

## 10. `SystemPromptBuilder`

### 10.1 输入

Builder 使用显式 immutable 输入：

- normalized working directory；
- 实际启用的 `ToolSpec`；
- optional custom prompt；
- optional append prompt。

它不读取文件、环境变量、classpath resource 或用户目录。

### 10.2 默认 Prompt 内容

默认内容只包含：

1. coding assistant 身份；
2. 可用工具的名称和简短描述；
3. 精炼、路径清晰、先读取再修改等基础 guideline；
4. 当前工作目录。

Prompt 中必须使用 Jcode 自身命名，代码和配置中不得显式提及参考项目。

### 10.3 Custom 与 append 语义

- `customSystemPrompt != null`：完整替换默认主体；
- `appendSystemPrompt != null`：追加到默认或 custom 主体；
- 工具真实可用性和 cwd 始终进入最终 prompt；
- 用户文本不做 trim/normalize；
- section 之间空行规则固定并由测试锁定。

### 10.4 工具描述

System Prompt 的工具列表必须由最终启用工具导出，不能写死 `read` 字符串后再与工具 registry 漂移。第一阶段只有 `read`，但实现应接受 ordered list。

### 10.5 测试方式

避免对整段英文做脆弱 snapshot。测试：

- section 顺序；
- tool name/description 恰好一次；
- normalized cwd；
- custom 替换；
- append 追加；
- 用户空白和特殊字符保留。

## 11. `ReadTool`

### 11.1 Tool contract

工具名固定为 `read`。

参数：

```java
public record ReadToolArguments(
        String path,
        Integer offset,
        Integer limit
) {
}
```

JSON Schema：

- `path` required string；
- `offset` optional integer，范围 1～`Integer.MAX_VALUE`；
- `limit` optional integer，范围 1～`Integer.MAX_VALUE`；
- 不接受额外字段；
- 描述明确 relative/absolute 路径和 1-indexed offset。

运行时责任不能只写在 schema 中：

- `ReadTool.prepareArguments()` 在原始 `JsonNode` 上验证 object 形状、字段 allowlist，以及可选字段确为 integer 且在上述范围内；显式 null、浮点数、数字字符串和越界值均失败，不允许 Jackson coercion 兜底。
- 缺省 `offset` 采用 1，缺省 `limit` 采用 2000；缺省与显式 null 不等价。`path` 的 required/string 仍由 schema 校验保证。
- `ReadToolArguments` 构造时也校验非 null path 和非 null 数值范围，避免直接调用工具绕过边界；可选数值的 null 仅表示准备阶段确认的缺省。
- prepare 校验失败沿用三阶段管道生成 error tool result，禁止开始文件 I/O；不因此扩展 core validator，也不依赖调用方 ObjectMapper 的未知字段配置。

工具描述必须说明：

- 支持 text 与指定图片格式；
- 文本默认限制为 2000 行或 50 KiB；
- 多行大文件通过 offset/limit 无损继续读取；单个物理行超出字节上限时明确失败，不支持行内分页；
- 图片作为 attachment 返回。

### 11.2 路径规则

- relative path 以 Session working directory 为基准；
- absolute path 原样 normalize；
- `.` / `..` 使用平台真实路径语义；
- 不用字符串 `startsWith` 伪造 sandbox；
- missing、directory、permission denied、broken symlink 返回可诊断 tool failure；
- 错误可以包含目标路径，但不能泄漏无关环境变量或 secrets。

本阶段不提供 workspace-only policy。未来如需限制，由显式 path policy 或 `BeforeToolCall` 注入。

### 11.3 文本读取

默认上限：

```text
MAX_LINES = 2000
MAX_BYTES = 50 * 1024 UTF-8 bytes
```

规则：

- UTF-8 decoder 使用明确 malformed/unmappable 策略；推荐报告错误而不是静默替换；
- 不用 `Files.readString()` 无界读取任意大文件；
- 流式扫描到 offset，再按完整物理行在 line/byte 上限内收集；扫描跳过的行同样不得使用无界 `readLine()` 缓冲超长行；
- byte limit 包含归一化后的行尾字节，既不切断 UTF-8 code point，也不返回半行；最多有界前瞻到下一行以判断是否还有内容，不为计算总行数扫描整个文件；
- 有效 line 上限为 `min(limit, 2000)`，不能绕过 line 或 byte 上限；
- 保留文件中的文本，不做 Unicode normalization；
- 输出中的 CRLF/CR 行尾统一为 `\n`，末行原本没有行尾时不补换行；不修改磁盘内容；
- 空文件返回明确文本，例如 `"(empty file)"`，不能返回无 content result；
- 截断时附带 next offset 和继续读取提示；
- 到达 EOF 且未截断时不附加误导提示。

### 11.4 图片读取

第一阶段只支持：

- `image/jpeg`
- `image/png`
- `image/webp`

这是保守的 pass-through 集合，不承诺所有模型都具备 vision 能力。当前 OpenAI adapter 不负责格式转换；其官方输入要求不包含 BMP，GIF 仅允许非动画。首批不做动画检测，因此 GIF（含静态 GIF）和 BMP 均返回 unsupported image failure，不能先作为成功图片交给模型再等待 provider 拒绝。后续扩大格式集合时须同时定义检测/转码和 provider 兼容策略。

参考：[OpenAI image input requirements](https://developers.openai.com/api/docs/guides/images-vision#image-input-requirements)。该链接是格式选择依据，不引入 provider wire 逻辑或 `ai-providers` 模块依赖。

规则：

- 先按扩展名和必要的 magic bytes 校验 media type；
- 图片文件大小上限固定为 20 MiB，超过时返回 tool failure，避免 `readAllBytes()` 无界；
- 返回单个 `Content.Image(mediaType, base64)`；
- 不生成 data URL；
- 不在文本、异常或 `toString()` 中输出 base64；
- 不做 resize、转码或动画解析；
- 不支持的二进制文件返回 tool failure。

不允许只按扩展名盲读；无法可靠确认 media type 时返回 unsupported binary failure。

### 11.5 取消

本地普通文件读取无法保证像网络请求一样立即中断阻塞 syscall，但必须：

- 执行前检查 cancellation；
- 扫描大文本的循环中周期检查；
- 图片读取前后检查；
- 取消归一为 aborted/failure 语义，不继续返回成功内容；
- 不使用轮询线程或 `Thread.sleep()`。

### 11.6 `OutputTruncator`

`OutputTruncator` 是无状态 UTF-8 utility，第一阶段只实现 head truncation。返回：

```java
public record TruncatedOutput(
        String content,
        boolean truncated,
        int outputLines,
        long outputBytes,
        OptionalInt nextOffset
) {
}
```

这些字段构成第一阶段的最小成功契约：

- 未截断时 `nextOffset` 为空；恰好达到上限且已到 EOF 也属于未截断，不给出 EOF 之后的 continuation。
- 截断结果只包含完整物理行，`nextOffset = requestedOffset + outputLines`，指向第一个尚未返回的物理行，而不是跳过未返回的行尾。
- 下一行放不进剩余 byte 预算时，返回已完整收集的行，并让 continuation 从该行重新读取；累计行号和 offset 运算必须检查溢出，不能回绕为合法的小 offset。
- 若请求起始行自身超过 byte 上限，返回明确的超长行 tool failure，不产生“前缀成功”或虚假的 next offset。诊断仅说明行号、上限和不支持行内分页，不包含整行数据。
- `outputBytes` 只计算 content 的 UTF-8 字节（含归一化行尾），不包含固定的展示提示。行数和字节同时成为限制时，提示优先说明 line limit；否则说明 byte limit。
- 首行过长的检测也必须有界，不先把整行读入内存。失败由内部截断/读取结果或专用异常明确传给 `ReadTool`，不能靠匹配异常文本区分。

阶段二 bash 的 tail truncation 另行增加，不在第一阶段先做未使用 API。

### 11.7 Tool result

成功文本：

```text
<file content>

[Showing lines X-Y of ...; continue with offset=Z]
```

提示文本不必照搬参考实现，但必须：

- 人和模型都能理解；
- 精确说明截断原因；
- 成功截断时给出指向完整未返回行的 offset；超长起始行失败时说明不可行内续读，不伪造 continuation；
- 不谎报总行数（若未扫描到 EOF 就不要猜总数）。

失败使用 `ToolExecutionResult.failure(message)`；I/O 异常不能异常击穿 Agent Loop。

## 12. Agent 装配

`CodingAgentSession` 创建底层 `AgentConfig` 时使用：

- `initialContext.systemPrompt`：builder 结果；
- `initialContext.messages`：空；
- `initialContext.tools`：只包含 `ReadTool`；
- `model` / `modelClient`：来自产品 config；
- `objectMapper`：来自 config；
- `ContextTransformer.identity()`；
- `MessageProjector.standard()`；
- `ToolExecutionMode.PARALLEL`；
- `BeforeToolCall.noop()` / `AfterToolCall.noop()`；
- `eventSink`：产品 adapter；
- queue modes / thinking / request options：来自 config；
- `PrepareNextTurn.noop()` / `ShouldStopAfterTurn.never()`。

不要为了减少 constructor 参数而新增静态 global defaults。

## 13. 失败与取消语义

### 13.1 配置失败

工作目录或 required dependency 无效时，在构造 config/session 时 fail fast，使用 `IllegalArgumentException`/`NullPointerException` 的既有 Java 风格；此时尚未开始 Agent stream，不生成模型 terminal event。

### 13.2 模型失败

沿用 `ModelClient` 和 Agent 的 terminal assistant 语义，`CodingAgentRunResult` 正常完成并携带 failure assistant。不能把 provider `ERROR` 再转成 failed future。

### 13.3 工具失败

missing file、invalid UTF-8、unsupported binary、permission denied 等成为 error tool result，模型可以在下一轮响应或修正。

### 13.4 基础设施失败

产品 event sink failure、executor rejection、线程中断等保持异常完成；不能伪装成“文件读取失败”或模型 error。

### 13.5 用户取消

`abort()` 沿用 Agent 的 `ABORTED` terminal result。调用方得到可识别的 `CodingAgentRunResult`；close 期间的取消同样不能产生第二个 terminal。

## 14. 实施步骤

本阶段作为一个纵向 PR 实施，避免先合入没有真实产品能力的空模块。PR 内按以下 TDD 顺序提交或组织改动。

### 步骤 1：先重构模块边界 guard

1. 为现有模块写/调整 enforcer 配置；
2. 用故意依赖或 effective-pom 检查证明规则按模块生效；
3. 保持现有三个模块测试绿色；
4. 尚不加入空 `coding-agent` module。

### 步骤 2：增加模块和最小测试骨架

1. 根 reactor 和 dependency management 加入 `coding-agent`；
2. 新增 module POM、`AGENTS.md` 和 smoke test；
3. 立即进入同一 PR 的功能实现，不把该状态单独作为完成交付。

### 步骤 3：先实现 truncation 和 read tests

1. UTF-8 byte/完整行 truncation、超长首行和无损 continuation 测试；
2. offset/limit 范围、额外字段、宽松 ObjectMapper 和 EOF 测试；
3. path/error/image allowlist/cancellation 测试；
4. 完成最小 `ReadTool`。

### 步骤 4：实现 Prompt builder

1. 默认、custom、append、tool list、cwd 测试；
2. 完成纯 builder；
3. 不引入资源发现。

### 步骤 5：实现产品事件和 Session

1. event snapshot/完成事件投影/backpressure tests；
2. config validation/default tests；
3. prompt/steer/followUp/abort/close 和队列收尾竞态 tests；
4. state/result conversion 及嵌套参数树隔离 tests；
5. 完成 Agent 装配。

### 步骤 6：完成纵向集成测试

脚本模型行为：

1. 第一次请求断言 system prompt、user message 和 `read` spec；
2. 返回 `Content.ToolCall("...", "read", {"path":"fixture.txt"})`；
3. 第二次请求断言 tool result 包含 fixture 内容；
4. 返回最终 assistant；
5. 测试断言 final result、context、new messages 和 event sequence。

### 步骤 7：文档和基线更新

- 更新根 `AGENTS.md`；
- 完成 `coding-agent/AGENTS.md`；
- 更新父计划中的阶段状态；
- 如公共架构发生变化，再更新 `docs/architecture`；
- 第一阶段全部完成后，把本文移到 `docs/plans/archived/`，修正父计划链接。

## 15. 代码改动清单

### 15.1 新增

- `coding-agent/pom.xml`
- `coding-agent/AGENTS.md`
- `coding-agent/src/main/java/site/pplee/jcode/codingagent/CodingAgentSession.java`
- `coding-agent/src/main/java/site/pplee/jcode/codingagent/CodingAgentConfig.java`
- `coding-agent/src/main/java/site/pplee/jcode/codingagent/CodingAgentState.java`
- `coding-agent/src/main/java/site/pplee/jcode/codingagent/CodingAgentRunResult.java`
- `coding-agent/src/main/java/site/pplee/jcode/codingagent/event/CodingAgentEvent.java`
- `coding-agent/src/main/java/site/pplee/jcode/codingagent/event/CodingAgentEventSink.java`
- `coding-agent/src/main/java/site/pplee/jcode/codingagent/prompt/SystemPromptBuilder.java`
- `coding-agent/src/main/java/site/pplee/jcode/codingagent/tool/ReadTool.java`
- `coding-agent/src/main/java/site/pplee/jcode/codingagent/tool/ReadToolArguments.java`
- `coding-agent/src/main/java/site/pplee/jcode/codingagent/tool/OutputTruncator.java`
- `coding-agent/src/main/java/site/pplee/jcode/codingagent/tool/TruncatedOutput.java`
- 对应 tests 和 `support/` 测试双

### 15.2 修改

- `pom.xml`
- `ai/pom.xml`（仅模块边界 guard，如选择 child-local 配置）
- `ai-providers/pom.xml`（同上）
- `agent-core/pom.xml`（同上）
- `AGENTS.md`
- 父计划状态字段（阶段完成时）

### 15.3 原则上保持不变

- `ai/src/main/java/**`
- `ai-providers/src/main/java/**`
- `agent-core/src/main/java/**`

若必须修改这些路径，实施前先更新本计划的“当前 seam 评估”和改动清单。

## 16. 测试矩阵

### 16.1 Module boundary

- `ai` 无内部依赖；
- `ai-providers -> ai` 通过；
- `agent-core -> ai` 通过；
- `coding-agent -> ai + agent-core` 通过；
- fixture 或 effective config 证明被禁止方向会失败；
- reactor 顺序正确。

### 16.2 Config

- required null；
- relative cwd 转 absolute normalized path；
- missing/not-directory cwd；
- defaults；
- custom/append 文本保持；
- config 结构不可变，注入依赖的线程安全/所有权约定明确；
- state/result/event 构造及 accessor 对可变参数树均做防御性复制；修改一次返回值后再次读取，原快照值保持不变。

### 16.3 Prompt

- 默认 identity；
- read tool 恰好出现一次；
- cwd 使用 normalized path；
- custom replacement；
- append ordering；
- 特殊字符和多行输入；
- 无隐藏文件或环境读取。

### 16.4 Truncation

- 少于上限；
- 恰好达到行上限；
- 超过行上限；
- 恰好达到 byte 上限；
- 多字节 code point 位于边界，整行保留或整体留至下页；
- CRLF/CR 归一化与末行无换行，byte 计数包含归一化行尾；
- 空内容；
- offset 之后截断；
- 下一完整行放不进剩余预算时 continuation 指向该行；
- 恰好达到上限且 EOF 时不附带 nextOffset；
- 超过 50 KiB 的单行/起始行失败，不返回半行或跳至下一行；
- 超长行扫描与前瞻内存有界，不使用无界 readLine；
- 多页拼接等于归一化后的原文本（每个物理行均可容纳时）；
- offset 加法溢出不回绕。

### 16.5 Read text

- relative/absolute path；
- nested path 和 `..`；
- empty file；
- offset=1 / offset beyond EOF；
- custom limit，以及超过 2000 时仍按 2000 截断；
- offset/limit 为零、负数、显式 null、浮点数、数字字符串和整数溢出均失败；
- 非 object 参数及额外字段失败；宽松 ObjectMapper（忽略未知字段、允许 coercion）不能改变校验结论；
- 缺省 offset/limit 使用默认值，合法边界值通过；准备失败不触发文件 I/O；
- missing/directory/permission denied；
- malformed UTF-8；
- unsupported binary；
- cancellation before/during scan；
- error result 不抛入 loop。

permission test 如受平台限制，应使用可控 test double，不写依赖特定 Unix mode 的脆弱测试。

### 16.6 Read image

- JPEG/PNG/WEBP 每个支持 media type；
- BMP、静态 GIF、动画 GIF 均在工具层返回 failure，不产生成功的 `Content.Image`；
- base64 round trip；
- extension/magic mismatch；
- 超过大小上限；
- data 不出现在 `toString()` 和错误；
- 取消。

### 16.7 Session

- prompt success；
- model error result；
- prompt 并发 fail fast；
- steer/followUp 在 Session idle/closed 时拒绝，在 running/收尾期按 §8.6 接纳；
- 用 latch 控制最后 drain 与完成事件投递，验证此时新入队消息不进入已结束的 turn，而在后续 run 对应 drain 点消费；
- 模型失败和 abort 时尚未 drain 的队列保留，下一 prompt 不隐式清空；已 drain 的消息不自动补发；
- 收尾、prompt、enqueue、abort/close 竞态无死锁、不重复接纳，不靠 sleep 或轮询构造测试；
- abort；
- close 幂等；
- close active run；
- close 后调用；
- 模型/工具 state 与底层事件归约同步；Session running/isRunning 在 stage 收尾前后保持一致；
- `AgentCompleted` 只映射一次 `RunCompleted`，`RuntimeEvent` 拒绝此类型，public 完成 payload 不可获取 `AgentTool`；
- 修改 `ToolStarted`、`MessageUpdated.delta`、state 和 result 中的参数树，不影响实际 read 路径、下一轮请求、其他快照或同一快照的后续读取；
- 完成事件与 stage 结果值一致但无对外可变节点别名；
- event sink backpressure；
- event sink failure；
- model client/provider 所有权不被错误关闭。

### 16.8 End-to-end

- text → read tool → tool result → final text；
- 多行大文件 continuation tool call，无遗漏或重复行；
- 超长首行失败后模型可正常结束，不因虚假 offset 无限重读；
- 支持图片作为 tool result 进入下一轮请求；GIF/BMP 在进入请求前已成为 error tool result；
- read failure 后模型可恢复；
- event sequence 与 context message order；
- system prompt 和 tool schema 实际进入 `ModelRequest`。

## 17. 验证命令

实现过程中按最小范围执行：

```bash
mvn -pl coding-agent -am test
```

最终必须执行：

```bash
mvn verify
```

检查依赖图：

```bash
mvn -pl coding-agent dependency:tree
```

本阶段没有 Java `main` 或 Spring Boot 入口，因此不使用 Gravity 启动；不得为了满足 smoke test 临时增加伪入口。

### 17.1 可选 live probe

只有在本地凭证存在且用户明确同意产生 API 调用时，才允许手工执行：

1. 调用方显式创建 `OpenAiProvider`；
2. 显式创建 `DefaultModels`；
3. 把 `Models` 和 `ModelRef` 传入 `CodingAgentConfig`；
4. 在临时工作区询问模型读取一个小文本文件；
5. 验证发生一次 read tool call 和最终回答。

live probe：

- 默认不进入 Surefire；
- 不读取或打印 API Key；
- 不成为合并门槛；
- 不能替代 fake-model 测试。

## 18. 评审检查表

### 18.1 API

- public 类型是否必要，结构不可变与注入运行时依赖的边界是否明确；
- 嵌套 JsonNode 是否在构造及 accessor 双向隔离，是否遗漏 delta/result 分支；
- 是否泄露底层 mutable Agent、LoopResult 或可执行 AgentTool；
- 是否出现 `Map<String,Object>`；
- 是否存在隐藏环境读取或全局状态；
- 命名是否符合 `*Config/*State/*Result/*Sink` 约定；
- Javadoc 是否为英文且描述失败/取消/所有权。

### 18.2 并发

- 是否新增不必要 executor；
- sink stage 是否被等待；
- listener 是否线程安全；
- abort/close 是否幂等；
- 是否把接纳误写成当前 run 消费保证，收尾/失败残留队列是否符合 §8.6；
- 是否持有 Session 锁等待 sink、run 或 worker；
- 是否存在阻塞轮询或 `Thread.sleep()`；
- terminal 是否恰好一次。

### 18.3 文件 I/O

- 是否无界读取；
- UTF-8 边界是否安全；
- offset/limit 范围与额外字段是否在工具层显式校验；
- continuation 是否完整覆盖未返回的行，超长首行是否明确失败；
- path 规则是否与文档一致；
- 是否误称 sandbox；
- image payload 是否 redacted，格式 allowlist 是否与 §11.4 一致。

### 18.4 架构

- `agent-core` 是否仍只依赖 `ai`；
- coding prompt/tool/session 是否全部留在产品模块；
- 是否重复实现 model stream 或 tool pipeline；
- module boundary 是否由构建验证；
- 是否为未来能力创建了未使用抽象。

## 19. 风险与处置

### 19.1 Session 门面只是 Agent 的薄重命名

风险：新增模块没有产品事实，只把每个方法一一转发。

处置：首批同时交付 working directory、System Prompt、内置 read、产品 event/state/result 和纵向工具闭环。这些共同构成真实产品行为。

### 19.2 过早稳定过大的 public API

风险：后续 Session persistence 和 config 被首批构造器绑死。

处置：首批 public 面保持小；内部组装不暴露；只承诺 prompt/run/state/event 的核心语义。尚无消费者的 I/O 和 registry 接口保持 package-private。

### 19.3 ReadTool 变成万能内容加载器

风险：首批加入 PDF、URL、视频、压缩包、编码猜测和图片处理。

处置：只支持 UTF-8 text 和有界标准图片。其他格式返回明确 failure，后续有真实需求再扩展。

### 19.4 Event sink 拖慢模型流

风险：调用方在 sink 中同步渲染或落慢存储，阻塞 run。

处置：文档明确 backpressure；后续 TUI 使用自身 event queue。第一阶段不在产品层擅自异步丢事件。

### 19.5 图片支持增加不成比例的复杂度

风险：可靠识别、尺寸保护和 provider capability 处理扩大首批范围。

处置：首批固定为有界 JPEG/PNG/WEBP pass-through；GIF/BMP 明确失败。格式支持与模型 vision capability 分开处理，不让通用 `Content.Image` 能表示某 MIME 被误认为当前 provider 一定支持该 MIME。

### 19.6 Maven guard 为新模块被整体关闭

风险：为了让 `coding-agent -> agent-core` 构建通过而移除 enforcer。

处置：先改为 per-module allow graph，并用构建验证反向依赖仍失败。

### 19.7 队列接纳被误认为当前 run 投递确认

风险：调用方在收尾或 abort 后复用 Session，旧消息在下一 run 才被消费；产品层即使加锁也不能改变底层 drain 时机。

处置：§8.6 固定跨 run 保留和非可靠投递语义，用 latch 覆盖竞态；不承诺当前 run 必达，也不静默清理尚未 drain 的消息。后续严格 run-bound 接纳必须先完成 core seam 设计。

### 19.8 Record 外壳掩盖可变参数别名

风险：只复制列表或首次 deep copy 后直接暴露 accessor，调用方仍能修改内部快照，甚至通过完成事件取得工具实例。

处置：统一防御性快照 mapper，构造和访问双向隔离；完成事件投影为产品结果；测试实际执行路径和下一轮请求，而不只断言集合不可修改。

## 20. Definition of Done

第一阶段只有同时满足以下条件才算完成：

1. `coding-agent` 是 reactor 中真实可用的 Maven library module。
2. `CodingAgentSession` 可以完成 text → read → tool result → final assistant 的完整运行。
3. public API 不暴露底层 Agent/LoopResult/工具实例；配置结构不可变，state/result/event 的嵌套参数树满足 §8.7 的构造及访问隔离。
4. System Prompt 与实际启用工具、工作目录一致。
5. ReadTool 显式校验参数，完整行分页不遗漏内容，超长首行和 GIF/BMP 明确失败；I/O、图片、错误和取消均有有界行为。
6. 产品事件保留底层顺序和 backpressure，状态在投递前已归约；Session 收尾与队列归属满足 §8.6，并有无 sleep 的竞态测试。
7. 默认测试不访问公网、不读取真实凭证、不修改仓库工作文件。
8. `mvn -pl coding-agent -am test` 和 `mvn verify` 通过。
9. `mvn -pl coding-agent dependency:tree` 未出现未声明或越层内部依赖。
10. 根 `AGENTS.md` 与 `coding-agent/AGENTS.md` 已反映最终实现。
11. 本计划移入 `docs/plans/archived/`，父计划记录阶段一完成状态和最终偏差。

## 21. 实施完成后必须记录的证据

阶段完成总结至少包含：

- changed files；
- 新增/修改 tests；
- 实际执行命令与退出码；
- 模块依赖树摘要；
- 未执行或失败的验证；
- live probe 是否执行；
- residual risks；
- staged files 状态；
- 与本计划不同的 API/行为决策及原因。

## 22. 完成记录（2026-09-18）

### 22.1 实际交付

- 根 reactor 纳入 `coding-agent`，四个子模块分别拥有适合自身层级的 Maven Enforcer 依赖边界。
- 新增 `CodingAgentSession`、`CodingAgentConfig`、`CodingAgentState`、`CodingAgentRunResult` 和产品事件协议；底层 `Agent`、`LoopResult`、context 与工具实例不从产品 API 泄漏。
- 新增统一结构快照 mapper，对 message、content、stream delta 和 tool-call `JsonNode` 做递归复制；公开 accessor 每次重新生成快照。
- 新增纯函数 `SystemPromptBuilder`，prompt 中的工具和工作目录来自实际 Session 装配。
- 新增严格参数校验的 `ReadTool`：UTF-8 文本按完整物理行有界读取，支持 CR/LF/CRLF、continuation offset、取消和超长行失败；图片仅支持经 magic signature 验证的 JPEG/PNG/WEBP，大小上限 20 MiB。
- 新增 fake-model 纵向闭环，验证 text → read call → tool result → final assistant，并覆盖工具失败恢复和图片进入下一模型请求。

### 22.2 测试与验证证据

以下命令均在仓库根目录执行：

- `mvn -pl coding-agent -am test -q`：退出码 0。
- `mvn clean verify`：退出码 0；reactor 四个子模块均成功。
- 修改最终边界规则后再次执行 `mvn verify -q`：退出码 0。
- Surefire 报告合计 568 个测试，0 failure、0 error、0 skipped；其中 `coding-agent` 38 个测试。
- `mvn -pl coding-agent dependency:tree`：退出码 0；compile graph 仅包含 `ai`、`agent-core`、Jackson，以及 `ai` 传递的 Lombok；未出现 `ai-providers` 或更高产品模块。
- 临时向 `coding-agent` 注入被禁止的 `ai-providers` 依赖并执行 `mvn -pl coding-agent -am validate`：按预期以退出码 1 失败，命中 `coding-agent must depend only on ai and agent-core among Jcode modules.`；fixture 随后恢复。
- `git diff --check`：退出码 0。

所有自动测试均使用 scripted/fake model 与临时目录，不访问公网或读取真实凭证。未执行 OpenAI live probe：本阶段刻意不依赖 `ai-providers`，且 live 调用不是合并门槛；需要手工验证时，应由外部 embedding 应用显式组装 provider、`ModelClient` 与 `ModelRef`，在用户确认凭证和费用后调用同一 `CodingAgentSession` API。

### 22.3 与计划的最终差异

- 未新增仓库内 live-smoke source set。原因是没有现成产品入口，并且让 `coding-agent` 测试依赖具体 provider 会破坏第一阶段依赖边界；以外部显式组装说明替代。
- `CodingAgentSession` 用同步 admission lock 线性化 prompt、queue、abort 与 close 的产品接纳状态，但不宣称该锁与 `agent-core` 内部 queue drain 构成原子边界。收尾期间接纳但未 drain 的消息确定性保留到下一 run。
- 产品完成事件在底层 `AgentCompleted` 的原事件位置投影为唯一 `RunCompleted`，而不是同时暴露两种 terminal event。

### 22.4 残余风险与工作区状态

- `ReadTool` 明确不是 workspace sandbox：相对路径基于工作目录解析，绝对路径按原值规范化；权限与可信目录策略留给后续产品层。
- 文件在属性检查和读取之间可能被外部进程替换；实现保证读取量有界和失败可见，但不承诺文件系统事务快照。
- steering/follow-up 的成功接纳不等于当前 run 已消费，严格 run-bound 投递需要后续 core seam。
- 本阶段不含 Session 持久化、配置/凭证发现、compaction、resource/extension 或 UI，这些继续按父路线图推进。
- 完成验证时无 staged 文件。工作区中的 `.idea/vcs.xml` 修改不属于本阶段交付，后续提交时应排除。
