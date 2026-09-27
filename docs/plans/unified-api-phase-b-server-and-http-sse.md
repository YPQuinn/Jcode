# 统一 API 阶段 B：独立服务与 HTTP / SSE 接入开发计划

| 项目 | 内容 |
|---|---|
| 依据 | [总体架构 v0.1](unified-api-and-multi-end-architecture-design_v0.1.md)；[阶段 A 实施计划](unified-api-phase-a-runtime-and-application-layer.md) |
| 核对基线 | `YPQuinn/Jcode@b5459420b0135228373d46dd0b5f6e0896894a7c` |
| 日期 | 2026-09-27 |
| 状态 | B1、B2 已实现；B3 待实施 |
| 本阶段目标 | 将 A1–A3 已有能力暴露为独立服务，证明客户端 A 退出后，客户端 B 能接管同一会话和原 Run |
| 范围约束 | 一个新服务模块；复用现有协议、应用服务、配置与历史；不建设通用平台或复杂安全体系 |

> 本阶段不是重写 Agent，也不是开始完整 TUI / WebUI / Desktop。先把现有进程内闭环变成可测试的网络闭环。

## 1. 范围与首期取舍

### 1.1 必须交付

独立 Java 服务进程；固定版本的 HTTP JSON 接口；SSE 订阅与重连；运行、输入和审批查询；会话发现、打开和只读历史；最低限度的本地认证；单实例启动及受控停止；两个真实客户端进程的退出／接管测试。

执行继续属于 `ManagedSession`。关闭 HTTP 响应、SSE、客户端或浏览器页面，只释放该连接，不调用 Run 取消或 Session 关闭。

### 1.2 暂不交付

不做完整三端 UI、通用客户端 SDK、OpenAPI 代码生成、WebSocket / JSON-RPC 双协议、PTY、账号或多租户、RBAC / OAuth / JWT / mTLS、设备配对、分布式锁、消息中间件、数据库、跨进程命令去重、任务崩溃续跑、自研沙箱或权限规则语言。

也不在 B 做自动拉起守护进程、跨平台双重 fork、systemd / launchd / Windows 服务安装器、自动升级、容器打包及端口自动扫描。首先显式运行服务，客户端指定 endpoint，或读取服务实例描述。

**对总体路线的收敛：**B 完成“服务独立存在、客户端可接管”和“同数据目录不重复启动”；“TUI / Desktop 自动发现并拉起后台服务”的便利功能放到 C。关闭承载服务的终端、主机登出与退出 TUI 不是同一种操作，不作混同承诺。

### 1.3 技术基线

- 使用 **JDK 21 `HttpServer` + Jackson**，HTTP 请求显式配置虚拟线程 executor，不使用默认单执行线程承载长期 SSE。[J1]
- 只新增 **`jcode-server`**。可测试的服务对象与 `main` 分离；测试可以在同一 JVM 启动真实 HTTP 服务。
- 使用现有 `SessionEvent`、`EventCursor`、`SessionSnapshot`、`SessionReducer`、Run / Input / Approval DTO 和枚举，不再另造一套 `run.finished` 事件字典。[C3]
- 请求结果与运行结果分离：HTTP 返回已接纳对象或现有结果，不等待 `settled(runId)`。
- 只做文件模式 Session；保留 A 阶段内存去重及重放范围，不扩大持久化承诺。

## 2. 代码落点与确实需要补齐的接缝

### 2.1 模块和职责

```text
jcode-server
  ServerMain / ServerConfig     参数与本地配置、资源装配、进程入口
  JcodeServer                   HttpServer、路由、认证、服务启动与停止
  SseHandler                    SessionSubscription -> SSE 字节流
  少量 HTTP / JSON 辅助代码     请求解析、错误映射、游标编码

jcode-app
  SessionRegistry               继续持有会话与 writer，增加最小列表/宿主生命周期接缝
  ManagedSession                继续处理命令、幂等、结果、快照、订阅、审批

coding-agent
  复用 Session 工厂、设置、模型与正式历史；只补实际需要的装配/只读展示接缝

jcode-protocol
  复用现有类型；仅增加能力信息、会话概要、历史页及确有需要的错误码
```

类名是职责建议，不要求一个名词对应一个独立类。路由用显式方法与路径匹配即可，不做注解扫描、通用命令总线、处理器注册平台或反射式依赖注入。

