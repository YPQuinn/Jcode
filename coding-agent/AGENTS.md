# coding-agent 模块知识库

Headless 编码产品内核。组合 `ai` 与 `agent-core`，提供 `CodingAgentSession`、coding prompt、具体编码工具以及产品级 state/result/event。当前阶段不包含 Session 文件、配置发现、Compaction、Extension 或 UI。

## WHERE TO LOOK

| 任务 | 位置 |
|------|------|
| 产品调用入口 | `CodingAgentSession.java` |
| 显式运行配置 | `CodingAgentConfig.java` |
| 产品快照 | `CodingAgentState.java` / `CodingAgentRunResult.java` / `event/` |
| System Prompt | `prompt/SystemPromptBuilder.java` |
| 文件读取工具 | `tool/ReadTool.java` |
| 完整行截断 | `tool/OutputTruncator.java` |
| 递归快照 | `internal/SnapshotMapper.java` |

## CONVENTIONS

- `CodingAgentSession` 是唯一产品运行入口；不得公开内部 `Agent`。
- Provider、model client、工作目录、`ObjectMapper` 和 hook 均显式注入；本模块不读取 API Key 或环境变量。
- `pom.xml` 的 module-local Enforcer 仅允许 Jcode 内部依赖 `ai` 与 `agent-core`，并显式禁止 `ai-providers`、`server` 和 `tui`。
- Public config 不持有可变 collection/tree；state/result/event 对可变 `JsonNode` 在构造与访问时都做递归快照。
- `AgentCompleted` 必须投影为产品 `RunCompleted`，不能通过 public event 暴露 `LoopResult` 中的工具实例。
- 内置工具使用强类型参数和 JSON Schema；运行时边界不能只依赖 core 的最小 schema validator。
- 文件路径以 Session working directory 解析，但 working directory 不是 sandbox。工具路径参数不得在交给文件系统前通过 lexical normalize 折叠 `..`；必须保留符号链接及中间路径不存在/非目录时的平台解析语义。
- 文件读取必须有界；禁止对不受信任文件使用无界 `readLine()`、`readString()` 或 `readAllBytes()`。`OutputTruncator` 完整跳过 offset 前置行但不缓冲内容，目标页按剩余 UTF-8 字节预算读取，确定超限即停止，不扫描超长行余下部分；仍保留完整行、CR/LF/CRLF 归一化及取消检查。
- 事件 sink 保留 core backpressure；失败属于基础设施失败，不归一为 tool/model error。流式 sink 失败或返回 null stage 时，产品 stage 异常完成、状态清理、不发送成功完成事件，Session 可接受后续 prompt；底层负责取消在途 provider。
- Session 的 admission lock 只线性化产品 API 接纳；steering/follow-up 接纳不等于当前 run 已消费，未 drain 消息保留到下一 run。
- Javadoc 使用英文；生产代码、POM 和配置中不得提及参考项目名称。

## ANTI-PATTERNS

- 不依赖 `ai-providers`（首阶段）、`server` 或 `tui`。
- 不复制模型流协议、Agent Loop、工具三阶段管道或 provider registry。
- 不使用 `ServiceLoader`、classpath 扫描、静态可变 registry 或 DI 容器。
- 不用 `Map<String,Object>` 表示配置、工具参数或结果。
- 不把 partial assistant 写入产品结果或未来 Session 历史。
- 不声称 working directory、normalize 或字符串前缀检查构成安全 sandbox。
- 不为测试增加伪 `main` 或 Spring Boot 入口。

## COMMANDS

```bash
mvn -pl coding-agent -am test
mvn -pl coding-agent dependency:tree
mvn verify
```
