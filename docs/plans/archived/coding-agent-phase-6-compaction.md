# Coding Agent 第六阶段：Compaction 与分支摘要

> 状态：已完成并归档。
>
> 编制日期：2026-09-22。
>
> Jcode 实施基线：`c669e53f2e1efa6f57a698771613cbf634e6fde9`。编制时远端 `master` 仍指向该提交。第五阶段修复已完成源码复审；其最新测试执行记录应在实施前据实补齐，不能沿用旧提交的数字。
>
> pi 对标基线：`60e7e76bd7ea25cad1dd6f3f1ce0d18814a42759`。本阶段不随 pi 浮动分支改变行为标准。
>
> 归档路径：`docs/plans/archived/coding-agent-phase-6-compaction.md`。
>
> 现行实现：`coding-agent` 的 `compaction/`、`session/CompactionEntry.java`、`session/BranchSummaryEntry.java` 与 `CodingAgentSession.java`；provider-neutral 错误分类位于 `ai`，OpenAI 结构化映射位于 `ai-providers`，失败后继续入口位于 `agent-core.Agent`。
>
> 验证记录：见第 21 节。
>
> 总路线图：`docs/plans/coding-agent-development-roadmap.md`。本文第 19 节列出需要同步的范围与语义。
>
> 本文保留实施前的行为设计和验收依据；实际交付状态、偏离和验证结果以第 21 节为准。本次开发未创建提交或推送远端。

## 1. 目标、范围与完成标准

### 1.1 只完成长会话的上下文缩减闭环

```text
接纳正常消息和工具结果
    → 在下一次实际模型请求前构造当前分支的请求视图
    → 必要时总结较早内容，保留近期内容
    → 追加摘要 Entry，不改写原始历史
    → 发送缩减后的请求
    → 保存、重开、分支或再次压缩后仍可准确重建
```

三份信息各有职责：Session Entry 是历史事实；摘要 Entry 是对历史的有损概括和保留边界；请求视图是本次发送内容。它们不能互相冒充。[P1][P2][J1]

### 1.2 必须交付

| 能力 | 本阶段完成含义 |
|---|---|
| 上下文用量 | 最近有效 usage 加新增内容估算；失效时全量估算；标明依据，不伪装精确 tokenizer |
| 手动压缩 | idle 接纳、规划、无工具摘要调用、提交、结果与取消 |
| 自动阈值压缩 | 新 prompt、continue、steering/follow-up 和同一 run 的工具循环均在模型请求前检查 |
| 压缩持久化 | `CompactionEntry`、保留边界、连续压缩、重开和分支投影 |
| 溢出恢复 | provider-neutral 分类；仅已分类的上下文溢出在一次用户操作内最多恢复一次 |
| 分支摘要 | 显式带摘要的分支操作；从离开分支提炼内容，追加到目标节点之后 |
| 生命周期 | 模型调用、取消、文件提交、宿主事件、关闭及观察 Future 的关系明确 |
| 配置衔接 | 复用阶段五设置和 ModelProfile，不另建模型目录或预算系统 |

仅交付手动/阈值压缩而没有分类后的单次恢复，只能标为阶段内部分完成。分支摘要由第四阶段明确后移至本阶段，也不能以空 Entry 类型代替完整行为。[J1]

### 1.3 明确不做

不引入向量库、跨会话记忆、检索式上下文、工作区回滚、历史删除、后台预摘要、自动换大模型、摘要模型路由、递归分块摘要、压缩后的无限重试、工具自动重放、摘要质量评分器或摘要修复器。

不新增 Session 存储格式分支、数据库、摘要 sidecar、独立锁服务、全局 OperationScheduler、冷却/熔断系统、配置文件监听、Extension hook、CLI/TUI/Server。第七阶段的 custom 数据和执行型扩展不提前进入本阶段。

### 1.4 旧 API 的变化限度

旧构造器和第五阶段默认设置不自动启用摘要生成，也不增加默认模型费用；原 `prompt`、`continueRun`、`branch`、`resetLeaf`、模型切换和工具授权语义保留。

“关闭自动压缩”只表示不生成新检查点，不表示忽略历史中已经存在的摘要。新 reader 一旦成功打开包含摘要的 Session，仍必须按其边界投影请求，不能恢复成发送全部旧历史。

`branch()` 不发起模型请求。带摘要分支使用明确的新入口。手动压缩也不会自动执行下一次正常 prompt 或保存默认设置。

## 2. pi 如何做 → Jcode 如何映射 → 必要差异

| 议题 | 固定 pi 行为 | Jcode 映射 | 差异性质与理由 |
|---|---|---|---|
| 历史压缩 | 追加 summary 和 firstKeptEntryId，沿分支重建上下文 [P1][P2] | 同样追加两类摘要 Entry，原记录不变 | 行为对齐，不新增存储系统 |
| 运行时视图 | 压缩后可重建运行时消息 [P1][P2] | core 保留原始分支消息；ContextTransformer 生成短请求视图 | 复用 Jcode 已稳定的 request-local 隔离契约 [J2] |
| 自动触发位置 | 新请求前、工具循环及部分 run 结束位置检查 [P5] | 集中在每个即将发生的正常模型请求前 | 避免重复触发点和没有后续请求时的额外摘要调用 |
| 用量 | 最近非失败、非零 assistant usage 加尾部估算 [P1] | 同一思路，遵守 Jcode 缓存/推理量口径 | 不累加整个会话账单，不重复计数 [J6] |
| 截断点 | user/assistant 可作边界，不从 tool result 开始；支持 split turn [P1] | 对完整工具组选择边界，允许长任务内部切分 | 保留关键行为，不限定只能按用户轮次切 |
| split turn 摘要 | 历史和轮次前缀可分别调用后合并 [P1] | 首版一次请求总结整个待压缩前缀，显式提供原任务与后缀衔接要求 | 有意收敛模型调用次数，不声称这是 Java 的必然差异；不丢掉 split-turn 能力 |
| 分支摘要 | 共同祖先之后的离开路径，追加到目标位置；按预算取近期内容 [P3] | 显式方法，相同路径原则；包含有界结果与错误信息 | 利用已有标准工具结果，避免把失败调用写成完成工作 |
| 摘要提示 | 专用提示、旧摘要更新、文件线索、工具结果文本截短 [P1][P4] | 独立无工具请求；截短只作用于摘要输入 | 不执行摘要中的工具，不过滤原历史 |
| 溢出识别 | pi 有自己的错误识别路径 | ai 标准分类，adapter 解释已知 wire code，产品仅消费分类 | Jcode 模块边界要求；不在产品层猜错误文本 [J1][J7] |
| 一次恢复 | 产品编排压缩与继续 | 原失败保留，窄的失败后继续入口，产品完成统一结算 | 当前 continue 不接纳 assistant 末尾，必须明确处理 [J2] |
| 配置 | pi 默认启用，reserve=16384、keepRecent=20000 [P1] | 默认不自动生成；保留这两个显式预算的参考缺省，并校验已知小窗口 | 维持现有 SDK 默认无额外请求；不暗中按比例改用户参数 |
| 写入与关闭 | 产品管理摘要与 Session | 复用 Manager/writer 和真实收尾顺序 | 必要并发映射，不再移植一套持久化逻辑 |

新增差异必须附一个具体使用场景或失败用例。不能以“更安全”“将来方便”“测试容易”为唯一依据。类型与普通错误检查不等于需要可插拔策略、接口族和默认实现族。

## 3. 实施结构与依赖

### 3.1 复用优先

主体仍在 `coding-agent`。直接使用阶段五的 ModelClient/Models、当前 ModelSelection、现有 SessionManager/codec、CancellationSource、产品接纳及事件投递。

| 组件草案 | 最小职责 |
|---|---|
| `CompactionSettings` | enabled、reserveTokens、keepRecentTokens；区分稀疏层与有效设置 |
| `ContextUsageEstimator` | 纯估算和一个有效用量观测，不调用网络、不调用 tokenizer 服务 |
| `CompactionPlanner` | 确定历史前缀、保留边界、旧摘要与 split-turn 信息；不做 I/O |
| `SummaryGenerator` | 通过现有 ModelClient 进行一次摘要请求并验证终结结果 |
| `CompactionEntry` / `BranchSummaryEntry` | 有来源边界的持久化值，不是普通消息 |
| `SessionContextBuilder` 扩展 | 区分原始 transcript 构建与有效请求视图构建 |
| `CodingAgentSession` 内部编排 | 手动/自动/恢复/分支操作的接纳、提交与完成 |

可用包内辅助类拆短方法；不要求每行都拆成接口、实现、工厂。需要访问包内 Manager 的编排留在产品包，不为了目录美观公开 Manager。

### 3.2 跨模块改动仅两项

