# `ai-providers` 共享模块与 Provider Runtime 抽象实施方案

> 状态：已完成并归档
> Jcode 基线：`8d15c8e`（2026-08-04）
> 参考来源：`docs/references/pi/packages/ai/src/models.ts`、`docs/references/pi/packages/ai/src/providers/openai.ts`、`docs/references/pi/packages/ai/src/api/openai-responses.ts`、`docs/references/pi/packages/ai/src/api/openai-responses-shared.ts`、`docs/references/pi-book/src/ch04-provider-registry.md`
> 涉及模块：扩展 `ai` 的 provider runtime 抽象；新增共享模块 `ai-providers`，仅依赖 `ai`

> **模块策略修订**：本方案最初以每个 provider 一个 Maven 模块（`ai-provider-openai`）实施。后续评审决定改为共享 providers 模块策略（方案 B）：所有具体 provider 放在同一个 `ai-providers` 模块内，每个 provider 一个子包（`site.pplee.jcode.aiproviders.<provider>`），不再为每个 provider 新建 Maven 模块。本文档以下内容已按最终实施形态更新为 `ai-providers`。

## 1. 决策摘要

本方案按 pi-book 第 4 章“Provider 不是 Adapter”的思想实施：先在 `ai` 建立 provider-neutral 的 runtime 抽象，再用 `ai-providers` 提供第一个具体 provider（OpenAI）。

目标依赖图：

```text
ai  <-  ai-providers
ai  <-  agent-core
```

新增抽象后，`agent-core` 仍然只依赖 `ai`，并继续只通过 `ModelClient` 发起模型调用；组合层可以把一个 `Models` 集合作为 `ModelClient` 传给 `AgentConfig`。具体 OpenAI HTTP/SSE 解析只存在于 `ai-providers` 的 `openai` 子包内部。

核心决策：

1. `ModelProvider` 是运行时单元，拥有 provider 身份、模型列表、认证状态、模型归属校验和 stream 行为。
2. Adapter 是 provider 内部实现细节，负责某一种 API dialect 的 request/event 映射。
3. `Models` 是显式持有 provider 的运行时集合，负责 provider lookup、model lookup、auth/status convenience 与 stream dispatch。
4. 不使用全局 registry、`ServiceLoader`、classpath 自动注册或隐藏环境变量读取。
5. 不把 provider runtime、provider SDK、API key 读取、模型 catalog 刷新、OAuth 或产品配置放进 `agent-core`。

选择现在就抽象的原因：Jcode 已经有 `ModelRef(provider/api/modelId)` 三维身份和 `ModelClient` seam；如果第一个 provider 直接暴露为裸 `OpenAiModelClient`，后续再引入 Anthropic/Google 时会被迫迁移调用方装配方式。现在先建立 `Provider`/`Models` 形状，可以让 OpenAI 成为体系里的第一个 concrete provider，让 Responses API adapter 成为 provider 内部实现，而不是未来需要偿还的特殊入口。

## 2. 分层设计

### 2.1 `ai` 新增 provider runtime 抽象

`ai` 仍是 provider-neutral 层：新增的是抽象接口、状态值与默认集合实现，不包含任何具体厂商 SDK、HTTP 端点、API key 环境变量读取或产品配置。

建议包结构：

```text
ai/src/main/java/site/pplee/jcode/ai/provider/
├── ModelProvider.java          # provider runtime unit
├── Models.java                 # provider collection + ModelClient routing view
├── MutableModels.java          # explicit mutable collection seam
├── DefaultModels.java          # immutable provider collection implementation
├── CopyOnWriteModels.java      # mutable copy-on-write implementation
├── ProviderAuth.java           # provider-owned auth status abstraction
├── AuthCheck.java              # auth status value, no raw credential/header
├── ModelsRefreshContext.java   # refresh hook context
└── ModelsRefreshResult.java    # refresh summary/failure value
```

公开接口草案：

