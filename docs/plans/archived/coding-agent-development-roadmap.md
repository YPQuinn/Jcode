# Coding Agent 总开发计划

> 状态：阶段一至阶段七已完成并归档
>
> Jcode 基线：`849ae9a46dc730caed26773252d6da0810c43cef`（2026-09-18）
>
> 功能收口基线：`8c10108e0878053b028aeb5ea268af7c2498b4fa`（2026-09-23）
>
> 归档路径：`docs/plans/archived/coding-agent-development-roadmap.md`
>
> pi 源码基线：`60e7e76bd7ea25cad1dd6f3f1ce0d18814a42759`（`@earendil-works/pi-coding-agent` 0.85.1，2026-09-16）
>
> pi-book 基线：`c01f38d5440f7b51cb1e545c2110c27b694fb0bf`（2026-07-27）
>
> 架构基线：[`pi-inspired-module-boundaries.md`](pi-inspired-module-boundaries.md)
>
> 第一阶段实施计划（已完成）：[`coding-agent-phase-1-headless-foundation.md`](coding-agent-phase-1-headless-foundation.md)
>
> 第二阶段实施计划（已完成）：[`coding-agent-phase-2-local-tools.md`](coding-agent-phase-2-local-tools.md)
>
> 第三阶段实施计划（已完成）：[`coding-agent-phase-3-project-context.md`](coding-agent-phase-3-project-context.md)
>
> 第四阶段实施计划（已完成）：[`coding-agent-phase-4-session-persistence.md`](coding-agent-phase-4-session-persistence.md)
>
> 第五阶段实施计划（已完成）：[`coding-agent-phase-5-settings-models-credentials.md`](coding-agent-phase-5-settings-models-credentials.md)
>
> 第六阶段实施计划（已完成）：[`coding-agent-phase-6-compaction.md`](coding-agent-phase-6-compaction.md)
>
> 第七阶段实施计划（已完成）：[`coding-agent-phase-7-resources-extensions.md`](coding-agent-phase-7-resources-extensions.md)
>
> 实施范围：阶段一至阶段七；不包含 TUI、Server 或其他产品入口

## 1. 决策摘要

Jcode 已通过第一阶段创建 `coding-agent`，把现有 provider-neutral 模型协议和通用 Agent Runtime 组装成可独立嵌入的编码产品内核。后续阶段继续补齐工具、上下文与产品能力。TUI、CLI、RPC 和 Server 都应消费该产品内核，而不是直接操作 `Agent`、具体 provider adapter 或内置工具。

目标依赖方向为：

```text
                    +------------------+
                    |  future TUI/CLI  |
                    +---------+--------+
                              |
                              v
                    +------------------+
                    |   coding-agent   |
                    +----+--------+----+
                         |        |
                         v        v
                  agent-core      ai-providers   # 阶段五按需接入
                         |        |
                         +---+----+
                             v
                             ai
```

阶段一至七严格按以下依赖顺序推进：

```text
阶段一  Headless 产品闭环
  ↓
阶段二  编码工具集
  ↓
阶段三  项目上下文与 System Prompt
  ↓
阶段四  Session 树与 JSONL 持久化
  ↓
阶段五  配置、模型与凭证装配
  ↓
阶段六  Compaction
  ↓
阶段七  Resource 与 Extension
```

总体决策：

1. `coding-agent` 是 headless 产品内核，不包含终端渲染、按键处理或 UI 组件。
2. 产品层以 `CodingAgentSession` 为统一入口，后续入口不得绕过它直接持有 `Agent`。
3. 复用 `ai.Models`、`Agent`、`ContextTransformer`、`MessageProjector`、工具三阶段管道和生命周期事件，不复制 pi 的模型运行时或 Agent Loop。
4. 先交付纵向可运行切片，再逐步加入持久化、压缩和扩展；不一次移植 pi 的全部功能。
5. Java 实现采用不可变配置、显式构造和显式注册；不照搬动态 TypeScript loader、全局注册表或 classpath 自动扫描。
6. 每个阶段都必须有独立完成门槛；后续阶段不能以“稍后补测试”为前提破坏前序契约。
7. 真实 provider probe 是补充证据，不替代 deterministic 单元测试和 fake model 集成测试。

## 2. 基线与差距

### 2.1 已具备的内核能力

`ai` 已提供：

- `Model` / `ModelRef` 模型身份；
- 标准 `Message` / `Content` / `Usage` / `StopReason`；
- `ToolSpec` 与 constrained-sampling 声明；
- `ModelClient`、`Models`、`ModelProvider`；
- `AssistantMessageStream` 及流式事件；
- provider-neutral 取消和 replay state。

`ai-providers` 已提供：

- OpenAI Responses API 的真实 HTTP/SSE adapter；
- API Key、组织、项目、自定义 header 与 endpoint profile；
- reasoning replay、图片、工具、usage、cost、重试和取消；
- 失败、错误和取消的 terminal stream 归一化。

`agent-core` 已提供：

- 单 active run 的 `Agent`；
- turn / message / tool 生命周期；
- steering / follow-up；
- 工具 prepare / execute / finalize 管道；
- 顺序和并行工具执行；
- `BeforeToolCall` / `AfterToolCall`；
- `ContextTransformer` / `MessageProjector`；
- `PrepareNextTurn` / `ShouldStopAfterTurn`；
- 实时 `AgentState` 和确定的事件归约顺序。

### 2.2 产品层缺口

第一阶段已提供 Session 门面、工作目录、最小 coding prompt、`read` 工具和防御性产品事件/状态/结果；第二阶段已补齐显式授权的本地编码工具闭环；第三阶段已完成项目上下文发现；第四阶段已完成 append-only Session 树与 JSONL 持久化；第五阶段已完成显式设置、项目授信、默认模型、凭证、Provider 装配、恢复选择、idle 切换和原子保存；第六阶段已完成长会话 compaction、单次溢出恢复和分支摘要；第七阶段已完成 Skill、Prompt Template、Resource Loader、范围授信、显式 Java Extension、固定 custom 历史和文本资源 reload。

