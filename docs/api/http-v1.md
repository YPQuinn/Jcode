# Jcode 本机 HTTP API v1

服务只监听 `127.0.0.1`。所有业务请求和 SSE 都携带 `Authorization: Bearer <service-token>`；Token 保存在服务数据目录的 `service.token`，不会出现在 `runtime.json`。有 `Origin` 的请求只接受服务同源或配置中的精确来源。

## 路由

以下路径均以 `/v1` 开头。JSON 请求使用 `Content-Type: application/json`，正文上限为实际读取的 1 MiB；成功响应直接是协议对象，不套通用 `data` 外壳。

| 方法 | 路径 | 成功状态 |
|---|---|---|
| GET | `/capabilities`、`/workspaces`、`/sessions` | 200 |
| POST | `/sessions`、`/sessions/open` | 201、200 |
| GET | `/session-files?workspaceId=…` | 200 |
| GET | `/sessions/{sessionId}/snapshot`、`/history` | 200 |
| POST | `/sessions/{sessionId}/runs` | 202 |
| GET | `/sessions/{sessionId}/runs/{runId}` | 200 |
| POST | `/sessions/{sessionId}/runs/{runId}/inputs` | 202 |
| GET | `/sessions/{sessionId}/inputs/{inputId}` | 200 |
| POST | `/sessions/{sessionId}/runs/{runId}/cancel` | 200 |
| GET | `/sessions/{sessionId}/approvals/{approvalId}` | 200 |
| POST | `/sessions/{sessionId}/approvals/{approvalId}/resolve` | 200 |
| POST | `/sessions/{sessionId}/close` | 204 |
| GET | `/sessions/{sessionId}/events` | 200 SSE |
| POST | `/server/stop` | 202，仅服务空闲时 |

`POST /sessions` 接受 `{"workspaceId":"project"}`。`GET /session-files` 返回该工作区目录中的文件引用；`POST /sessions/open` 接受 `{"workspaceId":"project","fileRef":"…jsonl"}`。文件引用是该目录下的文件名，不能用绝对路径或 `..` 访问其他文件。关闭会话保留历史文件。

Run、输入和审批命令分别使用现有 `RunCommand`、`InputCommand`、`ApprovalCommand` JSON 字段。客户端在首次发送前生成 `commandId`、`runId`、`inputId`，重试时原样保留。输入请求体的 `targetRunId`、审批请求体的 `approvalId` 必须与路径一致。`202` 只表示命令已接纳，不预测最终 Run 状态；连接断开不会取消 Run。取消返回的 `cancelRequested` 与最终状态分别查询。

历史查询默认每页 50 条，`limit` 为 1–100。首请求可省略 `headEntryId`，响应会固定当前 head；后续页带回相同 head，并用上一页的 `nextBeforeEntryId` 作为 `beforeEntryId`。条目按新到旧返回，展示正文最多 8192 个 UTF-16 字符；`textTruncated` 标明截断或非文本内容的省略。该查询不会移动会话活动 leaf。

## SSE 重连

先取 `/sessions/{sessionId}/snapshot`，再以其 `cursor` 连接 `/sessions/{sessionId}/events?after=<epoch>:<seq>`。重连时非空 `Last-Event-ID` 优先于 `after`。服务先校验游标并完成订阅，再发送 SSE 响应头。

业务帧使用 `event: session.event`，`id` 为 `<epoch>:<seq>`，`data` 为完整 `SessionEvent` JSON。客户端只在**完整解析并应用**业务帧后推进游标；重复事件按 `SessionReducer` 规则去重。15 秒空等发送无 id 的注释心跳。落后订阅者收到 `stream.control`（`SUBSCRIBER_SLOW`、`resync`）后重新取快照；会话关闭时先排空已接纳事件，再发送 `SESSION_CLOSED`、`stop` 控制帧。控制帧不占业务序号，连接异常中断时不能据此推断 Run 已结束。

本地服务进程的 SSE 响应可在 5 分钟时限到期后断开，客户端应按最后完整事件的游标重连。该时限不取消 Agent Run。`ApiError {code,message}` 用于 JSON 错误；请求诊断 ID 在 `X-Request-Id` 响应头中。