```java
public interface ModelProvider {
    String id();
    String name();
    Optional<URI> baseUrl();
    ProviderAuth auth();
    List<Model> models();
    boolean supports(ModelRef ref);
    CompletionStage<Void> refreshModels(ModelsRefreshContext context);
    AssistantMessageStream stream(ModelRequest request, CancellationSignal cancellation);
}

public interface Models extends ModelClient {
    List<ModelProvider> providers();
    Optional<ModelProvider> provider(String id);
    List<Model> models();
    List<Model> models(String providerId);
    Optional<Model> model(ModelRef ref);
    CompletionStage<AuthCheck> checkAuth(String providerId, CancellationSignal cancellation);
    CompletionStage<ModelsRefreshResult> refresh(ModelsRefreshContext context);

    @Override
    AssistantMessageStream stream(ModelRequest request, CancellationSignal cancellation);
}

public interface MutableModels extends Models {
    void setProvider(ModelProvider provider);
    void deleteProvider(String id);
    void clearProviders();
}
```

设计约束：

- `Models` 可以实现 `ModelClient`，因为它是 provider 集合的 routing view；单个 `ModelProvider` 不实现 `ModelClient`，避免把 provider 降格为 adapter。
- `ModelProvider.stream()` 是 provider runtime 行为，不代表 provider 等于 adapter；具体 API dialect adapter 是 provider 内部实现。
- `ModelProvider.baseUrl()` 返回 `Optional<URI>`，因为本抽象也要容纳本地、嵌入式或非 HTTP provider。
- `ProviderAuth` 只表达认证状态诊断，不暴露 resolved headers、OAuth token、API key 或 credential store。具体 header 构造留在 provider 实现内部。
- `refreshModels()` 是 provider runtime seam。首版 OpenAI provider 返回静态模型列表，不联网刷新；失败以 `ModelsRefreshResult` 记录，不让 refresh future 因单个 provider 失败而整体异常。
- `DefaultModels` 是不可变集合；`CopyOnWriteModels` 才提供 `MutableModels`。`CopyOnWriteModels.stream()` 在调用开始时快照 provider map，active stream 不受后续 mutation 影响。
- 所有集合返回不可变快照。

### 2.2 Provider/catalog 不变量

`DefaultModels` / `CopyOnWriteModels` 必须校验以下不变量：

1. provider id 非空，并且在同一个 `Models` 实例内唯一。
2. provider name 非空。
3. `ModelProvider.models()` 中每个 `Model.provider()` 必须等于 `provider.id()`。
4. 同一 provider catalog 中不得出现重复 `(provider, api, modelId)`。
5. `Models.model(ModelRef)` 只查询 advertised catalog；catalog 为空表示“无可展示目录”，不是隐式 wildcard。
6. `Models.stream(ModelRequest, CancellationSignal)` 使用 `provider.supports(request.model())` 判断 provider 是否接受该请求；具体 provider 可以显式允许未列入 catalog 的模型，但必须在自己的配置中声明。
7. unknown provider、unsupported model、auth 未配置等错误都生成 synthetic failure stream，且事件序列必须是 `Start -> Error`。

### 2.3 `ai-providers` 共享模块与 OpenAI 具体 provider

`ai-providers` 是共享的具体 provider 模块，每个 provider 一个子包。当前只实现 OpenAI provider 与 Responses API adapter：

```text
ai-providers/
├── pom.xml
├── AGENTS.md
└── src/
    ├── main/java/site/pplee/jcode/aiproviders/openai/
    │   ├── OpenAiProvider.java             # public final，implements ModelProvider
    │   ├── OpenAiProviderConfig.java       # public final class，redacted config
    │   ├── OpenAiCredentials.java          # public final，redacted secret holder
    │   ├── OpenAiModelCapabilities.java    # public record，provider-local model capability map
    │   ├── OpenAiApiKeyAuth.java           # package-private，explicit API key auth status
    │   ├── OpenAiResponsesAdapter.java     # package-private，Responses API stream adapter
    │   ├── OpenAiRequestMapper.java        # package-private，请求 JSON 映射
    │   ├── OpenAiSseParser.java            # package-private，SSE line/data parser
    │   ├── OpenAiEventMapper.java          # package-private，provider event -> AssistantMessageEvent
    │   └── OpenAiToolCallIds.java          # package-private，call_id/item_id 编码规则
    └── test/java/site/pplee/jcode/aiproviders/openai/
        ├── OpenAiProviderTest.java
        ├── OpenAiResponsesAdapterTest.java
        ├── OpenAiRequestMapperTest.java
        ├── OpenAiSseParserTest.java
        ├── OpenAiEventMapperTest.java
        └── support/
            ├── FakeOpenAiServer.java
            └── MutableCancellationSignal.java
```

