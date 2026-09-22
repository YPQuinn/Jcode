# 运行时正确性契约

修改流协议、取消、agent loop、hook、队列、事件或工具执行时读取本文。具体类型的详细约束见对应模块的 `AGENTS.md`。

## 模型流

- `ModelClient.stream()` 必须返回 `AssistantMessageStream`，不得同步抛出 provider 或网络失败。
- 成功、provider 失败、网络失败和取消都通过唯一一个携带最终 `Message.Assistant` 的 `Done` 或 `Error` 事件终止。
- `AgentLoop.consumeStream()` 在 `Start` 时发出 `MessageStarted`，在 delta 时发出 `MessageUpdated`，在 `Done` 或 `Error` 时返回最终消息。
- Partial assistant 不得进入持久 context。

## Agent 生命周期与事件

- 一个 `Agent` 同时最多接纳一个 active run；并发 `prompt()` 或 `continueRun()` 必须 fail fast。
- `updateSystemPrompt()` 与 `replaceMessages()` 只允许在 idle 状态同步执行；busy/closed 时拒绝且不得部分修改 context。消息替换只更新 transcript 及公开状态快照，保留 system prompt、tools、steering/follow-up 队列与 error，不触发模型、工具、hook、投影或事件。
- 归约 event sink 先更新 `AgentState`，再调用用户 sink，确保用户处理事件时看到已归约状态。
- `AgentEventSink.emit()` 返回的 stage 具有 backpressure，必须等待；`RunEventEmitter` 是唯一等待点。
- User 与 tool-result 消息发出 `MessageStarted → MessageCompleted`；assistant 消息发出 `MessageStarted → MessageUpdated… → MessageCompleted`。
- 流式事件投递失败时，取消在途 provider、清理 run 状态，并让 run future 异常完成；不得将 sink failure 改写为模型失败。

## Session 产品历史

- `CodingAgentSession` 默认维护内存历史；文件模式必须通过显式 `create/open` 取得 writer 所有权。宿主只读取不可变快照，不能直接持有可变 Session manager。
- 只有 core 的 `MessageCompleted` 可以接纳标准消息；`MessageStarted`、delta、`ToolCompleted` 和投影消息都不得伪造历史。文件模式按“写入文件 → 接纳内存树 → 通知宿主 sink”排序。
- 宿主 sink 或 writer 导致 run 基础设施失败时，下一次产品操作前必须用已接纳分支重新对齐 Agent transcript。writer 失败不接纳该消息，并封锁同一 owner 的后续追加；后续 `prompt()`、`continueRun()` 和元信息修改必须在调用 provider 或生成新 Entry id 前拒绝。
- `continueRun()` 只允许当前分支非空且 leaf 是 user/tool-result 时调用，复用 core continuation，不伪造 user 消息。open 使用调用方当前模型、工具和配置；历史 model/thinking 仅描述历史，并在下一条完成消息前按需追加当前配置元信息。
- `branch()`、`resetLeaf()`、`setName()` 与 `setLabel()` 是 idle-only 历史操作，与 run、reload 和 close 互斥。branch/reset 只移动当前 leaf 并替换 Agent transcript，不删除旧 Entry、不执行工具、不回滚工作区，且保留 steering/follow-up 队列。
- 取消调用方拿到的观察 Future 不取消或释放已接纳运行。close 时若运行或历史追加仍在途，Session writer、文件锁及 Session 自建 Provider 必须保留到对应完成回调结束。
- model/thinking 切换是 idle-only 产品操作，与 run、reload、历史操作和 close 互斥；core 在接纳 run 时原子捕获该对参数。切换本身不写历史或默认设置，下一条真实完成消息前按实际 run 参数追加必要差异。

## Settings、凭证与模型装配

- 新工厂只读取宿主显式提供的用户配置目录和工作目录；不推导 HOME，不扫描祖先 settings。项目设置必须先由 SDK 决定或独立 trust store 的精确规范路径决定授权；未授权时不得打开项目 settings。
- sparse settings 按“内建 < 全局 < 已授权项目 < SDK”合并，缺失与空工具列表不同；ModelRef 和工具列表整体覆盖，request 仅按已知叶字段合并。非法已知字段使整层不应用，未知字段只诊断、不进入运行时。
- 凭证优先级固定为 SDK、显式启用的单变量环境查询、只读 `auth.json`。高层非法值不得降级到低层；密钥只进入现有秘密持有类型，不进入设置结果、诊断、事件或 Session。
- 新 Session 必须有显式/默认模型，不从目录挑第一个。恢复时优先历史模型，历史不可用才尝试不同的配置默认；SDK 明确模型不可用时直接失败。该选择只检查本地 provider/supports/auth 配置，不表示远端认证成功，也不触发网络。
- borrowed `Models` 由宿主关闭；工厂自建 Provider 随 Session 关闭且只关闭一次。构造或恢复失败必须释放已取得的 Session writer 和自建资源。
- settings/trust 保存是同步显式操作：稳定旁路锁文件、同 JVM 短时 reservation、跨进程 `tryLock()`、锁内重读、同目录临时文件和原子替换。锁冲突立即失败；不删除锁文件、不后台重试、不用 SessionFileAccess，也不把保存与当前 Session 切换包装成事务。