`ai` 增加最小的结果错误分类，`ai-providers` 映射已知结构化溢出错误；`agent-core` 增加窄的失败后继续入口。core 不理解 Compaction、SessionEntry、文件和预算。

既有 ContextTransformer 的整体异常协议不为本阶段随意修改。需要特别处理的产品 I/O 和回调失败，在产品编排中保留原失败含义，见第 11 节。

不新增 Maven 模块，不把模型上下文窗口塞进 `ai.Model`，不让 `ai` 或 core 反向依赖 `coding-agent`。

## 4. 数据、格式与历史事实

### 4.1 CompactionEntry

在公共 Entry 基础字段 `id/parentId/timestamp/type` 之外，首版保存：

| 字段 | 含义 |
|---|---|
| `summary` | 已成功生成的最终文本；不是运行时 partial |
| `firstKeptEntryId` | 本分支中仍保留原内容的第一个上下文可见来源节点 |
| `tokensBefore` | 压缩前有效请求视图的用量值；不是整个文件历史 token 总和 |
| `tokenEstimateSource` | usage 加尾部估算或全量估算，避免把数值呈现为精确实测 |
| `summaryModel` | 本次摘要真正使用的 ModelRef；允许与历史最后 model_change 不同 |
| `usage` | 摘要调用的标准 Usage；未报告时沿用 Usage.zero，不当作主会话新用量基线 |
| `details` | 固定类型的文件线索，例如 readFiles/modifiedFiles；不接受任意 Map 或 Extension payload |

`parentId` 为压缩提交前当前 leaf；firstKept 必须能沿其祖先链找到。它是保留边界，不是要从 JSONL 文件物理删除的位置。

首版要求确实存在可保留的原消息或分支摘要；不支持用 null 边界表示“删掉所有当前内容”。当前用户问题和最新完整工具组不得被悄悄全量丢弃。

### 4.2 BranchSummaryEntry

保存基础字段、`fromId`、`summary`、`summaryModel`、`usage` 和同一固定 details。`parentId` 是目标节点；`fromId` 是离开时的原 leaf，可以不在目标祖先链中，但必须是本 Session 中既有节点。

若为分支摘要按预算省略了较早部分，应在摘要材料及最终说明中标明覆盖范围有限。不声称该摘要完整代表所有离开历史；首版不另建覆盖率指标或索引。

### 4.3 格式策略

保留当前 Header version 1，扩展明确的 Entry `type`，不改变已有五类记录的含义，不重写已有 Header 和旧记录。这是本次选择的增量扩展规则，不声称旧 reader 能识别新类型。[J3]

新版继续读取旧五类文件。旧版本遇到 compaction/branch_summary 会按现有未知类型规则明确失败，不会静默忽略摘要再发送全部历史。文档必须说明不能拿旧二进制继续写含新类型的文件。

编解码、SessionEntries.copy/validateNext、SessionSnapshot、树遍历、列表统计以及所有 sealed switch 一并更新。摘要调用不计入普通 user/assistant 消息条数；usage 可供单独统计，但本阶段不新增计费报表。

新增错误分类按可选 metadata 字段编解码：旧字段缺省表示未知；未知的未来分类值按未知处理，不能启动恢复。未知 Entry 类型仍失败，这两种情况不能混为一谈。

### 4.4 不复制第二套历史

Manager 仍是唯一长期 Entry 序列及索引所有者。规划过程中可形成短期的带 Entry id 的视图列表；不要长期再保存一份 JSONL、第二棵树或所有历史内容的散列索引。

为了确认回调输入与 Session 父链相符，可利用同一接纳边界和消息顺序；不按内容字符串查找节点，因为不同用户消息可以完全相同。

## 5. 原始 transcript 与有效请求视图

### 5.1 两个明确构建入口

现有 `SessionContextBuilder.build(...)` 继续为 core 产生所选分支的真实标准消息和历史 model/thinking；新的摘要 Entry 不混成假的用户消息写回 raw transcript。

增加等价于 `buildRequestView(snapshot, leaf)` 的纯构建入口，用于摘要投影。生成的 summary 消息仅在此次请求中存在，使用摘要 Entry 的时间和明确来源说明，不向 Session 再追加普通消息。

旧 `continueRun()` 对原始末尾角色的要求不因请求视图第一个/最后一个 synthetic summary 变成 user 而被放宽。压缩本身不等于新增用户指令。

### 5.2 当前分支的重建算法

给定 root→leaf 路径 P：

1. 取得 P 中最后一个 CompactionEntry C；不能扫描别的分支来选“最新摘要”。
2. 无 C：依路径顺序投影标准消息及 BranchSummaryEntry；其他元信息不进入请求。
3. 有 C：输出 C 的摘要；然后输出 firstKept 至 C 之前的可见内容；再输出 C 之后至 leaf 的可见内容。
4. C 之前旧 CompactionEntry 的文本不重复注入；它们的概括已经由最新摘要承接。保留区间内 BranchSummaryEntry 仍按正常内容投影。
5. model/thinking 的历史查询仍沿完整父链，不因请求裁剪丢失元信息；本次实际选用的模型依阶段五 current selection，不被摘要或分支自动覆盖。

与 pi 相同的核心是“最后一份适用检查点 + 保留区间 + 后续新增”。[P1][P2]

### 5.3 可验证示例

```text
原路径：U0 → A0 → U1 → A1 → U2 → A2
追加 C1，firstKept=U1
请求视图：Summary(C1), U1, A1, U2, A2

随后追加 U3 → A3，并追加 C2，firstKept=U2
C2 输入：C1.summary + U1/A1（本轮需要被概括的部分）
新视图：Summary(C2), U2, A2, U3, A3
```

第二次不能只总结 C1 之后的消息，否则会漏掉此前保留的 U1/A1。[P1]

从 C1 之前的 A0 开新分支，不应用 C1/C2。分支若确实继承 C1 节点，则继续应用 C1。标签、会话名称不影响这个判断。

### 5.4 与 ContextTransformer 的配合

正常模型请求前，当前用户、steering/follow-up 和上一批工具结果已经通过 MessageCompleted 进入 Manager。[J2][J3] 产品 transformer 使用这一已接纳分支生成短视图，必要时完成自动压缩后重新构建。

不对 raw AgentContext 调用 replaceMessages 来完成自动压缩；这会破坏 request-local 隔离。replaceMessages 仍用于真正的 branch/reset 或异常后的原始历史对齐。

重开、模型切换、project context reload、分支与失败后对齐使用同一套投影语义。不能只在新工厂配置 transformer，而旧 `open(config,file)` 打开含摘要文件时仍使用 identity。

## 6. 设置与模型限制

### 6.1 首版配置

在阶段五 sparse settings 中增加固定 `compaction` 对象，按叶字段继承：

| 字段 | 缺省及含义 |
|---|---|
| `enabled` | false；开启请求前阈值检查与分类后的单次溢出恢复 |
| `reserveTokens` | 16384，参考 pi；正整数，不代表具体模型窗口 |
| `keepRecentTokens` | 20000，参考 pi；正整数，近期内容的近似保留目标，不是严格字符串截断长度 |

手动压缩和显式分支摘要不受 enabled=false 禁止，但也需要可用的模型限制和合法预算。没有隐式后台行为，也不新增 per-model settings 字典、独立 overflow 重试开关或动态阈值策略。

SDK 和旧完整配置通过一个不可变配置值显式接入；旧构造器保留兼容重载并默认 disabled。不要要求用户为了启用 compaction 重建 Provider。

设置加载、SettingsOverrides/effectiveOverrides、来源诊断、SettingsUpdate SET/REMOVE 和保存验证同时更新。尤其不能因第五阶段 effectiveOverrides 手工复制 Settings 字段而丢失新增 compaction 覆盖项。

### 6.2 Profile 必须进入真实运行路径

当前工厂的 ModelRuntime/目录快照持有 profiles，但 Session 未把它作为压缩依赖。将这份非秘密、不可变的 ModelRef→ModelProfile 数据明确传给 Session；借用模式使用宿主传入的 profiles。[J4]

旧显式 API 可通过本阶段配置值传同样的模型限制；没有 profile 时保持未知。不得从 modelId、endpoint 或 API 名称推断窗口，不强制把未列出但 Provider 支持的模型加入目录。

模型切换后按新 ModelRef 获取窗口，不能沿用旧值。profiles 不持久化成完整配置快照，不增加网络规格拉取或热更新。

### 6.3 预算定义

记 W 为当前模型已知总窗口，M 为可选的已知最大输出，R 为本次有效预留量，K 为近期目标：

```text
R = max(config.reserveTokens, 正常请求显式 maxOutputTokens 或 0)
T = W - R
S = min(floor(0.8 × config.reserveTokens), M（若已知）)
```

