# Coding Agent 第七阶段：Resource 与显式 Extension

> 状态：已完成并归档。
>
> 编制日期：2026-09-22。
>
> Jcode 实施基线：`a1964e51f19b5a7ca5a6ed75870b55284b11c682`。编制时重新读取远端 `master`，仍为该第六阶段收口提交。
>
> pi 对标基线：`60e7e76bd7ea25cad1dd6f3f1ce0d18814a42759`。不跟随 pi 的浮动分支改变本阶段验收语义。
>
> 归档路径：`docs/plans/archived/coding-agent-phase-7-resources-extensions.md`。
>
> 关联路线图：`docs/plans/coding-agent-development-roadmap.md`。需同步的旧口径见第 22 节。
>
> 本文保留实施前的行为约束和接口草案；第 24 节记录实际实现与验证结果。本次工作树未创建 Git 提交或推送。

## 1. 目标与完成标准

### 1.1 完成资源和扩展的使用闭环

```text
宿主显式启用资源、提供 Java Extension 实例
    → 发现 Skill / Template / SYSTEM 文本并记录来源
    → 构造真实工具与 Skill 目录对应的 system prompt
    → 明确展开文本或调用扩展命令
    → 使用既有模型、工具执行和 policy 链路
    → 必要扩展记录进入同一 Session 树
    → 保存、重开、分支和压缩后仍可解释
    → idle 时重新加载文本资源，不重载 Java 代码
```

本阶段让已有 headless 内核具备可用扩展入口，不开发插件市场、长期记忆平台或另一个运行时。[J1]

### 1.2 必须交付

| 能力 | 完成含义 |
|---|---|
| ResourceSnapshot | 汇总 Skill、模板、SYSTEM/APPEND、既有项目 context 和结构化 diagnostics |
| Skill | 发现、frontmatter、模型目录、显式展开、按需读取及来源/同名诊断 |
| Prompt Template | 文件发现、参数替换、缺参诊断、显式展开，保存实际入模文本 |
| Java Extension | 宿主显式实例注册；工具、命令、上下文变换、生命周期和事件观察 |
| 扩展历史 | `custom` / `custom_message` 的实际生产、保存、重开、查询和分支行为 |
| Compaction 衔接 | 可见 custom 消息参与请求与摘要，纯扩展状态不入模，临时 hook 结果不偷存 |
| Project trust | 保留 settings 原授权；增加实际使用的文本资源范围，不扩大旧授权 |
| Reload | idle 原子发布文本资源及 prompt；扩展集合保持不变，收到资源更新通知 |
| 关闭与错误 | 复用真实收尾、借入资源所有权、backpressure 及明确的失败分类 |

所有能力必须有真实消费者。只增加接口、Entry 或演示注册表，不算相应能力完成。

### 1.3 明确不做

不增加 TUI/主题/快捷键/对话框、CLI 输入循环、RPC 服务、包下载/安装/依赖解析、远端 Skill 仓库、MCP、新 Provider、OAuth、Extension 自定义 Provider wire hook。

不扫描 classpath，不使用 `ServiceLoader`，不按项目文件中的类名实例化对象，不执行项目 jar/JS/Python，不做 Java 代码热替换。Skill 的 `scripts/` 不因发现或展开被执行。

不增加签名、内容哈希、资源风险评分、权限交集引擎、通用策略 SPI、多维预算、后台监听、扩展熔断/自动重试、全局事件调度器或新存储格式分支。

不修改前六阶段已稳定的工具顺序、历史单写者、模型配置、压缩提交与真实关闭规则。没有新的具体失败用例，不顺带重构相关机制。

## 2. pi 如何做 → Jcode 如何映射 → 必要差异

| 议题 | 固定 pi 行为 | Jcode 映射 | 差异性质与理由 |
|---|---|---|---|
| 资源入口 | Loader 汇总 skills、prompts、context、system 与动态扩展等 [P1] | 文本资源快照 + 独立显式 ExtensionRunner | 本阶段无包管理/UI/动态代码，不复制其子系统 |
| 项目指令 | 独立发现后交给 Prompt Builder [P1] | 委托既有 ProjectContextLoader | 不重写阶段三的祖先、候选、worktree 行为 |
| Skill | 元信息入 prompt，正文按需读取，禁自动调用不禁显式使用 [P2] | 相同分层；读取复用真实 read/bash 能力 | 不新增 activation tool 或 Skill 执行引擎 |
| 格式校验 | 名称等问题可告警，缺失描述才不加载 [P2] | 保留可用性与诊断区分 | 不把格式建议升级成全面硬拒绝 |
| 模板 | 位置参数、全部参数、默认值、切片；单次非递归替换 [P3] | 一个小型替换器及缺参结果 | 不引入模板语言/脚本；缺参仍按 pi 为空 |
| 同名资源 | 有序加载后 first-wins，报告 collision [P1][P2] | 稳定候选序列、完整资源选择 | 不使用 settings 的逐字段覆盖逻辑 |
| SYSTEM 文件 | 可信项目优先全局，显式值可替代发现 [P1] | SDK 明确文本/文件 > 已授权项目 > 用户目录 | 字符串不再靠“恰好是文件路径”猜类型 |
| 输入路由 | pi 有 slash 入口与命令/资源展开 [P3][P4] | 显式文本展开及命令 API；普通 prompt 不解释 slash | 保留 Jcode 既有文本 API，避免隐藏执行 |
| 扩展代码 | pi 支持工厂和文件扩展 [P1][P4] | 宿主显式提供实例、贡献一次固定 | Java 装配边界；不声称支持 TS 兼容或热替换 |
| 扩展工具 | 接入既有工具调用与 hook [P4] | 直接复用 AgentTool 和 CodingToolPolicyAdapter | 不再包装另一套工具运行/授权协议 |
| 冲突命令 | pi 可生成带序号调用名 [P4] | 使用 extensionId + commandName 二元标识 | 无 TUI 命令补全，不复制自动重命名机制 |
| Context hook | 顺序变换请求消息，普通异常报告后继续 [P4] | 对有效基础视图运行一次；保留上一个成功输出 | 普通 hook 不是强制权限入口，不改 raw 历史 |
| 自定义历史 | custom 不入模，custom_message 可见 [P5] | 两类固定信封 + 一种受支持 CustomAgentMessage | 不序列化任意 Java 类，不要求扩展在场才能读档 |
| 授信 | pi 的项目授信影响设置及资源 [P1] | 复用既有 store，仅增加 TEXT_RESOURCES 范围 | 旧 settings 授权不得自动扩大；本阶段无执行型项目装载 |
| Reload | pi 可重载资源及动态扩展 [P1] | 只更新文本资源；Java 实例/工具/命令固定 | 明确范围收敛，变更扩展集合需重建 Session |
| 关闭 | runner/Session 管理生命周期 [P4] | 借入实例不自动 close，Session 自有工作真实结束后通知关闭 | 不把“注册”当成取得宿主对象所有权 |

上述差异必须在实施记录中保留。不能用“Java 更安全”“未来方便”替代具体原因；本计划也不要求逐行复制 pi。

## 3. 新入口与旧 API 的兼容边界

### 3.1 默认不增加发现和执行

旧 `CodingAgentConfig` 构造重载、Session create/open、`prompt(String)`、`continueRun()` 保持原语义。未显式启用资源时，不因提供了 userConfigDirectory 就扫描 Skill、模板或 SYSTEM 文件；未提供 extensions 时，无扩展回调。

既有 ProjectContextConfig 独立生效。资源总开关关闭，不关闭已经由阶段三显式启用的 AGENTS；不要重新解释旧配置。[J2][J3]

### 3.2 一个定制配置入口，不散落大量新参数

建议在完整配置中增加一个不可变组合值 `CustomizationConfig`，内容仅为 `ResourceConfig` 和有序 Extension 实例列表。旧 canonical 形状提供兼容重载，默认空定制；工厂 Options 可提供 `.resources(...)` 与 `.extensions(...)` 便利入口，并汇入同一个组合值。

ResourceConfig 只表达：是否启用、用户资源目录、是否包含默认目录、有序显式 Skill/模板路径、可选显式 SYSTEM/APPEND 文件，以及文本资源授信的显式输入/用户 store 位置。最终名称可调整，但每项只允许一个事实来源。


| ResourceConfig 输入 | 缺省/解释 |
|---|---|
| enabled | false；控制本阶段新增的文件资源发现，不控制宿主已显式提供的 Java Extension |
| includeDefaults | true，但只有 enabled=true 时才使用默认目录 |
| userDirectory | 未提供则无用户默认来源，不推导 home；工厂可在显式启用后采用已提供的 userConfigDirectory |
| skillPaths / promptPaths | 空列表；非空时按宿主顺序读取，不解释 URL/包坐标 |
| systemPromptFile / appendSystemPromptFile | 未提供；只接收显式 Path，不从文本内容猜路径 |
| projectTextResources | UNSPECIFIED；只属于 TEXT_RESOURCES，不复用旧 projectTrust 的 SETTINGS 值 |

includeDefaults=false 时不发现用户/项目默认 Skill、模板和 SYSTEM/APPEND，仅使用显式路径；enabled=false 时连上述显式文件也不读取。既有 SDK 内联 prompt 字符串与 ProjectContextConfig 不受此开关影响。