`jcode-server` 可依赖 app、protocol、coding-agent 及必要的 provider-neutral `ai` 类型；不直接依赖 `agent-core`，不解析 Provider wire 数据。网络类型只留在 server。依赖及构建插件版本沿用根 POM 管理。

### 2.2 A3 不是已经具备所有 HTTP 所需入口

当前 `SessionRegistry` 主要提供基于 `CodingAgentConfig` 的 create/open/find/close；并没有完整的服务目录、批量停机或设置驱动工厂入口。不能把不存在的方法写成已经可调用。[C1]

B 只补以下接缝：

| 接缝 | 最小处理 |
|---|---|
| 模型与配置装配 | 复用 `CodingAgentSessionFactory` / `CodingAgentSessionOptions`；Registry 增加设置驱动创建/打开路径，原 Config 入口和测试保留 |
| 事件与审批包装 | 工厂得到最终 `CodingAgentConfig` 后、创建 Session 前，经过一个显式装饰接缝组合现有 instrument；不复制整个工厂，不先裸建 Session 再补观察者 |
| 托管列表 | 从 Registry 的现有索引取得不可变概要，不另建一份会话事实表 |
| 历史文件发现 | 复用 `SessionFiles.list`，只扫描配置的 Session 目录；不自动打开全部文件持有 writer |
| 历史展示 | 从内核的一次不可变历史快照生成只读展示页，提供必要的产品层映射，不向 HTTP 序列化 `AgentMessage` 或 Provider replay state |
| 停机与同工作区接纳 | 在现有应用接纳/结算处增加短临界区和简单占用记录，不实现任务调度器 |

工厂接缝只用于组合事件和工具 policy，不改变既有模型选择、设置优先级、项目授信及 owned/borrowed 资源所有权。统一服务始终显式使用 `RUN_SCOPED`。[C1][C4]

## 3. B1：可独立启动的服务宿主

### 3.1 启动和配置

新增可运行的服务产物及 `ServerMain`。示例为**目标命令**，不是已有功能：

```bash
java --add-modules jdk.httpserver \
  -jar jcode-server/target/jcode-server.jar \
  --config /absolute/path/server.json
```

提供一个可运行 JAR 即可，不做 native-image、安装器或新的构建系统。`ServerMain` 负责阻止主进程提前退出，不依赖某个客户端 stdin 保活。

建议的最小宿主配置：

```json
{
  "port": 8787,
  "dataDirectory": "/home/me/.local/share/jcode-server",
  "userConfigDirectory": "/home/me/.jcode",
  "workspaces": {"jcode": "/home/me/src/Jcode"},
  "allowedOrigins": [],
  "approvalTools": [],
  "approvalTimeoutSeconds": 300
}
```

- B 固定监听 `127.0.0.1`；端口可改，测试支持 `0` 并读取实际端口。远程客户端先经用户自行建立的隧道接入，不在本阶段开放公网部署。
- 工作区是本地配置的名称到目录的映射；客户端创建/打开时只选择 `workspaceId`。可以配置多个目录，不提供远程新增任意路径接口。
- Session 文件统一放在 `dataDirectory/sessions/<workspaceId>/`。工作区标识在配置边界限定为简单目录键；Run / Input / Tool 等不透明业务 ID 不因此增加字符限制。
- 模型、凭证、默认设置复用已有用户配置目录和工厂，不在 `server.json` 复制 `models.json` / `auth.json` 的结构。工作区配置不自动授信项目设置或文本资源。
- 工具集合沿用内核设置和显式工具配置，缺省保持原有只读行为；`approvalTools` 仅决定哪些已经启用的工具需要一次性审批，不开启工具，也不覆盖原 policy 的拒绝。
- 网络 handler 不装载项目 JAR、执行插件脚本或解析模型密钥。测试使用可注入的受控 `ModelClient`，不要求真实云模型。

### 3.2 一个目录只运行一个服务

`dataDirectory` 是服务实例的所有权目录；用一个进程级文件锁覆盖整个服务生命周期。不能先检查文件存在、再无保护地创建实例。锁文件保留，退出释放锁即可，不靠删除锁文件判断运行状态。

成功绑定端口、安装路由并完成必要装配后，写入 `runtime.json`：`instanceId`、endpoint、PID、HTTP 主版本、事件 schemaVersion。文件不包含 Token 或模型凭证。就绪输出只打印 endpoint 和实例信息。

