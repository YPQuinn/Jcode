# Coding Agent 第二阶段：本地编码工具集

> 状态：实施中；PR 1（工具集合、产品 policy、显式装配）已完成
>
> Jcode 基线：`849ae9a46dc730caed26773252d6da0810c43cef`（2026-09-18）
>
> 总路线图：[`coding-agent-development-roadmap.md`](coding-agent-development-roadmap.md)
>
> 前置阶段：[`archived/coding-agent-phase-1-headless-foundation.md`](archived/coding-agent-phase-1-headless-foundation.md)
>
> pi 源码：`60e7e76bd7ea25cad1dd6f3f1ce0d18814a42759`
>
> pi-book：`c01f38d5440f7b51cb1e545c2110c27b694fb0bf`
>
> 本文是本地实施设计，不替代 GitHub Issues。确认方案后再拆分 tracker issue；本次没有创建远端 issue。

## 1. 建议：先完成改代码的闭环，再补齐发现能力

第二阶段不应以“增加几个工具类”为完成标准，而应证明：调用方仍只使用 `CodingAgentSession`，模型可以检查文件、做精确修改、运行验证命令，再根据实际结果继续工作。

分成两个里程碑，但不减少总路线图约定的交付：

- **2A：最小编码闭环**——`read/write/edit/bash`、显式启用与授权、mutation 顺序执行、进程和输出生命周期。
- **2B：结构化发现**——`grep/find/ls`、ignore 规则、有界搜索结果，以及完整产品集成测试。

2A 可独立合并和试用；只有 2A、2B 都达到验收标准，才能宣称第二阶段完成。不要为了尽早凑齐七个工具，把超长输出、取消、文件提交和慢 sink 留到后续阶段。

### 1.1 推荐决策

1. 保持单一 `coding-agent` Maven 模块；内部依赖仍仅为 `ai` 和 `agent-core`。
2. 原有 Session 保持只读。新增显式 coding profile，而不是把旧构造器的工具集自动扩大为可写、可执行命令。
3. 工具授权通过产品层只读快照接口注入，内部适配已有 `BeforeToolCall`；不公开底层工具实例或 `AgentContext`。
4. `write/edit/bash` 声明 `SEQUENTIAL`，先复用现成的整批串行规则，不建立静态文件锁表或跨 Session 全局 mutation queue。
5. 文件修改采用“全部校验和规划成功 → 同目录临时文件 → 单次原子替换”；不支持原子替换时失败，不偷偷降级为先截断再写入。
6. `edit` 只容忍换行表示差异，不做 whitespace/Unicode/fuzzy fallback；未修改区域保持原字节。
7. 进程执行、输出采集和事件投递分离。慢 sink 可以阻塞 run 完成，但不能阻止 timeout/cancellation 终止进程。
8. 搜索复用显式配置的 ripgrep，不自动下载工具，不自行实现 Git ignore 引擎；`find` 和 `grep` 共用一个后端依赖。
9. 首批 shell 只声明经过验证的 POSIX Bash profile；PowerShell/Windows shell 后续交付，不借 `bash` 名称运行其他语言。
10. 本阶段只扩展真实工具说明，不顺带实现 AGENTS.md 发现、Session 持久化、配置发现或 UI。

## 2. 当前基线与参考依据

### 2.1 已有能力

第一阶段及其审查修复已在 `849ae9a` 提交：

- Session 独占 `Agent`，单 active run、队列接纳、abort/close 与产品阶段收尾已经明确。
- 产品 state/result/event 对 `JsonNode` 递归快照；完成事件不暴露 `LoopResult.context().tools()`。
- `read` 支持 UTF-8、JPEG/PNG/WEBP；文本完整行分页，默认 2000 行、50 KiB 正文。
- 工具路径参数保留 symlink/`..` 的平台解析语义；超长目标行超过剩余预算即停止扫描。
- 流式 sink 失败使 run 异常完成；异常退出会取消在途 provider，清理失败不覆盖原始失败。
- 工具 update 投递失败由 core 记录并在 settle 时传播，不应转成普通工具错误。
- 当前验证基线为 `mvn clean verify`：585 个测试，0 failure/error/skipped。

不能假定已经具备的能力：可配置工具集合、产品授权接口、工具资源宿主、写文件、进程执行、搜索、完整输出文件管理。

### 2.2 参考源码及有意差异

路径均相对于 `docs/references/pi/`：

| 参考位置 | 借鉴内容 | Jcode 决策 |
|---|---|---|
| `packages/coding-agent/src/core/tools/index.ts` | 工具集合与显式工厂 | Java enum + 显式构造；旧 Session 不自动扩权 |
| `.../tools/write.ts`、`edit.ts`、`edit-diff.ts` | 多 replacement、统一输入快照、BOM/换行处理 | 全部 edits 匹配原文件；有界读写；不照搬模糊匹配或整文件换行重写 |
| `.../tools/file-mutation-queue.ts` | mutation 必须等实际 I/O settle 才释放执行权 | 先使用已有整批 `SEQUENTIAL`，不复制全局 Map |
| `.../tools/bash.ts`、`output-accumulator.ts` | tail 输出、实时快照、timeout/cancel | Java 进程与事件泵分离；不默认落盘无限完整输出 |
| `.../tools/grep.ts`、`find.ts`、`ls.ts` | 结构化搜索、路径输出、结果上限 | 统一 ripgrep 后端；禁止自动安装；明确扫描与返回上限 |
| `packages/agent/src/agent-loop.ts` | 事件 sink 等待与工具管道 | 继续复用 Jcode core，不复制循环 |

