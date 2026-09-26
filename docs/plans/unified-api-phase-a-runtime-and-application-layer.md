# 统一 API 阶段 A：运行内核与最小应用层实施计划

| 项目 | 内容 |
|---|---|
| 依据 | [总体架构 v0.1](unified-api-and-multi-end-architecture-design_v0.1.md)，重点为 §4.4、§5、§7、§9、§13、§14 |
| 代码基线 | `YPQuinn/Jcode@6db4fd35a3a6cf5b7780fde036f4e80ef82a933c` |
| 日期 | 2026-09-26 |
| 状态 | 实施计划；本文不表示 Java 改造或测试已经完成 |
| 本轮约束 | 不过度设计，不引入复杂安全机制；优先复用现有代码 |

## 1. 本阶段只解决什么

目标是：**在不引入 HTTP 或 UI 的情况下，让同一托管 Session 的运行、补充输入、结果与观察具有可测试的一致语义。** 后续 HTTP/SSE 只适配这些能力，不重新实现业务规则。

本阶段按三个可验证批次推进：

| 批次 | 交付物 | 明确不做 |
|---|---|---|
| A1：严格输入内核闭环 | 产品 Run 范围、输入身份、历史应用确认、取消及终结处理，保留旧兼容行为 | 新 Maven 模块、HTTP、数据库 |
| A2：最小应用服务 | 必要协议类型、会话托管、运行与输入查询、幂等提交、终态归一化 | 完整 OpenAPI、通用任务调度或事件总线 |
| A3：进程内多端恢复 | 一致性快照、有界事件重放、订阅隔离、一次性工具审批 | 后台服务启动、真实浏览器、完整客户端 SDK |

先完整实现 A1，再开始 A2。不要先建立三个空模块、十几个 Service 或一套未来可能使用的抽象。原讨论中的“输入类型”与“真实历史接入”合并为一个功能交付，避免只有回执而没有真实保证。

阶段 B 再实现独立服务、HTTP/SSE、基本认证和客户端进程退出演练。阶段 C 再做正式 TUI、WebUI、Desktop 和高级管理接口。

## 2. 已确定的取舍

### 2.1 输入归属与状态

补充输入记录由 Session 保存；首次注入只属于指定的产品 Run。`steer` 与 `follow-up` 只区别注入时机，后者不创建下一 Run。内部压缩及 `continueAfterFailure()` 属于原产品 Run；用户在终态后发起新 prompt/continue 才是新 Run。

```text
pending -> applied_to_context | not_applied | reconciliation_required
reconciliation_required -> applied_to_context | not_applied
```

- `applied_to_context`：正式历史已经接纳，并取得真实 `entryId`。不表示 Provider 已收到请求或模型遵循了要求。
- `not_applied`：确认未进入正式历史，且本次投递已结束。
- `reconciliation_required`：无法确认历史接纳结果，冻结投递资格。它只是状态和查询结果，不需要一个后台“核对服务”。没有可靠证据时不自动重试。

出队不等于应用。目标 Run 结束后，不允许仍有可投递的 `pending` 输入；未应用记录保留供查询，不转移至下一 Run。已经进入历史的输入仍按正常历史与分支规则继承，不删除、不回滚文件。

### 2.2 兼容策略

在 Session 创建时显式选择两种模式，建议名称为 `LEGACY_SESSION_QUEUE`、`RUN_SCOPED`。旧构造及旧嵌入用法默认保持前者，统一应用服务固定使用后者；运行中不可切换。

严格模式只接受携带运行和输入身份的新入口；无身份的旧 prompt/continue/steer/follow-up 调用在严格模式明确拒绝，并给出新入口提示。不自动推断“当前 Run”或生成一条客户端无法查询的隐式补充输入。旧模式不开放新的严格入口。同一个 Session 不混用两套语义。

配置沿现有 Config/Options 与工厂传递；保留旧构造重载的委托默认值，不新增单独的配置文件、版本迁移框架或动态模式系统。新模式不进入 Provider 协议。

### 2.3 取消结果

```text
accepted -> running -> completed | failed | cancelled
accepted / running -> cancelling -> completed | failed | cancelled
accepted -> failed
```

`cancelRequested` 单独保存。取消关闭新补充输入接纳，但不预先决定最终 Run 结果。成功、模型 ERROR、ABORTED、基础设施异常分别按实际结果归一化；stage 正常完成不必然等于运行成功。

取消、关闭 Session、断开订阅是不同操作；观察对象取消和订阅断开不取消已接纳 Run。取消只针对明确的运行身份，旧请求不能取消后来的 Run 或维护操作。

