# Coding Agent 第三阶段：项目上下文与 System Prompt

> 状态：已实施并归档（2026-09-19）
>
> 验证：审查修复后全仓 `mvn clean verify` 762 个测试（`coding-agent` 220 个），0 failure/error/skipped；严格 `local-tools-smoke` 与生命周期/加载边界三轮复验通过
>
> Jcode 基线：`15a7d6d171fd5362c58d662d23ab7153d1f2605c`
>
> 总路线图：[`../coding-agent-development-roadmap.md`](../coding-agent-development-roadmap.md)
>
> 前置阶段：[`coding-agent-phase-2-local-tools.md`](coding-agent-phase-2-local-tools.md)
>
> pi 源码：`60e7e76bd7ea25cad1dd6f3f1ce0d18814a42759`
>
> pi-book：`0a8863b611504a47302931ad9adf1cab71a0b79f`（本次实际参考版本，不沿用旧计划的书籍版本号）
>
> 本文保留实施设计并在 §11 记录实际交付与审查修复，不替代 GitHub Issues。本次未创建远端 issue；下文 PR 编号表示实现切片，不是已创建的远端 PR。

## 1. 目标与核心决策

第三阶段让调用方回答三个问题：**本次模型请求使用了哪些项目指令，为什么选择这些来源，以及修改后何时生效。**

它不是把几个文件无条件拼进字符串，也不是提前建设阶段七的通用 Resource Loader。主线固定为：

```text
显式启用 → 有界发现与读取 → 不可变上下文快照 → 纯 Prompt 装配 → idle 原子 reload
```

本计划采用以下决策：

1. 旧 Config 构造及默认配置保持不发现项目文件；新能力必须显式启用。
2. 全局指令目录由宿主显式传入，不推断用户主目录、`~/.jcode` 或环境变量。
3. 同目录候选仅为 `AGENTS.override.md`、`AGENTS.md`、`AGENTS.MD`，按此顺序选择一个。**不探测或回退到 `CLAUDE.md` / `CLAUDE.MD`，不提供任意候选名扩展。**
4. 全局来源在前，项目来源按物理祖先目录从外到内排列；启用后默认遍历至文件系统根，允许显式设置发现上界。
5. 文件发现、文件读取、Prompt 装配与 Session 生命周期各自负责一个边界；builder 保持无 I/O 的纯函数。
6. 成功选中的空文件也占据其目录作用域；override 只影响同目录候选，不清空祖先规则。
7. 同目录高优先级来源读取失败不偷偷回退；重复物理文件与 worktree 同仓库作用域遮蔽分开处理。
8. custom prompt 只替换基础提示，继续保留 Jcode 既有的实际工具说明与 cwd；不会照搬参考项目的 custom 分支。
9. 文件仅在 Session 创建和显式 reload 时读取；reload 只在 idle 接纳，不自动中断模型运行，不重建 Agent 或工具。
10. 出错时使用结构化、有界诊断；整体加载失败或取消不替换已生效快照。

这些是本计划的实施默认值。后续可以调整类型名称，但规则变更必须同步修改本文及对应验收用例，不能由实现中的局部 fallback 决定。

## 2. 现状、参考与有意差异

### 2.1 当前代码事实

- `CodingAgentConfig` 有 13 参数主构造和 12 参数兼容构造；工具配置缺省为 read-only。
- `SystemPromptBuilder.build()` 当前顺序为基础提示、append、实际工具说明、cwd；custom 为 `null` 才采用默认提示，空字符串是有效替换值。
- Session 在构造时装配 `BuiltInTools`，生成一次 prompt，再创建独占的 `Agent`；工具集合在 Session 生命周期内固定。
- Session admission lock 已用于产品 API 接纳；模型 run 与事件投递的等待不能被移入此锁。产品当前只有 `prompt()`，没有 `continueRun()`；busy/closed 的产品接纳失败同步抛异常，core 的 `prompt()` / `continueRun()` 则返回失败 future，不能混淆两层契约。
- `Agent.context()` 和 `AgentState.context()` 承载当前 prompt；目前没有 idle 更新 prompt 的公开入口。`PrepareNextTurn` 是 run 内的 turn hook，不能替代 idle reload。
- 既有 `CodingAgentConfig` 会把 cwd 转为规范化绝对路径，各工具沿用这一 Session cwd 契约。本阶段不修改该输入契约，也不把逻辑别名目录链与物理目录链混合继承。
- 阶段二已完成验证基线为全仓 721 个测试，其中 `coding-agent` 181 个；这不是阶段三的新验证结果。

### 2.2 参考依据

路径相对于 `docs/references/pi/`；这些说明只出现在文档中，不进入生产代码注释、POM 或配置。

