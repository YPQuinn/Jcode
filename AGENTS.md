# PROJECT KNOWLEDGE BASE

**Generated:** 2026-09-17 02:55 PDT
**Branch:** master

## OVERVIEW

Java 21 多模块 Maven monorepo。`ai` 是 provider-neutral 模型调用协议层（零内部依赖），`agent-core` 是通用 Agent Runtime（仅依赖 `ai`）。库模块，无 main/Spring Boot 启动类。

## STRUCTURE

```
Jcode/
├── pom.xml              # 聚合 reactor: ai -> ai-providers -> agent-core; enforcer 强制模块边界
├── .mvn/jvm.config      # -Djava.io.tmpdir=C:/Temp/maven (会副作用创建根目录 C:/)
├── ai/                  # 模型调用协议层 (零 Jcode 内部依赖)
│   └── src/main/java/site/pplee/jcode/ai/
│       ├── client/      # ModelClient SPI + ModelRequest / ModelRequestOptions / ToolChoice / PromptCacheOptions
│       ├── concurrent/  # CancellationSignal 只读取消协议 + CancellationRegistration
│       ├── message/     # Message/Content/StopReason/Usage/CostEstimate/ResponseMetadata/ModelReplayState 标准 LLM 类型
│       ├── model/       # Model/ModelRef 模型身份 (provider/api/modelId)
│       ├── provider/    # ModelProvider/Models/DefaultModels/CopyOnWriteModels provider runtime 抽象
│       ├── stream/     # AssistantMessageEvent (sealed 12 variants) + AssistantMessageStream (push-pull) + AssistantMessageStreams (合成失败流)
│       ├── tool/        # ToolSpec + ToolInputConstraint 可声明工具规范
│       └── util/        # UnicodeSanitizer（无状态 UTF-16 well-formedness / unpaired surrogate 删除）
├── ai-providers/       # 共享的具体 provider 模块 (唯一依赖: ai; 每 provider 一子包, 当前含 openai)
│   └── src/main/java/site/pplee/jcode/aiproviders/openai/
│       ├── OpenAiProvider.java            # public provider runtime (implements ai.provider.ModelProvider)
│       ├── OpenAiProviderConfig.java      # 显式配置 (redacted toString) + credentials/capabilities/compatibility/serviceTier/OpenAiHeaders/OpenAiPricing/OpenAiRetryPolicy
│       ├── OpenAiHeaders.java             # 安全 custom header builder（redacted toString，reserved 头不可覆盖）
│       ├── OpenAiPricing.java             # 显式价格表（按 model id，DECIMAL128，默认 absent）
│       ├── OpenAiRetryPolicy.java         # 显式 opt-in 重试（maxRetries 默认 0，有界 delay/jitter/budget）
│       ├── OpenAiSecretRedactor.java      # package-private 请求本地敏感值清洗（terminal Error 统一 redacted）
│       ├── OpenAiResponsesAdapter.java    # package-private Responses HTTP/SSE 流 adapter（可中断 ActiveExchange + 可取消 backoff）
│       ├── OpenAiTranscriptPlanner.java   # package-private transcript 规划（reasoning replay / pairing / custom tools）
│       ├── OpenAiConstrainedSampling.java # package-private strict/grammar 映射与 custom-tool JSON delta
│       ├── OpenAiReplayStateCodec.java    # package-private opaque replay envelope
│       └── OpenAiPartialJsonParser.java   # package-private 有界 partial JSON 预览
├── agent-core/          # 通用 Agent Runtime (唯一内部依赖: ai)
│   └── src/main/java/site/pplee/jcode/agentcore/
│       ├── Agent.java           # 公开运行门面 (AutoCloseable, 虚拟线程)
│       ├── AgentConfig.java     # 构造 Agent 的配置 record (含 beforeToolCall/afterToolCall)
│       ├── AgentState.java      # 实时状态快照 (streaming/streamingMessage/pendingToolCalls/errorMessage)
│       ├── AgentLoop.java       # package-private 循环主干 (线性编排 runLoop, 流式消费, 调度 ToolCallExecutor)
│       ├── ToolCallExecutor.java # package-private 工具执行编排 (三阶段管道, 顺序/并行分发, LoopToolUpdateSink, ToolOutcome)
│       ├── AgentContext.java     # 不可变 transcript (systemPrompt/messages/tools)
│       ├── AgentLoopConfig.java  # package-private per-run 配置
│       ├── LoopState.java        # package-private 唯一可变状态
│       ├── LoopResult.java       # 一次 run 的结果
│       ├── ToolSchemaValidator.java  # package-private 最小 JSON Schema 校验器
│       ├── RunEventEmitter.java   # package-private per-run 同步事件投递 adapter (等待 sink stage)
│       ├── concurrent/  # CancellationSource (取消所有权)
│       ├── event/       # AgentEvent sealed (10 variants) + AgentEventSink
│       ├── message/     # AgentMessage 开放接口 + StandardAgentMessage 桥接 + ContextTransformer + MessageProjector
│       ├── queue/       # PendingMessageQueue/Source + QueueMode (steering/follow-up)
│       ├── tool/        # AgentTool + ToolExecutionResult + ToolExecutionMode + ToolUpdateSink + BeforeToolCall + AfterToolCall
│       └── turn/        # PrepareNextTurn + NextTurnUpdate + ShouldStopAfterTurn + TurnContext (下一 Turn 控制)
└── docs/
    ├── agents/          # issue tracker、triage 标签与 domain docs 配置
    ├── architecture/    # mdBook 架构解读文档集 (含写作规则 AGENTS.md)
    ├── issues/          # 本地问题分析与待发布 issue 记录（GitHub Issues 仍为权威 tracker）
    ├── plans/           # 方案文档
    │   └── archived/    # 已归档的过往实现计划，可以参考但不可作为当前的实施规范
    ├── references/      # pi 参考副本 (.gitignored, 不属本仓库规则)
    └── rules/           # 仓库级流程与规范文档
```