## 3. 当前代码接缝与最小改动范围

以下均以代码基线为准；类名中的新增建议在首次实现时统一，不逐个包装 ID 类型。

| 位置 | 当前事实 | 本次处理 |
|---|---|---|
| `agent-core/.../queue/PendingMessageQueue.java` | 只保存 AgentMessage；没有输入 ID 或产品 Run 范围 | 旧路径保持；严格路径使用可携带关联信息的输入来源 |
| `agent-core/.../Agent.java` | 长期持有 steering/follow-up 队列 | 允许产品层给一次底层运行提供严格输入来源，默认仍使用旧来源 |
| `agent-core/.../AgentLoop.java` | 先 drain，再检查取消及追加上下文 | 在领取、实际应用及完成通知中保留身份；不能因局部 pending 列表退出而丢失记录 |
| `coding-agent/.../CodingAgentSession.java` | 管理产品运行和内部溢出恢复 | 建立产品范围，管理严格输入记录，在最终结束前关闭和结算 |
| 同类 `emitProductEvent()` | `appendCompletedMessage()` 返回历史 entry 后，再通知扩展及外部 sink | 在这一成功提交点记录 inputId/entryId；外部观察失败不得抹去关联 |
| 同类 `finishRunFinal()` | 发出产品完成事件后才释放 running 并完成 stage | 严格模式先关闭并结算输入，再进入最终事件观察链；应用层以最终 stage 结算 Run |
| Config、Options、Factory、SnapshotMapper | 已有显式配置、装配及不可变复制路径 | 只同步新增参数和关联信息，不另建装配框架 |

正式历史仍只由 `SessionManager` 写入。关联通知不能额外再写一次用户消息；不修改模型正文、不插入隐藏 ID 文本、不按内容或时间戳匹配输入，也不为了关联改造所有 `ai.Message` 类型。

### 3.1 建议公开入口

下面是接口草案，不是现有可调用 API；使用现有项目的 Java 21、record 和显式装配风格。ID 先使用经过基本非空检查的 String，不为每一种 ID 建包装类。

```java
// 仅说明拟增加的方法边界；实际异常与包名在 A1 一并实现。
CompletionStage<CodingAgentRunResult> prompt(String runId, String text);
CompletionStage<CodingAgentRunResult> continueRun(String runId);
InputRecord submitInput(InputRequest request);
Optional<InputRecord> input(String inputId);
List<InputRecord> inputs(String runId);
void abort(String runId);
```

`InputRequest` 最小为 `inputId / targetRunId / mode / text`；`InputRecord` 包含请求身份与内容、四种状态、可空 `entryId` 和原因。应用层 `commandId` 映射到内核 `inputId`，无需再让内核实现一套通用命令日志。

`runId`、`inputId` 由调用方在发起操作前生成。严格范围开始前建立 runId，输入接纳时在同一短临界区登记 ID 和入队。范围检查失败不能返回成功记录。

同一 inputId 的重复调用：先查已登记记录；请求一致则返回原记录，请求不同则冲突，再对真正的新输入检查目标范围是否开放。这是唯一记录的完整性检查，不是引入一套持久化消息投递系统。记录过期后不承诺再次提交去重。

旧无参 `abort()` 保留现有嵌入管理语义；统一服务只使用指定 runId 的取消入口。取消在身份核验与信号取得之间不能切换目标，回调和信号传播放在锁外，捕获的旧范围引用不能影响新运行。

## 4. A1：严格输入的实际实现

### 4.1 一个权威记录组件，不能两套可执行队列

在 `coding-agent` 内增加一个非公开的 `RunInputState`（名称建议）。它保存当前产品 runId、输入接纳开关、输入记录表和待领取的 steering/follow-up ID 队列。表是权威状态，队列只保存待处理 ID，不复制完整可执行消息。

它向 core 提供输入来源；同一产品 Run 的内部重试继续使用同一来源。旧模式继续使用已有队列。`jcode-app` 后续只读取记录并维护投影，不能再创建另一套能够自行执行的输入队列。

core 只需要输入消息、关联 ID 和少量领取/提交接缝，不认识产品 Session 历史、HTTP DTO、认证、审批或 JSONL。新增队列项和通知优先复用现有源/事件接口；必要的重载或默认适配只用于保留旧调用，不建立通用信箱或工作流 SPI。

### 4.2 身份贯穿领取和历史接纳

按下面的过程接入真实 Loop：