后续新增 provider（anthropic/google 等）时在 `ai-providers` 内新建对应子包，不再新建 Maven 模块。

`OpenAiProvider` 负责 provider-level 行为：

- `id()` 默认 `openai`
- `name()` 默认 `OpenAI`
- `baseUrl()` 默认 `https://api.openai.com/v1`
- `auth()` 返回显式 API key auth status
- `models()` 返回静态模型列表
- `supports(ModelRef)` 校验 provider/api/modelId，并按配置决定是否允许未列入 catalog 的模型
- `stream(ModelRequest, CancellationSignal)` 先执行 provider 归属校验和 auth 检查，再委托 package-private `OpenAiResponsesAdapter`

`OpenAiResponsesAdapter` 只负责 Responses API：HTTP request、SSE 读取、provider event mapping、错误归一。它不是公开装配入口。

## 3. 公开接口草案

### 3.1 OpenAI credential 与 provider 配置

原始 API key 不得作为 public record component 出现，避免 Java record 自动生成的 `toString()` 泄露密钥。配置使用普通 final class 或 redacted secret holder。

```java
public final class OpenAiCredentials {
    public static OpenAiCredentials apiKey(String apiKey);
    @Override public String toString(); // redacted
}

public final class OpenAiProviderConfig {
    public static OpenAiProviderConfig responses(
            OpenAiCredentials credentials,
            List<Model> models
    );

    public URI baseUrl();
    public OpenAiCredentials credentials();
    public Optional<String> organization();
    public Optional<String> project();
    public Duration requestTimeout();
    public String providerId();
    public String providerName();
    public String api();
    public List<Model> models();
    public Map<String, OpenAiModelCapabilities> capabilities();
    public boolean allowUnlistedModels();

    @Override public String toString(); // redacted
}

public record OpenAiModelCapabilities(
        boolean reasoning,
        Map<ThinkingLevel, String> reasoningEfforts
) { }
```

配置约束：

- `OpenAiCredentials` 由调用方显式创建；模块不读取环境变量。
- `OpenAiCredentials.toString()` 与 `OpenAiProviderConfig.toString()` 必须 redacted。
- `baseUrl` 可有默认值，但不得隐藏读取环境变量。
- `providerId` 默认 `openai`，`providerName` 默认 `OpenAI`，`api` 默认 `openai-responses`。
- `models` 是显式静态模型列表；首版不主动联网刷新。
- `allowUnlistedModels=false` 时，`supports` 必须要求 `modelId` 出现在 `models` 中；为 true 时，`supports` 可接受 provider/api 匹配但未列入 catalog 的模型。
- `OpenAiModelCapabilities` 是 provider-local metadata，不进入 `ai.Model`，避免把 OpenAI reasoning/capability 细节塞进 provider-neutral模型值。

### 3.2 组合示例

```java
var openAi = new OpenAiProvider(
        OpenAiProviderConfig.responses(OpenAiCredentials.apiKey(apiKey), models)
);
var modelsRuntime = new DefaultModels(List.of(openAi));

var agentConfig = new AgentConfig(
        initialContext,
        initialModelRef,
        modelsRuntime,
        ...
);
```

调用方也可以只注册一个 provider，但仍通过 `DefaultModels` 路由到 `AgentConfig`，保持与未来多 provider 组合方式一致。

### 3.3 Synthetic failure stream helper

为避免 `DefaultModels` 与 provider adapter 重复手写 `Start -> Error`，在 `ai` 增加一个小型 helper：

```java
public final class AssistantMessageStreams {
    public static AssistantMessageStream failed(StopReason reason, String errorMessage);
    public static AssistantMessageStream failed(StopReason reason, String errorMessage, List<Content> partialContent);
}
```

约束：

- `reason` 只能是 `ERROR` 或 `ABORTED`。
- helper 必须先 push empty/partial `Start`，再 push terminal `Error`。
- `resultStage()` 正常完成为 final assistant，不 exceptional complete。

## 4. 实施范围

### 4.1 目标