| 参考位置 | 借鉴内容 | Jcode 决策 |
|---|---|---|
| `packages/coding-agent/src/core/resource-loader.ts` 的 `loadContextFileFromDir` | 同目录候选优先级、普通文件检查、BOM | 仅保留三种 AGENTS 候选；严格 UTF-8、有界读取、结构化诊断 |
| 同文件的 `loadProjectContextFiles` | 全局在前、祖先从外到内 | 显式上界、固定物理祖先链、物理文件去重 |
| 同文件的 `findShadowedContextFile` | 嵌套 linked worktree 的重复仓库作用域 | 验证有限 Git 元数据；按作用域而非仅同名文件遮蔽，不误伤普通祖先 |
| `packages/coding-agent/test/resource-loader.test.ts` | 普通仓库、symlink cwd、worktree、bare/submodule 反例 | 转为 Java NIO fixtures 与真实文件系统测试 |
| `packages/coding-agent/src/core/system-prompt.ts` | 预加载 context 与纯装配、来源标签 | 保留 Jcode custom/tools 语义；安全编码新增区块 |
| `packages/coding-agent/src/core/agent-session.ts` 的 reload 路径 | 明确生效边界 | 只 reload 项目指令，不加载 extension、skill、theme、settings 或动态工具 |

同时参考 pi-book 第 14 章 `ch14-system-prompt.md` 和第 17 章 `ch17-resource-loader.md`。源码事实优先于解读；Jcode 已有契约和本次不兼容 CLAUDE 的决定优先于两者。

## 3. 范围与模块边界

### 3.1 本阶段交付

- 全局及祖先目录的 AGENTS 文件发现、选择、去重与有界读取。
- 带来源、确定顺序和诊断的不可变产品快照。
- 结构化、纯函数的 System Prompt 装配。
- Session 创建时加载、快照查询和显式 idle reload。
- `agent-core` 最小的通用 idle prompt 更新 seam。
- 文件、路径、worktree、编码、预算、并发与故障矩阵测试。

### 3.2 明确不交付

- CLAUDE 文件兼容、任意候选文件注册、递归扫描 cwd 的子目录。
- `SYSTEM.md` / `APPEND_SYSTEM.md` 自动发现；现有 custom/append 参数仍是文本，不根据文件是否存在猜测其含义。
- 通用设置发现、provider/凭证装配、project trust store、交互式授信。
- Skill、Prompt Template、Extension、包管理器、classpath 扫描或通用资源注册表。
- 文件监听、每个 turn 重新读取、根据工具访问路径自动追加子目录规则。
- 动态 cwd、model、tools、policy 重载；Session 持久化或上下文压缩。
- 完整 Git 实现、JGit 依赖、生产环境执行 `git`、执行任何仓库 hook/config。

### 3.3 依赖方向

主体仍在 `coding-agent`，建议新增 `context/` 包；内部依赖仍只允许 `ai`、`agent-core`。`ai`、`ai-providers` 无需修改。

`agent-core` 只增加字符串 prompt 的通用更新能力，不知道文件名、路径、工作区、Git 或来源诊断。不能为了 reload 把整个产品 context loader 注入 core，也不增加任意可变 context/tools 的宽接口。

## 4. 配置、快照与 API 草案

### 4.1 显式配置

建议给 `CodingAgentConfig` 末尾增加 `ProjectContextConfig projectContext`，保留现有 12/13 参数构造；`null` 与旧构造均等价于 `disabled()`。

`ProjectContextConfig` 的首批字段：

| 字段 | 语义 |
|---|---|
| `enabled` | 是否执行项目发现；关闭时不进行新增文件系统 I/O |
| `globalDirectory` | 可选，宿主传入的绝对目录；不沿它的祖先再次扫描 |
| `discoveryRoot` | 可选，包含在项目扫描中的物理祖先上界；缺省为文件系统根 |
| `failureMode` | `WARN_AND_SKIP` 或 `FAIL`，缺省为前者，见 §6 |

提供 `disabled()`、`project()` 和 `projectAndGlobal(Path)` 等简洁工厂；具体命名在 PR 1 固定。可选路径必须是绝对路径，不展开 `~` 或环境变量；配置构造只检查参数形状，真实目录和祖先关系由 loader 验证。disabled 时拒绝同时设置发现目录，避免看似生效但被忽略的配置。

首批预算使用实现常量，不把每项限制做成配置旋钮。

### 4.2 不可变产物

建议的公开产品类型：

| 类型 | 职责 |
|---|---|
| `ProjectContextFile` | scope、发现路径、物理路径、去 BOM 后正文、原始字节数 |
| `ProjectContextDiagnostic` | 固定 code、severity、来源及可选关联来源；不保存原始异常消息或文件正文 |
| `ProjectContextSnapshot` | revision、实际发现基准目录、按生效顺序排列的 files、diagnostics |
| `ProjectContextLoadException` | 整体加载失败时携带有界诊断，供构造失败或 reload stage 查询 |

所有 collection 防御性复制；文本不暴露可变 buffer；`toString()` 隐去正文和路径。显式 accessor 可以返回来源路径和指令正文，日志默认不输出它们。snapshot 只记录实际有效来源；被遮蔽、去重或跳过的来源用诊断说明，未发现文件的普通目录不逐个产生日志。

disabled snapshot 的 revision 为 0；启用后的初始成功快照为 1，每次成功应用 reload 加 1，即使文本不变也表示一次新发现。revision 不进入 prompt，不用时间戳破坏确定性；失败不增加 revision。

### 4.3 产品与内部入口

建议 Session 增加：

```java
ProjectContextSnapshot projectContext();
boolean isReloading();
CompletionStage<ProjectContextSnapshot> reloadProjectContext();
```

`projectContext()` 始终返回已应用的快照，而不是尚未提交的候选结果；关闭后仍可查看最后快照。reload 失败诊断从异常获取，不改写旧 snapshot。返回 stage 使用只读观察视图；调用方修改或取消其 `toCompletableFuture()` 不得提前释放 operation 槽位或提交候选，操作取消通过 Session `abort()` 表达。

