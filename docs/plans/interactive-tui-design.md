# Interactive TUI 设计方案

> 状态：设计讨论中（尚未实施）；已确认决策见 §11.1，待确认项见 §11.2
>
> Jcode 基线：`59a73fb`（2026-09-27，统一 API 阶段 A、B 已完成）
>
> 上位设计：[`unified-api-and-multi-end-architecture-design_v0.1.md`](unified-api-and-multi-end-architecture-design_v0.1.md)。本文是其阶段 C 中 TUI 与 Java 客户端 SDK 部分的详细方案
>
> 相关契约：[HTTP API v1](../api/http-v1.md)、[本机服务接管](../api/client-takeover.md)
>
> pi 源码基线：`6f7551516b84278eb9da1c340c8e7bc66be1a6ba`（`@earendil-works/pi-coding-agent` 0.87.1，2026-09-27）
>
> pi-book 基线：`0a8863b611504a47302931ad9adf1cab71a0b79f`（2026-08-02）
>
> 主要参考：pi `packages/tui`、`packages/coding-agent/src/modes/interactive`、`packages/coding-agent/docs/{usage,cli,keybindings,slash-commands,tui,settings,themes}.md`、根目录 `tui-plan.md`；pi-book 第 24、25 章

## 1. 决策摘要

1. **TUI 是 `jcode-server` 的客户端。** TUI 与 `-p` 模式都经 HTTP 命令和 SSE 事件访问本机服务，不在进程内持有 `CodingAgentSession`，也不直接依赖 `jcode-app`。会话和运行属于服务进程：退出 TUI 只断开连接，任务继续运行，WebUI、Desktop 或另一个 TUI 可以接管同一会话。
2. **新增三个模块：**
   - `jcode-tui`：终端 UI 基础库，零 Jcode 内部依赖，对应 pi-tui；
   - `jcode-client`：Java 客户端 SDK，只依赖 `jcode-protocol` 与 JDK `HttpClient`，负责服务发现、鉴权、命令身份与重试、SSE 游标、重连/重同步和本地视图归约；
   - `jcode-cli`：最终用户入口，持有 `main`、参数解析、交互模式与 print 模式，依赖 `jcode-client` 与 `jcode-tui`。
3. **服务发现与自动拉起**由客户端完成：读取 `runtime.json` 并校验实例；没有可用服务时，在用户级锁下拉起后台 `jcode-server` 并等待就绪。服务不随 TUI 退出。
4. **工作区本地可信注册**：服务新增一个需要服务 Token 的工作区注册接口。能读到 `service.token` 即视为本机可信；TUI 用 cwd 自动注册工作区，服务把它写回 `server.json`。
5. **渲染模型**沿用 pi-tui：组件返回“按宽度排好的一组终端行”，渲染器逐行差分，只重绘变化区间，用 CSI 2026 同步输出。首版只做 main-screen（保留终端 scrollback），alt-screen 全屏放到后续。
6. **终端底层只用 JLine 的 `jline-terminal`**（raw mode、尺寸、SIGWINCH、输入流），通过自有 `Terminal` 接口隔离；编辑器、差分渲染和组件自建。
7. **线程模型**：单 UI 线程独占全部界面状态；SSE 读取线程与 HTTP 回调只向 UI 线程投递，UI 线程永不阻塞等待网络。
8. 服务端缺少的能力（维护操作、查询、协议粒度、工作区注册、会话创建参数）作为**服务 API 补口**（S1～S5）在 `jcode-protocol`/`jcode-app`/`jcode-server` 中实现，遵循上位设计的接纳与维护模型，不为 TUI 开旁路。

```text
   jcode-cli ──────────────► jcode-tui        (zero Jcode deps; external: jline-terminal)
       │
       ▼
   jcode-client ──► jcode-protocol
       ┆ HTTP + SSE (loopback, Bearer token)
       ▼
   jcode-server ──► jcode-app ──► coding-agent ──► agent-core / ai-providers / ai
```

## 2. pi TUI 功能盘点与取舍

“阶段”列指 §8 的交付阶段；“不做”表示明确不纳入，而不是遗漏。

### 2.1 框架层（pi-tui → Jcode `jcode-tui`）

| 能力 | pi 现状 | Jcode 取舍 | 阶段 |
|---|---|---|---|
| `Component.render(width) → lines` + `invalidate()` | 核心接口 | 照搬语义，Java 化 | T1 |
| Container / Text / TruncatedText / Spacer / Box | 内建 | 保留 | T1 |
| 差分渲染（first/last changed、宽度变化全量重绘） | `TuiMainScreen` | 保留 | T1 |
| 渲染合并与 16ms 节流 | `requestRender` | 保留 | T1 |
| CSI 2026 同步输出 | 默认启用 | 保留；不支持的终端自动忽略 | T1 |
| 行宽校验 | 强制 | 测试中强制，生产中截断并记录诊断 | T1 |
| ANSI 感知宽度、截断、按列切片、带 SGR 状态的换行 | `utils.ts` | 自建；grapheme 基于 `BreakIterator`，东亚宽度基于 JLine `WCWidth` | T1 |
| stdin 切分、ESC 超时、bracketed paste | `stdin-buffer.ts` | 保留 | T1 |
| 按键解析（legacy xterm、Kitty keyboard protocol） | `keys.ts` | legacy 放 T1；Kitty 协议渐进增强放 T2 | T1/T2 |
| Focusable + `CURSOR_MARKER` 双层光标（IME） | 内建 | 保留；退出时先清软件光标、再恢复硬件光标 | T2 |
| Overlay（定位、焦点栈、隐藏） | 内建 | 保留锚点与百分比定位 | T2 |
| Input / Editor / SelectList / SettingsList / Loader | 内建 | 保留；Editor 按 §4.4 不变量实现 | T2 |
| Keybindings 管理、AutocompleteProvider | 内建 | 保留 | T2 |
| Markdown 渲染与代码高亮 | `markdown.ts` | 首版不做，助手文本按纯文本换行显示 | T6 |
| 颜色 truecolor / 256 / 16 降级、OSC 11 明暗探测 | 内建 | 保留；OKLCH/OKHSL 色彩运算与运行时订阅不做 | T4 |
| OSC 8 超链接、Kitty/iTerm2 图片 | 内建 | 后续 | T6 |
| Alt-screen：VStack/HStack/ScrollView、鼠标、选区、搜索 | `TuiAltScreen` | 后续整体交付 | T6 |
| LaTeX / Mermaid、原生剪贴板 addon | 内建 | 不做；复制用 OSC 52 | — |

