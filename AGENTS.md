# PROJECT KNOWLEDGE BASE

**Generated:** 2026-07-29 16:50 CST
**Commit:** 83b1feb
**Branch:** master

## OVERVIEW

Java 21 多模块 Maven monorepo，基于 pi（earendil-works/pi 0.82.1）设计思想实现最小 agent loop。`ai` 是 provider-neutral 模型调用协议层（零内部依赖），`agent-core` 是通用 Agent Runtime（仅依赖 `ai`）。库模块，无 main/Spring Boot 启动类。

## STRUCTURE

```
Jcode/
├── pom.xml              # 聚合 reactor: ai -> agent-core; enforcer 强制模块边界
├── .mvn/jvm.config      # -Djava.io.tmpdir=C:/Temp/maven (会副作用创建根目录 C:/)
├── ai/                  # 模型调用协议层 (零 Jcode 内部依赖)
│   └── src/main/java/site/pplee/jcode/ai/
│       ├── client/      # ModelClient SPI + ModelRequest 边界
│       ├── concurrent/  # CancellationSignal 只读取消协议
│       ├── message/     # Message/Content/StopReason/Usage 标准 LLM 类型
│       ├── model/       # Model/ModelRef 模型身份 (provider/api/modelId)
│       └── tool/        # ToolSpec 可声明工具规范
├── agent-core/          # 通用 Agent Runtime (唯一内部依赖: ai)
│   └── src/main/java/site/pplee/jcode/agentcore/
│       ├── Agent.java           # 公开运行门面 (AutoCloseable, 虚拟线程)
│       ├── AgentLoop.java       # package-private 循环主干 (15步 runLoop)
│       ├── AgentConfig.java     # 构造 Agent 的配置 record
│       ├── AgentContext.java     # 不可变 transcript (systemPrompt/messages/tools)
│       ├── AgentLoopConfig.java  # package-private per-run 配置
│       ├── LoopState.java        # package-private 唯一可变状态
│       ├── LoopResult.java       # 一次 run 的结果
│       ├── concurrent/  # CancellationSource (取消所有权)
│       ├── event/       # AgentEvent sealed + AgentEventSink
│       ├── message/     # AgentMessage 开放接口 + StandardAgentMessage 桥接
│       ├── queue/       # PendingMessageQueue/Source + QueueMode (steering/follow-up)
│       └── tool/        # AgentTool/A> + ToolExecutionResult + ToolExecutionMode
└── docs/
    ├── architecture/    # pi-inspired-module-boundaries.md (模块边界权威文档)
    ├── plans/           # java21-agent-loop-minimal-plan.md (Wave 0-5 实现计划)
    └── references/      # pi 参考副本 (.gitignored, 不属本仓库规则)
```

## WHERE TO LOOK

| 任务 | 位置 | 说明 |
|------|------|------|
| 理解模块边界与依赖方向 | `docs/architecture/pi-inspired-module-boundaries.md` | §1 决策摘要、§5 Wave 0-5、§7 明确拒绝项 |
| 理解 agent loop 15 步序列 | `agent-core/src/main/java/site/pplee/jcode/agentcore/AgentLoop.java` | runLoop() 私有方法，注释标注 step 1-15 |
| 添加新工具 | 实现 `agentcore.tool.AgentTool<A>` | spec()/execute()，参数边界 JsonNode |
| 接入新 model provider | 实现 `ai.client.ModelClient` | 返回 CompletionStage，不得同步抛 |
| 公开运行入口 | `agentcore.Agent` | prompt()/continueRun()/steer()/followUp()/abort()/close() |
| 模型调用 seam | `ai.client.ModelClient.generate(ModelRequest, CancellationSignal)` | provider-neutral，不含 Agent 语义 |
| 消息投影到模型 | `AgentLoop.projectMessages()` | Wave 0 内联，Wave 3 拆 MessageProjector |
| 并发/取消 | `agentcore.concurrent.CancellationSource` + `ai.concurrent.CancellationSignal` | source 持有取消权，signal 只读 |
| steering/follow-up 队列 | `agentcore.queue.PendingMessageQueue` | QueueMode ALL/ONE_AT_A_TIME，volatile 可改 |
| 测试双 | `agent-core/src/test/.../support/` | ScriptedModelClient/RecordingEventSink/TestTools |