同时参考 pi-book 第 19–23 章。源码事实优先于书中旧版本描述，Jcode 已稳定契约优先于两者。

一个已验证的搜索陷阱：本机 ripgrep 15.2.0 的正向 `--glob '*.java'` 会重新包含被 `.gitignore` 排除的同名文件。因此不能一边直接传正向 glob，一边承诺“glob 仅缩小 ignore-aware 结果”。本文采用枚举/搜索后过滤，具体见 §8。

## 3. 范围与模块边界

### 3.1 本阶段交付

- 七个内置工具的真实装配：`read/write/edit/bash/grep/find/ls`。
- 显式工具集合、Bash/search 配置、产品级工具授权 seam。
- 有界文件 mutation、精确多段 edit、可测试的进程执行组件。
- 三种输出策略：read head、bash tail、搜索 record 输出。
- 工具资源在 Session 中的构造、失败清理、关闭和所有权规则。
- 无网络 deterministic 测试、真实临时文件/进程测试与 fake-model 产品闭环。

### 3.2 不交付

- AGENTS.md/项目设置发现、project trust 持久化、凭证或 provider 装配。
- Session JSONL、历史树、compaction、skill、extension、自定义工具插件体系。
- CLI/TUI/Server/RPC、交互终端、PTY、后台任务/终端会话管理。
- 任意进程的安全沙箱、可靠捕获所有逃逸后代、跨进程文件事务。
- shell 自动探测、依赖自动下载、PowerShell 实现。
- 自动保存完整 shell 输出及其 artifact 存储 API。

### 3.3 代码组织建议

继续使用 `codingagent.tool`；内部协作组件可以放在其内部子包，不创建 `common/shared/storage` 模块。

```text
codingagent/
├── CodingAgentConfig / CodingAgentSession
├── tool/
│   ├── CodingTool / CodingToolConfig
│   ├── CodingToolPolicy / CodingToolRequest
│   ├── ReadTool / WriteTool / EditTool / BashTool / GrepTool / FindTool / LsTool
│   ├── *Arguments
│   └── internal/
│       ├── BuiltInTools / ToolResources
│       ├── FileMutationWriter / EditPlanner
│       ├── ProcessRunner / ProcessOutcome / ProcessOutputBuffer
│       └── SearchBackend / SearchGlob / BoundedRecordReader
└── prompt/SystemPromptBuilder
```

这里的内部类型名是职责建议，不要求预先创建所有文件。I/O 与进程 seam 首先为本模块测试服务；没有独立使用者时不提升为公共通用框架。

## 4. 工具装配、授权与兼容性

### 4.1 显式配置

建议给 `CodingAgentConfig` 增加一个末尾字段 `CodingToolConfig tools`，保留现有 12 参数构造器，委托到 `readOnly()`。新增 canonical constructor 中 `tools == null` 同样使用只读默认。

`CodingToolConfig` 的最小内容：

- `Set<CodingTool> enabledTools`，防御性复制；enum 与 wire tool name 一一对应。
- 可选 `BashConfig`：绝对 Bash executable、显式环境变量快照、默认和最大 timeout。
- 可选 `SearchConfig`：绝对 ripgrep executable、显式环境变量快照。
- `CodingToolPolicy`：运行时服务引用，默认允许已启用的工具；文档说明线程安全要求。

建议便捷 profile：

| Profile | 实际能力 |
|---|---|
| `readOnly()` | 仅 `read`，保持现有行为 |
| `coding(BashConfig)` | `read/write/edit/bash`，调用方显式授予本地副作用能力 |
| `codingWithSearch(BashConfig, SearchConfig)` | 全部七个工具 |
| 显式 enabledTools 集合 | 例如只启用 `read/grep/find/ls`，或者无 shell 的 `read/write/edit` |

装配规则：

- Session 生命周期内工具集合固定；本阶段不添加运行中增删工具 API。
- 启用 bash/search 却缺少配置，或者平台明确不受支持，构造时 fail fast；不能悄悄隐藏请求的工具。
- 缺失 executable、非绝对路径或不可执行文件在装配时诊断；实际启动失败仍按工具错误处理，不能假定检查后文件不会变化。
- 需要执行版本探测时，必须走同一个有界 runner，时间上限明确；不得在 config record 构造器中启动进程。
- `BashConfig`/`SearchConfig`/`CodingToolConfig.toString()` 不打印环境变量值、命令或策略内部状态。
- 不使用静态 registry、`ServiceLoader`、classpath 扫描。`BuiltInTools` 在 Session 创建时显式生成实例。
- prompt 中列出的工具与实际 `AgentContext.tools()` 完全一致；同一份工具列表派生 specs，不维护第二份能力描述表。

这里有意细化总路线图中的“默认编码工具”：它是**显式选择 coding profile 后的默认集合**，不是旧构造器的新默认权限。

### 4.2 为什么不直接暴露 `BeforeToolCall`

当前 core hook 会收到 `AgentTool<?>` 和 `AgentContext`；后者又包含可执行工具。直接加入产品 Config 会绕过第一阶段的工具实例隔离。

新增产品接口只接收：

