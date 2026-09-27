# jcode-server 指南

本模块是独立运行的本机单用户 HTTP 宿主。B1 只提供配置、实例所有权、认证、能力查询和空闲停止；Session 命令、历史与 SSE 路由在 B2 实现。

- 监听地址固定为 `127.0.0.1`。所有公开路由从第一版起要求服务 Bearer Token；OPTIONS 预检只验证精确 Origin、方法与请求头。不得把服务 Token 或模型凭证写入 `runtime.json`、URL、日志或错误响应。
- `server.lock` 原生文件锁覆盖整个实例生命周期。同 JVM 对规范化数据目录先占位，再打开锁通道；失败启动和关闭时释放目录占位、端口、executor 与锁。锁文件可保留；`runtime.json` 只在 HTTP 就绪后发布，关闭时仅删除本实例的描述。
- `ServerConfig` 只接受明确配置的工作区，不通过网络增加任意路径。工作区目录取真实路径；数据目录下的服务 Token 在 POSIX 平台限制为仅所有者读写。
- HTTP 使用 JDK 21 `HttpServer` 和显式虚拟线程 executor。网络断开不取消 Run 或关闭 Session。停止路径先拒绝新工作；空闲停机一经接纳，即使 202 响应写入失败，也从另一执行路径清理资源。
- 设置驱动 Session 经 `CodingAgentSessionFactory` 的配置装饰接缝进入 `SessionRegistry`，始终选 `RUN_SCOPED`；保留工厂的模型选择、工具配置和 borrowed/owned 资源所有权。
- 本模块不得直接依赖 `agent-core` 或 `ai-providers`，也不得复制模型循环、正式历史存储或应用层输入/审批状态。