trust store 始终是宿主指定的用户配置来源；不因为 userDirectory 指向一个资源包，就读取该资源包里的 trust.json。直接配置和工厂须得到同样的范围决定，不能只有工厂支持受信项目资源。

本阶段不把资源路径写入普通 settings。路径仅来自宿主显式配置；由项目 settings 注入扫描路径、包或扩展类均不支持。这避免为了资源启用再扩一套字段保存、路径重映射与授权递归。

工厂路径在显式开启 resources 后，可以使用已传入的 userConfigDirectory 作为用户资源目录；也可单独指定资源目录。直接配置路径必须得到同样能力，不强迫旧用户改用工厂。

不要整体替换现有 Config/Options，不重新设计 Builder，不把本阶段改造成公共 API 迁移工程。

### 3.3 保留实际输入与明确动作

`prompt("/review file")` 仍然发送该文本。显式展开得到的文本可由宿主传给 prompt；调用扩展命令使用独立 API，不由普通文本猜测。

展开、调用命令、保存历史与发送模型不是同一个隐含事务。展开本身不写 Session、不调模型、不执行 shell。

## 4. 组件与依赖安排

| 协作者草案 | 职责 | 不负责 |
|---|---|---|
| ResourceLoader | 汇总有序发现、诊断和候选快照 | 模型调用、执行扩展、凭证读取 |
| SkillLoader / PromptTemplateLoader | 文件发现、frontmatter 与资源值 | 工具执行、Session 写入 |
| FrontmatterParser / TemplateExpander | 纯解析和一次参数替换 | 变量求值、文件包含、环境变量 |
| ResourceSnapshot / ResourceDiagnostic | 不可变查询和来源 | 另一套全局缓存/注册中心 |
| SystemPromptBuilder | 从完整输入构建一个 prompt | 文件发现、授权交互 |
| ExtensionRunner | 显式贡献、顺序调用与错误边界 | 动态加载、全局调度、沙箱 |
| Command handler / result | 命令本身与待接纳的固定记录 | 重入同一 Session 启动 run |
| CustomEntry / CustomMessageEntry / CustomAgentMessage | 扩展状态与可见消息 | 任意 Java 对象反序列化 |
| SessionManager / SessionContextBuilder | 唯一历史及一致投影 | 扩展代码执行 |
| CodingAgentSession | 接纳、提交、组合与完成 | YAML/模板算法、扫描和通用插件逻辑 |

主体位于 coding-agent 的 resource、extension、message、session 子包。需要访问包内 Manager 的编排可留在产品包；不要为了移动文件公开 Manager。

当前 AgentMessage 已是开放接口，MessageProjector 已是请求接缝，AgentTool 已支持宿主实现。首版预计无需扩展 core/ai 协议；若实施中确有需要，先证明产品层现有接缝无法满足，不把 Resource 或 Extension 类型下沉。[J4][J5]

### 4.1 使用成熟解析器，不自行发明格式

YAML 采用 SnakeYAML Engine 的 YAML 1.2 基础数据结构读取路径，随后显式取 typed 字段。不启用任意 Java 类构造。库提供的是解析能力，不等于对所有资源作安全保证。[E2]

ignore 匹配采用 JGit 的 `org.eclipse.jgit.ignore` 现有规则解析能力，局部适配三个 ignore 文件的合并顺序；不打开 Git Repository、不运行 git、不自行实现完整 glob/ignore 语言。[E3]

新增依赖仅在 coding-agent 中使用；父 POM 固定具体兼容版本。7A 提交必须记录实际选定版本及 Java 21 构建结果，不使用 LATEST、浮动版本，也不为这个任务升级所有现有依赖。沿用解析库正常的别名/结构限制，不另叠多套资源预算。

## 5. 资源位置、顺序与诊断

### 5.1 默认目录仅在显式启用后使用

```text
<userResourceDirectory>/
    skills/
    prompts/
    SYSTEM.md
    APPEND_SYSTEM.md

<workingDirectory>/.jcode/
    skills/
    prompts/
    SYSTEM.md
    APPEND_SYSTEM.md
```

用户目录不默认推导为 home。项目资源根使用该 Session 的配置工作目录；只扫描这些命名资源根，不扫描 cwd 的全部文件或所有祖先 `.jcode`。

AGENTS 仍使用阶段三自己的发现范围。用于授信和去重的物理身份，不改变 AGENTS 已定义的词法祖先继承语义。

### 5.2 选择规则固定

| 类型 | 候选顺序与选择 |
|---|---|
| Skill / Prompt Template | 默认用户目录 → 已授权项目目录 → SDK 显式路径列表；同名 first-wins |
| 显式路径列表内部 | 严格保持宿主提供顺序；目录内按名称稳定排序 |
| 同一个物理文件 | 只保留第一次有效接纳的资源，给出 duplicate physical path 诊断 |
| SYSTEM / APPEND_SYSTEM | SDK 明确文本优先，其次 SDK 对应文件，其次已授权项目文件，再次用户文件 |
| 项目 context | 完全委托 ProjectContextLoader，不套用上面同名选择规则 |

Skill/模板顺序选用 pi 独立 `loadSkills` / `loadPromptTemplates` 的最小来源规则，而不是复制带包管理的完整资源解析顺序。[P2][P3]

需要显式路径优先时，宿主关闭默认目录，按期望顺序列出资源路径；不再增加 overridePriority、rank 或资源优先级策略对象。碰撞选择整个资源，不混合正文和 frontmatter。

同一个物理路径跨不同资源类型可以各自使用；去重按资源类型进行。realpath 仅用于身份，诊断保留发现路径和来源。

### 5.3 最小诊断形状

诊断包含 code、resourceType、source、path 及简洁原因；collision 另含 name、winnerPath、loserPath。至少覆盖 PARSE_FAILURE、INVALID_METADATA、COLLISION、DUPLICATE_PHYSICAL_PATH、UNSUPPORTED_RESOURCE、UNTRUSTED_PROJECT_RESOURCE、IO_FAILURE。

没有默认目录是正常空来源；显式指定的不存在路径有诊断。普通引用 Markdown 没有 Skill description 时不因被扫描而大量报错。取消与关闭不是 parse failure，不吞成一次成功的空 reload。

文本坏文件通常诊断并跳过，不因一个 Skill 无效拒绝整个 Session。已选中的 SYSTEM/APPEND 文件读取失败，则本次完整装配失败并保留旧快照，避免静默丢失它应提供的基础提示；没有该文件时才检查下一候选。这一点不改变 AGENTS 的候选回退。

diagnostics 只报告资源问题，不默认包含完整正文或解析器输入转储；不增加资源签名、内容审查或每文件错误缓存。

## 6. Frontmatter 与 Skill

### 6.1 一个共享 frontmatter 解析器

按固定 pi 接受 UTF-8 BOM、规范化 CRLF/CR；有配对的 frontmatter 分隔段时读取 YAML 并提取 body，无 frontmatter 时 body 为全文。无闭合分隔段按普通正文处理，后续由资源元信息规则决定能否使用。[P6]

有 frontmatter 时，提取正文的边缘空白处理与 pi 对齐。这里“正文原文”指不编码或改写正文内容，允许明确的 BOM/换行与 frontmatter 去除；SYSTEM 和 AGENTS 不走该提取器，保持已有原文语义。

YAML 根须为映射或空文档，typed 已支持字段按真实类型读取，不把字符串 `"false"` 当布尔 false。重复键、多文档、非映射根按解析/元信息错误处理，不按最后一个对象猜配置。

未知字段不自动执行或影响权限。不把通用 Map 作为产品执行配置；可以忽略普通未知元信息，对已知但未支持的执行提示返回 unsupported 诊断。

### 6.2 Skill 发现对应固定 pi

在一个扫描根内：优先检查 `SKILL.md`，存在有效普通文件时把该目录视为 Skill 根，不继续递归其 references/assets/scripts。没有 SKILL.md 时，根的直接 `.md` 文件可作为候选，并递归子目录寻找 SKILL.md；下级任意引用 `.md` 不全部作为 Skill。[P2]

跳过隐藏条目与 node_modules；读取当前目录 `.gitignore`、`.ignore`、`.fdignore`，按此顺序及向下目录继承规则匹配，包含否定规则和目录规则。显式指向单文件时不套用其外部父目录的扫描忽略规则。

跟随可解析的文件/目录符号链接；遍历中用当前扫描的已访问物理目录集合避免循环，用物理文件身份去重。这是遍历正确性所需状态，不延伸为文件稳定性协议或全局路径数据库。

### 6.3 Skill 值与校验等级

| 字段 | 首版语义 |
|---|---|
| name | frontmatter 字符串；缺省或空值按 pi 使用父目录名 |
| description | 必须是非空字符串，缺失则不加载 |
| filePath / baseDir | 发现文件及其父目录，不靠名称重新拼路径 |
| source | USER / PROJECT / EXPLICIT 及原来源信息 |
| disableModelInvocation | 只有布尔 true 禁止模型目录发现；显式调用仍可用 |

