# coding-agent 模块知识库

Headless 编码产品内核。组合 `ai` 与 `agent-core`，提供 `CodingAgentSession`、coding prompt、项目指令发现、具体编码工具以及产品级 state/result/event。当前阶段不包含 Session 文件、通用设置发现、Compaction、Extension 或 UI。

## WHERE TO LOOK

| 任务 | 位置 |
|------|------|
| 产品调用入口 | `CodingAgentSession.java` |
| 显式运行配置 | `CodingAgentConfig.java` |
| 产品快照 | `CodingAgentState.java` / `CodingAgentRunResult.java` / `event/` |
| System Prompt | `prompt/SystemPromptBuilder.java` |
| 项目指令配置、加载与快照 | `context/ProjectContextConfig.java` / `context/ProjectContextLoader.java` / `context/ProjectContextSnapshot.java` |
| 工具选择与授权 | `tool/CodingToolConfig.java` / `tool/CodingToolPolicy.java` |
| 内置工具装配 | `BuiltInTools.java` / `CodingToolPolicyAdapter.java` |
| 文件读取工具 | `tool/ReadTool.java` |
| 原生文件修改 | `tool/WriteTool.java` / `tool/EditTool.java` / `tool/LocalFileAccess.java` |
| 纯编辑规划 | `tool/EditPlanner.java` |
| Bash 与进程控制 | `tool/BashTool.java` / `tool/ProcessRunner.java` / `tool/LocalProcessLauncher.java` |
| 进程输出与更新 | `tool/ProcessOutputBuffer.java` / `tool/LatestToolUpdatePublisher.java` |
| 目录列表与输出格式 | `tool/LsTool.java` / `tool/BoundedRecordFormatter.java` |
| 结构化搜索工具 | `tool/GrepTool.java` / `tool/FindTool.java` |
| rg/fd 后端与 record 解析 | `tool/SearchProcessBackend.java` / `tool/BoundedByteRecordReader.java` / `tool/ConfiguredSearchTools.java` |
| 完整行截断 | `tool/OutputTruncator.java` |
| 递归快照 | `internal/SnapshotMapper.java` |
| 第二阶段工具计划（已归档） | `../docs/plans/archived/coding-agent-phase-2-local-tools.md` |
| 第三阶段上下文计划（已归档） | `../docs/plans/archived/coding-agent-phase-3-project-context.md` |

## PLANNING STATUS

