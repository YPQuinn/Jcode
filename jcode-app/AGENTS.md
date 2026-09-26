# jcode-app 指南

本模块是传输无关的进程内应用服务，管理 `SessionRegistry` 与 `ManagedSession`。它只使用 `RUN_SCOPED` 的 `CodingAgentSession`，不能建立第二套可执行输入队列，也不能自行写正式历史。

- `SessionRegistry` 对一个文件路径只保留一个 writer；并发 open 共享同一结果。关闭要求空闲，不删除 Session 文件。单个 registry 最多托管 64 个 Session。
- `ManagedSession` 先在短锁内查命令去重，再检查新请求的状态和预期 leaf；同步预留 Run 或输入占位，锁外调用内核。相同命令返回原身份与最新状态，不同请求冲突。内核输入接纳成功前不得向外返回已接纳输入。
- 每个 Session 最多保留 1,024 条应用命令回执；容量满时只淘汰最早的终态记录，不能淘汰活跃或接纳未决命令。过期回执不承诺继续去重；内核的输入记录和正式历史仍按各自生命周期查询。
- Run 的公开终态只按产品 stage 与最终 stop reason 结算一次。`ERROR` 为 failed，`ABORTED` 为 cancelled，取消请求另行保留。结果更新和释放应用占用在同一短锁内完成；等待模型、文件、Future 或用户回调必须在锁外。
- A2 不提供网络入口、跨进程恢复、事件重放或审批；这些能力不能以当前内存表作持久化承诺。