## CONVENTIONS

- Java 21：`record`、`sealed interface`、模式匹配、`Executors.newVirtualThreadPerTaskExecutor()`。
- 包名：`site.pplee.jcode.<module>`（模块名 `agent-core` → 包 `agentcore`，不带连字符）。
- 显式装配：provider/工具/hook 通过构造参数传入；禁止静态可变注册表、禁止 `ServiceLoader` 扫描、禁止 classpath 自动注册。
- 公开类型不可变；一次 run 的可变状态仅存在于 package-private `LoopState`。
- `ModelClient.stream()` 返回 `AssistantMessageStream`；adapter 不得同步抛，成功/错误/取消均通过 `Done`/`Error` 事件产生最终 `Message.Assistant`。
- `Agent` 内部包装用户 `AgentEventSink` 为归约 sink：先以 `AtomicReference<AgentState>` + CAS 更新 AgentState，再委托用户 sink。用户 sink 看到事件时状态已完成归约。
- 流式消费：`AgentLoop.consumeStream()` 在 `Start` 事件发 `MessageStarted`，在 delta 事件发 `MessageUpdated`，在 `Done`/`Error` 返回最终消息。partial 不进入 context。
- Context 投影：每次模型调用前依次执行 `ContextTransformer`（异步、取消感知）→ `MessageProjector`（同步），只生成本次请求视图，不修改 transcript。transformer 裁剪/注入的消息不进入 context、`LoopResult.newMessages` 或事件。回调失败归一为 terminal `ERROR`/`ABORTED` assistant，不使 run future 异常失败。取消在 transform 后和 project 后各检查一次。
- 下一 Turn 控制：每个正常 turn 的 `TurnCompleted` 之后依次执行 `PrepareNextTurn`（替换 context/model/thinking，Optional 空值表示保持）→ 原子应用 → `ShouldStopAfterTurn`（STOP 优雅停止，不改 stop reason、不 drain steering/follow-up）→ steering drain → follow-up drain。hook 失败归一为 terminal `ERROR`/`ABORTED` assistant；terminal model failure 跳过 hook；model/thinking 更新仅影响当前 run 后续 turn，context 替换持久到 `Agent.context()`。`ModelRequest` 携带绝对 `ThinkingLevel`（`PROVIDER_DEFAULT` 表示 adapter 不主动指定）和固定的 `ModelRequestOptions`（本批 `PrepareNextTurn` 不可改）。
- 消息事件序列：用户/toolResult 发 `MessageStarted → MessageCompleted`；assistant 发 `MessageStarted → MessageUpdated... → MessageCompleted`。
- 工具三阶段管道（prepare/execute/finalize）由 `ToolCallExecutor` 统一保证顺序；具体工具只实现 `AgentTool<A>`。
  - prepare：`prepareArguments` → `ToolSchemaValidator` → `BeforeToolCall` → `treeToValue`
  - execute：`tool.execute(id, args, ToolUpdateSink, cancellation)` → 异常转 error result → settle sink
  - finalize：`AfterToolCall` patch → 生成 `Message.ToolResultMessage`