内部 `ProjectContextLoader` 负责一次候选快照构建，配合窄的文件 I/O seam 做故障注入；不暴露宿主可执行回调或通用 loader SPI。`SystemPromptBuilder` 增加接收预加载 files 的 overload，旧四参数方法委托为空列表；不要求纯 builder 为显示而再次查询 realpath。

## 5. 发现、选择与身份规则

### 5.1 发现目录链

1. 以已有 Config 验证后的 Session cwd 为输入，通过文件系统解析取得物理 cwd。
2. 若配置 `discoveryRoot`，解析其物理路径，确认它确实是物理 cwd 的祖先或自身；否则整体失败。比较使用 Path 组件，不使用字符串前缀。
3. 从物理 cwd 向上收集至上界（包含上界），倒序处理。它不是安全 sandbox，候选 symlink 的目标仍可在链外。
4. 全局目录若配置，必须能解析为真实目录；目录不存在或不可访问属于整体配置/加载失败。合法目录中没有候选文件则是正常的空结果。
5. 全局来源先于项目来源，去重也按此顺序决定胜者。全局目录恰好位于项目链中时，不会重复注入同一物理文件。

例如物理 cwd 为 `/work/repo/module`、上界为 `/work`，顺序为全局目录、`/work`、`/work/repo`、`/work/repo/module`。不因为 `/work/repo/.git` 存在就截断外层规则。

逻辑 cwd 别名的另一套父目录规则不会再叠加进来。保留原 Session cwd 作为已有工作目录展示与工具输入；记录物理发现基准，向调用方解释两者的区别。对新增配置路径、候选 symlink 和 Git 元数据目标，不先用 lexical normalize 折叠 `..` 再交给文件系统。

### 5.2 同目录选择

候选顺序固定：`AGENTS.override.md` → `AGENTS.md` → `AGENTS.MD`。

| 情况 | 处理 |
|---|---|
| 候选不存在 | 尝试下一个；不产生缺失警告 |
| 候选是目录、FIFO、设备等非普通文件 | 不打开正文；记录非错误跳过诊断，继续下一个 |
| symlink 指向普通文件 | 可以选中；保留入口路径和物理身份 |
| dangling symlink、symlink loop、身份无法判断或权限错误 | 视为该目录候选错误；按 failureMode 处理，不悄悄尝试更低候选 |
| 普通文件大小为 0，或只有 UTF-8 BOM | 选中空内容，阻止同目录低优先级候选 |
| 普通候选已选中，随后读取/解码/单文件预算失败 | 按 failureMode 处理；该目录不再 fallback |
| 同目录候选在大小写不敏感文件系统上指向同一实体 | 仍只选第一个，不重复加载 |
| 只有 `CLAUDE.md` / `CLAUDE.MD` | 无来源；不读取或解析这些名字 |

override 只选择当前目录来源，不删除全局或祖先来源；不同层级的规则按顺序拼接，不解析自然语言来执行所谓语义覆盖。相同内容但物理身份不同的文件仍是两个来源。

### 5.3 重复物理文件

固定流水线为：物理目录链与候选选择 → 有限 Git 元数据验证及作用域遮蔽 → 稳定排序与物理身份去重 → 正文读取与渲染验收。元数据与身份查询也必须检查取消和预算。

先执行作用域选择与 worktree 遮蔽，再按最终全局→祖先→子级顺序去重：

- realpath 用于解析 symlink 别名；不同路径的 hardlink 通过 `Files.isSameFile()` 或具有文件系统范围的可靠身份验证。
- 不把不同文件系统的裸 `fileKey` 当作全局 ID，也不按内容 hash 合并不同来源。
- 只比较已选中的有界来源集合，不维护静态跨 Session 缓存。
- 先出现的候选保留原位置和路径，后者记录 `DUPLICATE_SOURCE` 及关联来源；先出现者随后正文读取失败，也不自动重新启用其重复别名。
- 不能确认身份时按加载错误处理，不默认“不同文件”而重复注入。

候选 symlink 只加载目标正文，不进一步扫描目标所在目录的祖先规则。文件读取前后复核可获得的身份、大小及修改属性；检测到变化时不提交该文件。本阶段不承诺整个目录树具有文件系统事务快照，也不通过无限重试追赶修改中的文件。

### 5.4 Git worktree 的作用域遮蔽

物理去重不能解决两个 checkout 中不同实体却代表同一仓库根规则的问题。仅在下列条件全部得到证实时应用遮蔽：

1. 当前 cwd 属于一个 linked worktree，工作区根通过其 `.git` 文件和 Git 管理目录识别。
2. `commondir`、必要的 `gitdir` 回指及路径关系能证明它关联一个普通主工作树；主工作树的 `.git` 确实对应 common git directory。
3. 主工作树根是当前工作区根的物理祖先，并且仍位于配置的发现链中。
4. 当前 worktree 根有一个按 §5.2 选中的普通 AGENTS 候选。

此时只遮蔽主工作树根的项目来源，保留外层组织规则、中间目录规则和当前 worktree 根及子目录规则。两端候选文件名可以不同，例如主树 `AGENTS.md` 与 worktree `AGENTS.override.md`。显式全局 scope 不被仓库遮蔽规则删除。

