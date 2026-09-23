# Coding Agent 第四阶段：Session 树与 JSONL 持久化

> 状态：已完成并归档（4A 内存模型、4B JSONL 文件闭环、4C 产品接入与失败一致性、4D 分支与会话发现均已交付）。
>
> 编制日期：2026-09-20。
>
> Jcode 实施基线：`8e3dff82cbbebe668a6f49f8d3d1e604a158a973`，第三阶段收口版本。
>
> pi 源码基线：`60e7e76bd7ea25cad1dd6f3f1ce0d18814a42759`，本阶段不跟随浮动 main 改变验收语义。
>
> pi-book 辅助参考：`0a8863b611504a47302931ad9adf1cab71a0b79f`，第 11 章；源码与现有 Jcode 契约优先于书籍解读。
>
> 归档路径：`docs/plans/archived/coding-agent-phase-4-session-persistence.md`。
>
> 总路线图：`docs/plans/archived/coding-agent-development-roadmap.md`。本计划第 15 节列出必须同步的范围修订；不能同时保留相互矛盾的验收要求。
>
> 本文保留阶段四的决策、验收范围与完成证据；实施切片不是已创建的 GitHub Issue 或 PR。

## 实施进度

- 2026-09-20：完成 4A。`agent-core.Agent` 增加 idle-only `replaceMessages()`；`coding-agent.session` 增加 Header、首版五种 Entry、短 ID 冲突重试、append-only 父链树、branch/resetLeaf、只读树快照和沿 leaf 的 context 投影。
- 2026-09-20：完成 4B。增加显式 version/type/role/content JSON codec、精确保留标准消息字段的 JSONL 格式、`SessionFile` create/open、句柄级独占 writer 锁、短写循环、物理行/字节位置诊断，以及只对 EOF 截断 JSON 或不完整 UTF-8 后缀生效的延迟恢复。完整末行无 LF 会在下一次追加前补分隔符；实际写失败会封锁当前 owner。
- 2026-09-20：完成 4C。`CodingAgentSession` 默认使用内存历史，并提供显式文件 `create/open`、`history()`、`sessionFile()`、恢复诊断与 `continueRun()`；仅从真实 `MessageCompleted` 接纳标准消息，按 writer、内存历史、宿主 sink 的顺序处理。宿主 sink 或 writer 失败后按已接纳历史对齐 Agent transcript；关闭在途运行时先保存 core 产生的真实 `ABORTED` 消息，再释放文件锁。open 保留调用方当前模型、工具与配置，并在下一条完成消息前按需记录当前 model/thinking 变化。
- 4C 产品测试覆盖默认内存、文件 create/close/open、锁所有权、当前配置优先、工具结果顺序、`ERROR`/`ABORTED`、sink/writer 失败、观察 Future 取消、close 和 EOF 恢复诊断。
- 2026-09-20：完成 4D。`CodingAgentSession` 增加与 run/reload/close 互斥的 idle-only branch/reset/name/label；branch/reset 保留 append-only 旧分支和 pending 队列。`SessionFiles` 增加显式目录的一层 list/latest/cwd 过滤、稳定活动时间排序和逐文件坏文件诊断。
- 4D 测试覆盖保存—关闭—恢复—继续—分支、旧分支保留、用户节点继续、根重置、pending steering 保留、在途元数据 append 的 close/锁所有权、未完成 tool call 只读恢复，以及会话列表排序、过滤、计数、恢复诊断和坏文件隔离。

## 1. 目标与范围

### 1.1 本阶段要完成的产品闭环

创建会话，进行多轮对话和工具调用，关闭后重新打开，以恢复的历史继续对话；也能选中历史节点产生新分支，同时保留原分支。

主线固定为：

```text
完成的标准消息
    → 带 id / parentId 的 SessionEntry
    → 内存历史；文件模式同时顺序追加 JSONL
    → 从选定节点恢复消息列表
    → 显式发起下一次模型运行
```

恢复的是会话历史与后续请求所需的数据，不是运行线程、工具执行现场、子进程或工作区文件。分支不撤销已经发生的文件修改，不自动再次执行历史工具，不生成分支摘要。

### 1.2 必须交付

| 能力 | 本阶段交付边界 |
|---|---|
| 会话历史 | 一份 Entry 序列及其索引，支持内存和文件模式，语义一致 |
| 保存与恢复 | Header、标准消息和本阶段元信息的显式编解码，关闭后重新打开 |
| 树与分支 | 当前节点、节点查询、children、分支路径、树查询、移动当前节点后追加新记录 |
| 产品接入 | 从真实 `MessageCompleted` 记录历史，不要求宿主自行监听并保存 |
| 继续运行 | 已有 `prompt()`；补充映射现有 core 能力的产品 `continueRun()` |
| 元信息 | 模型及 thinking 的历史记录、会话名称、节点标签 |
| 会话发现 | 显式目录内的 JSONL 列表、最近会话、按创建时 cwd 过滤 |
| 故障处理 | 追加失败可观察、残缺尾行可恢复、坏文件不被静默当成新文件 |

### 1.3 明确不交付

不实现数据库、远程存储、多写者合并、跨进程协作编辑、后台同步、事件总线、写入队列服务、WAL、事务恢复、工具重试、工作区回滚、历史文件导入导出平台或磁盘格式自动修复器。

第五阶段的模型发现、凭证装配、自动 fallback 和动态模型切换不提前实现。第六阶段的 Compaction 与分支摘要、第七阶段的 custom/extension 记录随各自功能再设计；本阶段不创建没有生产者和上下文语义的空壳 Entry。

保持 headless；不增加 CLI、TUI、HTTP/RPC Server 或为了展示而新增应用启动入口。

## 2. 对标与差异决策

### 2.1 判定顺序

每项能力先写明 pi 的源码行为，再说明如何使用 Jcode 已有类型和模块实现。仅在语言运行时、已有契约或本阶段明确的产品范围需要时引入差异。必要差异包括范围裁剪，但不能把个人偏好的严格策略称为 Java 的必然要求。

“有测试”“写进计划”不自动证明机制必要。没有真实消费者的 SPI、策略注册器、可配置安全等级和多维预算均不进入首版。

### 2.2 行为映射表

