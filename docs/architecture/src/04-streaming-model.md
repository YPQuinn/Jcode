# 04. 流式模型

> 状态：待补充。

本章用于解释 Assistant stream 的事件模型、partial 消息语义，以及最终消息如何进入上下文。

## 待回答的问题

- `AssistantMessageEvent` 的各类事件如何组成一次 assistant 输出？
- 为什么 partial 不进入 `AgentContext`？
- `Done` 与 `Error` 如何都产生最终 assistant 消息？
- `MessageStarted` / `MessageUpdated` / `MessageCompleted` 的顺序契约是什么？