长度与命名检查对应 pi：名称超过 64、非法字符/首尾连字符/连续连字符给告警；描述超过 1024 给告警，但可用非空描述不因此丢弃。不要新增“name 必须等于目录名”的硬规则。[P2]

`allowed-tools`、`context: fork`、`agent`、`model`、安装命令等不改变实际工具、模型或运行模式；诊断未支持即可，不执行它们。`disable-model-invocation` 是本阶段对齐的 pi 字段，不宣称所有 Agent Skills 实现都使用相同开关。[P2][E1]

### 6.4 模型目录与实际读取

system prompt 只追加可自动发现 Skill 的 name、description、location，以及相对路径按 baseDir 解析的说明。名称/描述/路径在结构标签中编码；Skill 正文不整体 XML 编码。

只有真实启用内置 read 时才提示使用 read；无 read 但真实启用内置 bash 时，沿 pi 提示可通过 bash 读取。两者皆无时不注入自动加载目录指令，并提供“不可自动读取”的资源诊断；显式 SDK 展开仍可用。不通过扩展工具恰好叫 read 就推断它具有相同能力。[P2]

工具 policy 仍在真实调用时评估，目录中出现不等于绕过 policy；不为目录预执行一次授权检查，不偷开工具。

### 6.5 显式 Skill 展开

通过快照中的名称精确找到赢家，显式读取该 filePath，移除 frontmatter，附上 Skill 名称、来源、baseDir、正文和用户补充文本。正文中的 `$1` 等按普通 Skill 内容保留，不自动套用模板参数语法；引用文件不在展开时递归载入。

加载阶段可以为解析元信息读过文件，但 ResourceSnapshot 不需要长期缓存所有 Skill 正文。显式展开读取调用时文件内容，与 pi 的按需读取模型对应；快照元信息不承诺等于稍后文件内容的逐字版本。不增加哈希、mtime 复核或全量正文版本库。

展开结果是不可变文本与来源说明。将其提交 prompt 时保存实际发送文本；历史恢复不再读取当前 SKILL.md 重放旧展开。

`disableModelInvocation=true` 仍可显式展开。未知名称或读取失败明确失败，不把失败结果悄悄发送为原 slash 字符串。

## 7. Prompt Template 与显式输入

### 7.1 发现与描述

模板目录只加载直接 `.md` 子文件，不递归。支持显式 Markdown 文件和目录，符号链接指向普通文件可读；名称取文件 basename 去掉 `.md`，不由 frontmatter name 改名。[P3]

可选 description、argument-hint；无 description 时按 pi 使用正文第一条非空行的简短说明。description/argument-hint 为非字符串时诊断，不调用任意对象的字符串表达。

模板正文在资源快照中固定；同名选择遵循第 5 节。修改文件后，已有快照的模板内容不变，显式 reload 才更新。

### 7.2 替换语法按 pi 收敛

| 语法 | 行为 |
|---|---|
| `$1`、`$2` … | 1 起算的位置参数，缺失为空字符串 |
| `$@`、`$ARGUMENTS` | 全部参数以一个空格连接 |
| `${1:-default}` | 参数缺失或空时使用字面默认值 |
| `${@:-default}`、`${ARGUMENTS:-default}` | 全部参数为空时使用字面默认值 |
| `${@:N}`、`${@:N:L}` | 参数切片；N=0 按固定 pi 当作从第一个开始 |

只对模板字符串进行一次匹配和替换；参数值及默认值中的 `$1`、`$ARGUMENTS` 不递归展开。其他 `${name}`、shell 命令替换、反引号、`!command`、`@file`、环境变量均为普通文本。[P3]

没有默认值的缺参给出 MISSING_ARGUMENT 诊断但仍按 pi 输出空字符串；不增加默认 strict 模式。全部展开结果若为空，展开可成功，随后旧 prompt 的非空文本要求仍生效。

优先公开 `expandTemplate(name, List<String> arguments)`，避免把参数边界和 shell 解析混合。另提供纯文本 `expandInput(text)` 时，仅解析模板和 `/skill:name`，不执行 Extension 命令。

slash 参数拆分对齐固定 pi 的引号和空白行为，不宣称完整 Bash parser；不存在文件展开、转义执行或 shell 调用。补充双引号、单引号、空参数、未闭合引号与非递归值 fixture，公开字符串接口与 List 接口的差异。

### 7.3 输入解析次序与未知命令

`expandInput()`：匹配 `/skill:name` 时按 Skill 显式展开；否则 `/template args` 只在已有模板名匹配时替换；其他内容保持原文。已匹配资源但读取/解析失败必须返回错误，不伪装未匹配。

命令始终通过 `executeCommand(extensionId, commandName, arguments)` 调用；不会落入文本解析分支。普通 `prompt()` 不调用 expandInput。

ResourceSnapshot revision 与来源可以随 ExpandedPrompt 返回，用于宿主显示；不因此额外向 Session 插入审计 Entry。用户最终发送的正文由正常 MessageCompleted 保存。

## 8. SYSTEM / APPEND 与统一 Prompt 装配

### 8.1 明确文本和文件来源

既有 customSystemPrompt/appendSystemPrompt 是文本，不因看起来像一个现存文件就自动读取。新增文件来源只能通过 Path 类型显式提供；空字符串是显式文本，可抑制默认文件发现。

SYSTEM 和 APPEND 各自按第 5 节选择一份有效来源；不会将全局和项目 APPEND 文件自动双重拼接。默认目录中的候选不存在时继续查找，无候选时使用既有默认 body/无 append。SDK 显式指定的文件不存在或不可读则明确失败，不悄悄改用默认提示。[P1]

### 8.2 保持同一个完整构建顺序

```text
选定 custom body 或既有默认 body
    → 选定 append 文本（若有）
    → 真实启用工具的说明
    → 已加载项目 instructions
    → 当前可自动发现 Skill 目录（若具备读取入口）
    → cwd 信息
```

模板不直接注入 system prompt；全部 Skill 正文不直接注入。SYSTEM 替代的是基础 body，不意味着丢弃工具/context/cwd 等 Jcode 既有追加内容。[J3]

保持纯函数装配。资源 reload、项目 context reload、初始创建共用同一个完整输入结构，不能分别拼字符串后互相覆盖。

### 8.3 单一资源快照

ResourceSnapshot 汇总项目 context 和新资源；既有 projectContext() / diagnostics 查询可委托到它。不要长期同时维护两份可独立更新的 ProjectContextSnapshot。

currentSystemPrompt 是该快照和固定工具集合的派生值，可继续保留；每次发布时一并更新，不能出现快照已变、下一次请求仍用旧 prompt。

## 9. Project trust 的最小范围扩展

### 9.1 只增加 SETTINGS 与 TEXT_RESOURCES 两个已用范围

既有 `projectTrust(...)`、lookup/remember/remove 的无 scope 重载继续只代表 SETTINGS。新增 TEXT_RESOURCES 用于自动发现项目 Skill、模板和 SYSTEM/APPEND；SDK 可显式给决定，也可使用已有用户级 trust store。[J6]

没有适用决定时，这些新的项目默认目录不读取、不解析，返回根路径级未授权诊断。用户资源和宿主显式提供的路径仍可使用；SDK 显式路径是对该文件/目录的明确选择，不来自项目 settings，也不顺带授权项目其余资源。

既有 AGENTS 文本继续服从 ProjectContextConfig，不被本次新增 scope 追溯拦截。Java Extension 始终只由宿主实例提供，不创建 EXECUTABLE scope 的空实现，也不让任何 scope 开启项目代码加载。

授权控制自动加载入口，不是文件系统沙箱。项目已授权不绕过工具 policy；项目未授权也不意味着模型永远无法通过宿主许可的普通 read 读取该文件。

### 9.2 存储增量兼容

旧结构仍可读：

```json
{"projects":{"/absolute/project":true}}
```

旧 boolean 只解释为该项目 SETTINGS 的 ALLOW/DENY，TEXT_RESOURCES 未指定。新增 scoped 结构示例：

```json
{
  "projects": {
    "/absolute/project": {
      "settings": true,
      "textResources": false
    }
  }
}
```

新 reader 接受 legacy boolean 或已知 scoped 对象。修改某 scope 时只改该 scope，保留同项目其他 scope；第一次扩展一个 legacy 项时保留它原来的 settings 决定。移除一个 scope 不是删除全部项目授权；两项都不存在时才删除项目键。

显式 SDK 决定 > store 同项目同范围决定 > UNSPECIFIED。保存记录继续使用真实项目身份、项目外 store、已有位置/权限检查和短时 ConfigFileUpdater；不重新实现一套 trust 文件锁。

旧二进制无法读取包含对象值的新 store，可能按原 invalid store 行为拒绝授信；必须在文档说明。不宣称反向兼容，不为旧 reader 自动降级成“允许所有范围”，不建迁移框架。

### 9.3 授信重读边界

创建时解析，reloadResources 时重新读取 TEXT_RESOURCES 决定，以便显式重载应用新增授权或撤销。资源 reload 不刷新普通 settings、模型或凭证。

没有监听器或每次工具调用的磁盘授权检查。授权撤销后，reload 移除后续自动发现资源，但不抹掉此前已经进入历史的真实文本。