| 议题 | pi 如何做 | Jcode 如何映射 | 必要差异及理由 |
|---|---|---|---|
| 历史结构 | JSONL Header + Entry；`id/parentId` 形成树；当前 leaf 决定追加位置 [P1] | 相同数据关系；Java record / sealed 类型表达本阶段 Entry | 不将 TypeScript 联合类型翻译成任意 `Map<String,Object>` |
| 模型上下文 | 沿 leaf 的父链构建；摘要记录有专门投影 [P1] | `SessionContextBuilder` 纯函数按父链抽取标准消息 | 暂无摘要功能，因此不提前实现摘要投影 |
| 分支 | 移动 leaf，下一次追加成为其子节点；不删除旧记录 [P1] | idle 时移动当前节点，同时替换底层消息列表 | Java 用窄更新接口，不公开内部可变 state，也不重建整个 Agent |
| 文件模式 | 直接文件追加；首次落盘存在等待 assistant 的延迟逻辑 [P1] | 显式 `create(config, directory)` 创建文件并写 Header，随后按消息追加 | headless 的显式创建操作应直接报告文件创建失败；不复制交互产品延迟建档状态 |
| 内存模式 | 与文件模式共用 SessionManager 的树语义 [P1] | 共用 Manager；无文件对象时不执行文件 I/O | 不增加两套 Repository/Store 实现体系 |
| 短 Entry ID | 随机短 ID，加会话内碰撞检查 [P1] | 用 JDK UUID 派生短 ID并检查索引 | 不增加分布式 ID 服务；会话 ID 使用 JDK UUID，不引入 UUIDv7 依赖 |
| 消息保存顺序 | SessionManager 按消息追加；流式 delta 不是独立历史 Entry [P1] | 以 Jcode `MessageCompleted` 为唯一消息入口 [J2] | Jcode 的并行 `ToolCompleted` 是完成顺序，不能据此持久化 transcript |
| 未配对工具 | 请求转换层为缺失结果提供占位，不重跑工具 [P3] | 复用现有 `OpenAiTranscriptPlanner` 的请求局部配对与 replay 处理 [J4] | 不在 Session 层复制修复器，也不把占位输出保存成真实执行结果 |
| 格式版本 | 有真实 v1→v2→v3 迁移 [P1] | 首版定义 Jcode version 1，检查版本 | 无已发布旧格式，不虚构历史迁移；不承诺 pi 文件互通 |
| 残缺文件 | pi 的解析流程会跳过无法解析的行 [P1] | 仅容忍可确认的 EOF 截断尾片段；结构损坏报告位置 | Jcode 不能从缺失父链构造一份貌似完整的上下文；不顺带实现修复框架 |
| 写入所有权 | 所核对的 SessionManager 直接写文件，无多写者合并协议 [P1] | 一个文件一个写所有者，用文件句柄 `tryLock()` 检测跨进程冲突，并协调同 JVM 的同文件通道生命周期 | Java 关闭任一同文件通道可能释放进程已有锁；只增加窄的进程内 owner 注册，不引入租约、心跳、重试 |
| 配置与资源 | SessionContext 解析选定路径的历史 model/thinking [P1] | 恢复数据；运行仍使用调用方显式 config/client/tools | 产品配置归第五阶段；Session 文件不成为可执行配置来源 |
| 失败后的历史 | SessionManager 管理历史，运行时管理执行状态 [P1、P2] | 已接纳 Entry 为产品历史；运行异常后重新对齐运行时消息视图 | 当前 core 仅正常返回时发布长期 context，产品接入必须处理这一差异 [J3] |

### 2.3 保留与禁止的复杂度

保留：标准类型校验、正常文件错误处理、不可变公开快照、单 active operation 的接纳、原有取消与背压、一个文件的单写者检测。

不增加：文件正文过滤、敏感词扫描、Prompt XML 校验、路径 sandbox、反篡改签名、哈希链、多阶段 mtime/fileKey 检查、祖先层数/节点数/时间等组合预算、每条记录的强制 fsync 或事务式临时文件替换。

Session 文件可能包含用户主动提供的敏感内容。禁止把宿主配置中的凭证自动写入历史，不等于能够保证用户正文永远不含敏感信息；不能通过改写正文来制造这种保证。

## 3. 模块职责与事实来源

### 3.1 模块边界

主体位于 `coding-agent/session/`，不新增 Maven 模块。`coding-agent` 继续只依赖 `ai` 和 `agent-core`；文件格式不进入 `ai`，路径、Entry、版本、分支知识不进入 `agent-core`。

建议职责如下；小型值类型可以嵌套，下面不是要求每行都建立一个独立扩展接口。

| 组件 | 职责 |
|---|---|
| `SessionHeader` / `SessionEntry` | 固定首版数据结构与不可变访问 |
| `SessionManager` | 唯一长期持有内存 Entry 序列与索引，并管理当前节点、追加和查询；由产品门面独占 |
| `SessionCodec` | 本阶段类型与 JSON 之间的显式转换；消息子 codec 按文件可读性拆分 |
| `SessionFileReader` | 一次性读取、格式校验与尾部诊断；解析结果交给 Manager 后不保留历史副本 |
| `SessionFile` | 一个已打开 writer 的通道、Header、尾部位置、追加失败状态、锁与关闭，不长期复制 Entry/索引 |
| `SessionFileAccess` | 窄的进程内 writer reservation 与同文件临时读取生命周期协调；活动 owner 从 Manager 历史生成发现视图，不读取 writer 通道 |
| `SessionContextBuilder` | 指定节点的标准消息与历史模型/thinking 元信息投影 |
| `SessionSnapshot` / `SessionInfo` | 对外只读结果，不包含 Agent、工具实例、writer 或可执行回调 |
| `SessionFiles` | 显式目录内的只读列表、最近项与 cwd 过滤 |

不公开可供宿主绕开 `CodingAgentSession` 修改历史的 Manager。窄文件 I/O 测试替身可以是包内接口，不能因此设计通用存储 SPI。

### 3.2 避免第二份 transcript

已接纳的 SessionEntry 是产品历史。底层 Agent 的消息列表是当前运行使用的视图；索引、树和列表都是派生数据，不分别持久化。

JSONL 是文件模式的持久表示。文件完整追加成功、内存 Entry 接纳完成后，才向宿主报告该消息的完成。正在流式生成的 assistant、尚未成为 `MessageCompleted` 的工具进度、pending 队列不是已接纳历史。

