# 架构与模块边界

## 依赖规则

Maven Enforcer 保护以下边界：

- `ai` 不依赖任何其他 Jcode 模块。
- `ai-providers` 仅依赖 `ai`。
- `agent-core` 仅依赖 `ai`，不得依赖 provider 或产品/UI 模块。
- `coding-agent` 仅依赖 `ai` 与 `agent-core`，不得直接依赖 `ai-providers`。
- 依赖必须无环，并从产品层指向内核层。

`message`、`event`、`tool`、`queue`、`concurrent` 是源码包，不是 Maven 模块。不要创建泛化的 `common` 或 `shared` 模块，也不要维护相互竞争的 `Message`、`Content` 或 `StopReason` 定义。

## 装配与状态

- Provider、工具和 hook 必须通过构造参数显式装配。
- 禁止可变静态注册表、`ServiceLoader`、classpath 扫描或 DI 容器。
- 公开值类型必须不可变。Core 单次 run 的可变状态只能存在于 package-private `LoopState`。
- `coding-agent` 对含可变 Jackson `JsonNode` 的 state、result 和 event，必须在构造与暴露时都递归快照。
- Provider 特有 transcript 与 wire-format 逻辑留在 `ai-providers`，不得泄漏到 `ai` 或 `agent-core`。

## 产品边界

除非有专门功能明确修改这些边界：

- 不引入 Spring Boot、Guice 或其他 DI 框架。
- 不增加 Session 持久化、数据库或缓存基础设施。
- 不增加隐式 compaction、prompt template、extension 或 plugin 系统。
- 不使用 `Map<String, Object>` 表示工具参数；边界使用 Jackson `JsonNode`，工具内部使用强类型参数。
- `AgentLoop` 不得读取 API key 或环境变量；provider 配置与凭证类型不得自行从环境变量发现值，凭证必须由调用方显式传入。
- Provider 重试默认关闭；允许 provider 支持显式配置、有界的 opt-in retry。

## 参考资料隔离

对外部参考项目的名称、链接或对比只能出现在 `docs/` 与 `AGENTS.md` 文件中。生产源码、测试、POM 和其他配置不得提及这些参考项目。