这些能力均保留在产品层，没有下沉到 `ai` 或 `agent-core`。后续产品入口可直接消费已闭合的 headless 内核。

## 3. 对标原则与有意差异

### 3.1 以职责和不变量为标准，不按文件逐个翻译

pi 的 `AgentSession` 同时编排模型、工具、持久化、compaction、资源和扩展。Jcode 应保留同样的产品级职责，但把实现拆成可阅读、可测试的小类型，避免形成数千行单类。

建议映射：

| pi coding-agent | Jcode 目标 |
|---|---|
| `AgentSession` | `CodingAgentSession` 产品门面 |
| `createAgentSession()` | 显式配置和 session factory |
| `SessionManager` | append-only Session tree、JSONL codec 与单写者文件所有权 |
| `ModelRuntime` | 优先复用 `ai.Models`；产品层只补配置和选择策略 |
| `buildSystemPrompt()` | 纯 `SystemPromptBuilder` |
| `ResourceLoader` | 有诊断结果的显式资源加载器 |
| `read/bash/edit/write/...` | `codingagent.tool` 中的强类型 `AgentTool` |
| `SettingsManager` | 不可变设置模型、分层 resolver、独立 secrets 来源 |
| Extension loader/runner | 显式注册的 Java Extension API 与 runner |
| interactive/RPC modes | 后续独立产品入口 |

### 3.2 不复制动态运行时机制

Java 首批明确不采用：

- 动态加载任意脚本作为 extension；
- `ServiceLoader` 或 classpath 扫描；
- 静态可变 provider/tool/extension registry；
- 未经显式装配的目录即代码执行；
- 用 `Map<String, Object>` 表示产品配置、工具参数或 Session entry。

资源发现可以扫描 markdown/config 文件，但所有可执行 Java 能力必须由 composition root 显式传入。

### 3.3 不伪装成 sandbox

coding-agent 默认在启动它的操作系统用户权限内运行。工作目录用于路径解析和项目上下文，不自动构成安全边界；绝对路径和 `..` 的语义必须明确，不能仅靠字符串前缀制造不可靠的“沙箱”。

阶段五在加载项目设置前交付最小 project trust，阶段七再扩展到资源和显式注册的 Extension；不能先读取不受信任的项目设置、再补信任检查。文件和 shell 工具仍需通过调用方可注入的 policy/hook 进行额外控制。现有 `BeforeToolCall` 是权限确认和策略拒绝的运行时 seam；project trust 不等价于文件系统 sandbox 或工具执行授权。

### 3.4 参考材料优先级

当 pi-book 与当前 pi 源码不一致时，优先级为：

1. Jcode 已稳定的公开契约与仓库规则；
2. 固定 commit 的 pi 源码；
3. pi-book 的设计解读；
4. 历史计划中的旧接口草案。

## 4. 目标产品结构

阶段七完成时，预计保持单一 Maven 模块：

```text
coding-agent/
├── pom.xml
└── src/main/java/site/pplee/jcode/codingagent/
    ├── CodingAgentSession.java
    ├── CodingAgentConfig.java
    ├── CodingAgentState.java
    ├── CodingAgentRunResult.java
    ├── event/
    ├── tool/
    ├── prompt/
    ├── context/
    ├── session/
    ├── config/
    ├── model/
    ├── compaction/
    ├── resource/
    └── extension/
```

这些目录是源码包，不是新的 Maven 模块。只有出现 coding-agent 之外的真实独立使用者时，才评估拆分 storage、tools 或 extension host。

### 4.1 `CodingAgentSession` 的职责

`CodingAgentSession` 负责：

- 维护工作目录和当前产品配置；
- 独占并编排底层 `Agent`；
- 接收 prompt、steering、follow-up 和 abort；
- 转换并发布产品事件；
- 连接 session persistence；
- 在空闲边界应用 model/thinking/tool/config 变更；
- 编排 compaction、资源 reload 和 extension 生命周期；
- 在关闭时释放 Agent、provider 和本地执行资源。

它不负责：

- TUI 渲染；
- HTTP/RPC transport；
- provider wire mapping；
- 重写 Agent Loop；
- 在 public API 中暴露可变内部状态。

### 4.2 事件边界

产品事件采用独立 `CodingAgentEvent`。普通运行事件封装底层 `AgentEvent` 的防御性快照；`AgentCompleted` 转为只携带产品结果的 `RunCompleted`，不透出 `LoopResult.context().tools()` 中的可执行工具引用。后续阶段可以增加：

- Session entry appended；
- Session metadata changed；
- Model/thinking changed；
- Compaction start/end；
- Resource diagnostics changed；
- Extension lifecycle；
- Queue snapshot。

事件只增加产品语义，不另建流式 assistant 或工具执行事实来源。底层事件已经归约进 `AgentState` 后，产品事件才能向外投递。`Content.ToolCall.arguments` 和流式 delta 中的 `JsonNode` 是可变对象：只复制集合或套一层 record 不构成隔离，事件、状态和结果必须统一采用第一阶段计划定义的递归快照与防御性访问策略。

### 4.3 状态边界

`CodingAgentState` 是从底层状态和产品状态派生的不可变快照，不成为第二套 transcript。权威事实分别保留在：

- run 接纳和收尾：Session 同步状态机；模型流与工具执行状态：`AgentState`；
- transcript：`AgentContext` / Session branch；
- 持久化历史：Session entries；
- 产品设置：resolved config；
- provider catalog：`Models`。