- 第三阶段已完成显式项目指令发现、不可变来源/诊断快照、纯 Prompt 装配和 idle 原子 reload；实施计划归档于 `docs/plans/archived/coding-agent-phase-3-project-context.md`。候选仅 `AGENTS.override.md` → `AGENTS.md` → `AGENTS.MD`，不读取 CLAUDE 文件；物理祖先链、全局来源、symlink/hardlink 去重和严格验证的嵌套 linked-worktree 遮蔽均有固定边界。旧配置默认不执行新增发现 I/O，custom/append/tools/cwd 语义不变。阶段三审查修复后新增 19 个回归，全仓 762 个测试、本模块 220 个测试，0 failure/error/skipped，严格 native smoke 与生命周期/加载边界连续三轮复验通过。
- 项目指令读取采用严格 UTF-8、BOM 规则、文件/总量/来源/祖先/Git 元数据/读取/deadline/渲染预算和结构化诊断。`WARN_AND_SKIP` 可提交带诊断快照，`FAIL`、整体预算失败或取消不替换旧快照。reload 与 prompt/close 共用产品 admission lock，不重建 Agent/工具，不改变历史、队列、model/options；返回 stage 是不可反向取消内部操作的观察副本。
- reload 在提交或失败清理的临界区内释放接纳，再锁外完成 stage；同步完成回调可启动 prompt/下一 reload，也可 close。worker 使用线程局部标记覆盖回调周期，避免新 reload 覆盖旧 worker 身份而导致 close 自等待；close 的两秒窗口结束不能替尚未退出加载的 worker 结算 stage 或清除 reloading。取消导致的读取中断在真实清理后归一为取消。
- 项目来源打开前必须复核所选属性与普通文件类型，已知变化或特殊文件不得试读；package-private `InputOpener` seam 用于确定性文件替换/FIFO 回归。保留的 256 KiB/64 个来源预算在 UTF-8、正文与展示路径验证后计算，失败读取仍消耗 1 MiB 实际读取预算。路径不可渲染遵循 WARN/FAIL，不留给 builder 才失败；可选 `commondir` 仅真正缺失可忽略，非普通文件、悬空链接及读取错误走 Git 元数据诊断。
- 第二阶段 PR 1～6 已完成工具配置/policy/显式装配、`write`/`edit`、受控 `bash`、有界 `ls`/`grep`/`find`、完整 fake-model Git 工作区闭环、故障恢复矩阵和严格 native smoke。旧配置的默认可执行工具仍只有 `read`；显式 `coding(BashConfig)` profile 提供 `read/write/edit/bash`，`codingWithSearch(BashConfig, SearchConfig)` profile 显式提供全部七个工具。
- 阶段二分 2A（显式启用 read/write/edit/bash）与 2B（结构化发现），均已完成，不包含持久化、配置发现或 UI。审查修复后全仓 721 个测试通过（本模块 181 个），严格 native smoke 与进程/sink 回归连续三次复验通过；实施记录已归档。
- 旧 Config 与 `tools == null` 保持 read-only；产品授权请求递归快照参数，不能直接对外暴露含 `AgentTool`/`AgentContext` 的 core hook，诊断不得输出参数或工作目录。policy 失败或拒绝归一为普通 tool error，不得执行工具。
- 第二阶段后续行为对齐记录见 `docs/plans/archived/local-tools-behavior-alignment.md`；该记录替代旧归档中的原子 mutation、严格匹配、Shell 初始化禁令和自研 glob 契约。
- `BashConfig` 的 executable 仍显式绝对路径；null environment 继承进程环境，非 null map 完全替换且防御性快照；允许调用方选择 shell 初始化变量。default/maximum timeout 均可为 null，缺省按 default → maximum → 无时限解析；没有 3600 秒硬上限，显式值仅检查正数、整秒及调度器表示范围。配置和请求 toString 继续脱敏。
- `write`/`edit` 均为 `SEQUENTIAL`：复用 core 整批源序执行，不增加静态跨 Session 文件锁。`LocalFileAccess` 直接写入文件并保留 native symlink/hardlink 与已有文件权限语义，不读旧文件做 CAS、不创建 mutation 临时文件、不要求 atomic move；I/O 失败或取消不提供事务回滚。write 可覆盖大文件为小文件，也不把最终内容限制为 8 MiB。
- `edit` 的全部 replacement 基于同一原始文件匹配，精确匹配优先，必要时整体进入 NFKC/行尾空白/引号/破折号/空格规范化视图；唯一性与非重叠校验仍保留。按实际匹配行范围投影变化，保留 BOM、未触及行及原有换行适配；不是任意近似匹配。仅内存编辑规划保留 8 MiB 输入/结果及参数文本预算。
- Bash 使用 `-c`，每个 `ProcessRunner` 最多 4 个活跃进程；持续 drain 输出并保留 2000 行/50 KiB tail。本轮不增加完整输出临时文件。timeout 可缺省，但取消/close 的有界进程树清理不变：保留已观察后代句柄并等待树退出，无法终止返回 `TERMINATION_FAILED`；close 后清理 scheduler/executor 延迟至已接纳执行结算。
- 工具 update publisher 与输出采集解耦，最多一个 update 在途并只保留最新待发布快照；慢 sink 不阻碍 timeout/cancel，但已接纳 update 必须在工具完成前 settle。publisher 失败立即触发工具自有 `CancellationSource`，停止并清理进程后再传播原基础设施异常，不取消调用方的只读 signal。
- `ls` 使用 `DirectoryStream`、有界 top-N heap 和固定的目录优先/code point 排序；特殊名称用 JSON 字符串显示，扫描上限/超时与返回/字节截断分别诊断，不递归且不应用 ignore。
- glob 由 rg/fd 原生处理，不维护自研方言。`grep` 使用 rg `--json --hidden`，`glob` 直接透传 `--glob`，支持 brace/字符组/否定 glob，显式规则可重新包含 ignored 文件；`find` 使用 fd `--glob --hidden --print0`，glob 不覆盖 ignore，搜索根不在 Git 仓库时添加 `--no-require-git`，并排除 `.git`、`node_modules`。
- `SearchConfig(rg, fd, environment)` 显式注入两个 binary，null environment 继承、map 替换。旧二参数构造仍可用于 grep-only；启用 find 必须提供 fd，单独启用一项不要求另一 executable。组合 profile 共享一个进程 runner，不做版本号/help 文本握手，不自动下载工具。
- 搜索不再限制单文件为 8 MiB；仍保留 30 秒搜索 deadline、64 KiB 单 record、100000 条记录与结果/字节预算。stdout/stderr 独立 drain，达到结果预算时内部取消。路径使用 JSON 字符串；文件 I/O 和后端失败保留有界错误原因与必要路径，不再以白名单抹掉诊断。显式 grep 文件仍加 `./`，防止 `-` 被当作 stdin。
- `coding`/`codingWithSearch` profile 均已开放。严格 `local-tools-smoke` 必须显式注入绝对 Bash/rg/fd executable，能力缺失视为失败而非 skip。

