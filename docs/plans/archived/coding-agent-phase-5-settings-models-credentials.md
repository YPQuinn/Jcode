# Coding Agent 第五阶段：配置、模型与凭证装配

> 状态：已实施并归档。现行约束见 [`coding-agent/AGENTS.md`](../../../coding-agent/AGENTS.md)、[`runtime-contracts.md`](../../agents/runtime-contracts.md) 与本文件第 19 节实施记录。
>
> 编制日期：2026-09-21（America/Los_Angeles）。
>
> Jcode 实施基线：`3d2bdc0c1402b833104585f999f44415b8891f89`，第四阶段收口基线。
>
> pi 源码基线：`60e7e76bd7ea25cad1dd6f3f1ce0d18814a42759`。本阶段不随浮动分支改变验收语义。
>
> pi-book 辅助参考：`0a8863b611504a47302931ad9adf1cab71a0b79f`，第 13 章；已稳定的 Jcode 契约与固定版本 pi 源码优先。
>
> 建议仓库路径：`docs/plans/coding-agent-phase-5-settings-models-credentials.md`。
>
> 总路线图：`docs/plans/archived/coding-agent-development-roadmap.md`。第 17 节列出必须同步的范围修订。
>
> 本文保留实施前的设计决策和阶段门槛；实际交付入口与验证结果以第 19 节实施记录及现行模块契约为准。本文不是 Issue 或 PR 记录。

## 1. 目标与范围

### 1.1 产品目标

在不改变现有显式 API 的前提下，提供一条配置驱动的 headless 装配路径：读取明确来源的设置，选择模型，解析凭证，装配现有 Provider 与 Models，创建或恢复 Session，并在 idle 边界切换当前模型和 thinking。

主线固定为：

```text
显式配置位置与 SDK 覆盖项
    → 全局设置 + 经授权的项目设置
    → 强类型解析、合并与来源说明
    → 凭证解析、现有 Provider / Models 装配
    → 创建 Session，或从已锁定的 Session 历史选择恢复模型
    → 显式 prompt / continue
    → idle 时变更 model / thinking
    → 按需独立保存默认设置
```

必须分清五组概念：设置与运行状态、凭证与普通设置、历史模型与当前模型、启动回退与请求重试、当前切换与持久默认值。

### 1.2 必须交付

| 能力 | 首版范围 |
|---|---|
| 设置 | 小型强类型 Settings、明确的缺省语义、全局/项目/SDK 合并、字段来源和加载诊断 |
| 项目设置授信 | SDK 明确决定、独立用户级决定记录、无决定/拒绝时不读取项目设置 |
| 凭证 | SDK 显式 API Key、显式启用的环境来源、独立用户凭证文件的只读来源 |
| 模型装配 | 复用现有 OpenAiProvider、OpenAiProviderConfig 与 ai.Models；支持现有显式兼容 endpoint |
| 模型选择 | 完整 ModelRef 选择、本地目录/支持能力/认证配置状态区分、恢复时的可诊断默认模型回退 |
| 会话切换 | idle-only model/thinking 更新，不重建 Agent、工具或 Provider，不改写历史 |
| 默认值保存 | 显式作用域、只更新指定字段、保留其他字段、同步报告保存结果 |
| 模型限制 | 产品层的 contextWindow/maxOutputTokens 信息，为第六阶段提供输入；未知保持未知 |
| 生命周期 | 新工厂创建的资源有唯一所有者；借入 Models/Provider 不被 Session 顺带关闭 |

### 1.3 不交付

不增加新模型厂商协议、OAuth 登录/刷新、密钥写入或轮换、命令取密钥、系统钥匙串/KMS 集成、远程模型目录下载、后台鉴权探测、配置文件监听、Provider 热替换、跨模型请求重试、路由负载均衡、工具集热切换、Compaction 算法、Extension、CLI/TUI/Server。

不建设通用配置平台、Secret 框架、策略注册器、审计系统、租约/心跳服务或自动修复器。小型包内辅助类型可以复用，但不能因为存在两份 JSON 文件就建立通用存储 SPI。

### 1.4 旧 API 不变

`new CodingAgentSession(config)`、现有 `CodingAgentSession.create(config, directory)` 和 `open(config, file)` 继续消费完整显式的 `CodingAgentConfig`。它们不新增设置/凭证发现、不读取环境变量、不自动选择其他模型，也不改变第四阶段的 Session 文件创建和恢复语义。[J2]

新工厂是便利装配路径，不替换旧路径。旧路径借入的 ModelClient/Models 继续由调用方管理；原有项目指令发现和工具 policy 约定不因启用设置系统而改变。

## 2. “pi 如何做 → Jcode 如何映射 → 必要差异”

### 2.1 行为映射表

| 议题 | pi 如何做 | Jcode 如何映射 | 必要差异或范围裁剪 |
|---|---|---|---|
| 设置分层 | SettingsManager 合并全局与项目，再应用 overrides；对象递归合并、数组替换 [P1] | 强类型可缺省 Settings + 纯 resolver + 来源结果 | 不把已补默认值的 CodingAgentConfig 用作局部覆盖 |
| 模型身份 | Settings 分开保存 provider/model 字段 [P1] | defaultModel 使用完整 ModelRef，作为整体覆盖 | Jcode 有 provider/api/modelId 三维身份，不能拼成来自不同层的半套身份 |
| 项目授信 | 独立 trust store；headless 无决定时可拒绝；SettingsManager 本身还有默认 trusted 的底层路径 [P2] | 新工厂先决定是否加载项目设置，再调用 loader | 不照搬低层默认 trusted；仅授权设置，不授权代码资源 |
| 授信路径 | 规范化真实路径，查找最近祖先决定 [P2] | 对显式工作目录真实路径做精确匹配 | 首版不交付父目录批量授信/继承，不修改第三阶段词法指令继承 |
| 模型运行时 | ModelRuntime 包装 pi-ai Models；ModelRegistry 是兼容门面 [P3] | 复用 ai.Models；产品补选择、装配、只读状态 | 不再实现 Provider 路由中心、网络目录缓存或兼容 Registry |
| 凭证 | AuthStorage 与运行时覆盖分离，包含 API Key/OAuth 等 [P4] | 独立解析凭证，适配现有 OpenAiCredentials | 首版只读 API Key 文件，不移植登录、刷新、命令求值或写入队列 |
| 启动选择 | SDK 显式模型优先，再尝试历史，最后默认/可用模型；返回回退说明 [P5] | 显式模型 → 可用历史模型 → 合并后的默认模型 | 默认仍缺失时失败，不任意选择目录第一项，不跨 endpoint 自动试错 |
| thinking | SDK/历史/默认值后按能力 clamp [P5] | 使用既有绝对 ThinkingLevel；明确来源，不静默 clamp | 保持 Jcode PROVIDER_DEFAULT/OFF 与 adapter 错误语义；不复制 wire 映射 |
| 当前切换 | pi 在产品会话和运行时之间更新当前模型/思考参数 [P5、P6] | core 增加窄的 idle model/thinking 原子更新 | 不重建 Agent，不在产品层只改显示值 |
| 变更持久化 | pi 有 Session 元信息与 Settings 默认值写入路径 [P1、P5] | 当前值、第五阶段默认设置、第四阶段历史记录各自独立 | 沿用 Jcode“实际使用新配置产生消息时记录差异”，不为浏览选择制造历史 |
| 保存 | 作用域锁内重新读取，只合并被修改字段 [P1] | 同步、显式字段更新，小型固定 JSON 写回 | 不复制 Promise 写队列、同步忙等或虚构旧格式 migration |
| 资源与默认行为 | pi SDK 默认发现用户目录并创建所需运行时 [P5] | 文件位置与是否装配明确；默认内存 Session；资源有显式所有者 | 保留可嵌入 Java 库的无隐式启动行为 |