```text
登记 InputRecord + 入队
  -> Loop 领取（内部 claimed，公开仍为 pending）
  -> 应用前取得提交资格（内部 applying）
  -> 按既有顺序追加上下文并处理 MessageCompleted
  -> SessionManager 接纳正式历史，返回 entryId
  -> 权威记录改为 applied_to_context，保存 entryId
  -> 再通知产品观察者、扩展和外部 sink
```

关联信息随队列项和消息通知传递，不靠对象地址或全文比对。产品入口要把关联信息带到原历史追加位置；不要通过第二个事件重复追加历史。

取消先于提交资格取得时，尚未应用输入不再开始提交；提交资格已取得时，允许正在进行的历史写入按真实结果结算。不能为了显示“已取消”，把已成功接纳的输入改为未应用。

底层尝试中断时，仍由权威表保留 claimed/applying 身份。仅确认未开始提交且产品范围仍开放的输入，才可回到同一产品范围原有顺序；已应用不重新入队，结果不明冻结。最终结束则直接结算，不回到其他 Run。

### 4.3 最终结束的顺序

`CodingAgentSession` 在判断不再进入内部恢复后执行：

```text
关闭输入接纳
  -> 结算已领取/正在提交的输入
  -> 确认未应用的剩余记录标为 not_applied
  -> 无法确认的记录标为 reconciliation_required
  -> 通知最终产品事件
  -> 释放内核运行占用，完成公开 stage
```

关闭必须发生在最终 `RunCompleted` 的 sink 等待之前。内部 `continueAfterFailure()` 不关闭产品范围。模型 ERROR、主动取消、启动失败、基础设施异常、Session close 都要走可追踪的范围清理，不能只覆盖正常成功路径。

沿用现有生命周期的短锁，输入状态尽量共用它；不要为每条输入增加线程、锁或计时器。不在锁内等待 I/O、模型、观察者或 Future；必要的在途提交计数/完成信号只在实际存在异步提交时增加，不能为了形式对每条消息套事务框架。

历史已接纳后观察者失败，输入仍是已应用；在历史写入失败且结果不能确认时，保留待核对，不自动扫描所有文件重建状态。不新增补偿事务、回滚系统或后台重试服务。

### 4.4 保留旧行为与回归

保留现有三个跨 Run 测试的期望：完成背压期间迟到输入、取消后 follow-up、模型失败后 follow-up。严格模式新增独立测试类，不用更新旧测试来掩盖行为变更。

严格路径完成后再将统一服务绑定到它；不能通过先清空旧队列再启动下一 Run 的方式伪装严格语义，因为清空无法提供输入身份、应用确认及并发结算。

## 5. A2：最小协议与应用服务

A1 通过后再新增 `jcode-protocol` 和 `jcode-app`；暂不建立 `jcode-server`。在根 POM 登记实际使用的模块与边界，不增加 Spring、Reactor、消息中间件或新的存储依赖。

先只需要两个主要应用类：

- `SessionRegistry`：按 sessionId 查找和托管 Session，同一历史文件只打开一次。
- `ManagedSession`：组织运行记录、命令映射、必要视图及后续订阅。内部 Map 和小型帮助类能表达的职责，不立即拆成多个 Registry/Manager/Service。

协议仅定义首个闭环用到的请求、回执、状态、错误和事件信封。一个类型有真实使用者才增加；先手写少量 DTO 和 JSON 往返测试，不上来搭建跨语言代码生成流水线。

### 5.1 接纳与幂等

新 Run 的 commandId/runId 和应用占用先登记，再调用内核；启动同步失败或 stage 异常也形成可查询结果。对补充输入，只有内核登记成功才返回已接纳；事件可能先于方法返回，因此应用层先建立 inputId 的命令关联占位。

同一命令只需一份内存记录，保存规范化请求和执行结果。先查重，再检查新请求的 expectedLeafId、目标 Run 和接纳状态。相同键不同请求返回冲突；相同请求返回原身份与结果。并发提交采用短锁和占位结果，不采用无保护的先查后建。

首期不做跨进程幂等、外部缓存、令牌桶或分布式锁。数据限制采用简单固定上限；活跃或结果未决记录不能被淘汰，容量满则拒绝新接纳。仅淘汰终结记录，并公布保留范围与过期行为，不承诺无限期去重。

### 5.2 终态与观察

以产品 stage 的最终结算为应用终态输入，同时检查 result.stopReason 和异常。内部 RunCompleted 不再单独发布另一份 run.finished。cancelRequested 只记录请求，不能覆盖实际完成或错误。

在 ManagedSession 的同一状态提交中更新输入结果、Run 结果、应用占用和最终事件，再接纳下一命令。外部回调一律锁外执行，避免回调同步发起下一操作而自锁。