T 是自动触发边界，S 是一次摘要的输出上限。R 的计算只用于留足本次正常输出空间，不修改用户请求的 maxOutputTokens；S 使用专用摘要参数，不照搬普通请求输出值。

W 已知时，要求 W>R、K<T、S>0；正常显式输出超过已知 M 等既有合法性问题明确报错，不静默 clamp。不强制根据 K 推断最终保留区间必须恰好小于 K；工具组边界和实际摘要大小仍需后续检查。

小窗口模型必须显式提供适用的 reserve/keep 值。例如 W=8192 可以使用 reserve=2048、keepRecent=3072。参考 pi 数字不适用时不能悄悄按比例替换用户的显式值。

W 未知：返回用量估计和“窗口未知”；自动跳过新增压缩，正常请求仍可发送；手动或分支摘要返回不可执行原因，不猜预算；分类后的 overflow 原样结束，不假称已完成恢复。用户可用 SDK 提供可靠限制。

已启用自动压缩且目标模型预算已知非法时，创建/切换在发布前拒绝；W 未知不等于非法。disabled 路径不因未用到的参考预算阻止普通对话。

## 7. 上下文用量与估算

### 7.1 首选有效观测，不累计账单

采用最近一次对应有效请求视图、同一模型的成功 assistant usage，再加其后的未计入消息估算。ERROR/ABORTED 和全零 usage 不作为基线；摘要调用 usage 永远不作为正常上下文基线。[P1][J6]

Jcode 标准量优先取正的 totalTokens，缺失/零时取 input+output+cacheRead+cacheWrite。OpenAI adapter 已从 input 拆出缓存部分；reasoningTokens 是 output 子集，不能再次累加。不能将多轮 totalTokens 相加当成当前窗口占用。[J6]

### 7.2 一个可失效的观测足够

本进程维护最近有效观测即可：模型、当时的正常请求边界、适用摘要 Entry id、usage 和请求固定部分。普通模型成功后更新。

压缩提交、branch/reset、模型切换、system prompt reload 或重新打开 Session 时，使旧观测失效；先用全量估算，等下一次正常请求提供新 usage。无需持久化观测、维护历史签名或猜旧文件中某个 usage 是否恰好对应当前 prompt。

这比从所有历史 assistant 中无条件找最后一个 usage 更保守，但对应一个具体问题：同样的原消息可能经历了不同摘要、模型和 prompt。用一个可失效值解决，不建设用量缓存系统。

### 7.3 全量估算范围

首版不新增 tokenizer 依赖，采用 pi 对应的文本长度近似，明确属于 heuristic：普通文本/参数 JSON 字符数除以 4 向上取整；图片按 pi 的每块约 1200 tokens 参考值估算。[P1]

全量范围包括 system prompt、实际工具声明及其 schema、有效消息文本、可见 thinking 文本、工具参数与工具结果；图片不按 base64 长度计算，opaque replay payload 不按 JSON 字符数当作精确可见 token。

中文、代码、图片和不透明 replay 的估算可能偏高或偏低，不写“始终保守”“保证不超窗”。不因估算不完美添加多个安全系数和多模型 tokenizer 注册器。

有有效 usage 基线时，它已覆盖当时 system/tools 和输入，不再重复加这些固定部分；仅加基线之后的新内容。重新构造摘要后的候选视图使用同一个全量估算器比较前后，避免把实测前值与估算后值直接当严格压缩率。

公开用量结果最多包含 token 值、来源、可选窗口/阈值及是否已知；不需要每条消息单独持久化用量或发诊断洪流。

## 8. 截断规划与摘要输入

### 8.1 在可见内容和 Entry id 上规划

规划器输入当前分支、最新适用检查点、有效预算及估算函数，输出 previousSummary、待总结区间、firstKeptEntryId、保留区间和 split-turn 信息。所有输出都是短期值，不做文件 I/O。

从有效近期内容末尾向前累计到 K，再选择附近合法边界。尽可能保留近期工作，而不是精确凑足一个 token 数。必须有非空的待总结前缀；没有前缀则无可压缩工作。

合法起点可以是 user、assistant 或已存在的 BranchSummaryEntry。不可从 tool result 起步，不可只保留一批并行结果的一部分。跨越同一 assistant 工具调用及结果的候选边界，要移到整组开始之前。

metadata 不是截断时的“消息”，不靠条目数估算；新的 firstKept 首版指向真正产生上下文的节点，不把相邻标签误当作待保留正文。[P1]

### 8.2 长单任务必须支持拆分

不能规定只在 user 起点切分，否则一次 prompt 内的长工具循环永远没有可用切点。允许保留某个 assistant 工具组之后的后续过程，同时在摘要输入中明确：原始任务是什么、早期做过什么、保留后缀继续需要什么。

首版将旧摘要与整个待压缩前缀放入一次摘要请求，不复制 pi 的历史摘要/轮次前缀双请求。通过提示中的 Original request / Context for retained suffix 要求保留衔接信息；不把近期后缀重复发去总结，也不丢弃旧摘要。[P1]

这是减少独立模型调用的明确范围决策。若实测一个请求不能满足必要质量，应拿具体失败 fixture 再调整，不预先建立摘要合并工作流。

### 8.3 已存在的坏配对不在此修复

算法不得新制造孤立结果；恢复文件中原来就缺结果的工具调用，仍由既有 provider 请求投影处理。规划器遇到未完成尾部工具组时把整个尾组保留，不拆分、不执行补调用、不写假结果。[J7]

不能为通过截断测试重新创建 ToolHistoryRepairer。后续模型重新提出同一业务操作，与产品自动重放旧 ToolCall 是两回事；本阶段只承诺不会由恢复器主动重放旧调用。

### 8.4 无法形成有效切点

手动操作无待总结前缀时返回 SKIPPED/NOTHING_TO_COMPACT，不发摘要请求、不追加记录。

自动检查已超过 T，但最新用户输入或一个必须保持完整的工具组已经占满预算时，停止本次正常请求，报告 INSUFFICIENT_REDUCTION/NO_VALID_CUT 等明确原因，不反复缩小 K、不逐条丢弃最近内容。

摘要输入过大也不是递归分块的开关。只使用下节规定的文本序列化和工具结果截短；仍明显无法容纳时停止并保留历史，让调用方调整模型/预算或显式分支。

## 9. 一次专用摘要请求

### 9.1 模型与参数

使用当前选定模型及现有 ModelClient/Models；捕获该操作的 ModelRef/thinking，操作期间不允许模型切换。既有 provider 的认证、HTTP、SSE 和取消机制直接复用。

构造新的 ModelRequest：专用 system prompt、一个包含材料与要求的 user 消息、空 tools、当前绝对 thinking、新的 ModelRequestOptions。

摘要 options 使用 S 作为 maxOutputTokens；temperature 不指定；ToolChoice 采用默认 AUTO（没有工具可选），不能复制普通请求的 REQUIRED/Specific。PromptCacheOptions 使用既有 NONE，不携带原缓存 key/affinity，不创建新的路由 Session 或随机缓存 ID。与 pi 的不写缓存目标对应，但服从 Jcode NONE 的已有语义。[P1][J5]

不创建另一个 CodingAgentSession，不经过自动压缩 transformer，不启动工具执行循环。现有 adapter 的显式 retry 如被宿主配置仍可工作；产品不加一层摘要重试，不跨模型 fallback。

### 9.2 材料序列化

按时间顺序保留 user、assistant 可见文字、thinking 可见摘要、工具名与参数、工具结果/错误、旧摘要和分支摘要。用角色与来源标记说明这些是要总结的材料，而不是新的运行指令。[P4]

图片只留明确占位说明，不把 base64 或 opaque replay payload 搬进摘要文本；原消息中的图片和 replay 全部原样留在历史，近期保留部分仍正常发送。

沿用 pi 的单个工具结果最多约 2000 个文本字符的摘要输入限制，并带截短标记；避免在 Java UTF-16 surrogate 中间截断。它不是新增的工具输出限制，也不影响原始 JSONL。[P4]

旧摘要和 user 文本不被同样悄悄切碎。将 system、全部材料和 S 一起估算；已知不能容纳时不发送摘要请求。估算可能有误，真实摘要溢出按失败结束，不触发摘要的摘要。

### 9.3 提示词目标

固定模板要求保留目标、约束、已完成/进行中/阻塞事项、关键决定、错误与未验证假设、下一步及关键文件路径。split turn 增加原始问题和对保留后缀的说明。第二次压缩要求更新已有摘要，而不是丢掉已有事实重新总结最近几条消息。

允许调用方提供本次 additional instructions，作为摘要焦点追加，不做模板脚本执行。首版不开放任意摘要执行器或 Extension 替换 hook。

结构是提示词要求，不做标题逐字解析或“缺了某个标题就自动修复”。摘要是有损结果，不承诺语义完全等价，不为它增加质量评分器。