```text
CodingToolRequest(toolCallId, toolName, preparedArguments, workingDirectory)
CodingToolPolicy.evaluate(request, cancellation) -> CompletionStage<Allow | Deny(reason)>
```

契约：

- `preparedArguments` 在构造及访问时 deep copy；修改观察快照不能改变实际执行参数。
- 不暴露 `AgentTool`、`AgentContext`、`LoopResult` 或内部 Agent；请求 `toString()` 不打印文件正文/命令。
- 在 core prepare 阶段适配 policy，顺序保持 `prepareArguments → schema → policy → treeToValue`。
- policy 只允许/拒绝，不改写参数。Allow 不是路径沙箱，不消除 symlink/TOCTOU。
- Deny 返回普通 error tool result，工具不执行；policy 抛出、failed stage、null stage/decision 按既有 before-hook 失败语义处理，不伪装成允许。
- policy stage 必须等待；取消感知由传入只读 signal 提供。永久不完成的 policy 可能阻塞 run，不能宣称 abort 会强制完成任意宿主回调。
- `bash` 的授权覆盖命令实际拥有的文件/网络能力；禁用 `write/edit` 但允许任意 bash，不构成只读执行环境。

### 4.3 并发与资源所有权

- `write/edit/bash` 为 `SEQUENTIAL`；只要批次包含一个，整批按模型源顺序执行。这直接保护同一批次的 `read → edit → bash` 依赖。
- `read/grep/find/ls` 可保持 `PARALLEL`；只读批次的事件双排序仍由 core 保证。
- 只承诺同一 Session 的执行顺序；不同 Session、直接调用工具实例、外部编辑器不受此规则保护。
- 不增加跨 Session 静态锁。冲突检测只是额外保护，不宣传外部写者也参与事务。
- `ToolResources` 归 Session 所有：runner、有限 scheduler、输出/发布 worker、进程登记、临时文件清理。用户注入的 model client/policy 仍由用户持有。
- Session 构造失败必须清理已创建资源；close 幂等，先禁止新 admission 并请求取消本地任务，实际等待/关闭不在 lifecycle lock 中进行。
- 执行器使用受进程并发额度约束的虚拟线程任务；不能借 `newVirtualThreadPerTaskExecutor()` 名义接受无限待处理输出任务。

## 5. 工具参数与统一边界

首批参数形状如下；省略参数的默认值由 `prepareArguments` 填入 request-local 副本，未知字段、显式 null、错误类型、越界值必须验证。沿用当前 `ReadTool` 对可选整数的严格规则，不依赖 Jackson 的宽松强制转换。

| 工具 | 参数 | 关键结果 |
|---|---|---|
| read | 现有 `path, offset?, limit?` | 保持当前完整行分页和图片契约 |
| write | `path, content` | 新建或完整替换；返回字节数和简短确认 |
| edit | `path, edits[{oldText,newText}]` | 全部基于原文件匹配，一次提交 |
| bash | `command, timeout?`，单位秒 | stdout/stderr tail、退出码或明确终止原因 |
| grep | `pattern, path?, glob?, ignoreCase?, literal?, limit?` | 相对路径、行号、有界行摘要 |
| find | `pattern, path?, limit?` | 相对搜索根的文件路径 |
| ls | `path?, limit?` | 稳定排序的直接子项，不递归 |

建议首批固定上限（不是向模型开放的可绕过参数）：

| 对象 | 默认 / 硬上限 |
|---|---|
| 返回正文 | 2000 行 / 50 KiB UTF-8；不改变 read 已有正文预算 |
| 状态与截断提示 | 另外最多 1 KiB、4 行；诊断也要截断，不回显完整参数 |
| 文件 mutation | 原文件、最终文件各最多 8 MiB；超限不写 |
| edit 请求 | 最多 100 个 replacement；所有 old/new 文本合计最多 8 MiB |
| bash command | 最多 64 KiB UTF-8 |
| bash timeout | 默认 120 秒、默认 profile 上限 600 秒；profile 可明确配置，首批硬上限 3600 秒；工具参数不能超过 profile 上限 |
| kill / pipe drain grace | terminate grace 1 秒，force 后 drain grace 1 秒；仅用于清理，不延长命令执行预算 |
| 单 Session 本地进程 | 最多 4 个；无空闲额度时返回可诊断 resource-busy，不建立无界等待队列 |
| bash 实时快照 | 每次最多正文预算，通常不高于 10 次/秒，最多一个在途 update 与一个待发布最新快照 |
| grep | 默认 100，最多 1000 个匹配行；行正文最多 500 Unicode code point |
| find / ls | 默认 1000，最多 2000 条；仍受总 line/byte 上限限制 |
| 搜索执行 | 默认最长 30 秒；最多处理 100000 个记录；ripgrep 单文件搜索上限 8 MiB |
| 结构化搜索输入流 | 每条 JSON/路径记录最多 64 KiB；增量解析，不用无界 readLine |

表中的正文/提示预算针对文本；`read` 图片仍沿用第一阶段的 20 MiB 原始文件上限，不受文本预算替代。

实现时必须给 path/pattern/glob 等标识参数配置有限长度并验证 NUL；grep/find 的 pattern 不允许为空。不能将超长原值完整拼进诊断。范围限值写入工具说明及测试；不得在代码中散落互不一致的 magic number。