客户端先完成认证并读取 `/v1/capabilities`，核对真实实例与版本；PID 和描述文件不是存活证明。第二个服务使用同目录启动应明确失败，不杀旧进程、不静默换端口。启动失败必须释放已经取得的锁、HTTP 端口和资源。清理描述文件时确认它仍属于本实例。

### 3.3 多 Session 的工作区规则

首期对同一配置工作区实行**一次只运行一个 Run**，暂不区分只读/写入并行。不同工作区可以并行。忙碌时返回冲突，不自动排队或改成 follow-up。

用工作目录的真实路径合并本服务内的别名，占用绑定实际 Session/Run 身份；在应用层原子取得，在启动失败或最终结算时释放。释放应早于对应公开终态可见，迟到取消/完成不能释放后续 Run 的占用。不按 TCP 连接寿命持有或释放占用。

这只是本服务内的并发规则，不承诺保护外部编辑器、另一个显式数据目录的服务或遗留后台进程；不因此新增沙箱或跨进程文件锁。

### 3.4 停止规则

先支持一个显式、已认证的 **空闲停止** 请求。有活跃 Run、未结算输入接纳或会话创建/打开操作时返回 `409`，服务继续运行；需要停止任务的用户先调用已有取消接口，等结算后再停止服务。

停止检查与新命令接纳必须有共同边界：已决定停止之后，不再接纳新工作。登记短期占用后锁外执行，不持锁等待模型、文件、Future 或 socket。无需实现 drain/force/maintenance 多套停机模式。

空闲停止成功：先回传 `202`，再由独立执行路径关闭 Session、结束订阅、停止 HTTP、释放资源与实例锁。不要在处理停止请求的线程中等待它自己退出。单个 Session 关闭报错仍尝试清理其余资源，错误保留到服务日志。[J1]

直接向**服务进程**发送终止信号视为显式停止：shutdown hook 关闭接纳，对已有 Run 请求取消，并在一个总等待窗口内尽力清理。不能把多会话逐个等待叠加为无限停机。进程强杀/断电不承诺 hook 执行、任务续跑或终态必达。

**B1 退出条件：**可独立启动和停止；同目录并发启动只成功一个；模型/配置所有权正确；基本认证从首个公开路由开始存在；不依赖任何 UI。

## 4. B2：最小 HTTP JSON 契约

### 4.1 路由目录

路径统一以 `/v1` 为前缀。成功响应直接使用协议对象，不在外面再套一层通用 `success/data/result`。下表中的新增查询类型只服务这些真实路由。

| 方法与路径 | 行为与正常状态 |
|---|---|
| `GET /capabilities` | `200`：实例、协议/事件版本、支持操作、既有容量边界 |
| `GET /workspaces` | `200`：可选择的本地配置工作区概要 |
| `GET /sessions` | `200`：当前托管会话列表；不伪称包含全部磁盘历史 |
| `POST /sessions` | `201`：按 `workspaceId` 创建文件会话，不自动运行模型 |
| `GET /session-files?workspaceId=...` | `200`：可打开的磁盘会话概要、文件引用及已有读取诊断 |
| `POST /sessions/open` | `200`：按工作区与文件引用打开/复用 Session，不自动 continue |
| `GET /sessions/{s}/snapshot` | `200`：原 A3 快照和游标 |
| `GET /sessions/{s}/history` | `200`：固定历史头的只读展示页 |
| `POST /sessions/{s}/runs` | `202`：调用 start，返回 RunView；不等待最终结果 |
| `GET /sessions/{s}/runs/{r}` | `200`：当前/最终 RunView |
| `POST /sessions/{s}/runs/{r}/inputs` | `202`：内核实际接纳后返回 InputView |
| `GET /sessions/{s}/inputs/{i}` | `200`：查询权威输入状态的现有映射 |
| `POST /sessions/{s}/runs/{r}/cancel` | `200`：取消请求后的 RunView，不宣称必然 CANCELLED |
| `GET /sessions/{s}/approvals/{a}` | `200`：查询该次审批 |
| `POST /sessions/{s}/approvals/{a}/resolve` | `200`：提交原 ApprovalCommand，返回决定状态 |
| `POST /sessions/{s}/close` | `204`：空闲关闭；保留历史文件 |
| `GET /sessions/{s}/events` | `200 text/event-stream`：见 §5 |
| `POST /server/stop` | `202`：接纳空闲停止；忙碌则 `409` |