可先实现创建/打开会话、启动/查询 Run、提交/查询输入、指定 Run 取消、空闲关闭。分支、模型、资源等管理接口不在本批次铺开；其已有内核能力保留，A1 的分支回归仍需通过。

## 6. A3：一致视图、订阅及最小审批

### 6.1 快照与重放

ManagedSession 维护一个内存视图和一个有界重放缓冲。用同一短临界区完成事件序号分配、视图更新和缓冲接纳；snapshot 从该边界取得视图和游标。取得重放水位与登记实时订阅也在同一边界完成，不能留漏事件窗口。

历史继续由内核提供，视图只保存当前展示需要的内容和历史引用。新增 inputId/entryId 映射与消息历史关联由真实提交通知提供；工具 snapshot 替换而非重复追加。终态可见时，该 Run 不再保留可执行 pending 输入。

缓冲只按事件数/总字节设上限；大工具输出沿用有界尾部并标明截断。不引入完整事件溯源、永久事件日志、细分的十几种限流预算或自动调优器。

每个订阅独立有界队列。慢消费者断开并要求重新同步；内核 emit 只等待服务端事件接纳，不等待客户端。事件桥自身失败仍按基础设施错误传播，不能与客户端掉线一起吞掉。

先用进程内测试客户端证明相同快照和事件得到一致视图；不声称已验证终端退出、跨进程服务存活或浏览器兼容。

### 6.2 一次性审批

复用 `CodingToolPolicy`，只加待审批记录和等待 Future。支持允许本次、拒绝本次、取消、一个可配置等待超时；不增加策略语言、长期授权学习或审批工作流引擎。

审批决定与取消通过一次状态转换竞争；状态先提交，Future 在锁外完成。原订阅断开不改变审批，另一订阅可以查询和处理。批准绑定不可变的那次工具请求，不批准修改后的参数。

工具策略、工具超时及清理规则沿用内核，不降低现有默认权限，也不把客户端在线与否当成授权依据。

## 7. 不做复杂安全体系，但保留最低边界

阶段 A 不监听网络，所以不写 OAuth、RBAC、账号系统、JWT、mTLS、设备配对、租约、权限数据库或分布式审计。也不自研容器沙箱、命令白名单语言、逐文件授权或跨平台进程隔离。

阶段 B 的最低方案是：默认仅 loopback；单用户服务凭证；受限浏览器来源；Provider 密钥只留在服务端且不进入日志；工作区由可信本地配置或受认证用户明确选择。不把 CORS 当认证，不默认公开一个无需凭证即可执行 shell 的端口。

不开发任意目录安全扫描或自动风险打分。沿用已有文件、工具和项目授信逻辑，并如实说明本地文件/shell 不是沙箱。同工作区并发策略先用简单串行规则，不制造分布式文件锁。

以上仅是下一阶段边界，不成为 A1 的依赖或额外开发任务；本计划不改变“默认只读工具需显式开启写入/shell”的已有约束。

## 8. 测试、交付与执行顺序

### 8.1 最小测试矩阵

| 编号 | 必须证明的行为 | 所属批次 / 总体设计对应 |
|---|---|---|
| K01 | 旧三个跨 Run 测试保持通过；严格与兼容模式不混用 | A1 / T20 |
| K02 | 严格 steer/follow-up 在同一产品 Run 应用，entryId 可查，消息只追加一次 | A1 / 输入基础契约 |
| K03 | 最终完成 sink 被阻塞时，新输入拒绝；不进入下一 Run | A1 / T11 内核部分 |
| K04 | 取消、模型 ERROR、branch/reset 后均不注入旧未应用输入 | A1 / T12、T13 |
| K05 | 出队后取消不冒充已应用；历史已接纳后观察失败仍保留关联 | A1 / T14、T18 |
| K06 | 输入接纳与范围关闭竞争：稳定接纳且可结算，或者明确拒绝 | A1 / T16 |
| K07 | 内部溢出恢复仍使用同一产品 Run；已应用输入不重复注入 | A1 / T15 |
| K08 | 终态交付前接纳取消仍可能正常成功；结果未被取消标志覆盖 | A1+A2 / T17 |
| P01 | 并发或响应丢失后的相同命令只创建一个 Run/Input；结束后仍返回原回执 | A2 / T04、T11、T19 |
| P02 | 成功/ERROR/ABORTED/异常映射正确；旧取消不影响新 Run；旧占用释放后可开始新任务 | A2 / T05、T07 |
| P03 | 快照和实时交错不漏事件；重放重复可去重，游标过期明确重同步 | A3 / T06、T08 |
| P04 | 订阅断开不影响执行；中途接入可恢复输入和审批，审批仅结算一次 | A3 / T02、T03 的进程内部分 |

