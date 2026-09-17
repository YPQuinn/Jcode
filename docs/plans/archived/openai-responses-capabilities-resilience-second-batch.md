# OpenAI Responses 能力与韧性第二批实施方案

> 状态：已实施（2026-09-17；PR 1–6 全部落地）
>
> 问题基线：[`docs/issues/openai-responses-protocol-parity.md`](../../issues/openai-responses-protocol-parity.md)
>
> 参考实现：`docs/references/pi` 中 `origin/main@588915ec71714688cee8b7153339e8bdebb3e82e`
>
> 实施范围：OAI-010～OAI-017；OAI-014 中 deferred tool loading 拆为后续独立事项
>
> 涉及模块：`ai`、`agent-core`、`ai-providers`

## 1. 背景

OpenAI Responses 第一批正确性修复已经完成 OAI-001～OAI-009，建立了以下基础：

- `store:false` 下的 reasoning replay；
- 请求前 transcript 规划与 tool-call/result 配对修复；
- 可立即中断的 HTTP/SSE 生命周期；
- `response.incomplete` 的准确终止语义；
- reasoning、message、tool-call 与 SSE event 的完整流式映射。

第二批不再修复主链路正确性，而是扩展内容、请求、工具和响应元数据能力，并补齐可选重试及防御性 Unicode 处理。实现仍需遵守现有模块边界、显式装配和流式错误协议，不追求与参考实现逐行一致。

## 2. 目标

实施完成后应满足：

1. 用户消息和工具结果能够携带 provider-neutral 图片内容。
2. 空工具结果、vision 降级和 system/developer role 选择具有确定行为。
3. 调用方能够类型安全地设置输出 token 上限、temperature、tool choice、prompt cache 和 session affinity。
4. OpenAI 特有的 service tier、endpoint compatibility 和自定义 header 不污染 `ai` 公共协议。
5. 工具能够声明 strict JSON Schema 或 grammar constrained sampling，并继续通过标准工具调用事件执行。
6. 响应 correlation id、原始终止原因、reasoning token 和可选成本估算可供调用方观察。
7. provider 可选择启用有界、可取消的重试，默认仍只请求一次。
8. 非法 UTF-16 surrogate 不会破坏请求序列化，且不会修改 canonical transcript。
9. 所有新增映射、配置、HTTP 和协议错误仍通过 `Start -> Error` 表达。

## 3. 非目标

本计划不实现：

- 图片 URL、本地文件路径、provider 侧自动下载或图片输出生成；
- 音频、视频、PDF 等其他多模态内容；
- 任意 JSON request payload extension 或 `Map<String, Object>` 逃生口；
- 自动识别 base URL 对应的兼容端点；
- provider SDK；HTTP 仍使用 JDK `HttpClient`；
- 默认启用重试；
- 在库中硬编码会随时间变化的 OpenAI 模型价格；
- deferred tool loading、tool search 和运行时动态工具发现；
- session 文件、数据库或其他持久化设施。

## 4. 总体设计原则

### 4.1 公共协议与 provider 能力分层

放入 `ai` 的内容必须是跨 provider 有意义的概念：

- 图片内容；
- generation/request options；
- tool choice；
- prompt-cache 意图；
- constrained sampling；
- response metadata、usage detail 和 cost estimate。

以下内容只存在于 `ai-providers/openai`：

- OpenAI endpoint profile；
- service tier；
- session header 具体名称；
- OpenAI grammar/custom tool payload；
- strict/grammar/developer-role 等 OpenAI capability 映射；
- OpenAI 价格表和 tier multiplier；
- OpenAI HTTP error envelope 与 retry headers。

### 4.2 显式能力，不做 URL 猜测

兼容端点行为必须由 `OpenAiProviderConfig` 显式配置。不得通过 host 字符串推断 OpenAI、OpenRouter 或其他代理，也不得在 mapper 中散落 provider-id 特判。

### 4.3 保留流契约

- `stream()` 同步 push `Start` 后返回。
- 参数校验、映射、HTTP、重试和 Unicode 处理错误都由异步执行路径转成 terminal `Error`。
- 一次调用最多产生一个 terminal event。
- 重试不得再次发 `Start`，也不得把多次尝试的 partial 混在同一消息里。

### 4.4 公共类型保持不可变和可诊断安全

- 图片、header、credential、replay payload 等大对象或敏感对象的 `toString()` 必须 redacted。
- 货币使用 `BigDecimal`，不使用 `double`。
- 所有集合在构造时执行 defensive copy。
- 不使用未限定大小的 provider metadata map。

## 5. 交付顺序