空的 worktree 根候选也形成遮蔽。该候选选中后若读取失败，仍不 fallback 到主 checkout 的规则：WARN 模式跳过它并保留遮蔽诊断，FAIL 模式拒绝整个候选快照。worktree 根没有候选时保留普通祖先继承。

普通仓库、兄弟 worktree、bare 布局、submodule、独立的嵌套仓库不能仅凭某个路径包含 `.git` / `worktrees` 就触发遮蔽。无法证明关系就不遮蔽；已识别但损坏/不可读的元数据按 failureMode 报告。

生产实现只读有限的 `.git`、`commondir` 和必要的 `gitdir` 回指，不运行 Git，不递归解析任意元数据链，不加载 config/hook。元数据中的相对路径按它所属文件的 Git 语义解析，不以进程 cwd 猜测；越过 discoveryRoot 的仓库不得改变范围内的作用域选择。解析过程可为验证读取链外的有限元数据，但不会因此加载链外项目 instructions。

## 6. 有界读取、失败与诊断

### 6.1 固定预算

| 项目 | 首批上限 | 超限处理 |
|---|---:|---|
| 单个指令文件原始 UTF-8 字节（含 BOM） | 64 KiB | 单文件错误，不截断正文 |
| 保留的指令文件原始字节合计 | 256 KiB | 整体失败，不丢弃部分祖先以强行继续 |
| 实际保留来源数 | 64 | 整体失败 |
| 项目祖先目录数 | 128，另加最多一个全局目录 | 整体失败，不把半条继承链当成功 |
| 单个 Git 元数据文件 | 8 KiB | 元数据错误 |
| Git 元数据文件读取次数 | 32 | 整体失败 |
| 一次加载实际读入字节总量 | 1 MiB，包含失败读取、元数据及超限检测字节 | 整体失败 |
| 单个来源/诊断路径的 UTF-8 展示长度 | 4 KiB | 来源错误，不用截断路径伪装真实来源 |
| 诊断条目 | 512 | 预留最后一条表示省略；错误状态不能被诊断省略清除 |
| 新增 project_context 区块序列化后 UTF-8 字节 | 2 MiB | 整体失败，计入转义膨胀 |
| 一次加载协作式 deadline | 5 秒，使用 monotonic clock | 检查点到期则取消候选构建 |

预算计数在过滤与错误路径中同样生效；去重前已实际读入的字节也不能从总读取计数扣回。按块读取，每次最多读取剩余额度加一个超限检测字节；先检查普通文件类型，禁止对特殊文件使用阻塞式“试读”。

这些是框架内存、扫描和合作式时间边界，不是任意文件系统调用的硬超时保证。网络文件系统上的属性查询等可能不可中断；不得用“future 已完成”假装底层 I/O 已经回收。无后台轮询、文件监听或无限重试。

### 6.2 编码与变化

- 使用严格 UTF-8 decoder，非法或不完整序列为错误，不替换乱码。
- 仅移除文件起始的一个 UTF-8 BOM；内部 BOM、换行、空白和 Unicode 组合序列保持不变。
- 原始字节预算包含 BOM；不解析 front matter、include、环境插值或 Markdown 中的命令。
- 非法 XML 1.0 控制字符不能原样进入新增标签区块，作为不支持的来源文本报错；不悄悄删除规则中的字符。
- 打开期间文件消失、替换或检测到内容变化时，该文件失败；同目录不降级，整个加载不自动重试。

### 6.3 错误分层

| 错误 | `WARN_AND_SKIP` | `FAIL` |
|---|---|---|
| 已选中文件不可读、编码错误、单文件超限、身份不明或检测到变化 | 跳过整份来源并返回诊断 | 拒绝候选快照 |
| 已识别 Git 元数据损坏或读取失败 | 记录警告，不应用未经证实的遮蔽 | 拒绝候选快照 |
| 目录中没有候选、空文件、正常去重/遮蔽、非普通候选 | 正常结果及必要的非错误诊断 | 同左，不因正常选择行为失败 |
| cwd / 显式目录 / 上界无效，或全局预算超限 | 整体失败 | 整体失败 |
| abort / Session close 取消 | 候选不应用，stage 以 `CancellationException` 结算 | 同左 |
| 加载 deadline 到期 | 候选不应用，以含 `LOAD_DEADLINE_EXCEEDED` 诊断的加载异常结算 | 同左 |
| 线程执行器拒绝、内部契约错误 | 基础设施失败，不伪装成文件警告 | 同左 |

WARN 是显式的 best-effort 策略，不保证所有规则均成功加载；有强约束要求的宿主应选择 FAIL。成功但有警告的 reload 可以替换旧快照，包括某个原来源因失败被跳过的情况；只有整体失败才保证原快照不变。这一区别必须写进公开 Javadoc 和集成测试。

对外诊断采用固定 code，例如 `NOT_REGULAR_FILE`、`SOURCE_UNREADABLE`、`SOURCE_TOO_LARGE`、`INVALID_UTF8`、`SOURCE_CHANGED`、`DUPLICATE_SOURCE`、`SHADOWED_WORKTREE_SOURCE`、`GIT_METADATA_INVALID`、`LOAD_LIMIT_EXCEEDED`。不直接拼接 `IOException.getMessage()`，不打印 stderr；来源通过明确的路径字段查询。路径本身超过展示预算或不能安全表示时只返回固定原因码，省略该路径字段，不保存无限字符串或截断后冒充原路径。