## 5. 跨阶段不变量

所有阶段必须持续满足：

1. `ai` 零 Jcode 内部依赖。
2. `agent-core` 唯一 Jcode 内部依赖仍是 `ai`。
3. 产品能力只能从 `coding-agent` 指向内核，不允许反向依赖。
4. `coding-agent` 不直接解析 OpenAI SSE、replay payload 或 provider wire 字段。
5. `CodingAgentSession` 同时最多一个 active run；并发 prompt fail fast。
6. partial assistant 不进入持久 transcript 或 Session 文件。
7. 工具失败进入 tool result，不异常击穿 loop；基础设施失败保持可观察。
8. event sink 的等待和失败语义必须文档化，不能在不同阶段静默改变。
9. public 配置结构不可变；state/result/event 的快照在构造和访问时隔离嵌套可变数据。显式注入的 client、sink 等运行时依赖有单独的所有权和线程安全约定，不伪称为深层不可变值。
10. 文件、进程、scheduler、provider 和 listener 都有明确关闭所有权。
11. API Key、header、session affinity 等敏感值不能进入日志、Session、事件或 `toString()`。
12. 不用无界共享平台线程池，不用阻塞轮询或 `Thread.sleep()`。
13. 代码中的 Javadoc 使用英文；非 docs 产物不得显式提及 pi。
14. 每个阶段更新根 `AGENTS.md` 和受影响模块的 `AGENTS.md`；完成的阶段计划移入 `docs/plans/archived/`。

## 6. 阶段一：Headless 产品闭环

详细方案及完成证据见 [`coding-agent-phase-1-headless-foundation.md`](coding-agent-phase-1-headless-foundation.md)。

### 6.1 目标

建立第一个无 UI、可嵌入、可以完成真实 coding turn 的产品切片：

```text
caller
  -> CodingAgentSession.prompt()
  -> Agent
  -> model requests read
  -> ReadTool
  -> tool result
  -> model final answer
  -> CodingAgentRunResult
```

### 6.2 交付

- `coding-agent` Maven 模块和按模块生效的依赖边界；
- `CodingAgentSession`、配置、状态、结果和产品事件；
- 最小 coding System Prompt；
- `read` 工具和按完整物理行分页的有界 head truncation；超长首行明确失败，不丢弃行尾后提示下一行；
- `read` 自行补齐现有 schema validator 未执行的数值范围和额外字段校验；首批图片仅支持 JPEG/PNG/WEBP；
- 工作目录解析；
- fake model 纵向集成测试；
- 默认关闭的可选 OpenAI live smoke 入口或明确的手工 probe 说明。

### 6.3 非目标

- 写文件和执行命令；
- AGENTS.md 自动发现；
- Session 文件；
- 设置文件和凭证读取；
- compaction、skill、extension；
- TUI/CLI/RPC。

### 6.4 阶段门槛

阶段一完成时必须证明：

1. 新模块不是空壳，能够读取真实临时工作区文件并完成两轮模型调用。
2. 调用方只通过 `CodingAgentSession` 驱动产品闭环。
3. module boundary 由 Maven Enforcer 验证，而不只是文档约定。
4. `mvn verify` 通过，且测试不访问公网。
5. 对外快照的参数树修改不会影响工具执行、transcript 或其他快照；完成事件不泄漏工具实例。
6. steering/follow-up 的接纳不承诺在当前 run 消费；正常收尾、失败、取消后的 Session 队列归属均有确定性测试，不把产品层锁误当成底层 drain 的原子边界。

### 6.5 完成状态（2026-09-18）

阶段一已完成并通过全仓验证。实际交付包括 module-local Enforcer 边界、`CodingAgentSession` 产品门面、防御性 state/result/event 快照、最小 coding prompt、严格有界 `read` 工具，以及覆盖文本、图片、失败恢复、取消、队列归属和事件顺序的 deterministic 测试。随后审查修复了 symlink/`..` 解析、超长行扫描和流式 sink 失败传播；`849ae9a` 基线的 `mvn clean verify` 共 585 个测试全部通过。初始交付证据见已归档计划，第二阶段以修复后的提交为起点。

## 7. 阶段二：编码工具集

阶段二已完成全部七个本地工具、显式 profile/policy、fake-model 工作区闭环及 native 验证。原始实施记录见 [`coding-agent-phase-2-local-tools.md`](coding-agent-phase-2-local-tools.md)；后续按参考行为收敛的当前契约见 [`local-tools-behavior-alignment.md`](local-tools-behavior-alignment.md)。后者替代原子 mutation、严格匹配、自研 glob 和固定 Bash 限制等原始选择。

### 7.1 目标

完成本地编码工作所需的受约束工具，而不是只向模型暴露万能 shell。

分为 2A（read/write/edit/bash 的读取—修改—执行验证闭环）与 2B（grep/find/ls 的结构化发现）。2A 可独立试用，但全部完成才达到第二阶段门槛。

### 7.2 必须交付

显式选择 coding profile 后的默认编码工具（旧 Config 构造器继续仅启用 read，不自动扩大权限）：

- `read`：阶段一能力继续作为统一读取入口；
- `write`：创建父目录并完整写入文件；
- `edit`：精确匹配优先、规范化回退，执行唯一且不重叠的 replacement；
- `bash`：执行命令、流式 stdout/stderr、支持 timeout 和 cancellation。

结构化搜索工具：

- `grep`：有界匹配数和单行长度，尊重 ignore 规则；
- `find`：有界文件匹配，返回相对搜索根的路径；
- `ls`：稳定排序、有界目录列表。

首批使用显式 POSIX Bash profile；PowerShell 作为后续适配，不得在不匹配的平台宣传不可执行的工具。搜索显式配置 rg 与 fd，复用进程 runner；分别保留原生 glob/ignore 语义，不维护自己的 glob 方言，不自动下载依赖。