```text
PR 1  OAI-010 图片与工具结果规范化 + OAI-011 role capability
  ↓
PR 2  OAI-012 请求控制 + OAI-013 cache/session/header
  ↓
PR 3  OAI-014 strict/grammar/custom tools
  ↓
PR 4  OAI-015 response metadata、reasoning usage 与 cost
  ↓
PR 5  OAI-016 opt-in retry 与结构化 provider error
  ↓
PR 6  OAI-017 Unicode 清洗 + 第二批协议矩阵收口
```

每个 PR 必须独立保持 reactor 绿色。OAI-018 的协议矩阵随每个 PR 增量补充，不在最后一次集中补测试。

## 6. PR 1：图片、工具结果规范化和 prompt role

### 6.1 `Content.Image`

在 `ai.message.Content` 增加第四种 content：

```java
record Image(String mediaType, String base64Data) implements Content
```

契约：

- `mediaType` 和 `base64Data` 非空且非 blank；
- `mediaType` 必须是合法的 `image/*` media type；
- 只接受标准 base64，不接受完整 data URL；
- 内容以不可变字符串保存，不使用暴露可变性的 `byte[]` record component；
- `toString()` 只显示 media type 和编码长度，不输出图片数据；
- 第一批不增加 URL source。后续如有真实需求，再把 image source 演进为 sealed union。

`Message.User` 和 `Message.ToolResultMessage` 可以携带 `Content.Image`。OpenAI adapter 暂不生成 assistant 图片。

### 6.2 工具结果规范化

修改 `OpenAiTranscriptPlanner` 的 tool-result 映射：

- 无 content 或所有可见文本均为空时，输出稳定占位符 `"(no tool output)"`；
- tool result 中的多个文本块保持原顺序；
- vision 可用时，图片作为 `function_call_output` 的 content item 保留；
- vision 不可用时，图片变成稳定占位符，例如 `"[Image omitted: image/png]"`；
- 图片不得移动到后续 synthetic user message；
- tool result 中出现 `Thinking`、`ToolCall` 等不允许的 content 时返回映射错误，不静默丢弃。

### 6.3 用户图片映射

- vision 可用时，用户图片映射为 Responses `input_image`；
- data URL 只在 OpenAI mapper 内组装；
- vision 不可用时，图片按原 content 顺序变成稳定文本占位符；
- 相邻文本是否合并由 planner 统一决定，不修改 canonical message。

### 6.4 model 与 endpoint capability

扩展 provider-local capability，但避免把所有布尔字段平铺到一个长 record 中。建议拆分为：

- `OpenAiModelCapabilities`
  - reasoning 配置；
  - `imageInput`；
  - `developerRolePreferred`；
  - 后续 PR 所需的 temperature/tool capability。
- `OpenAiResponsesCompatibility`
  - endpoint 是否接受 developer role；
  - 后续 PR 所需的 cache/session/strict/grammar 能力。

有效能力由 model capability 与 endpoint compatibility 共同决定。

### 6.5 `developer` 与 `system`

system prompt 映射规则：

- model 声明优先使用 developer role，且 endpoint 支持时，发送 `developer`；
- 其他情况继续发送 `system`；
- 空 prompt 继续省略；
- custom endpoint 可以通过显式 compatibility 强制 `system`。

### 6.6 兼容性

- 为 `OpenAiModelCapabilities` 保留当前两参数构造路径或等价静态工厂；
- 老配置默认无 image capability，并维持原 `system` 行为；
- `Content` 是 sealed interface，所有 switch 和测试必须在同一 PR 更新完毕。

### 6.7 测试

至少覆盖：

- 图片 `toString()` 不泄露 base64；
- 空 tool result；
- text-only tool result；
- image-only 和 text+image tool result；
- vision/non-vision 的用户图片与工具图片；
- 图片保持在 `function_call_output` 内；
- `developer`、`system` 和 endpoint 强制降级；
- 非法 media type/base64；
- 不允许的 tool-result content 映射失败。

## 7. PR 2：请求控制、cache/session 和 header

### 7.1 provider-neutral request options

在 `ai.client` 增加不可变 `ModelRequestOptions`，并追加到 `ModelRequest`：

- `maxOutputTokens`：未设置或正整数；
- `temperature`：未设置或有限非负数；
- `ToolChoice`；
- `PromptCacheOptions`。

旧的四参数、五参数 `ModelRequest` 构造器继续存在，并使用 `ModelRequestOptions.defaults()`。

`ToolChoice` 使用 sealed 类型或等价的类型安全表示：

- `AUTO`；
- `NONE`；
- `REQUIRED`；
- `Specific(String toolName)`。

不得使用裸字符串同时表达模式和工具名。

### 7.2 Agent 传递

在 `AgentConfig` 中增加固定的 `ModelRequestOptions`：