1. 在 `ai` 新增 provider runtime 抽象：`ModelProvider`、`Models`、`MutableModels`、`DefaultModels`、`CopyOnWriteModels`、auth/refresh/status value types。
2. 在 `ai` 新增 `AssistantMessageStreams.failed(...)` helper，统一 synthetic failure stream 生命周期。
3. 新增共享 Maven 模块 `ai-providers`。
4. `OpenAiProvider implements ModelProvider`，公开 provider runtime，不公开 low-level adapter。
5. `DefaultModels` 可作为 `ModelClient` 传入 `AgentConfig`，并根据 `ModelRef.provider()` dispatch 到对应 provider。
6. 支持 `ModelRequest.model().api()` 为 `openai-responses` 的流式调用。
7. 支持标准消息映射：system prompt、user、assistant text/thinking/tool call、tool result。
8. 支持 tool declaration 映射：`ToolSpec` → Responses API function tool。
9. 支持流式事件映射：text、thinking、function-call arguments、terminal done/error。
10. 支持 usage 与 stop reason 映射。
11. 用纯 JUnit + 本地 fake HTTP/SSE server 覆盖 provider runtime、Models routing 与 OpenAI adapter 契约，不需要真实 API key。

### 4.2 非目标

- 不实现 Chat Completions API。
- 不实现 OAuth、credential store、动态模型 catalog、自动联网刷新。
- 不实现自动 retry、prompt cache、service tier、cost 计算。
- 不实现 image/file/web-search/code-interpreter/MCP/custom tool。
- 不实现 `previous_response_id` 依赖式会话；首版使用 `store: false` 与完整 transcript replay。
- 不升级 `agent-core` 或改变 `AgentLoop`。
- 不新增 CLI、TUI、session persistence、coding tools、skills/extensions。

## 5. 关键协议映射

### 5.1 request → Responses payload

首版 payload 使用完整 transcript replay：

```json
{
  "model": "<ModelRef.modelId>",
  "input": [ ... ],
  "tools": [ ... ],
  "stream": true,
  "store": false
}
```

映射规则：

- `ModelRequest.systemPrompt()` 映射为首个 system/developer 输入项；首版固定 system，后续如需 reasoning/developer role 再加 compat 配置。
- `Message.User` 的 `Content.Text` 合并为 user input text。
- `Message.Assistant` 的 `Content.Text` 映射为 assistant output text replay。
- `Content.Thinking` 首版可忽略或作为 provider 不可回放内容保留在本地，不强行发送给 OpenAI；后续如需 reasoning signature 再扩展。
- `Content.ToolCall` 映射为 prior `function_call` item。
- `Message.ToolResultMessage` 映射为 `function_call_output` item，`call_id` 使用 decoded tool-call id 中的 call id。
- `ToolSpec` 映射为 function tool：`name`、`description`、`parameters`。
- `ToolSpec.parameters()` 为 `NullNode` 时规范化为空 object schema；非 object schema 生成 provider-level terminal `Error`。
- `ThinkingLevel.PROVIDER_DEFAULT` 不发送 reasoning 参数。
- `ThinkingLevel.OFF` 默认不发送 reasoning 参数；如果 model capability 标记该模型必须显式 reasoning/off 而又没有 off 映射，则 terminal `Error`。
- `MINIMAL/LOW/MEDIUM/HIGH/XHIGH/MAX` 必须通过 `OpenAiModelCapabilities.reasoningEfforts` 显式映射；缺少能力映射时 terminal `Error`，不静默降级。

### 5.2 tool call id 编码

Responses API 同时存在 function `call_id` 与 output item `id`。Jcode 标准 `Content.ToolCall.id` 只有一个字符串字段，因此 provider 内部 Responses adapter 使用版本化、可转义的 codec，而不是裸字符串拼接。

建议编码：

```text
oai1:<base64url({"callId":"...","itemId":"..."})>
```

规则：

- provider 返回 function call 时，把 `call_id` 与 item `id` 编码成 `oai1:...` 写入 `Content.ToolCall.id`。
- 写回 `function_call_output` 时 decode `Content.ToolCall.id` / `ToolResultMessage.toolCallId`，取 `callId`。
- 回放 prior assistant function call 时，如果 decoded `itemId` 合法且符合 OpenAI item id 约束，则带上 item id；否则省略 item id。
- 对外部/旧格式 raw id，只按 raw `call_id` 处理；如果无法安全映射为 OpenAI `call_id`，返回 terminal `Error`。
- codec 必须测试 delimiter、base64url malformed、overlong id、missing item id、item id 非 `fc_`、raw id fallback。

### 5.3 Responses stream → Jcode events

事件映射：