### 7.3 工具共同约束

- 参数使用强类型 record 和 JSON Schema；
- 输出统一受 line/byte 上限保护；
- `write/edit/bash` 声明 `SEQUENTIAL`，复用 core 整批源序执行；不建立静态跨 Session 文件锁；
- 产品 policy 经 defensive snapshot 适配 `BeforeToolCall`，不泄漏底层工具/context；副作用工具显式启用；
- 文件修改使用原生直接写入，不做旧文件 CAS、权限复核、临时文件或强制原子替换；write 不读取旧文件，失败不承诺回滚；
- `edit` 精确优先，必要时在 NFKC/空白/引号规范化视图匹配，按受影响行回写；保留 BOM、未触及行，拒绝零匹配、多匹配及重叠 edit，仍对整文件编辑保留内存预算；
- `bash` 取消必须终止直接活动进程、尽力回收可观察的普通前台子进程，并有界 drain 已接收输出；不承诺回收逃逸后代；
- 输出采集与事件 publisher 分离：慢 sink 可阻塞 run 完成，但不得阻碍进程 timeout/cancel；不默认保存无限完整日志；
- Bash 环境可继承或显式替换，timeout 可缺省；调用方可显式设置默认和上限，不设 3600 秒政策限制或初始化变量禁令。超时和用户取消产生不同的可诊断结果；
- 默认实现只使用调用用户权限，不声称提供 sandbox；
- I/O seam 应允许 deterministic test double，但不提前创建独立 storage 模块。

### 7.4 阶段门槛

- 默认 `read/bash/edit/write` 组成一个完整 coding turn；
- 工具 schema、边界值、截断、换行、并发、取消和错误均有测试；
- 对临时 Git 仓库完成一次“定位—读取—修改—测试”的 fake-model 集成流程；
- `mvn clean verify` 与显式注入 Bash/rg/fd 的严格 native smoke 均通过；不以 skip 代替平台支持证据；
- 框架输出缓冲、事件排队和搜索记录处理无界增长被测试阻止；不把此保证夸大为任意子进程的 OS 内存沙箱。

### 7.5 原始交付记录（2026-09-19）

以下为当时的实现与验收记录，不代表后续对齐后的当前契约；更新见 §7.6。

阶段二 PR 1～6 已完成：`CodingAgentConfig` 增加末尾工具配置且保留旧构造的 read-only 行为；Session 仅装配显式启用且已实现的工具；产品 policy 接收参数深拷贝并在 core prepare 阶段执行；prompt 与模型请求共享实际工具列表。`write`/`edit` 使用强类型参数、严格边界校验和 `SEQUENTIAL` 模式，统一经 8 MiB 有界 reader/writer、同目录临时文件和单次原子替换提交；`edit` 基于同一原始视图规划，保留 BOM、换行风格、未触及文本和已有 POSIX 权限。`bash` 使用绝对 executable、无 profile 启动和完全替换的环境快照；有界 runner 最多运行 4 个进程，持续 drain 合并输出并保留 2000 行/50 KiB tail，timeout/cancel 终止进程树，Session close 回收活跃进程。更新发布与采集解耦，最多一个 update 在途且 sink 失败不归一为工具错误。`ls` 使用 `DirectoryStream` 与有界 top-N heap；`grep/find` 共享经 capability probe 的显式 ripgrep 14+ backend和每 Session 进程预算，以有界 NDJSON/NUL parser 处理结构化结果。

最终 fake-model Git 工作区测试通过唯一产品 Session 使用全部七个工具，验证真实定位、读取、修改、命令失败、再次修改和命令成功，并确认进程退出及 mutation 临时文件清理；同一 Session 的 policy 拒绝、edit 冲突和搜索失败均可恢复。`local-tools-smoke` profile 要求显式绝对 Bash/ripgrep 路径并禁止以 assumption skip 代替严格证据。Darwin arm64、Java 21.0.10 LTS、Maven 3.9.14、GNU Bash 3.2.57、ripgrep 15.2.0 环境下，严格 native verify 与全仓 `mvn clean verify` 均通过；审查修复后全仓共 721 个测试，其中 `coding-agent` 181 个，0 failure/error/skipped。进程/sink 生命周期回归连续三次复验通过。阶段二计划已归档。

PR 2～6 审查发现的五项问题均已修复并补齐回归：进程终止保留已观察后代句柄，等待整棵已观察树退出且保留有界强杀 deadline；sink 失败立即通过工具自有取消源停止进程，清理后传播原异常；显式 grep 文件预检 8 MiB 上限并添加 `./` 防止 `-` 被当 stdin；glob 正确处理文件名中的字面星号。

### 7.6 行为对齐收口（2026-09-20）

- 移除文件事务与自研 glob，实现原生直接写入、hardlink 保持、write 大文件覆盖、edit 规范化匹配及有效错误诊断。
- grep 使用 rg `--glob --hidden`，显式 glob 可覆盖 ignore；find 使用 fd 原生 glob 并遵守 ignore，Git 边界与无仓库根场景分别测试。取消 rg 14+ 和 help 文本握手、单文件 8 MiB 搜索上限。
- `SearchConfig(rg, fd, environment)` 支持独立 binary；旧二参数构造用于 grep-only，启用 find 时必须补充 fd。Bash 环境/timeout 可缺省，显式旧配置仍受支持。
- 保留 core 调度、policy、进程树清理、背压和有界输出；不新增完整输出文件或通用存储机制。当前 Java 特有边界与未覆盖差异详见[对齐记录](local-tools-behavior-alignment.md)。
- 全仓 `mvn clean verify -Djcode.test.fd=...`：772 tests（本模块 230），0 failure/error/skipped；显式 Bash/rg/fd 的严格 native smoke 通过。第一批 10 个对齐回归先在旧实现失败，再随调整通过；旧事务和禁令测试改为当前行为契约。