- 缺省值为 `defaults()`；
- `AgentLoop` 每轮构造 `ModelRequest` 时原样传递；
- 本批不允许 `PrepareNextTurn` 修改 request options；如果后续出现逐 turn 调节的真实消费者，再扩展 `NextTurnUpdate`；
- 保留现有构造器兼容路径。

### 7.3 OpenAI request control 映射

- `maxOutputTokens` 未设置时不发送；
- 设置为 `1～15` 时发送 `16`；
- `>=16` 原值发送；
- 不接受 0 或负数，避免“0 表示省略”的双重语义；
- temperature 仅在 capability 支持时发送；
- `Specific` 的工具必须存在于本次声明列表；
- `REQUIRED` 在无工具时失败；
- 不支持的显式选项必须产生 terminal mapping error，不能静默忽略。

OpenAI 特有 `serviceTier` 放入 `OpenAiProviderConfig` 的显式枚举/值类型，不进入 `ModelRequestOptions`。

### 7.4 prompt cache 类型

`PromptCacheOptions` 包含：

- retention：`PROVIDER_DEFAULT`、`NONE`、`SHORT`、`LONG`；
- 可选 cache key；
- 可选 session affinity id。

语义：

- `PROVIDER_DEFAULT` 不主动覆盖 provider 默认缓存策略；
- `NONE` 不发送 cache key 和 affinity header；
- 其他模式由 provider capability 映射；
- OpenAI cache key 按 Unicode code point 截断到 64 个字符，不能按 UTF-16 code unit 截断；
- cache key 和 session id 是请求 hint，不进入 transcript 或 assistant message。

### 7.5 endpoint profile 与 session affinity

在 OpenAI 配置中增加显式 endpoint profile：

- `OPENAI`；
- `OPENAI_NO_SESSION`；
- `OPENROUTER`；
- 如需自定义组合，使用显式 `OpenAiResponsesCompatibility`，不做 URL 猜测。

映射：

- `OPENAI`：使用 OpenAI session header 形状，并发送 client request id；
- `OPENAI_NO_SESSION`：不发送 OpenAI session header，但可发送 client request id；
- `OPENROUTER`：使用 `x-session-id`；
- retention `NONE` 时以上 affinity header 全部省略。

### 7.6 custom header 安全模型

新增 `OpenAiHeaders` builder，而不是公开保存 header value 的 record 或 map：

- builder 支持普通 header 和显式 sensitive header；
- `toString()` 只显示 header 名和数量；
- 禁止 CR/LF、空名称和非法 HTTP token；
- 禁止覆盖 `Host`、`Content-Length`、`Content-Type`、`Accept`、认证头、组织/项目头和 cache/session 保留头；
- `Authorization` 只由 `OpenAiCredentials` 管理；
- `Proxy-Authorization` 或代理自定义认证头必须标记为 sensitive；
- custom headers 只做 additive merge，不隐式覆盖 typed config；
- header value 不得进入异常、事件或 config diagnostics。

若代理必须使用不同的 bearer token，应通过 `OpenAiCredentials` 配置该 token，而不是用 custom header 绕过认证边界。

### 7.7 测试

至少覆盖：

- max token clamp 的未设置、1、15、16 和更大值；
- temperature capability；
- 四种 tool choice 和非法指定工具；
- cache retention 四种状态；
- 64 code point 截断且不切断 emoji；
- 三种 endpoint profile 的 header/body；
- retention `NONE` 完全省略 affinity；
- reserved header 拒绝、CR/LF 拒绝、safe header 发送；
- config/header `toString()` 不泄露 value；
- AgentConfig 到 ModelRequest 的透传。

## 8. PR 3：strict、grammar 和 custom-tool stream

### 8.1 provider-neutral constrained sampling

在 `ai.tool` 增加 sealed `ToolInputConstraint`：

```java
sealed interface ToolInputConstraint {
    record None() implements ToolInputConstraint {}
    record JsonSchema(Requirement requirement) implements ToolInputConstraint {}
    record Grammar(
            Map<GrammarSyntax, String> variants,
            Requirement requirement
    ) implements ToolInputConstraint {}
}
```

相关枚举：

- `Requirement.PREFER`：能力不支持时允许安全降级；
- `Requirement.REQUIRE`：能力不支持时映射失败；
- `GrammarSyntax.LARK`；
- `GrammarSyntax.REGEX`。

`ToolSpec` 增加 constraint component，原三参数构造器默认 `None`。`AgentTool.spec()` 继续是声明事实来源，并增加可覆写的 constraint 方法或直接由实现覆写 `spec()`。

### 8.2 strict JSON Schema

- endpoint 支持 strict 时发送 `strict: true`；
- 不支持且策略为 `PREFER` 时退化为普通 function tool；
- 不支持且策略为 `REQUIRE` 时 terminal mapping error；
- plain function tool JSON 保持与当前完全兼容。