P2 的授信范围与 P1 的底层默认参数不能混为一谈；“pi 有这个方法”不代表其默认值适合直接成为 Jcode 的新产品入口。

### 2.2 复杂度边界

保留正常类型校验、字段来源、已存在的消息/凭证脱敏约定、同步接纳、资源关闭和针对已知文件锁行为的必要协调。

不增加内容安全过滤、全环境快照、配置哈希链、签名验证、递归权限巡检、多维读取预算、统一 OperationScheduler、自动 retry/fallback 管道、反射类加载或 ServiceLoader 扫描。

如果实施确需偏离本表，先给出可复现的失败场景和最小改动；不能以“更安全”“以后扩展”“测试方便”作为独立理由。

## 3. 结构、入口与依赖

### 3.1 组件职责

主体仍在一个 `coding-agent` Maven 模块中。值类型可放在 `settings/`、`model/` 等子包；必须访问包内 SessionManager 的工厂放在产品包内，不为目录整齐而公开 Manager。

| 组件 | 职责 |
|---|---|
| `CodingAgentSettings` / `ResolvedSettings` | 可缺省设置、有效值及字段来源；不含 Provider 或凭证 |
| `SettingsLoader` / `SettingsResolver` | 显式文件读取与纯合并分离 |
| `SettingsFiles` / 固定字段更新值 | 目标作用域的读—修改—写回；不操作正在运行的 Session |
| `ProjectTrustStore` | 精确项目身份的 ALLOW/DENY 保存、查询和删除；不执行项目代码 |
| `CredentialResolver` | 固定几类凭证来源解析；不管理 OAuth 或运行时轮换 |
| `ProviderDefinition` / `ModelProfile` | 非秘密连接定义、静态模型定义及产品限制；不执行网络探测 |
| `CodingAgentSessionFactory` | 从设置和定义装配资源，选择模型，创建/恢复现有 Session |
| `SessionCreationResult` / `ModelSelection` | Session、解析诊断与选择结果；不返回密钥或可变 Manager |

名称可以按现有包约定微调；不得将每一行都进一步拆成接口、默认实现、注册器和策略工厂。

### 3.2 依赖规则

本阶段在 `coding-agent` 显式加入对 `ai-providers` 的依赖，并同步本模块的 Maven Enforcer 与架构说明；总路线图已为第五阶段预留这一接入。[J1]

`ai` 与 `agent-core` 不反向依赖 `coding-agent` 或具体 Provider。core 新接口仅使用 `ModelRef`、`ThinkingLevel` 等既有 provider-neutral 类型；文件路径、凭证、ProjectTrust、ModelProfile 不进入 core。

### 3.3 两种装配方式

新工厂支持二选一：借用调用方提供的 `Models`；或从用户级 Provider 定义与独立凭证创建本次 Session 专用的 `DefaultModels`。二者不能同时提供，也不自动混合相同 providerId 的多个来源。

借用模式不读取 `models.json` 和 `auth.json`，也不替调用方创建或关闭 Provider。旧任意 ModelClient 的用法继续走原显式 API。

文件模式仍须明确指定 Session 目录或现有文件。新工厂提供 `inMemory`、`create`、`open` 三种入口或等价的明确选择，不用一个布尔组合隐含“自动恢复最近会话”。

```java
// 实施入口；options 不是已填满默认值的 CodingAgentConfig。
CodingAgentSessionFactory.inMemory(options);
CodingAgentSessionFactory.create(options, sessionDirectory);
CodingAgentSessionFactory.open(options, sessionFile);

// 结果包含已创建的 Session、有效设置/模型选择和无秘密诊断。
// 关闭 result.session() 足以关闭工厂为该 Session 拥有的资源。
```

创建/open 是同步的一次性装配，调用方可在自己的工作线程执行；不为文件解析、每次选择或每个 Session 新增装配线程池。工厂不发模型请求或工具调用。

## 4. 设置来源与读取规则

### 4.1 显式位置

新工厂要求显式工作目录；可选的 `userConfigDirectory` 由宿主提供。不提供时，不推导 `~/.jcode`，也不扫描 HOME。提供后可读取以下固定文件：

| 文件 | 职责 | 首版写入 |
|---|---|---|
| `<userConfigDirectory>/settings.json` | 全局偏好 | 显式字段保存 |
| `<workingDirectory>/.jcode/settings.json` | 当前项目偏好 | 授权后显式字段保存 |
| `<userConfigDirectory>/models.json` | Provider/模型定义 | 只读；SDK 可以提供替代定义 |
| `<userConfigDirectory>/auth.json` | providerId 对应的 API Key | 只读；不创建、不修改 |
| `<userConfigDirectory>/trust.json` | 已保存的项目设置授信决定 | 显式 remember/remove |

只有新工厂会执行上述固定文件读取。项目设置只读当前显式工作目录这一处，不沿祖先找 settings，不自动识别 Git 根目录，不加载 `.pi` 或其他产品配置。

`ProjectContextConfig` 仍由宿主显式传入；本阶段不将用户配置目录自动接到 AGENTS 全局来源，也不让项目设置改变第三阶段的发现路径、边界或开关。

### 4.2 读取顺序

先读取全局偏好及独立授信决定，得到项目加载决定，再决定是否打开项目设置。SDK 明确 ALLOW/DENY 时不必读取 trust store；UNSPECIFIED 才查询已启用的 store。

没有授信或明确拒绝，不打开、不解析项目 settings，仅返回 `PROJECT_SETTINGS_NOT_APPLIED` 及决定来源。项目文件中的 `trusted=true`、store 路径、凭证来源等不参与授信决策。

缺失的可选 settings 文件按空层处理，不为了读取创建目录或文件。文件存在但不可读、不是所需结构或已知字段非法时，该层不应用并返回诊断；不能静默应用合法字段、丢掉同层非法字段后假装整层有效。

显式 SDK 覆盖项非法直接失败。最终无有效模型选择也失败，不把配置错误转成自动外部模型选择。

## 5. 首版 Settings 模型与合并

### 5.1 已知字段

| 字段 | 类型与语义 | 内建缺省 |
|---|---|---|
| `defaultModel` | 完整 `{provider, api, modelId}`，整体覆盖 | 不指定模型，不硬编码在线服务 |
| `defaultThinkingLevel` | 既有 ThinkingLevel 的稳定文本值 | `PROVIDER_DEFAULT` |
| `defaultTools` | 既有内置工具名的列表，整体替换 | 现有 `CodingToolConfig.readOnly()` 的工具集合 |
| `steeringMode` | 既有 QueueMode | `ONE_AT_A_TIME` |
| `followUpMode` | 既有 QueueMode | `ONE_AT_A_TIME` |
| `request.maxOutputTokens` | 既有 ModelRequestOptions 的正整数语义 | 不指定 |
| `request.temperature` | 既有有限、非负数语义 | 不指定 |

SDK 继续可传工具 policy、工作目录、ObjectMapper、Clock、event sink、custom/append prompt、ProjectContextConfig 和完整请求参数。它们不是项目 JSON 中可实例化的对象。

首版 JSON 不配置 toolChoice、缓存亲和参数、动态工具定义、shell 初始化脚本、网络代理、HTTP header、Provider 端重试或 Extension；高级能力仍通过现有显式对象传入。它们不因为缺少 JSON 字段而被删除。

### 5.2 缺省不等于默认值

Settings 中“字段缺失”表示这一层没有意见。JSON `null` 不作为第四种继承模式：对首版已知字段应报类型错误；删除本层字段由显式保存更新表达。

`defaultTools: []` 表示明确不选择任何工具；不能按缺省替回只读工具。`OFF` 与 `PROVIDER_DEFAULT` 都是显式绝对值，不能当作 absence。未指定 SDK 值不能覆盖磁盘值。