## 8. 阶段三：项目上下文与 System Prompt

### 8.1 目标

让模型在每次 Session 建立时获得与工作目录一致、来源可诊断的项目约束。

### 8.2 必须交付

- `SystemPromptBuilder` 扩展为结构化装配管道；
- 工作目录向祖先目录的 context file 发现；
- 同目录仅按 `AGENTS.override.md` → `AGENTS.md` → `AGENTS.MD` 选择一个候选，不探测或回退到 `CLAUDE.md` / `CLAUDE.MD`；
- 全局 instructions 与项目 instructions；
- 父级到子级的稳定顺序、同目录候选优先级和去重；
- BOM、symlink、Git worktree 与重复物理文件处理；
- `<project_context>` / `<project_instructions>` 边界；
- prompt append 与 custom prompt 的清晰替换/追加语义；
- 资源读取警告和来源路径诊断；
- 显式启用、初次加载、不可变快照查询和 idle 原子 reload；
- 聚合实际读取上限，以及整体加载失败时保留旧快照。

### 8.3 关键不变量

- Prompt 构建结果只由显式输入决定，builder 本身不做隐藏 I/O；
- discovery 和 builder 分离；
- 注入文本是数据，不获得 Java 代码执行能力；
- context 文件内容不作为 Session transcript message 持久化；
- 旧配置默认不新增项目文件发现，全局目录由宿主显式注入；
- 项目来源使用配置 working directory 的词法祖先链，默认至文件系统根，可显式设定发现上界；
- reload 只能在 idle 接纳边界一次替换 system prompt 与项目上下文快照，消息历史、队列和工具集合保持不变；
- 本阶段不加载 SYSTEM/settings/skill/extension，不引入文件监听或动态工具重载。

### 8.4 阶段门槛

- 嵌套工作目录、全局文件、候选覆盖、symlink 去重和加载失败均有测试；
- 最终 prompt 中每个来源只出现一次并保留可追踪路径；
- 不同工具选择生成一致且真实的工具说明；
- custom/append 与默认关闭模式保持原契约，源文件不进入消息 transcript；
- reload 的失败、取消、close 竞态，以及 core idle prompt 更新的原子接纳均有测试；
- 有界加载、文件系统回归与全仓/严格 native smoke 均有实际验证记录。

### 8.5 完成状态（2026-09-19，2026-09-20 纠偏）

第三阶段已完成，实施与后续纠偏记录见 [`coding-agent-phase-3-project-context.md`](coding-agent-phase-3-project-context.md)。`coding-agent` 提供显式 `ProjectContextConfig`、不可变来源/诊断/snapshot、纯 Prompt 装配，以及 Session 初始加载、快照查询和 idle reload；旧构造默认关闭发现，且关闭时不创建 reload executor。

发现顺序为显式全局来源后接配置 working directory 的词法祖先链。同目录依次尝试 `AGENTS.override.md`、`AGENTS.md`、`AGENTS.MD`，高优先级候选缺失、非普通文件、不可读或非法 UTF-8 时继续回退；不读取 CLAUDE/SYSTEM/settings 等旁路文件。保留普通文件筛选、严格 UTF-8/BOM、物理来源去重和 1 MiB 聚合实际读取上限，删除单文件、保留总量、来源数、祖先数、路径、诊断、渲染及 deadline 等重叠限制和多阶段文件稳定性契约。

Prompt 中的项目来源标签只用于标记边界：路径属性单独转义，指令正文逐字拼接，不再 XML 转义或按 XML 字符集拒绝。linked-worktree 识别是 best-effort，Git 元数据失败只记录诊断而不阻止普通来源加载；worktree 根来源成功后，只遮蔽主工作树中同候选文件名的来源。`WARN_AND_SKIP` 与 `FAIL` 保留可诊断差异，reload 的原子接纳、旧快照保留、历史/队列/工具/model/options 不变、取消/close 发布防护及观察 stage 隔离继续保持。

## 9. 阶段四：Session 树与 JSONL 持久化

### 9.1 目标

建立 append-only、可恢复、可分支的产品历史，不把数据库或 UI 引入核心路径。

第四阶段已完成：包内提供内存/文件共用的 Session 树、固定显式 JSON codec、独占 writer 锁、顺序短写处理和可诊断 EOF 尾片段恢复。产品 `CodingAgentSession` 提供默认内存历史、显式文件 create/open、真实 `MessageCompleted` 持久化、恢复诊断、`continueRun()`、idle-only branch/reset/name/label；`SessionFiles` 提供显式目录的 list/latest/cwd 过滤和逐文件坏文件诊断。基础设施失败后会按已接纳历史对齐运行时 transcript，close 不会提前释放在途 writer。

### 9.2 数据模型

Session 文件由一条 header 和多条 entry 组成：

```text
SessionHeader
SessionEntry(id, parentId, timestamp, type, payload)
```

首版 entry 固定为五种：

- standard message；
- model change；
- thinking-level change；
- session info；
- label。

Compaction/branch summary 由阶段六增加；typed custom/custom message 由阶段七增加，并由对应阶段定义模型投影语义。

### 9.3 必须交付

- 首版 schema version 和未知版本拒绝；出现真实旧版本后再增加实际 migration；
- 短 id 生成与冲突检测；
- append-only JSONL writer；
- trailing partial line 和损坏 entry 的确定处理；
- in-memory 与 file-backed Session；
- 当前 leaf、children、branch、tree 构建；
- 从指定 leaf 重建模型 context；
- Session 列表、最近 Session 和 cwd 过滤；
- 消息完成后持久化，partial/update 不落盘；
- 同一会话切换分支时，在 idle 边界使用通用消息替换更新底层 Agent；只有 create/open 新对象才新建 Agent。