写文件参数必须是 well-formed UTF-16；UTF-8 编解码使用 REPORT。不能用删除 unpaired surrogate 的 sanitizer 静默改变用户要求写入的源码。输入已经来自模型的巨大 JsonNode 不在工具内存预算承诺之内，但工具不能再做未校验的无限复制或编码。

### 5.1 不强行复用一种 truncation

- **read head**：完整物理行，nextOffset 可继续；超长首行失败，保留现有契约。
- **bash tail**：保留末尾信息；允许为了保留巨大单行的末尾而返回有标记的 partial first line，但不切断 UTF-8 code point。
- **search records**：按完整结果记录限量；路径不能切一半后看似可执行，内容摘要可以明确标记截断。

可以共享限值、UTF-8 计量、有限诊断 formatter；不要把现有 `OutputTruncator` 变成带大量 mode 开关的万能类。对 read 的必要改动仅限提示/错误的外层上限，不改变其分页正文。

## 6. `write/edit`：先规划，后提交

### 6.1 文件与路径规则

- 相对路径以 Session cwd 为基准；允许绝对路径和 `..`，这不是 sandbox。
- 路径解析不得提前 lexical normalize 掉 symlink 后的 `..`；复用第一阶段回归测试。
- `write` 可创建父目录；`edit` 要求文件已经存在。不读写 FIFO、设备、目录等非普通文件。
- 已存在的末级 symlink 指向普通文件时，修改其 referent，不能用 rename 把 symlink 本身替换掉；broken symlink 明确失败。
- 对新文件，先创建/解析实际父目录，再在该目录下生成临时文件；不能依靠字符串拼接猜测真实目标。
- 不支持对恶意并发 symlink 置换提供安全保证；路径/身份复查能缩小窗口，不能代替 OS 级约束。
- 文件取消在调用前后与分块边界检查；同步文件系统调用未必可中断。不能把本地普通文件测试外推为任意网络文件系统上的即时取消保证。

### 6.2 提交协议

`FileMutationWriter` 统一执行以下流程：

1. 检查取消，校验目标类型和文件大小；对既有文件记录身份/属性和必要的原字节快照。
2. `write` 编码新内容；`edit` 完成所有匹配、冲突检测和结果编码。任何验证错误都发生在目标提交之前。
3. 以 `CREATE_NEW` 在目标**同一实际目录**创建随机临时文件，有限分块写入；过程中检查取消。
4. 保留已有文件的 POSIX permission bits，特别是 executable bit；平台无此属性时使用明确的本地策略。属性设置失败不能静默忽略后提交。
5. 提交前复查已观察到的目标状态；`edit` 复读并比较原字节，观察到外部变更则报告 conflict，不自动重试。
6. 最后一次取消检查后，执行一次 `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`。这是提交点。
7. 未提交的临时文件在 finally 清理；清理失败不能覆盖原始失败，必要时附带有界诊断。

保证与非保证必须同时写入文档：

- 验证/预提交取消/写临时文件失败，不应修改既有目标内容；已创建的空父目录不承诺回滚。
- 不支持原子替换的文件系统直接失败，不做“先删旧文件再移动”或 truncate-and-write fallback。
- 原子可见性不等于掉电持久性，也不是 compare-and-swap；外部写者仍可能在最后复查与 move 间修改文件。
- 原子替换可能改变 inode/hardlink 关系；不承诺保留所有 owner/ACL/xattr/hardlink 语义。目标文件如需额外元数据保护，宿主应拒绝工具操作；第一批至少保留支持平台的 POSIX mode。
- move 已成功后再观察到取消，返回已提交事实，不将其伪装成“未写入”。不尝试覆盖回旧文件做补偿回滚。
- run/sink 失败不代表磁盘回滚。文件已提交而 `ToolCompleted` 投递失败时，调用方需通过 read 检查实际状态。

### 6.3 精确多段 edit

`EditPlanner` 为无 I/O 的纯组件，输入为有界原始文件和 replacements，输出 replacement ranges 与最终字节；失败时不产生任何部分写入。

匹配规则：

1. 严格 UTF-8 解码，保留起始 BOM；BOM 不参与 oldText 匹配，但默认原样写回。
2. 建立逻辑匹配视图：仅把 CRLF/CR 映射为 LF，同时保存逻辑 offset 到原始文本 offset 的映射。
3. oldText 做同样的换行映射；除此之外逐字精确比较，不 trim、不变更引号、不做 Unicode normalization 或 fuzzy fallback。
4. oldText 不允许为空；每段在**同一份原文件视图**中必须恰好出现一次。检测多匹配时必须包括自身重叠出现，例如 `aaa` 中的 `aa` 不是唯一匹配。
5. 全部 ranges 一次验证，拒绝重复、包含、交叠；相邻且不重叠的 edits 可以接受。不得用前一个 replacement 的结果寻找下一个 oldText。
6. 按原始位置拼接新文件，所有未修改字节原样保留。不要为了 edit 把整个文件的 CRLF 或混合换行重写一遍。
7. newText 的换行使用被替换区域的第一个换行风格；区域没有换行时使用文件第一个换行风格，无换行文件使用 LF。此规则写进 description，混合换行有专门测试。
8. 任意校验失败或结果超过 8 MiB，整个 edit 失败。结果字节与原文件完全一致时，返回 no-change，不触碰文件。

