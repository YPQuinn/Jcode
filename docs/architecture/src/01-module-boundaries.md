# 01. 模块边界

> 状态：待补充。

本章用于解释 Maven reactor、模块依赖方向，以及当前通过 enforcer 守住的边界。

## 待回答的问题

- `ai` 为什么必须保持零 Jcode 内部依赖？
- `agent-core` 为什么只能依赖 `ai`？
- 哪些包是源码包而不是 Maven 模块？
- 为什么禁止 `common` / `shared` 杂物模块？