### 9.4 持久化边界

- 标准消息使用显式 DTO/codec，不依赖任意 Java 类型反序列化；
- opaque replay state 可以随标准消息保存，但必须保持 redacted `toString()`；
- API Key、custom header、环境变量和完整配置快照不进入 Session；
- Session writer 失败必须可观察，不能假装持久化成功；
- 同一 Session 文件的并发写入策略必须显式决定并测试。

### 9.5 阶段门槛

- 新建、追加、恢复、分支、版本拒绝和损坏文件测试完整；
- 恢复后的模型请求 transcript 与保存前等价；
- 分支与恢复不改写原始消息顺序和关系；中断产生的未配对历史由 provider 请求投影层处理，不重放工具、不伪造工具结果；
- 正常追加保留既有完整 JSONL 行，EOF 截断有可诊断处理；不承诺断电 durability 或不合作外部写者的行为；
- 文件句柄执行单写者检测，不实现多写者协同。

## 10. 阶段五：配置、模型与凭证装配

### 10.1 目标

让 headless 产品可以从显式、分层、可诊断的配置中选择模型和 provider，同时严格隔离 secrets。

### 10.2 配置层

建议优先级从低到高：

```text
内建默认值
  -> 全局设置
  -> 项目设置（受 project trust 约束）
  -> 启动参数/SDK 显式配置
  -> 当前 Session 临时变更
```

字段级 merge 必须由强类型 resolver 完成；保存时只写本层拥有或被修改的字段。

最小 project trust 在本阶段交付，而不是依赖阶段七：

- 加载并应用项目设置前，先解析显式 SDK decision 或受保护的独立 trust store 中的 decision；项目设置不能给自身授信；
- decision 绑定规范化的项目身份；路径、symlink 去重与保存策略必须在阶段五详细计划中固定并测试；
- 无 decision 或显式拒绝时，不加载项目设置，返回安全诊断并沿用全局/显式配置；headless 不隐式弹出交互或默认为 trusted；
- 本阶段授权范围只覆盖项目设置，不自动授权未来的 Extension 或可执行资源；阶段七必须按资源风险重新判断。

### 10.3 必须交付

- typed settings、解析、校验和来源诊断；
- 项目设置的最小 trust gate、独立 decision 来源和未授权时的安全 fallback；
- 默认 provider/model/thinking/tool list；
- 独立 credential source，不把 key 放入普通 settings；
- OpenAI Provider 的产品级装配；
- `Models` catalog snapshot、auth check 和模型选择；
- Session 恢复时的 model fallback；
- idle 时 model/thinking 切换；
- session-only 与持久默认值的区分；
- 产品级 model profile，至少表达 context window 和 compaction 所需限制；
- 配置文件并发更新的 lock/merge 策略；
- `toString()`、错误和事件的 secret redaction。

### 10.4 有意边界

- `ai.Model` 继续表示 provider-neutral 身份，不因 compaction 需要立即塞入所有产品元数据；
- OpenAI-specific capability 留在 provider/config，不进入 Agent Loop；
- 暂不为尚不存在的 provider 设计 OAuth 通用框架；
- 不通过 URL 或 model id 猜测 endpoint profile 或能力。

### 10.5 阶段门槛

第五阶段已按归档详细计划完成；现行行为见 `coding-agent/AGENTS.md` 与 `docs/agents/runtime-contracts.md`。

- 显式 key、环境 source 和受保护文件 source 的优先级可测试；
- 无 decision、显式拒绝、显式允许和已保存 decision 均有测试；未授权项目设置不能改变 provider/endpoint、模型或启用工具，项目文件不能自授信；
- model 恢复失败提供安全 fallback 和清晰诊断；
- 修改 model/thinking 不破坏 transcript；
- 敏感值不出现在日志、异常、事件、Session 和测试快照。

## 11. 阶段六：Compaction

本阶段已完成；详细行为、范围与验收记录见 [`coding-agent-phase-6-compaction.md`](coding-agent-phase-6-compaction.md)。

### 11.1 目标

在不修改历史事实的前提下，为后续模型请求构造可持续的短 context，并把摘要决策写入 Session 树。

### 11.2 必须交付

- context usage 计算和估算 fallback；
- 手动 compaction；
- threshold-triggered compaction；
- overflow 后的单次恢复策略；
- 有效 cut point 选择；
- 最近消息保留预算；
- tool call/tool result 原子边界；
- file operation、错误、决策和待办信息的结构化 summary prompt；
- compaction entry 持久化；
- `ContextTransformer` 中的 request-local context 重建；
- compaction start/end/abort/failure 事件；
- compaction 独立取消，不误取消已经完成的普通 run。

### 11.3 不变量

- 原始 Session entries 不删除、不覆盖；
- summary 是新 entry，不是对历史 message 的原地改写；
- cut point 不能留下孤立 tool result 或未配对 tool call；
- compaction callback 失败不能发送未经裁剪且可能超窗的 fallback 请求；
- 同一阈值只触发一次有效 compaction，避免重入循环；
- token 预算使用产品级 model profile 和 provider usage，不硬编码 OpenAI 数值；
- 仅已分类的 context overflow 可触发单次恢复；普通 `ERROR`、鉴权/网络失败和未知失败不得触发该策略。

### 11.4 阶段门槛

- 手动、阈值、overflow、abort、summary failure 和恢复路径均有 deterministic 测试；
- compaction 前后可见最近上下文与历史摘要符合预期；
- Session 分支上的 compaction 只影响对应 branch 的 request view；
- overflow 分类为正、普通错误为负、未知分类和恢复后仍失败的路径均有测试；未知错误不自动重试。

