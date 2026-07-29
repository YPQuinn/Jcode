# Jcode

基于 **Java 21** 的多模块 Maven monorepo，实现一套**最小、可组合、可测试**的 Agent Runtime。

Jcode 将「模型调用协议」与「Agent 循环运行时」严格分层：

- `ai`：provider-neutral 的模型调用协议层（零 Jcode 内部依赖）
- `agent-core`：通用 Agent Runtime（唯一内部依赖为 `ai`）

当前仓库是**库模块**，不包含 Spring Boot 启动类、CLI、HTTP Server、Session 持久化或具体 provider SDK。

---

## 技术栈

| 类别 | 选型 | 说明 |
|------|------|------|
| 语言 / 平台 | Java 21 | `record`、`sealed interface`、模式匹配、虚拟线程 |
| 构建 | Maven（多模块 reactor） | 根聚合：`ai` → `agent-core` |
| JSON | Jackson Databind `2.18.2` | 工具参数边界使用 `JsonNode` |
| 工具辅助 | Lombok `1.18.46` | 编译期辅助 |
| 测试 | JUnit 5 `5.11.4` | 无 Mockito；自定义测试双放 `support/` |
| 边界守卫 | maven-enforcer-plugin `3.5.0` | 禁止非法模块依赖 |

**外部依赖版本一律由根 POM `<properties>` 固定，不使用版本范围。**

---

## License

当前版本为内部/个人开发快照（`1.0-SNAPSHOT`）。如需开源许可，请在发布前补充正式 License 文件。