不能从一个已经补齐默认值的 `CodingAgentConfig` 反向推断用户是否明确设置过字段。新 SDK override 类型保留 presence；完成解析后才创建 CodingAgentConfig。[J2]

### 5.3 合并顺序

普通偏好：`内建缺省 < 全局 settings < 已授权项目 settings < SDK 覆盖`。

SDK 明确覆盖模型或 thinking，就视为本次明确选择，优先于历史恢复；不再同时提供另一组含义模糊的“SDK 临时默认模型”字段。文件中的 defaultModel/defaultThinkingLevel 只是默认偏好。resolver 必须保留这一来源区别，不能在合并后把所有模型值一律当成 SDK 明确选择。

标量高层覆盖；`request` 按首版已知叶字段合并；工具列表整体替换；ModelRef 整体替换，不把上一层 provider 与下一层 modelId 拼起来。来源记录到字段即可，不建立配置依赖图。

如果 SDK 传入完整 `ModelRequestOptions`，其中已知值按显式参数覆盖 JSON，其他 SDK-only 选项原样保留；不能因为合并了 temperature 而丢失 ToolChoice 或 PromptCacheOptions。SDK 的“完整替代请求配置”和“局部设置覆盖”必须采用不同入口或明确的互斥输入，不能猜测哪种含义。

工具选择只决定构造哪些既有工具；调用权限仍由当前 policy/hook 决定。授信不产生默认 ALLOW_ALL policy；项目文件不能构造或替换 Java policy。

### 5.4 未知字段与诊断

Settings 已知字段非法使该层失败；未来未知字段可保留原 JSON 并给出“不支持、未应用”的说明，不能自动将未知数据送入运行时。

明确禁止在普通 settings 中放置的秘密/执行/连接字段（如 apiKey、headers、baseUrl、credentialSource、trust decision）不能当作成功应用的未知字段；报不含值的范围错误。不能为此扫描所有自然语言内容或递归识别疑似密钥。

解析结果返回有效值、字段来源、作用域/文件位置及错误类别。诊断不含整个配置对象或原始 JSON 行；引用文件路径不等于允许在异常中携带文件正文。

## 6. 项目设置授信

### 6.1 最小决定模型

决定为 `ALLOW`、`DENY`、`UNSPECIFIED`；SDK 决定优先于已保存决定。无保存记录视为 UNSPECIFIED，实际不加载项目设置。headless 无交互提示、无首次自动授信。

独立 trust store 只保存 `规范项目路径 → allow/deny`。remember/remove 是明确操作，不因用户本次 SDK ALLOW 自动写盘。不持久化失效时间、风险级别、文件摘要、批准人或租约。

### 6.2 项目身份与范围

身份是本次显式工作目录的 `toRealPath()`，不是项目文件提供的名字。符号链接指向相同目录时共用决定；换到其他目录不继承决定。不能把工作目录字符串前缀相似当作同一项目。

首版仅精确匹配，不采用 pi 的祖先授信继承，不将 `/work/team` 的决定自动扩展到其所有子目录。[P2] 调用方需要另一目录时显式作决定。

trust 文件由用户级装配指定，不能由项目 settings 重定向。用作已保存授权依据的 store 应位于本项目之外的用户控制位置；若物理位置落在项目目录内，拒绝用它建立持久授信并给出诊断，调用方仍可使用 SDK 明确决定。这是控制决定来源，不是对工具文件访问实现 sandbox。

目录权限无法确认、store 不可读或内容损坏时，不从该 store 得出 ALLOW；不阻止调用方以明确 SDK 决定使用项目设置。不要递归巡检整个祖先目录、重写现有 ACL 或检查 Git remote。

### 6.3 授权范围不扩散

ALLOW 只允许采用本阶段的项目偏好；不授权 Provider 定义、凭证、Java 类、SYSTEM 文件、skills 或 Extension。第三阶段 AGENTS 行为不由这份决定隐式改变。第七阶段对执行资源另行定义范围。

改变已保存决定只影响后续明确加载/装配；本阶段没有文件监听，也不在每次 prompt 前重新读取决定。正在运行的 Session 不被另一个保存操作悄悄重配。

## 7. Provider 定义与凭证来源

### 7.1 设置不定义连接目的地

普通全局/项目 settings 只选择模型偏好。Provider 定义来自 SDK 或显式用户级 `models.json`；项目不能增加 Provider、改 endpoint、选择另一个 auth 文件或设置环境变量名。

以 providerId 标识一个已装配的连接实例。两个不同 endpoint 使用不同 providerId，可以共享现有 OpenAI Responses adapter，但凭证不会因 API 类型相同而自动跨实例复用。

SDK 的某个完整 Provider 定义可替换用户文件中的同 ID 定义，不将两个定义逐字段混合；凭证始终针对最终定义的 providerId 按第 7.4 节解析。SDK 属于显式装配边界，调用方需要保证其指定目的地与凭证匹配；工厂不推断跨 endpoint 密钥可通用，也不另建凭证—域名绑定策略。需要隔离两个目的地时使用不同 providerId。

### 7.2 首版 Provider 定义

只支持现有 OpenAI Responses 适配器对应的固定配置形式。非秘密字段覆盖 providerId/name、api、baseUrl、显式 endpoint compatibility、静态模型列表、现有 allowUnlistedModels，以及模型能力引用。[J4]

模型能力沿用 `OpenAiModelCapabilities` 的表达及 adapter 映射。Provider retry、service tier、pricing 和其他已有高级选项可通过 SDK 对象提供，首版不要求把所有 builder 字段都 JSON 化。

HTTP header 值通过独立的显式认证装配提供，不进入普通 Settings 或公开诊断。复用 OpenAiHeaders 的既有校验与显示规则，不重新做任意头部覆盖和第二套 header merge。

不从 URL 或模型名称推断 official/custom compatibility；不默认改变现有 retry 策略。允许符合现有协议的显式本地 HTTP endpoint，不新增“所有 URL 必须 HTTPS”之类影响现有使用的策略。

### 7.3 首版用户级定义文件的固定形状

`models.json` 的根对象只在 `providers` 下描述固定 Provider 定义；键是 providerId。相同文件中重复定义同一逻辑 ID 属于错误，不让最后一条静默覆盖。SDK 提供的完整定义可按第 7.1 节替换同 ID 定义。

| 定义字段 | 首版含义 |
|---|---|
| `adapter` | 固定 `openai-responses`；不是 Java 类名，不动态加载实现 |
| `name` | 展示名称，可缺省为 providerId |
| `api` | ModelRef 的 API 标识；与该定义下生成的 Model 必须一致 |
| `baseUrl` | 显式连接地址，复用现有 Provider 的 URI/请求构造约定 |
| `compatibility` | 显式映射已有 OpenAiResponsesCompatibility，不按地址猜测 |
| `apiKeyEnv` | 可选的环境变量名；只有宿主已启用环境来源时读取 |
| `allowUnlistedModels` | 显式布尔值；缺省沿用现有 Provider builder 的默认值 |
| `models` | 模型列表；每项含 `id`、可选 `name`、可选 `capabilities`、可选 `profile` |

每个模型的 provider/api 从所在定义确定；`capabilities` 的字段和语义直接映射现有 OpenAiModelCapabilities，不建立并行的能力语言；`profile` 只包含第 8.3 节两个可选限制。缺省能力沿用现有 Provider 的能力缺省，不通过模型名称补齐。未知 adapter、已知字段类型错误或重复模型 ID 明确失败。

`auth.json` 固定为 `{ "providers": { "<providerId>": { "type": "api_key", "key": "<secret>" } } }`。`<secret>` 仅是格式占位说明，不是可使用的凭证。首版不接受 OAuth、任意认证对象或普通设置混入；环境变量名不在此再次定义，避免同一来源有两套解析入口。