### 11.5 前置依赖：overflow 分类

`ResponseMetadata.failureKind` 提供最小的 provider-neutral 分类，首个值为 `CONTEXT_OVERFLOW`。OpenAI adapter 只从结构化 `context_length_exceeded` code 映射该值；产品层消费分类并决定是否 compact，core 只提供窄的 `continueAfterFailure()` 入口。

## 12. 阶段七：Resource 与 Extension

本阶段已完成；详细行为、差异和验收记录见 [`coding-agent-phase-7-resources-extensions.md`](coding-agent-phase-7-resources-extensions.md)。

### 12.1 目标

在稳定 Session、配置和 compaction 基础上增加可发现资源与显式扩展点，而不破坏 Java 的装配和安全边界。

### 12.2 Resource Loader

统一加载：

- Skill；
- Prompt Template；
- System Prompt override/append；
- 项目 context。

本阶段不实现 UI theme，也不为尚无消费者的主题能力增加空资源类型；后续如有 TUI 的真实需求，应在独立产品入口计划中设计。

Loader 必须返回资源和 diagnostics，诊断至少区分：

- parse failure；
- invalid metadata；
- collision；
- duplicate physical path；
- unsupported resource；
- untrusted project resource；
- I/O failure。

### 12.3 Skill

- markdown + typed frontmatter；
- name/description/path/baseDir/source；
- 可控制是否允许模型自动发现；
- system prompt 只注入摘要，正文由 `read` 按需读取；
- 显式 `/skill:name` 展开与普通用户文本有明确边界；
- 名称冲突和 symlink 去重可诊断。

### 12.4 Prompt Template

- markdown/frontmatter 资源；
- 显式变量展开和缺失变量诊断；
- 不执行任意脚本；
- 展开结果在进入 `prompt()` 前固定，Session 保存实际用户输入或明确的 typed custom entry。

### 12.5 Extension

Java Extension 首批采用：

- 构造参数显式注册；
- typed lifecycle callbacks；
- 可声明工具、命令、context transform 和事件观察；
- runner 统一错误隔离和调用顺序；
- 不扫描 classpath，不使用 `ServiceLoader`；
- 不动态执行项目目录中的 jar/script；
- headless API 不假定 UI context。

### 12.6 Project trust

复用阶段五已有的 decision 来源、项目身份和默认拒绝规则，将授权范围扩展到资源类型，而不是在此阶段首次建立 trust：

- SDK 显式参数可为指定项目和资源范围提供 decision；
- 已保存 decision 只有在项目身份和授权范围匹配时才可复用；“允许项目设置”不自动升级为“允许执行型资源”；
- 无 UI 且无适用 decision 时默认拒绝执行型项目资源；
- trust decision 与普通 settings 分离，项目文件不能自授信；
- 单纯文本 context、影响配置的资源和可执行资源的风险级别必须区分；
- 即使项目已授信，也不允许动态加载项目 jar/script；显式 Java Extension 装配规则不变。

### 12.7 阶段门槛

- 资源来源、优先级、冲突、reload 和 diagnostics 有完整测试；
- extension 顺序、失败隔离和关闭生命周期确定；
- 未信任项目不能触发可执行资源；
- reload 在 idle 边界原子替换资源，不产生半更新 Agent。

## 13. 跨阶段测试策略

### 13.1 测试层次

每个阶段至少包含：

1. 纯单元测试：parser、resolver、codec、truncation、cut point 等；
2. 组件测试：工具、Session manager、resource loader；
3. fake model 集成测试：真实 `Agent` + deterministic `ModelClient`；
4. 临时文件系统测试：使用 JUnit `@TempDir`，不修改开发者工作区；
5. smoke test：验证 Java 21 环境、模块装配和最小 prompt；
6. 可选 live probe：只有凭证可用且用户明确同意时运行。

不使用 Mockito；测试双放入各模块 `support/` 包。

### 13.2 回归重点

- 单 active run；
- 事件顺序、backpressure 与嵌套可变参数隔离；
- 取消、run 收尾和 steering/follow-up 残留消息竞态；
- 工具 result 顺序；
- UTF-8、CRLF、symlink、空文件、大文件；
- JSONL partial write 和 migration；
- secrets redaction 与 project trust 默认拒绝、授权范围隔离；
- compaction tool-pair boundaries 与 overflow 分类正反例；
- reload 与 active run 互斥；
- close 的幂等和资源释放。

### 13.3 最低验证命令

每个阶段至少执行：

```bash
mvn -pl coding-agent -am test
mvn verify
```

阶段引入真实可执行入口后，本地启动和 smoke test 应遵守仓库的 Gravity CLI 规则；在此之前 library module 不伪造 `main` 或 Spring Boot 启动类。

## 14. 实施与 PR 纪律

1. 每个阶段先形成详细计划；阶段一使用本计划链接的独立文档。
2. 每个 PR 只承担一个可回滚的行为集合，不能同时跨越多个阶段的核心事实来源。
3. 每个 PR 必须先写失败测试，再做最小实现，再做结构整理。
4. 公共 API 变更必须列出兼容影响；早期未发布 API 也不得无说明地反复改名。
5. 需要调整 `ai` 或 `agent-core` 时，先证明产品层无法通过现有 seam 完成；不得为了 coding-agent 便利把产品逻辑下沉。
6. GitHub Issues 是权威 tracker；本地计划用于实施规范，不替代 issue 状态。
7. 阶段完成后更新根 `AGENTS.md` 和 `coding-agent/AGENTS.md`，并把对应阶段计划移入 `docs/plans/archived/`。
8. 本总计划只有阶段一至七全部完成后才归档；中途若架构决策改变，应先更新总计划再实施。