成功结果只需 replacement 数量、首个变更行和有界确认；首批不新增标准 tool result 的 `details` 字段，也不要求生成可能无界的完整 diff。可选人类预览必须使用相同输出预算。

## 7. `bash`：把进程生命周期作为第一等契约

### 7.1 命令构造和环境

- 仅显式 POSIX Bash profile；通过参数数组启动，例如 `<absolute-bash> --noprofile --norc -c <command>`。
- 不把 executable、cwd 或可执行程序的普通参数拼接进另一层 shell 命令；`command` 本身才是有意执行的 shell 程序。
- stdin 立即关闭，本阶段不支持交互、PTY 或等待用户输入。
- 子进程环境从显式配置的不可变 Map 生成；清空 `ProcessBuilder` 隐式继承的环境。调用方需要继承时自己显式传入所选变量。
- profile 拒绝影响隐式 shell 初始化的 `BASH_ENV/ENV/SHELLOPTS/BASHOPTS`，不自动读取用户初始化脚本或项目 `.env`。
- 命令和环境不进入框架日志、异常或配置 `toString()`；工具参数本来会出现在模型 transcript/事件中，不能因此承诺用户写在 command 中的秘密会被隐藏。
- shell 输出是调用方授权采集的数据，仍可能包含敏感内容；框架不承诺任意内容的自动脱敏。

### 7.2 Runner 的最小职责

进程控制使用 typed request/outcome，不依赖 Agent、模型消息或 UI。request 明确选择合并输出或独立 stdout/stderr：bash 使用合并输出，搜索协议使用独立管道。实现至少区分：

```text
admitted → starting → running → stopping → draining → settled
Outcome: exited(code) | timedOut | cancelled | startFailed | ioFailed | outputLimitReached
```

- 用单调时间计算 deadline；timeout 覆盖 spawn 及执行，不依赖可回拨 wall clock。
- 正常退出、deadline、取消之间由一个原子终止原因决定胜者；后来的事件不能把 timeout 改写为 exit 0，或者把已观察到的正常结束改写成取消。输出截断、drain 不完整和解析错误是独立状态：进程已正常退出，并不代表采集结果必然完整或成功。
- 执行前已取消不得 spawn；start 与取消竞争时，新得到的 process 必须立即登记并处理 pending stop，不能遗失。
- cancellation listener 只标记/通知控制器，不在 listener 内等待 exit、drain、sink 或做可能长时间阻塞的树扫描。
- 使用 `Process.onExit()`、显式 deadline scheduler 和完成信号；不用 `Thread.sleep()`/轮询 exitValue。
- 保留调用用户权限。不限制命令自身申请内存、磁盘或网络，因此“有界”主要指框架内存、输出和等待策略，不是 OS resource sandbox。

### 7.3 stdout/stderr 与慢 sink

BashTool 推荐默认将 stderr 合并到 stdout，再用一个连续解码器采集。这样提供两类输出，不承诺还原两个独立管道在应用内的精确写入先后，也不提供独立通道标签。这不是 ProcessRunner 对所有调用方的强制模式。

- 流式 UTF-8 decoder 保留跨 chunk 状态；命令输出的非法序列使用 U+FFFD 并在结果注明替换，不让错误字节永久阻塞进程。
- 采集器持续读取固定大小 chunk，写入有限 tail buffer；不得先收集完整输出再 truncate，不使用无界行读取。
- 超长单行也只能占有限内存。字符计数、总字节计数等统计溢出时饱和，不回绕。
- `ToolUpdateSink.update(Content.Text(snapshot))` 发送的是**当前 tail 快照**，不是可直接累加的 delta；以 toolCallId 区分，产品消费者应替换该工具的预览。
- 独立 publisher 最多一个在途 update；未接纳的快照可合并为最新值，不建立无限队列。已交给 sink 的 update 必须等待，不得取消其 stage 来伪造成功。
- publisher 在调用 `update()` 本身时就可能阻塞：当前 core 实现内部同步等待事件。不能只防 returned stage 而把调用放到 reader 或控制器线程上。
- sink 阻塞时，reader 和 timeout/cancel 仍工作；命令结束后仍要等待已接纳 update settle，才能完成工具。
- sink 失败先停止本地进程并清理资源，再传播原基础设施异常；不得 catch-all 后返回“命令执行失败”。保留 core update-sink 的权威异常。
- 永久不完成的用户 sink 仍可阻止 run 完成，这是 backpressure 契约；不能承诺此时 `abort()` 一定在固定时限内完成 run future。进程终止与 run future 完成必须分别测试。

首批不保存“无限完整输出文件”；截断结果明确说明只保留 tail。若用户需要完整日志，可以显式在 command 中重定向到自行管理的路径，该文件属于命令副作用，不是框架 artifact。

### 7.4 停止、drain 和关闭

1. 请求停止后立即禁止新的输出预览排队；保留已接纳 update 的结算义务。
2. 控制器抓取可观察 descendants，先请求终止后代和主进程；grace 到期后对仍活动的已知进程调用强制终止。
3. 继续消费已接收/管道内可取得输出；force 后超过 drain grace 仍无 EOF，关闭管道并标记 `outputIncomplete`，不能永久等待子进程持有的 fd。
4. 等待自己拥有的进程/reader/publisher 任务结算，再关闭 registration、timer、stream、进程登记和额度。
5. 正常 exit 0 但管道无法正常 drain 或发生 I/O 错误，不得冒充完整成功；结果必须可诊断。

