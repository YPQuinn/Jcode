# 06. 并发与取消

> 状态：待补充。

本章用于解释 Jcode 当前的并发模型、单 active run 约束、虚拟线程使用，以及取消所有权边界。

## 待回答的问题

- 为什么每个 `Agent` 同时最多一个 active run？
- `CancellationSignal` 与 `CancellationSource` 的所有权如何划分？
- 为什么 `AgentEventSink.emit()` 返回的 stage 必须被等待？
- 慢 sink 如何形成 backpressure？