公开快照沿用 `SnapshotMapper` 的可变 `JsonNode` 隔离原则。只在显式查询时构造需要的历史/树视图，不在每次 delta 或每条消息后自动深拷贝整棵树。

## 4. 首版文件与数据模型

### 4.1 Header

Header 独占首行，不是树节点：

| 字段 | 语义 |
|---|---|
| `type` | 固定为 `session` |
| `version` | Jcode 首版为 `1`；不是 pi version 1 |
| `id` | 会话 UUID |
| `timestamp` | 创建时间，使用 UTC ISO-8601 文本 |
| `cwd` | 创建时 `CodingAgentConfig` 已规范化的绝对工作目录 |

本阶段不实现跨文件 fork，因此不提前加入 `parentSession` 路由、快照引用或跨文件继承链。文件名由创建时间和生成的会话 ID 组成，不把任意用户名称当路径。

### 4.2 Entry 公共字段与类型

公共字段为 `type`、`id`、`parentId`、`timestamp`。`parentId=null` 表示根；ID 与 parentId 是记录关系，不是工具调用 ID。

| `type` | 专有字段 | 是否参与模型消息 |
|---|---|---|
| `message` | `message`：标准 User / Assistant / ToolResultMessage 的显式 DTO | 是，按原有顺序还原 |
| `model_change` | `model`：完整 ModelRef 的 provider/api/model 标识 | 否，只用于历史元信息 |
| `thinking_level_change` | `thinkingLevel`：ThinkingLevel 的稳定取值 | 否，只用于历史元信息 |
| `session_info` | `name`；null 表示清除名称 | 否 |
| `label` | `targetId`、`label`；null 表示清除标签 | 否 |

本阶段不提供任意对象 payload，也不为未实现的 compaction、branch_summary、custom 或 custom_message 预留可被误用的公开 append 方法。

`model_change` / `thinking_level_change` 记录当前真正用于运行的显式配置。在一轮运行第一个 `MessageCompleted` 入历史之前，比较该分支的历史取值与当前配置；仅缺失或不同时顺序追加对应元信息，再追加该消息。普通 open/查看/branch 不因为读取历史而写入配置记录。

这不是动态模型切换 API。历史取值与当前运行配置分别可查；恢复时不自动寻找 provider、不修改宿主凭证，也不宣称历史模型已经自动恢复。

### 4.3 消息往返必须保留的内容

User 保留完整内容与时间；Assistant 保留内容、stop reason、error message、usage、timestamp、sourceModel、ResponseMetadata；ToolResult 保留 toolCallId、toolName、内容、error 和 timestamp。`Content.Text`、`Thinking`、`ToolCall`、`Image` 全部覆盖，不能把多模态或工具消息降格成一段显示文本。[J5]

Text/Thinking 的 `ModelReplayState.format/payload` 原样保存为不透明数据，只有相应 provider adapter 解释。图片保留现有 mediaType/base64Data；不另建附件仓库。工具参数保留 JSON 数据类型与嵌套结构，不经 `toString()` 再猜测解析；JSON 浮点数直接读取为 `BigDecimal`，不能先经 `double` 丢失十进制精度。[J5、J6]

终结为 ERROR/ABORTED 的标准 assistant 是正式终结消息，应记录。它与 `MessageUpdated` 中尚未终结的 partial assistant 不同。是否把终结失败消息送入 provider 请求，继续由已有请求投影规则决定。

### 4.4 编解码与版本

使用现有 Jackson 能力和显式 type/role/content 分派，不使用 Java 类名、默认多态反序列化或任意类注册表。可以用 JsonNode 承载 JSON 语法树和既有工具参数；不能用它替代所有公开领域类型。

首版只接受明确的 Jcode version 1。缺失/不支持的版本、未知 Entry 类型或无法构成现有消息的字段值，应报告文件、行位置与原因；不能静默忽略一条可能改变模型上下文的记录。已知类型的额外字段可以忽略，既有文件字节不因读取而重写，但不承诺任意跨版本互通。

只做数据结构与已有消息契约校验，不检查自然语言是否“安全”，不转义或删改正文。异常保留底层 cause；普通诊断不主动拼接整条历史记录。`toString()` 不自动展开整段正文、图片或 replay payload，不引入新的日志脱敏框架。

Jcode 还没有已发布历史格式，本阶段没有真实 migration。交付版本检查与当前格式固定 fixture；以后出现实际旧版本再增加相应迁移函数。

## 5. 树、分支与元信息规则

### 5.1 追加与索引

每条新记录的 parentId 指向当前节点；追加成功后当前节点前移。内存序列保持文件追加顺序，`byId` 提供定位；不额外保存 children 文件或索引数据库。

新 ID 在当前会话索引内检查碰撞。读取文件时重复 ID、缺失父节点、指向自身或前向引用都报错；要求 parentId 指向此前已经存在的记录，自然避免父链循环，无需另建通用图验证器。允许多个 `parentId=null` 的根，以支持重置到会话起点再产生分支。

父链而非时间戳决定模型消息顺序。树的展示顺序采用时间戳排序，同值按追加顺序稳定排列；时间不作为事务序号或 ID 唯一性条件。

### 5.2 当前节点与重开

`branch(entryId)` 选中已经存在的记录；`resetLeaf()` 选中根之前。两者只移动内存当前节点并更新 Agent 消息，不追加“光标记录”。

重新打开时默认当前节点是最后一条有效 Entry。仅移动后关闭、没有追加任何 Entry，不保存这次浏览位置；需要指定位置时，调用方打开后显式 `branch(entryId)`。这是文档化语义，不增加 sidecar 文件。

移动到用户节点保留该用户消息，随后 `continueRun()` 可从其后生成回答。重新编辑某条用户输入，应先选中其父节点，再 `prompt(newText)`；不能把包含旧用户输入和替代输入的上下文称为“编辑同一条消息”。

### 5.3 名称与标签

名称和标签用追加 Entry 表示修改。名称按整个文件最新 `session_info` 解析，标签按 targetId 的最新 `label` 解析，不因分支浏览而倒退。标签目标必须已经存在；名称不是文件重命名操作。

这些 Entry 可以前移当前节点，但不进入模型消息列表。所有修改只在 idle 接纳，返回值表达已经完成的修改；不为它们新增通用产品事件流。

## 6. JSONL 读写与故障边界

### 6.1 创建、读取与追加

