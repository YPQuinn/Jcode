# Jcode Agent Guide

Jcode 是一个 Java 21 多模块 Maven monorepo，包含 provider-neutral 模型协议、provider adapter、通用 Agent Runtime 与 headless 编码产品内核。

## Essentials

- 构建与依赖管理使用系统安装的 Maven（`mvn`）；仓库没有 Maven Wrapper。
- 全仓最终校验运行 `mvn verify`。
- 仓库中的模块都是库模块，没有 `main` 或 Spring Boot 启动类。
- 修改某个目录前，先读取作用域更近的 `AGENTS.md`；更具体的规则优先于本文件链接的通用规则。

## Guides by task

只在任务涉及相应主题时读取：

- [项目结构与代码定位](docs/agents/project-structure.md)
- [架构与模块边界](docs/agents/architecture-boundaries.md)
- [Java 与 API 约定](docs/agents/java-and-api-conventions.md)
- [运行时正确性契约](docs/agents/runtime-contracts.md)
- [构建与测试](docs/agents/build-and-testing.md)
- [开发与 Git 工作流](docs/agents/development-workflow.md)
- [Issue tracker](docs/agents/issue-tracker.md)
- [Triage labels](docs/agents/triage-labels.md)
- [Domain docs](docs/agents/domain.md)

## Module-specific guides

- [`ai`](ai/AGENTS.md)
- [`ai-providers`](ai-providers/AGENTS.md)
- [`agent-core`](agent-core/AGENTS.md)
- [`coding-agent`](coding-agent/AGENTS.md)
- [`jcode-protocol`](jcode-protocol/AGENTS.md)
- [`jcode-app`](jcode-app/AGENTS.md)
- [架构文档写作](docs/architecture/AGENTS.md)