文件引用只标识所选工作区 Session 目录下的文件，不接受任意绝对路径。校验文件仍在该目录内即可，不扫描整个文件系统。模型切换、分支修改、压缩、资源重载和扩展命令管理 API 留到 C；这里不为已有全部 Java 方法逐个创建路由。

### 4.2 直接沿用已有命令身份

示例请求中的 `commandId` / `runId` / `inputId` 由客户端在提交前生成，并在重试时保留。B 不再同时维护一套 `Idempotency-Key` 映射或额外命令存储：

```http
POST /v1/sessions/s_123/runs
Authorization: Bearer <service-token>
Content-Type: application/json

{"commandId":"cmd_1","runId":"run_1","kind":"PROMPT",
 "text":"检查项目结构","expectedLeafId":null}
```

路由 ID 与请求体中的相应 ID 必须一致；ID 作为不透明值保留，按 URL 段编码/解码，不拼接成伪复合身份。枚举使用现有 JSON 形式，如 `PROMPT`、`FOLLOW_UP`、`RUN_CHANGED`。

同一命令的重试直接走 A2 的幂等路径，返回原身份及当前可查状态，不能先用目标 Run 已结束拒绝合法重试。`202` 表示命令接纳路径已完成，响应对象可能已经处于终态；真实 outcome 以对象内容为准。

HTTP 连接断开、客户端取消请求、响应写出失败，都不能反向调用 `abort()`。输入接口可等待内核接纳，但不能等待该输入被应用。响应丢失后查询/重试原身份，不生成新身份。

创建 Session 的接口首期不另加持久化幂等机制，也不允许客户端无条件自动重试；文件 open 继续按 Registry 身份复用。服务实例改变或去重记录过期时，只能报告结果待确认，不自动重提有副作用的工作。

### 4.3 只读历史不要变成第二套存储

`ManagedSession` 增加一个只读查询入口；从内核历史快照取一条固定父链，再生成有界展示页。请求以 snapshot 的 `leafId` 作为 `headEntryId`，用 `beforeEntryId` 向更早节点翻页；默认页长 50，最多 100。首请求省略 head 时由服务捕获一次并在响应中返回，后续页沿用该值。

只返回必要的 `entryId`、`parentId`、条目类型、角色、展示正文/摘要和截断标记；非消息条目保留类型与可解释的摘要，不假装是用户消息。完整富内容编辑/下载协议不在本批次铺开。

已有正式消息正文仍保存在内核历史；页面摘要被截断必须标注，不能声称它就是原始全部内容。JSONL 文件写入及解析继续由内核负责，server 不直接读写文件重建聊天。

不新增虚构的 `historyRevision` 字段或全量历史缓存。使用已存在的 append-only entryId 固定展示范围；空历史、未知 head、非祖先的 before 均明确处理。读取页面不改变活动 leaf，也不抢占当前 Run。

### 4.4 错误和请求解析

沿用 `ApiError {code,message}`，请求诊断 ID 放在 `X-Request-Id` 即可，不为本阶段引入分布式追踪体系。只在确有场景时增加少量错误码：

| HTTP 状态 | 处理 |
|---|---|
| `400` | 非法 JSON、字段/游标错误、路径与请求体身份不一致 |
| `401 / 403` | 服务凭证缺失/错误，或浏览器来源不允许 |
| `404 / 405` | 对象或路由不存在 / 方法不支持 |
| `409` | 原业务冲突、幂等冲突、输入关闭、审批关闭、工作区/服务忙碌、流需重新同步 |
| `413 / 415` | 请求体超过上限 / 非支持的 Content-Type |
| `429` | 既有应用容量上限；只做容量拒绝，不引入请求频率限流器 |
| `500 / 503` | 内部失败 / 服务正在停止 |

业务执行失败若已经形成 RunView，应查询到 `FAILED`，不能只丢给客户端一个 HTTP 500。禁止仅凭“任意 IllegalStateException”猜成 SESSION_BUSY；异常分类以真实接纳路径为准。

JSON 请求使用固定 1 MiB 实际读取上限，覆盖无 Content-Length 和 chunked 请求；要求单个 JSON 根值，拒绝尾随垃圾。异常栈和凭证不进入响应。HTTP 连接始终在合适的 finally 路径释放，不把外部 IOException 包装成内核失败。[J2]