### 2.2 交互产品层（pi interactive mode → Jcode `jcode-cli`）

| 能力 | pi 现状 | Jcode 取舍 | 阶段 |
|---|---|---|---|
| 用户消息、助手流式消息 | 有 | 纯文本（按宽度换行） | T3 |
| 工具执行（按工具定制渲染、Ctrl+O 折叠/展开） | 有 | T3 通用视图（名称、状态、输出尾部）；T4 在协议补口 S3 后做专用渲染与 edit diff | T3/T4 |
| Thinking 块显示/隐藏（Ctrl+T） | 有 | 需要 S3 | T4 |
| Compaction / branch summary / custom message 展示 | 有 | 需要 S3 | T4 |
| 启动 header：上下文、资源与诊断 | 有 | 需要 S2 | T4 |
| 工作中状态指示 | 有 | 保留 | T3 |
| Footer：cwd、会话名、模型、thinking、上下文占用、token/cost | 有 | T3 显示 cwd、会话名与运行状态；模型与用量需要 S2/S3 | T3/T4 |
| Enter 提交 / 运行中 Enter = steer、Alt+Enter = follow-up | 有 | 映射为补充输入命令（绑定目标 Run） | T3 |
| Esc 中止 | 有 | 显式取消当前 Run（按 runId） | T3 |
| 待处理输入显示、Alt+Up 取回、中止时回填编辑器 | 有 | 用 `InputView` 的 pending / not_applied 状态实现 | T5 |
| 工具审批对话框 | 需扩展 | **必需**：服务开启审批时必须能处理，否则 Run 一直等待 | T3 |
| 其他客户端发起的运行与审批 | — | 按 SSE 事件同样显示，可接管操作 | T3 |
| Ctrl+C 清空/两次退出、Ctrl+D 退出、Ctrl+Z 挂起 | 有 | 退出只断开连接，运行中退出提示“任务在后台继续” | T3 |
| Shift+Tab 思考级别、Ctrl+L 模型选择器 | 有 | 需要 S1/S2 | T5 |
| Ctrl+G 外部编辑器 | 有 | 保留 | T5 |
| `/` 命令补全、`@` 文件引用、Tab 路径补全 | 有 | 保留；模板/技能列表需要 S2，文件列表由客户端本地读取 cwd（本机场景） | T5 |
| 斜杠命令（清单见 §5.8） | 有 | 首版 15 个内建命令 | T5 |
| `/tree` `/fork` `/clone` `/import`、HTML 导出、`/login`、`/scoped-models` | 有 | 后续 | T6 |
| 扩展命令 | 有 | 后续（服务端尚无扩展装配方案） | T6 |
| `!cmd`、图片粘贴 | 有 | 后续 | T6 |
| 项目信任选择器 | 有 | 需要 S2 | T5 |
| 主题 dark/light/auto、`keybindings.json` | 有 | 保留（客户端本地配置） | T4/T5 |
| print 模式（`-p`） | 有 | 保留，同样经服务 | T3 |
| JSON 事件模式 / RPC 模式 | 有 | 不在本方案内（服务 HTTP API 已承担程序集成） | — |
| OAuth 登录、分享、遥测、更新检查、彩蛋 | 有 | 不做 | — |

## 3. 模块、依赖与构建

### 3.1 模块职责

| 模块 | 包 | 职责 | Jcode 内部依赖 |
|---|---|---|---|
| `jcode-tui` | `site.pplee.jcode.tui` | 终端抽象、输入解析、文本宽度、差分渲染、组件、overlay、keybindings、autocomplete 接口、样式 | 无 |
| `jcode-client` | `site.pplee.jcode.client` | 服务发现与拉起、鉴权、命令调用与身份保留、SSE 解析与游标、重连/重同步、基于 `SessionReducer` 的本地视图 | `jcode-protocol` |
| `jcode-cli` | `site.pplee.jcode.cli` | `main`、参数、客户端本地配置、交互模式、print 模式、主题与命令 | `jcode-client`、`jcode-protocol`、`jcode-tui` |

### 3.2 Enforcer 规则

- `jcode-tui`：禁止依赖任何 Jcode 模块。
- `jcode-client`：只允许依赖 `jcode-protocol`。
- `jcode-cli`：只允许依赖 `jcode-client`、`jcode-protocol`、`jcode-tui`；禁止依赖 `coding-agent`、`jcode-app`、`jcode-server`、`agent-core`、`ai`、`ai-providers`。这样客户端在编译期就无法绕过服务。
- 其余模块禁止依赖上述三个客户端模块。