`trust.json` 固定为 `{ "projects": { "<canonical-project-directory>": true } }`，值只能是 true/false；没有键表示无决定，撤销时删除键，不保存 null。以上为 Jcode 首版格式，不尝试自动识别或导入 pi 的同名文件。

不要求为这些新格式预设 migration 或类型注册表；格式定义与固定 fixture 应一起交付。

### 7.4 凭证固定优先级

本阶段明确采用：`SDK 本次显式凭证 > 显式启用的环境来源 > 用户 auth.json`。这是 Jcode 的产品决策，不声称是所有 pi 认证路径的逐项复刻。

每个 providerId 独立解析：只有更高层来源缺失才查看下一来源；高层已给出空白/非法值属于错误，不当成缺失。环境值只有在 Provider 定义或 SDK 明确指定变量名并启用环境读取时才参与，不根据 providerId 扫描整个进程环境。

环境适配可以接受一个窄的 `name → value` 查询函数；测试使用内存映射。生产 convenience 明确绑定 `System.getenv`，不在 ai 或 ai-providers 内加入隐藏环境读取。

凭证文件按 providerId 保存固定 API Key 记录，不支持任意命令、`${...}` 插值、文件间递归引用、OAuth 对象或类名。SDK 凭证和环境读取获胜时，不为了“核验全部来源”打开不需要的 auth 文件。

### 7.5 错误与持久化

选择到某份凭证以后，401/403 不触发下一来源，不轮流试密钥，也不跨 endpoint fallback。凭证的有效性由真实请求结果表达；本阶段不发送验证请求来“预热”认证状态。

文件缺失/没有该 providerId 可视为凭证缺失；已选择的凭证文件读取失败或记录非法必须报告来源问题。未被选择的 Provider 缺少凭证可以出现在装配诊断中，不必阻止其他有效 Provider 使用。

首版 auth 文件只读，不提供 saveKey/login/logout，不创建 auth 文件，不声称保存到系统安全区。API Key 只进入已有秘密持有类型和 Provider 私有配置，不进入 ResolvedSettings、SessionEntry、ModelProfile、日志或事件。[J4]

用户自己在消息或工具输出中包含的秘密不由配置层识别或改写。历史仍按第四阶段原样保存，不能用删改历史来制造“绝无敏感内容”的承诺。

## 8. 模型装配、目录与产品限制

### 8.1 复用 Models

工厂创建的运行时优先使用已有 `DefaultModels`；Session 的 ModelClient 就是这份 Models 路由视图。不新增 ModelRegistry、ModelDispatcher 或静态全局 Provider 集合。[J3]

Provider 定义中的静态目录用来构造既有 `Model`；同一 ModelRef 不重复注册。未具备凭证的 OpenAI 定义不构造假 Provider，不用占位密钥满足 OpenAiProviderConfig 构造约束；记录该定义不可装配的原因即可。[J4]

配置定义和已实例化的 Models 必须在结果中区分：前者表示用户声明过什么，后者才具有运行路由。不可用定义的说明可以附在只读目录结果中，但不维护第二个可变目录和第二套路由规则。

### 8.2 目录、支持与认证状态

| 概念 | 信息来源 | 不能推导的结论 |
|---|---|---|
| 已列出模型 | `Models.models()` / `model(ref)` | 不代表已通过远端鉴权 |
| 支持调用 | 对应 `ModelProvider.supports(ref)` | 不代表一定出现在展示目录 |
| 凭证已配置 | `ProviderAuth.isConfigured()` / 既有 AuthCheck | 不代表密钥未过期、网络可达或账号有额度 |
| 真实请求成功 | 既有 adapter 的运行结果 | 不形成永久健康状态 |

显式 ModelRef 和历史模型可在 allowUnlistedModels 允许时调用，不因目录未列出而拒绝。此类模型的产品限制可以未知；不编造 ModelProfile。

目录显示与普通启动只读取本地定义及既有快照。复用 Models 的 checkAuth/refresh API 时必须保持其当前含义；只有宿主明确请求允许网络的刷新，才可让支持该能力的 Provider 执行对应操作。首版本身不新增网络目录实现、定时刷新或隐式 auth probe。

### 8.3 ModelProfile

ModelProfile 用完整 ModelRef 关联产品限制，首版只需可选 contextWindow 和 maxOutputTokens。字段为已知正值或 absent；不能用 0/统一默认窗口表示未知。

本计划的 contextWindow 表示用于上下文管理的总 token 窗口；不能把只有输入上限、含义不同的供应商数字未经说明直接填入。无法可靠确认对应含义时保持 absent，第六阶段不据此自动估算阈值。

来源为 SDK 或用户级模型定义，不来自项目偏好或模型名称启发式。SDK 对同一模型的 profile 整体覆盖文件定义，避免将不同规格的部分字段拼接。请求值与模型能力不同：`request.maxOutputTokens` 是本次请求，profile 是限制。

有可靠限制时可检查显式输出请求是否超过已知上限；不修改用户请求以自动“适配”。限制未知不阻止本阶段普通对话，第六阶段需要据此决定能否自动 compaction。

不将价格、OpenAI wire 字段、reasoning effort 映射或 endpoint 特性复制进产品 profile；它们仍归已有 Provider 类型。历史消息的 sourceModel 和 opaque replay 不被当前 profile 重写。[J4]

## 9. 新建与恢复的模型选择

### 9.1 ModelRef 的优先级与失败行为

| 场景 | 选择规则 |
|---|---|
| SDK 明确指定本次模型 | 只尝试该完整引用；不支持/无法配置认证时失败，不静默换成历史或默认 |
| 新建，SDK 没有指定 | 使用已合并的 defaultModel；没有默认则返回“未选择模型” |
| 恢复，SDK 没有指定 | 优先使用选定历史分支中的 model；不可用时尝试合并后的 defaultModel |
| 历史不可用且默认相同或也不可用 | 明确失败，返回两次选择的原因；不反复尝试同一个引用 |
| 借入任意 ModelClient 的旧 API | 使用原完整 config，不增加这一自动选择过程 |

本地“可用”指 Provider 已装配、supports(ref) 为真、认证配置符合其已有要求；不表示已经远端验证。

fallback 只发生在新工厂恢复选择阶段。不把请求异常、额度耗尽、限流或模型输出错误解释为“换一个厂商继续跑”。不扫描目录选第一个模型，也不新增 fallback 策略接口。

### 9.2 thinking 的来源

优先级为 SDK 本次明确 thinking；否则，如果成功恢复了历史模型且历史有 thinking 记录，使用历史值；否则使用合并后的 defaultThinkingLevel，最后为 PROVIDER_DEFAULT。

若恢复时换了模型，或 SDK 明确选择了另一模型，不自动沿用旧模型的高 thinking 值。首版不增加 per-model thinking 默认字典；需要特殊值由 SDK 明确给出。

不静默 clamp；既有 adapter 负责具体 wire 能力的最终映射。不在产品层复制 reasoningEfforts 分支。配置值合法但目标协议不接受时，仍走既有 terminal mapping failure，不伪装为成功降级。[J4]

### 9.3 恢复文件只取得一次所有权

工厂 open 应复用第四阶段的包内打开路径：取得 SessionManager 和文件写所有权，读取这同一 Manager 的选定分支元信息，完成模型选择，再把该 Manager 交给 CodingAgentSession。

不能先用 list/无锁读取猜历史模型，再第二次 open 得到另一份历史；不能为预读额外打开并关闭活动 writer 的通道。选择、装配或 Session 构造失败时，释放本次 Manager 及新建资源，不改写原 Session 文件。[J6]

普通创建先完成设置/凭证/模型选择，再创建新的 Session 文件，避免纯装配错误留下无意义文件。没有新消息时不写 model/thinking 差异记录。

### 9.4 不泄漏的选择结果