## 7. 纯 Prompt 装配契约

固定顺序如下：

```text
默认基础提示，或 customSystemPrompt
appendSystemPrompt（非 null 时沿用原有拼接语义）
Available tools（来自实际 BuiltInTools 的同一份 ToolSpec 顺序）
project_context（有有效来源时才添加）
Current working directory（沿用原有展示）
```

- custom 的 `null`、空字符串及空白保留原语义；append 不 trim，也不解释成路径。
- 不发现文件或 files 为空时，新 overload 必须与旧 builder 的输出逐字符相同。
- 空的有效文件仍输出一个有来源的空 instructions 区块；它与未发现来源不同。
- 不改变工具名称、说明或顺序；custom 模式仍包含实际工具和 cwd。
- 每个有效来源只渲染一次，不把诊断、revision、mtime、当前日期或 Git 元数据塞给模型。
- 全局与项目来源均置于一个 `<project_context>` 区块中，按 snapshot 顺序输出 `<project_instructions path="...">`。

渲染器对新增来源正文的 `&`、`<`、`>` 做文本节点转义，CR 使用字符引用以免 XML 换行归一化丢失原表示；路径属性另转义引号及换行/制表符等，验证不能安全表示的控制字符和无效 UTF-16。snapshot accessor 保留原始去 BOM 文本，渲染结果使用明确的可逆编码，不执行 normalization。特别覆盖来源正文包含 `</project_instructions>`、伪造兄弟标签和代码中的尖括号。

转义负责保留结构和来源归属，不保证模型会忽略恶意指令。项目文本可以指导模型，但不能修改 `CodingToolPolicy`、工具集合或 Java 配置；本阶段不声称提供 project trust 或模型层面的安全隔离。

Context 内容进入 `AgentContext.systemPrompt` 和模型请求，但不追加到 `AgentContext.messages`、用户/assistant/tool-result 消息或消息事件。纯 builder 不读环境、不做文件存在判断，也不重新 realpath。

## 8. Session 装配、原子 reload 与 core seam

### 8.1 初次创建

启用时先加载候选 sources，再装配真实工具、构建 prompt、创建 Agent，最后发布初始 snapshot。默认关闭时跳过新增 loader I/O；其他构造步骤沿用原逻辑。

文件整体加载失败时构造失败并携带安全诊断；工具 probe 或后续 prompt 构建失败仍由既有 ownership 清理已创建资源。不能因新增 loader 让搜索 backend、进程 executor 或临时文件漏清理。

不增加构造期用户 event 回调；诊断从成功 Session 的快照或构造失败异常取得。

### 8.2 reload 生命周期

Session 增加一个至多一个的 reload operation，与现有 active run 共用 admission lock：

```text
IDLE → 接纳 reload → 锁外读取及构建 → 锁内复核并提交 → IDLE
                          └→ 失败/取消：丢弃候选 → IDLE 或 CLOSED
```

- `reloadProjectContext()` 在 active run、另一次 reload、disabled 或 closed 时同步抛 `IllegalStateException`，沿用产品层接纳风格；不启动 I/O，不自动 abort run。已接纳后的加载/执行器失败才通过返回 stage 表达。
- reload 期间 `prompt()` 不能被接纳，仍同步抛 busy 异常；`isRunning()` 继续表示模型 run 的产品接纳至收尾区间，新增 `isReloading()` 避免混淆。本阶段不新增产品 `continueRun()`。
- steering/follow-up 保持仅在产品 run 已接纳时可调用；reload 期间仍像普通 idle 一样拒绝新入队。原先已入队但未 drain 的消息必须保留，reload 不消费、清空或重排它们，下一次 run 再按原协议处理。
- 所有文件读取、realpath、render 和资源等待在 admission lock 外执行；锁内只做接纳、状态检查及有界引用替换，不调用用户 sink。
- 内部用一个候选对象携带 files、diagnostics、revision 和已渲染 prompt；只在 Session 仍打开且 operation 身份匹配时提交。
- 提交调用 core 的 idle prompt 更新，再发布产品 snapshot，完成后释放 reload 槽位并完成 stage；读取快照的产品入口也受同一接纳边界保护，不能观察到半次提交。
- 成功后消息历史、工具实例、policy、model/options 和 pending queues 保持；下一次模型请求用新 prompt。
- 文件由 write/edit 修改不会自动生效。reload 接纳后再发生的并发文件修改按 §5/§6 处理；不保证跨多个文件的 OS 事务快照。

### 8.3 abort、close 与资源

reload 使用 Session 自有的 `CancellationSource`，不 cast 或取消调用方 signal。`abort()` 在 reload 期间请求取消该操作；无 run/reload 时仍幂等无效。取消先于提交线性化时旧快照保持；提交已经赢得边界时此次 reload 成功，迟到 abort 不回滚。

仅在启用时创建 reload 专用虚拟线程 executor，每 Session 最多一个已接纳加载；先接纳再提交任务，拒绝时释放槽位。取消 listener 只做非阻塞通知/中断，不在 listener 内执行可能阻塞的文件系统操作。