文件模式由调用方显式提供目录或现有文件路径。旧构造器仍为纯内存会话，不扫描用户主目录、不自动加载“最近会话”，不默认向 cwd 写文件。

新建生成唯一文件名，以 `CREATE_NEW` 创建并写 Header，不覆盖已有文件。open 要求已有的非空、有效会话文件；不存在或空文件不是隐式新建请求。读完整文件并验证成功后再发布产品 Session。

写入先把单条 Entry 编码为 UTF-8 JSON 加一个 LF，再在顺序通道上写完全部字节；必须正确处理短写，不能把一次 `write()` 返回等同于整行写完。[K1] 不在每条消息后重写整份历史，不增加后台 writer/executor 或自动批量 flush 服务。

读取按记录顺序进行，不先将整个文件读成一个巨大字符串再 split。使用现有 Jackson/JDK 的顺序处理，不复制第三阶段的多维预算，不将项目指令的 1 MiB 额度套到会话文件或图片上。标准库已经存在的约束应如实记录，不额外建立 `SessionBudgetManager`。

### 6.2 一文件一写者

文件句柄生命周期内持有独占 `tryLock()`；锁冲突或同 JVM 重叠锁应明确失败，不等待、不重试、不自动抢占。取得锁后读取当前文件，避免先读旧视图再取得所有权。

Java 原生文件锁与通道关闭按进程相关联：同 JVM 若为列表读取或重复 open 另开并关闭同文件通道，可能使原 owner 的锁失效；查询线程中断也可能关闭它正在读取的 `FileChannel`。因此所有产品内同文件通道经过窄的 `SessionFileAccess` 协调：writer 在开通道前 reservation，活动 owner 的发现查询从 Manager 已接纳历史生成内存视图，不在查询线程触碰 writer 通道；无 owner 的临时读取用同文件 reader slot 阻止 writer 开通道。registry 全局锁只保护登记、查找和状态切换，身份解析、摘要计算、文件 I/O、等待 source 与资源关闭均在锁外。该机制只保护本进程通道生命周期，跨进程排他仍由原生锁证明。

Manager 的线程内操作仍受产品接纳规则控制，不能用操作系统文件锁代替 Java 线程同步。文件锁只协调遵守相同约定的写者，不阻止外部编辑器或不遵守锁的程序修改文件，也不声称是安全隔离。[K1]

不支持所需文件锁的文件系统应给出明确错误，而不是静默降级成多写者。首版不增加可配置路径注册服务、关闭锁检测策略、锁文件、心跳或租约。

### 6.3 尾行与损坏文件

| 输入情况 | 读取结果 | 后续写入 |
|---|---|---|
| 正常 JSONL | 读取所有有效记录 | 从 EOF 追加 |
| 最后一条完整、合法 JSON 但没有 LF | 接受该记录 | 首次追加前补一个 LF，不能与新 JSON 粘连 |
| EOF 处明确截断的 JSON，或尾部 UTF-8 序列未完成 | 保留之前的完整记录，报告残缺尾部的行号/字节位置 | 取得写所有权后，在首次追加时截断这段未完成尾片段，再追加 |
| 带 LF 的坏行、文件中部坏行 | 失败，原文件不变 | 不允许继续追加 |
| 完整 JSON 但 Header/类型/父链/必要字段不合法 | 失败；即使在最后一行也不当作可丢弃尾片段 | 原文件不变 |
| 空文件、缺失 Header、未知版本 | 明确失败 | 不自动初始化或改写 |

空白行可忽略；新 writer 不产生空白行。EOF 截断处理不是“任何最后一行错误都可删除”。单纯 open/list 不修改文件；首次新写入对未完成尾片段的处理必须在诊断中可见。

完整、已接纳记录仍是 append-only；丢弃尚未形成有效记录的尾片段和补分隔符，是恢复追加边界，不是对已完成历史的修改。不加自动备份链、回滚文件或隔离目录。

### 6.4 追加失败

编码失败发生在写入前，应正常传播且不推进 Entry。实际写入抛 I/O 异常时，不能假定磁盘上零字节变化；当前 writer 标记不可继续追加，后续 prompt/continue/元信息写入在执行副作用前明确拒绝。宿主关闭并重新打开，由同一读取规则判断磁盘上的完整前缀与残缺尾部。

这里是一个 writer 失效标志，不是新的恢复状态机。禁止自动重试同一 Entry、自动退回内存模式或声称已经回滚；原始异常和清理异常应保留。失败后仍允许查询已接纳快照与关闭资源。

追加成功表示本次字节写入完成并接纳到内存，不默认表示断电持久化。首版不每行调用 `force()`；不能在文档中把普通 append、flush、close 写成每条消息断电不丢。[K1]

## 7. 接入 CodingAgentSession

### 7.1 已交付公开入口

本阶段最终交付以下入口：

```java
// Existing constructor: still in-memory, with no implicit session-file I/O.
new CodingAgentSession(config);

// New explicit file-backed factories.
CodingAgentSession.create(config, sessionDirectory);
CodingAgentSession.open(config, sessionFile);

// Existing run entry and a thin facade over Agent.continueRun().
session.prompt(text);
session.continueRun();

// Read-only snapshots, file metadata and idle-only history operations.
session.history();
session.sessionFile();
session.sessionDiagnostics();
session.branch(entryId);
session.resetLeaf();
session.setName(name);
session.setLabel(entryId, label);

// Explicit-directory discovery; malformed files are isolated as diagnostics.
SessionFiles.list(sessionDirectory);
SessionFiles.list(sessionDirectory, cwd);
SessionFiles.latest(sessionDirectory, cwd);
```

不向已有 `CodingAgentConfig` 继续追加目录、版本、重试和恢复策略参数。不允许宿主持有内部可变 Agent/Manager。SessionSnapshot 提供节点、children、branch 和 tree 的只读查询；不必在产品门面重复所有低层 getter。

create/open 和 SessionFiles 查询是显式文件操作，调用方可以从自己的工作线程调用；不为静态创建和每个查询另建专用线程池。名称/标签采用同步完成的 idle 操作；其接纳槽覆盖追加与内存接纳，确保 run、reload、close 不与该操作交错。

本阶段不提供“在同一产品对象中切到另一个文件”的宽接口。打开另一个文件使用新的产品对象；同一对象内只切当前会话的分支，避免顺带增加 cwd、工具及 provider 热切换。