## 10. Extension 的显式装配与固定贡献

### 10.1 首版契约

CodingExtension 提供稳定 id，以及工具、命令、context transform 和观察/生命周期回调。定义可采用一个接口及少量值对象，不要求接口—默认实现—工厂三层套叠。

贡献在创建时读取一次、复制并验证，保持宿主注册顺序。贡献方法不接收内部 Agent/Manager，也不通过返回值修改模型或 Provider。多个 Session 是否复用实例，由宿主负责；不能偷偷把实例注册进静态全局容器。

显式实例的贡献声明抛异常、重复 extensionId 或真实工具名冲突时，创建明确失败，不发布半注册 Session。普通资源坏文件的可跳过规则不用于掩盖明确的 Java 装配错误。

### 10.2 贡献范围

| 贡献 | 首版支持 |
|---|---|
| tools | 有序 `List<AgentTool<?>>`，复用原协议 |
| commands | extensionId + name 对应 handler，精确查找 |
| context transforms | 按注册顺序运行的异步函数，只返回本次消息视图 |
| lifecycle | session started、resources reloaded、session shutdown |
| observers | 既有 CodingAgentEvent 的只读观察 |

不开放 provider headers/payload、任意 prompt 运行前改写、工具结果重写、扩展自定义 Compaction 实现或资源动态注册。它们可以是后续独立需求，不作为“预留 hook”先实现。

### 10.3 借入实例不自动取得关闭权

首版 Extension 实例及其工具、线程池、客户端统一由宿主拥有；Session 不因为它实现 AutoCloseable 就调用 close。Session 只管理自身回调注册、运行与命令接纳，并在实际工作结束后发送一次 session shutdown 通知。

内置工具仍由原 BuiltInTools.ToolSet 拥有和关闭。合并“实际可用工具列表”时，不把扩展工具加入内置 closeables，不重复关闭 builtin 容器。[J7]

不增加 owned/borrowed 枚举、工厂池或租约：本阶段只有这一种显式借入形式。宿主在 Session 的真实关闭收尾完成通知之后释放自己的对象；close 的快速返回不等于所有在途工作已终结。

扩展回调可以管理它自己明确创建的 Session 局部状态，但这不把宿主共享对象变成 Session 私有资源。

## 11. 工具接入与命令执行

### 11.1 一个实际工具集合

实际集合为“当前配置启用的内置工具 + 显式扩展贡献工具”，保持稳定顺序；与 builtin 或其他扩展真实注册工具同名时拒绝创建并报告双方来源。不自动覆盖、不自动改 wire name。[J3][J7]

内置 `defaultTools` / CodingToolConfig 的语义不变，只选择内置工具；扩展贡献属于宿主另一次明确装配。不扩充 CodingTool 枚举，不把任意扩展名写入旧 enum 配置。需要限制其运行，使用现有 CodingToolPolicy 按名称评估。

`CodingToolConfig.readOnly()` 表达内置 read 集合，不是对任何显式 Java 扩展代码的沙箱保证。Extension 工具由模型调用时，必须经过已有参数准备、policy、execute、取消、工具结果与 MessageCompleted 链路；不能直接调用 execute 绕过它。[J8]

system prompt、模型请求 ToolSpec、实际执行目录与上下文估算器都使用同一集合。Skill 目录不能虚构文件读取能力。

### 11.2 命令与工具不同

命令由宿主明确调用，模型看不到它们作为 ToolSpec，普通 prompt 不解释它们。查找使用两个参数 extensionId 与 commandName；不同扩展的同短名无需自动重命名，同一扩展内重复命令名为装配错误。

命令参数优先 List<String>，不执行 shell 拆词/变量展开。handler 得到只读 ExtensionContext 和取消信号，返回 `CompletionStage<CommandOutcome>`；结果包含显示文本和零个或多个固定自定义记录草稿，不隐式请求模型。

没有记录的纯命令在 7C 即可独立使用；有记录的完整闭环在 7D 交付。不要用只列出命令名的空注册表充当命令实现。

### 11.3 命令接纳和记录提交

```text
idle 接纳命令，固定扩展/资源/历史快照
    → 锁外执行 handler 并等待真实完成
    → 校验固定记录草稿，归属由当前 extensionId 注入
    → 提交接纳前检查取消/关闭及 writer 可用性
    → 锁外按顺序追加记录
    → 从已接纳历史同步 raw runtime 视图
    → 通知结果并释放接纳
    → 完成公开观察 Future
```

命令与 run/reload/branch/model change/compaction 互斥，复用维护操作接纳与按需 executor。不得在 handler 中对同一个 Session 调用 prompt/executeCommand/reload 并等待；ExtensionContext 不提供这些重入方法。

命令显式执行失败，公开 Future 失败，不按普通 observer 的容错规则改成“命令成功”。取消观察 Future 不取消命令，abort() 取消实际命令。Extension 自身执行的外部副作用无法通过 Session 回滚，不自动重试 handler。

多个记录不是 JSONL 事务。可以先验证整组结构，但逐条写入失败时保留已接纳前缀并同步运行视图，writer 按既有规则失效；不删除已写内容、不重复执行 handler、不宣称全部回滚。命令完成结果列出已成功接纳 id；失败时历史仍可查询已接纳部分。

## 12. Context hook 与事件 runner

### 12.1 只暴露必要上下文

ExtensionContext 提供 cwd、当前资源快照、当前分支历史只读快照、ModelSelection、取消信号及本扩展标识；不直接给 Agent、SessionManager、Provider 配置、凭证、executor 或文件 writer。

Context 中的数据在调用时获取，不能在注册时固定一份旧资源或旧分支。回调参数和返回值中的 JSON 做结构复制，沿用 SnapshotMapper/SessionEntry 的现有隔离方式。

Extension 是同 JVM 可信宿主代码；限制 API 不是阻止 Java 对象访问磁盘/网络的安全沙箱。不引入 classloader 隔离或假权限令牌。

### 12.2 Context transform 顺序与失败

按 Extension 注册顺序、扩展内声明顺序串行 await。第一个收到基础请求视图，下一项收到此前成功输出的快照；未返回替换表示保持原值。

允许返回标准消息和本阶段固定 CustomAgentMessage；返回 null 列表/null 元素或未支持的 AgentMessage 实现，视为该 hook 失败，报告诊断并保留前一个成功输出。宿主不能据此认为任意 Java 消息类型都能持久化或投影。

为防止“修改后抛异常”污染后续结果，每个 handler 收到自己的结构副本，仅成功返回且验证后才接纳该阶段输出。这是处理可变 JSON 的必要映射，不建立对象权限层。[P4][J9]

普通 hook 抛异常：报告扩展诊断，继续后续 hook，不重试、不永久禁用该扩展。CancellationException/取消状态不得作为普通告警吞掉，真实取消阻止后续 hook 和模型请求；JVM Error 不降格为可恢复扩展错误。

context hook 不是强制授权/脱敏入口。它失败时会保留上一个有效视图，需要“必须阻断工具”的约束仍通过已有 policy 实现。

### 12.3 事件观察顺序与并发

MessageCompleted 先按既有规则接纳到历史，再形成产品事件快照；observer 按注册顺序调用，最后交给原宿主 sink。其他普通事件同样按一个确定的 runner 入口处理，保留阶段六一个逻辑 RunCompleted 的语义。

每次事件分发内顺序确定；并行工具可以产生不同线程上的独立事件分发，不承诺全局事件全序。Extension observer 必须允许这种既有并发，不加全局锁在等待用户回调时串行化所有工具进度。

每个 callback 返回的 CompletionStage 都纳入既有 backpressure；不发 fire-and-forget 观察线程。长时间不完成的 callback 属于在途操作，不能被早发 Completed 隐藏。

ExtensionDiagnostic 直接发给宿主 sink，不重新分发给扩展观察器；诊断回调失败沿宿主基础设施失败传播，不再生成“诊断失败诊断”。

### 12.4 不同失败不要一律吞掉

| 来源 | 行为 |
|---|---|
| 普通扩展 observer/context hook | 诊断，保留已成功结果，继续；不回滚历史 |
| 明确命令 handler | 当前命令失败，不执行默认 fallback |
| 扩展 AgentTool | 沿原 tool executor 的错误/结果规则 |
| tool policy | 沿原授权失败规则，不能由 runner 改成 Allow |
| Session writer / 原宿主 sink | 基础设施失败，原异常保留并传播 |
| 资源整体构建失败 | 不发布候选；保留旧快照 |
| 已发布资源后的扩展通知失败 | 扩展诊断，资源保持已提交；原宿主 sink 再失败时公开 stage 失败 |

尤其是 context runner 发诊断时若宿主 sink 失败，不能被外层“扩展容错 catch”再次吞掉，也不能仅抛给 ContextTransformer 后就变成普通 assistant ERROR。复用第六阶段的已知边界标记与产品失败传播方式，在合成错误持久化之前传播真实基础设施故障。[J10]

不扫描任意异常树猜故障来源，不引入重试/熔断框架；只在明确的发生边界区分。

## 13. Custom 数据与可见消息

### 13.1 两个固定 Session Entry