### 8.3 grammar validation 与映射

Grammar tool 必须满足：

- parameters schema 根节点是 object；
- 恰好一个 required 属性；
- 该属性存在于 `properties` 且类型为 string；
- 至少提供一个 endpoint 支持的 grammar variant；
- variant 内容非 blank。

OpenAI 映射优先级固定为 LARK，再到 REGEX。映射为 Responses custom tool format，但公共 `ToolSpec` 不出现 OpenAI custom-tool 字段名。

### 8.4 custom-tool event 归一化

扩展 `OpenAiEventMapper` 处理：

- custom tool output item added/done；
- custom tool input delta/done；
- terminal response 中 only-done recovery。

对外仍发标准事件：

```text
ToolCallStart
  → ToolCallDelta("{\"input\":\"...")
  → ToolCallEnd(arguments = {"input": "完整内容"})
```

要求：

- JSON delta 必须正确转义引号、反斜线和控制字符；
- 累积输入只能单调追加；
- done 事件中的最终输入为权威值；
- partial scratch buffer 不进入 transcript；
- 结果继续走现有 `AgentTool<A>` 参数校验和执行管道。

### 8.5 deferred tool loading 决策

OAI-014 在本计划中只交付 strict/grammar/custom-tool adaptation。Deferred tool loading 拆为后续独立 issue，原因是它需要同时设计：

- runtime 中工具从 deferred 到 available 的状态变化；
- tool search call/output 的标准表示；
- ToolResult 宣告新增工具的协议；
- transcript replay 顺序；
- 静态 `AgentContext.tools()` 与动态工具可见性的关系。

在这些语义明确前，不在 `ToolSpec` 增加无实际效果的 `deferred` 布尔字段。

### 8.6 测试

至少覆盖：

- 普通 function tool payload 不回归；
- strict supported、preferred fallback、required failure；
- LARK/REGEX 选择；
- schema 根类型、required 数量和属性类型校验；
- custom input 的 start/delta/end；
- 引号、反斜线、换行和 emoji；
- done-only recovery；
- 非单调输入、close 后变化和 malformed event；
- 最终 `JsonNode` 可以被现有 ToolCallExecutor 消费。

## 9. PR 4：响应元数据、reasoning usage 和 cost

### 9.1 response metadata

在 `ai.message` 增加 `ResponseMetadata`，并由 `Message.Assistant` 携带：

- 可选 response correlation id；
- 可选 provider request id；
- 可选 raw terminal reason。

约束：

- 每个字段有明确长度上限；
- 不允许任意 map 或完整 provider response；
- `ResponseMetadata.empty()` 表示无元数据；
- 旧 Assistant 构造器继续工作并填入 empty metadata；
- OpenAI output message id/phase 继续保存在 `ModelReplayState`，不重复建模。

OpenAI adapter 从 response body 和允许的 response header 提取 metadata。任何 request header、credential 或 error body 都不得进入 metadata。

### 9.2 usage detail

扩展 `Usage`：

- 增加 `reasoningTokens`；
- reasoning tokens 语义上属于 output token 的子集；
- 保留当前 input/cache-read/cache-write 公式；
- partial message 继续使用 `Usage.zero()`；
- 提供旧五参数构造器兼容路径。

如 provider 返回明显违反约束的负数或 reasoning 大于 output，作为协议映射错误处理，不静默修正计费数据。

### 9.3 provider-neutral cost estimate

在 `ai.message` 增加可选 `CostEstimate`：

- currency；
- input；
- output；
- cache read；
- cache write；
- total；
- 所有金额使用非负 `BigDecimal`；
- 明确标记为 estimate，而非 provider invoice。

`Usage` 可以携带 optional/nullable `CostEstimate`；未配置价格时必须是 absent，不能用全零冒充已计算成本。

### 9.4 OpenAI pricing 配置

价格只通过 `OpenAiProviderConfig` 显式提供，按 model id 查找：

- 每百万 input token；
- 每百万 output token；
- 每百万 cache-read token；
- 每百万 cache-write token；
- 可选上下文阈值 tier；
- 可选 service-tier multiplier。

不在仓库中维护“最新 OpenAI 价格常量”。调用方负责提供与其账号、endpoint 和时间点一致的价格表。

计算要求：

- 采用 `MathContext.DECIMAL128` 或文档化的等价精度；
- 组件 cost 之和产生 total；
- service tier multiplier 在 OpenAI provider 内应用；
- 价格表缺失时只返回 token usage；
- 价格配置和 cost diagnostic 不包含 credential。

### 9.5 测试

至少覆盖：

