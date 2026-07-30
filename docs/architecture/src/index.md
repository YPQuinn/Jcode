# Jcode 架构解读文档集

本文档集用于沉淀 Jcode 的架构解读、模块边界、运行机制与设计约束。它不是短期实施计划，也不是外部资料摘录；它面向后续维护者，解释当前代码为什么这样组织、关键流程如何协作，以及扩展时应守住哪些边界。

## 阅读顺序

建议按以下顺序阅读：

1. [`00-overview.md`](00-overview.md)：项目全局视图、目标范围、模块关系。
2. [`01-module-boundaries.md`](01-module-boundaries.md)：Maven reactor、模块依赖方向、禁止跨越的边界。
3. [`02-ai-protocol.md`](02-ai-protocol.md)：`ai` 模块提供的 provider-neutral 模型调用协议。
4. [`03-agent-runtime.md`](03-agent-runtime.md)：`agent-core` 的 Agent 门面、运行循环与状态归约。
5. [`04-streaming-model.md`](04-streaming-model.md)：Assistant stream 事件、partial 消息、完成语义。
6. [`05-tool-pipeline.md`](05-tool-pipeline.md)：工具三阶段管道、hook、schema 校验与结果写回。
7. [`06-concurrency-and-cancellation.md`](06-concurrency-and-cancellation.md)：单 run 约束、虚拟线程、取消所有权与 sink backpressure。
8. [`07-extension-roadmap.md`](07-extension-roadmap.md)：未来 provider、coding-agent、server、tui 等模块的扩展入口与门槛。

## 写作约定

- 正文使用中文，代码标识符、模块名、类名、协议名保留英文原名。
- 每篇文档优先回答三个问题：解决什么问题、核心抽象是什么、哪些边界不能破坏。
- 架构解读必须以当前源码为准；历史方案可引用 `docs/plans/archived/`，但不能作为当前实现依据。
- 新增文档时保持数字前缀顺序，避免同一主题分散到多个文件。
