# 02. AI 协议层

> 状态：待补充。

本章用于解释 `ai` 模块的 provider-neutral 模型调用协议，以及模型、消息、工具声明、流式事件之间的关系。

## 待回答的问题

- `ModelClient` 与 `ModelRequest` 的边界是什么？
- 标准 `Message` / `Content` / `StopReason` 如何作为事实来源？
- `ToolSpec` 在协议层解决什么问题？
- provider adapter 应该承担哪些转换职责？