### 7.2 唯一消息记录点

产品事件适配在 `MessageCompleted` 到达时先取标准消息的防御性快照，再按顺序追加所需配置元信息和该消息。文件追加成功后发布内存 Entry，最后向宿主投递对应 RuntimeEvent。

不从 `prompt(text)` 入口提前保存用户消息，因为 steering/follow-up 只有实际进入 transcript 才属于历史。不监听 `ToolCompleted` 落盘，因为并行工具完成顺序不等于 transcript 顺序。不在 `RunCompleted` 再保存同一批消息。[J2]

`MessageStarted`、`MessageUpdated`、ToolUpdate、ToolStarted、ToolCompleted、TurnCompleted 和 RunCompleted 本身都不是 Session message entry。工具输出只有成为标准 ToolResultMessage 并完成时才进入历史。

### 7.3 历史接纳顺序

```text
core 将终结消息归约到运行状态
    → 产品取得消息快照
    → 文件模式完成整条 JSONL 追加
    → 产品发布 Entry / 索引 / 当前节点
    → 向宿主投递 MessageCompleted 对应事件并等待背压
```

宿主在事件回调中查询历史，可以看到对应 Entry 已经接纳。宿主回调失败不撤销此前已保存事实。写入失败则不发布该 Entry 或伪装成功事件，使用现有基础设施失败通道结束 run。

这是明确的成功顺序，不是跨磁盘、内存、宿主回调的事务。进程若在磁盘写完而确认前退出，重开时可能读到调用方尚未观察到的完整记录；不引入 exactly-once 或两阶段提交承诺。

## 8. 运行失败、一致性与关闭

### 8.1 必须处理的现有差异

当前 `Agent` 仅在 loop 正常返回时发布 `result.context()`。若某些 MessageCompleted 已经入历史，后续宿主 sink 抛错，core 长期 context 可能仍停留在 run 前。[J3]

因此，产品 future 的失败收尾必须在 core 已结束、但 Session 尚未解除运行接纳时，从当前已接纳历史重建消息，并调用第 9 节的窄接口对齐 Agent。不能直接设置 `running=false` 就允许下一次 prompt 使用旧 context。

| 情况 | 已接纳历史 | 后续行为 |
|---|---|---|
| 正常完成 | 包含全部实际完成消息 | 原有成功结果；不重复追加 |
| 模型终结 ERROR/ABORTED | 包含实际完成的终结消息 | 保留原模型结果语义；请求投影按既有 provider 规则处理 |
| 宿主事件 sink 在若干已保存消息后失败 | 保留已接纳记录 | 等 core 真正结束后对齐消息视图，再异常完成产品 future；健康 writer 可接受下一次 run |
| 文件追加失败 | 保留此前已接纳前缀；磁盘尾部可能不确定 | 对齐已接纳内存视图，writer 拒绝新追加，异常完成，要求关闭重开 |
| 运行期间 close | 保留关闭过程中真实完成并已接纳的记录 | 禁止新操作；不再次更新已经关闭的 Agent；已有操作真实收尾后完成结果 |

产品层引入完成消息历史后，不再把“基础设施失败时丢弃整个 run 已完成消息”当成产品契约。Standalone `Agent` 的失败策略不改；需要更新的是新增历史能力对应的产品层测试和说明。

### 8.2 锁与操作接纳

继续复用 Session 现有生命周期接纳锁，不增加通用 OperationScheduler。prompt、continue、reload、branch/reset 与元信息修改互斥接纳，closed 后拒绝新操作。

磁盘读取、编码、写入、树路径构建和等待尽量在锁外完成；锁内只做接纳、复核、引用更新及短状态变更。构造候选视图后再原子提交，不能在锁内等待宿主 event sink、provider 或文件锁。

元信息写入被接纳后，占用同一操作槽直到写入与接纳完成；失败时释放槽位。这里允许一个小型私有状态/辅助方法收拢现有标志，不为未来资源热重载建立通用状态机框架。

`RunCompleted` 的事件回调不等于产品 future 已完成；沿用现有接纳语义。只有结果 stage 已真实完成后，完成回调才可以接纳下一次 run/分支操作。

### 8.3 取消与 close

文件追加是已接纳操作的一部分，不能靠取消返回 Future 假装写入已经结束。沿用只读 CancellationSignal、观察 Future 与真实操作隔离的约定。

close 先禁止新接纳，再取消/关闭现有运行及 reload，最后按真实在途操作的收尾释放 writer、文件锁和其他新增资源。即使既有等待窗口已经结束，也不能提前释放仍被正在写入的操作使用的 writer。

通过已有操作完成路径执行最终释放，不新增轮询线程、关闭重试器或延迟清理服务。不可中断的外部 I/O 可能延迟资源释放，应如实说明，不承诺任意文件系统硬超时。发生中断写入按 writer 失败处理，不补写假成功结果。

构造/open 失败释放本次已取得的文件锁与已创建资源；不删除或改写调用方已有的会话文件。不把 Session 不拥有的 ModelClient/provider 作为新增文件资源顺带关闭。

## 9. Agent 的最小通用更新接口

### 9.1 拟增加的接口

```java
void replaceMessages(List<AgentMessage> messages);
```

职责仅为 idle 时替换标准的运行时 transcript，更新 `AgentContext` 与公开 `AgentState` 对应消息视图。参数容器防御性复制；产品传入的嵌套 JSON 已经过 SnapshotMapper 隔离。core 不承担产品文件 codec 或来源诊断。

该接口与 prompt/continue/close 使用同一接纳边界，active run 或 closed 时拒绝。不执行模型、工具、transformer、hook 或用户 event sink，不合成 run 事件。

保留 system prompt、实际工具实例、model/client/request options 和 pending queues，不把“替换消息”扩展成任意 context/config 热替换。空闲状态的 streaming/pending-tool 字段保持正确；既有 error 字段的保留/清理语义在 Agent 测试中明确，不借消息替换重写此前失败结果。

### 9.2 分支提交

先计算目标节点的父链和标准消息列表；进入产品提交临界区后复核 Session 仍 idle/open，调用 core 窄接口并更新产品当前节点。失败不能只更新其中一半。

同会话 branch/reset 保留当前对象尚未消费的 steering/follow-up 队列，且不落盘；队列属于当前产品对象，不能因消息替换静默丢失。它们会在下一次被显式发起的运行中按原规则消费，这一行为必须在 API 文档中说明。新建/重新打开的对象没有旧对象的 pending 队列。[J7]