`close()` 先禁止新接纳，取消在途 reload，在锁外关闭/等待其资源，并沿用已有 Agent/工具清理顺序。新增 loader 等待窗口上限为 2 秒；能被中断的 channel 必须回收。若底层文件系统调用不可中断，close 可以在窗口后返回，但迟到 worker 绝不能发布 snapshot、触发模型或重新开放 Session；其 stage 在实际退出并清理后结算。公开文档必须说明这个限制，不能宣称所有文件系统 I/O 均可强制停止。stage 完成回调可以重入 Session；从 reload worker 自身回调触发 close 时不得等待当前线程自己结束，其他资源仍按所有权回收。

本阶段不增加资源加载事件变体；快照、reload stage 和安全异常足以表达结果，避免把新增资源生命周期塞进模型消息事件。

### 8.4 `Agent.updateSystemPrompt(String)` 最小 seam

建议新增同步、仅 idle 可调用的通用方法，具体命名在 PR 4 固定：

- `null` 参数拒绝；closed / active run 时同步抛 `IllegalStateException`，不修改状态。
- 更新成功后，当前 `AgentContext.systemPrompt` 与 `AgentState.context().systemPrompt` 一致；保留 messages、tools、其他状态字段及 pending queues。
- 不调用模型、不调用 transformer/projector/turn hook、不发 run/message/tool 事件。
- 与 `prompt()` / `continueRun()` 的接纳及 close 使用同一原子边界；不能只检查 `activeRun.get() == null` 再无保护赋值。
- 引入必要的短 admission 临界区，但不把 provider、sink、future 等待或 executor drain 放进锁内；已有并发 prompt 的失败 stage 契约不变。
- 更新返回后的两个公开视图应一致；不承诺多次独立 getter 在并发操作期间构成一笔读取事务。

不通过 request-local `ContextTransformer` 长期覆盖一个过期的 core prompt，不借 `PrepareNextTurn` 执行 idle 操作，不销毁重建 Agent 来回避更新接口。后两种方案会破坏生效事实来源或历史/队列所有权。

## 9. 实现拆分与每批验收

PR 1～3 完成数据和纯逻辑，PR 4 才在产品 Session 中启用，PR 5 收口。不能在真实 Session 尚未接入时宣称阶段三完成。

| PR | 交付范围 | 验收重点 |
|---|---|---|
| 1：契约与纯装配 | `context/` 配置/快照/诊断类型，builder overload，旧 Config 构造兼容；未接入的 enabled 配置不得静默声称已加载 | disabled 零新增 I/O，custom/append 精确兼容，空来源与空文件区别，工具同源，正文/路径转义和输出预算 |
| 2：有界发现与读取 | 物理祖先链、global/root 校验、三候选选择、UTF-8/BOM、预算和 failureMode；窄 I/O 测试 seam | 明确拒绝 CLAUDE 兼容，同目录失败不回退，边界值/特殊文件/变化/取消/诊断计数 |
| 3：身份与 worktree | symlink/hardlink 去重，有限 Git 元数据解析，作用域遮蔽 | 不同候选名遮蔽，global 不误删，symlink cwd，普通仓库/兄弟 worktree/bare/submodule/独立嵌套仓库反例 |
| 4：产品装配与 reload | initial load、Session 查询及单 reload 接纳、core idle prompt 更新、abort/close 清理 | fake model 验证初次请求和 reload 后请求；并发接纳与 close 竞态；历史/队列/工具/事件保持 |
| 5：闭环与发布收口 | 产品故障矩阵、真实文件系统测试、资源清理证据、文档与归档 | 全仓与严格 native smoke，测试不可用条件不得被假通过，正式记录结果并归档计划 |

每个 PR 都补本批测试，不能把所有测试推到 PR 5。新增核心类型和方法使用英文 Javadoc；错误类型与诊断有单一来源，不引入 `Map<String,Object>` 或散落的同名候选常量。

## 10. 测试矩阵与完成门槛

### 10.1 必须覆盖的矩阵

| 领域 | 必须覆盖 |
|---|---|
| 候选 | 三候选优先级；override 空文件/BOM-only；同名目录继续；特殊文件不打开；选中后失败不 fallback；仅 CLAUDE 文件不加载；AGENTS 与 CLAUDE 并存只选择 AGENTS |
| 继承 | global→外层→cwd；默认根与显式上界；上界等于 cwd；非祖先根失败；Git 根不截断组织规则；cwd 子目录不递归发现 |
| 路径 | symlink cwd 的物理祖先链；文件 symlink 目标在链外；重复 realpath 与 hardlink；同内容不同实体；dangling/loop；新增路径中的 symlink/`..` 原生解析 |
| worktree | 嵌套 linked worktree、候选名称不同、空 override、无 worktree 根候选、读取失败保持遮蔽；兄弟 worktree/bare/submodule/普通嵌套仓库不误遮蔽；相对 gitdir/commondir、回指失败、元数据预算 |
| 编码与预算 | UTF-8 BOM、内部 BOM、CR/LF/CRLF、组合字符、非法/截断 UTF-8、控制字符；所有预算 N−1/N/N+1；读取中增长/替换、超限字节也计数 |
| Prompt | 旧 overload 精确兼容；custom 空/非空；append 空/空白；真实工具顺序；来源仅一次；关闭标签、引号和特殊路径安全编码；转义后字节预算 |
| 失败策略 | WARN 跳整份且不 fallback；FAIL 整体拒绝；无候选为成功；aggregate 超限不返回半条继承链；错误诊断不泄漏正文/原始异常 |
| 产品闭环 | 仅通过 Session 获取来源；初次模型请求；edit/write 后 reload 前不生效；reload 后生效；来源不进入 transcript 消息或消息事件 |
| 生命周期 | Session run vs reload、reload vs reload、reload vs prompt、close vs commit、abort 前后线性化；core prompt/continue vs idle 更新；两层接纳异常风格保持；executor 拒绝释放接纳；不阻塞事件 sink |
| 状态保持 | 成功/失败/取消后的历史、pending steering/follow-up、工具身份及 policy；core context/state 同步，idle prompt 更新不发模型事件 |
| 清理 | 文件 reader/channel 关闭；候选失败无资源泄漏；Session 构造中后续工具 probe 失败；close 后迟到 loader 不发布结果；完成回调内 close 不自等待；取消观察 future 不释放接纳；不可中断 I/O 使用 latch 可控释放 |