返回 requested/restored/selected ModelRef、thinking、来源及 fallback 原因。可用原因类别如“Provider 未装配”“模型不受支持”“缺少凭证”，不返回 key、header 值、环境值或整个异常响应。

选择模型是显式产品行为：返回的 Session 当前模型必须与选择结果一致，不能只改诊断或显示信息而底层仍使用原 config。

## 10. idle 模型与 thinking 更新

### 10.1 core 的最小更新

当前 Agent 在每次运行构建 AgentLoopConfig 时读取初始 config 的 model/thinking，因此必须增加真实的运行参数状态，而不是只修改 CodingAgentSession 的字段。[J5]

建议的窄接口：

```java
// 已实施；二者一起更新，避免半套 model/thinking。
void updateModel(ModelRef model, ThinkingLevel thinkingLevel);
```

core 内使用一个不可变的当前选择值，初始值来自 AgentConfig。update 与 prompt/continue/close 共用 admission lock；active run/closed 时失败。在 run 接纳时捕获这一对值并传入本次 AgentLoopConfig，不能在 worker 中再读取可能变化的当前选择。

接口不读取文件、调用 provider/checkAuth、构造客户端、更新 Settings 或发出新 run 事件。它不更改 messages、systemPrompt、tools、pending queues、request options 或历史错误信息。

既有 PrepareNextTurn 对单次 run 的参数更新语义不改变，不借此新增跨 run 自动继承规则。Standalone Agent 仍不了解产品层模型目录或设置来源。

### 10.2 产品入口与原子提交

产品可以提供 `setModel(ref, thinking)` 和仅改 thinking 的窄入口，返回新的只读 ModelSelection；确切名称在 5A 固定。返回“当前模型”时不能继续把初始 CodingAgentConfig.model 当成事实来源。

模型选择与 prompt/continue/reload/history operation 互斥接纳。接纳后在锁外完成本地 supports/状态校验；锁内复核 open，调用 core 更新，并同时发布产品当前选择。失败保留旧选择，不重建 Agent 或 Provider。

使用新工厂/Models 路由时，本地校验注册与 supports；旧任意 ModelClient 路径没有目录可查，应明确由调用方负责目标模型可用性，不猜测其能力，也不自动创建 Provider。合法显式选择可以更新，同一 ModelClient 仍负责处理后续请求。

不为 model change 增加异步选模服务、通用资源热更新事务或 Provider 替换队列。既有文件 writer 不可用时，新产品变更按当前历史可写性要求前置失败，不留下“能改模型、下一步必定无法记录”的假成功路径。

### 10.3 历史与分支

切换当前模型不直接写 model_change/thinking_level_change，不修改默认设置。下一次实际 MessageCompleted 接纳前，沿用 SessionManager 的配置差异追加逻辑，记录该 run 真正使用的值。[J6]

产品事件适配不能仍捕获构造时 final model/thinking；应使用本次已接纳 run 的固定选择。provider 的消息 sourceModel 仍保留原消息事实，不被产品选择覆写。

branch/reset 保留当前显式选择和 pending 队列。历史分支的旧 model/thinking 用于显示和恢复选择，不在浏览历史时自动切回。重新打开文件产生新对象时，按第 9 节重新选择。

只切换后关闭、尚未产生新消息时，不保存这次临时选择。需要永久默认值时执行第 12 节保存；不增加当前模型 sidecar 或光标文件。

## 11. 资源所有权与关闭

### 11.1 区分自建与借用

| 资源 | 新工厂自建 | 调用方提供 |
|---|---|---|
| OpenAiProvider / 其 adapter 资源 | 新 Session 拥有 | 调用方拥有，不自动关闭 |
| HTTP client 等连接依赖 | 工厂显式创建并按已有类型契约管理 | 不顺带关闭共享对象 |
| Models 集合 | 使用自建 Provider 的固定路由视图 | 借用，不取得其 Provider 所有权 |
| SessionManager、工具集和 Agent | 保持原 Session 所有权 | 不对外开放内部 Manager |
| Settings/Trust 文件句柄 | 单次操作内关闭 | 不作为 Session 长期运行资源 |

不使用“instanceof AutoCloseable 就全部关闭”的推断，不给所有 Models 增加新的所有权接口；工厂只跟踪自己实际创建的资源。

### 11.2 构造失败与 close

按创建顺序记录本次资源，失败时逆序关闭，保留主异常及安全的清理错误。文件恢复失败不删除已有 Session 文件，部分 Provider 装配失败不泄漏已创建客户端。

Session close 先禁止新接纳，按既有取消和终结协议结束 active run；自建 Provider 的释放接入真实 run 的完成路径。不能因为 Agent.close 的有限等待已经返回，就提前关闭仍在使用的 owned client 或让历史 writer 过早释放。

只有 run 已真实收尾，才能执行依赖于该 run 结束的最终资源释放；已关闭时迟到的模型更新不发布。完成回调里 close 或进入下一次允许操作的行为必须有回归。复用已有收尾与拥有资源列表，不新增后台清理线程或延迟回收服务。

借入 Models 的外部变更和生命周期仍由宿主管理；本阶段不为多个 Session 共享可变 Models 建立全局事务。自建 DefaultModels 在 Session 生命周期中不进行 Provider 热替换。

## 12. 保存默认值与授信决定

### 12.1 独立保存，不隐式影响当前会话

保存为同步、显式操作。调用方明确选择 GLOBAL/PROJECT 并给出要 SET 或 REMOVE 的首版字段；工具列表整体替换，request 字段可以只更新 temperature 或 maxOutputTokens。

使用固定强类型更新表达，不使用任意 JSON Patch 或字符串脚本。REMOVE 表示删除目标层字段，之后自然继承低层；不向文件写一个含糊的 null。相同字段的冲突更新应在构造更新请求时拒绝。无更新时直接返回，不创建锁文件、目录或临时文件。

保存只改变目标文件，不自动重新解析所有 Session、不切换当前模型、不写 model_change。当前 Session 已选 B、保存默认值失败时，B 仍是当前选择；不尝试跨磁盘和运行时回滚。

项目保存需要本次有效授信决定。显式 SDK ALLOW 只授权本次读写，并不自动 remember。remember/remove trust 是另一明确操作，不能写进项目 settings 自授信。

### 12.2 固定的读—修改—写回流程

```text
检查作用域和强类型更新
    → 取得该目标的短时写所有权
    → 重新读取磁盘当前 JSON
    → 保留未修改字段（含未知字段）
    → 只 SET/REMOVE 请求字段并验证目标层
    → 同目录临时文件写完
    → 原子替换目标文件
    → 释放所有权，返回保存结果
```

不将 ResolvedSettings 整体序列化回全局或项目层。多个调用方修改不同字段时，每次取得锁后重读，后续成功写入保留前一次的不同字段；相同字段以实际成功提交顺序为准。

当前文件损坏时明确失败，不重置为空对象、不覆盖修复。加载和保存使用固定 JSON codec；未知数字等值保留 JSON 数据意义，避免默认浮点收窄带来隐含修改。

### 12.3 文件协调：不用 Session 长期 writer 协议

配置是短时替换，Session 是长期追加，不能直接复用 SessionFileAccess 或把它泛化为全仓文件管理器。

首版采用目标旁的稳定 `.lock` 文件作为写锁承载对象：在同 JVM 打开它之前，以规范化目标身份取得包内短时 reservation，再取得该 `.lock` 通道的 `tryLock()`。本地或跨进程冲突立即报告，不忙等、不 sleep 重试。全局结构只保护登记与释放，不能覆盖文件读取、写入或关闭。[K1]

读 settings/trust 数据不打开 `.lock`，也不借用 writer 的通道。锁文件不在每次释放后删除，避免不同写者锁住不同 inode；锁状态由文件锁决定，不由空文件是否存在决定，不需要心跳、过期清理或 PID 文件。