### 9.4 终结与候选验证

逐步消费已有 AssistantMessageStream，直到终结；不把摘要 delta 投递为普通助手消息，不只等待最终 Future 而永远不消费流。

只接受 StopReason.STOP、非空最终文本、无 ToolCall 的结果。ERROR、ABORTED、LENGTH、TOOL_CALL、空文本或非法结果均不成为检查点；失败原因不回显整份材料或 partial 摘要。[P1]

用候选 summary 加保留消息构造候选请求视图，以相同估算口径比较前后。需要实际减少上下文；自动/overflow 路径还要求候选估计不超过 T。否则不提交、不发送原始大请求、不二次自动缩写刚生成的摘要。

手动路径可提交确有减少但仍超过自动阈值的候选，结果必须标明剩余估计与未满足阈值；它不会自动继续普通请求。不能声称“手动压缩成功”就证明后续模型一定能接收。

### 9.5 文件线索

复用工具调用中显式 path 和旧摘要 details 累积 readFiles/modifiedFiles，不扫描磁盘，不读取文件当前内容，不运行 git diff。[P4]

这些字段按 pi 表达历史中涉及的读/写路径，不是操作成功或当前文件存在的证明。提示材料保留相应工具结果的错误标记，要求区分尝试、成功和未验证工作；不得仅凭 write ToolCall 就在摘要中宣称“已修改成功”。

固定 details 不包含凭证、完整配置、工具实例、未知 JSON payload 或未来扩展数据。文件顺序可排序去重，无需引入路径身份数据库。

## 10. 自动检查与手动入口

### 10.1 默认行为与入口草案

```java
// 草案；实际类型名可按仓库风格调整。
CompletionStage<CompactionResult> compact(String additionalInstructions);
CompletionStage<BranchSummaryResult> branchWithSummary(String targetEntryId,
                                                     String additionalInstructions);
ContextUsageEstimate contextUsage();
```

compact/branchWithSummary 仅 idle 接纳，与 run/reload/history/model switch 互斥。返回 stage 为观察副本，取消观察 stage 不取消真正操作；现有 abort() 扩展为取消当前实际操作。

contextUsage() 不调用模型、不写文件，不通过查询触发自动摘要。其数值是当前时点估计，不承诺多次 getter 之间形成事务快照。

### 10.2 自动检查唯一位置

每个正常 ContextTransformer 调用：先取有效请求视图；enabled=false 或 W 未知则不生成；有效估计<=T 则直接返回；否则规划一次并最多生成一次摘要，提交后返回重建视图。[J2]

这个位置覆盖新输入、continue、steering/follow-up、上一批工具结果以及失败后恢复的下一次模型调用。无后续正常请求时不主动压缩，不在 ToolCompleted、RunCompleted 和计时器中重复挂触发器。

自动摘要可在一个长 run 内发生多次，但每次必须是新增正常内容造成新的可压缩前缀。不能因 metadata 变化或同一摘要后估算仍偏高而不断压同一段内容。

### 10.3 用边界而非冷却机制防重入

每次 transformer 至多一次；最新检查点及 firstKept 决定哪些内容已被概括；没有新可见前缀时不生成。候选后仍不满足条件则明确停止。

overflow 刚完成有效压缩后的第一次恢复请求直接使用该候选视图，不立刻再跑一次阈值摘要。之后若新工具结果真实增长，正常阈值检查恢复。用本次恢复的一次性状态即可，不建 cooldown、永久禁用标志或按模型的失败缓存。

## 11. 取消、提交、事件与故障

### 11.1 一次摘要操作的边界

```text
锁内接纳并捕获当前路径/模型/设置
    → 锁外规划、发摘要请求、消费结果
    → 校验候选
    → 锁内检查关闭/取消并接纳本次提交
    → 锁外由 Manager 同步追加并接纳 Entry
    → 发布所需原始运行时分支视图（分支摘要时）
    → 通知完成并释放接纳
```

模型等待、宿主事件等待和文件 I/O 不在产品生命周期锁内执行。接纳期间禁止别的 run/branch/reload/model change，Manager 仍顺序写入。

可复用一个按需 Session 级维护 executor 承载 reload、手动摘要和恢复后续；自动 transformer 可使用本次 run 的既有执行线程。不为每次摘要建立线程池，也不建设通用任务队列。未启用相关能力、也未请求手动操作时不额外创建 executor。

### 11.2 取消与提交谁先成立

摘要开始时有自己的 CancellationSource。手动/分支摘要不借用已经完成的 Agent run signal；自动摘要连接当前有效 run 的取消；恢复期间使用本次产品操作取消状态，不取消上一轮已结束的 core。

abort() 取消手动摘要时不调用上一轮 Agent.abort。自动摘要期间取消当前产品 run 时同时停止摘要，不再发送下一次正常请求。连接取消的 registration 在操作退出时释放。

提交接纳前取消或 close：不追加检查点。提交已经被接纳并进入同步写入后，不承诺可以撤销；写完的 Entry 仍是事实。迟到取消不能把已提交记录伪装成未提交，也不能在写到一半时人为中断通道来制造回滚。

close 先阻止新接纳并发出协作取消；有在途摘要、提交或产品恢复时延后 Manager/owned Provider/客户端等最终释放。尤其 branch-summary 提交后仍需更新 core 原始分支视图，不能在这个交接中先关闭 core。

对取消不合作的 provider/文件系统，不虚构任务已结束；保持可观察 stage 未完成，直到实际调用和收尾结束。禁止新增后台“稍后清理”服务。

### 11.3 产品摘要事件

增加同一组最小事件：SummaryStarted、SummaryCompleted、SummaryFailed、SummaryCancelled；带 cause=MANUAL/THRESHOLD/OVERFLOW/BRANCH 及必要节点/模型/用量信息。可以沿仓库命名为 Compaction*，但不得为不同 cause 再复制四套协议。

Started 在真正将发摘要请求时触发；无工作跳过只返回结果。Completed 在 Entry 已接纳、必要状态同步之后发出。不要把摘要 stream 转成普通 MessageStarted/Updated/Completed。

事件使用既有 sink/backpressure。payload 不包含工具实例、完整配置或秘密。摘要 Entry 可通过显式历史查询读取，不必在每个进度事件重复传一份全文。

### 11.4 Model 错误与基础设施失败不能混淆

| 故障位置 | 行为 |
|---|---|
| 手动规划无可压缩前缀 | SKIPPED，无调用、无 Entry |
| 手动摘要生成失败/取消 | 结果 stage 失败/取消，历史和当前分支不变 |
| 阈值摘要失败 | 不发送原未压缩正常请求；按现有 transformer 契约形成请求准备 ERROR，或取消下的 ABORTED |
| 摘要生成的 ERROR 本身带 overflow 分类 | 仍是摘要失败，不能递归恢复；不得把该分类复制到正常请求准备 ERROR |
| writer 追加失败 | 原失败向产品传播，保留已接纳前缀；继续遵守第四阶段失效 writer 的前置拒绝 |
| Entry 已提交，完成事件失败 | Entry 保留，产品报告提交后通知失败；不能回滚或自动重复生成 |
| 普通模型恢复尝试又失败 | 保留真实第二次结果，不再自动恢复 |

ContextTransformer 会把同步/异步异常转成普通 assistant ERROR，这是现有 core 契约。[J2] 不能仅在其中抛出 writer/sink 异常，就误以为它仍会以基础设施异常传播。

最小映射：本次产品操作保留一个待传播的基础设施失败引用。自动摘要的 writer/sink 失败先登记该失败；随后的事件适配在落盘任何由 transformer 合成的 ERROR 之前传播原异常，从而使 core run 异常结束，并由既有失败收尾按已接纳历史对齐。不要将原始摘要失败响应当成主对话消息保存。

普通“摘要生成不可用”的失败可沿现有 transformer 归一化路径；真正文件/宿主回调失败不可变成模型失败或静默成功。该引用仅服务于本次操作，不扩展为异常分类框架。

### 11.5 完成时点和资源释放

释放操作接纳、提交历史及必要视图之后再完成公开观察 stage，后续完成回调才可接纳下一操作。事件回调处于操作内部，不能提前宣称 Session idle。

从完成回调调用 close 时不得等待自己的执行线程。沿已有 reload/self-close 处理方式做本地保护，不新增清理线程或强制关闭共享资源。阶段五 owned/borrowed 客户端契约继续有效。

## 12. 溢出错误的最小标准分类

### 12.1 数据入口

建议在现有 ResponseMetadata 增加可选 `failureKind`，值类型为一个最小的 `ModelFailureKind`，首版仅识别 CONTEXT_OVERFLOW；缺省和未知值表示未分类。