确定性测试使用 JUnit 5、自定义双与 barrier/latch，不用 sleep 轮询。真实目录/文件、symlink、hardlink 与 Git 元数据 fixture 验证平台行为；worktree fixtures 必须同时验证正反例，不用 Git 运行时依赖代替路径语义实现。真实 `git worktree` 创建的 fixture 可作为额外复核，但不是生产依赖。

普通测试在缺少 symlink/hardlink 能力的平台可明确 skip 对应 native 用例；在现有 `local-tools-smoke` 严格模式中，新文件系统能力测试同样不得以 skip 代替支持证据。继续保留阶段二 Bash/ripgrep 严格门槛，不为了纯文档任务或库测试增加 main/应用启动入口。

### 10.2 完成门槛

阶段三只有同时满足下列条件才可标记完成：

1. 实际产品 Session 完成全局/项目指令加载、诊断查询、纯装配和显式 reload。
2. 候选白名单固定且 CLAUDE 名称没有探测/回退路径；没有顺带加载 SYSTEM、settings 或可执行资源。
3. 原 Config、custom/append、工具选择与阶段二行为保持；启用后的规则表全部有测试。
4. 文件大小、累计预算、元数据读取和渲染膨胀有硬边界；时间与不可中断 I/O 的限制如实说明。
5. reload 与 run/close 接纳线性化，无半提交；成功不损坏历史/队列/工具，整体失败或取消保留旧快照。
6. core seam 保持 provider-neutral，不引入产品文件知识；全仓 Enforcer 与测试通过。
7. 真实文件系统及严格 native smoke 有环境、命令、数量和结果记录，不把未运行测试写成通过。
8. 根及涉及模块 `AGENTS.md`、路线图同步实际状态；完成后将本文移入 `docs/plans/archived/` 并修正链接。

实施时的验收命令：

```bash
mvn -pl coding-agent -am test
mvn -pl agent-core -am test
mvn clean verify
mvn -pl coding-agent -am verify -Plocal-tools-smoke \
  -Djcode.test.bash=/absolute/path/to/bash \
  -Djcode.test.rg=/absolute/path/to/rg
git diff --check
```

最初计划编写仅修改文档，未执行代码验收；以下实施记录为后续实际运行结果，不以阶段二的 721 个通过测试代替阶段三交付证据。


## 11. 实施与审查修复记录（2026-09-19）

阶段三五个实现切片已完成：显式配置与不可变来源/诊断契约、有界发现及物理去重、有限 worktree 元数据验证、纯 Prompt 装配、Session initial load/idle reload，以及 core `Agent.updateSystemPrompt()` seam。旧配置默认不新增发现 I/O，不读取 CLAUDE 文件；没有引入通用 Resource Loader、Git 运行时依赖、持久化或 UI。

初次实现全仓 743 个测试通过，但审查的可控 loader 与真实文件系统探针发现以下六类缺陷。本轮累计增加 19 个回归，其中首批 17 个中的 13 个在修复前按预期失败，另外 4 个验证既有边界；修复后再补充 2 个嵌套回调/中断归一回归。

| 已修复问题 | 修复边界与证据 |
|---|---|
| reload stage 完成回调仍看到 busy | 成功提交/失败清理时先在 admission lock 内释放槽位，再锁外结算 stage；成功与失败回调可 prompt，取消回调可再次 reload |
| 已观察到来源变为 FIFO 等类型后仍打开 | 打开前比较所选属性与最新普通文件属性；目录、不同普通文件及真实 FIFO 替换回归均断言不得调用 opener，不靠阻塞试读判断类型 |
| close 超时强行结算仍执行中的加载 | close 仅取消/中断并有界等待，不提前清除 reloading 或结算 future；可控不可中断 loader 释放后才完成清理与取消结算，旧快照保持 |
| 无效来源提前触发保留总量/数量预算 | UTF-8、正文与路径验证后才计入 256 KiB/64 个来源；满额有效内容加非法 UTF-8 仍可 WARN 跳过，失败读取仍计入 1 MiB 预算，额外有效来源仍整体失败 |
| 不可渲染路径到 builder 才整体失败 | loader 同时验证正文与展示路径，WARN 跳过整个选中来源且不 fallback，FAIL 拒绝候选快照 |
| 损坏 commondir 被当成正常缺失 | 先无跟随检查存在性，再按需检查 symlink 目标；目录/悬空链接进入 Git 元数据 WARN/FAIL，真正缺失仍允许普通独立 Git 目录 |