## Session 文件

- Session JSONL 使用显式 version/type/role/content 分派，不使用 Java 默认多态反序列化。标准消息的多模态内容、工具参数、usage、metadata、source model 与 replay state 必须无损往返；JSON 浮点数按 `BigDecimal` 读取，不能先经过二进制浮点而损失工具参数精度。
- `SessionManager` 是 Entry 序列及索引的唯一长期内存所有者；reader 只做一次临时解析与校验，`SessionFile` 只持有 Header、通道、锁、追加位置、尾部恢复和失败状态。
- 一个 Session 文件在 writer 生命周期内持有独占文件锁；锁冲突立即失败，不进行多写者协调、等待或重试。同一 JVM 必须协调 writer reservation 与同文件临时读取生命周期。活动 owner 的发现查询从 `SessionManager` 已接纳历史计算，不能在查询线程读取 writer 通道；无 owner 的临时读取仅阻塞同文件 writer，registry 全局锁不得覆盖摘要计算、文件 I/O 或资源关闭。
- open 只读取和诊断，不修改文件。只有 EOF 截断 JSON 或不完整 UTF-8 后缀可恢复；首次后续追加先截断该尾片段。中部损坏、未知版本/类型、坏父链和完整但非法的末行必须失败且保持原文件不变。
- 顺序追加必须处理短写。实际写入失败后磁盘尾部状态不确定，当前 writer 必须封锁后续追加；底层 channel 已关闭或原生锁已失效时也必须在 provider 调用或 Entry id 生成前拒绝新操作。不得自动重试同一 Entry、退回内存模式或声称回滚成功。
- 普通追加不逐条 `force()`，因此只承诺成功写完整本次字节，不承诺断电 durability。
- `SessionFiles` 只扫描调用方显式目录的一层 `*.jsonl` 文件；可按规范化 cwd 精确过滤。合法会话按最新 user/assistant 活动时间倒序排序，无此类消息时使用 Header 时间；坏文件不得阻断其他结果，只返回不含消息内容的逐文件诊断。发现和 recoverable-tail 诊断不得修改文件。

## Context 投影与 Turn hook

每次模型调用前，先异步且感知取消地运行 `ContextTransformer`，再同步运行 `MessageProjector`。输出只是本次请求视图，不得修改 transcript，也不得进入 context、`LoopResult.newMessages` 或事件。

每个正常的 `TurnCompleted` 之后按顺序执行：

1. 运行 `PrepareNextTurn`。
2. 原子应用 context/model/thinking 更新。
3. 运行 `ShouldStopAfterTurn`。
4. Drain steering 消息。
5. Drain follow-up 消息。

优雅停止不得重写 stop reason，也不得 drain 已排队的 steering/follow-up。Terminal model failure 跳过这些 hook。Hook 失败归一为 terminal `ERROR` 或 `ABORTED` assistant，而不是使 run 异常完成。

## 取消

- `CancellationSignal` 是只读协议，不得 cast 为 `CancellationSource` 后触发取消。
- `cancel()` 必须幂等；每个 listener 最多执行一次，单个 listener 失败不得阻止其他 listener 或使 `cancel()` 失败。
- Cancellation listener 必须非阻塞。
- 不使用阻塞 sleep 轮询，也不使用长期共享的无界平台线程池。

## 工具管道

每个工具调用都经过 `ToolCallExecutor` 所有的统一管道：

1. **Prepare：**准备参数 → schema 校验 → `BeforeToolCall` → `treeToValue` 转换。
2. **Execute：**使用 cancellation signal 与 update sink 调用工具；工具异常归一为 error result；settle sink。
3. **Finalize：**运行 `AfterToolCall`，再创建 `Message.ToolResultMessage`。

正确性规则：

- 未知工具、schema 失败、参数转换失败、hook 阻止和工具失败都转为 error `ToolExecutionResult`，不得抛入 loop。
- 事件投递失败、executor 拒绝或中断等基础设施失败不是工具失败；必须在 drain 已提交工作后传播。
- `AfterToolCall` 失败时保留原工具结果。
- `ToolUpdateSink` settle 后到达的 update 被静默丢弃。
- `LENGTH` 响应中的所有 tool call 都转为 failure，禁止执行。
- 任一调用声明 `SEQUENTIAL` 时，整批按源顺序执行。
- 并行批次按源顺序 prepare 并发出 `ToolStarted`，按完成顺序发出 `ToolCompleted`，然后恢复源顺序写入 transcript、context 与 `TurnCompleted.toolResults`。
- Finalize 前必须投递所有在 sink settle 前已接纳的 update。
- `terminate` 只存在于运行时，不得进入标准模型 transcript。