保留原三字段构造器、of(...) 和 empty() 等源码兼容入口，新增字段计入 isEmpty，保持无秘密的 toString。Message.Assistant 本身的构造签名不必为此扩一轮；其 metadata 和所有防御性复制、Session codec 必须保留新值。[J6]

这将 ResponseMetadata 从纯关联信息扩展为“关联信息和最小失败分类”，应同步 javadoc/架构约定。不把 raw HTTP、异常、Provider 类名或 compaction 策略塞入该类型。

只有当前返回结果为 StopReason.ERROR 且分类为 CONTEXT_OVERFLOW，产品才考虑恢复。成功、LENGTH、ABORTED 或历史文件里既有的分类，不触发启动时自动调用模型。

### 12.2 Provider 映射依据

OpenAI Responses 的首版正向映射仅接受结构化 error code 等于 `context_length_exceeded`。已有 OpenAiHttpError 的 envelope 以及 OpenAiEventMapper 的 `response.failed`/`error` 处理位置是实际接入点。[J7]

官方 Codex 固定源码在 response.failed 分支使用同名结构化 code 判断 ContextWindowExceeded，可作为 SSE 映射的主依据；官方仓库的用户上报给出了 HTTP error envelope 实例，属于公开报告，不是本次现场复现。[E1][E2]

分类在读取结构化字段时得出，随后只携带标准枚举；不要先把字段拼成诊断字符串再正则提取。保留已有秘密脱敏，不能为分类扩展 raw response 的保存范围。

| 输入 | 分类 |
|---|---|
| 有效 error envelope，code 恰为 context_length_exceeded | CONTEXT_OVERFLOW |
| SSE response.failed 的 response.error.code 命中 | CONTEXT_OVERFLOW |
| 当前支持的 SSE error 事件中相同结构化 code | CONTEXT_OVERFLOW，需固定 fixture 证明映射 |
| HTTP 400/413 但没有上述 code | 未分类 |
| 401/403/429、网络断开、限流、一般 ERROR | 未分类 |
| 文本包含“context”“too long”等，但 code 未命中 | 未分类 |
| response.incomplete / max_output_tokens | 沿原 LENGTH 语义，不是输入上下文溢出 |
| 截断/损坏 envelope 无法可靠读取 code | 未分类，不猜测 |

已有 error body 上限、SSE 解析和兼容 endpoint 约定保留；不额外抓取全部响应，不从 URL 判断是否可以重试，不建立跨厂商错误字典。

### 12.3 分类验证不需要真实付费请求

6A 明确分类契约与兼容影响，6D 交付 HTTP/SSE 正反 fixture 及产品恢复。fixture 要写明来自官方源码行为还是公开错误报告/人工构造；不得把模拟响应写成真实服务实测。

没有可靠映射的 provider 仍可使用手动/阈值压缩，但不会触发 overflow 恢复。不能为了通过验收把所有 ERROR 都标成 overflow。

## 13. 溢出后只恢复一次

### 13.1 启动条件

仅针对本次已接纳产品 prompt/continue 操作内真实返回的、已分类的正常模型错误；还需 enabled=true、模型窗口和预算可用、writer 可写、产品未取消/关闭且本次尚未恢复。

不扫描历史自动找一个失败来恢复；不把 callback、摘要失败或普通异常作为恢复入口。不能用当前 selected model 与旧历史猜出失败发生在哪个请求。

### 13.2 保留失败事实，不重复用户输入

第一次 core 尝试按已有协议完成真实失败消息和工具/turn 事件，失败记录照常持久化。接着在同一个产品操作中规划并生成一次有效压缩。

成功提交后，从“下一次模型调用”继续；不再 prompt 原文本，不倒回已经执行完的工具，不删除第一次失败的 Entry。

### 13.3 core 的窄继续入口

现有 continueRun() 拒绝 assistant 末尾，不能通过偷偷放宽它或临时删除历史来绕开。[J2]

增加类似 `continueAfterFailure()` 的 provider-neutral 窄入口：仅 idle、原始末尾是标准 assistant 且 StopReason.ERROR 时可用；继续同一 transcript 发起下一次模型调用，不重新执行旧 ToolCall。ABORTED、普通成功 assistant 和空历史仍拒绝。

core 不判断 overflow，也不自行重试；只有产品按上述条件显式调用。旧 continueRun 的规则、steering/follow-up 消费顺序及模型参数接纳快照不变。

请求中对终结失败 assistant 的协议投影继续由已有 OpenAiTranscriptPlanner 处理；不要写第二个跨 provider 的历史修复器。新入口保留 raw failure，针对实际 wire fixture 检查未发送协议不接受的失败内容。[J7]

### 13.4 一次产品操作、最多两次 core 尝试

```text
原 prompt/continue
    → core attempt 1（其中可能已有多轮正常工具工作）
    → 若出现已分类 overflow：一次压缩
    → core continueAfterFailure attempt 2
    → 结束
```

“最多一次”限制产品恢复，不是把整个长工具 run 限定成两次模型调用。attempt 2 可正常进行工具循环，但其任何后续 overflow 都不再启动第二次产品恢复。下一次用户主动操作重新计数。

Provider 自己已有的传输重试不计为产品 attempt，也不能借产品恢复形成跨层无限重试。恢复仍使用本次固定模型、thinking、凭证路由和正常 request options；不自动换模型。

### 13.5 产品结果和事件必须统一结算

当前事件适配会将每个 core AgentCompleted 直接转成产品 RunCompleted。[J5] 启用恢复的路径应先保留已映射的尝试结果，完成恢复判断后再发布一次产品 RunCompleted；不能把含工具实例的 LoopResult 暴露给宿主。

真实 RuntimeEvent（含各 attempt 的 Started/Turn/Message/Tool）按原顺序保留；它们可能来自两个 core attempt。摘要事件 cause=OVERFLOW 标明中间阶段，不再发一个假的普通用户消息或中间产品 RunCompleted。

CodingAgentRunResult.newMessages 为本次两个 attempt 的真实新增标准消息按顺序合并，包含第一次真实错误，用户输入只出现一次；summary Entry 不混入 newMessages。finalMessage 是最终实际正常模型结果，未恢复时仍是第一次 ERROR。[J5]

恢复摘要失败/无可行计划/不足以缩减：发相应摘要诊断，原失败结果作为产品结果，不调用第二次正常模型。文件或宿主事件基础设施失败：产品 stage 异常，不伪装成原 ERROR 的正常完成。

在两次 attempt 之间取消：不启动 attempt 2，已接纳历史保留，产品 stage 以 CancellationException 结束；不为凑出 aborted=true 再伪造主对话 assistant。正在 core 内的取消仍沿其既有 ABORTED 消息规则。

第二次尝试开始前和提交/完成边界都检查本次产品取消状态。产品 running 接纳从第一次开始到最后结算不释放，禁止恢复间隙被另一个 prompt/model switch 插入。

不要在核心完成回调里 join 尚待执行的下一次 run；使用既有 CompletionStage 组合及按需维护 executor。一个操作内的恢复已用标志、尝试结果和取消状态即可，不建立可扩展重试调度器。

## 14. 显式分支摘要

### 14.1 收集与生成

固定 fromId 和 targetId；在各自 root→leaf 路径中找到最深共同祖先，只总结 fromId 回溯到共同祖先之后的离开部分。目标相同、没有旧位置或无可见离开内容时不调用模型，按普通分支或 no-op 返回。[P3]

收集过程不能遇到 CompactionEntry 就直接停止；已有摘要和它之后的内容可能是重要材料。可以复用有效摘要视图减少重复，但不能遗漏离开路径中实际存在的未被该摘要覆盖的工作。

按可用摘要输入预算从近期向前保留完整材料单元，最终按时间顺序发送。允许带标记省略较早离开内容；这与一般压缩不得悄悄丢原任务的规则不同，因为分支摘要是明确、有限范围的迁移摘要。[P3]

复用同一 SummaryGenerator、结构化提示和文件线索。工具结果按第 9 节有界保留，尤其保留失败/未验证信息，不建设另一套记忆生成器。

### 14.2 写入目标，但先不切走

生成和候选校验期间当前位置仍是 fromId。Manager 增加一个窄的“在指定既有父节点追加 BranchSummaryEntry 并接纳为 leaf”操作；不要先调用公开 branch(target) 再开始等待摘要。

写文件成功后才接纳 Entry 与新 leaf，并按目标分支更新历史派生 model/thinking；再同步 core 的原始目标分支消息。阶段五当前实际 ModelSelection 和队列保持不变，历史配置不能趁此切换当前模型。

提交前失败/取消保留原 leaf；文件写失败遵守原 writer 规则。提交后通知失败保留新 Entry 和 leaf，明确可查询 committedEntryId，不声称“分支未发生”。

