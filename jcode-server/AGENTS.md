# jcode-server 指南

本模块是独立运行的本机单用户 HTTP 宿主，提供配置、实例所有权、认证、业务路由和 SSE 适配。测试源码包含三进程接管及崩溃重启演练；浏览器示例仅作为测试资源，不进入生产 JAR。

- 监听地址固定为 `127.0.0.1`。所有公开路由从第一版起要求服务 Bearer Token；OPTIONS 预检只验证精确 Origin、方法与请求头，SSE 路由允许 `Last-Event-ID` 重连头。不得把服务 Token 或模型凭证写入 `runtime.json`、URL、日志或错误响应。
- `server.lock` 原生文件锁覆盖整个实例生命周期。同 JVM 对规范化数据目录先占位，再打开锁通道；失败启动和关闭时释放目录占位、端口、executor 与锁。锁文件可保留；`runtime.json` 只在 HTTP 就绪后发布，关闭时仅删除本实例的描述。
- `ServerConfig` 只接受明确配置的工作区，不通过网络增加任意路径。工作区目录取真实路径；数据目录下的服务 Token 在 POSIX 平台限制为仅所有者读写。
- Run 占用按真实工作目录合并别名；会话响应中的工作区归属按历史文件所在的 `sessions/<workspaceId>` 目录确定，保证返回的 `workspaceId + fileRef` 可用于重开。
- HTTP 使用 JDK 21 `HttpServer` 和显式虚拟线程 executor。网络断开不取消 Run 或关闭 Session。停止路径先拒绝新工作；空闲停机一经接纳，即使 202 响应写入失败，也从另一执行路径清理资源。
- HTTP 命令直接调用 `ManagedSession`，请求正文实际读取上限为 1 MiB；成功响应使用协议对象，不另造执行队列。SSE 在订阅成功后才发 200，事件只使用 A3 的游标和队列；慢订阅者发无 id 的重同步控制帧。
- 独立入口在 `HttpServer` 初始化前设置 JDK 请求/响应时限；`sun.net.httpserver.maxReqTime` 与 `maxRspTime` 的配置值单位为秒。库构造器不修改全 JVM 系统属性。
- 设置驱动 Session 经 `CodingAgentSessionFactory` 的配置装饰接缝进入 `SessionRegistry`，始终选 `RUN_SCOPED`；保留工厂的模型选择、工具配置和 borrowed/owned 资源所有权。
- 本模块不得直接依赖 `agent-core` 或 `ai-providers`，也不得复制模型循环、正式历史存储或应用层输入/审批状态。