- `stream()` 创建 `AssistantMessageStream` 后立即 push 一个空 assistant `Start`，再在后台 producer 中发起 HTTP/SSE；这样消费方可立即得到 well-formed lifecycle。
- `response.output_item.added`：
  - message → append `Content.Text("")`，push `TextStart`
  - reasoning → append `Content.Thinking("")`，push `ThinkingStart`
  - function_call → append `Content.ToolCall(encodedId, name, emptyObject)`，push `ToolCallStart`
- `response.output_text.delta` / refusal delta → append text，push `TextDelta`。
- `response.reasoning_*delta` → append thinking，push `ThinkingDelta`。
- `response.function_call_arguments.delta` → append raw JSON delta，best-effort parse 为 `JsonNode` partial，push `ToolCallDelta`。
- `response.function_call_arguments.done` 只更新 final arguments 并补发缺失 delta，不 push `ToolCallEnd`。
- `response.output_item.done` 对 function_call push exactly one `ToolCallEnd`；如果此前已收到 arguments.done，则使用 final arguments，否则使用 item 中 arguments 或累计 partial JSON。
- output item done for message/reasoning → push `TextEnd` / `ThinkingEnd`。
- `response.completed`：映射 usage；若 final content 含 tool call，则 `StopReason.TOOL_CALL`，否则 `STOP`。
- `response.incomplete`：`StopReason.LENGTH`。
- provider error / HTTP error / JSON parse fatal / stream ends before terminal response：terminal `Error(ERROR)`。
- cancellation observed before request or between provider events：terminal `Error(ABORTED)`。

### 5.4 Usage 与 SSE terminal 规则

Usage 映射规则：

```text
cached = input_tokens_details.cached_tokens || 0
cacheWrite = input_tokens_details.cache_write_tokens || 0
input = max(0, input_tokens - cached - cacheWrite)
output = output_tokens || 0
cacheRead = cached
cacheWrite = cacheWrite
totalTokens = total_tokens || input + output + cacheRead + cacheWrite
```

说明：Responses API 的 reasoning tokens 目前计入 `output_tokens`；Jcode 当前 `Usage` 没有独立 reasoning 字段，首版不扩展。

SSE terminal 规则：

- 看到 `response.completed` / `response.incomplete` 后再 EOF 或 `[DONE]`：按 terminal response 正常结束。
- 未看到 terminal response 就 EOF 或 `[DONE]`：terminal `Error(ERROR)`，错误信息说明 stream ended before terminal response。
- `response.failed` 或 `error` event：terminal `Error(ERROR)`。

## 6. 错误、取消与 producer 生命周期

- `DefaultModels.stream()`、`OpenAiProvider.stream()`、`OpenAiResponsesAdapter.stream()` 都不得同步抛；所有失败都归一为 stream terminal `Error`。
- 所有 synthetic failure stream 必须使用 `Start -> Error`，包括 unknown provider、unsupported model、auth 未配置、配置错误、HTTP/JSON/SSE/provider 错误。
- terminal failure 都必须携带 final `Message.Assistant`，`errorMessage` 非空，`usage` 无数据时用 `Usage.zero()`。
- `OpenAiResponsesAdapter.stream()` 创建 stream 并 push `Start` 后，使用 provider-owned virtual-thread executor 或显式传入的 executor 启动 producer；HTTP/SSE 读取、event mapping、terminal push 都在 producer 中执行。
- `stream()` 必须在 fake server 发送任何 SSE event 前返回，保证调用方可立即消费 stream。
- producer 必须 catch 所有异常并转换为 terminal `Error`；response body / input stream 在 `finally` 中关闭。
- adapter 必须保证每个 stream 最多一个 terminal event；terminal 后 late push 由 `AssistantMessageStream` 静默忽略。
- 取消为协作式，只保证在取消边界生效：dispatch 前、请求前、SSE event/chunk 之间。观察到取消后在下一个边界停止读取并 push `Error(ABORTED)`。
- 对 provider 长时间静默且底层 HTTP read 阻塞的情况，首版只依赖 request timeout 或关闭 response body；不承诺立即中断。

## 7. 测试计划

### 7.1 `ai` provider runtime tests

覆盖：