`CodingAgentReloadLifecycleTest` 包含 7 个生命周期回归，`ProjectContextBoundaryTest` 包含 12 个读取与预算回归。前者还覆盖完成回调启动下一 worker 后 close 不自等待，以及 close 中断读取产生的异常在实际清理后归一为取消。`ReloadGate` 用可控屏障模拟不可中断 I/O，不使用 sleep/轮询。`InputOpener` 仅为 package-private seam，实际读取与流关闭仍由有界 reader 负责，不新增公开配置；FIFO fixture 使用 `/usr/bin/mkfifo`，严格 native 模式下缺少能力即失败。

### 11.1 实际验收

环境：Darwin arm64、Java 21.0.10 LTS、Maven 3.9.14、Bash 3.2.57、ripgrep 15.2.0。

- `mvn clean verify`：**762 tests，0 failure/error/skipped**；`ai` 71、`ai-providers` 267、`agent-core` 204、`coding-agent` 220。Enforcer 模块边界通过。
- `mvn -pl coding-agent -am verify -Plocal-tools-smoke -Djcode.test.bash=/bin/bash -Djcode.test.rg=/opt/homebrew/bin/rg`：通过，包含真实 FIFO、symlink/hardlink、进程和搜索 native 回归，无 skip。
- `AgentTest`、`CodingAgentSessionTest`、`CodingAgentReloadLifecycleTest`、`ProjectContextLoaderTest`、`ProjectContextBoundaryTest` 在同一严格 native 配置下连续三轮通过。
- `git diff --check`、归档链接及 UTF-8 检查通过；根与两个涉及模块的知识库、路线图同步。无关 `.idea/encodings.xml`、`.idea/vcs.xml` 不属于本阶段提交。

文件系统属性查询/打开之间仍存在 OS 级竞态，不宣称消除所有 TOCTOU 或保证任意文件系统调用可强制中断。close 的两秒窗口只限制等待，不代表底层加载已退出；实际退出清理前的 stage 保持 pending，迟到加载不得提交。

## 12. 对齐 pi 的行为纠偏（2026-09-20）

在后续复核 pi `60e7e76` 与阶段三实现时，确认第 4、6、8、9、10、11 节将若干防御性实现细节误升格成了产品契约。此次纠偏保留项目上下文功能与 reload 并发骨架，但以下内容取代前文对应的现行行为描述；前文作为当时实施历史不删除。

- `<project_context>` / `<project_instructions>` 只是 prompt 来源边界。`path` 属性继续单独轻量转义，来源正文与 pi 一致逐字拼接；删除正文 XML 转义、XML 字符合法性检查和渲染后字节预算。正文即使包含标签样式文本、控制字符或 `&` 也不由 builder 改写。
- 同目录候选按 `AGENTS.override.md`、`AGENTS.md`、`AGENTS.MD` 逐个检查并读取。缺失、非普通文件、悬空链接、不可读或非法 UTF-8 的高优先级候选不再阻断较低优先级候选；`FAIL` 仅在该目录确有候选失败且最终没有任何候选成功时抛出，并保留底层 cause。显式全局目录真正缺失表示没有全局来源。
- 发现沿调用方配置的 working directory 词法祖先链进行，不再把 real path 祖先链当成用户可见继承关系。real path 仅用于物理身份去重和 worktree 关系比较。
- 只保留普通文件检查、严格 UTF-8/BOM 与一个易解释的 1 MiB 聚合实际读取上限。删除 64 KiB 单文件、256 KiB 保留内容、64 来源、128 祖先、路径、诊断、Git 次数、渲染和五秒 deadline 等重叠限制，也删除多阶段属性/identity 稳定性协议及其 `InputOpener` 测试 seam；不以锁、hash 或另一套可配置安全框架替代。
- linked-worktree 识别改为 best-effort。Git 元数据损坏或关系识别失败只记录诊断并按普通祖先来源继续加载；只有 worktree 根来源已经成功加载时才应用遮蔽，而且只遮蔽主工作树中的同名候选，不扩张为跨候选文件名遮蔽。
- 不可变 snapshot、来源元数据、严格 idle 接纳、reload 原子发布、失败/取消保留旧快照、close 后迟到结果防护、完成回调重入和观察 Future 隔离全部保留。项目上下文关闭时不再创建闲置 reload executor。

对应测试删除了 `ProjectContextBoundaryTest` 中只证明上述旧附加契约的用例，改为直接覆盖候选回退、缺失全局目录、词法祖先、正文原样拼接、单一聚合上限、best-effort Git 识别与同名 worktree 遮蔽；`CodingAgentReloadLifecycleTest` 和 Session 生命周期回归继续保留。针对性命令 `mvn -pl coding-agent -am -Dtest=ProjectContextLoaderTest,SystemPromptBuilderTest,CodingAgentSessionTest,CodingAgentReloadLifecycleTest -Dsurefire.failIfNoSpecifiedTests=false test` 运行 44 个测试，0 failure/error/skipped。全仓 `mvn verify` 运行 762 个测试，0 failure/error，常规配置下 10 个需显式本机工具的测试按预期 skipped；Enforcer 与打包通过。