- `terminate` 仅存在于运行时 `ToolExecutionResult`；标准 `Message.ToolResultMessage` 不含 terminate 字段。
- 工具参数边界用 Jackson `JsonNode`；具体工具用共享 `ObjectMapper.treeToValue()` 转强类型。
- 外部依赖版本由根 POM `<properties>` 固定，不用版本范围。当前：Lombok 1.18.46 / Jackson 2.18.2 / JUnit 5.11.4。
- 测试纯 JUnit 5，无 Mockito；自定义测试双放 `support/` 包；`smoke/` 验证环境可运行；`ToolPipelineTest` 验证三阶段管道契约。
- 类命名后缀：`*Config`/`*Loop`/`*State`/`*Result`/`*Source`/`*Sink`/`*Mode`/`*Request`/`*Client`/`*Spec`/`*Signal`/`*Validator`。
- 注释规范：关键类、接口、方法必须使用精炼准确的注释，符合 Javadoc 规范且必须用英文书写。
- 开始新功能时必须先参考 `docs/references/pi` 的源码和 `docs/references/pi-book` 的解读，参考但不照搬 pi 的设计意图与实现，确保符合项目整体架构和 java 项目设计原则。
- 如果 `docs/references/pi` 或者 `docs/references/pi-book` 不存在，先用 `git clone https://github.com/earendil-works/pi.git` 或 `https://github.com/ZhangHanDong/pi-book.git` 将仓库克隆到 `docs/references` 下。

## ANTI-PATTERNS (THIS PROJECT)

**模块边界（enforcer 强制）：**
- `ai` 不得依赖 `agent-core` 或任何 Jcode 模块。
- `agent-core` 仅可依赖 `ai`；禁止依赖 `ai-providers`/`coding-agent`/`server`/`tui`（前瞻 guard）。
- 依赖图必须无环，只能产品层 → 内核层。
- `message`/`event`/`tool`/`queue`/`concurrent` 是源码包，不是 Maven 模块。
- 禁止 `common`/`shared` 杂物模块。
- 不长期维护两套 `Message`/`Content`/`StopReason` 事实来源。

**并发与取消：**
- 每个 `Agent` 同时最多一个 active run；并发 `prompt()`/`continueRun()` fail fast。
- `CancellationSignal` 只读；不得 cast 回 `CancellationSource` 调 `cancel()`。`onCancellation` 只观察取消，listener 必须非阻塞。
- `cancel()` 幂等；listener 最多执行一次，失败不得阻止其他 listener 或让 `cancel()` 失败。
- 不阻塞轮询、不用 `Thread.sleep()`、不用长期共享无界平台线程池。
- `AgentEventSink.emit()` 返回的 stage 必须被等待；慢 sink 阻塞 run。`RunEventEmitter` 是唯一等待点——loop 和 `LoopToolUpdateSink` 均通过它投递事件。

**工具三阶段管道：**
- 工具失败/参数转换失败/schema 失败/未知工具 → 转 error `ToolExecutionResult`，不抛进 loop。
- `BeforeToolCall` 阻止 → error result，不执行工具。
- `AfterToolCall` 失败 → 保留原 result，不抛进 loop。
- `ToolUpdateSink` settle 后的迟到 update 被静默丢弃。
- `LENGTH` 响应中的 tool call 全部转 failure，禁止执行。
- 任一工具声明 `SEQUENTIAL`，整批按原顺序执行。
- 并行批次中 `ToolStarted` 与 prepare 按源顺序串行；`ToolCompleted` 按实际完成顺序由 loop 线程投递；tool-result 消息、context、`TurnCompleted.toolResults` 按源顺序写回。
- prepare 失败（未知工具/schema/before hook/参数转换）在 prepare pass 当场发 error `ToolCompleted`，不执行工具。
- 事件投递失败/executor 拒绝/中断等基础设施失败不归一为 tool error：已提交任务先 drain 再传播首个异常，未提交 entry 不执行；已接纳的 `ToolUpdate` 在 finalize 前投递完成。
- 并行结果必须恢复为原 tool-call 顺序后写回上下文。
- `terminate` 不进入标准 LLM transcript。

**ModelClient SPI：**
- adapter 不得同步抛异常；provider/网络失败必须通过返回的 `AssistantMessageStream` 推送 terminal `Error` 事件表达，不使用 failed `CompletionStage` 表示模型调用失败。

**Must-NOT-Have（首批实现）：**
- 无 Spring Boot/Guice/DI 容器。
- 无 session 文件/数据库/缓存。
- 无自动重试/compaction/prompt template/extension/plugin。
- 不用 `Map<String,Object>` 表示工具参数。
- 不在 `AgentLoop` 读 API key 或环境变量。