`ProcessHandle.descendants()` 是快照而不是可靠的 OS 进程组隔离。普通前台命令树的取消是必须测试的能力；双重 fork、提前 reparent、自行脱离的后台进程不在可靠回收承诺内。不能写“保证杀死所有后代”。后台任务管理和原生进程组/Job Object 是后续独立需求。

关闭时要有有限的本地资源清理等待；不把可能永久等待用户 sink 的 executor 放进无条件 `ExecutorService.close()`。若 sink 永久挂起，既不能丢弃已接纳事件，也不能谎称全部 worker 已释放，应明确保留这一宿主契约限制。

### 7.5 工具结果

- exit 0：success，附 tail；没有输出时使用明确占位。
- exit 非 0：error，附退出码与 tail。
- timeout/cancel：分别使用可诊断原因，保留已有 tail；不使用相同的模糊 `execution failed`。
- start/I/O/资源额度失败：error，安全且有界的诊断。
- tail 截断本身不把成功命令变成失败；drain 不完整则另行标记为错误。
- 状态存于工具内部 `ProcessOutcome`，向模型仍输出标准 `ToolExecutionResult`/`Message.ToolResultMessage`，不复制另一套工具协议。

## 8. `grep/find/ls`：搜索语义比调用命令更重要

### 8.1 后端选择

首选一个显式 ripgrep 后端，替代同时引入 rg/fd 或自己编写 ignore 引擎：

- 建议最低 ripgrep 14，能力检查覆盖 JSON、NUL 输出和必要选项；真实 smoke 标明实际版本。
- `grep` 使用 `--json`；`find` 使用 `--files --null`，共享进程控制和记录解析基础设施。stdout 走结构化 parser，stderr 独立并发 drain 到有界诊断 buffer；不能沿用 bash 的 stderr merge 污染 JSON/NUL 协议。
- 直接使用 executable + argv，**不经过 bash**。pattern 使用独立 `-e` 参数，搜索根使用 `--` 分隔，测试以 `-` 开头的输入和 shell 元字符。
- 使用 `--no-config --no-ignore-global`，固定忽略用户配置和全局 ignore；遵循后端的仓库/父目录 `.gitignore/.ignore/.rgignore` 与 `.git/info/exclude` 规则，并在说明中界定这些规则来自本机文件而非项目执行授权。
- 默认不递归跟随目录 symlink、不自动启用 hidden；本阶段不提供 `noIgnore/hidden/follow` 开关。
- 使用有限线程数、单文件大小过滤、执行 deadline 和总记录扫描上限。不能把外部工具的无限输出接入无界 queue。
- 未配置后端且未启用搜索工具时，read/write/edit/bash/ls 不受影响。已请求搜索却没有后端时显式失败，不宣传不存在的能力。

### 8.2 路径 glob

`glob` 只是进一步缩小 ignore-aware 候选集。首批使用一个小型、平台无关的 `SearchGlob`：

- 路径分隔符统一为 `/`；支持段内 `*`、`?`，以及占完整路径段的 `**`。
- 不含 `/` 的模式匹配 basename；含 `/` 的模式相对搜索根匹配。
- `src/**/*.java` 中 `**` 允许零个目录；大小写敏感。
- 首批不支持 brace expansion、字符组、extglob、反向 `!` 规则；遇到未支持语法明确报错，不悄悄按另一种语法解释。
- 模式长度、路径记录长度、匹配计算有界，禁止直接把不受信任模式拼成可灾难性回溯的正则。
- `find` 过滤 `rg --files` 的候选；`grep` 过滤 JSON match 的相对路径。不能直接用正向 `rg --glob` 重新打开 ignore 排除的文件。

### 8.3 grep

- 默认 Rust regex 语义，`literal=true` 使用 fixed string；不启用 PCRE2。无效正则、缺失路径、权限错误是 tool error；零匹配是正常成功。
- 默认 `path` 为 cwd；可以是普通文件或目录。目录结果相对搜索根；单文件结果用文件名，不能把搜索根路径偷偷加入 shell 插值。目录遍历遵循 ignore；调用方显式指定单个文件时沿用后端的显式文件语义，可搜索原本被 ignore 排除的文件，这与 glob 重新开启目录候选不同。
- 一条结果对应一个匹配的物理行，不按同一行内 occurrence 数量计数。
- 路径通过 JSON 字符串转义后显示，再附行号和摘要；不得把含冒号、换行的路径当作普通 `path:line` 无歧义协议。
- 全局匹配数、正文行数/字节数、单行摘要长度各自限制；rg 的 per-file `--max-count` 不等于全局 limit。
- JSON 以有界 record 增量读取。`--json` 不能依赖展示型 max-column 选项保证记录长度；超大记录应丢弃其余部分至分隔符，并明确标记有结果被省略，而不是分配整个巨大 JsonNode。
- 支持后端 JSON 的 text/bytes 形式；无法安全呈现的非 UTF-8 路径/正文不得用替换后的路径作为文件身份，可以省略并给有界诊断。
- 总 limit 命中后停止搜索并回收进程。主动停止是 `outputLimitReached`，不能因为自己发了 kill 而误报命令失败。
- 部分结果之后后端 exit 2/解析失败仍是 error，可保留有界已收集结果；exit 1 仅在正常完成且无其他错误时表示无匹配。