| Entry | 持久字段（基础 id/parentId/timestamp 外） | 模型可见性 |
|---|---|---|
| CustomEntry (`custom`) | extensionId、customType、data（标准 JSON 或 null） | 不进入请求、token 预算或摘要材料 |
| CustomMessageEntry (`custom_message`) | extensionId、customType、content、details、display | content 进入上下文；details/display 不进入模型 |

可见 content 首版仅允许 Text/Image，不接受伪造 Assistant、ToolCall、ToolResult 或 Provider replay 的自定义包装。metadata 是固定信封中的数据，不是可执行类型说明。

display=false 仅表示未来 UI 可以不显示，不表示消息不进入模型。非可见状态必须使用 CustomEntry，不能用 display=false 隐藏“不会入模”的错误假设。[P5]

extensionId 标识归属，不是 JVM 安全边界。handler 返回的记录归属由 runner 注入；不允许结果通过伪造另一个扩展名混淆历史来源。

### 13.2 固定产品消息类型，不开放任意序列化

增加一种 `CustomAgentMessage implements AgentMessage`，表达上述可见消息。raw SessionContextBuilder 对 CustomMessageEntry 产出此类型，对 CustomEntry 不产出消息；保留真实来源，而不是在原始历史里伪装成用户输入。

SnapshotMapper、CodingAgentRunResult、事件快照、SessionMessageCodec/SessionCodec、SessionEntries.copy/validateNext 与所有 sealed switch 同步支持固定类型；未知 Java AgentMessage 仍明确拒绝，不能依赖 core 默认 projector 将它静默丢弃。[J4][J5][J9]

产品 MessageProjector 将 CustomAgentMessage 转为带明确 `extensionId/customType` 来源说明的标准 user 内容，正文原样，图片保留。固定 Compaction/BranchSummary 的请求转换继续保持原语义。工具结果及 sourceModel/replay 的既有转换不重写。

### 13.3 JSON 数据规则

只保存普通 JSON tree，不保存 Java class 名、对象引用、二进制序列化、工具实例或 runtime 配置。沿用 Session codec 的精确数值读取；data/details 的读写快照深拷贝，不暴露可变底层节点。

本阶段不新增 custom codec registry、Jackson 默认多态 typing、Class.forName、扩展特定反序列化 hook。扩展自身解释自己 namespace 下的数据；宿主未加载该扩展时也可打开和查询记录。

Session Header 保持 version 1，新增明确 type。新版读取旧文件；旧二进制遇到新 type 明确失败，不静默丢失可见消息。普通旧 message 内容和含有 CompactionEntry 的历史不重写。

### 13.4 生产入口与运行时对齐

首版自定义记录由命令结果产生；可提供同语义的显式宿主 append 入口，但仅 idle，复用同一接纳与提交实现。普通 observer、context hook、资源 reload 不直接追加记录，不为每个事件自动写一份审计数据。

追加可见记录成功后，从真实所选父链更新 core raw 消息；追加纯状态不会制造新的用户消息。原摘要 Entry 仍只改变请求视图，不因 CustomAgentMessage 的加入改成 raw 用户消息。

CustomMessage 是本阶段明确新增的 user-like 上下文，追加后可以通过 continueRun 继续；CustomEntry 或命令显示文本本身不能让末尾为普通 assistant 的旧继续规则失效。不得给数据记录伪造一条空 user 来绕开规则。

扩展数据的分支恢复按当前父链查询，不扫描整个文件取“全局最后一条”而读入其他分支。重开不执行旧命令、不重放外部副作用、不自动重新注册扩展；当前宿主提供的实例仍按正常创建流程注册。

## 14. 与 Compaction 的统一接入

### 14.1 正常请求的唯一流水线

```text
raw 当前分支（标准消息 + 固定 CustomAgentMessage）
    → 既有检查点/分支摘要重建有效基础视图
    → 第六阶段必要的阈值压缩
    → Extension context transforms，按顺序各执行一次
    → 产品 MessageProjector（标准 + 固定 Custom）
    → 原 ModelClient / Provider 请求投影
```

摘要模型请求本身不经过 Extension context hooks，不把摘要再送回自动压缩入口。资源 reload 通知和用量 getter 也不调用 context hooks。

### 14.2 CustomEntry 与 CustomMessage 的压缩差异

CustomEntry 的 data 永远不入摘要材料；它仍在 append-only 文件及祖先链里，可被扩展读回，不需要为状态数据建立摘要或新缓存。

CustomMessage 的可见 content 参与基础视图、token 估算、截断规划、摘要材料和分支摘要；details/display 不参与。可作为 user-like 合法切点，但不拆散真实的 assistant/tool-result 组。

更新 Planner 的标准消息强制类型转换、latestUser 判定、estimateMessage、SummaryMaterialSerializer、分支材料收集与内容可见性判断，不能只更新 codec。所有处使用同一已知产品消息投影，避免各自将未知内容按 toString() 发送。[J10]

连续两次压缩、firstKept 指向 custom_message、目标分支已有摘要、重开后禁用新摘要生成等场景，继续沿同一个父链算法工作。

### 14.3 临时 context hook 输出不入持久摘要

hook 结果仅用于本次请求，不追加 Session、不写 CompactionEntry、不成为下一次 hook 的隐式状态。需要保留的扩展信息应明确使用 CustomMessage/CustomEntry。

Extension 可以有意变更请求消息顺序或工具相关内容；它负责变换语义。产品不另建历史修复器，Provider 已有请求投影继续工作。失败的 hook 输出不污染输入或历史。

### 14.4 用量口径必须说明，不新增循环预算系统

没有 context transform 时，第六阶段 usage + tail 逻辑不变。存在至少一个 context transform 时，压缩基础视图不能再用可能包含临时扩展内容的真实 usage 当基线，改用基础视图全量估算；普通 assistant 的实际 usage 仍原样保存。

contextUsage() 不运行扩展代码，返回的 scope 标明 `SESSION_BASE`，即未含临时 hook 变换；无 hook 时保持既有基础请求估算口径，不声称这是精确的最终 Provider wire token 数。资源或 custom 消息变化使已有观测失效。可用一个结果字段表达范围，不建立两套 token 历史。

hook 可能增大最终请求；本阶段不二次执行 hook、不递归 compact、不依靠估算阈值默默丢弃其内容。第六阶段压缩保证的是持久基础视图的缩减，不是对任意外部 hook 输出的窗口证明。最终溢出仍遵守现有已分类单次恢复：最多重建基础视图并对新的实际请求运行一次 hook，不能无限给扩展注入内容腾空间。

不因此新增摘要模型路由、tokenizer 平台、context transform 内容签名或严格长度沙箱。明确口径比看似精确的错误保证重要。

## 15. 资源 reload 与既有 context reload

### 15.1 reloadResources 的范围

重读文本资源来源和 TEXT_RESOURCES 授信，构造新的 ResourceSnapshot；项目 context 仍调用既有 Loader。更新相应 system prompt 和派生用量观测。

不重新读取模型/凭证/普通 settings，不重新创建 Provider、Agent、Java Extension、Tool 实例或命令集合。Extension 可在资源更新通知中读取新快照，但返回值不能改变固定贡献集合。

需要更换 Extension 集合时，宿主显式创建新 Session/打开已有文件。文档称“资源重载”，不宣称支持 pi 的 JavaScript 模块 cache 清除或 Java 热替换。

### 15.2 原子发布与局部坏资源

```text
锁内接纳 reload，与所有真实运行/命令/摘要操作互斥
    → 锁外解析授信、扫描和读取
    → 按坏资源可跳过规则形成完整候选及诊断
    → 锁外构造完整 system prompt
    → 锁内检查取消/关闭并更新 core prompt + ResourceSnapshot
    → 清除失效 usage 观测
    → 锁外发送资源更新通知
    → 释放接纳，完成观察 stage
```

个别 Skill/模板损坏被跳过时，新集合可以少于旧集合，这是一次成功的候选集合，不保留旧版本的坏资源混入新集合。所选 SYSTEM 读取失败、旧 context Loader 整体失败、取消或整体构建失败，则不发布任何新资源。

取消在发布前生效时保持旧快照；发布已发生后不伪装成未提交。资源更新通知故障不回滚；公开结果/异常应能让调用方根据 revision 确认是否已发布。

### 15.3 reloadProjectContext 保持旧作用域

旧方法只重读项目 instructions，用最新已有 Skill/模板/SYSTEM 快照重新构建完整 prompt。它不隐式扫描新资源，也不会擦掉当前 Skill 目录或替换为旧 custom body。

两个入口共用一次接纳协议、统一 PromptInputs 和快照发布函数，不各维护一套 prompt 字符串。项目 context 的 revision 和整体资源 revision 可各自递增，但只有一个被发布的组合快照。

### 15.4 在途展开与 reload

模板展开基于调用时取得的一个不可变快照；读取 Skill 正文按第 6.5 节规则。展开返回的文本不因之后 reload 变化。展开不持有产品生命周期锁等待文件 I/O。

任何需要将展开和开始 run 合成一个便利方法的实现，必须先固定展开结果，再以原 run 接纳边界提交；不得在 prompt 接纳后异步换用另一版本资源。首版显式“展开后 prompt”即可，不要求新增便利重载。