**注释与文档纪律：**
- 代码（类/接口/方法注释）、`pom.xml`、配置等**非文档产物**中**不得显式提到 pi**；对 pi 的参考、对比、链接仅允许出现在 `docs/` 和 AGENTS.md 下。

## COMMANDS

```bash
# 构建全模块（ai -> agent-core reactor 顺序）
mvn clean install

# 测试全模块
mvn test

# 单模块测试（agent-core 会自动拉 ai）
mvn -pl agent-core -am test

# 单模块测试（ai-providers 会自动拉 ai）
mvn -pl ai-providers -am test

# 单模块测试（ai 独立）
mvn -pl ai test

# 根 reactor 验证（enforcer + 全测试）
mvn verify

# 架构文档构建（需要先安装 mdBook）
mdbook build docs/architecture

# 架构文档本地预览
mdbook serve docs/architecture --open
```

## NOTES

- 根目录 `C:/` 目录是 `.mvn/jvm.config` 中 `-Djava.io.tmpdir=C:/Temp/maven` 在非 Windows 上的副作用。非源码，可忽略。
- `docs/references/pi/` 是参考仓库副本，`.gitignore` 忽略；其中的 `AGENTS.md` 不是本仓库规则。
- 无 Maven wrapper（`mvnw`）；用系统 `mvn`。
- 无 CI 配置（无 `.github/workflows`/`Jenkinsfile`）。
- jdtls（Java LSP）未安装；codegraph 未索引（`.codegraph/` 存在但未 `codegraph init`）。
- 未来模块（未创建）：`coding-agent`、`server`、`tui`。仅在出现真实独立使用者或产品入口时创建，不预建空模块。未来 provider 实现（anthropic/google 等）不再新建 Maven 模块，而是放入共享模块 `ai-providers` 的子包。
- `agent-core` 核心基线已包含模块 seam、流式协议、工具三阶段执行、Context 投影、下一 Turn 控制与并行工具双排序；对应实施方案均已归档。
- OpenAI Responses 正确性首批（OAI-001～OAI-009）已落地：`store:false` reasoning replay、transcript planner、可中断 HTTP/SSE、incomplete 分 reason 映射、stream/event fidelity。方案见 `docs/plans/archived/openai-responses-correctness-first-batch.md`。`Content.ToolCall` 仍走 `oai1:` id 编码，text/thinking 走 `ModelReplayState`；统一到 content replayState 须在引入第二个 provider 前完成。
- OpenAI Responses 第二批（OAI-010～OAI-018，OAI-014 不含 deferred tool loading）已落地。方案见 `docs/plans/archived/openai-responses-capabilities-resilience-second-batch.md`。
- OpenAI Responses 第二批 PR 1（OAI-010/OAI-011）已落地：`Content.Image`（base64-only，`toString()` redacted）、空工具结果 `"(no tool output)"`、vision/non-vision 图片占位与 `function_call_output` 内保留、`developer`/`system` 由 model capability × endpoint compatibility 共同决定。老 `OpenAiModelCapabilities` 两参数构造默认无 image、不优先 developer。
- OpenAI Responses 第二批 PR 2（OAI-012/OAI-013）已落地：provider-neutral `ModelRequestOptions`（`maxOutputTokens`/`temperature`/`ToolChoice`/`PromptCacheOptions`）、`AgentConfig` 固定透传；OpenAI max token clamp（1–15→16）、temperature/tool-choice capability、显式 `OpenAiServiceTier`；四种 cache retention、cache key 按 Unicode code point 截断 64；显式 `OpenAiEndpointProfile`/`OpenAiHeaders`（敏感值与 reserved 头不可覆盖，不按 URL 推断）。不支持的显式选项走 terminal mapping error。
- OpenAI Responses 第二批 PR 3（OAI-014 的 strict/grammar/custom-tool 部分，不含 deferred tool loading）已落地：`ai.tool.ToolInputConstraint`（`None`/`JsonSchema`/`Grammar` + `Requirement` PREFER/REQUIRE + `GrammarSyntax` LARK/REGEX，无 wire 映射）；`ToolSpec` 四参数 + 三参数默认 `None`；endpoint `strictTools`/`grammarTools` 显式配置、旧构造默认关闭、不从 URL 推断。普通 function payload 不变；strict 可转则发 `strict: true`，`PREFER` 安全降级、`REQUIRE` mapping error；grammar 映射 custom tool（LARK 优先，`lark`/`regex` 仅 provider 内翻译），Specific tool_choice 跟随同一 `resolveGrammar` 事实；stream 归一为标准 ToolCall 事件（JSON string field 正确转义；terminal `response.output` 权威 finalize 仍 open 的 custom slot），replay 走 `custom_tool_call`/`custom_tool_call_output`。
- OpenAI Responses 第二批 PR 4（OAI-015）已落地：`ResponseMetadata`（bounded optional response/request id 与 raw terminal reason，`empty()`，`toString` redacted）；`Message.Assistant.metadata` 兼容旧构造默认 empty，partial empty，terminal success/error 保留安全 metadata；`Usage.reasoningTokens` + optional `CostEstimate`（DECIMAL128，未定价 absent）；OpenAI 从 `response.created`/`response.id` / `status[.reason]` / 响应头 `x-request-id` 提取（非 2xx 不读 error body），`correlationSnapshot` 在 mapper 处理后同步到 exchange 供 synthetic ERROR/ABORTED 沿用；非法/溢出 usage 与非 object details 为 mapping error；`OpenAiPricing` 显式注入、按 model id、可选 threshold（严格大于）与 service-tier multiplier（未知非空 tier 不套用 requested multiplier）。
- OpenAI Responses 第二批 PR 5（OAI-016）已落地：`OpenAiRetryPolicy`（`maxRetries` 默认 0；`maxServerDelay`/`maxBackoff`/jitter 范围/可选 totalBudget 严格非负有界）；仅在 2xx SSE 被接受前重试；`x-should-retry` 覆盖 → 无 status transport → 408/409/429/500–599（不含 499/600+），其他 4xx 不重试；delay 优先 retry-after-ms / Retry-After 秒 / HTTP 日期 / 指数退避+jitter，非法 header 回退，负 delay 归零，超过 maxServerDelay 立即失败；`totalBudget` 从首次 attempt 起 wall-clock，wait 完成后与下一 attempt 发送前复查 deadline（clock 回拨视为 elapsed=0）；非 2xx body 纳入 ActiveExchange，bounded read 后立即 identity-safe close/detach，cancel/close 立即关闭，read IO 按 abort cause 归一且不重试；`OpenAiSecretRedactor` 请求本地清洗 credentials/org/project/全部 custom header values/原始与 clamp 后 cache key/session id，含外部文本的 terminal Error 统一清洗 message 与 metadata，`OpenAiHttpError` 生产路径 retained 文本即已清洗，内部异常与 SSE name/type conflict 用固定消息；`sendAsync` 同步 RuntimeException 固定 `request failed`，仅 `UncheckedIOException` 按 transport 重试。每次 attempt 重建 HttpRequest/BodyPublisher、复用请求 JSON bytes；provider-owned 有界 scheduler，取消/close 取消 timer，attempt 间不复用 EventMapper，全生命周期一次 Start 与一次 terminal。`OpenAiHttpError` 仅保留 retry allowlist 头、`x-request-id`、≤4KiB body 与标准 envelope；非 2xx body id 不进 metadata。
- OpenAI Responses 第二批 PR 6（OAI-017/OAI-018）已落地：`ai.util.UnicodeSanitizer` 单次 UTF-16 扫描删除 unpaired high/low surrogate，合法 pair/组合序列不变，已合法字符串返回原实例，无 normalization。OpenAI 仅在请求序列化边界做 request-local 清洗（system/developer prompt、可见 message/tool-result 文本、tool description、schema/arguments 的 textual JsonNode、grammar/custom input）；image base64 与 opaque replay/encrypted payload 不清洗。身份字段失败而非改写：tool name（先于 constraint resolution）、tool-call id、model/provider/api id、header name/value（含 Authorization API key）、JSON object field name、prompt cache key、assistant replay item id/phase。诊断不含原值。请求期身份失败走既有 Start → Error；header builder / 配置校验保持原 early-fail 契约。第二批协议矩阵收口于 `OpenAiResponsesProtocolMatrixTest`。deferred tool loading 未交付。

## Must Do After Change

- 每次任务完成后要询问用户是否需要提交代码，如果提交代码**必须**按照 `docs/rules/git-commit-message.md` 规范提交代码
- 每次代码提交前**必须**更新本文档以及各子 AGENTS.md 文档（如果有涉及到相应模块的更新）
- `docs/plans` 根目录下的计划文档已经实现后要挪入 `docs/plans/archived` 文件夹

## Agent skills

### Issue tracker

Issues and planning artifacts are tracked in GitHub Issues. See `docs/agents/issue-tracker.md`.

### Triage labels

Triage uses the five canonical skill roles. See `docs/agents/triage-labels.md`.

### Domain docs

Domain documentation uses a single-context layout. See `docs/agents/domain.md`.