- response id、request id 和 raw reason；
- metadata 长度边界和 redaction；
- reasoning token 提取；
- usage 不变量；
- 无价格表时 cost absent；
- 普通价格、cache 价格、阈值 tier 和 service tier multiplier；
- `BigDecimal` 精度与 total 汇总；
- terminal success/error 都能保留已有的安全 correlation metadata。

## 10. PR 5：opt-in retry 和结构化 provider error

### 10.1 retry policy

在 OpenAI 配置中增加不可变 `OpenAiRetryPolicy`：

- `maxRetries`，默认 0；
- `maxServerDelay`，默认 60 秒；
- exponential backoff 上限；
- jitter 范围；
- 可选总重试预算。

默认配置必须保持当前“一次请求、不重试”的行为。

### 10.2 可重试条件

按以下顺序判断：

1. `x-should-retry: true/false` 显式覆盖默认状态规则；
2. 无 HTTP status 的 connect/send transport failure 可重试；
3. HTTP 408、409、429、500–599 可重试（499 与 600+ 不按 5xx）；
4. 其他 4xx 不重试。

服务端 delay 解析顺序：

1. `retry-after-ms`；
2. `Retry-After` 数字秒；
3. `Retry-After` HTTP 日期；
4. exponential backoff + jitter。

服务端要求的 delay 超过 `maxServerDelay` 时直接失败，不能无限等待。负 delay 规范化为立即重试。

### 10.3 重试边界

- 仅在成功 SSE response 尚未被 adapter 接受前重试；
- 一旦收到 2xx 并进入 SSE 消费，不重试 EOF、parse error 或中途断线，避免重复输出和工具调用；
- 非 2xx error body 必须纳入 ActiveExchange，cancel/close 立即关闭；bounded read 完成（成功或失败）后立即 identity-safe close/detach，不得把剩余 body 拖过 backoff/下一 attempt；body 读取 IO 先按 abort cause 归一，不得继续调度 retry；
- `totalBudget` 是从首次 attempt 起的 wall-clock 总预算；wait 完成后与下一 attempt 发送前必须复查 deadline；clock 回拨视为 elapsed=0；已超预算时即使 delay 为 0 也不再发送；
- `sendAsync` 同步 `RuntimeException` 使用固定 redacted 失败语义；仅确属 transport（`UncheckedIOException`）时按既定规则重试，不得把编程错误误判为网络错误；
- 每次 attempt 使用新的 `HttpRequest`/`BodyPublisher`；
- request JSON 可以预先序列化为不可变 byte array 并复用；
- 多次 attempt 共用一个 stream lifecycle，只发一次 `Start` 和一次 terminal event；
- attempt 间不得保留 event mapper 的 partial state。

### 10.4 可取消 backoff

使用 provider-owned、可关闭的有界 scheduler：

- 不使用 `Thread.sleep()` 或轮询；
- cancellation listener 只取消 timer/future，不阻塞；
- caller cancellation 在 backoff 中立即产生 `ABORTED`；
- provider close 取消 timer 和 active exchange，并产生 `ERROR("provider is closed")`；
- close 后不再提交新 retry task；
- scheduler 生命周期由 `OpenAiResponsesAdapter.close()` 管理。

为测试提供 package-private clock/random/scheduler seam，生产公开 API 不暴露这些基础设施对象。

### 10.5 结构化 HTTP error

增加 package-private `OpenAiHttpError` 或等价值对象：

- status；
- retry 相关 header；
- provider request id；
- 截断后的 body；
- 解析后的 OpenAI error code/message/type。

规则：

- body 最多保留 4 KiB；
- 优先解析标准 OpenAI error envelope；
- 解析失败时使用 status + 截断 body；
- 不输出 request headers、API key、organization/project secret 或完整异常对象；
- 最终错误消息稳定、可测试；生产 `OpenAiHttpError.parse` 必须传入 request-local `OpenAiSecretRedactor`，retained body/envelope/requestId/diagnostic 在对象内即已清洗；mapper 直接生成的 terminal Error 与 synthetic `completeOnce` 同时清洗 message 与 metadata 三字段；纯内部异常与 SSE event-name/type conflict 使用固定稳定消息，不拼任意 `RuntimeException`/`IOException` 或 provider 原文；
- retry 判断不依赖错误消息字符串。

### 10.6 测试

使用 `FakeOpenAiServer` 至少覆盖：

- 默认零重试；
- transport failure 后成功；
- 408/409/429/500 后成功；
- 400/401/403 不重试；
- `x-should-retry` true/false 覆盖；
- `retry-after-ms`、秒数和 HTTP 日期；
- 超过 max delay；
- backoff 中 caller cancellation；
- backoff 中 provider close；
- 2xx SSE 开始后断开不重试；
- 每次 attempt 的 request body 一致；
- terminal-once；
- 标准/非标准/超长 error body；
- error 和 config diagnostics 不泄露 header/credential。