### 3.3 外部依赖

| 依赖 | 用途 | 模块 |
|---|---|---|
| `org.jline:jline-terminal` + `jline-terminal-jni` | raw mode、尺寸、SIGWINCH、输入流、`WCWidth` | `jcode-tui` |
| Jackson（已在根 POM 固定） | 协议 JSON | `jcode-client` |

HTTP 与 SSE 使用 JDK `java.net.http.HttpClient`，不引入其他网络库。参数解析手写强类型 parser。

### 3.4 可执行产物

- `jcode-cli` 提供 `site.pplee.jcode.cli.Main`；`jcode-server` 仍是独立服务入口。
- 自动拉起需要客户端找到服务的启动方式。首版约定：同一 JVM 可执行文件（`ProcessHandle.current().info().command()`）加服务的 classpath；服务 jar 位置由发行目录布局决定，开发时可用环境变量 `JCODE_SERVER_JAR` 指定。
- 发行形态（两个 fat jar 并列 + 启动脚本，或单一发行目录）见 §11.2 待确认项。

## 4. `jcode-tui` 模块设计

### 4.1 包布局

```text
site.pplee.jcode.tui
├── terminal/   Terminal、JLineTerminal、TerminalSize、TerminalCapabilities
├── input/      InputBuffer（切分/ESC 超时/bracketed paste）、KeyParser、KeyEvent、KeyId
├── text/       TextWidth、AnsiText（wrap/truncate/sliceByColumn）、Style、Color、ColorMode、控制字符过滤
├── render/     Component、Focusable、Container、Tui（main-screen 渲染器）、RenderScheduler
├── overlay/    OverlayOptions、OverlayHandle、OverlayAnchor
├── keymap/     Keybindings、KeybindingAction、KeybindingsConfig
├── complete/   AutocompleteProvider、Suggestion、SlashCommandSpec、PathCompletionProvider
└── component/  Text、TruncatedText、Spacer、Box、Input、Editor、SelectList、SettingsList、Loader
```

测试支持：`VirtualTerminal`（见 §7.1）与输入脚本工具。

### 4.2 核心接口

```java
/** A renderable node; all methods are confined to the UI thread. */
public interface Component {
    /** Render lines whose visible width never exceeds {@code width}. */
    List<String> render(int width);

    /** Handle one key event while focused; return true when consumed. */
    default boolean handleInput(KeyEvent event) { return false; }

    /** Drop cached render output so the next render starts fresh. */
    void invalidate();
}

/** Component that renders a text cursor and wants hardware-cursor placement for IME. */
public interface Focusable {
    void setFocused(boolean focused);
}
```

- `KeyEvent` 是 sealed：`Key(KeyId id, String text)`、`Paste(String text)`、`Unknown(String raw)`；`Keybindings.matches(event, action)` 对应 pi 的 `matchesKey`。
- `CURSOR_MARKER` 使用私有 APC 序列 `ESC _ jcode:c BEL`；渲染器合成后定位硬件光标并移除标记。
- 每行末尾追加 SGR reset，样式不跨行。

### 4.3 渲染器（main-screen）

- **调度**：`requestRender()` 线程安全、可合并；UI 线程按 ≥16ms 间隔执行 `doRender()`。`requestRender(force)` 用于主题切换、resize 与挂起恢复。
- **差分**：比较新旧帧找出 `firstChanged`/`lastChanged`，只重写该区间；宽度变化整屏重绘；收缩时清理多余行；全部输出包在 `CSI ?2026h … ?2026l` 中一次写出。变化发生在已进入 scrollback 的行时，清屏重放整份文档。
- **Overlay**：先渲染主内容，再合成 overlay，最后差分；焦点栈记录 `preFocus`。
- **生命周期**：`start()` 进入 raw mode、开启 bracketed paste、隐藏光标。`stop()` 依次清除软件光标 → 光标移到内容末尾 → 关闭 bracketed paste/Kitty 协议 → 显示光标 → 恢复 cooked mode；shutdown hook 与 finally 保证异常退出也能恢复终端。
- **挂起**（Ctrl+Z，POSIX）：恢复终端后向自身发送 SIGTSTP；SIGCONT 后重新进入 raw mode 并强制重绘。

### 4.4 Editor

- 文本模型为逻辑行列表加光标；换行在 grapheme 边界进行并记录视觉块 `[start,end)`，用于光标映射；按词操作用 `BreakIterator.getWordInstance()`。
- Undo 快照同时保存文本、光标、paste registry 与计数器；删除 paste marker 时同步 registry 并重新编号。
- 大段粘贴（>10 行或超过字符阈值）折叠为 `[paste #n +k lines]` 原子段，提交时展开。
- Kill ring：Ctrl+K/U/W、Ctrl+Y、Alt+Y。
- 自动补全：输入触发时去抖、Tab 强制触发；请求带递增序号，过期结果丢弃；补全列表以 overlay 显示，可见 3～20 条。
- 超高时滚动并显示 `↑ n more`（按列宽安全截断）；首行按 Up 浏览 prompt 历史。

## 5. 客户端设计（`jcode-client` + `jcode-cli`）

### 5.1 配置目录与服务发现

默认目录 `~/.jcode`（可由 `$JCODE_HOME` 或 `--config-dir` 覆盖）：

```text
~/.jcode/
├── server.json          服务配置（客户端首次拉起时生成默认值）
├── models.json、auth.json、settings.json、trust.json …   现有用户配置（服务的 userConfigDirectory）
├── keybindings.json、tui.json                          客户端本地配置
└── server/                                             服务 dataDirectory
    ├── runtime.json、service.token、server.lock
    └── sessions/<workspaceId>/*.jsonl
```