使用可控 ModelClient、真实 Session/Loop 和临时历史存储。并发测试通过 latch/Future 精确控制关卡，不用 sleep 碰运气，不为竞态测试引入重型仿真框架。HTTP、独立进程与服务重启相关的完整 T01/T09/T10 留在阶段 B，不能拿进程内测试替代。

### 8.2 每批工作方式

实施前读取根与受影响模块 AGENTS.md、相关主题指南；按仓库工作流定向查看 pi/pi-book 参考，只借鉴设计意图，不照搬跨语言框架。本计划不因参考项目存在更多能力而扩展范围。

每批：先添加契约测试 → 实现最小路径 → 补取消/错误/观察失败测试 → 运行受影响模块测试与全仓校验 → 只更新受影响的指南和计划状态。可以先写失败测试，但合入批次必须可编译且通过回归。

```bash
# A1：core 与 product 回归
mvn -pl coding-agent -am test

# A2/A3：相应模块建立后执行
mvn -pl jcode-app -am test

# 每批合入前
mvn verify
```

不为本阶段新装数据库、启动真实模型或绑定云服务。需网络下载 Maven 依赖时按开发环境正常处理；测试没有实际执行就不得标为通过。提交按仓库约定另行确认，不混入其他工作区改动。

### 8.3 本次与下一次交付

- [x] 编制本阶段实施计划，明确最小范围和验收边界。
- [x] A1：完成严格输入内核闭环及 K01–K08 中的内核测试。
- [ ] A2：完成最小应用服务和 P01–P02。
- [ ] A3：完成进程内恢复、订阅与审批，验证 P03–P04。
- [ ] 阶段 B：独立服务与两个客户端进程的真实退出/接管演练。

**A1 实施范围仅包含内核闭环。** 不顺手引入 jcode-server、认证、通用命令总线、完整协议生成或前端。A1 以真实 Loop、真实历史和严格语义一起通过为功能完成标准。

## 9. 核验依据

本计划最初基于静态源码核对编制。A1 完成后，严格模式的 8 项测试与旧兼容测试均通过，已运行全仓 `mvn verify`。总体设计的目标状态与现有兼容行为应继续分开标注。

- [总体架构设计](https://github.com/YPQuinn/Jcode/blob/6db4fd35a3a6cf5b7780fde036f4e80ef82a933c/docs/plans/unified-api-and-multi-end-architecture-design_v0.1.md)：§4.4、§5、§7、§9、§13、§14。
- [CodingAgentSession.java](https://github.com/YPQuinn/Jcode/blob/6db4fd35a3a6cf5b7780fde036f4e80ef82a933c/coding-agent/src/main/java/site/pplee/jcode/codingagent/CodingAgentSession.java)：startRun、finishRun、finishRunFinal、emitProductEvent、branch/resetLeaf。
- [Agent.java](https://github.com/YPQuinn/Jcode/blob/6db4fd35a3a6cf5b7780fde036f4e80ef82a933c/agent-core/src/main/java/site/pplee/jcode/agentcore/Agent.java) 与 [AgentLoop.java](https://github.com/YPQuinn/Jcode/blob/6db4fd35a3a6cf5b7780fde036f4e80ef82a933c/agent-core/src/main/java/site/pplee/jcode/agentcore/AgentLoop.java)：长期队列、底层运行、领取与上下文应用边界。
- [PendingMessageQueue.java](https://github.com/YPQuinn/Jcode/blob/6db4fd35a3a6cf5b7780fde036f4e80ef82a933c/agent-core/src/main/java/site/pplee/jcode/agentcore/queue/PendingMessageQueue.java) 与 [CodingAgentSessionTest.java](https://github.com/YPQuinn/Jcode/blob/6db4fd35a3a6cf5b7780fde036f4e80ef82a933c/coding-agent/src/test/java/site/pplee/jcode/codingagent/CodingAgentSessionTest.java)：旧输入队列及现有兼容测试。
- [Java/API 约定](https://github.com/YPQuinn/Jcode/blob/6db4fd35a3a6cf5b7780fde036f4e80ef82a933c/docs/agents/java-and-api-conventions.md) 与 [开发工作流](https://github.com/YPQuinn/Jcode/blob/6db4fd35a3a6cf5b7780fde036f4e80ef82a933c/docs/agents/development-workflow.md)：不可变公共类型、显式装配、计划目录与提交要求。