1. `DefaultModels` 按 provider id dispatch 到正确 `ModelProvider`。
2. unknown provider / unsupported model 生成 `Start -> Error` stream，不同步抛。
3. `providers` / `provider` / `models` / `model` 返回不可变快照。
4. `DefaultModels` 拒绝重复 provider id、重复 model ref、provider/model 不匹配。
5. `CopyOnWriteModels.setProvider/deleteProvider/clearProviders` 显式更新集合，无全局副作用。
6. `CopyOnWriteModels.stream()` 对 provider map 做 call-start snapshot，active stream 不受后续 mutation 影响。
7. `checkAuth` / `refresh` 对不存在 provider、静态 provider、失败 provider 的结果可预测。
8. `Models` 可直接作为 `ModelClient` 使用。
9. `AssistantMessageStreams.failed(...)` 严格生成 `Start -> Error`，且 `resultStage()` 正常完成。

### 7.2 OpenAI provider / contract tests

覆盖：

1. `OpenAiProvider` 暴露正确的 id/name/baseUrl/models/auth，并按 provider/api/model id 执行 `supports(ModelRef)`。
2. `OpenAiProvider` 不实现 `ModelClient`；low-level `OpenAiResponsesAdapter` 非 public。
3. `OpenAiCredentials` 与 `OpenAiProviderConfig.toString()` redacted，不泄露 API key。
4. `stream()` 在归属校验失败、配置错误、HTTP 失败、JSON 失败、provider error、已取消时均不同步抛。
5. 每个 failure stream 都以 `Start` 开始，以 `Error` 结束；成功 stream 以 `Done` 结束。
6. terminal `Done` 不使用 `ERROR/ABORTED`；terminal `Error` 只使用 `ERROR/ABORTED`。
7. `AssistantMessageStream.resultStage()` 最终完成为 final assistant，不用 failed stage 表达 provider/network 失败。
8. terminal 后 late events 被忽略。

### 7.3 request mapper tests

覆盖：

- system prompt + user text。
- assistant text replay。
- tool call replay + tool result output。
- `ToolSpec` JSON Schema 透传。
- `ToolSpec.minimal()` / `NullNode` 规范化为空 object schema。
- 非 object tool schema 返回 provider-level stream error。
- `ThinkingLevel.PROVIDER_DEFAULT` / `OFF` / supported level / unsupported level。
- model provider/api 不匹配返回 provider-level stream error。

### 7.4 SSE/parser tests

覆盖：

- 标准 `event:`/`data:` 分段。
- 多行 `data:` 合并。
- comment/heartbeat 忽略。
- `[DONE]` 或 EOF after terminal 正常结束。
- `[DONE]` 或 EOF before terminal 归一为 terminal `Error`。
- malformed JSON 归一为 terminal `Error`。

### 7.5 event mapper tests

覆盖：

- text start/delta/end 顺序与 partial 累积。
- tool call arguments delta 聚合与 final `JsonNode` 解析。
- `function_call_arguments.done` 不重复 emit `ToolCallEnd`。
- `response.output_item.done` 对 function call exactly once emit `ToolCallEnd`。
- `response.completed` usage 映射，包含 cached/cache-write token 公式。
- tool-call content 导致 `StopReason.TOOL_CALL`。
- `response.incomplete` 映射 `StopReason.LENGTH`。
- provider `response.failed`/`error` 映射 terminal `ERROR`。
- cancellation between events 映射 terminal `ABORTED`。

### 7.6 fake HTTP integration tests

使用 JDK `com.sun.net.httpserver.HttpServer` 或等价本地测试 server：

- 捕获请求 JSON，断言 path/header/body。
- 返回 `text/event-stream`，模拟 text response。
- 返回 function call response，断言 Jcode event 与 final assistant。
- 返回 non-2xx，断言 terminal `Error`。
- fake server 延迟发送 event，断言 `stream()` 先返回。
- pre-request cancellation、between-chunks cancellation、long-silent read limitation 均有测试或明确文档断言。
- 不依赖真实 OpenAI API key。

## 8. Maven 与边界改动

实施时需要：

1. 根 `pom.xml` 增加 `<module>ai-providers</module>`，位置在 `ai` 之后即可。
2. 新模块 POM 只声明：
   - `site.pplee:ai`
   - `jackson-databind`
   - `junit-jupiter`（test）
3. 保持 `agent-core` 不依赖 providers 模块（enforcer 排除 `site.pplee:ai-providers`）。
4. `ai` 新增 provider runtime 抽象不引入任何新第三方依赖。
5. 如后续创建产品/组合层，再单独细化 enforcer 规则；本实施不需要让任何现有模块依赖 `ai-providers`。
6. 新增 `ai-providers/AGENTS.md`，记录模块边界、配置与测试规则。

