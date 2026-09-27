# jcode-app 指南

本模块是传输无关的进程内应用服务，管理 `SessionRegistry` 与 `ManagedSession`。它只使用 `RUN_SCOPED` 的 `CodingAgentSession`，不能建立第二套可执行输入队列，也不能自行写正式历史。

- `SessionRegistry` 对一个文件路径只保留一个 writer；并发 open 共享同一结果。关闭要求空闲，不删除 Session 文件。未接纳的忙碌关闭保留索引；已结算的关闭即使抛错也按对象身份注销，原异常继续传出。open 不复用正在关闭的实例。单个 registry 最多托管 64 个 Session。
- 服务宿主可用严格模式的 `CodingAgentSessionOptions` 经工厂创建/打开托管 Session；Registry 仍负责同文件 writer 复用和应用观察装饰。创建/打开占用计入容量和空闲停止判定；最终登记时复查停止状态，迟到完成的内核 Session 不得发布，须关闭其 writer。
- Registry 的空闲停止判定与 ManagedSession 新 Run 接纳共用短的 admission lock；停止先取得边界时拒绝新 Run，Run 先取得边界时停机看到活跃占用。模型执行和 Session 文件 I/O 不在该锁内。
- `ManagedSession` 先在短锁内查命令去重，再检查新请求的状态和预期 leaf；同步预留 Run 或输入占位，锁外调用内核。相同命令返回原身份与最新状态，不同请求冲突。内核输入接纳成功前不得向外返回已接纳输入。
- 每个 Session 最多保留 1,024 条应用命令回执；容量满时只淘汰最早的终态记录，不能淘汰活跃或接纳未决命令。过期回执不承诺继续去重；内核的输入记录和正式历史仍按各自生命周期查询。
- Run 的公开终态只按产品 stage 与最终 stop reason 结算一次。`ERROR` 为 failed，`ABORTED` 为 cancelled，取消请求另行保留。结果更新和释放应用占用在同一短锁内完成；等待模型、文件、Future 或用户回调必须在锁外。
- `SessionFeed` 在与 ManagedSession 共用的短锁内提交视图、seq 和有界重放。订阅各有 128 条缓冲，慢订阅者须重新取快照；客户端断线不取消运行或审批。工具更新是替换式快照，消息及工具文本在投影层有界。
- 服务端关闭事件流时，已接纳的订阅事件仍可读完，随后明确报告 `SESSION_CLOSED`；客户端主动退订可立即丢弃自己的队列。工具投影按 runId 与 toolCallId 二元身份查询，不拼接不透明 ID。
- 审批只对 `ApprovalSettings` 明确列出的工具启用，先遵循原 `CodingToolPolicy`。每条待审批记录绑定一次不可变工具请求和摘要；允许、拒绝、取消、超时仅能结算一次，Future 在锁外完成。默认工具策略及权限保持原样。
- 审批终结后取消其独立超时任务，包括状态先结算、计时句柄后登记的竞争；共享定时器的取消任务须从等待队列移除。
- A3 仍不提供网络入口、跨进程运行恢复或持久化事件日志；内存视图、待审批 Future 与事件缓冲不作进程重启保证。