## 16. 生命周期、启动与关闭

### 16.1 启动时的所有权

创建先验证显式 Extension 身份和贡献，再形成实际工具与资源输入；core 及 Manager 所有权继续通过现有工厂建立。任何中途失败关闭本次真正自建的资源，不关闭借入实例。

onSessionStarted 在 Session 内部状态完整、外部调用方取得可用 Session 之前执行，且在生命周期锁外等待；只读通知不得在启动中调用同一 Session 的 run。普通扩展通知失败形成诊断，诊断宿主 sink 失败使创建失败并清理自建资源。

所有通知不要求新增异步工厂；现有同步创建路径可以明确等待 stage。不得以后台通知掩盖“创建成功但扩展尚未就绪”的状态。

### 16.2 关闭是实际收尾，不是观察 Future 取消

关闭首先禁止新接纳并发出已有 run/reload/summary 以及命令的协作取消；正在执行的 Extension stage、工具、记录提交与资源通知纳入各自操作收尾。

按第六阶段规则延后关闭正在被操作使用的资源；已接纳的写入不通过中断 FileChannel 实现回滚。扩展返回不合作的 stage 时，不虚构完成、超时成功或静默遗弃。

复用 Session 按需维护 executor；不为每个 Extension 创建 executor，也不启动清理线程。实际完成回调中的 close 不等待自己，保留已修复的 core worker 检查。

### 16.3 关闭通知与借入对象

在 Session 所属工作、必要历史同步和资源通知结束后，按反注册顺序发送一次 onSessionShutdown，只提供最终只读快照；关闭通知不重新接纳命令或写记录。一个扩展关闭通知失败不阻止其他通知和自建资源清理，最终保留故障。

此通知不是自动调用 Extension/AgentTool 的 AutoCloseable.close。宿主可据通知/实际收尾安排自身资源释放；不要将借用实例加入 ownedResources。

扩展诊断递送遵守第 12 节。关闭中的通知故障不能递归通知同一失败扩展，也不能令资源清理完全跳过。

## 17. 公开 API 草案与非目标

以下只规定行为，名称按仓库风格实现；不应把所有内部协作者都公开。

```java
// 草案，当前基线中不存在这些新增接口。
ResourceSnapshot resources();
CompletionStage<ResourceSnapshot> reloadResources();
ExpandedPrompt expandTemplate(String name, List<String> arguments);
ExpandedPrompt expandSkill(String name, String additionalText);
ExpandedPrompt expandInput(String text);  // 仅文本资源，不执行 Extension 命令
CompletionStage<CommandResult> executeCommand(
        String extensionId, String commandName, List<String> arguments);
```

ExpandedPrompt 提供实际 text、可选来源/资源 revision 和诊断。CommandResult 提供命令输出与已接纳 Entry id，不自动发送输出。原 history() 可查询自定义数据和消息，不需要再建 ExtensionStateRepository。

ExtensionContext、CommandOutcome、固定记录草稿只覆盖真实消费者。首版不提供任意 Session action map、按反射调用 Java 方法、代码热 reload、模板嵌套 include 或“下一阶段通用扩展点”。

## 18. 实施切片与依赖顺序

| 切片 | 内容 | 完成证据 | 禁止顺带扩展 |
|---|---|---|---|
| 7A：资源发现与范围授权 | ResourceConfig/Snapshot/diagnostics，frontmatter、Skill/模板发现、SYSTEM、scoped trust | 稳定来源与冲突、坏资源处理、legacy trust 只授 settings、旧 AGENTS 回归 | 包管理、动态代码、预算框架 |
| 7B：文本资源使用 | Skill 目录、模板语法、显式展开、统一 PromptInputs | 请求含目录而非全部正文；实际展开文本被保存；旧 prompt 不执行 slash | CLI、activation 工具、脚本模板 |
| 7C：显式 Extension | 固定贡献、工具/只读命令、context/observer runner、借入生命周期 | 真实 ModelRequest 工具与 policy；顺序、扩展诊断/宿主错误边界 | 动态加载、公开 Agent/Manager、全局 scheduler |
| 7D：扩展历史与压缩 | 两类 Entry、固定产品消息、命令记录接纳、codec/copy/投影/估算/摘要 | 命令产生数据→重开→分支→两次压缩→继续，纯状态不泄入请求 | 自定义 Java serializer、事件溯源平台 |
| 7E：reload 与整体收口 | 组合快照发布、旧 context reload、通知/关闭、文档 | 不重建 Agent/工具；失败无半更新；在途 stage 实际结束后释放 | Java 热替换、后台监听、跨资源事务 |

7C 中临时 context transform 先支持标准消息；7D 完成固定 CustomAgentMessage 后开放对应返回类型并补全兼容测试。每一切片有实际运行路径，不能提前宣称下游能力已交付。

## 19. 验收矩阵

| 类别 | 必须证明的结果 |
|---|---|
| 旧构造/工厂 | 未启用资源不新增扫描或回调；原工具、模型、AGENTS 行为不变 |
| 默认目录 | 不隐式读取 home；项目目录仅在对应范围 ALLOW 后加载 |
| 候选优先级 | 用户→项目→显式的 first-wins 可解释；SYSTEM 项目优先规则单独验证 |
| 显式来源 | includeDefaults=false 保留宿主路径顺序；不会被普通项目 settings 注入 |
| physical 去重 | 同类型同物理文件只接纳一次；符号链接循环终止，不改变发现路径展示 |
| ignore | 三种 ignore 文件、否定规则、子目录规则与显式文件路径的行为符合固定 fixture |
| Skill 根 | SKILL.md 阻止递归 references/scripts；下级普通说明文件不误作 Skill |
| frontmatter | BOM、CRLF、空 frontmatter、无闭合段、重复键、非映射、布尔真实类型 |
| metadata 等级 | 名称/长度告警不升级成不可用；描述缺失跳过；name 缺省父目录回退 |
| Skill 权限 | disable-model-invocation 不进入目录但可显式展开；allowed-tools 不开启工具 |
| 按需内容 | 目录不包含所有正文；读取能力缺失不虚构 read；不运行 scripts |
| 原文 | Skill/模板代码中的 `<T>`、`&&` 等正文未 XML 编码，system/context 既有行为不变 |
| 模板 | 位置/全部/默认/切片、缺参诊断、一次替换、参数中模板符号不递归 |
| 输入边界 | 普通 prompt 的 slash 保持文本；显式展开不写历史、不调用命令/模型 |
| SYSTEM | 空显式文本抑制发现；坏已选文件整体失败；APPEND 不隐式双重拼接 |
| trust 兼容 | legacy bool 仅 SETTINGS；scoped 修改保留其他范围；取消授权 reload 生效 |
| trust 存储 | 真实项目身份、项目外 store、读取与保存同规则、JSON 尾部校验不回退 |
| Extension 注册 | 固定顺序、重复 id/真实工具名冲突失败；无扫描/ServiceLoader |
| Tool 集合 | 实际 request/schema/prompt/执行集合一致，启用列表修复不回退 |
| Tool policy | 扩展工具被 deny 后不执行；允许后沿原调用次数、取消与源顺序规则 |
| Command | 精确二元名称、同扩展重名失败；输出不隐式 prompt；失败不自动重试 |
| Runner 顺序 | 每次分发按注册顺序；并行事件不靠全局锁串行化 |
| Context 复制 | 某 hook 修改参数后抛异常，下一项仍看到此前成功视图，历史原 JSON 不变 |
| 扩展容错 | 普通 observer/context 失败诊断并继续；取消不吞掉；VM Error 不降格 |
| 宿主故障 | ExtensionDiagnostic 的宿主 sink 故障真实传播，不循环诊断或变成普通模型错误 |
| Custom 格式 | 精确 JSON/图片/来源往返；无 Java 类名反序列化；未知 Entry 明确失败 |
| 可见性 | custom.data 不出现在 request/summary；custom_message.content 出现；details/display 不泄入 |
| 分支数据 | 当前父链状态不被另一分支的更晚 custom 覆盖；重开不执行旧 handler |
| Snapshot | CustomAgentMessage 在结果、事件、历史和状态中可复制；未知类型不静默丢失 |
| 压缩 | firstKept 可指 custom_message；连续压缩承接旧保留消息；纯状态不丢也不入模 |
| 临时 Hook | 只出现在本次最终请求，不写 JSONL/摘要；getter、reload、摘要请求不执行 hook |
| 用量 | 有 hook 时基础估算不复用临时内容 usage；scope 明确，换资源/分支后失效 |
| 单次恢复 | Extension 注入过长内容不触发无限压缩/重试；仍最多一次已分类恢复 |
| 命令写入 | 接纳前失败不写；批次中途 I/O 失败保留前缀并同步 core，不假称事务 |
| Reload | idle 原子更新快照与 prompt；单个坏资源按规则跳过；整体失败保留旧值 |
| 旧 reload | reloadProjectContext 不擦掉 Skill/SYSTEM，也不隐式重扫资源 |
| 提交后通知 | 资源已发布后 observer 故障不回滚；原 sink 再故障时 stage 失败、revision 可查 |
| 取消/close | 命令/回调不合作时不虚构完成；已接纳提交实际结束后释放；回调 close 不等自身 |
| 所有权 | Session 关闭内置资源一次，不自动关闭借入扩展/工具/Models |