## 5. B2：SSE 是 A3 订阅的薄适配

### 5.1 开流与帧格式

顺序固定为：认证/来源检查 → 找 Session → 解析 cursor → 调用 `subscribe()` → 成功后发送 SSE 响应头。订阅失败时返回正常 JSON 错误，不先写 `200`。

cursor 支持首次连接的 `?after=<epoch>:<seq>` 和重连的 `Last-Event-ID`。**非空 Last-Event-ID 优先**，避免自动重连仍携带旧 query；非法 header 不静默回退。二者都缺失时要求先取快照，不擅自从“现在”开始漏掉事件。

响应使用 UTF-8、`text/event-stream` 和 `Cache-Control: no-cache`。JDK 的 chunked 响应按 `sendResponseHeaders(200, 0)` 使用；每个完整事件写完后 flush，不等待积累大量文本。[J2][J3]

所有业务事件共用 SSE 名称 `session.event`，data 原样序列化现有 SessionEvent；下例 `data` 是一条完整业务事件，不是模型文本片段：

```text
id: e_7:128
event: session.event
data: {"schemaVersion":1,"sessionId":"s_123","runId":"run_1","cursor":{"epoch":"e_7","seq":128},"type":"RUN_CHANGED","data":{"sessionId":"s_123","commandId":"cmd_1","runId":"run_1","status":"RUNNING","cancelRequested":false,"stopReason":null,"text":null,"textTruncated":false,"errorMessage":null}}

```

cursor 只由 A3 分配；服务端写出字节不表示客户端已消费。后续 DTO 变更须同步更新示例与 JSON 往返测试，不能仅改网络层字段名。

### 5.2 等待、重连和关闭

SSE handler 在自己的请求任务中读取 `SessionSubscription.next()`，不占用内核事件回调。空等时每 15 秒写一条注释心跳；心跳不带 id、不占 seq、不进入重放缓冲。

不额外建立 SSE 业务队列，直接复用 A3 的有界队列。客户端以最后一个**完整解析并成功应用**的事件游标重连；UTF-8 分片或半个事件帧被截断时不提前推进游标。相同事件可重复，继续由 SessionReducer 去重。[J3]

| 情况 | SSE 适配行为 |
|---|---|
| 建流前游标过期/epoch 改变 | 返回 `409` 和原错误码，客户端重新取快照 |
| 建流后订阅落后 | 尽力发送不带 id 的 `stream.control`，code 为 SUBSCRIBER_SLOW、action 为 resync；然后关闭 |
| 服务端关闭 Session | 先读完 A3 已接纳事件，再发 `stream.control`，code 为 SESSION_CLOSED、action 为 stop；然后关闭 |
| socket 写失败/客户端主动断开 | 只释放该 subscription 和 exchange；没有条件保证最后控制帧一定送达 |
| EOF 且未收到明确关闭控制帧 | 视为连接中断，重新握手/接续；不能据此推断 Run 完成 |

控制帧不进入 SessionReducer，不制造 seq 或覆盖恢复位置。响应头发出后不再尝试改写 HTTP 状态或混入普通 JSON 错误响应。

### 5.3 阻塞写与服务关闭

有界订阅队列不等于阻塞 socket 写能自动退出。B 先使用 JDK 已有传输时限，不另做复杂 watchdog：启动前设置请求接收时限 15 秒、响应时限 5 分钟，采用 JDK 21 的 `sun.net.httpserver.maxReqTime` / `maxRspTime`（配置值单位为秒，JDK 内部换算成毫秒）。这是宿主级设置，只由独立入口配置，库构造器不暗改全 JVM 属性。[J4]

**响应时限覆盖整条 SSE，不是写空闲超时。**健康连接也可能到期关闭，客户端必须按游标重连；它不限制 Agent Run 总时长，不触发取消。定时粒度依赖 JDK 实现，测试不能要求毫秒级准点。

真实 socket 测试必须证明：不读响应的客户端最终能够释放连接/订阅，其他客户端仍可查询和取消。服务停机也必须有界结束残留 exchange，不能无限等待 SSE handler。若这项测试未通过，就先修传输退出路径，不以“已有心跳”宣称问题解决。

## 6. 最低安全边界：只做单用户本地服务所需的部分