## CODE MAP

| Symbol | Type | Location | Role |
|--------|------|----------|------|
| `Agent` | class (public, AutoCloseable) | agent-core/.../Agent.java | 公开运行门面，virtual-thread executor，active-run 互斥 |
| `AgentLoop` | class (pkg-private) | agent-core/.../AgentLoop.java | 循环主干，15 步 runLoop，工具调度，事件发射 |
| `AgentConfig` | record (public) | agent-core/.../AgentConfig.java | 构造 Agent 的配置，含默认值 |
| `AgentContext` | record (public, immutable) | agent-core/.../AgentContext.java | systemPrompt + messages + tools，append 返回新实例 |
| `LoopState` | class (pkg-private) | agent-core/.../LoopState.java | 核心层唯一可变消息集合持有者 |
| `LoopResult` | record (public) | agent-core/.../LoopResult.java | context + newMessages |
| `AgentMessage` | interface (open, non-sealed) | agent-core/.../message/AgentMessage.java | 产品层可扩展 transcript 标记接口 |
| `StandardAgentMessage` | class (public) | agent-core/.../message/StandardAgentMessage.java | ai.Message 桥接到 AgentMessage |
| `AgentTool<A>` | interface (public) | agent-core/.../tool/AgentTool.java | 工具契约：spec + execute + executionMode |
| `ToolExecutionResult` | record (public) | agent-core/.../tool/ToolExecutionResult.java | 工具执行内部结果 (content/error/terminate) |
| `AgentEvent` | sealed interface | agent-core/.../event/AgentEvent.java | 7 个生命周期事件 record |
| `AgentEventSink` | @FunctionalInterface | agent-core/.../event/AgentEventSink.java | emit() 返回 CompletionStage，loop 等待 |
| `PendingMessageQueue` | class (public) | agent-core/.../queue/PendingMessageQueue.java | ConcurrentLinkedQueue 实现 |
| `CancellationSource` | class (public) | agent-core/.../concurrent/CancellationSource.java | AtomicBoolean，signal() 返回不可 cast 的只读视图 |
| `ModelClient` | interface (public, SPI) | ai/.../client/ModelClient.java | 模型调用 seam，不得同步抛 |
| `ModelRequest` | record (public) | ai/.../client/ModelRequest.java | model + systemPrompt + messages + tools |
| `Message` | sealed interface | ai/.../message/Message.java | User/Assistant/ToolResultMessage |
| `Content` | sealed interface | ai/.../message/Content.java | Text/Thinking/ToolCall |
| `Model`/`ModelRef` | record | ai/.../model/ | provider/api/modelId 三维度身份 |
| `ToolSpec` | record | ai/.../tool/ToolSpec.java | name/description/parameters(JsonNode) |
| `StopReason` | enum | ai/.../message/StopReason.java | STOP/TOOL_CALL/LENGTH/ERROR/ABORTED |
| `CancellationSignal` | interface (read-only) | ai/.../concurrent/CancellationSignal.java | isCancelled()/throwIfCancelled() |

## CONVENTIONS

- Java 21：`record`、`sealed interface`、模式匹配、`Executors.newVirtualThreadPerTaskExecutor()`。
- 包名：`site.pplee.jcode.<module>`（模块名 `agent-core` → 包 `agentcore`，不带连字符）。
- 显式装配：provider/工具/hook 通过构造参数传入；禁止静态可变注册表、禁止 `ServiceLoader` 扫描、禁止 classpath 自动注册。
- 公开类型不可变；一次 run 的可变状态仅存在于 package-private `LoopState`（核心层唯一允许持有可变消息集合的类型）。
- 工具参数边界用 Jackson `JsonNode`；具体工具用共享 `ObjectMapper.treeToValue()` 转强类型。
- 外部依赖版本由根 POM `<properties>` 固定，不用版本范围。当前：Lombok 1.18.46 / Jackson 2.18.2 / JUnit 5.11.4。
- 测试纯 JUnit 5，无 Mockito；自定义测试双放 `support/` 包；`smoke/` 验证环境可运行；`AiModuleTest` 验证 `ai` 可独立编译且不依赖 `agent-core`。
- 类命名后缀：`*Config`/`*Loop`/`*State`/`*Result`/`*Source`/`*Sink`/`*Mode`/`*Request`/`*Client`/`*Spec`/`*Signal`。
- 注释规范：关键类、接口、方法必须使用精炼准确的注释，符合 Javadoc 规范且必须用英文书写。

