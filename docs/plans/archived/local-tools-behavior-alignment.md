# 本地工具行为对齐调整

> 状态：已实施并归档（2026-09-20）。参考 pi `60e7e76bd7ea25cad1dd6f3f1ce0d18814a42759` 的实际源码及 pi-book 工具章节。
> 本轮修改第二阶段已经落地的契约；旧归档描述历史实现，不再作为被本记录替代条目的当前规范。

## pi 如何做 → Jcode 如何映射 → 必要差异

| 主题 | pi 行为 | 本轮 Jcode 调整 | 保留的差异 |
|---|---|---|---|
| grep/find | 默认搜索 hidden；grep.glob 交给 rg，可重新包含 ignored 文件；fd glob 遵守 ignore | 删除 SearchGlob，分别使用 rg `--glob --hidden` 和 fd `--glob --hidden --print0`；去掉单文件 8 MiB 限制、global-ignore/config 禁令及固定 rg 线程参数；fd 根据 Git 边界选择 `--no-require-git` | 显式注入 binary；不下载工具；路径仍以 JSON 字符串输出 |
| 搜索启动 | 使用既有工具执行 | 删除 rg 14+ 与 help 文本 capability handshake，错误在执行时报告 | 仍需显式绝对 executable；不自动下载二进制 |
| write/edit | 直接写文件；按路径排队 | 删除旧文件 CAS、权限复核、临时文件和强制 atomic move；write 不读旧文件、不限制旧/新文件为 8 MiB | 沿用现有 SEQUENTIAL 批次规则，不增加全局队列；edit 整文件规划仍保留原有 8 MiB 内存边界 |
| edit 匹配 | 精确优先，必要时 NFKC/空白/引号等规范化回退；唯一、非重叠；保留未触及行 | 实现同一匹配流程及按受影响行回写；保留 BOM 和原有换行适配 | 不引入通用 diff/patch 引擎 |
| Bash | 环境可继承/显式传入；timeout 可选、无默认限制 | 允许 null 表示继承环境/无 timeout；保留旧显式配置；允许 shell 初始化变量，取消 3600 秒硬上限，使用 `bash -c` | 参数暂保持整数秒；Duration 仅验证正数和调度器可表达范围 |
| 诊断 | 保留文件错误及后端 stderr | 删除 I/O 文本白名单和固定错误分类，保留有界原因与必要路径 | 配置、参数对象的 incidental toString 继续脱敏 |

## 不在本轮改动的机制

- 不改 core 三阶段管道、整批串行规则和 policy 默认 allowAll。
- 保留进程树退出确认、取消/timeout 清理、持续 drain、异步 sink 失败停止进程与已接纳 update settle。
- 保留当前结果/内存缓冲预算、进程容量与结构化记录边界；不把移除单文件搜索上限解释为无限保留输出。
- Bash 完整输出临时文件是独立的能力补齐，本轮不实现；不增加 Session 持久化、UI 或配置发现。

## 验证

- 先增加能复现旧行为偏离的回归：hidden/ignored/glob、大文件覆盖与 hardlink、编辑规范化、Bash 配置和有效诊断。
- 替换旧事务/禁止配置测试，不削弱进程树、取消、sink、工具执行顺序测试。
- `mvn verify`；严格 `local-tools-smoke` 注入 Bash、rg、fd 三个 executable；重复生命周期及 native 工具回归。
- 完成后更新 README、模块知识库、阶段二归档说明及路线图，归档本记录。不自动提交。


## 实际实现与兼容性

- `SearchConfig` 增加 `findExecutable`：`new SearchConfig(rg, fd, environment)`。旧二参数构造只配置 grep；完整 profile 或 find-only 必须显式提供 fd。find-only 可将 rg 设为 null。两个工具共同启用时共享一个 `ProcessRunner`，单独启用时不会创建或验证另一个工具。
- `SearchProcessBackend` 替代仅面向 rg 的后端，移除 `--version`/`--help` 文本探测；只有 rg 的 exit 1 视为无匹配，fd 非零退出保留错误。stderr 保留有界原因，换行平铺到现有 notice 格式。
- `BashConfig(Path)` 默认继承环境且无 timeout；`BashConfig(Path, Map)` 可替换环境；原四参数构造继续可用。缺省 timeout 按 default → maximum → 无时限选择。`ProcessRequest` 中 null environment/timeout 分别表示继承/不调度 deadline。
- 删除 `FileMutationWriter`、`FileMutationOperations`、`NioFileMutationOperations` 及旧事务测试替身；`LocalFileAccess` 只负责编辑读取与原生写入。write 无需读取旧内容，保留 inode/hardlink 关系；不再保证写入失败或中途退出能保留原文件。只读状态/取消前检查仍保留，不提供通用事务或跨 Session 锁。
- 编辑规范化包括 NFKC、行尾空白、智能引号、Unicode 破折号与空格；若发生回退，全部 edits 在同一规范化视图检查唯一性和重叠。按实际匹配行范围投影修改，不靠 diff 对齐重复行；保留未改动行及既有换行适配。
- 保留的 Java 边界：单次 edit 8 MiB 内存输入/结果与参数预算、整数秒 Bash timeout、core 任一 SEQUENTIAL 导致整批源序执行、结果/record/扫描/搜索 deadline/进程容量边界。没有宣称完全复刻全部工具行为，也没有借本轮新增另一套安全框架。

## 验收记录

- 新增 `LocalToolAlignmentTest` 首批 10 个回归在旧实现全部失败，修复后全部通过。
- 补充精确匹配优先、规范化歧义/重叠、同一行多次替换、兼容字符展开及跨行插入后的原始范围保持测试。
- 新增 fd 无仓库 ignore、嵌套仓库边界、非法 glob 错误、rg/ fd 单独装配，以及 Bash 初始化文件、无默认 timeout 下取消并清理进程等回归。
- 移除/改写旧原子提交、glob 子集禁令、版本握手、shell 初始化禁令及大文件拒绝测试；保留并复验取消、进程树、sink、原生路径及全工具 fake-model 工作区闭环。
- `mvn clean verify -Djcode.test.fd=...`：**772 tests，0 failure/error/skipped**（ai 71、ai-providers 267、agent-core 204、coding-agent 230）。
- 严格 `local-tools-smoke` 显式注入 Bash、rg、fd 后通过，无 skip。fd 取本机已安装 executable，没有下载或安装 binary。
- 上述工具/进程/输出发布和工作区闭环回归在严格 native 配置下连续三轮通过。
- 未自动提交；README、模块知识库、路线图与旧归档的当前契约指向已同步。