| 保留的措施 | 实施方式 |
|---|---|
| 本地监听 | B 仅 loopback，不提供匿名公网模式 |
| 一个服务凭证 | 首次启动生成随机 Token，保存在服务私有目录；后续复用，HTTP/SSE 统一使用 Authorization: Bearer |
| 浏览器来源限制 | 有 Origin 时，只允许本服务同源或配置中的精确 origin；不使用 `*`，不默许 `Origin: null` |
| 凭证隔离 | 模型密钥仍由服务内工厂解析，不下发；Token 不放 URL、runtime.json 或日志，不接受 query token |
| 资源与输入基本校验 | 固定请求体上限、已有应用容量与传输时限；不建风控、评分或细粒度限流体系 |

Token 文件在支持 POSIX 权限的平台设为仅所有者可读写；其他平台使用用户私有目录，不自研跨平台 ACL 管理器。凭证读写失败明确报错。就绪信息只告诉用户凭证文件位置，不回显 Token。

正常业务路由包括 capabilities、列表、只读历史和 SSE 都要求服务凭证。OPTIONS 预检只验证来源、方法和请求头，不要求预检携带 Bearer，也不返回业务数据。非浏览器客户端没有 Origin 仍可在认证后使用。

不使用 Cookie 登录，因此不额外实现会话 Cookie / CSRF token 体系。浏览器示例使用带 Authorization 的 Fetch 流读取；原生 EventSource 不能直接配置该请求头，不为迁就它把 Token 搬进 URL。[J3]

不解析 shell 字符串自动判风险，不增加逐文件审批，不改现有默认工具权限，不把文件目录校验宣传为沙箱。上述措施与“不做复杂安全机制”并不矛盾：服务能执行本地工具，不能缺少最基本的访问凭证。

## 7. B3：真实进程验证与少量使用示例

### 7.1 三进程演练

测试控制进程独立拉起**服务进程 S、客户端进程 A、客户端进程 B**；A/B 不能与服务共享 Java 对象，服务也不能由 A 的 try-with-resources 生命周期托管。

```text
S 启动，绑定随机端口并就绪
  → A 通过 HTTP 创建 Session，提交 Run 并订阅
  → 控制进程确认接纳，强制终止 A，确认其 PID 已退出
  → S 保持原 instanceId / sessionId / runId，继续执行
  → 无客户端期间产生审批
  → B 认证，取快照，恢复审批和待处理输入
  → B 提交一次决定及补充输入
  → 原 Run 完成，B 查询结果与历史
```

测试服务用受控 ModelClient 和真实 Session/Loop/JSONL，计数工具只产生临时测试文件。采用测试专用入口或注入，不给生产服务增加 `--fake-model`、测试控制路由或后门。

用管道、测试文件关卡或明确的就绪消息控制时序，不依赖随意 sleep。记录真实工具执行次数、输入 entryId 和服务 PID；不仅断言“收到了某段 done 文本”。所有子进程在 finally 中有超时清理，避免失败测试残留服务。

### 7.2 最小验收矩阵

| 编号 | 必须验证的场景 | 通过条件 |
|---|---|---|
| B01 | 打包启动、配置装配 | 可运行入口使用真实服务对象；生产路径不依赖假模型或 UI |
| B02 | 同 dataDirectory 双进程启动、启动中途失败 | 至多一个合法实例；无残留端口/锁，既有实例不被误删 |
| B03 | HTTP 正常路径与错误 | 状态码及 DTO 往返正确；路由/请求体 ID 一致；非法/超大 JSON 不被接纳 |
| B04 | Run / Input 响应丢失后重试 | 同有效保留范围内身份一致、只接纳一次；已结束 Run 的原命令仍可查询 |
| B05 | SSE 实时、重放、中文分片与半帧断开 | 完整事件后才推进 cursor；归约与快照一致，不静默丢事件 |
| B06 | 游标过期、落后消费者、阻塞写 | 显式重同步或连接中断；不拖住 Agent，不占住全部查询能力 |
| B07 | 会话关闭与未读终态 | 可用连接先读完终态再得到 SESSION_CLOSED；客户端退订只影响自己 |
| B08 | A 被强制退出，B 接管原任务 | 同一个服务/会话/Run；无需重放任务，无重复工具副作用 |
| B09 | 离线时产生审批、随后决议/取消/超时 | 审批可从快照发现，只结算一次；定时任务按 A3 规则释放 |
| B10 | 同工作区 Run 竞争、终态后立即再提交 | 忙碌明确拒绝；旧占用正确释放，迟到完成不误释放新 Run |
| B11 | 空闲停机与新任务竞争 | 停机前已接纳工作不被遗漏；停机成功后无新接纳，无锁内长等待 |
| B12 | 服务强杀后重启并打开历史 | 新 instanceId/epoch；仅恢复已保存历史，不自动恢复 Run/审批/回执或重执行工具 |
| B13 | 正确/错误 Token、浏览器 Origin/预检 | HTTP 和 SSE 使用同一最低认证规则；允许来源可用，拒绝来源不得执行写操作 |