目标上可能已有自己的 CompactionEntry，应沿目标路径投影，再追加新 BranchSummary；不能把来源分支的压缩边界强行套到目标路径。

模型生成摘要只是传递上下文，不回滚、复制或合并工作区文件。普通 branch() 仍然是无摘要、无网络的原操作。

## 15. 公开结果、失败和可观察性

### 15.1 最小结果形状

手动 CompactionResult 区分 COMPACTED 与 SKIPPED；成功包含新 Entry id、保留边界、前后估计及来源、摘要 Usage。SKIPPED 只用于没有需要总结的前缀等无工作情形，不把生成失败、未知窗口或写入失败标成成功跳过。

BranchSummaryResult 包含原位置、目标位置、最终 leaf 和可选摘要 Entry id。没有离开内容时可完成普通分支且无摘要 Entry，须明确标识没有调用模型。

模型限制未知、预算非法、生成失败、取消和 I/O 使用明确错误/异常语义。错误只需类别、相关 Entry id 及是否已有提交；不要返回可变 Manager、Provider 私有配置或 raw parser/provider 异常。

摘要事件可以带 Usage 与非秘密 ModelRef，但普通 RunResult 不将摘要调用伪装成正常 assistant。真实付费统计由 Usage 的实际产生方区分，不能把摘要输入总量当作压缩后的窗口实测。

### 15.2 只承诺接纳一致，不制造跨文件事务

新接纳的 prompt/branch/model switch 必须看到提交后的同一状态。多个独立只读 getter 在并发期间不承诺组成事务快照，宿主应使用已有快照 API。

Session JSONL 和用户 settings 各自提交；手动压缩、设置 enabled、保存默认值不是一个事务。保存偏好失败不撤回已有摘要，模型调用收费也不因 Entry 未提交而消失。

### 15.3 必须保留的非目标

不做摘要内容审查、项目指令转义、风险评级、文件变更验证或密钥扫描。只检查结构和能否作为完整检查点：正常终结、非空文本、无工具调用、合法祖先边界，以及必要的预算结果。

不能为了可观察性引入全文日志。用户显式查询历史可看到摘要和原始材料；默认事件/异常仅提供必要状态。模型可能遗漏信息，应允许用户查询原历史，而不是声称摘要能替代全部事实。

## 16. 实施切片与依赖顺序

| 切片 | 交付内容 | 本批完成证据 | 禁止顺带扩展 |
|---|---|---|---|
| **6A：数据、投影与用量** | 两类 Entry/codec、raw 与 request view、连续压缩重建、模型限制接入、估算器、最小错误分类契约 | 不调用模型即可证明正确父链、旧文件兼容、不同分支隔离、usage 不双算；兼容构造可编译 | 不增加 tokenizer 平台、历史迁移框架或摘要存储 |
| **6B：手动压缩** | 切点、split turn、一次摘要请求、候选验证、提交、取消、结果事件 | fake ModelClient 完成手动闭环；真实原 Entry/字节未被改写；失败不提交 | 不增加子 Agent、工具执行或摘要重试 |
| **6C：阈值压缩** | 每个正常请求前接入、长工具循环、配置继承、关闭协调 | 同一 prompt 内发生压缩并继续；终止工具批次不多发摘要；失败不发原始大请求 | 不新增定时器、全局冷却表或通用 scheduler |
| **6D：分类后的单次恢复** | HTTP/SSE 分类映射、core 窄继续、一次恢复、产品结果/事件聚合 | 正向 overflow 恢复；普通错误不恢复；第二次溢出停止；工具执行次数不增加 | 不扩大全量错误体系，不做跨模型/跨凭证 failover |
| **6E：分支摘要与收口** | 共同祖先、带摘要分支、写入目标、配置保存和文档 | 保存→重开→再压缩→分支→继续的端到端闭环；原分支可查 | 不变更普通 branch 的无网络语义，不引入跨会话 fork |

6A 中先完成错误字段的源码兼容设计；6D 再加入实际 Provider 映射及恢复。每批有可运行测试，不在 6A 创建尚无消费者的通用 hook。

全部切片完成且验证证据齐全后才标记阶段六完成。不得把“输出了一段摘要”或“创建了两个 Entry 类型”当作收口证据。

## 17. 验收矩阵与关键 fixture

### 17.1 最小行为矩阵

| 类别 | 必须证明的结果 |
|---|---|
| 原 API | 不配置自动压缩时无额外摘要请求；旧构造、工具权限、AGENTS 行为不变 |
| 文件兼容 | 旧五类 Entry 文件继续读取；两类新记录往返；坏祖先边界失败；未知类型不静默跳过 |
| 请求隔离 | summary 只出现在请求视图，不进入 raw 标准消息、普通 newMessages 或普通消息事件 |
| 连续压缩 | 第二次正确包含上次保留且本次被裁去的消息，不漏掉 compaction Entry 之前的保留区间 |
| 父链隔离 | 文件里更晚但不在当前祖先链的摘要不得生效；metadata 不作为正文边界 |
| 相同内容 | 两条内容完全相同、id 不同的消息不会被误定位或误删除 |
| usage | cache/输出/推理量不双算；全零/失败 usage 不当基线；摘要 usage 不替代正常观测 |
| 观测失效 | 压缩、reload、setModel、branch/reset、open 后不沿用旧请求实测值 |
| 全量估算 | system、实际 tools/schema、参数、图片占位及有效消息都被考虑，结果标为估算 |
| Profile | SDK/file/borrowed 传入真实 Session；未知不猜；小窗口预算错误不隐式改参数 |
| 配置 | compaction 叶字段继承与 REMOVE、false 显式覆盖、tools 的 effectiveOverrides 不丢新增字段 |
| 手动 | idle 接纳；摘要后可继续；无前缀无调用；失败/取消/非法结果不提交 |
| 长单任务 | 在一个 user 任务内部找到合法切点，不能只依赖第二条 user 才能压缩 |
| 并行工具 | 完成顺序与源顺序相反时，保留完整调用组及结果；旧调用不被重放 |
| 摘要请求 | 无 tools，不继承 REQUIRED/Specific，不继承普通 temperature/output/cache key；只有本阶段摘要预算 |
| 摘要材料 | 工具结果仅在摘要输入截短，历史原值和近期保留值不变；不发送 base64/replay 密文作为摘要正文 |
| 摘要结果 | STOP 非空文本成功；ERROR/ABORTED/LENGTH/TOOL_CALL/空内容失败；不按标题格式评分 |
| 阈值时点 | 新 prompt、continue、steering/follow-up、工具循环均覆盖；没有下一请求时不为完成事件压缩 |
| 防重入 | 同一个 transformer 最多一次；刚提交检查点后无新增内容不重复生成；不足以减少就停止 |
| 分类兼容 | ResponseMetadata 老构造/empty/of 行为保留；新字段所有复制/codec 保留；未知分类不恢复 |
| 正向分类 | HTTP error envelope、SSE response.failed/error 命中明确 code；同等测试通过真实 adapter 路径 |
| 负向分类 | HTTP 400 无 code、鉴权/网络/限流、仅文案匹配、LENGTH、摘要自身 overflow 均不触发 |
| 恢复次数 | 一次产品操作最多一次恢复；第二次失败不再 compact；新用户操作不会被永久禁用 |
| 恢复内容 | 不再追加同一 user，不重放已执行工具；sourceModel/replay 原值保留 |
| 产品结算 | 一次正常完成的逻辑操作只一个 RunCompleted；newMessages 顺序含真实两次结果且无摘要伪消息 |
| 回调失败 | Started sink 失败不发摘要；writer/sink 失败不被普通 transformer ERROR 吞掉；提交后失败可查 Entry |
| 取消关闭 | 手动取消不碰已结束 run；恢复间隙取消不发第二请求；迟到结果不在关闭后新提交 |
| 分支摘要 | 共同祖先排除、fromId/parentId 正确、目标自身摘要生效；失败时不先移动 leaf |
| 资源 | 保留 writer/owned client 到真实收尾；借用 Models 不被关闭；完成回调 close 不等待自己 |
| 全链路 | 自动压缩→普通回答→关闭→打开→第二次压缩→带摘要分支→切模型继续，历史与请求均可解释 |

### 17.2 必须先写清的四条时序 fixture

**F1：长工具循环。** fake 正常模型先输出工具调用；两个受控工具以反序完成，历史仍按调用源顺序接纳。下一次正常模型请求之前触发摘要；摘要成功后该 run 继续。断言工具执行次数、最终 wire 消息顺序以及所有原结果仍在 JSONL。另设 terminate=true 的末批工具，若无待处理消息，断言没有多余摘要调用。

**F2：溢出但不重做工作。** 正常请求先完成一次工具操作；随后模型结构化 overflow；摘要成功；失败后继续成功。assert 原 user 只一次、工具只执行一次、失败 Entry 保留、一个产品 RunCompleted、最终结果来自恢复尝试。替换为第二次又 overflow，assert 不发生第二次恢复。