连接流程（`jcode-client`）：

```text
读取 server/runtime.json
  → 有：用 service.token 调 /v1/capabilities，核对 instanceId 与协议版本
       → 成功：连接完成
       → 身份不符或版本不兼容：明确报错，不悄悄启动第二个实例
  → 无或陈旧：在 ~/.jcode 的用户级锁下再次检查
       → 仍无：确保 server.json 存在（缺失时生成默认配置）
              → 以后台进程启动 jcode-server --config ~/.jcode/server.json
              → 轮询 runtime.json + capabilities 直到就绪或超时
```

- 服务进程与客户端解耦：重定向 stdin/stdout，不继承终端，客户端退出不影响服务。
- 服务停止由显式命令完成（`jcode server` 子命令，属于 §11.2 待确认项）。

### 5.2 工作区解析

1. 取 cwd 的真实路径，调用 `GET /v1/workspaces` 查找目录相同的已配置工作区。
2. 没有则调用新增的注册接口（S4）：`POST /v1/workspaces {"directory": "<真实路径>"}`。服务在现有 Bearer Token 鉴权下接受该请求，生成 `workspaceId`（目录名 + 短哈希，满足现有 ID 规则），加入内存配置并原子写回 `server.json`。
3. 注册只影响“可选择的工作区”，**不自动授信**项目设置或文本资源；信任仍由现有 trust 机制与 `/trust` 决定。

### 5.3 线程模型

```text
 input thread ──KeyEvent──┐
 SSE thread ──SessionEvent─┼──► [ UI executor (single thread) ] ──render──► Terminal
 HttpClient callbacks ─────┘            │
                                         └── commands via HttpClient.sendAsync
```

- **UI 线程**独占所有组件与本地视图，其他线程只能 `ui.execute(Runnable)` 投递。
- **SSE 线程**阻塞读取事件流，完整解析一帧后投递给 UI 线程；UI 线程用 `SessionReducer` 应用后才推进游标。这满足协议要求的“完整解析并应用后推进游标”。
- **命令**全部经 `sendAsync` 发出，回调回到 UI 线程；UI 线程禁止 `join()` 任何网络结果，测试环境加线程断言。
- **慢消费**：服务端每个订阅有 128 条缓冲，客户端落后会收到 `stream.control` 重同步。UI 线程侧合并渲染，归约开销必须足够小；收到重同步时重新取快照并重建视图。
- **断线**：按最后成功应用的游标重连；游标过期或 epoch 变化时重新取快照。重连不会自动重提任何命令。

### 5.4 会话、运行与接管

| 用户动作 | 客户端行为 |
|---|---|
| 启动（无会话参数） | 在当前工作区 `POST /sessions` 新建会话 |
| `-c` | `GET /sessions` 中找本工作区已托管的会话（优先）；否则 `GET /session-files` 取最近一个并 `POST /sessions/open` |
| `--session <fileRef\|ID 前缀>` | 在本工作区按文件引用或 ID 前缀匹配；已托管就直接接管，否则打开 |
| 连接会话 | 取快照 → 历史分页补齐转录 → 用快照游标订阅 SSE；进行中的 Run、输入与审批从快照恢复 |
| 提交（空闲） | `POST /runs`（prompt），客户端生成 `commandId`/`runId`，重试时原样保留 |
| 提交（运行中） | `POST /runs/{runId}/inputs`，mode 为 steer（Enter）或 follow-up（Alt+Enter） |
| Esc | `POST /runs/{runId}/cancel`；界面显示“取消中”，终态以事件为准 |
| 审批事件 | 弹出审批对话框：允许本次 / 拒绝本次；提交时携带快照中的 `approvalId`、`toolCallId`、`requestDigest` |
| 退出（Ctrl+D、`/quit`、两次 Ctrl+C） | 断开 SSE；有运行中任务时提示“任务在后台继续，可用 `jcode -c` 重新连接” |
| 命令响应丢失 | 以原身份查询或重试，界面显示“结果待确认”，不生成新身份 |

### 5.5 视图映射

| 协议事件 / 视图 | UI 动作 |
|---|---|
| `RUN_CHANGED` / `RunView` | working 指示、取消中状态、终态（completed / failed / cancelled）与错误提示 |
| `MESSAGE_CHANGED` / `MessageView` | 按 `messageId` 创建或替换消息视图；`complete` 后定稿 |
| `TOOL_CHANGED` / `ToolView` | 按 `runId + toolCallId` 更新工具视图（状态、输出尾部、是否截断/错误） |
| `INPUT_CHANGED` / `InputView` | 待处理输入区；`not_applied` 可取回；`reconciliation_required` 显示“结果待确认” |
| `APPROVAL_CHANGED` / `ApprovalView` | 弹出、更新或关闭审批对话框（其他客户端先决定时同步关闭） |
| `HISTORY_CHANGED` | 重新读取历史分页，刷新已定稿的转录 |

转录由“历史分页（已接纳条目）+ 快照中的流式消息与工具”组合而成，二者通过 `entryId` 衔接。

### 5.6 启动参数（服务客户端模式）

用法：`jcode [选项] [--] [@文件...] [消息...]`