规范化沿用真实目标路径；目标未创建时使用真实父目录加文件名，保证常规符号链接别名一致。首版不宣称任意硬链接别名和非合作外部写者可共同更新同一逻辑配置，不为此扫描文件系统或增加身份校验协议。

settings 与 trust 可以复用一个小型包内 JSON 更新辅助函数；不抽象成可扩展 Repository、LockManager、事务或通用存储插件。

### 12.4 原子性和失败含义

同目录临时文件用于避免普通读者读到原地截断后的半份 JSON；使用文件系统支持的原子替换。遇到不支持原子移动或替换失败时明确报错，不悄悄改成先截断原文件。[K2]

替换成功前失败保留原文件，尽力清理本次临时文件；替换已成功但后续释放/清理报错，应说明“目标已更新、清理失败”，不能宣称未保存或自动重试。可以使用内部提交标志表达，不建立事务恢复状态机。

不承诺多文件原子提交、断电不丢或自动回滚。保存默认值与保存授信分别提交，不能把两者包装成假事务。

### 12.5 文件保护范围

新创建的用户级私有目录与 trust 文件在支持 POSIX 的平台采用用户私有权限；临时 trust 文件与目标保持相应保护。普通项目 settings 可以被版本控制，不自动 chmod 整个项目。

读取已有 auth/trust 时，复用最小文件属性检查；POSIX 上明显允许其他用户写 trust，或允许其他用户读写凭证文件时，不将其宣称为受保护来源，报告可操作的权限问题。不要自动修正管理员管理的 ACL 或递归检查祖先目录。[K2]

非 POSIX 平台由宿主提供其已保护的用户级位置，诊断说明未执行 POSIX 检查；本阶段不实现 ACL 编辑器，不伪造“已验证安全”。SDK 显式凭证和授信始终是无需文件管理的替代路径。

## 13. 错误、诊断与敏感值

### 13.1 错误不能掩盖选择

| 失败 | 处理 |
|---|---|
| 可选 settings 缺失 | 空层 |
| settings 非法/不可读 | 该层未应用 + 诊断；有效 SDK/低层设置可继续 |
| 项目未授权 | 不读项目设置，不将文件内容用于诊断 |
| Provider 定义或显式 SDK 值非法 | 明确装配错误；不猜另一 endpoint |
| 当前候选缺少凭证 | 标记不可用；仅恢复选择允许按第 9 节改用默认 |
| 已选来源格式/读取错误 | 报来源失败，不当作空凭证自动再试 |
| 远端鉴权或网络失败 | 原 adapter 错误/既有重试，不执行产品层换模型 |
| idle 更新校验失败 | 旧 model/thinking、历史和默认文件都不变 |
| 设置写锁冲突/写回失败 | 保存失败或明确提交后清理失败；当前 Session 不变 |

### 13.2 凭证解析错误的边界

凭证、认证 header 和完整环境值不能进入 toString、事件、Session、配置解析结果或测试快照。借用既有 OpenAiCredentials/OpenAiHeaders 的值隔离方式，不再建立一个通用 redaction 框架。[J4]

解析 auth JSON 时，Jackson 的错误文本/cause 可能携带输入片段；不能只把顶层消息换成“解析失败”却仍暴露原始异常链。对含秘密的输入，仅公开文件、字段名、行列、错误种类及安全原因；不直接附带原始 JSON 或可能含值的 parser cause。普通无秘密 I/O 错误仍保留可用原因。

针对已装配秘密的诊断不回显 URI 认证信息或 query 值。普通配置不提供“把 key 放进 baseUrl”的认证形式。真实用户消息、工具输出及 Provider replay 数据按原契约处理，不作本阶段内容过滤。

## 14. 可复制的行为与 API 示例

### 14.1 Settings 示例

以下模型身份仅为示例，必须对应宿主配置的 Provider 和模型，不能当作真实在线模型目录。

全局 `settings.json`：

```json
{
  "defaultModel": {
    "provider": "example-responses",
    "api": "openai-responses",
    "modelId": "example-model"
  },
  "defaultThinkingLevel": "provider_default",
  "defaultTools": ["read", "grep", "find", "ls"],
  "request": { "maxOutputTokens": 4096 }
}
```

已授权项目 `settings.json`：

```json
{
  "defaultThinkingLevel": "high",
  "defaultTools": [],
  "request": { "temperature": 0.2 }
}
```

若 SDK 未覆盖，结果保留全局 defaultModel/maxOutputTokens，thinking 和 temperature 来自项目，工具集合为空。项目未授权时，上述项目字段全部不读取、不应用。Provider 是否接受 high/temperature，仍由其既有能力决定。

### 14.2 操作边界示例

```java
var overrides = SettingsOverrides.settings(CodingAgentSettings.builder()
        .defaultModel(initialRef)
        .build());
var options = CodingAgentSessionOptions.builder(workingDirectory)
        .userConfigDirectory(userConfigDirectory)
        .borrowedModels(models, Map.of())
        .settingsOverrides(overrides)
        .build();

var created = CodingAgentSessionFactory.open(options, sessionFile);
try (var session = created.session()) {
    // 等待 run 完成后再进行 idle-only 切换。
    session.prompt("检查当前实现").toCompletableFuture().join();
    session.setModel(targetRef, ThinkingLevel.PROVIDER_DEFAULT);

    // 保存默认值是独立操作，不是 setModel 的副作用。
    var settingsFiles = new SettingsFiles(
            workingDirectory, userConfigDirectory, new ObjectMapper());
    settingsFiles.updateGlobal(SettingsUpdate.builder()
            .setDefaultModel(targetRef)
            .build());
}
```

`models` 是调用方借入的 `Models`，生命周期仍由调用方管理；工厂从定义创建的 Provider 则随 Session 关闭。`initialRef`、`targetRef`、目录和 Session 文件均由宿主显式提供。

## 15. 实施切片

| 切片 | 本批交付 | 不得越界 | 本批完成证据 |
|---|---|---|---|
| **5A：Settings 与授信** | 可缺省设置、纯合并、作用域/字段来源、显式路径、SDK 决定与独立 trust 读取；确定公开输入草案 | 无 Provider 网络调用、无祖先 settings 扫描、无自授信 | precedence/presence/空工具/ModelRef 整体覆盖；未授权根本不打开项目文件；精确路径与符号链接决定测试 |
| **5B：凭证与 Provider 装配** | 固定凭证来源、用户级模型定义、复用 DefaultModels/OpenAI、只读目录和 ModelProfile、构造失败清理 | 无 OAuth/命令凭证/自动探测/新路由器 | 来源优先级、错误不降级、无密钥快照、unlisted 支持、owned/borrowed；本地 HTTP fixture 通过现有 adapter |
| **5C：创建/恢复与切换** | 新 Session 工厂、单次 Manager 所有权恢复、模型 fallback、core 窄更新、当前选择与 run 快照、历史衔接 | 无 Agent 重建、Provider 热替换、跨模型请求重试、分支自动切模型 | 恢复/显式优先、切换后真实请求、并发与 close、历史元信息匹配实际模型、队列/replay 保持 |
| **5D：显式保存与收口** | 默认字段保存、trust remember/remove、短时文件更新协议、示例/文档/路线图同步 | 无后台 writer、全局长 I/O 锁、多文件事务、通用存储框架 | 更新不丢无关字段、提交/清理失败、同 JVM/独立 JVM 并发、完整端到端、全仓与严格 native |

每批同时补测试；5B 必须形成可创建真实产品 Session 的最小纵向用例，不把所有集成推迟到 5D。未实现内容不能仅靠预留类型满足完成门槛。

## 16. 验收测试与运行命令

### 16.1 必须覆盖的行为

