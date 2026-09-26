# jcode-protocol 指南

本模块保存传输无关的请求、回执、状态和错误值类型。它不依赖内核、Provider、HTTP 框架或可变全局注册表。客户端语言独立性以明确 JSON 形状和往返测试验证。

- 只为已实现的应用操作增加 DTO；事件订阅、审批和完整 schema 生成属于后续批次。
- 命令身份由调用方提供。ID 非空；正文保留原文，不以 trim 改写用户输入。诊断 `toString()` 不显示正文。
- `RunView` 是终态摘要，文本最多 16,384 个 UTF-16 code unit；完整消息仍由 Session 历史负责。`stopReason` 描述模型结果，异常完成时为空；`cancelRequested` 不覆盖实际终态。
- 类型保持不可变，不暴露内部 `AgentMessage`、`Throwable` 或 Provider 私有状态。变更公开字段或枚举时同步更新 JSON 往返测试。