## 11. PR 6：Unicode 清洗和协议矩阵收口

### 11.1 sanitizer

在 `ai` 增加 provider-neutral、无状态的 Unicode 工具，例如：

```java
public final class UnicodeSanitizer {
    public static String removeUnpairedSurrogates(String value) { ... }
}
```

行为：

- 删除未配对 high surrogate；
- 删除未配对 low surrogate；
- 合法 surrogate pair 保持原样；
- 完全合法的字符串直接返回原实例；
- 不做 Unicode normalization，不修改组合字符；
- 输入非 null，null 由调用方边界处理。

实现使用单次 UTF-16 扫描，不使用可能依赖 lookbehind 行为的复杂正则。

### 11.2 应用边界

在 OpenAI 请求 mapper/serializer 边界清洗，不修改 `Message`、`ToolSpec` 或 `AgentContext`：

- system/developer prompt；
- user/assistant/tool-result text；
- tool description；
- JSON Schema 中所有 textual node；
- tool-call arguments 中所有 textual node；
- grammar/custom-tool input；
- image media type 之外不处理 base64 payload。

对结构标识符采用“校验失败”而不是静默清洗：

- tool name；
- tool-call id；
- model/provider/api id；
- header name/value。

原因是清洗标识符可能改变 tool lookup、call/result pairing 或认证语义。文本可以清洗，身份字段必须保持精确。

JsonNode 清洗必须创建 request-local deep copy；不得原地修改 ToolSpec schema 或 transcript arguments。

### 11.3 测试

至少覆盖：

- 单独 high surrogate；
- 单独 low surrogate；
- 连续多个非法 surrogate；
- 合法 emoji 和补充平面字符；
- 非法 surrogate 与合法 pair 混排；
- prompt、message、tool result、schema、嵌套 argument、grammar input；
- 原始 Message/JsonNode 未变化；
- 合法字符串 JSON decode 后 byte-for-byte 等价；
- 非法结构标识符明确失败。

## 12. OAI-018 第二批协议回归矩阵

维护一组以“输入能力 × endpoint capability × event shape × terminal shape”为维度的测试矩阵。至少包含：

| 维度 | 场景 |
| --- | --- |
| 内容 | text、empty、image、text+image、vision fallback |
| prompt role | system、developer、forced system |
| request | token clamp、temperature、tool choice、service tier |
| cache | provider default、none、short、long、OpenAI/OpenRouter affinity |
| tools | plain、strict、grammar LARK、grammar REGEX、preferred fallback、required failure |
| stream | delta、done-only、malformed custom input、early EOF |
| usage | cache tokens、reasoning tokens、无价格、有价格、tier multiplier |
| retry | no retry、status retry、header override、delay、cancel、close |
| Unicode | valid BMP、emoji、unpaired surrogate、nested JsonNode |

矩阵测试以 mapper/event mapper 的纯单元测试为主，以 `FakeOpenAiServer` transport 测试为辅。不得把所有组合做成笛卡尔积导致测试不可维护；每条协议规则至少有一个正例和一个反例。

## 13. 预期文件变更

### `ai`

可能新增或修改：

- `message/Content.java`
- `message/Message.java`
- `message/Usage.java`
- `message/ResponseMetadata.java`
- `message/CostEstimate.java`
- `client/ModelRequest.java`
- `client/ModelRequestOptions.java`
- `client/ToolChoice.java`
- `client/PromptCacheOptions.java`
- `client/CacheRetention.java`
- `tool/ToolSpec.java`
- `tool/ToolInputConstraint.java`
- `tool/GrammarSyntax.java`
- `util/UnicodeSanitizer.java`

### `agent-core`

可能新增或修改：

- `AgentConfig.java`
- `AgentLoop.java`
- `tool/AgentTool.java`
- 相关构造器、投影和工具管道测试

不应修改工具执行三阶段语义、事件等待点或并行排序规则。

### `ai-providers/openai`

可能新增或修改：

- `OpenAiProviderConfig.java`
- `OpenAiModelCapabilities.java`
- `OpenAiResponsesCompatibility.java`
- `OpenAiHeaders.java`
- `OpenAiRetryPolicy.java`
- `OpenAiPricing.java`
- `OpenAiRequestMapper.java`
- `OpenAiTranscriptPlanner.java`
- `OpenAiEventMapper.java`
- `OpenAiResponsesAdapter.java`
- 新增 package-private retry/error/unicode mapping helper
- `support/FakeOpenAiServer.java`

## 14. 迁移与兼容策略