### 19.1 六条必须具有实际断言的集成 fixture

**R1：资源优先级与正文。** 建立用户、项目、两个显式目录中的同名 Skill/模板，加入一个符号链接重复项。断言赢家路径、collision 双方、诊断类型、禁自动发现 Skill 的显式展开，以及 request 中只有预期目录/展开内容。不能只检查扫描列表长度。

**R2：扩展工具走原授权链。** 注册一个带计数器的 AgentTool，模型提出调用；policy 第一次拒绝，执行计数为零；另一 Session 允许后实际执行。再使用两个受控工具制造反序完成，断言真实事件序、历史源序和工具执行次数，不用 sleep 猜并发。

**R3：可恢复扩展状态与可见消息。** 命令产生一个 custom 状态和一个 custom_message；检查实际 JSONL、raw 自定义消息、最终 request、details 不入模。关闭重开时不提供该扩展，历史仍可读；在两个分支写不同状态后按父链读，不用全文件最后值。随后两次压缩，确认保留边界和可见正文正确。

**R4：context 与错误边界。** 第一项 hook 产生临时文本；第二项修改输入 JSON 后抛异常；第三项应看到第一项成功结果且无第二项污染。再次让 ExtensionDiagnostic 的宿主 sink 失败，断言公开 Future 保留宿主异常且不调用正常模型；错误通知不再投给失败扩展。检查文件没有临时文本。

**R5：reload 不是代码重载。** 在资源读取 gate 期间尝试 prompt/branch/compact 均因 busy 拒绝；放行后下一请求看到新 Skill/context/SYSTEM。检查扩展工具实例身份和构造计数不变。再改 AGENTS 并调用旧 reloadProjectContext，Skill/SYSTEM 仍保留；已发布后通知失败不回滚 revision。

**R6：命令与关闭。** handler 返回一个未完成受控 stage，close 后公开观察仍未完成，借入对象未被 Session 关闭；真正终结后释放接纳并发一次 shutdown 通知。另设记录追加第二条失败，检查第一条保持在历史、core 对齐、writer 下次在模型前拒绝且 handler 未被重跑。

### 19.2 测试方法

使用固定 Clock、@TempDir、已有 fake ModelClient、gate/latch/future、临时本地资源文件；不要求真实 API Key、不访问公网、不扫描开发者 home。

不以 Started、线程启动、固定 sleep 或过宽的“任意 ExecutionException”替代成功/失败原因断言；完成信号放在真实提交/收尾之后。

保留前六阶段回归，尤其第六阶段 THRESHOLD/OVERFLOW 的 SummaryFailed sink 故障与非合作摘要 close。不能为了新增 Extension 统一 catch，把它们再次吞为模型错误。

## 20. 资源与调用示例

### 20.1 Skill 文件（真实文件格式示例）

```markdown
---
name: review-java
description: Review Java changes for correctness and unnecessary mechanisms.
disable-model-invocation: false
---
Review the changed Java code. Keep verified findings separate from assumptions.
Use List<String> examples and preserve commands such as mvn test && git diff --check.
Resolve references/checklist.md relative to this skill directory.
```

`references/checklist.md` 由后续读取，不在加载时递归执行或载入。将该文件放在 `skills/review-java/SKILL.md`，无需为了基础使用增加其他元信息。

### 20.2 Prompt Template（真实文件格式示例）

文件名 `review.md`：

```markdown
---
description: Review selected changes
argument-hint: '"target path" focus'
---
Review $1 with focus on ${2:-correctness}.
Additional context: ${@:3}
Do not make changes unless explicitly requested.
```

List 参数 `["src/main", "lifecycle", "$1 remains literal"]` 中最后一个参数不会被再次替换。缺少第一个参数时仍按 pi 替换为空，并返回 MISSING_ARGUMENT 诊断。

### 20.3 Headless 使用顺序（新增 API 草案）

```java
// 伪代码：方法名/类型待实施后用真实可编译示例替换。
// 模型和资源目录由宿主显式提供；没有隐式 home 扫描。
var options = existingOptions
        .resources(resourceConfig)
        .extensions(List.of(reviewExtension));
var created = CodingAgentSessionFactory.create(options);
try (var session = created.session()) {
    var expanded = session.expandTemplate("review", List.of("src/main", "lifecycle"));
    session.prompt(expanded.text()).toCompletableFuture().join();

    // 命令不会自动调用模型；它可以返回固定扩展记录。
    var result = session.executeCommand("review", "save-note", List.of("checked"))
            .toCompletableFuture().join();

    // 只重载文本资源，不替换 reviewExtension 或其工具实例。
    session.reloadResources().toCompletableFuture().join();
}
// reviewExtension 及其外部依赖由宿主管理；不能把 close 的快速返回
// 当作所有在途扩展 stage 已完成，资源释放以真实收尾通知为准。
```

完整示例应在 7E 使用实际构造器、fixture 模型和明确配置编译，不把上述未定义变量伪代码当作可运行 README。

## 21. 验证命令与证据

```bash
mvn -pl coding-agent -am test
mvn clean verify

mvn -pl coding-agent -am verify -Plocal-tools-smoke \
  -Djcode.test.bash=/absolute/path/to/bash \
  -Djcode.test.rg=/absolute/path/to/rg \
  -Djcode.test.fd=/absolute/path/to/fd

git diff --check
```

如果实际修改 core，另外执行并记录 `mvn -pl agent-core -am test`；新增 YAML/ignore 依赖记录 dependency tree 和 Enforcer 结果。不因读文档就声称已编译通过。

验收记录分别列出源码提交/工作树状态、环境、R1～R6、全仓数量/skip、严格 native 范围、依赖和格式兼容结果。严格 native 只证明已有本地工具相关环境满足要求，不替代资源/扩展协议测试。

真实 Provider probe 不是门槛；未执行就明确记录。本文不预填未来测试数量、不复制第六阶段 895 作为第七阶段结果。

## 22. 总路线图和文档同步

| 旧口径/潜在歧义 | 本阶段规则 |
|---|---|
| Skill、Template、Extension 都能 reload | 文本资源可 reload；显式 Java 实例和固定贡献不可热替换 |
| 项目已信任即可使用资源 | 授权按范围；旧 bool 只代表 SETTINGS |
| 区分执行型资源 | 本阶段不加载项目执行代码，不创建 EXECUTABLE scope 空实现 |
| 模板缺参诊断 | 缺参按 pi 为空并诊断，不默认强制失败 |
| Extension 错误隔离 | observer/context 普通异常可继续；命令、policy、writer 和原 sink 分别处理 |
| Custom 类型预留 | 本阶段必须完整生产、保存、恢复、投影与压缩，不是两个空 record |
| 切换 branch 重建 Agent | 改为既有 idle 消息替换；资源 reload 更新 prompt，不重建 Agent |
| Compaction 精确代表最终 hook 请求 | 管理持久基础视图；临时 hook 的 usage 不作为基础压缩观测 |
| 主题预留 | 本阶段无类型/实现占位，留给有真实 TUI 消费者的阶段 |
| 所有扩展工具跟随 readOnly | readOnly 选择内置工具；显式扩展工具走同一 policy，Java 代码不是沙箱 |

更新 README、根/模块 AGENTS、runtime-contracts、公共数据与依赖说明、示例、Session 新 Entry 及 trust 格式兼容说明。保留旧实现记录，不用新描述遮盖历史差异。

仅当 7A～7E 和验收证据均完成，才归档本计划。总路线图需要复核一至七全部 DoD，不能因第七阶段代码已提交就自动认定此前未验证部分全部通过。

## 23. 固定参考和查阅依据

[P*] 是固定 pi 源码行为，[J*] 是已读取 Jcode 基线，[E*] 是格式/库能力说明。实现决策属于本文，不冒充原项目行为。源码路径用于定位，不把浮动行号当作永久契约。