## CONVENTIONS

- `CodingAgentSession` 是唯一产品运行入口；不得公开内部 `Agent`。
- Provider、model client、工作目录、`ObjectMapper` 和 hook 均显式注入；本模块不自动发现 API Key 或产品配置。子进程环境按 `BashConfig` / `SearchConfig` 继承或显式替换。
- `pom.xml` 的 module-local Enforcer 仅允许 Jcode 内部依赖 `ai` 与 `agent-core`，并显式禁止 `ai-providers`、`server` 和 `tui`。
- Public config 不持有可变 collection/tree；state/result/event 对可变 `JsonNode` 在构造与访问时都做递归快照。
- `AgentCompleted` 必须投影为产品 `RunCompleted`，不能通过 public event 暴露 `LoopResult` 中的工具实例。
- 内置工具使用强类型参数和 JSON Schema；运行时边界不能只依赖 core 的最小 schema validator。
- 文件路径以 Session working directory 解析，但 working directory 不是 sandbox。工具路径参数不得在交给文件系统前通过 lexical normalize 折叠 `..`；必须保留符号链接及中间路径不存在/非目录时的平台解析语义。
- 文件读取必须有界；禁止对不受信任文件使用无界 `readLine()`、`readString()` 或 `readAllBytes()`。`OutputTruncator` 完整跳过 offset 前置行但不缓冲内容，目标页按剩余 UTF-8 字节预算读取，确定超限即停止，不扫描超长行余下部分；仍保留完整行、CR/LF/CRLF 归一化及取消检查。
- 事件 sink 保留 core backpressure；失败属于基础设施失败，不归一为 tool/model error。流式 sink 失败或返回 null stage 时，产品 stage 异常完成、状态清理、不发送成功完成事件，Session 可接受后续 prompt；底层负责取消在途 provider。
- Session 的 admission lock 线性化 prompt、project-context reload 与 close 接纳；文件发现和 Prompt 渲染必须在锁外，锁内仅复核并提交 core prompt 与产品 snapshot。steering/follow-up 接纳不等于当前 run 已消费，未 drain 消息保留到下一 run。
- Javadoc 使用英文；生产代码、POM 和配置中不得提及参考项目名称。

## ANTI-PATTERNS

- 不依赖 `ai-providers`（首阶段）、`server` 或 `tui`。
- 不复制模型流协议、Agent Loop、工具三阶段管道或 provider registry。
- 不使用 `ServiceLoader`、classpath 扫描、静态可变 registry 或 DI 容器。
- 不用 `Map<String,Object>` 表示配置、工具参数或结果。
- 不把 partial assistant 写入产品结果或未来 Session 历史。
- 不声称 working directory、normalize 或字符串前缀检查构成安全 sandbox。
- 不为测试增加伪 `main` 或 Spring Boot 入口。

## COMMANDS

```bash
mvn -pl coding-agent -am test
mvn -pl coding-agent dependency:tree
mvn verify
mvn -pl coding-agent -am verify -Plocal-tools-smoke \
  -Djcode.test.bash=/absolute/path/to/bash \
  -Djcode.test.rg=/absolute/path/to/rg \
  -Djcode.test.fd=/absolute/path/to/fd
```