这延续已有 Jcode 队列所有权，不增加序列化队列、跨对象搬运或自动排空机制。

### 9.3 continueRun 的边界

产品 `continueRun()` 使用与 prompt 相同的接纳、事件、历史写入和失败收尾路径，调用既有 `Agent.continueRun()`，不复制 loop。

历史为空或最后一条标准消息为 assistant 时，沿用 core 的限制，不自动编造用户“继续”消息，不重跑该 assistant 的旧工具。恢复到用户/工具结果之后可以显式 continue；需要在完整 assistant 后开启新轮，由调用方使用 prompt。

## 10. 恢复、当前配置与工具副作用

### 10.1 恢复时构造什么

open 从文件重建 Manager 和选定分支消息，以调用方当前 config 装配 client、tools、policy、system prompt，再创建一个新的 Agent，初始 messages 使用恢复结果。

恢复后同一对象的 branch 不重建 Agent。项目指令在 open 时按第三阶段流程加载，之后分支移动不额外重新发现文件；显式 reload 才更新项目指令。

在消息数据、模型和相关请求输入相同时，恢复前后的模型请求历史应等价；不承诺项目文件、凭证、外部环境或 provider 输出在不同时间相同。

### 10.2 cwd 与模型

Header.cwd 表示创建位置，`config.workingDirectory()` 决定此次执行位置。不同情况下返回明确的 cwd 差异诊断并使用调用方显式配置，不偷偷修改 cwd，也不自动信任历史目录。会话列表的 cwd 过滤仍针对 Header 中的创建位置。

当前运行模型/thinking 使用显式 config。SessionContext 可返回历史取值，供宿主决定如何构造 config；本阶段不自动恢复缺失模型或做 provider fallback。首次新消息前记录实际配置差异，原模型消息的 sourceModel 和 replay 数据不改写。

会话 UUID 只是历史标识，不自动覆盖现有缓存键或会话亲和配置；这些请求参数仍由宿主提供，不把缓存装配作为持久化的隐含副作用。

### 10.3 不复制已有工具配对与 replay 逻辑

pi 在请求转换层处理缺失工具结果；Jcode 的 `OpenAiTranscriptPlanner` 已经完成请求局部配对、ERROR/ABORTED 处理和 same-model replay 选择。[P3、J4]

SessionManager 只保证原始消息及父链正确保存。工具调用存在而结果尚未完成，是可能发生的中断历史，不是 JSONL 格式损坏。恢复时保留该事实，不凭空执行工具、不追加合成成功/失败结果、不解析 OpenAI replay payload。

恢复测试需证明两件事：codec 之后标准消息与原始数据相同；现有 OpenAI planner 对这组标准消息能产生既有合法请求。可以分别在 coding-agent 和 ai-providers 的测试中验证，不为跨层测试增加被禁止的模块依赖。

对宿主注入的其他 ModelClient，本阶段不能宣称自动修复所有 provider 的不完整工具历史。它们仍需实现对应请求协议；不能为了笼统“兼容所有模型”在产品层再加一套 repair/sanitizer。

恢复、open、branch 和 list 均不调用工具；下一次模型运行可能基于历史自行提出新工具调用，这与框架自动重放旧调用是两回事。验收只承诺前者不会被框架偷偷变成后者，不承诺模型绝不提出重复操作。

## 11. 会话列表与查询

`SessionFiles.list(directory, cwdFilter)` 只扫描调用方显式目录下的 JSONL，不遍历用户主目录，不递归发现其他产品资源，不写入索引文件。

返回 SessionInfo 列表和单文件读取诊断。目录不存在返回空结果；目录本身不可读抛出整体 I/O 错误；单个坏文件不掩盖其他合法会话，也不能被展示成一条正常会话。只读扫描允许看到正在追加的有效前缀，不承诺多文件事务快照。

SessionInfo 包含 path、id、创建 cwd、name、created、modified 和 messageCount。messageCount 统计文件所有分支的 message Entry，不统计模型/标签等元信息；modified 采用最新 user/assistant 活动时间，没有消息时用 Header 创建时间，避免一次读取或标签查看把旧会话变为最近。[P1]

列表按 modified 降序、相同时间按路径稳定排序。`latest` 从同一结果取首项，不暗中 open，不增加后台缓存、搜索全文索引或并行扫描线程池。过滤只做规范化路径比较，不将其描述为目录访问授权。

## 12. 实施切片

| 切片 | 交付内容 | 不得越界 | 本批完成证据 |
|---|---|---|---|
| **4A：内存模型与窄 core 接口（已完成）** | Header/五种 Entry、ID/父链、Manager、snapshot、context builder；`Agent.replaceMessages` | 无文件 I/O、动态配置、摘要/扩展类型 | 树与分支路径、名称标签、消息隔离；core idle/active/closed、context/state及队列保持测试 |
| **4B：JSONL 文件闭环（已完成）** | 固定 codec、SessionFile、创建/open、顺序追加、单写者、尾部处理 | 无后台 writer、数据库、迁移框架、WAL | 文本/多模态/replay往返；短写、尾行/中部损坏、锁冲突、追加失败 fixture |
| **4C：产品接入与失败一致性（已完成）** | 默认内存及显式文件工厂、MessageCompleted 接入、continueRun、run失败对齐、关闭所有权 | 不监听 ToolCompleted 伪造历史、不复制 AgentLoop、不开新恢复服务 | fake model 多轮与工具结果；已写入后 sink失败、writer失败、取消/close与观察Future回归 |
| **4D：分支、列表和发布收口（已完成）** | 产品 branch/reset、名称标签、显式目录列表/最近/cwd过滤；文档与完整演示 | 无跨文件fork、光标sidecar、热切换provider、UI | 保存—关闭—恢复—继续—分支端到端；旧分支可查、无工具自动重放、全仓与native验证 |

每批同时补测试，不在 4D 才第一次接入真实产品。名称可以根据当前包约定微调，但出现行为改变时必须改本文映射和对应验收，不由局部 fallback 决定。

## 13. 必须覆盖的测试