| 类别 | 参数 | 含义 | 依赖 | 任务 |
|---|---|---|---|---|
| 调用 | `[消息...]` | 以空格拼接成第一条 prompt；交互模式自动提交 | — | T3.3 |
| | `@路径` | 文本文件内容以 `<file path="…">…</file>` 包裹后放在第一条 prompt 前；严格 UTF-8、有大小上限，非文本报用法错误 | — | T3.3 |
| | `--` | 停止解析选项 | — | T3.3 |
| | `-p, --print` | 经服务运行一次，等待 Run 终态，把最终助手文本写到 stdout 后退出 | — | T3.3 |
| | 管道 stdin / 自动 print | stdin 非 TTY 时有界读取并拼到 prompt 前；stdin 或 stdout 非 TTY 时自动 print | — | T3.3 |
| 会话 | `-c, --continue`、`--session <fileRef\|ID 前缀>` | 见 §5.4 | — | T3.3 |
| | `-r, --resume` | 会话选择器 | — | T5.4 |
| | `-n, --name <名称>` | 设置会话名 | S1 | T5.4 |
| 模型 | `--model <provider/id[:thinking]>`、`--thinking <level>` | 连接会话后以维护操作设置 | S1 | T5.4 |
| | `--list-models [关键词]` | 列出服务端模型目录后退出 | S2 | T5.4 |
| 显示 | `--use-theme <dark\|light\|auto>`、`--verbose` | 本次运行的主题 / 完整启动信息 | — | T4.2 |
| 其他 | `--config-dir <dir>`、`-h`、`-v` | — | — | T3.3 |

print 模式退出码：Run completed 为 0；failed 或 cancelled 为 1；用法错误为 2；无法连接或拉起服务为 3。print 模式收到 SIGINT/SIGTERM 时对自己的 Run 发出取消请求，有界等待终态后以 130 退出。

以下原先确认的参数在服务模式下需要重新确认（§11.2）：`-t/-xt/-nt`、`--system-prompt`、`--append-system-prompt`、`--skill`、`--prompt-template`、`--no-default-resources`、`-nc`、`-a/-na`（都属于会话装配参数，需要 S5）；`--no-session`（服务不支持内存会话）；`--session-dir`（会话目录由服务拥有）。

### 5.7 输入语义

| 按键（默认） | 空闲 | 运行中 |
|---|---|---|
| Enter | 路由提交（见下） | steer 补充输入 |
| Alt+Enter | 同 Enter | follow-up 补充输入 |
| Esc | 关闭 overlay / 补全 | 取消当前 Run |
| Alt+Up | — | 取回 pending/not_applied 输入到编辑器 |
| Ctrl+C | 清空编辑器；500ms 内再按一次退出（断开） | 同左 |
| Ctrl+D | 编辑器为空时退出（断开） | 同左，并提示任务在后台继续 |
| Shift+Tab / Ctrl+L | 切换思考级别 / 模型选择器 | 维护操作与运行冲突，提示稍后 |
| Ctrl+O / Ctrl+T | 工具输出展开 / thinking 显示 | 同左 |
| Ctrl+G | 外部编辑器 | 同左 |

提交路由：内建命令 → 资源命令（模板 `/名称`、技能 `/skill:名称`，由服务端展开，S1）→ 普通 prompt。未知 `/xxx` 按普通文本发送。

### 5.8 斜杠命令清单（首版）

| 命令 | 作用 | 依赖 | 任务 |
|---|---|---|---|
| `/model [provider/id]` | 选择或切换模型（Ctrl+L 同） | S1、S2 | T5.4 |
| `/thinking [level]` | 设置思考级别（Shift+Tab 循环） | S1 | T5.4 |
| `/new` | 在当前工作区新建会话并切换过去 | — | T5.4 |
| `/resume` | 选择本工作区的会话恢复（`-r` 同） | — | T5.4 |
| `/compact [说明]` | 手动压缩上下文 | S1 | T5.4 |
| `/reload` | 服务端重载上下文与资源；客户端重载 `keybindings.json`、`tui.json` | S1 | T5.4 |
| `/settings` | 设置面板 | S2 | T5.7 |
| `/name [name]` | 设置或显示会话名 | S1 | T5.3 |
| `/session` | 会话文件、ID、消息数、上下文占用、token 与费用 | S2 | T5.3 |
| `/copy` | 复制最后一条助手回复（Ctrl+X 同，OSC 52） | — | T5.3 |
| `/export [path]` | 导出当前会话为 JSONL | S2 | T5.3 |
| `/hotkeys` | 列出当前生效的快捷键 | — | T5.3 |
| `/debug` | 把当前渲染行、本地视图与最近事件写入 `~/.jcode/debug.log`，提示可能含敏感信息 | — | T5.3 |
| `/trust` | 保存当前项目的信任决定 | S2 | T5.6 |
| `/quit` | 断开并退出 | — | T5.3 |

`/settings` 首版范围：Agent 设置（默认模型、默认思考级别、steering/follow-up 模式、自动压缩及阈值，经 S2 由服务端写入用户级 settings）与界面设置（写客户端本地 `tui.json`）。不编辑项目级设置、Provider、凭证与信任。

延后到 T6：`/tree`、`/fork`、`/clone`、`/import`、HTML 导出、`/login`、`/logout`、`/scoped-models`、扩展命令。不做：`/share`、`/bug`、`/changelog`、`/llama` 与彩蛋命令。

### 5.9 客户端本地配置

`~/.jcode/keybindings.json`（动作 id → 按键或列表）与 `~/.jcode/tui.json`（`theme`、`quietStartup`、`editorPaddingX`、`autocompleteMaxVisible`、`showHardwareCursor`、`hideThinking`、`toolsExpanded`、`externalEditor`）只由客户端读取，属于各客户端自己的偏好，不进入服务或协议。解析沿用仓库约定：完整单根 JSON，未知字段忽略，诊断不含字段值。

