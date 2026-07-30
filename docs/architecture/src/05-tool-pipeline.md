# 05. 工具管道

> 状态：待补充。

本章用于解释工具三阶段管道：prepare、execute、finalize，以及 hook、schema 校验和结果写回的顺序保证。

## 待回答的问题

- 为什么具体工具只实现 `AgentTool<A>`？
- `prepareArguments`、`ToolSchemaValidator`、`BeforeToolCall` 与 `treeToValue` 的顺序为何固定？
- 工具异常为什么转为 error result 而不是抛进 loop？
- `terminate` 为什么只存在于运行时结果而不进入标准 transcript？