| 类别 | 关键行为 |
|---|---|
| Header/Entry | version 1、五种类型、ID碰撞处理、重复ID、父节点必须已存在、多个根、未知类型/版本 |
| 消息codec | User/Assistant/ToolResult，四种Content，usage/metadata/sourceModel；嵌套JSON、Unicode、空正文、图片、opaque replay精确保留 |
| 不可变性 | 调用方修改原参数或返回JsonNode不影响历史、文件写入或后续请求 |
| 树与元信息 | 原分支保留、root→leaf顺序、非选中分支不混入；名称/标签追加及清除，且不进模型消息 |
| 顺序接入 | 两个工具反向完成仍按MessageCompleted的源顺序落盘；每条消息只保存一次；steer/follow-up只在实际消费后入历史 |
| 半成品 | 大量delta不入Session；终结ERROR/ABORTED正确保存；工具进度不伪装成ToolResult |
| 文件边界 | 真实临时文件顺序追加、短写、完整末行无LF、截断JSON/UTF8尾片段、首次恢复后追加不粘行；中部/结构损坏不改文件 |
| 单写者 | 同一文件第二写者被拒绝；正常及预中断列表查询、重复 open 失败后由独立 JVM 证明 owner 锁仍有效；活动列表仍可追加；关闭后可重开；锁与Java接纳职责分开 |
| 故障一致性 | user已入历史后模型流sink失败；tool结果已入历史后宿主失败；下次请求含已接纳前缀且不重复；写入不确定或底层 channel/lock 失效后 `prompt`/`continue`/元信息在 provider 或新 Entry id 前拒绝 |
| 生命周期 | run/reload/branch/metadata互斥；取消观察Future不取消真实操作；close不提前释放在途writer；失败完成回调可以在允许状态下重入 |
| 恢复与配置 | 相同输入恢复前后标准请求历史等价；当前显式cwd/model差异可诊断；旧replay保留、adapter选择规则不变 |
| 工具中断 | 恢复到存在未完成tool call的历史不调用工具；现有planner只在请求视图提供占位，不修改JSONL |
| 分支与队列 | branch不重建工具/Agent，保留当前对象pending队列；新open对象无旧队列；用户节点continue与父节点替换输入分别验证 |
| 列表 | 缺失目录、坏单文件、多个cwd、稳定最近排序、messageCount口径、活动 owner 使用 Manager 内存视图；阻塞一个会话的摘要读取不阻塞另一会话创建/关闭 |
| 前三阶段回归 | 项目指令原样拼接、候选回退、idle reload、原生工具、背压、模块边界均不退化 |

并发测试使用已经存在的 gate/latch/future和明确完成条件，不使用 sleep 或自旋碰运气。错误注入使用小型包内替身，不公开额外 production 配置。

测试应验证可观察行为；不要重新建立第三阶段已撤销的节点数、文件变化协议、XML字符过滤等验收。测试能力缺失必须显式 skipped，严格 native 模式不能以 skipped 代替通过。

## 14. 完成门槛与验证命令

只有以下条件同时成立才可标记完成：

1. 仅通过 `CodingAgentSession` 完成创建、对话、工具、关闭、恢复、继续和分支；不能只展示底层JSONL读写。
2. 内存与文件模式共用相同的树与上下文语义；完整消息顺序、字段及opaque replay可往返。
3. 原始Session历史、当前分支消息视图与provider请求投影的边界清楚；没有伪造工具事实或自动重放。
4. 基础设施失败后，产品历史与下一次运行使用的消息一致；writer失败不假装持久化成功。
5. 正常追加不改写完整历史；尾部恢复和版本/结构错误有确定行为；同文件写入所有权可测试。
6. 不增加通用存储/恢复框架，不提前交付第五至七阶段功能；保留必要的Java生命周期与快照契约。
7. 前三阶段测试、全仓模块边界和严格native验证通过，并记录实际运行环境、数量与skip原因。
8. 第15节的路线图和公开说明同步完成；计划归档时补实施记录，不把本计划中的目标写成已完成结果。

建议实施时使用以下命令；此处不是执行结果：

```bash
mvn -pl agent-core -am test
mvn -pl coding-agent -am test
mvn -pl ai-providers -am test
mvn clean verify

mvn -pl coding-agent -am verify -Plocal-tools-smoke \
  -Djcode.test.bash=/absolute/path/to/bash \
  -Djcode.test.rg=/absolute/path/to/rg \
  -Djcode.test.fd=/absolute/path/to/fd

git diff --check
```

针对性测试名称随实际类名确定，不预填将来的测试数。fake model和本地HTTP fixture足以形成主要证据；真实provider probe仅可作为补充，不要求网络、API Key或真实账单来证明文件持久化正确。

## 15. 必须同步的路线图与文档修订

### 15.1 总路线图第9节的替代口径

| 原计划表述 | 应同步为 |
|---|---|
| 首批Entry至少包括compaction、branch summary、typed custom | 首版五种Entry完整交付；摘要和custom记录随第六、七阶段增加并定义投影 |
| 切换分支时在idle边界重建底层Agent | 同会话分支使用通用idle消息替换；只有create/open新对象才新建Agent |
| schema version与显式migration | 首版版本化及未知版本处理；出现真实旧版本后增加实际migration |
| 工具配对不会因分支或恢复破坏 | 原始消息顺序与关系不被改写；中断产生的未配对历史由既有provider请求层处理，不重放工具、不写假结果 |
| 进程中断不会使已完成JSONL行不可解析 | 正常追加保留既有完整行；EOF截断可诊断处理；不承诺断电durability或不合作外部写者的行为 |
| 同一文件并发写策略待决定 | 文件句柄单写者检测，不实现多写者协同 |

以上属于当前详细计划对早期草案的显式收敛，不是漏实现后仍声称满足旧范围。阶段六计划需承接 Compaction/branch_summary 的数据与投影；阶段七承接 custom/custom_message 的数据与注册语义。

### 15.2 其他文件

更新 `coding-agent/AGENTS.md` 的定位、数据职责和当前能力；更新 `agent-core/AGENTS.md` 与运行时契约说明中的idle消息替换边界；README补充显式创建/open、分支不是工作区回滚、当前配置优先以及持久性限制。

根 `AGENTS.md` 仅在通用约定或入口确有变化时修改，不为记录日常开发过程添加长篇状态日志。进行中的本文放 `docs/plans/`，实现完成后移到 `docs/plans/archived/` 并修正引用。[J8]