## 15. 风险与处置

### 15.1 `CodingAgentSession` 变成巨型类

风险：把 pi `AgentSession` 的全部职责集中翻译到一个 Java 文件。

处置：Session 只做状态机和编排；codec、prompt、tool、config、compaction、resource 和 extension 都由独立协作者承担。禁止以“仅 Session 使用”为理由把复杂算法写成私有长方法。

### 15.2 产品状态与 Agent transcript 双写漂移

风险：Session、AgentContext 和产品 state 各保存一套消息。

处置：active run 以 AgentContext 为准，持久历史以 Session entries 为准；切换 branch 时复用现有 idle 消息替换接口同步 transcript，不重建 Agent。`CodingAgentState` 只派生快照，不独立修改 transcript。

### 15.3 为 TUI 提前污染产品 API

风险：在 headless 阶段加入 dialog、keybinding、render component。

处置：产品 API 只表达 prompt、run、tool、session、model、compaction 和 resource 事件。确认框通过异步 policy seam 表达，UI 映射留给后续适配层。

### 15.4 文件和 shell 工具造成越权错觉

风险：工作目录被误解成安全 sandbox。

处置：明确 local trust boundary；如需限制，通过显式 path policy、tool allowlist 和 `BeforeToolCall` 注入，不通过脆弱路径字符串判断冒充 sandbox。

### 15.5 Session schema 过早冻结

风险：后续 compaction/extension 被迫塞入无类型 metadata。

处置：首版带 version，使用 sealed typed entries 和 opaque-but-typed custom entry envelope；不暴露任意反序列化类型。

### 15.6 Compaction 反向污染模型协议

风险：为了 context window 和摘要把产品字段加入 `ai.Model` 或 provider wire 类型。

处置：优先使用 `coding-agent` 的 model profile；只有出现多个非产品消费者时才评估下沉 provider-neutral metadata。

### 15.7 Extension 破坏显式装配

风险：Java 插件系统演变为 classpath 魔法和不可控代码执行。

处置：第一版只允许调用方显式提供 extension 实例；动态插件加载必须有独立使用者、安全模型和单独方案后才能进入范围。

## 16. Definition of Done

阶段一至七全部完成时，Jcode coding-agent 必须满足：

1. headless 调用方可以创建、恢复、驱动和关闭一个 coding session。
2. 内置工具提供读取、搜索、修改文件和执行可取消命令的能力，并由宿主按配置显式启用；兼容配置默认只启用 `read`。
3. System Prompt 能装配真实工具、工作目录和项目 instructions。
4. Session 以 versioned JSONL tree 持久化并支持分支。
5. 配置、模型、凭证和 secrets 具有明确来源及优先级。
6. 长会话可手动和自动 compact，原始历史保持 append-only。
7. Skill、Prompt Template、SYSTEM/APPEND 等文本资源可以加载、诊断和 reload；显式 Java Extension 实例及其固定工具、命令贡献在 Session 生命周期内不热替换。
8. 全部产品事件可被后续 TUI/CLI/RPC 消费，不需要直接接触底层 Agent。
9. 模块依赖、敏感值、取消、事件和资源关闭不变量有自动化测试。
10. `mvn verify` 通过，默认测试不访问公网、不要求真实凭证。

达到该门槛后，才开始制定 TUI 开发计划。

## 17. 最终收口记录

- 阶段一至阶段七的详细计划均已完成并归档；功能收口基线为 `8c10108e0878053b028aeb5ea268af7c2498b4fa`。
- 最终交付物是可嵌入的 Headless Coding Agent 内核，不代表复制 pi 的全部功能，也不包含 CLI、TUI、HTTP/RPC Server、Spring Boot 启动入口、动态插件装载、包管理或扩展代码热替换。
- 收口验证记录为 `mvn verify` 共 919 个测试，0 failure、0 error、0 skip；模块分布为 `ai` 71、`ai-providers` 269、`agent-core` 209、`coding-agent` 370。严格 native profile、Maven Enforcer 与 `git diff --check` 均通过。
- 验证使用 deterministic fake model、临时文件系统和本地原生工具；未执行真实 Provider 网络 probe，也不把长期生产运行验证表述为本计划已完成的门槛。
- 达到本路线图门槛后，CLI/TUI 等最终用户入口应以独立计划推进并继续通过 `CodingAgentSession` 消费内核。

## 18. 参考来源

### 18.1 pi 0.85.1 源码

- `packages/coding-agent/src/core/sdk.ts`
- `packages/coding-agent/src/core/agent-session.ts`
- `packages/coding-agent/src/core/session-manager.ts`
- `packages/coding-agent/src/core/system-prompt.ts`
- `packages/coding-agent/src/core/resource-loader.ts`
- `packages/coding-agent/src/core/settings-manager.ts`
- `packages/coding-agent/src/core/project-trust.ts`
- `packages/coding-agent/src/core/compaction/`
- `packages/coding-agent/src/core/extensions/`
- `packages/coding-agent/src/core/tools/`

### 18.2 pi-book

- 第 11 章：Session tree
- 第 12 章：Compaction
- 第 13 章：配置层
- 第 14 章：System Prompt
- 第 15～17 章：Extension、Skill、Resource Loader
- 第 18 章：Model Registry
- 第 19～23 章：工具原则及实现
- 第 26b 章：SDK
- 第 30～32 章：极简核心与边界

### 18.3 Jcode 基线

- `ai/src/main/java/site/pplee/jcode/ai/`
- `ai-providers/src/main/java/site/pplee/jcode/aiproviders/openai/`
- `agent-core/src/main/java/site/pplee/jcode/agentcore/`
- `docs/plans/archived/pi-inspired-module-boundaries.md`
- `AGENTS.md`