**F3：连续压缩跨旧边界。** 固定 C1 firstKept=U1，继续 U3/A3，再生成 C2 firstKept=U2。断言 U1/A1 出现在 C2 摘要材料，不因位于 C1 Entry 之前被遗漏；最终正常请求只含 C2 summary 及 U2 以后保留内容。

**F4：提交后回调失败。** 使 SummaryCompleted sink 失败，确认检查点/新 branch leaf 已存在；公开 stage 报基础设施失败；下一操作或重开不会重复写同一检查点，raw transcript 不混入摘要模型响应。另设写入本身失败，确认没有接纳失败记录且 writer 后续在模型调用前拒绝。

### 17.3 测试方法约束

用固定 Clock、可控 ModelClient、本地 HTTP/SSE fixture、现有 gate/latch/future 验证。通过 message purpose/固定摘要 system prompt 区分摘要请求与正常请求，不只依赖“第 N 次调用猜用途”。

测试不依赖真实 API Key 或账单，不用随机模型文字断言摘要质量。不以 sleep、自旋或 Started 事件证明 Completed；完成信号在资源和提交真正结束后发布。

被摘要的原始消息通过 Entry id、内容、replay、工具调用 id 与原文件前缀字节对照。不要把已经截短的摘要输入误当成原始工具结果来比较。

对不可避免的源码兼容扩展，补编译用例并注明不承诺 Java record 的二进制 ABI 完全不变。不要为测试公开内部 Agent/Manager 或增加可插拔持久化框架。

## 18. 可复制的配置、调用顺序与验证命令

### 18.1 配置示例

以下字段已由分层设置加载器支持。模型 W=8192 仅为固定示例规格，不代表任何在线模型。

```json
{
  "compaction": {
    "enabled": true,
    "reserveTokens": 2048,
    "keepRecentTokens": 3072
  }
}
```

设置仍按内建→全局→已授权项目→SDK 解析。ModelProfile 在用户级 models 定义或 SDK 配置中给出；项目 settings 不能自行更改模型规格、endpoint 或凭证。

关闭自动压缩使用 `enabled:false`；显式保存与当前配置修改按第五阶段规则区分。没有监听器自动让运行 Session 跟着文件变化。

### 18.2 调用顺序

```java
static void maintainSession(
        site.pplee.jcode.codingagent.CodingAgentSession session,
        String targetEntryId
) {
    session.prompt("继续检查实现").toCompletableFuture().join();

    var compacted = session.compact("重点保留未完成事项和验证结果")
            .toCompletableFuture().join();

    var switched = session.branchWithSummary(targetEntryId, "保留本分支的关键结论")
            .toCompletableFuture().join();

    session.prompt("按新的方向继续").toCompletableFuture().join();
}
```

Session 的创建方式、ModelProfile 和预算配置见 README 的 headless 装配示例；借入的 Models/Provider 仍由外层调用方关闭。

### 18.3 运行命令

```bash
mvn -pl ai -am test
mvn -pl ai-providers -am test
mvn -pl agent-core -am test
mvn -pl coding-agent -am test
mvn clean verify

mvn -pl coding-agent -am verify -Plocal-tools-smoke \
  -Djcode.test.bash=/absolute/path/to/bash \
  -Djcode.test.rg=/absolute/path/to/rg \
  -Djcode.test.fd=/absolute/path/to/fd

git diff --check
```

严格 smoke 是既有工具验证的一部分，不等于所有新增协议行为都被覆盖；需同时记录本章定向 fixture 的执行结果。不得把某个子模块测试数写成全仓结果。

真实 provider probe 仅是补充证据，不作为必须消耗真实服务额度的门槛。第一次实施尚无结果，本文不预填测试数量或“通过”。

## 19. 总路线图、文档与审查收口

| 既有口径/潜在歧义 | 本阶段明确规则 |
|---|---|
| Compaction 在 ContextTransformer 中重建 | raw transcript 与 request view 分离；自动检查集中在实际模型调用前 |
| 阈值触发 | 单个 transformer 一次，新的真实内容可再次触发；不靠 cooldown/永久禁用 |
| 不配对工具不能出现 | 不由截断新增坏配对；既有不完整历史仍归 Provider 请求投影，不自动重放 |
| overflow 单次恢复 | 最小标准分类 + 一个窄的失败后继续入口 + 一次产品结果结算 |
| ERROR 可以触发恢复 | 只有当前正常模型 ERROR 且已分类为 CONTEXT_OVERFLOW；其他情况不恢复 |
| 分支摘要随阶段六支持 | 显式 branchWithSummary 闭环，不改变原 branch 的无网络语义 |
| 关闭自动压缩 | 禁止生成，不禁止解释已有检查点 |
| 摘要完整性 | 只提交完整终结的非空文本；有损概括不等于原历史等价 |
| Session 格式扩展 | 同一 version 1 加显式新 Entry；新 reader 向后读，旧 reader 对新类型失败 |
| 摘要 usage | 单独的模型消耗，不作主请求压缩后实测占用 |
| 模型窗口 | 使用第五阶段真实 Profile，未知保持未知，切模型/开文件重新判断 |

更新总路线图、coding-agent/ai/agent-core/ai-providers 的 AGENTS 中确实变化的契约、runtime-contracts、公共数据说明、README 和可编译 headless 示例。架构依赖不变，错误分类映射和窄继续入口的职责需注明。

正文保留开发计划，收口时移动到 `docs/plans/archived/`，在文首给出现行规则和验证记录位置。实现偏离本计划时，先写清 pi 对应行为、Jcode 实际需要与最小差异，不用新测试将未经解释的额外机制固化。

收口条件：五个切片均有实际行为与确定性测试；普通历史不被改写；长工具 run 可以压缩继续；溢出仅恢复一次；分支和重开重建正确；资源/取消/事件真实结束；前五阶段回归、模块边界和严格 native 均有最新代码状态的执行记录。

## 20. 固定参考与实际查阅依据

文中 [P*] 是固定 pi 行为，[J*] 是 Jcode 既有源码/契约，[E*] 是错误分类的外部证据。建议和范围决策不冒充原实现。文件路径/方法名用于定位，不把会随编辑变化的行号作为验收契约。