### 5.10 工具渲染与安全

- 折叠态一行：工具名、关键参数（S3 后）、状态与耗时；展开态显示有界输出尾部。
- 所有来自服务的文本（消息、工具输出、审批参数预览）渲染前过滤 C0/C1 控制字符与 ESC，防止篡改终端状态。
- `service.token` 只从文件读入内存，不写日志、不进入 `/debug` 输出。

## 6. 服务 API 补口

均按上位设计的接纳矩阵实现：维护操作与运行冲突时返回冲突而不排队；使用 `operationId` 和独立结果查询，不伪装成普通 Run；协议字段变更同步 JSON 往返测试。

| 编号 | 内容 | 首版使用方 | 阶段 |
|---|---|---|---|
| S1 维护操作 | 切换模型与思考级别、会话命名、手动压缩、重载资源与项目上下文；模板/技能展开后运行（prompt 附带“按资源展开”选项，或独立展开接口） | `/model` `/thinking` `/name` `/compact` `/reload`、`--model`、资源命令 | T5 |
| S2 查询与设置 | 模型目录；资源列表（模板、技能及描述）与诊断；上下文占用与累计 usage；会话信息与 JSONL 导出；用户级 settings 读写；项目信任查询与保存 | `/model` `/session` `/export` `/settings` `/trust`、补全、header | T5 |
| S3 协议粒度 | `MessageView` 增加结构化内容块（文本、thinking）、stop reason 与 usage；`ToolView` 增加有界参数摘要；compaction / branch summary / custom message 的展示条目 | 富渲染、footer | T4 |
| S4 工作区注册 | Token 鉴权的 `POST /v1/workspaces`，原子写回 `server.json`，不自动授信 | 任意目录启动 | T3 |
| S5 会话装配参数 | 创建会话时指定工具集、system prompt、资源路径、上下文发现与本次信任 | §5.6 中待确认的装配参数 | 待定 |

## 7. 测试策略

### 7.1 VirtualTerminal

`jcode-tui` 测试支持中实现最小 VT 模拟器（屏幕网格、scrollback、光标、SGR），只接受渲染器会产生的序列，遇到未知序列即失败。

### 7.2 分层测试

- `jcode-tui`：宽度/截断表驱动测试；字节序列 → `KeyEvent` 表；渲染矩阵；组件与 Editor 行为矩阵。
- `jcode-client`：用进程内 `JcodeServer` + 假模型做真实 HTTP/SSE 测试：命令重试保持身份、半帧断线后重连、游标过期重取快照、慢订阅重同步、服务不可用与身份不符。服务发现与拉起用临时配置目录和真实子进程测试。
- `jcode-cli`：VirtualTerminal + 脚本输入 + 真实服务进程的端到端测试：提交、流式、补充输入、取消、审批、退出后任务继续、重新连接接管。
- 服务补口 S1～S5 在各自模块内补契约测试，并保留阶段 A、B 回归。
- 默认测试不访问网络、不需要真实凭证、不依赖真实 TTY；真实终端兼容性用手工检查清单记录。

## 8. 分阶段交付与任务拆解

### 8.1 拆分原则

1. 一个任务对应一个可独立合并的 PR，`mvn verify` 通过，测试随代码交付。
2. 按依赖层切分；服务端补口与客户端任务分开，服务端改动单独评审。
3. 不预建空模块：`jcode-tui` 在 T1.1、`jcode-client` 在 T3.1、`jcode-cli` 在 T3.3 随真实代码创建。
4. 高风险点单列：终端恢复（T1.5）、服务发现与拉起（T3.2）、事件接入与 UI 线程约定（T3.4）。

### 8.2 阶段总览

| 阶段 | 内容 | 完成门槛 |
|---|---|---|
| **T1 终端与渲染基础** | `jcode-tui` 终端、输入、文本、差分渲染 | 渲染/宽度/输入测试齐全；异常退出后终端可恢复 |
| **T2 交互组件** | 焦点与 overlay、快捷键、基础组件、Editor、补全 | Editor 行为矩阵与 overlay 焦点测试通过 |
| **T3 客户端纵向切片** | `jcode-client`、服务发现与拉起、工作区注册（S4）、`jcode-cli` 与 `-p`、交互主循环、审批、接管 | 真实服务进程上的端到端测试通过；退出后任务继续并可重新连接 |
| **T4 富渲染** | 协议粒度（S3）、主题、消息视图、工具视图 | 所有消息与工具类型的渲染快照；历史重建与实时渲染一致 |
| **T5 命令与选择器** | 服务维护与查询（S1、S2）、命令框架与补全、选择器、补充输入交互、配置与信任、设置面板 | 每个命令的端到端测试；S1/S2 契约测试 |
| **T6 后续** | Markdown、`/tree`、fork/clone、alt-screen、图片、扩展命令、S5 等 | 各自单独立项 |

### 8.3 任务拆解

#### T1 终端与渲染基础（`jcode-tui`）