1. 所有扩展现有 public record 的改动都提供兼容构造器。
2. 新 optional 字段使用明确的 `defaults()`/`empty()`，不让调用方手工拼 null。
3. 原有 text/function-tool 请求在默认配置下生成与当前等价的 payload。
4. image、sampling、cache、strict/grammar、cost 和 retry 均为显式 opt-in。
5. custom endpoint 的默认 capability 采取保守值；不支持的高级能力不擅自发送。
6. 若某项显式要求无法满足，失败而不是静默退化；只有声明 `PREFER` 的 constrained sampling 允许降级。
7. 实施中同步更新根 `AGENTS.md`、`ai/AGENTS.md`、`agent-core/AGENTS.md` 和 `ai-providers/AGENTS.md` 中受影响的知识库条目。

## 15. 验证命令

每个 PR 至少执行对应模块测试；合并前执行完整验证：

```bash
mvn -pl ai test
mvn -pl ai-providers -am test
mvn -pl agent-core -am test
mvn verify
```

该仓库是库模块，无 Java main/Spring Boot 入口，因此本计划不使用 Gravity 启动流程。

## 16. 完成条件

本计划完成需同时满足：

- OAI-010、OAI-011、OAI-012、OAI-013、OAI-015、OAI-016、OAI-017 的验收条件全部通过；
- OAI-014 的 strict/grammar/custom-tool 部分完成，deferred 部分已有独立 issue 或明确保留在 backlog；
- OAI-018 覆盖第二批所有已交付规则；
- 默认 text/function-tool 行为无回归；
- API key、header value、图片 payload、replay payload 不出现在 diagnostics；
- `mvn verify` 通过；
- 文档状态更新为已实施，并移动到 `docs/plans/archived/`。

## 17. 已确定决策

1. 图片第一批只支持 base64 data，不支持 URL 或本地路径。
2. endpoint compatibility 必须显式配置，不通过 URL 自动识别。
3. 任意 payload extension 不进入本批实现。
4. Deferred tools 不与 strict/grammar 同批实现。
5. Cost 使用调用方显式提供的价格表；未配置时为 absent。
6. Retry 默认关闭，且 SSE 开始后不重试。
7. Unicode 文本在 provider 边界清洗，结构标识符不做静默改写。

## 18. 实施记录

### PR 1–PR 3（OAI-010～OAI-013 与 OAI-014 已交付部分）

- PR 1（OAI-010 / OAI-011）：`Content.Image` 仅 base64 `image/*`，`toString` 不含 payload。用户/工具结果可带图；空白文本在有图片时抑制，仅当既无非空白文本也无图片时才输出 `"(no tool output)"`；vision 开启映射 `input_image`（data URL 仅 mapper 内组装），否则按原顺序 `"[Image omitted: …]"`。`imageInput` / `developerRolePreferred` 与 endpoint `developerRole` 共同决定 `developer` vs `system`；空 prompt 省略；`forceSystem()` 不改 transcript。
- PR 2（OAI-012 / OAI-013）：`ModelRequestOptions` 承载 `maxOutputTokens`（1–15 clamp 16）、temperature、`ToolChoice`；不支持的显式选项 mapping error。`PromptCacheOptions`：`NONE` 不写 cache/affinity；`PROVIDER_DEFAULT` 不覆盖 retention，显式 key 仍可发；`SHORT`/`LONG` 按能力发送 `prompt_cache_key` 与 `24h` retention。cache key 按 code point 截断 64。session affinity 按显式 `OpenAiEndpointProfile`（`session_id`+`x-client-request-id` / 仅 client request id / `x-session-id`）。`OpenAiHeaders` 加法合并、保留头不可覆盖、sensitive 头 redacted；`OpenAiServiceTier` 仅 OpenAI 配置。不从 URL 推断 endpoint。
- PR 3（OAI-014 已交付部分）：`ToolSpec` 声明 `JsonSchema` / `Grammar`。strict 仅在 endpoint `strictTools` 且 schema 可转 OpenAI subset 时发送；`PREFER` 可降级普通 function，`REQUIRE` 失败。`Grammar` 在 `grammarTools` 下映射 custom tool（LARK 优先 REGEX）；schema 必须是恰好一个 required string property。custom-tool 流归一为标准 `ToolCallStart`/`Delta`/`End`；下一 turn 走 `custom_tool_call` / `custom_tool_call_output`。deferred tool loading 未交付。

### PR 6（OAI-017 / OAI-018，已交付）