| 类别 | 最小可观察验收 |
|---|---|
| 旧 API 回归 | 原构造/create/open 不新增 settings/env/credential I/O；原工具和 AGENTS 默认行为不变 |
| Settings 合并 | 缺省不覆盖、OFF 非缺省、空工具列表有效、request 叶字段合并、ModelRef 整体替换、来源正确 |
| 读取错误 | 缺文件、坏 JSON、非法已知字段、未知字段、禁止字段；层失败与 SDK 失败有区分 |
| Project trust | 无决定、拒绝、允许、保存与删除、SDK 优先、项目不能自授信；拒绝时文件读取替身调用次数为零 |
| 项目身份 | 同目录符号链接共用决定，不同目录不继承；不能把路径前缀相似当作同一目录；不改 AGENTS 继承 |
| 凭证 | SDK/env/file 的固定优先级；缺失继续、非法不降级；高层获胜不读取低层；不扫描全环境 |
| 秘密 | 使用假的唯一哨兵值，验证异常 cause/suppressed、toString、诊断、事件、历史和快照均不因装配泄漏该值 |
| 模型能力 | catalog/find 与 supports 区分、allowUnlisted 可选、不支持拒绝；configured 状态不被写成远端验证成功 |
| 无隐式网络 | 解析、目录、工厂、选择和切换的计数器为零；真实请求只在 prompt/continue 或显式支持的刷新发生 |
| Provider 装配 | SDK/文件定义互斥和替代语义；自定义 endpoint 与 compatibility 显式；不同 providerId 不串凭证 |
| 恢复 | SDK 模型优先、历史可恢复、历史不可用→默认、无默认失败；锁定同一 Manager 后选择；失败释放 writer |
| thinking | 成功历史恢复与换模型时来源不同；PROVIDER_DEFAULT/OFF 保留；不静默 clamp |
| 当前切换 | 下一次 ModelRequest 真正使用新 model/thinking；active/reloading/history/closed 下拒绝；验证失败不发布半套配置 |
| 历史一致性 | run 事件保存本次实际选择；切换本身不写历史；首次新消息前差异记录只追加必要项；旧 sourceModel/replay 不变 |
| 分支与队列 | branch/reset 不重建实例、不自动换模型、不丢 pending 队列；新 open 对象按恢复规则重新选择 |
| writer 失效 | 无法记录历史时 prompt/continue/配置变更前置失败，不先调用模型；第四阶段通道与锁回归继续通过 |
| 保存 | 只改指定作用域和字段、REMOVE 继承、未知字段保留、坏文件不覆盖、锁冲突可观察、原子替换失败不截断原文件 |
| 文件协调 | 同 JVM 第二写者不能损坏第一写者锁；独立 JVM 证明冲突；只读不会关闭写锁通道；不同目标不共等长 I/O |
| 资源 | 构造失败逆序清理；owned Provider 最终关闭且只关闭一次；borrowed 不关闭；慢 run/close/完成回调不提前释放资源 |
| 端到端 | 用设置+本地 HTTP fixture 建会话→保存→关闭→恢复→切模型→继续→独立保存默认值；比较请求、历史与设置三者 |

测试使用现有 gate/latch/future、包内小替身和本地 HTTP fixture。不得靠 sleep、自旋或事件“开始”信号证明操作“已结束”。完成 Future 应在 try-with-resources 关闭完成之后发布；确需验证跨进程锁时使用独立 JVM。

不要求真实 API Key、真实账单或网络模型目录才能验收。不得为测试而增加公开 credential dump、内部 Agent/Manager getter 或通用资源替换接口。

### 16.2 完成条件

只有设置解析、凭证边界、创建/恢复、真实 idle 切换、默认值保存和 owned/borrowed 生命周期同时成立，才能将第五阶段标记完成。能打印“选中了模型 B”而请求仍使用 A，不算完成；能写 JSON 而覆盖其他层设置，不算完成。

前三/四阶段回归、模块边界和严格 native 验证必须通过。不同作用域的文件错误、鉴权配置和网络失败要有对应证据，不用测试总数代替场景验证。

```bash
mvn -pl agent-core -am test
mvn -pl ai-providers -am test
mvn -pl coding-agent -am test
mvn clean verify

mvn -pl coding-agent -am verify -Plocal-tools-smoke \
  -Djcode.test.bash=/absolute/path/to/bash \
  -Djcode.test.rg=/absolute/path/to/rg \
  -Djcode.test.fd=/absolute/path/to/fd

git diff --check
```

以上是建议命令，不是本次运行结果。实施记录必须写清真实环境、命令、测试数、skip 原因及提交基线，不预填将来的通过数字。

## 17. 总路线图与公开文档同步

| 总路线图或旧草案口径 | 第五阶段详细口径 |
|---|---|
| 当前 Session 临时变更是最高配置层 | 它是当前运行选择；加载偏好、历史恢复、当前切换分开定义，不增加第五层文件 |
| defaultProvider/defaultModel 分开覆盖 | 完整 ModelRef 整体选择，避免跨层混合 provider/api/modelId |
| 项目设置可以影响 provider/endpoint | 项目只选已有模型与工具偏好；连接定义、credential/header 来源仅用户级或 SDK |
| 最小 project trust 与独立 store | SDK 优先，store 精确目录决定；不加载项目前完成判断，不扩展父目录授信和未来执行资源 |
| Session 恢复失败安全 fallback | 仅恢复模型选择回退到配置默认；显式模型错误/运行请求失败不自动改投其他 Provider |
| 凭证装配与来源 | API Key 的 SDK/env/只读文件，固定优先级；不实现凭证文件写入和 OAuth |
| catalog snapshot 与 auth check | 复用 Models/ProviderAuth；目录、supports、已配置和远端验证严格区分；默认无网络 |
| idle model/thinking 切换 | 同一 core/产品接纳边界更新，不重建 Agent/Provider，不因切换自动保存默认值 |
| 切换记录持久化 | 沿用第四阶段实际消息前记录差异；只切未运行不保存临时模型选择 |
| 产品 model profile | context/output 限制可选且有来源；不复制能力/价格，不猜窗口，不提前实现 compaction |
| 配置并发更新 | 同步目标层字段更新；短时锁文件+原子替换；不泛化 SessionFileAccess，不增加后台同步 |
| secrets 不出现在 Session | 系统装配的凭证不进入 Session；不声称用户正文无法包含敏感内容，不过滤历史 |

同步 `coding-agent/pom.xml`、模块 AGENTS、README、架构边界与 runtime-contracts；在总路线图中链接本计划。`agent-core/AGENTS.md` 写清新 idle 接口的范围及每次 run 参数快照。`ai/ai-providers` 只有既有契约确有变化时才修改，不能为记录项目进度堆积说明。

首版 Settings 与 auth/trust 的文档不宣称兼容 pi 文件。首次格式没有真实历史 migration；以后确有已发布旧数据再增加明确迁移，不能创建空迁移框架。

实现完成后将本计划移到 `docs/plans/archived/`，在文首标明现行实现说明的位置，并追加真实实施记录。若后续修订改变原计划规则，现行规则必须前置可见，不依赖读者读完全部历史才能判断。

## 18. 固定参考与实施定位

下列引用指向已经核对的基线文件或官方 API。文件中的源码行为用于定位；本计划新增的选择和范围决策已在第 2 节标明，不冒充 pi 原行为。

