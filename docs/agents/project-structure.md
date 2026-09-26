# 项目结构

## 模块

| 模块 | 职责 | Jcode 内部依赖 |
| --- | --- | --- |
| `ai` | Provider-neutral 模型协议、消息、流、取消、工具声明与 provider runtime 抽象 | 无 |
| `ai-providers` | 具体 provider adapter；当前包含 OpenAI Responses adapter | `ai` |
| `agent-core` | 通用 Agent Runtime、事件循环、队列、hook、取消所有权与工具执行 | `ai` |
| `coding-agent` | Headless 编码产品内核、设置/模型装配、Session、项目上下文、文本资源、显式 Java Extension、coding prompt 与本地编码工具 | `ai`、`agent-core`、`ai-providers` |
| `jcode-protocol` | 与传输无关的最小命令、回执、状态与错误类型 | 无 |
| `jcode-app` | 进程内 Session 托管、命令幂等、Run 终态与输入查询 | `jcode-protocol`、`coding-agent`、`ai` |

依赖方向是产品层 → runtime/protocol 层。所有模块都是库，没有应用启动入口。

## 包布局

Java 包名使用 `site.pplee.jcode.<module>`。Maven 模块名中的连字符不进入包名，因此 `agent-core` 使用 `site.pplee.jcode.agentcore`，`coding-agent` 使用 `site.pplee.jcode.codingagent`。

修改代码前读取距离目标最近的模块指南：

- [`ai/AGENTS.md`](../../ai/AGENTS.md)
- [`ai-providers/AGENTS.md`](../../ai-providers/AGENTS.md)
- [`agent-core/AGENTS.md`](../../agent-core/AGENTS.md)
- [`coding-agent/AGENTS.md`](../../coding-agent/AGENTS.md)
- [`jcode-protocol/AGENTS.md`](../../jcode-protocol/AGENTS.md)
- [`jcode-app/AGENTS.md`](../../jcode-app/AGENTS.md)

## 仓库文档

- `docs/architecture/`：mdBook 技术架构文档。
- `docs/issues/`：本地分析与待发布 issue 材料；GitHub Issues 仍是权威来源。
- `docs/plans/`：进行中的实施方案。
- `docs/plans/archived/`：已完成或放弃的方案。
- `docs/references/`：设计阶段使用且被 Git 忽略的本地参考仓库。
- `docs/rules/`：仓库工作流规则。

不要预建空模块。只有出现真实独立使用者或产品入口时才新增产品模块。后续 model provider 应放入 `ai-providers` 的 provider 子包，而不是建立新的 Maven 模块。