- `ai.util.UnicodeSanitizer`：单次 UTF-16 扫描；删除 unpaired high/low surrogate；合法 pair 原样；已合法字符串返回原实例；无 normalization；null 由调用方拒绝。`isWellFormedUtf16` 供身份校验。
- OpenAI 仅在 mapper/planner/constrained-sampling/header 边界清洗。`OpenAiRequestUnicode` 做 request-local 文本清洗与 JsonNode deep copy。不修改 Message、ToolSpec、schema/arguments 原节点、AgentContext 或 replay/encrypted payload；不清洗 image base64。
- 清洗范围：system/developer prompt；user/assistant/tool-result 可见文本；tool description；JSON Schema 与 tool-call arguments 的 textual node（含嵌套）；grammar definition 与 custom-tool input。prompt cache key 是 cache identity，非法 UTF-16 失败，不静默清洗。
- 身份字段失败而非改写：tool name（任何 constraint resolution / 诊断引用前）、tool-call id、model/provider/api id、header name/value（含 Authorization API key、session/org/project）、JSON object field name、prompt cache key、assistant replay item id 与非 null phase。诊断不含畸形/敏感原值。请求期失败走 Start → Error；`OpenAiHeaders` 与既有 header 校验保持配置期失败。不清洗、不改写 replay payload。
- OAI-018 矩阵增量补充 Unicode 正反例（BMP/emoji/unpaired/nested JsonNode/身份失败），并与内容、role、request、cache、tools、stream、usage、retry 规则并列收口。deferred tool loading 未交付。

### PR 5（OAI-016，已交付）

- `OpenAiRetryPolicy`：`maxRetries` 默认 0；`maxServerDelay` 默认 60s；`maxBackoff` 默认 8s；jitter 默认 0.75–1.0；可选 `totalBudget`。全部非负且有上限（retries ≤32，delay ≤10min，budget ≤30min）。旧 `OpenAiProviderConfig` builder 默认 disabled，`toString` 不含 credential。
- Adapter：仅在 2xx SSE 接受前重试；接受后 EOF/parse/mapping/read 不重试。`x-should-retry` → transport（无 status）→ 408/409/429/500–599。delay 顺序 retry-after-ms / Retry-After 秒 / HTTP 日期 / 指数退避+jitter。非法 header 回退；负 delay 归零；超过 `maxServerDelay` 或剩余 budget 立即失败。`totalBudget` 在 wait 完成后与下一 attempt 发送前复查；clock 回拨视为 elapsed=0。非 2xx body 纳入 ActiveExchange，cancel/close 立即关闭，read IO 按 abort cause 归一不重试。`sendAsync` 同步 `RuntimeException` 固定 `request failed`，仅 `UncheckedIOException` 按 transport 重试。每次 attempt 重建 HttpRequest/BodyPublisher，复用 JSON bytes。一次 Start、一次 terminal；attempt 间新 EventMapper。
- 可取消 backoff：provider-owned 单线程 daemon scheduler；listener 只 cancel timer/future；caller cancel → `ABORTED`；close → 取消 timer 与 exchange 并 `ERROR("provider is closed")`；close 后不提交 retry。package-private `OpenAiRetrySupport` 提供 clock/jitter/scheduler seam。
- `OpenAiHttpError`：status、retry allowlist 头、`x-request-id`、≤4KiB body、envelope code/message/type；稳定诊断经 `OpenAiSecretRedactor` 清洗；非 2xx body id 不进 metadata。
- `OpenAiSecretRedactor`：请求本地收集 credentials/org/project/custom header values/原始与 clamp cache key/session id；含外部文本的 terminal Error（含 mapper Error）统一清洗；内部异常用固定消息。
- 测试：`OpenAiResponsesRetryTest` + `FakeOpenAiServer` enqueue/drop 覆盖计划 10.6；499/500/599/600 边界；阻塞 error-body cancel/close；secret echo（envelope/raw/SSE/exception）；fake clock/scheduler budget；矩阵增量补充 retry 正反例。

### PR 4（OAI-015，已交付）

- `ai.message.ResponseMetadata`：optional response correlation id / provider request id / raw terminal reason；每字段 256 UTF-16 上限；`empty()`；`toString()` 只报 present/absent。
- `Message.Assistant` 增加 `metadata`；五/六参数构造器与 `of(...)` 源码兼容并默认 empty。partial 使用 empty；terminal success/error 保留安全 metadata。
- `Usage` 增加 `reasoningTokens` 与 `Optional<CostEstimate> cost`；保留五参数构造器。`CostEstimate` 使用 `BigDecimal` + `MathContext.DECIMAL128`，total 必须等于组件和。
- OpenAI：`response.created` 有界记录 id（不进 partial），terminal `response.id` 优先否则回退 created；`correlationSnapshot` 在每次 mapper 处理后同步到 exchange，synthetic terminal 保留安全 id/reason。raw reason 为 `status` / `status.reason`；provider request id 只从响应头 `x-request-id` 提取。非 2xx 不解析 error body。token 字段 exact integral long + checked 加减；details present 非 object 失败。`OpenAiPricing` 仅显式注入；未知非空 `service_tier` 不套用 requested multiplier。
- OAI-016 与 OAI-017 已在后续 PR 交付；见上文 PR 5 / PR 6 记录。