| 编号 | 固定来源 | 用途 |
|---|---|---|
| P1 | [pi compaction.ts](https://github.com/earendil-works/pi/blob/60e7e76bd7ea25cad1dd6f3f1ce0d18814a42759/packages/coding-agent/src/core/compaction/compaction.ts) | usage、shouldCompact、findCutPoint、prepareCompaction、摘要终结校验、split turn 和默认预算 |
| P2 | [pi session-manager.ts](https://github.com/earendil-works/pi/blob/60e7e76bd7ea25cad1dd6f3f1ce0d18814a42759/packages/coding-agent/src/core/session-manager.ts) | CompactionEntry/BranchSummaryEntry、buildContextEntries/buildSessionContext、父链投影 |
| P3 | [pi branch-summarization.ts](https://github.com/earendil-works/pi/blob/60e7e76bd7ea25cad1dd6f3f1ce0d18814a42759/packages/coding-agent/src/core/compaction/branch-summarization.ts) | 共同祖先、离开路径、近期输入预算和分支摘要 |
| P4 | [pi compaction/utils.ts](https://github.com/earendil-works/pi/blob/60e7e76bd7ea25cad1dd6f3f1ce0d18814a42759/packages/coding-agent/src/core/compaction/utils.ts) | 对话序列化、工具结果截短和历史文件线索 |
| P5 | [pi compaction 文档](https://github.com/earendil-works/pi/blob/60e7e76bd7ea25cad1dd6f3f1ce0d18814a42759/packages/coding-agent/docs/compaction.md) | 检查位置、连续压缩、同一 run 内继续及分支摘要的整体说明 |
| J1 | [Jcode 总路线图](https://github.com/YPQuinn/Jcode/blob/c669e53f2e1efa6f57a698771613cbf634e6fde9/docs/plans/coding-agent-development-roadmap.md) | 阶段四后移项、第六阶段完整范围、溢出分类前置依赖 |
| J2 | [ContextTransformer.java](https://github.com/YPQuinn/Jcode/blob/c669e53f2e1efa6f57a698771613cbf634e6fde9/agent-core/src/main/java/site/pplee/jcode/agentcore/message/ContextTransformer.java)；[AgentLoop.java](https://github.com/YPQuinn/Jcode/blob/c669e53f2e1efa6f57a698771613cbf634e6fde9/agent-core/src/main/java/site/pplee/jcode/agentcore/AgentLoop.java)；[Agent.java](https://github.com/YPQuinn/Jcode/blob/c669e53f2e1efa6f57a698771613cbf634e6fde9/agent-core/src/main/java/site/pplee/jcode/agentcore/Agent.java) | request-local 隔离、投影错误归一化、工具结果/队列时序、continue 拒绝 assistant、run 接纳 |
| J3 | [SessionManager.java](https://github.com/YPQuinn/Jcode/blob/c669e53f2e1efa6f57a698771613cbf634e6fde9/coding-agent/src/main/java/site/pplee/jcode/codingagent/SessionManager.java)；[SessionContextBuilder.java](https://github.com/YPQuinn/Jcode/blob/c669e53f2e1efa6f57a698771613cbf634e6fde9/coding-agent/src/main/java/site/pplee/jcode/codingagent/session/SessionContextBuilder.java)；[SessionCodec.java](https://github.com/YPQuinn/Jcode/blob/c669e53f2e1efa6f57a698771613cbf634e6fde9/coding-agent/src/main/java/site/pplee/jcode/codingagent/SessionCodec.java) | 唯一历史所有者、先写后接纳、当前 raw 构建、version 1 显式类型分派 |
| J4 | [ModelRuntime.java](https://github.com/YPQuinn/Jcode/blob/c669e53f2e1efa6f57a698771613cbf634e6fde9/coding-agent/src/main/java/site/pplee/jcode/codingagent/model/ModelRuntime.java)；[ModelProfile.java](https://github.com/YPQuinn/Jcode/blob/c669e53f2e1efa6f57a698771613cbf634e6fde9/coding-agent/src/main/java/site/pplee/jcode/codingagent/model/ModelProfile.java)；[CodingAgentSessionFactory.java](https://github.com/YPQuinn/Jcode/blob/c669e53f2e1efa6f57a698771613cbf634e6fde9/coding-agent/src/main/java/site/pplee/jcode/codingagent/CodingAgentSessionFactory.java) | 模型限制到 Session 的装配、原显式路径与新工厂、Settings 覆盖衔接 |
| J5 | [CodingAgentSession.java](https://github.com/YPQuinn/Jcode/blob/c669e53f2e1efa6f57a698771613cbf634e6fde9/coding-agent/src/main/java/site/pplee/jcode/codingagent/CodingAgentSession.java)；[CodingAgentRunResult.java](https://github.com/YPQuinn/Jcode/blob/c669e53f2e1efa6f57a698771613cbf634e6fde9/coding-agent/src/main/java/site/pplee/jcode/codingagent/CodingAgentRunResult.java)；[PromptCacheOptions.java](https://github.com/YPQuinn/Jcode/blob/c669e53f2e1efa6f57a698771613cbf634e6fde9/ai/src/main/java/site/pplee/jcode/ai/client/PromptCacheOptions.java) | 产品接纳、MessageCompleted 保存、完成事件/结果、关闭、NONE 缓存语义 |
| J6 | [Usage.java](https://github.com/YPQuinn/Jcode/blob/c669e53f2e1efa6f57a698771613cbf634e6fde9/ai/src/main/java/site/pplee/jcode/ai/message/Usage.java)；[ResponseMetadata.java](https://github.com/YPQuinn/Jcode/blob/c669e53f2e1efa6f57a698771613cbf634e6fde9/ai/src/main/java/site/pplee/jcode/ai/message/ResponseMetadata.java)；[StopReason.java](https://github.com/YPQuinn/Jcode/blob/c669e53f2e1efa6f57a698771613cbf634e6fde9/ai/src/main/java/site/pplee/jcode/ai/message/StopReason.java) | 缓存/推理计数、元数据兼容、STOP/TOOL_CALL/LENGTH/ERROR/ABORTED |
| J7 | [OpenAiHttpError.java](https://github.com/YPQuinn/Jcode/blob/c669e53f2e1efa6f57a698771613cbf634e6fde9/ai-providers/src/main/java/site/pplee/jcode/aiproviders/openai/OpenAiHttpError.java)；[OpenAiEventMapper.java](https://github.com/YPQuinn/Jcode/blob/c669e53f2e1efa6f57a698771613cbf634e6fde9/ai-providers/src/main/java/site/pplee/jcode/aiproviders/openai/OpenAiEventMapper.java)；[OpenAiTranscriptPlanner.java](https://github.com/YPQuinn/Jcode/blob/c669e53f2e1efa6f57a698771613cbf634e6fde9/ai-providers/src/main/java/site/pplee/jcode/aiproviders/openai/OpenAiTranscriptPlanner.java) | HTTP/SSE 分类接入、已规范化 usage、终结失败/工具配对的既有请求投影 |
| E1 | [OpenAI Codex responses.rs，固定 fa8cf449](https://github.com/openai/codex/blob/fa8cf449858c7fffc83d9e3604894852344962a1/codex-rs/codex-api/src/sse/responses.rs) | response.failed 与 is_context_window_error 对 context_length_exceeded 的结构化判断；只借错误语义，不移植 Codex 架构 |
| E2 | [OpenAI Codex 公开错误报告 #22890](https://github.com/openai/codex/issues/22890) | context_length_exceeded error envelope 实例；用户上报，不等同本次真实调用验证，也不作为所有兼容 endpoint 的保证 |

外部错误说明查阅日期为 2026-09-22。实施时将所用结构化正反样本固定为 fixture；不得依赖公开 issue 后续编辑或浮动 SDK 内容决定运行行为。

## 21. 实施记录

- 实施环境：基于 `c669e53f2e1efa6f57a698771613cbf634e6fde9` 工作树；Darwin arm64、Oracle JDK 21.0.10、Maven 3.9.14。主体实现提交为 `cce8a4abea62a31c35da5930b7c69342889f78c2`，其后完成本节记录的审查修复。
- 6A～6E 均已完成：加入最小失败分类和失败后窄继续入口；实现用量估算、切点规划、专用摘要请求、手动/阈值压缩、连续检查点、单次 overflow 恢复和分支摘要；Session version 1 以显式新 Entry 类型扩展，原消息保持 append-only。
- 实际差异：split turn 依计划使用一次摘要请求；分支摘要在输入超预算时按完整消息单元从旧到新裁掉较早材料并写入省略标记。自动压缩维持默认关闭，已存在检查点始终参与请求视图重建。
- 定向 fixture：HTTP/SSE 仅对结构化 `context_length_exceeded` 分类，文本命中保持未分类；产品 fixture 覆盖手动、阈值、连续压缩、单次 overflow 恢复、分支近期预算、完成事件失败、取消与关闭；Unicode 工具结果按 code point 截断。
- 全仓校验：`mvn clean verify` 通过；五个 reactor 模块 Enforcer 均通过。`ai` 71、`ai-providers` 269、`agent-core` 209、`coding-agent` 342，共 891 个测试，无 skip、失败或错误。
- 严格 native smoke：使用 `/bin/bash`、Codex 随附的绝对 `rg` 路径和 `/opt/homebrew/bin/fd` 执行第 18.3 节命令，通过；严格 profile 的 342 个 `coding-agent` 测试无 skip。为满足该检查，本机通过 Homebrew 安装了 fd 10.5.0。
- Session 兼容与生命周期：codec fixture 验证旧记录缺少 failureKind 时仍可读取、新摘要 Entry round-trip；请求视图 fixture 验证最新检查点和分支隔离；产品 fixture 验证 abort/close 取消在途摘要且提交前不追加记录，提交后事件失败保留 Entry 并释放接纳。
- 审查修复：分支摘要提交后按目标父链刷新历史 model/thinking 派生状态；候选压缩复用真实请求投影并以同一全量估算器比较，旧检查点后没有新可见内容时直接跳过；一次产品操作的取消状态贯穿 overflow 摘要与第二次尝试。
- 基础设施与关闭：摘要 writer 和宿主事件边界保留原失败，THRESHOLD/OVERFLOW 不再把提交前写入失败或提交后通知失败归一成模型错误；已知失效 writer 在摘要模型调用前拒绝。摘要追加在产品生命周期锁外执行，close 对尚未终结的非合作摘要流延后 core、writer 与 owned resource 释放。
- 审查回归：覆盖混合 model/thinking 的分支摘要继续与重开、usage/估算口径、重复压缩、overflow 事件内取消、F1 并行工具反序完成后的阈值压缩、F2 工具只执行一次的 overflow 恢复、F3 旧保留消息进入下一次摘要材料，以及手动/THRESHOLD/OVERFLOW 的提交前后基础设施失败。
- 静态检查：`git diff --check` 通过；未修改用户预先存在的 `.idea/encodings.xml` 与 `.idea/vcs.xml` 变更。
- 未执行真实 Provider probe，避免消耗真实凭证和额度；错误分类证据来自本地 HTTP/SSE fixture，因此不对其他兼容 endpoint 的未识别错误码作保证。