| 任务 | 内容 | 前置 |
|---|---|---|
| T1.1 模块骨架与文本工具 | 创建 `jcode-tui`、根 POM 固定 JLine、Enforcer、模块 `AGENTS.md`；`TextWidth`、`AnsiText`、`Style`/`Color`/`ColorMode`、控制字符过滤 | — |
| T1.2 Terminal 抽象与 VirtualTerminal | `Terminal` 接口；测试用 VT 模拟器 | T1.1 |
| T1.3 差分渲染器 | `Component`/`Container`/`Text`/`TruncatedText`/`Spacer`/`Box`；UI executor 与渲染合并；差分、同步输出、行宽校验、`stop()` 顺序 | T1.1、T1.2 |
| T1.4 输入解析 | `InputBuffer`、legacy `KeyParser`、`KeyEvent`/`KeyId`；焦点路由 | T1.3 |
| T1.5 JLine 终端 | `JLineTerminal`；shutdown hook 与 finally 恢复；Ctrl+Z 挂起/恢复 | T1.3、T1.4 |

#### T2 交互组件（`jcode-tui`）

| 任务 | 内容 | 前置 |
|---|---|---|
| T2.1 焦点与 Overlay | `Focusable`、`CURSOR_MARKER`；overlay 定位、焦点栈、合成 | T1.3 |
| T2.2 Keybindings | 动作注册、默认键位、用户覆盖、冲突诊断；Kitty 键盘协议 | T1.4 |
| T2.3 基础交互组件 | `Input`、`SelectList`、`SettingsList`、`Loader` | T2.1、T2.2 |
| T2.4 Editor 核心 | 文本模型、换行与光标映射、编辑与按词操作、历史、undo、滚动、IME 光标 | T2.1、T2.2 |
| T2.5 Editor 粘贴与 kill ring | paste marker 与 registry、undo 联动；kill ring；跳转字符 | T2.4 |
| T2.6 自动补全 | `AutocompleteProvider`、请求序号与取消、补全列表、`PathCompletionProvider` | T2.3、T2.4 |

#### T3 客户端纵向切片

| 任务 | 内容 | 前置 |
|---|---|---|
| T3.1 jcode-client 协议客户端 | 创建模块；鉴权请求、命令身份生成与原样重试、错误映射；SSE 帧解析、游标、重连与重同步；基于 `SessionReducer` 的本地视图 | — |
| T3.2 服务发现、拉起与工作区注册 | 客户端：`runtime.json` 校验、用户级锁、默认 `server.json`、后台拉起与就绪等待、cwd 工作区解析；服务端 S4 注册接口 | T3.1 |
| T3.3 jcode-cli 骨架与 `-p` | 创建模块；`Main`、参数框架、`--config-dir`；新建/`-c`/`--session`；`-p`、stdin、`@文件`、退出码与信号取消 | T3.2 |
| T3.4 事件接入与 UI 线程约定 | SSE 线程 → UI 线程、异步命令回调、线程断言；纯文本消息、通用工具视图、运行状态 | T1.5、T3.3 |
| T3.5 交互主循环 | 布局（header、转录、待处理输入、状态行、Editor、footer）；提交路由（prompt / steer / follow-up）；Esc 取消；退出与后台提示 | T2.4、T3.4 |
| T3.6 审批对话框 | 审批弹窗、允许/拒绝本次、多客户端竞争后的同步关闭 | T2.3、T3.5 |
| T3.7 历史与接管 | 历史分页重建转录并与流式视图衔接；连接已托管会话时从快照恢复运行、输入与审批 | T3.5 |

#### T4 富渲染

| 任务 | 内容 | 前置 |
|---|---|---|
| T4.1 协议粒度（S3） | 消息内容块、thinking、stop reason、usage；工具参数摘要；摘要类条目的展示投影 | —（服务端，可并行） |
| T4.2 主题 | `Theme` token、dark/light/auto、OSC 11 探测、颜色降级；`--use-theme`、`--verbose` | T3.5 |
| T4.3 消息视图 | thinking 块与 Ctrl+T、错误与中止、摘要类条目、启动 header | T3.7、T4.1、T4.2 |
| T4.4 工具视图 | 7 个内置工具专用渲染、通用渲染、edit diff、Ctrl+O | T3.7、T4.1、T4.2 |

#### T5 命令与选择器

| 任务 | 内容 | 前置 |
|---|---|---|
| T5.1 服务维护操作（S1） | 模型、思考级别、命名、压缩、重载；资源展开后运行；operationId 与冲突语义 | —（服务端，可并行） |
| T5.2 服务查询与设置（S2） | 模型目录、资源列表与诊断、上下文占用与 usage、会话信息与导出、settings 读写、信任 | —（服务端，可并行） |
| T5.3 命令框架与补全 | 命令注册与提交路由；`/`、`@`、Tab 补全；`/name` `/session` `/copy` `/export` `/hotkeys` `/debug` `/quit` | T2.6、T3.5、T5.1、T5.2 |
| T5.4 选择器与会话命令 | `/model` + Ctrl+L、`/thinking` + Shift+Tab、`/new`、`/resume` + `-r`、`/compact`、`/reload`；`--model`、`--thinking`、`-n`、`--list-models` | T2.3、T3.7、T5.3 |
| T5.5 补充输入交互 | 待处理输入显示、Alt+Up 取回、取消后回填、结果待确认提示 | T3.5 |
| T5.6 配置与信任 | `keybindings.json`、`tui.json` 与 `/reload`；启动信任选择器与 `/trust`；Ctrl+G 外部编辑器 | T2.2、T5.3 |
| T5.7 设置面板 | `/settings`：服务端 Agent 设置与本地界面设置 | T2.3、T5.6 |

### 8.4 依赖关系与并行