| 编号 | 依据 | 用途 |
|---|---|---|
| P1 | [pi settings-manager.ts](https://github.com/earendil-works/pi/blob/60e7e76bd7ea25cad1dd6f3f1ce0d18814a42759/packages/coding-agent/src/core/settings-manager.ts) | 可缺省设置、deepMergeSettings、projectTrusted、persistScopedSettings、作用域更新 |
| P2 | [pi project-trust.ts](https://github.com/earendil-works/pi/blob/60e7e76bd7ea25cad1dd6f3f1ce0d18814a42759/packages/coding-agent/src/core/project-trust.ts)；[trust-manager.ts](https://github.com/earendil-works/pi/blob/60e7e76bd7ea25cad1dd6f3f1ce0d18814a42759/packages/coding-agent/src/core/trust-manager.ts) | headless 授信解析、真实路径与祖先决定；Jcode 首版只保留精确设置授权 |
| P3 | [pi model-runtime.ts](https://github.com/earendil-works/pi/blob/60e7e76bd7ea25cad1dd6f3f1ce0d18814a42759/packages/coding-agent/src/core/model-runtime.ts)；[model-registry.ts](https://github.com/earendil-works/pi/blob/60e7e76bd7ea25cad1dd6f3f1ce0d18814a42759/packages/coding-agent/src/core/model-registry.ts) | Models 装配与兼容门面职责，不复制动态 Registry |
| P4 | [pi auth-storage.ts](https://github.com/earendil-works/pi/blob/60e7e76bd7ea25cad1dd6f3f1ce0d18814a42759/packages/coding-agent/src/core/auth-storage.ts)；[runtime-credentials.ts](https://github.com/earendil-works/pi/blob/60e7e76bd7ea25cad1dd6f3f1ce0d18814a42759/packages/coding-agent/src/core/runtime-credentials.ts) | 存储与运行时覆盖分离；不据此承诺完整 OAuth/命令凭证支持 |
| P5 | [pi sdk.ts](https://github.com/earendil-works/pi/blob/60e7e76bd7ea25cad1dd6f3f1ce0d18814a42759/packages/coding-agent/src/core/sdk.ts) | 创建、显式模型、历史模型、default fallback、thinking 来源与 clamp |
| P6 | [pi agent.ts](https://github.com/earendil-works/pi/blob/60e7e76bd7ea25cad1dd6f3f1ce0d18814a42759/packages/agent/src/agent.ts) | Agent state 和运行时参数；Java 的更新和并发映射以 J5 为准 |
| J1 | [Jcode 总路线图](https://github.com/YPQuinn/Jcode/blob/3d2bdc0c1402b833104585f999f44415b8891f89/docs/plans/coding-agent-development-roadmap.md) | 第五阶段原范围、第五至七阶段边界和 ai-providers 接入方向 |
| J2 | [CodingAgentConfig.java](https://github.com/YPQuinn/Jcode/blob/3d2bdc0c1402b833104585f999f44415b8891f89/coding-agent/src/main/java/site/pplee/jcode/codingagent/CodingAgentConfig.java)；[CodingAgentSession.java](https://github.com/YPQuinn/Jcode/blob/3d2bdc0c1402b833104585f999f44415b8891f89/coding-agent/src/main/java/site/pplee/jcode/codingagent/CodingAgentSession.java) | 旧显式配置、默认补齐、Session 接纳与资源所有权 |
| J3 | [Models.java](https://github.com/YPQuinn/Jcode/blob/3d2bdc0c1402b833104585f999f44415b8891f89/ai/src/main/java/site/pplee/jcode/ai/provider/Models.java)；[DefaultModels.java](https://github.com/YPQuinn/Jcode/blob/3d2bdc0c1402b833104585f999f44415b8891f89/ai/src/main/java/site/pplee/jcode/ai/provider/DefaultModels.java)；[ProviderAuth.java](https://github.com/YPQuinn/Jcode/blob/3d2bdc0c1402b833104585f999f44415b8891f89/ai/src/main/java/site/pplee/jcode/ai/provider/ProviderAuth.java) | 唯一路由集合、catalog/支持/auth 配置状态 |
| J4 | [OpenAiProvider.java](https://github.com/YPQuinn/Jcode/blob/3d2bdc0c1402b833104585f999f44415b8891f89/ai-providers/src/main/java/site/pplee/jcode/aiproviders/openai/OpenAiProvider.java)；[OpenAiProviderConfig.java](https://github.com/YPQuinn/Jcode/blob/3d2bdc0c1402b833104585f999f44415b8891f89/ai-providers/src/main/java/site/pplee/jcode/aiproviders/openai/OpenAiProviderConfig.java)；[OpenAiModelCapabilities.java](https://github.com/YPQuinn/Jcode/blob/3d2bdc0c1402b833104585f999f44415b8891f89/ai-providers/src/main/java/site/pplee/jcode/aiproviders/openai/OpenAiModelCapabilities.java) | 凭证装配、allowUnlistedModels、显式兼容能力与不重复实现的边界 |
| J5 | [Agent.java](https://github.com/YPQuinn/Jcode/blob/3d2bdc0c1402b833104585f999f44415b8891f89/agent-core/src/main/java/site/pplee/jcode/agentcore/Agent.java) | admissionLock、submit/getLoopConfig、当前 idle 接口、close 的实际等待与收尾 |
| J6 | [SessionManager.java](https://github.com/YPQuinn/Jcode/blob/3d2bdc0c1402b833104585f999f44415b8891f89/coding-agent/src/main/java/site/pplee/jcode/codingagent/SessionManager.java)；[第四阶段归档计划](https://github.com/YPQuinn/Jcode/blob/3d2bdc0c1402b833104585f999f44415b8891f89/docs/plans/archived/coding-agent-phase-4-session-persistence.md) | 单次打开、分支元信息、真实完成消息记录与文件所有权 |
| J7 | [OpenAiResponsesAdapter.java](https://github.com/YPQuinn/Jcode/blob/3d2bdc0c1402b833104585f999f44415b8891f89/ai-providers/src/main/java/site/pplee/jcode/aiproviders/openai/OpenAiResponsesAdapter.java) | 既有请求映射、错误、取消、重试与资源生命周期，不新增产品层重试 |
| K1 | [Java 21 FileLock](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileLock.html) | JVM 内与跨进程锁的区别、关闭另一个通道的影响、协作性质 |
| K2 | [Java 21 Files](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html) | createTempFile/move 与权限属性；原子替换不等同于断电保证 |
| B1 | [pi-book 第 13 章](https://github.com/ZhangHanDong/pi-book/blob/0a8863b611504a47302931ad9adf1cab71a0b79f/src/ch13-config-layers.md) | 分层配置的辅助解释；不将目录 AGENTS 继承当成 settings 继承 |

本阶段不以文档存在、测试数量或预留 API 代替真实装配、切换和保存闭环。

## 19. 实施记录

- 实现按 5A～5D 交付了 sparse settings、精确项目授信、固定 Provider/凭证装配、创建/恢复选择、idle model/thinking 切换，以及短时锁和原子替换保存。
- `coding-agent.settings` 保持普通偏好与凭证分离；`coding-agent.model` 复用 `Models`、`DefaultModels` 和现有 OpenAI Provider，不增加动态 registry、OAuth、自动网络探测或请求失败 fallback。
- 新工厂区分 borrowed 与 session-owned Models；open 只取得一次 SessionManager 所有权，切换不立即写历史，下一条真实完成消息前仍由 SessionManager 记录实际配置差异。
- 最终验证环境为 Darwin arm64、Java 21.0.10、Maven 3.9.14，提交基线为 `3d2bdc0`。`mvn -pl coding-agent -am dependency:tree -Dincludes=site.pplee:* -DskipTests`、第五阶段定向测试及 `mvn -pl coding-agent -am test` 均通过。
- `mvn clean verify` 通过，共运行 853 个测试（普通环境中 10 个需要显式本地 executable 的 native 测试按预期跳过）；随后使用 `/bin/bash`、`/opt/homebrew/bin/rg` 和 `/Users/quinncypp/.pi/agent/bin/fd` 运行严格 `local-tools-smoke`，853 个测试全部通过且无 skip。
- `git diff --check`、Maven Enforcer 依赖边界、Provider URL/secret redaction 审查均通过；`.idea/encodings.xml` 和 `.idea/vcs.xml` 是用户已有改动，不属于本阶段提交。
