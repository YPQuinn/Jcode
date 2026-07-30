# 03. Agent Runtime

> 状态：待补充。

本章用于解释 `agent-core` 的通用运行时设计，包括 `Agent` 门面、`AgentLoop` 主干、`AgentContext` 与 `LoopState` 的分工。

## 待回答的问题

- `Agent` 为什么是公开门面而不是业务对象？
- 一次 run 的不可变上下文和可变状态如何隔离？
- 事件归约 sink 为什么必须先更新状态再委托用户 sink？
- `LoopResult` 如何表达一次运行的结束结果？