### 8.4 find

- 返回普通文件路径，不递归展开目录 symlink；目录枚举由 `ls` 负责。
- NUL 分隔读取，支持文件名中的空白和换行；每条输出 JSON 转义，相对搜索根。
- 达到结果或扫描上限后立即停止，不为了统计总文件数扫描完整仓库。
- 不承诺结果是全仓库字典序最小的 N 个文件，也不提供可恢复 offset；返回已收集的有界子集，说明截断并引导缩小 pattern/path。
- 空匹配正常成功；后端错误不能冒充空目录。

### 8.5 ls

- 使用 Java `DirectoryStream`，列出直接子项，包括隐藏项；不应用 Git ignore，也不递归跟随 symlink。
- 项目类型明确区分目录、文件、symlink；排序规则固定为目录优先、名称 code point 顺序，避免 locale 相关顺序。
- 限量收集可使用 top-N heap，内存随返回预算而不是目录总条目数增长；不使用 `Files.list(...).sorted().toList()` 收集整个目录。
- 未命中扫描上限时，完整枚举后返回稳定排序的前 N 项；命中扫描/时间上限时只保证已见子集有序，并明确输出不完整，不能声称得到了全目录前 N 项。
- 单个记录若无法完整放入输出预算，省略记录并报告原因，不返回看似有效的半条路径。

搜索工具共用记录扫描和输出预算，但不把 grep JSON、NUL paths、目录项强行解析成相同的通用 `Map<String,Object>`。

## 9. 测试计划：先验证边界，再做产品闭环

### 9.1 确定性测试矩阵

| 领域 | 必须覆盖 |
|---|---|
| 装配 | 旧构造器只提供 read；显式 profile；缺少 backend；工具与 prompt 同源；平台不支持时不广告 |
| policy | Allow/Deny/同步抛/failed/null；等待与取消；参数快照 mutation 不改变实际写入；不暴露 runtime 类型 |
| 参数 | unknown/null/type/range；UTF-16；空 oldText；巨大文件/参数；默认值只写到局部副本 |
| write | 创建父目录；覆盖；权限；超限；symlink/referent；每个预提交失败点；临时文件清理；原子移动不支持 |
| edit | LF/CRLF/CR/BOM/混合换行；零/多/重叠/包含/相邻匹配；匹配原文件而非逐次结果；no-change；外部冲突 |
| commit 边界 | move 前取消不改目标；move 后取消仍记录已提交；sink 失败不等于文件回滚；executable bit 保留 |
| runner | 启动失败；非零退出；提前取消；start/cancel/exit/deadline 竞争；清理异常不覆盖根因；所有 registration/timer/permit 释放 |
| 输出 | UTF-8 chunk 边界；非法字节；巨大单行；大量短行；正文/提示双预算；并发 stdout/stderr；丢弃旧快照 |
| backpressure | sink 挂起时 child 仍能退出或超时；释放 sink 前 run 不结算；update 抛出/failed stage；无迟到事件 |
| 搜索 | ignore 优先级；正向 glob 不重新包含 ignored file；shell/argv 注入字符；JSON/NUL 特殊路径；全局 limit 与每文件 limit |
| 搜索失败 | 超大记录；超时；exit 1/2；部分结果后错误；主动停止与异常 kill 区分；file-size filter 提示 |
| 并发 | readonly-only parallel；任一 mutation 使整批源序串行；跨 Session 无虚假锁保证 |

ProcessRunner 的测试 seam 应覆盖 launcher、process/pipe、deadline scheduler；使用可控 future/latch 和手动时间，不靠 `Thread.sleep()` 制造竞争。不得为测试在产品模块增加伪 main 或 Spring Boot 入口。

### 9.2 产品纵向测试

在临时 Git 仓库中使用脚本化 ModelClient，经唯一产品入口完成：

```text
prompt
  → ls/find/grep 定位文件
  → read 观察内容
  → edit 修改已有文件 + write 创建新文件
  → bash 执行本地验证脚本
  → 模型读取真实退出码和输出
  → final assistant
```

另外覆盖：

- 验证失败后，模型继续 edit 再执行；第二次成功来自实际文件和命令结果，而不是 stubbed tool result。
- 同一个模型响应依次包含 read/edit/bash 时，实际按源序执行；全部是只读工具时保持已有双排序契约。
- policy 拒绝、edit 冲突和缺失搜索路径都可恢复，不误当 run 基础设施失败。
- 进程执行中 abort、Session close，以及 tool update sink 失败后的资源回收和 Session 后续可用性。
- 修改产品事件/结果中的 arguments 不影响文件或下一 turn；新增工具不能破坏第一阶段 snapshot 契约。

### 9.3 真进程证据与环境门槛

- 普通 `mvn verify` 不联网、不依赖真实模型或 API Key。平台无 Bash/rg 时仅跳过显式标注的 native smoke，fake 组件测试仍必须运行。
- 阶段验收需额外执行一个严格 native smoke profile；缺依赖时 fail，不把 skipped 当作已验证。
- 建议 profile `local-tools-smoke`，通过 `-Djcode.test.bash=/absolute/path`、`-Djcode.test.rg=/absolute/path` 显式注入；至少在声明支持的 POSIX 平台上验证真实 timeout、前台子进程树、ignore 与完整 coding turn。
- macOS/Linux 支持证据分别记录，不把一次 macOS 成功外推为已验证全部平台；Windows shell 不属于本阶段声明。
- 本项目是库，无 Java 应用入口；沿用 Maven/JUnit。无需为 Gravity 或端到端测试创建伪应用入口。

