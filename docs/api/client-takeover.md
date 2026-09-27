# 本机服务接管与浏览器示例

`jcode-server` 独立于客户端运行。客户端退出只关闭自己的 HTTP/SSE 连接；需要停止 Run 时，明确调用对应的取消接口。服务关闭或崩溃后，重新打开文件历史不会恢复内存中的 Run、输入回执或待审批项。

## 接管顺序

1. 读取服务数据目录的 `runtime.json`，用其中的 endpoint 访问已认证的 `/v1/capabilities`，核对 `instanceId` 和版本。Token 只从 `service.token` 读取，并放在 `Authorization: Bearer` 请求头。
2. 取得 `/v1/sessions/{sessionId}/snapshot`。从中恢复当前 Run、输入、工具和审批，并保存快照 cursor。
3. 用该 cursor 连接 `/v1/sessions/{sessionId}/events?after=<epoch>:<seq>`；重连时可发送 `Last-Event-ID`。只在完整解析并应用事件或新快照后推进 cursor。过期游标和慢订阅者都重新取快照。
4. 提交审批时携带快照中的 `approvalId`、`toolCallId`、`requestDigest` 和一次决定。命令响应丢失时，先查询原身份，或以原 `commandId`/`inputId` 重试；不要生成新身份重放有副作用的操作。

可运行的 Java HTTP 客户端示例是测试源码中的 [TakeoverClientFixture.java](../../jcode-server/src/test/java/site/pplee/jcode/server/TakeoverClientFixture.java)。`mvn -pl jcode-server -am verify` 会用两个独立客户端 JVM 和一个服务 JVM 演练退出后接管，并在服务强杀后核对历史恢复边界；离线审批的取消与超时也由真实 HTTP 路径验证。测试服务和假模型只在测试源码中，不进入生产 JAR。

## Fetch 流页面

轻量页面位于 [takeover.html](../../jcode-server/src/test/resources/browser/takeover.html)。它读取快照、用 Fetch 解析完整 SSE 帧，并可批准或拒绝一次待处理工具调用。页面不把 Token 放在 URL 或浏览器持久存储中。

从仓库根目录启动静态页面服务：

```bash
python3 -m http.server 8768 --bind 127.0.0.1 \
  --directory jcode-server/src/test/resources/browser
```

在 `server.json` 的 `allowedOrigins` 中加入 `http://127.0.0.1:8768`，重启 Jcode 服务后，在浏览器打开 `http://127.0.0.1:8768/takeover.html`。填写 Jcode endpoint 和 Session ID，并选择该**测试服务**数据目录的 `service.token` 文件。页面会从文件读取 Token 到内存中的密码输入框。使用该页面时，只从自己启动的本地静态服务加载它。

**手工冒烟记录（2026-09-27，macOS Chrome Guest）：**页面从独立 Jcode 测试服务取得快照，跨域请求带 `Authorization` 和 `Last-Event-ID` 成功订阅 SSE；服务在原客户端退出后产生的待审批工具显示在页面中。点击“允许一次”后，同一 Run 在页面上更新为 `COMPLETED`。这验证了实际 Chrome 的请求路径；自动化三进程测试另外验证输入 `entryId`、工具只执行一次，以及服务重启后只恢复文件历史。