## 9. 实施步骤

1. **确认协议事实**：编码前重新核对 OpenAI Responses API 当前文档中的 streaming event 名称、function-call replay/input shape、`store: false` 行为。
2. **实现 `ai` provider runtime 抽象**：`ModelProvider`、`Models`、`MutableModels`、`DefaultModels`、`CopyOnWriteModels`、auth/refresh/status value types 与 JUnit 契约测试。
3. **实现 synthetic failure stream helper**：`AssistantMessageStreams.failed(...)` 与 lifecycle 测试。
4. **创建 OpenAI 模块骨架**：POM、包目录、模块 AGENTS、最小 smoke test。
5. **实现 `OpenAiProvider`**：metadata/model list/supports/auth/stream dispatch。
6. **实现 credential/config 安全类型**：`OpenAiCredentials`、`OpenAiProviderConfig` redacted display 与测试。
7. **实现 package-private Responses adapter 骨架**：no-sync-throw、producer lifecycle、terminal error 骨架。
8. **实现 request mapper**：先覆盖 text-only，再加入 tool spec、tool call/tool result replay、thinking policy。
9. **实现 SSE parser**：独立于 OpenAI event 语义，只产出 event name + JSON data。
10. **实现 event mapper**：把 Responses events 映射到 `AssistantMessageEvent` 与 final assistant。
11. **接入 HTTP reader**：用 JDK `HttpClient` 发送请求、读取 SSE、驱动 parser/mapper。
12. **补齐测试**：先 provider/Models contract，再 mapper/parser，再 fake HTTP integration。
13. **更新文档**：根 `AGENTS.md`、`README.md` 模块列表、`ai/AGENTS.md`、新增 `ai-providers/AGENTS.md`，必要时架构文档。
14. **验证**：运行目标模块、`ai` 和根 reactor 测试。
15. **归档计划**：实施完成后把本文件移入 `docs/plans/archived/`，并同步更新相关 AGENTS 文档。

## 10. 验收标准

- `mvn -pl ai test` 通过。
- `mvn -pl ai-providers -am test` 通过。
- `mvn verify` 通过。
- `git diff --check` 通过。
- `ai` 仍无 Jcode 内部依赖、无 provider SDK、无环境变量/API key 读取。
- `ai-providers` 无 `agent-core` 依赖。
- `OpenAiProvider implements ModelProvider`；低层 Responses adapter 保持 package-private。
- `DefaultModels.stream()` 与 `OpenAiProvider.stream()` 的所有失败路径都通过 `Start -> Error` terminal stream 表达。
- `OpenAiCredentials` / `OpenAiProviderConfig.toString()` 不泄露 API key。
- `CopyOnWriteModels` mutation 语义和 stream snapshot 语义有测试覆盖。
- OpenAI tool-call id codec、ThinkingLevel mapping、usage mapping、SSE terminal 行为有测试覆盖。
- 测试不需要网络、不需要真实 API key。
- 代码、POM、配置等非文档产物不显式提到 pi。

## 11. 风险与后续问题

- 提前抽象会扩大 `ai` 公共 API 面；因此抽象必须只表达 provider-neutral runtime，不承载 OpenAI、OAuth、缓存、产品配置或 UI 决策。
- OpenAI Responses API 的事件 shape 可能变化；编码前必须以官方当前文档为准。
- 当前 `CancellationSignal` 只有只读轮询能力，没有 listener；首版取消只能在请求前和 SSE 事件间被观察，无法保证 provider 长时间静默时立即中断底层 HTTP read。
- `Content.Thinking` 没有 provider-specific signature 字段；首版不做 reasoning replay，后续如需多轮 reasoning signature，需要重新设计标准内容或 provider-local metadata seam。
- `ToolCall.id` 单字段承载 Responses 的 call id/item id 是折中方案；若未来多个 provider 都需要结构化 id，可能需要在 `ai` 层引入 provider-neutral tool-call id metadata。
- `ProviderAuth` 首版只承载显式认证状态；OAuth、credential store、动态 auth interaction 应等产品层或第二类认证方式出现后再实现。
- 动态模型刷新、cost、cache、retry、service tier 先保留为 provider/runtime seam，不在首个 OpenAI adapter 中实现。