本次计划不要求修改 `ai` 消息协议、既有工具行为或provider wire规则；如果实施发现必须修改，先给出失败fixture与最小差异说明，不能借此扩展成本阶段之外的改造。

## 16. 实施与验证记录

- 实施日期：2026-09-20；环境：macOS 27.0 arm64、Java 21.0.10、Maven 3.9.14。
- `mvn -pl coding-agent -am test`：通过，共 552 个测试，0 failures/errors，10 个本地工具环境相关 skip。
- `mvn clean verify`：通过，共 819 个测试，0 failures/errors，10 个本地工具环境相关 skip。
- 严格 native smoke 使用 `/bin/bash`、`/opt/homebrew/bin/rg`、`/Users/quinncypp/.pi/agent/bin/fd` 运行 `mvn -pl coding-agent -am verify -Plocal-tools-smoke ...`：通过，共 552 个测试，0 failures/errors/skips。
- `git diff --check`：通过。
- 实现包含 4A～4D 的模型、文件、产品接入、并发生命周期、分支/元数据与发现测试；未引入跨文件 fork、cursor sidecar、provider 热切换、Compaction、Extension 或 UI。
- 提交后审查加固了同 JVM 通道生命周期（并用独立 JVM 验证原生锁）、writer 失效前置拒绝、工具参数 `BigDecimal` 精度、Manager 唯一历史所有权，以及并行工具逆序完成的确定性 gate/latch 回归；没有扩展为通用锁或存储框架。
- 后续复审将活动 owner 的列表查询改为 Manager 内存视图，消除了查询中断关闭 writer 通道的路径；底层 channel/lock 失效纳入前置拒绝，并把 registry 全局锁收缩到登记和状态切换，同文件临时 reader slot 继续保护原生锁生命周期。

## 17. 参考依据与查阅位置

以下都是固定版本或官方API。代码位置用文件及函数名定位，避免后续格式变化让手写行号失效。pi-book仅作解释性辅助；当前pi源码和Jcode既有契约仍是行为依据。

| 编号 | 依据 | 本计划使用范围 |
|---|---|---|
| P1 | [pi session-manager.ts](https://github.com/earendil-works/pi/blob/60e7e76bd7ea25cad1dd6f3f1ce0d18814a42759/packages/coding-agent/src/core/session-manager.ts) | Header/Entry、buildSessionContext、_persist、_buildIndex、branch/resetLeaf、名称标签及列表 |
| P2 | [pi agent.ts](https://github.com/earendil-works/pi/blob/60e7e76bd7ea25cad1dd6f3f1ce0d18814a42759/packages/agent/src/agent.ts)；[pi-book第11章](https://github.com/ZhangHanDong/pi-book/blob/0a8863b611504a47302931ad9adf1cab71a0b79f/src/ch11-session-tree.md) | 运行时与产品历史职责、会话树设计解释；书籍不是完整产品恢复API的规范 |
| P3 | [pi transform-messages.ts](https://github.com/earendil-works/pi/blob/60e7e76bd7ea25cad1dd6f3f1ce0d18814a42759/packages/ai/src/api/transform-messages.ts) | 请求局部缺失工具结果、终结失败与same-model处理 |
| J1 | [Jcode总路线图](https://github.com/YPQuinn/Jcode/blob/8e3dff82cbbebe668a6f49f8d3d1e604a158a973/docs/plans/coding-agent-development-roadmap.md) | 阶段四原范围与阶段五至七边界 |
| J2 | [AgentEvent.java](https://github.com/YPQuinn/Jcode/blob/8e3dff82cbbebe668a6f49f8d3d1e604a158a973/agent-core/src/main/java/site/pplee/jcode/agentcore/event/AgentEvent.java)；[运行时契约](https://github.com/YPQuinn/Jcode/blob/8e3dff82cbbebe668a6f49f8d3d1e604a158a973/docs/agents/runtime-contracts.md) | MessageCompleted/ToolCompleted的顺序、背压、partial及请求投影边界 |
| J3 | [Agent.java](https://github.com/YPQuinn/Jcode/blob/8e3dff82cbbebe668a6f49f8d3d1e604a158a973/agent-core/src/main/java/site/pplee/jcode/agentcore/Agent.java)；[CodingAgentSession.java](https://github.com/YPQuinn/Jcode/blob/8e3dff82cbbebe668a6f49f8d3d1e604a158a973/coding-agent/src/main/java/site/pplee/jcode/codingagent/CodingAgentSession.java) | 当前context发布时点、产品接纳、失败收尾与资源所有权 |
| J4 | [OpenAiTranscriptPlanner.java](https://github.com/YPQuinn/Jcode/blob/8e3dff82cbbebe668a6f49f8d3d1e604a158a973/ai-providers/src/main/java/site/pplee/jcode/aiproviders/openai/OpenAiTranscriptPlanner.java) | 已有工具配对、终结失败及opaque replay处理，避免重复实现 |
| J5 | [Message.java](https://github.com/YPQuinn/Jcode/blob/8e3dff82cbbebe668a6f49f8d3d1e604a158a973/ai/src/main/java/site/pplee/jcode/ai/message/Message.java)；[Content.java](https://github.com/YPQuinn/Jcode/blob/8e3dff82cbbebe668a6f49f8d3d1e604a158a973/ai/src/main/java/site/pplee/jcode/ai/message/Content.java) | 标准消息与内容的实际字段 |
| J6 | [ModelReplayState.java](https://github.com/YPQuinn/Jcode/blob/8e3dff82cbbebe668a6f49f8d3d1e604a158a973/ai/src/main/java/site/pplee/jcode/ai/message/ModelReplayState.java) | 不透明payload及toString约定 |
| J7 | [PendingMessageQueue.java](https://github.com/YPQuinn/Jcode/blob/8e3dff82cbbebe668a6f49f8d3d1e604a158a973/agent-core/src/main/java/site/pplee/jcode/agentcore/queue/PendingMessageQueue.java) | 队列所有权与按模式消费，不虚构已存在的导出/导入API |
| J8 | [开发与Git工作流](https://github.com/YPQuinn/Jcode/blob/8e3dff82cbbebe668a6f49f8d3d1e604a158a973/docs/agents/development-workflow.md) | 计划目录、归档和指令文档更新范围 |
| K1 | [Java 21 FileChannel](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileChannel.html) | 短写、tryLock、truncate、force以及文件锁与线程同步的区别 |