## ANTI-PATTERNS (THIS PROJECT)

**模块边界（enforcer 强制）：**
- `ai` 不得依赖 `agent-core` 或任何 Jcode 模块。
- `agent-core` 仅可依赖 `ai`；禁止依赖 `coding-agent`/`ai-provider-*`/`server`/`tui`（前瞻 guard）。
- 依赖图必须无环，只能产品层 → 内核层。
- `message`/`event`/`tool`/`queue`/`concurrent` 是源码包，不是 Maven 模块；禁止拆成独立模块。
- 禁止 `common`/`shared` 杂物模块。
- 不长期维护两套 `Message`/`Content`/`StopReason` 事实来源。

**并发与取消：**
- 每个 `Agent` 同时最多一个 active run；并发 `prompt()`/`continueRun()` fail fast。
- `CancellationSignal` 只读；不得 cast 回 `CancellationSource` 调 `cancel()`。
- `cancel()` 幂等。
- 不阻塞轮询、不用 `Thread.sleep()`、不用长期共享无界平台线程池。
- `AgentEventSink.emit()` 返回的 stage 必须被等待；慢 sink 阻塞 run。

**工具执行：**
- 工具失败/参数转换失败/未知工具 → 转 error `ToolExecutionResult`，不抛进 loop。
- `LENGTH` 响应中的 tool call 全部转 failure，禁止执行。
- 任一工具声明 `SEQUENTIAL`，整批按原顺序执行。
- 并行结果必须恢复为原 tool-call 顺序后写回上下文。

**ModelClient SPI：**
- adapter 不得同步抛异常；provider/网络失败编码进 failed `CompletionStage`。

**Must-NOT-Have（首批实现，见 docs/plans §Must-NOT-Have）：**
- 无 Spring Boot/Guice/DI 容器。
- 无 session 文件/数据库/缓存。
- 无自动重试/compaction/prompt template/extension/plugin。
- 不用 `Map<String,Object>` 表示工具参数。
- 不在 `AgentLoop` 读 API key 或环境变量。

**注释与文档纪律：**
- 代码（类/接口/方法注释）、`pom.xml`、配置等**非文档产物**中**不得显式提到 pi**（如 "mirrors pi's…"、"pi-book"、"pi-ai"、`docs/architecture/pi-inspired-*.md` 路径引用等）；对 pi 的参考、对比、链接仅允许出现在 `docs/` 下。
- 产品代码自洽描述设计意图，不依赖对外部项目的指向性引用。

## COMMANDS

```bash
# 构建全模块（ai -> agent-core reactor 顺序）
mvn clean install

# 测试全模块
mvn test

# 单模块测试（agent-core 会自动拉 ai）
mvn -pl agent-core -am test

# 单模块测试（ai 独立）
mvn -pl ai test

# 根 reactor 验证（enforcer + 全测试）
mvn verify
```

## NOTES

- 根目录 `C:/` 目录是 `.mvn/jvm.config` 中 `-Djava.io.tmpdir=C:/Temp/maven` 在非 Windows 上的副作用（创建了一个名为 `C:` 的目录）。非源码，可忽略。
- `docs/references/pi/` 是 pi 参考仓库副本，`.gitignore` 忽略；其中的 `AGENTS.md` 是 pi 项目的规则，**不是本仓库规则**。
- 无 Maven wrapper（`mvnw`）；用系统 `mvn`。
- 无 CI 配置（无 `.github/workflows`/`Jenkinsfile`）。
- jdtls（Java LSP）未安装；codegraph 未索引（`.codegraph/` 存在但未 `codegraph init`）。
- 未来模块规划（未创建）：`coding-agent`（产品内核）、`ai-provider-openai/anthropic/google`、`server`、`tui`。创建门槛见 `docs/architecture/pi-inspired-module-boundaries.md` §3.6。
- Wave 进度：Wave 0（模块 seam）已完成；Wave 1-5 见 `docs/architecture` §5。