B04 可在客户端测试中读到响应后丢弃结果再以原命令重试，辅以断开真实请求连接；不能通过向服务增加“故意丢响应”生产接口验证。

B06 用缩短的传输时限启动测试子进程，不让常规测试等待五分钟。B12 不把未知结果直接显示为 FAILED，更不拿历史文本推断某条命令一定没有执行。

### 7.3 示例与构建验证

只提供两种小型示例：Java HTTP 测试客户端，以及一个测试资源中的 Fetch 流页面/脚本。浏览器示例仅完成连接、快照、订阅和一次审批，不做前端工程。HTTP 测试伪造 Origin 不能替代真实浏览器验证；至少记录一个实际浏览器的手工冒烟结果。

```bash
# 新模块建立后执行；命令不是本计划编制时的测试结果
mvn -pl jcode-server -am test
mvn -pl jcode-server -am verify
mvn verify
```

跨进程测试使用 Maven 集成测试阶段，例如 `*IT` 与 Failsafe，确保 `verify` 实际运行它们，不能只把测试源码提交后算完成。所有测试默认使用本地假模型，无需下载大模型或外部服务凭证。

## 8. 分批提交与完成标准

| 批次 | 交付范围 | 退出条件 |
|---|---|---|
| **B1：服务宿主** | jcode-server、配置装配接缝、文件锁、实例信息、基本凭证、独立启动和停止骨架 | B01/B02；启动/失败清理及资源所有权测试通过，最初路由即受认证保护 |
| **B2：网络闭环** | JSON 路由、必要列表与历史、同工作区接纳、SSE、错误与关闭映射 | B03–B07、B10/B11/B13 的自动测试通过；保留 A1–A3 回归 |
| **B3：进程验收与文档** | 独立客户端进程、断线接管、重启历史边界、浏览器冒烟及使用说明 | B08/B09/B12 及完整验收矩阵通过；全仓 verify 通过 |

每批可以拆成多个小提交，但合入版本必须可构建、可测试；不一次铺满空 Controller 和 DTO。开始前读取根及相关模块 AGENTS.md，按仓库工作流定向检查 pi/pi-book 参考，只用于理解意图，不因参考项目功能多而扩大范围。

新增 jcode-server/AGENTS.md，更新 README、根模块列表、架构边界及阶段计划状态；移除“全仓没有 main 入口”这一届时不再准确的说明，保留内核模块仍是可嵌入库的约束。网络协议可以先用一份简短 API 文档和契约测试维护，不以完整 OpenAPI 为 B1 前置。

- [x] 编制阶段 B 开发计划草案。
- [x] B1：服务宿主及最小装配接缝。
- [x] B2：HTTP / SSE 及只读查询闭环。
- [ ] B3：跨进程接管、重启边界、浏览器冒烟和完整校验。
- [ ] 校验通过后更新阶段 A / 总体路线中的阶段状态；不在实现之前标为已完成。

**阶段 B 完成的判据：**服务 S 独立运行，A 退出后 B 能连接同一 Session，继续观察、审批和补充输入；HTTP/SSE 失败不会重复执行或误取消 Run；服务重启仅恢复实际持久化历史，明确不承诺任务自动续跑。

**B1 实施记录：**新增 `jcode-server` 可运行 JAR，固定监听 loopback，并从首个能力查询路由起使用服务 Token 和 Origin 检查；`server.lock` 与 `runtime.json` 分别承担实例排他和非凭证发现。`CodingAgentSessionFactory` 在配置生成后提供装饰接缝，`SessionRegistry` 复用该接缝创建/打开严格模式会话，并让新 Run 接纳与空闲停机共享短临界区。B1 仅公开能力查询和空闲停止；Session 命令、历史与 SSE 路由仍属于 B2。模块级 `mvn -pl jcode-server -am verify` 和全仓 `mvn verify` 均已通过；测试包含打包 JAR 的并发双进程启动、启动失败清理、进程终止信号、认证与空闲停止。