| 编号 | 来源 | 用途 |
|---|---|---|
| P1 | [pi resource-loader.ts](https://github.com/earendil-works/pi/blob/60e7e76bd7ea25cad1dd6f3f1ce0d18814a42759/packages/coding-agent/src/core/resource-loader.ts) | 来源汇总、first-wins、SYSTEM/APPEND 选择与 reload |
| P2 | [pi skills.ts](https://github.com/earendil-works/pi/blob/60e7e76bd7ea25cad1dd6f3f1ce0d18814a42759/packages/coding-agent/src/core/skills.ts) | 发现、ignore、Skill 根、校验等级、目录格式和按需读取 |
| P3 | [pi prompt-templates.ts](https://github.com/earendil-works/pi/blob/60e7e76bd7ea25cad1dd6f3f1ce0d18814a42759/packages/coding-agent/src/core/prompt-templates.ts) | 非递归模板发现、参数拆分/替换、缺参和展开 |
| P4 | [pi extensions/runner.ts](https://github.com/earendil-works/pi/blob/60e7e76bd7ea25cad1dd6f3f1ce0d18814a42759/packages/coding-agent/src/core/extensions/runner.ts) | 回调顺序、context 容错、命令名和生命周期；不照搬 UI/Provider hooks |
| P5 | [pi session-manager.ts](https://github.com/earendil-works/pi/blob/60e7e76bd7ea25cad1dd6f3f1ce0d18814a42759/packages/coding-agent/src/core/session-manager.ts) | custom 与 custom_message 区别、来源及上下文可见性 |
| P6 | [pi frontmatter.ts](https://github.com/earendil-works/pi/blob/60e7e76bd7ea25cad1dd6f3f1ce0d18814a42759/packages/coding-agent/src/utils/frontmatter.ts) | BOM/换行/frontmatter 提取及 YAML 解析 |
| J1 | [Jcode roadmap](https://github.com/YPQuinn/Jcode/blob/a1964e51f19b5a7ca5a6ed75870b55284b11c682/docs/plans/coding-agent-development-roadmap.md) | 阶段七目标、范围、依赖纪律及全阶段 DoD |
| J2 | [CodingAgentConfig](https://github.com/YPQuinn/Jcode/blob/a1964e51f19b5a7ca5a6ed75870b55284b11c682/coding-agent/src/main/java/site/pplee/jcode/codingagent/CodingAgentConfig.java)；[Options](https://github.com/YPQuinn/Jcode/blob/a1964e51f19b5a7ca5a6ed75870b55284b11c682/coding-agent/src/main/java/site/pplee/jcode/codingagent/CodingAgentSessionOptions.java) | 显式配置、presence、兼容构造和工厂输入 |
| J3 | [SystemPromptBuilder](https://github.com/YPQuinn/Jcode/blob/a1964e51f19b5a7ca5a6ed75870b55284b11c682/coding-agent/src/main/java/site/pplee/jcode/codingagent/prompt/SystemPromptBuilder.java) | 纯装配、原文、真实工具和项目 instructions |
| J4 | [AgentMessage](https://github.com/YPQuinn/Jcode/blob/a1964e51f19b5a7ca5a6ed75870b55284b11c682/agent-core/src/main/java/site/pplee/jcode/agentcore/message/AgentMessage.java) | 已有开放产品消息接缝 |
| J5 | [MessageProjector](https://github.com/YPQuinn/Jcode/blob/a1964e51f19b5a7ca5a6ed75870b55284b11c682/agent-core/src/main/java/site/pplee/jcode/agentcore/message/MessageProjector.java) | 标准 projector 会过滤未知类型，产品须显式支持 Custom |
| J6 | [ProjectTrustStore](https://github.com/YPQuinn/Jcode/blob/a1964e51f19b5a7ca5a6ed75870b55284b11c682/coding-agent/src/main/java/site/pplee/jcode/codingagent/settings/ProjectTrustStore.java)；[SettingsLoader](https://github.com/YPQuinn/Jcode/blob/a1964e51f19b5a7ca5a6ed75870b55284b11c682/coding-agent/src/main/java/site/pplee/jcode/codingagent/settings/SettingsLoader.java) | legacy boolean、独立授信、实际路径、固定 JSON 解析及 settings scope |
| J7 | [BuiltInTools](https://github.com/YPQuinn/Jcode/blob/a1964e51f19b5a7ca5a6ed75870b55284b11c682/coding-agent/src/main/java/site/pplee/jcode/codingagent/BuiltInTools.java)；[coding-agent POM](https://github.com/YPQuinn/Jcode/blob/a1964e51f19b5a7ca5a6ed75870b55284b11c682/coding-agent/pom.xml) | 内置资源所有权、依赖与 native profile |
| J8 | [CodingToolPolicyAdapter](https://github.com/YPQuinn/Jcode/blob/a1964e51f19b5a7ca5a6ed75870b55284b11c682/coding-agent/src/main/java/site/pplee/jcode/codingagent/CodingToolPolicyAdapter.java) | 扩展工具复用既有授权接入 |
| J9 | [SnapshotMapper](https://github.com/YPQuinn/Jcode/blob/a1964e51f19b5a7ca5a6ed75870b55284b11c682/coding-agent/src/main/java/site/pplee/jcode/codingagent/internal/SnapshotMapper.java) | 当前仅标准类型、mutable JSON 防御复制 |
| J10 | [CodingAgentSession](https://github.com/YPQuinn/Jcode/blob/a1964e51f19b5a7ca5a6ed75870b55284b11c682/coding-agent/src/main/java/site/pplee/jcode/codingagent/CodingAgentSession.java)；[CompactionPlanner](https://github.com/YPQuinn/Jcode/blob/a1964e51f19b5a7ca5a6ed75870b55284b11c682/coding-agent/src/main/java/site/pplee/jcode/codingagent/compaction/CompactionPlanner.java) | 生命周期/恢复/原始与请求视图、估算/规划对已知消息类型的现有假设 |
| E1 | [Agent Skills specification](https://agentskills.io/specification) | Markdown/frontmatter 与分层加载的格式参考，不把全部建议变成 Jcode 硬门槛 |
| E2 | [SnakeYAML Engine README](https://github.com/snakeyaml/snakeyaml-engine/blob/master/README.md) | YAML 1.2、基础 Java 数据结构读取；该页不是所选依赖版本锁 |
| E3 | [JGit IgnoreNode API](https://archive.eclipse.org/jgit/site/5.12.0.202106070339-r/org.eclipse.jgit/apidocs/org/eclipse/jgit/ignore/IgnoreNode.html) | 成熟 ignore 解析及目录相对路径接口；该文档版本不是本阶段依赖选版 |

外部格式/库文档查阅日期：2026-09-22。7A 将实际依赖版本和用于对齐的资源样本固定到测试；不依赖远端文档日后改动决定运行行为。

## 24. 实施记录

- 实施工作树基于 `a1964e51f19b5a7ca5a6ed75870b55284b11c682`，未创建提交或推送。验证环境为 OpenJDK `21.0.10`、Maven `3.9.14`。
- 新增依赖固定为 SnakeYAML Engine `3.1.1` 与 JGit `7.8.0.202609011348-r`，仅由 `coding-agent` 使用。资源文件统一使用严格 UTF-8、有界 4 MiB 读取，以满足模块现有的不受信任文件读取约束。
- 7A～7E 均已接入真实消费者：ResourceSnapshot 与 scoped trust；Skill/模板/SYSTEM 展开和 prompt 装配；显式 Java Extension 工具、命令、context/observer/lifecycle；`custom`/`custom_message` codec、重开、分支和 compaction；资源原子 reload、旧 context reload 保留资源及真实关闭收尾。
- 与固定 pi 的差异保持计划约束：普通 `prompt()` 不解释 slash；Java Extension 只接收宿主实例且不热替换；借入扩展/工具不由 Session 调用 `close()`；项目文本资源使用独立 `TEXT_RESOURCES` 范围；不实现包管理、动态脚本、UI 或 Provider hook。
- R1 断言用户→项目→两个显式来源的 Skill/模板赢家、collision、物理重复、正文原文、禁自动发现但可显式展开，以及 system prompt 只含目录元数据。Frontmatter 另覆盖 BOM/CRLF、重复键、真实布尔类型、三个 ignore 文件、Skill 根和符号链接循环。
- R2 断言扩展工具 policy deny 时执行计数为零、allow 时沿真实管道执行；两个受控扩展工具反序完成时 `ToolCompleted` 为完成序，JSONL/请求工具结果为源序，执行次数精确为二。
- R3 断言命令生成 `custom` 与 `custom_message` 后 JSONL 精确往返，data/details 不入模；无扩展重开仍可读并继续；两个分支按父链隔离状态；连续两次 compaction 保留可见正文和旧摘要，`custom_message` 可作为 first-kept 边界。
- R4 断言失败 transform 对 JSON 副本的修改不污染下一项，上一成功临时文本只进入当次请求且不写 JSONL；ExtensionDiagnostic 的宿主 sink 故障保留原异常并阻止模型调用。
- R5 断言资源 reload 期间 prompt/branch/compact 均因 busy 拒绝；发布后请求同时看到新 Skill、SYSTEM 和项目 context，Extension 工具贡献只读取一次；旧 `reloadProjectContext()` 保留现有 Skill/SYSTEM。
- R6 断言未完成命令期间 close 不虚构完成，真实结算后只发一次 shutdown 且不关闭借入对象；第二条记录写失败时保留第一条及 accepted id，writer 在模型前拒绝后续操作，handler 不重跑。
- `mvn verify` 通过：`ai` 71、`ai-providers` 269、`agent-core` 209、`coding-agent` 364，共 913 个测试，0 failure、0 error、0 skip。Maven Enforcer 全部通过。
- 严格 native profile 使用 `/bin/bash`、`/opt/homebrew/Caskroom/codex/0.155.1/codex-path/rg`、`/opt/homebrew/bin/fd` 通过；`git diff --check` 通过。旧构造重载、默认资源关闭、旧 Session、legacy trust boolean 和阶段一至六回归均包含在上述全仓验证中。
- 未执行真实 Provider 网络 probe；本阶段验收使用 deterministic fake model、临时文件系统和本地原生工具，不需要 API Key。