## 10. 建议实施顺序

每个 PR 应在当前可用工具集合上保持 `mvn verify` 通过，不能先广告尚未实现的工具。profile 工厂随实际实现开放；内部类型按需创建。

| PR | 内容 | 完成门槛 |
|---|---|---|
| 1（已完成） | 工具集合/产品 policy/显式装配；旧 Config 兼容；prompt 同源 | 原有 585 测试不退化；read-only 无隐式扩权；policy 快照和失败测试 |
| 2 | 文件 I/O seam、原子 mutation writer、write、纯 EditPlanner 与 edit | 预提交失败不改原文件；多段匹配/BOM/换行/权限/取消/冲突矩阵；两工具 SEQUENTIAL |
| 3 | 有界 ProcessRunner、tail buffer、BashTool、Session 资源生命周期 | 不死锁的采集与 publisher；timeout/cancel/exit 竞争；native 前台树测试；2A 纵向闭环 |
| 4 | 有界 record formatter、SearchGlob、LsTool | 特殊路径；稳定排序与扫描截断的区别；不把完整目录装进内存 |
| 5 | 显式 ripgrep backend、GrepTool、FindTool | ignore/global limit/oversized record/exit 语义；真实 backend smoke；2B 工具齐备 |
| 6 | 全工具 fake-model Git 工作区闭环、故障矩阵收口、文档 | native 严格 profile、资源泄漏检查、完整阶段验收记录 |

PR 3 是最大风险点，应尽早用测试 seam 和少量本地真实进程验证“慢 sink 不阻碍超时/取消”。如果失败，先修 runner，不继续堆搜索工具。

预计生产改动集中于 `coding-agent`。`agent-core` 已具有需要的顺序/并行/hook/update-failure seam；只有确定性测试证实通用契约缺口时才做最小修复，不能为了 bash 把 Process 或路径逻辑塞进 core。`ai` 的消息/tool result 协议不因本阶段而扩张。

## 11. 完成门槛与交付记录

当前交付记录：PR 1 已增加 `CodingTool`、防御性 `CodingToolConfig`/外部进程配置、产品级 `CodingToolPolicy` 与快照请求，并由 Session 将 policy 适配到 core prepare hook。旧 12 参数 Config 和 `tools == null` 均保持 read-only；prompt 与模型请求从实际装配的同一工具列表生成。尚未开放 coding/search profile，也未实现 `write/edit/bash/grep/find/ls`。`mvn clean verify` 共 596 个测试通过，其中 `coding-agent` 56 个测试通过。

第二阶段完成必须同时满足：

1. 显式 coding profile 完成 read/edit/write/bash 的真实本地闭环；search profile 完成七工具闭环。
2. 旧构造器和 read-only 行为保持，写文件/命令权限没有悄悄扩大。
3. 一次 edit 的所有匹配基于原文件，失败不部分修改；符号链接、BOM、混合换行和元数据限制有证据。
4. 超大文件、单行输出、海量搜索结果均有有界内存测试；不得把无限输出转移到无限临时日志文件。
5. timeout、取消、正常退出、输出限额和基础设施失败可区分；慢 sink 不阻止进程控制，已接纳 update 仍受等待契约保护。
6. Session close/构造失败/执行失败不遗留自己拥有的普通前台进程、timer、reader 和临时 mutation 文件；无法回收的宿主回调/逃逸后代限制明示。
7. 新能力只能通过产品快照/标准 tool result 对外呈现，不泄漏内部 Agent、工具实例或可变参数引用。
8. `mvn clean verify` 以及严格 native smoke 均有命令、平台、依赖版本、测试数量和结果记录；不得用 skip 替代完成证据。
9. 根与相关模块 `AGENTS.md`、路线图同步实际实现；本计划完成后移入 `docs/plans/archived/`。

后续建议执行命令（native smoke profile 尚未落地）：

```bash
mvn -pl coding-agent -am test
mvn clean verify
mvn -pl coding-agent -am verify -Plocal-tools-smoke \
  -Djcode.test.bash=/absolute/path/to/bash \
  -Djcode.test.rg=/absolute/path/to/rg
```

## 12. 待确认取舍与后续工作

推荐按本文默认推进；以下是需要明确接受的范围取舍，不应留给实现者临时猜测：

- **兼容性**：旧构造器继续 read-only，coding profile 必须显式选择。
- **平台**：首批 Bash 以 POSIX 为范围；不在此阶段补 PowerShell 或后台任务平台。
- **搜索依赖**：需要宿主提供 ripgrep，不自动安装；本阶段不交付无外部依赖的 ignore-aware fallback。
- **文件提交**：原子移动不可用就失败；POSIX mode 之外不承诺完整元数据/硬链接保持，也不承诺跨进程 CAS。
- **编辑体验**：仅换行表示兼容的精确匹配；不做 fuzzy edit，不返回无限 diff。
- **输出保存**：默认只保留有界 tail；完整日志由显式命令重定向管理，artifact API 留待真实需求。

这些取舍控制第二阶段规模，同时把最重要的可靠性工作放在阶段内。第三阶段再处理项目上下文发现与系统提示装配，不把它混进工具实现。