**B1 生命周期修订：**停机一经接纳，即使响应写入失败也启动清理；同 JVM 重复启动在打开第二个锁通道前被拒绝，外部进程仍受原文件锁保护；创建/打开在最终登记时复查停机状态，迟到构造的 writer 在锁外关闭。对应断线、跨进程锁竞争和迟到登记回归已加入，全仓 `mvn verify` 通过。

**B2 实施记录：**补齐 HTTP JSON 路由、会话文件发现、固定历史头的有界展示页，以及使用 A3 游标和订阅队列的 SSE。应用层按工作目录真实路径限制同一工作区同时运行的 Run；网络请求断开只释放连接。测试覆盖命令重试、半帧断线后的事件重放、游标错误与落后订阅、关闭前未读终态、工作区别名竞争、停机接纳竞争和真实 socket 阻塞写。接口字段与重连规则见 [HTTP API v1](../api/http-v1.md)；独立客户端接管和服务重启边界留给 B3。

## 9. 核对依据与维护边界

本计划依据固定提交的静态代码与现有 A 阶段计划编制，不表示本次已执行 Maven 或完成阶段 B 实现。命令、路由、默认参数和新增方法均为本阶段拟采用契约。A3 修复已经纳入本次基线，B 不重复改写其正确性逻辑。

### 仓库依据

[C1] [SessionRegistry.java](https://github.com/YPQuinn/Jcode/blob/b5459420b0135228373d46dd0b5f6e0896894a7c/jcode-app/src/main/java/site/pplee/jcode/app/SessionRegistry.java)：当前 create/open/find/close、Config 入口、文件所有权和身份清理。

[C2] [jcode-app/AGENTS.md](https://github.com/YPQuinn/Jcode/blob/b5459420b0135228373d46dd0b5f6e0896894a7c/jcode-app/AGENTS.md)：应用接纳、固定容量、A3 订阅关闭及审批定时器约定。

[C3] [SessionEvent.java](https://github.com/YPQuinn/Jcode/blob/b5459420b0135228373d46dd0b5f6e0896894a7c/jcode-protocol/src/main/java/site/pplee/jcode/protocol/SessionEvent.java)：现有事件 JSON 信封与 cursor；[ApiError.java](https://github.com/YPQuinn/Jcode/blob/b5459420b0135228373d46dd0b5f6e0896894a7c/jcode-protocol/src/main/java/site/pplee/jcode/protocol/ApiError.java)：当前错误对象。

[C4] [CodingAgentSessionFactory.java](https://github.com/YPQuinn/Jcode/blob/b5459420b0135228373d46dd0b5f6e0896894a7c/coding-agent/src/main/java/site/pplee/jcode/codingagent/CodingAgentSessionFactory.java)；[CodingAgentSessionOptions.java](https://github.com/YPQuinn/Jcode/blob/b5459420b0135228373d46dd0b5f6e0896894a7c/coding-agent/src/main/java/site/pplee/jcode/codingagent/CodingAgentSessionOptions.java)：设置/模型装配、严格模式、借用与自有资源。

[C5] [SessionFiles.java](https://github.com/YPQuinn/Jcode/blob/b5459420b0135228373d46dd0b5f6e0896894a7c/coding-agent/src/main/java/site/pplee/jcode/codingagent/SessionFiles.java)：显式目录的只读历史文件发现。

### 协议与 JDK 依据

[J1] [JDK 21 HttpServer](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpServer.html)：executor、路径匹配、启动与 stop。

[J2] [JDK 21 HttpExchange](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpExchange.html)：响应头、chunked 响应及 exchange 释放。

[J3] [WHATWG Server-sent events](https://html.spec.whatwg.org/multipage/server-sent-events.html)：UTF-8、事件帧、Last-Event-ID 和 EventSource 接口。

[J4] [JDK 21 jdk.httpserver 模块](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.httpserver/module-summary.html)：请求/响应时限的单位与实现粒度；响应时限不是 Run 时限或写空闲时限。