```text
tui 主线     T1.1 → T1.2 → T1.3 → T1.4 → T1.5
组件         T1.3 → T2.1 ─┐
             T1.4 → T2.2 ─┴→ T2.3、T2.4 → T2.5
                               T2.3 + T2.4 → T2.6
客户端主线   T3.1 → T3.2 → T3.3（与 T1/T2 并行）
             T1.5 + T3.3 → T3.4 → (+T2.4) T3.5 → T3.6、T3.7
服务端补口   T4.1、T5.1、T5.2（与任意阶段并行）
富渲染       T3.5 → T4.2；T3.7 + T4.1 + T4.2 → T4.3、T4.4
命令         T2.6 + T3.5 + T5.1 + T5.2 → T5.3 → T5.4、T5.6 → T5.7
输入交互     T3.5 → T5.5
```

- 关键路径：T1.1 → … → T1.5 → T3.4 → T3.5。
- 可提前并行：客户端主线 T3.1～T3.3（最早打通“CLI → 服务 → 模型 → 工具”）、服务端补口 T4.1/T5.1/T5.2、T2 组件。
- 首个可手工试用的交互版本在 T3.5 完成时出现；T3.7 之后可以日常使用并支持接管。

每个阶段开工前另写详细实施计划，放在 `docs/plans/`，完成后归档。需要 GitHub 跟踪时按 [Issue tracker](../agents/issue-tracker.md) 的 Wayfinder 约定建立 map 与子 issue。

## 9. 有意差异

- **TUI 是服务客户端**：pi 的交互模式在同一进程内驱动 Agent；Jcode 按多端架构让 TUI 与 WebUI/Desktop 平等，换取退出不中断与多端接管。
- **首版只做 main-screen**：constrained layout 只对 alt-screen 成立，不给 main-screen 伪造 sticky 语义。
- **不提供扩展 UI API**：Jcode 扩展是显式 Java 实例，服务端尚无装配方案，扩展命令与 UI 另立方案。
- **客户端偏好本地保存**：主题、快捷键不进入服务或协议，与上位设计“草稿、滚动位置和面板布局各自保存”一致。
- **不做的周边能力**：OAuth、分享、遥测、更新检查、包管理、彩蛋、LaTeX/Mermaid、原生剪贴板 addon、OKLCH 色彩系统。

## 10. 风险

| 风险 | 处置 |
|---|---|
| 自动拉起的服务进程与终端未完全解耦，关终端时被连带杀死 | 重定向标准流、不继承 TTY；手工检查清单覆盖关闭终端窗口；平台级后台化（systemd/launchd）不在首版 |
| 并发启动多个客户端导致重复拉起 | 用户级锁 + 锁内复查 `runtime.json` 与 capabilities |
| 本地可信注册被滥用（持有 Token 的程序可注册任意目录） | Token 文件仅所有者可读；注册不授信项目配置；在文档中明确“持有 Token 即本机完全信任”的边界 |
| SSE 慢消费触发频繁重同步 | UI 线程归约保持轻量、渲染合并；重同步路径有专门测试 |
| 协议粒度不足导致体验打折（T3 阶段只有纯文本与输出尾部） | S3 放在 T4 首个任务并可提前并行 |
| JNI provider 在某些平台不可用 | fallback 到 exec（`stty`）；Windows 原生终端放到 T6 |
| 崩溃后终端残留 raw mode | shutdown hook + finally + 固定 stop 顺序，专门测试 |
| 服务输出注入控制序列 | 渲染前统一过滤 |

## 11. 决策记录

### 11.1 已确认（2026-09-27）

1. **运行模型**：TUI 与 `-p` 只做 `jcode-server` 的客户端，没有服务时自动拉起后台服务。
2. **模块**：`jcode-tui`（零依赖界面库）、`jcode-client`（Java 客户端 SDK）、`jcode-cli`（入口）。
3. **工作区**：本地可信注册，Token 鉴权的注册接口，cwd 自动注册并写回 `server.json`，不自动授信。
4. **终端底层**：JLine，只用 `jline-terminal`，经自有 `Terminal` 接口隔离。
5. **配置目录**：`~/.jcode`，可由 `$JCODE_HOME` 或 `--config-dir` 覆盖。
6. **工具审批**：默认不询问（服务 `approvalTools` 默认为空，与 pi 一致）；服务开启审批时 TUI 必须能处理审批。
7. **Markdown**：首版不做，延后到 T6。
8. **print 模式**：保留，经服务运行。
9. **首版斜杠命令**：§5.8 的 15 个；`/tree`、`/fork`、`/clone`、`/import`、HTML 导出、`/login`、`/logout`、`/scoped-models`、扩展命令延后到 T6。
10. **任务拆解**：按 §8.3 拆成 29 个任务，其中 T4.1、T5.1、T5.2 为纯服务端补口，T3.2 同时改服务端（S4）与客户端。

### 11.2 待确认

1. **会话装配参数**：`-t/-xt/-nt`、`--system-prompt`、`--append-system-prompt`、`--skill`、`--prompt-template`、`--no-default-resources`、`-nc`、`-a/-na` 在服务模式下需要 S5（创建会话时由客户端指定装配参数）。建议：首版不做，延后到 T6 与 S5 一起设计。
2. **`--no-session` 与 `--session-dir`**：服务不支持内存会话，会话目录由服务拥有。建议：删除这两个参数。
3. **服务管理子命令**：是否提供 `jcode server start|stop|status`，让用户显式启动、停止和查看后台服务。建议：提供，放在 T3.2。
4. **发行形态**：客户端需要找到服务 jar 才能自动拉起。建议：新增聚合模块 `jcode-dist`，产出含 `jcode-cli`、`jcode-server` 与启动脚本的发行目录；开发期用 `JCODE_SERVER_JAR`。
