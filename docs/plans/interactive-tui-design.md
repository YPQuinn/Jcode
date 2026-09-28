# Interactive TUI 设计方案

> 状态：设计已确认（尚未实施）；已按 `33ac240` 的审阅意见修订，决策记录见 §11
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
   - `jcode-client`：Java 客户端 SDK，只依赖 `jcode-protocol` 与 JDK `HttpClient`；
   - `jcode-cli`：最终用户入口，持有 `main`、参数解析、交互模式与 print 模式。
3. **同步状态只有一个所有者：`jcode-client` 的观察会话。** 它持有快照、恢复游标与历史合并结果，并在调用方提供的串行 executor 上归约事件（交互模式用 UI executor，print 模式用普通串行 executor）。界面只读取它发布的视图，不另存游标或第二份快照（§5.3）。
4. **先显式连接，再自动拉起。** SDK 与 CLI 先支持“只连接已有服务”的路径并完成纵向验证；自动拉起后台服务与工作区本地可信注册（S4）按已确认目标随后交付，有独立的提交边界和失败语义（§5.1、§5.2）。
5. **渲染模型**沿用 pi-tui：组件返回“按宽度排好的一组终端行”，渲染器逐行差分，用 CSI 2026 同步输出。首版只做 main-screen；转录分为冻结区与活动区，重绘范围有上限（§4.3）。
6. **终端底层只用 JLine 的 `jline-terminal`**，通过自有 `Terminal` 接口隔离；编辑器、差分渲染和组件自建。各项终端增强能力都可以检测、关闭和降级。
7. **只承诺现有契约能支撑的交互。** 补充输入不提供“撤回 pending 输入”；只有确认未应用（`NOT_APPLIED`）的输入才可以显式恢复为草稿（§5.6）。print 模式对审批、截断与不确定结果都有明确退出语义（§5.8）。
8. 服务端缺少的能力按性质分为只读查询、Session 维护、用户配置写入和资源命令运行，作为**服务 API 补口**（S1～S5）在 `jcode-protocol`/`jcode-app`/`jcode-server` 中实现，不为 TUI 开旁路（§6）。
9. **分两个里程碑交付**：C-MVP（可实际接管的 TUI）与 C-Enhanced（完整日常交互），见 §8.2。

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
| 差分渲染（first/last changed、宽度变化重绘） | `TuiMainScreen` | 保留；重绘限于活动区（§4.3） | T1 |
| 渲染合并与 16ms 节流 | `requestRender` | 保留 | T1 |
| CSI 2026 同步输出 | 默认启用 | 保留，可关闭 | T1 |
| 行宽校验 | 强制 | 测试中强制，生产中截断并记录诊断 | T1 |
| 字素分段、列宽、截断、按列切片、带 SGR 状态的换行 | `utils.ts` | 自建；字素边界用 `BreakIterator`，列宽另行计算（§4.5） | T1 |
| stdin 切分、ESC 超时、bracketed paste | `stdin-buffer.ts` | 保留 | T1 |
| 按键解析（legacy xterm、Kitty keyboard protocol） | `keys.ts` | legacy 放 T1；Kitty 协议作为可关闭增强放 T2 | T1/T2 |
| Focusable + `CURSOR_MARKER` 双层光标（IME） | 内建 | 保留；退出时先清软件光标、再恢复硬件光标 | T2 |
| Overlay（定位、焦点栈、隐藏） | 内建 | 保留锚点与百分比定位 | T2 |
| Input / Editor / SelectList / SettingsList / Loader | 内建 | 保留；Editor 按 §4.4 实现 | T2 |
| Keybindings 管理、AutocompleteProvider | 内建 | 保留 | T2 |
| Markdown 渲染与代码高亮 | `markdown.ts` | 首版不做，助手文本按纯文本换行显示 | T6 |
| 颜色 truecolor / 256 / 16 降级、OSC 11 明暗探测 | 内建 | 保留；OKLCH/OKHSL 与运行时订阅不做 | T4 |
| OSC 8 超链接、Kitty/iTerm2 图片 | 内建 | 后续 | T6 |
| Alt-screen：VStack/HStack/ScrollView、鼠标、选区、搜索 | `TuiAltScreen` | 后续整体交付 | T6 |
| LaTeX / Mermaid、原生剪贴板 addon | 内建 | 不做；复制用 OSC 52 | — |

### 2.2 交互产品层（pi interactive mode → Jcode `jcode-cli`）

| 能力 | pi 现状 | Jcode 取舍 | 阶段 |
|---|---|---|---|
| 用户消息、助手流式消息 | 有 | 纯文本（按宽度换行，保留换行与 Tab） | T3 |
| 工具执行（按工具定制渲染、Ctrl+O 折叠/展开） | 有 | T3 通用视图（名称、状态、输出尾部）；专用渲染只使用 S3 实际提供的字段（§6.3） | T3/T4 |
| Thinking 块显示/隐藏（Ctrl+T） | 有 | 需要 S3 | T4 |
| Compaction / branch summary / custom message 展示 | 有 | 需要 S3 | T4 |
| 启动 header | 有 | T3 基础信息（cwd、工作区、Session ID、服务实例）；资源与诊断详情需要 S2 | T3/T4 |
| 工作中状态指示 | 有 | 保留 | T3 |
| Footer | 有 | T3 显示 cwd、Session ID 与 `fileRef`、运行状态；会话名、模型与用量需要 S2/S3 | T3/T4 |
| Enter 提交 / 运行中 Enter = steer、Alt+Enter = follow-up | 有 | 映射为绑定目标 Run 的补充输入 | T3 |
| Esc 中止 | 有 | 在没有局部交互可关闭时，显式取消当前 Run（按 runId） | T3 |
| 待处理输入显示 | 有 | 展示四种输入状态（§5.6） | T3 |
| Alt+Up 取回排队消息、中止时回填编辑器 | 有 | **不承诺撤回 pending 输入**；仅对确认 `NOT_APPLIED` 且本客户端保留原文的输入提供显式恢复草稿 | T3 |
| 工具审批对话框 | 需扩展 | **必需**：服务开启审批时必须能处理 | T3 |
| 其他客户端发起的运行、输入与审批 | — | 按 SSE 事件显示状态与身份；他端输入原文需后续查询能力 | T3 |
| Ctrl+C 清空/两次退出、Ctrl+D 退出、Ctrl+Z 挂起 | 有 | 退出只断开连接，运行中退出提示“任务在后台继续” | T3 |
| Shift+Tab 思考级别、Ctrl+L 模型选择器 | 有 | 需要 S1/S2 | T5 |
| Ctrl+G 外部编辑器 | 有 | 保留 | T5 |
| `/` 命令补全、`@` 文件引用、Tab 路径补全 | 有 | 保留；模板/技能列表需要 S2，文件列表由客户端本地读取 cwd（本机场景） | T5 |
| 斜杠命令（清单见 §5.10） | 有 | 首版 15 个内建命令 | T5 |
| `/tree` `/fork` `/clone` `/import`、HTML 导出、`/login`、`/scoped-models` | 有 | 后续 | T6 |
| 扩展命令、`!cmd`、图片粘贴 | 有 | 后续 | T6 |
| 项目信任选择器 | 有 | 需要 S2 | T5 |
| 主题 dark/light/auto、`keybindings.json` | 有 | 保留（客户端本地配置） | T4/T5 |
| print 模式（`-p`） | 有 | 保留，同样经服务，规则见 §5.8 | T3 |
| JSON 事件模式 / RPC 模式 | 有 | 不在本方案内（服务 HTTP API 已承担程序集成） | — |
| OAuth 登录、分享、遥测、更新检查、彩蛋 | 有 | 不做 | — |

## 3. 模块、依赖与构建

### 3.1 模块职责

| 模块 | 包 | 职责 | Jcode 内部依赖 |
|---|---|---|---|
| `jcode-tui` | `site.pplee.jcode.tui` | 终端抽象、输入解析、文本宽度、差分渲染、组件、overlay、keybindings、autocomplete 接口、样式 | 无 |
| `jcode-client` | `site.pplee.jcode.client` | 实例定位与连接、自动拉起、鉴权、命令身份与重试、SSE 解析、观察会话（快照 + 游标 + 历史合并 + 连接代次） | `jcode-protocol` |
| `jcode-cli` | `site.pplee.jcode.cli` | `main`、参数、客户端本地配置、交互模式、print 模式、主题与命令 | `jcode-client`、`jcode-protocol`、`jcode-tui` |

### 3.2 Enforcer 规则

以下规则约束 **compile 与 runtime scope**（即生产依赖）：

- `jcode-tui`：禁止依赖任何 Jcode 模块。
- `jcode-client`：只允许依赖 `jcode-protocol`。
- `jcode-cli`：只允许依赖 `jcode-client`、`jcode-protocol`、`jcode-tui`；禁止依赖 `coding-agent`、`jcode-app`、`jcode-server`、`agent-core`、`ai`、`ai-providers`。
- `jcode-dist`：只做打包，允许依赖 `jcode-cli` 与 `jcode-server`，不含 Java 代码。
- 除 `jcode-dist` 外，其余模块禁止依赖上述三个客户端模块。

**test scope 例外**：`jcode-client` 与 `jcode-cli` 可以在 test scope 依赖 `jcode-server` 及其 test-jar，用于复用现有的测试服务与假模型装配，做进程内或子进程集成测试。`jcode-server` 发布 test-jar 属于 T3.1 的改动。生产代码仍无法绕过服务。

### 3.3 外部依赖

| 依赖 | 用途 | 模块 |
|---|---|---|
| `org.jline:jline-terminal` + `jline-terminal-jni` | raw mode、尺寸、SIGWINCH、输入流、终端类型、`WCWidth` | `jcode-tui` |
| Jackson（已在根 POM 固定） | 协议 JSON | `jcode-client` |

HTTP 与 SSE 使用 JDK `java.net.http.HttpClient`。参数解析手写强类型 parser。

### 3.4 可执行产物

- `jcode-cli` 提供 `site.pplee.jcode.cli.Main`；`jcode-server` 仍是独立服务入口。
- 自动拉起时，客户端使用当前 JVM 可执行文件（`ProcessHandle.current().info().command()`）加服务 classpath；服务 jar 位置由发行目录布局决定，开发时可用环境变量 `JCODE_SERVER_JAR` 指定。
- 新增纯打包模块 `jcode-dist`（T3.2），产出发行目录或压缩包：

  ```text
  jcode-<version>/
  ├── bin/jcode            启动脚本（java -jar lib/jcode-cli.jar）
  └── lib/
      ├── jcode-cli.jar
      └── jcode-server.jar
  ```

  CLI 按自身 jar 所在目录定位同级的 `jcode-server.jar`；`JCODE_SERVER_JAR` 可以覆盖。native image、系统安装器与包管理器分发不在范围内。

## 4. `jcode-tui` 模块设计

### 4.1 包布局

```text
site.pplee.jcode.tui
├── terminal/   Terminal、JLineTerminal、TerminalSize、TerminalCapabilities
├── input/      InputBuffer（切分/ESC 超时/bracketed paste）、KeyParser、KeyEvent、KeyId
├── text/       Graphemes、TextWidth、AnsiText（wrap/truncate/sliceByColumn）、Style、Color、ColorMode、TerminalTextSanitizer
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

**焦点与按键优先级**固定为：审批弹窗或其他 overlay → 补全列表 → Editor → 全局动作。任一层消费按键后，后续层不再处理。例如 Esc 先关闭补全，只有没有局部交互消费时才触发全局“取消 Run”。

### 4.3 渲染器（main-screen）

- **调度**：`requestRender()` 线程安全、可合并；UI 线程按 ≥16ms 间隔执行 `doRender()`。只合并绘制请求，不影响业务事件的归约（§5.3）。
- **冻结区与活动区**：已经进入终端 scrollback 且不会再变化的转录条目进入冻结区，不再参与差分；活动区包括仍可能变化的消息与工具、待处理输入、状态行、Editor 与 footer。
- **差分**：只在活动区内比较新旧帧，找出 `firstChanged`/`lastChanged` 并重写该区间；输出包在 `CSI ?2026h … ?2026l` 中一次写出（能力关闭时直接写出）。
- **重绘上限**：宽度变化或冻结区上方内容发生变化（例如展开一条已滚出的工具输出）时，清屏后只重放最近的有限条转录（上限可配置，默认按条目数与行数双重限制），更早部分以一行提示代替。完整记录由服务历史保存，不要求终端重放全部内容。
- **Overlay**：先渲染主内容，再合成 overlay，最后差分；焦点栈记录 `preFocus`。
- **生命周期**：`start()` 进入 raw mode、开启 bracketed paste、隐藏光标。`stop()` 依次清除软件光标 → 光标移到内容末尾 → 关闭 bracketed paste/Kitty 协议 → 显示光标 → 恢复 cooked mode；shutdown hook 与 finally 保证异常退出也能恢复终端。
- **挂起**（Ctrl+Z，POSIX）：恢复终端后向自身发送 SIGTSTP；SIGCONT 后重新进入 raw mode 并强制重绘活动区。

### 4.4 Editor

- 文本模型为逻辑行列表加光标；换行在字素边界进行，并记录视觉块 `[start,end)`，用于光标映射；按词操作用 `BreakIterator.getWordInstance()`。
- **换行键**：Enter 提交，Ctrl+J 插入换行（所有终端可用）；Kitty 键盘协议可用时 Shift+Enter 也插入换行。Alt+Enter 保留给 follow-up。
- Undo 快照同时保存文本、光标、paste registry 与计数器；删除 paste marker 时同步 registry 并重新编号。
- 大段粘贴（>10 行或超过字符阈值）折叠为 `[paste #n +k lines]` 原子段，提交时展开。
- Kill ring：Ctrl+K/U/W、Ctrl+Y、Alt+Y。
- 自动补全：输入触发时去抖、Tab 强制触发；请求带递增序号，过期结果丢弃；补全列表以 overlay 显示，可见 3～20 条。
- 超高时滚动并显示 `↑ n more`（按列宽安全截断）；首行按 Up 浏览 prompt 历史。

### 4.5 字素、列宽与控制字符

- **字素边界与列宽分开处理。** Java 21 的 `BreakIterator.getCharacterInstance()` 已按扩展字素簇分段，不引入 ICU；每个字素的显示列宽另行计算（东亚宽字符、组合音标、emoji 与 ZWJ 序列、变体选择符），基于 JLine `WCWidth` 加少量显式规则。
- Tab 在渲染时按固定制表宽度展开为空格；Editor 内部保留原字符。
- 删除、光标移动、换行、截断都以字素为最小单位，以列宽为度量；为中文、组合音标、emoji/ZWJ、变体选择符和 Tab 建立统一的宽度、删除、换行与光标测试矩阵。
- **来自服务的文本**经 `TerminalTextSanitizer` 处理：保留 `\n` 与 `\t` 的语义（分行、展开），`\r\n` 归一为 `\n`；删除其余 C0、C1 控制字符与 ESC 引导的序列。不能把多行消息压成一行。

### 4.6 终端能力检测与降级

| 能力 | 检测 | 降级 |
|---|---|---|
| CSI 2026 同步输出 | 终端类型与 DECRQM 查询（带超时） | 直接写出；`tui.json` 可强制关闭 |
| Kitty 键盘协议 | 查询响应（带超时） | legacy 按键；换行仅用 Ctrl+J |
| OSC 11 明暗查询 | 查询响应（100ms 超时） | 按 `COLORFGBG` 或默认 dark |
| OSC 52 剪贴板 | 无可靠查询 | 默认启用，可关闭；`/copy` 失败时提示 |
| 颜色模式 | `COLORTERM`/`TERM` | 256 或 16 色 |
| tmux / screen | 环境变量 | 关闭 Kitty 协议与 OSC 52 直写，按需使用透传 |

`tui.json` 可以逐项关闭增强能力；JLine provider 不可用时退回 exec（`stty`）并在启动信息中报告。

## 5. 客户端设计（`jcode-client` + `jcode-cli`）

### 5.1 实例定位、显式连接与自动拉起

默认目录 `~/.jcode`（可由 `$JCODE_HOME` 或 `--config-dir` 覆盖）：

```text
~/.jcode/
├── server.json          服务配置（自动拉起时若缺失则生成默认值）
├── models.json、auth.json、settings.json、trust.json …   现有用户配置（服务的 userConfigDirectory）
├── keybindings.json、tui.json                          客户端本地配置
└── client-launch.lock   客户端启动协调锁（与服务的 server.lock 分离）
```

**实例路径从配置解析。** `runtime.json`、`service.token` 的位置跟随 `server.json` 中实际的 `dataDirectory`；只有客户端生成默认配置时才把 `dataDirectory` 设为 `~/.jcode/server`。客户端不硬编码实例路径。

**显式连接（先交付）：**

- SDK 提供 `connect(dataDirectory)`：读取 `runtime.json` → 从 `service.token` 读 Token → 调 `/v1/capabilities` 核对 `instanceId` 与协议版本。失败即报错，不启动任何进程。
- CLI 提供 `--no-start`：只连接已有服务，连不上以退出码 3 结束。测试、print 模式与 SDK 的纵向验证先走这条路径。

**自动拉起（随后交付）：**

```text
connect(dataDirectory) 失败（无描述或描述陈旧）
  → 获取 client-launch.lock（客户端之间的协调锁）
      → 锁内再次 connect；成功则结束
      → 确保 server.json 存在（缺失时生成默认配置）
      → 启动 jcode-server --config <server.json 绝对路径>
      → 轮询 connect 直到就绪或超时
  → 释放锁
```

- 客户端**不**打开或尝试获取服务的 `server.lock`；服务仍保留最终的实例排他权。
- 子进程三个标准流都有明确去向：stdin 来自空设备；stdout 与 stderr 追加到 `<dataDirectory>/logs/server-launch.log`，避免管道写满阻塞服务，也不依赖客户端的管道。
- 进程与终端会话脱离：Linux 通过新会话（`setsid`）启动，macOS 以忽略 SIGHUP 的方式启动。具体做法在 T3.2 验证后固定；未验证的平台明确报错，要求用户显式启动服务。不建设跨平台服务安装器。
- 验收时把“退出 TUI”和“关闭终端窗口”分开：两种情况下服务与 Run 都必须继续存活，分别有手工检查记录。
- 身份不符或版本不兼容时明确报错，不启动第二个实例。

### 5.2 工作区解析与 S4 注册契约

客户端流程：

1. 取 cwd 的真实路径，在 `GET /v1/workspaces`（`WorkspaceView` 含 `directory`）中查找相同目录。
2. 没有则调用 `POST /v1/workspaces {"directory": "<真实路径>"}` 注册。

服务端契约（S4）：

- **配置来源显式传入。** `ServerMain` 把 `--config` 指定的文件路径显式交给宿主；宿主不猜测默认位置。
- **可变注册表。** 服务持有一个工作区注册组件，初始值来自 `ServerConfig.workspaces()`（`ServerConfig` 本身保持不可变）；工作区列表、创建会话、打开会话与会话文件目录解析都从该组件读取。
- **幂等。** 相同真实目录重复注册返回同一个 `workspaceId`（目录名 + 短哈希，满足现有 ID 规则）。
- **串行提交。** 并发注册在注册组件内串行化。
- **先持久化后发布。** 以读取—修改—原子替换写回 `server.json`（保留其他字段）；写入失败则不改变内存状态并返回错误，不返回成功。
- **范围受限。** 只更新 `workspaces`，不支持整个服务配置热重载；不自动授信项目设置或文本资源，不改变工具 policy 或审批配置。
- 鉴权沿用现有 Bearer Token：持有 `service.token` 即视为本机可信。

### 5.3 状态所有权、线程与背压

```text
SSE 读取线程
    ↓ 完整事件（或有界批次）
jcode-client ObservedSession
    ↓ 在调用方提供的串行 executor 上执行，读取线程等待其完成
SessionReducer → 本地快照 → 恢复游标 → 历史合并 → 发布视图变更
    ↓
界面（UI executor 上读取视图；只合并绘制请求）
```

- **唯一所有者。** `ObservedSession` 持有当前快照、恢复游标、历史合并结果与连接代次。交互模式把 UI executor 传给它，print 模式传普通串行 executor。界面不保存第二份快照或游标。
- **应用后推进游标。** 事件在 executor 上完成归约后才推进游标，满足协议“完整解析并应用后推进游标”的要求。
- **客户端背压。** SSE 读取线程每投递一个事件或一个有界批次，就等待 executor 完成应用后再继续读取；等待发生在读取线程，不在 UI 线程。本地没有无界队列。只合并绘制请求，不丢弃尚未归约的业务事件。客户端处理不过来时，积压会回到服务端的订阅缓冲，并按现有规则触发 `SUBSCRIBER_SLOW` 重同步。
- **HTTP 回执不回退状态。** 命令回执只用于确认本次提交的结果（接纳、冲突、错误）。回执与事件投影冲突时（例如先收到 `COMPLETED` 事件，后收到早先提交的 `ACCEPTED` 回执），以事件投影为准。回执不推进游标，也不覆盖视图。
- **连接代次。** 每次连接或切换会话都生成新的连接代次。快照、SSE、历史分页、命令回调与审批回调都带着发起时的代次，代次不匹配的结果直接丢弃，不会污染新会话的界面。
- **实例变化时不自动重提。** 发现新的 `instanceId` 或新 epoch 时，重新取状态；不确定结果的写命令标记为“结果待确认”，不为它们生成新身份重试。只有同一实例内、以原身份重试才是安全的。
- **UI 线程禁止阻塞。** 所有命令经 `sendAsync` 发出，回调回到调用方 executor；UI 线程禁止 `join()` 任何网络结果，测试环境加线程断言。

### 5.4 连接顺序、历史加载与合并

```text
获取快照，记住 cursor = S、leafId = H
    ↓
立即以 S 订阅 SSE，恢复 Run、输入与审批（审批与取消此时即可操作）
    ↓
以固定 head = H 异步加载最近一页历史
    ↓
用户向上浏览时再按需加载更早历史
```

全量历史加载**不是**开始接管的前置条件。历史请求带连接代次，切换会话后的迟到页直接丢弃。

合并规则：

| 数据 | 身份与合并规则 |
|---|---|
| 已提交历史条目 | 以 `entryId` 去重，决定历史位置 |
| 正在生成的消息 | 以 `messageId` 更新 |
| 流式消息完成并取得 `entryId` | 与同 `entryId` 的历史条目合并，不重复展示 |
| 固定 head `H` 之后产生的新消息 | 保留，不因较早历史页返回而删除 |
| 历史页的有界摘要（最多 8192 字符，`textTruncated`） | 不覆盖已持有的更完整实时文本；只在本地没有该条目时展示，并标注截断 |
| 旧连接代次的历史页、快照与回调 | 丢弃 |

### 5.5 会话、运行与接管

| 用户动作 | 客户端行为 |
|---|---|
| 启动（无会话参数） | 在当前工作区 `POST /sessions` 新建会话 |
| `-c` | `GET /sessions` 中找本工作区已托管的会话（优先）；否则 `GET /session-files` 取最近一个并 `POST /sessions/open` |
| `--session <fileRef\|ID 前缀>` | 在本工作区按文件引用或 ID 前缀匹配；已托管就直接接管，否则打开 |
| `-r` | 启动时的会话选择器（列出已托管会话与磁盘会话文件） |
| 连接会话 | 按 §5.4 的顺序 |
| 提交（空闲） | `POST /runs`（prompt），客户端生成 `commandId`/`runId`，重试时原样保留 |
| 提交（运行中） | `POST /runs/{runId}/inputs`，mode 为 steer（Enter）或 follow-up（Alt+Enter）；本客户端在内存中保留该输入的原文 |
| Esc（无局部交互时） | `POST /runs/{runId}/cancel`；显示“取消中”，终态以事件为准 |
| 审批事件 | 弹出审批对话框：允许本次 / 拒绝本次；提交时携带快照中的 `approvalId`、`toolCallId`、`requestDigest` |
| 退出（Ctrl+D、`/quit`、两次 Ctrl+C） | 断开 SSE；有运行中任务时提示“任务在后台继续，可用 `jcode -c` 重新连接” |
| 命令响应丢失 | 同一实例内以原身份查询或重试；界面显示“结果待确认”，不生成新身份 |

### 5.6 补充输入的展示与恢复

`InputView` 没有输入正文；内核内部的排队、领取与应用中状态都投影为 `PENDING`，客户端无法判断 pending 输入是否仍可撤回。首版因此只承诺以下交互：

| 输入状态 | 首版交互 |
|---|---|
| `PENDING` | 展示“已接纳、待应用”；不提供撤回或编辑 |
| `NOT_APPLIED` | 用户主动选择（Alt+Up 或菜单）后，把本客户端保留的原文恢复到草稿；再次提交使用新的 `commandId`/`inputId` |
| `APPLIED_TO_CONTEXT` | 作为已进入历史的输入展示，可按 `entryId` 定位；不回填为未执行任务 |
| `RECONCILIATION_REQUIRED` | 显示“结果待确认”；不自动恢复、不重投、不生成新身份 |

- **取消不触发回填。** 取消请求本身不改变草稿。目标 Run 与其输入结算之后，只对确认 `NOT_APPLIED` 的输入提供显式恢复操作。
- 恢复不覆盖用户正在编辑的新草稿：编辑器非空时追加到末尾并提示，或要求用户先确认；恢复后不自动提交。
- 其他客户端提交的输入，首版只展示状态与身份（`inputId`、mode、目标 Run）。跨端恢复原文需要新增输入正文查询，不在首版范围。
- 真正的 pending 撤回需要内核原子判定“撤回成功”或“已进入应用阶段”，另行设计，不为首版扩展内核。

### 5.7 视图映射

| 协议事件 / 视图 | UI 动作 |
|---|---|
| `RUN_CHANGED` / `RunView` | working 指示、取消中状态、终态（completed / failed / cancelled）与错误提示 |
| `MESSAGE_CHANGED` / `MessageView` | 按 `messageId` 创建或替换消息视图；`complete` 后定稿并按 `entryId` 与历史合并 |
| `TOOL_CHANGED` / `ToolView` | 按 `runId + toolCallId` 更新工具视图（状态、输出尾部、是否截断/错误） |
| `INPUT_CHANGED` / `InputView` | 按 §5.6 更新待处理输入区 |
| `APPROVAL_CHANGED` / `ApprovalView` | 弹出、更新或关闭审批对话框（其他客户端先决定时同步关闭并提示） |
| `HISTORY_CHANGED` | 在固定新 head 下刷新最近一页历史，按 §5.4 合并 |

### 5.8 print 模式（`-p`）

**输出通道：** stdout 只写约定的结果文本；服务发现、启动、审批、截断、错误等提示一律写 stderr。

**会话与运行：**

- `-p` 创建一个新的 Run，并在本地记录它的 `runId`。
- `-p --session …` 或 `-p -c` 遇到已有活动 Run 时明确报忙，不会转成对那个 Run 的 steer。
- SIGINT/SIGTERM 只对本次调用已生成的 `runId` 发出取消请求，不根据“当前活动 Run”重新选择目标；有界等待终态后退出。

**审批：** 首版非交互模式不自动批准，也不从 stdin 读取审批答案。遇到待审批事项时，在 stderr 输出 Session ID、Run ID、待审批工具，以及接管方法（`jcode --session <id>`），然后以退出码 4 结束；任务保留在服务端，由其他客户端接管。首版不提供等待审批的选项；以后如有需要，再以显式选项（如 `--wait-approval`）增加，不改变默认行为。

**截断：** `RunView.text` 是终态摘要，可能带 `textTruncated`。遇到截断时仍把已有文本写到 stdout，但在 stderr 明确提示输出不完整，并以退出码 5 结束，不把摘要伪装成完整结果。首版不提供完整正文读取；C-Enhanced 阶段视实际需要，再补一个按 `runId` 或 `entryId` 分段读取完整助手正文的只读接口，不顺带建设完整导出协议。

**退出码：**

| 退出码 | 含义 | 任务是否执行 |
|---|---|---|
| 0 | Run completed，输出完整 | 已执行 |
| 1 | Run failed 或 cancelled | 已接纳并结束 |
| 2 | 用法错误 | 未提交 |
| 3 | 无法连接或拉起服务；会话忙；命令被明确拒绝 | 未执行 |
| 4 | 需要人工审批，任务保留在服务端 | 进行中 |
| 5 | Run completed，但输出被截断 | 已执行 |
| 6 | 结果待确认（连接中断且无法确认、实例变化等） | 未知 |
| 130 | 收到中断信号，已对本次 Run 请求取消 | 视取消结果而定，详情见 stderr |

### 5.9 启动参数

用法：`jcode [选项] [--] [@文件...] [消息...]`

| 类别 | 参数 | 含义 | 依赖 | 任务 |
|---|---|---|---|---|
| 调用 | `[消息...]` | 以空格拼接成第一条 prompt；交互模式自动提交 | — | T3.3 |
| | `@路径` | 文本文件内容以 `<file path="…">…</file>` 包裹后放在第一条 prompt 前；严格 UTF-8、有大小上限，非文本报用法错误 | — | T3.3 |
| | `--` | 停止解析选项 | — | T3.3 |
| | `-p, --print` | 按 §5.8 运行一次 | — | T3.3 |
| | 管道 stdin / 自动 print | stdin 非 TTY 时有界读取并拼到 prompt 前；stdin 或 stdout 非 TTY 时自动 print | — | T3.3 |
| 连接 | `--no-start` | 只连接已有服务，不自动拉起 | — | T3.3 |
| 会话 | `-c, --continue`、`--session <fileRef\|ID 前缀>` | 见 §5.5 | — | T3.3 |
| | `-r, --resume` | 启动时的会话选择器 | — | T3.7 |
| | `-n, --name <名称>` | 设置会话名 | S1 | T5.4 |
| 模型 | `--model <provider/id[:thinking]>`、`--thinking <level>` | 连接会话后以维护操作设置 | S1 | T5.4 |
| | `--list-models [关键词]` | 列出服务端模型目录后退出 | S2 | T5.4 |
| 显示 | `--use-theme <dark\|light\|auto>`、`--verbose` | 本次运行的主题 / 完整启动信息 | — | T4.2 |
| 其他 | `--config-dir <dir>`、`-h`、`-v` | — | — | T3.3 |

**服务管理子命令**（T3.2）：

| 命令 | 行为 |
|---|---|
| `jcode server status` | 读取实例描述并校验，显示是否运行、endpoint、instanceId、版本、托管会话数与运行中 Run 数 |
| `jcode server start` | 复用自动拉起逻辑，只启动不连接；已在运行则直接报告 |
| `jcode server stop` | 调用现有 `/v1/server/stop`；服务只在空闲时接受。有运行中任务时列出任务并以非零码退出，不强制取消 |

**不提供的参数：**

- 会话装配参数 `-t/-xt/-nt`、`--system-prompt`、`--append-system-prompt`、`--skill`、`--prompt-template`、`--no-default-resources`、`-nc`、`-a/-na`：需要 S5，延后到 T6 与 S5 一起设计。首版会话一律按用户设置与项目设置装配。
- `--no-session`：服务不支持内存会话，删除。
- `--session-dir`：会话目录由服务拥有，删除；需要隔离存储时用 `--config-dir` 使用另一个配置目录与服务实例。

### 5.10 斜杠命令清单（首版）

| 命令 | 作用 | 依赖 | 任务 |
|---|---|---|---|
| `/model [provider/id]` | 选择或切换模型（Ctrl+L 同） | S1、S2 | T5.4 |
| `/thinking [level]` | 设置思考级别（Shift+Tab 循环） | S1 | T5.4 |
| `/new` | 在当前工作区新建会话并切换过去 | — | T5.4 |
| `/resume` | 选择本工作区的会话恢复（复用 `-r` 的选择器） | — | T5.4 |
| `/compact [说明]` | 手动压缩上下文 | S1 | T5.4 |
| `/reload` | 服务端重载上下文与资源；客户端重载 `keybindings.json`、`tui.json` | S1 | T5.4 |
| `/settings` | 设置面板 | S2 | T5.7 |
| `/name [name]` | 设置或显示会话名 | S1、S2 | T5.3 |
| `/session` | 会话文件、ID、消息数、上下文占用、token 与费用 | S2 | T5.3 |
| `/copy` | 复制最后一条助手回复（Ctrl+X 同，OSC 52） | — | T5.3 |
| `/export [path]` | 导出当前会话为 JSONL | S2 | T5.3 |
| `/hotkeys` | 列出当前生效的快捷键 | — | T5.3 |
| `/debug` | 把当前渲染行、本地视图与最近事件写入 `~/.jcode/debug.log`，提示可能含敏感信息 | — | T5.3 |
| `/trust` | 保存当前项目的信任决定 | S2 | T5.6 |
| `/quit` | 断开并退出 | — | T5.3 |

`/settings` 首版范围：Agent 设置（默认模型、默认思考级别、steering/follow-up 模式、自动压缩及阈值，经 S2 写入用户级 settings）与界面设置（写客户端本地 `tui.json`）。不编辑项目级设置、Provider、凭证与信任。

延后到 T6：`/tree`、`/fork`、`/clone`、`/import`、HTML 导出、`/login`、`/logout`、`/scoped-models`、扩展命令。不做：`/share`、`/bug`、`/changelog`、`/llama` 与彩蛋命令。

### 5.11 输入语义

| 按键（默认） | 空闲 | 运行中 |
|---|---|---|
| Enter | 路由提交（见下） | steer 补充输入 |
| Ctrl+J（Kitty 协议可用时另有 Shift+Enter） | 插入换行 | 同左 |
| Alt+Enter | 同 Enter | follow-up 补充输入 |
| Esc | 按 §4.2 优先级关闭局部交互 | 没有局部交互时取消当前 Run |
| Alt+Up | 恢复最近一条确认 `NOT_APPLIED` 的本地输入原文 | 同左 |
| Ctrl+C | 清空编辑器；500ms 内再按一次退出（断开） | 同左 |
| Ctrl+D | 编辑器为空时退出（断开） | 同左，并提示任务在后台继续 |
| Shift+Tab / Ctrl+L | 切换思考级别 / 模型选择器 | 维护操作与运行冲突，提示稍后 |
| Ctrl+O / Ctrl+T | 工具输出展开 / thinking 显示 | 同左 |
| Ctrl+G | 外部编辑器 | 同左 |

提交路由：内建命令 → 资源命令（模板 `/名称`、技能 `/skill:名称`，服务端展开后作为 Run 执行，S1）→ 普通 prompt。未知 `/xxx` 按普通文本发送。

### 5.12 客户端本地配置与安全

- `~/.jcode/keybindings.json`（动作 id → 按键或列表）与 `~/.jcode/tui.json`（`theme`、`quietStartup`、`editorPaddingX`、`autocompleteMaxVisible`、`showHardwareCursor`、`hideThinking`、`toolsExpanded`、`externalEditor`、`transcriptReplayLimit`、终端能力开关）只由客户端读取，不进入服务或协议。解析沿用仓库约定：完整单根 JSON，未知字段忽略，诊断不含字段值。
- 所有来自服务的文本按 §4.5 处理后再渲染。
- `service.token` 只从文件读入内存，不写日志，也不进入 `/debug` 输出。

## 6. 服务 API 补口

### 6.1 按性质分类

| 类别 | 接纳语义 | 包含 |
|---|---|---|
| 只读查询 | 不占用 Session 维护状态，运行中也可调用 | 模型目录、资源列表与诊断、上下文占用与 usage、会话信息（含名称）、JSONL 导出、信任查询 |
| Session 维护 | 使用 `operationId` 与独立结果查询；与 Run 冲突时返回冲突，不排队 | 切换模型与思考级别、会话命名、手动压缩、重载资源与项目上下文 |
| 用户配置写入 | 不属于 Session 维护；沿用现有 settings/trust 的锁内读—改—原子替换 | 用户级 settings 读写、项目信任保存 |
| 资源命令运行 | 仍然是 Run：`RunCommand` 增加“按资源展开”选项，展开与运行在服务端完成 | 模板 `/名称`、技能 `/skill:名称` |

### 6.2 补口清单

| 编号 | 内容 | 类别 | 首版使用方 | 阶段 |
|---|---|---|---|---|
| S1 | 模型与思考级别切换、会话命名、压缩、重载；资源命令运行 | Session 维护 / 资源命令运行 | `/model` `/thinking` `/name` `/compact` `/reload`、`--model`、`-n`、资源命令 | T5 |
| S2 | 模型目录、资源列表与诊断、上下文占用与 usage、会话信息、JSONL 导出、settings 读写、信任查询与保存 | 只读查询 / 用户配置写入 | `/model` `/session` `/export` `/settings` `/trust`、补全、header 与 footer 详情 | T5 |
| S3 | 协议粒度（见 §6.3） | 协议扩展 | 富渲染、footer | T4 |
| S4 | 工作区注册（契约见 §5.2） | 服务配置 | 任意目录启动 | T3 |
| S5 | 创建会话时由客户端指定装配参数（需先定义信任边界、多端可见性与持久化） | 待定 | §5.9 中延后的会话装配参数 | T6 |

### 6.3 S3 字段与兼容方式

S3 只增加以下字段，客户端专用渲染只使用这些实际提供的字段；字段缺失时回退到通用视图：

| 视图 | 新增字段 | 用途 |
|---|---|---|
| `MessageView` | 结构化内容块列表（`text`、`thinking`），每块有界并带截断标记；`stopReason`；`usage`（输入/输出 token 与费用，可空）；`model` | thinking 显示、footer 用量、错误展示 |
| `ToolView` | 有界参数摘要：`read` 路径与行范围、`write` 路径与字节数、`bash` 命令、`ls`/`grep`/`find` 查询条件；`edit` 的路径与有界 old/new 文本对（超限标记截断）；`startedAt`/`finishedAt` | 工具专用渲染、耗时；edit diff 仅在 old/new 未截断时展示，否则只显示路径与替换数 |
| 历史展示条目 | 摘要类条目（compaction、branch summary、custom message）的类型与有界文本 | 摘要类视图 |

兼容规则：

- 以上都是 `schemaVersion` 1 内的**可选字段**；旧客户端忽略未知字段，新客户端对缺失字段降级为通用视图。
- 新增 `EventType` 或改变已有字段语义，需要在 `/capabilities` 中声明能力，客户端只在声明存在时启用。
- 客户端遇到无法解析的事件时：停止该订阅的自动重连，重新取一次快照；如果仍无法解析，就报告协议不兼容并停在只读状态，不无限重连、反复撞到同一个事件。

## 7. 测试策略

### 7.1 VirtualTerminal

`jcode-tui` 测试支持中实现最小 VT 模拟器（屏幕网格、scrollback、光标、SGR），只接受渲染器会产生的序列，遇到未知序列即失败。

### 7.2 分层测试

- **`jcode-tui`**：
  - 字素与列宽矩阵（§4.5）、控制字符处理；
  - 字节序列 → `KeyEvent` 表；
  - 渲染矩阵（含冻结区与重放上限）、焦点优先级、能力降级；
  - 组件与 Editor 行为矩阵。
- **`jcode-client`**：
  - 协议层用 JDK `HttpServer` 桩服务测试解析、重试、错误映射；
  - 集成层在 test scope 使用 `jcode-server` 测试服务与假模型，走真实 HTTP/SSE；
  - 自动拉起用临时配置目录和真实子进程测试。
- **`jcode-cli`**：VirtualTerminal + 脚本输入 + 测试服务的端到端测试。
- **服务补口 S1～S5**：在各自模块内补契约测试，并保留阶段 A、B 回归。
- 默认测试不访问网络、不需要真实凭证、不依赖真实 TTY；真实终端兼容性与“关闭终端窗口”用手工检查清单记录。

### 7.3 首期优先验收场景

| 场景 | 要证明的结果 |
|---|---|
| UI 暂时处理不过来 | 客户端内存不无界增长；业务事件不被渲染合并丢掉；必要时由服务端触发重同步 |
| 终态事件先于提交的 HTTP 响应返回 | 界面不从 completed 回退为 accepted 或 running |
| 历史加载期间持续生成消息 | 连接后立即可以审批与取消；历史与实时不重复、不覆盖 |
| 切换会话后旧请求返回 | 快照、分页、命令与审批回调都不能污染新连接 |
| 取消后恢复输入草稿 | 只处理确认未应用的输入；不重投 pending 或待核对输入；不覆盖新草稿 |
| print 遇到审批或截断 | 按 §5.8 退出码结束；stdout 不伪装成完整成功结果 |
| 实例重启后重连 | 不自动重提不确定的写命令；显示“结果待确认” |
| TUI 自动启动服务后退出 | 原服务与 Run 继续存活；多个客户端并发启动不重复拉起 |
| 并发注册同一工作区 | 返回同一 `workspaceId`；持久化失败不返回成功 |

## 8. 分阶段交付与任务拆解

### 8.1 拆分原则

1. 一个任务对应一个可独立合并的 PR，`mvn verify` 通过，测试随代码交付。
2. 按依赖层切分；服务端补口与客户端任务分开，服务端改动单独评审。
3. 不预建空模块：`jcode-tui` 在 T1.1、`jcode-client` 在 T3.1、`jcode-cli` 在 T3.3 随真实代码创建。
4. 先显式连接后自动拉起：T3.3 只依赖 T3.1，自动拉起与 S4（T3.2）在显式连接的纵向验证之后接入。
5. 高风险点单列：终端恢复（T1.5）、自动拉起（T3.2）、事件接入与背压（T3.4）。

### 8.2 里程碑

| 里程碑 | 包含 | 不作为前置 | 完成门槛 |
|---|---|---|---|
| **C-MVP：可实际接管的 TUI** | T1.1～T1.5；T2.1；T2.2 的默认键位与匹配；T2.3；T2.4；T3.1～T3.7 | paste marker（T2.5）、kill ring、Kitty 协议与用户键位覆盖、自动补全（T2.6）、主题探测、工具专用视图、全部 T4/T5 任务 | §7.3 各场景通过；在开启审批的服务上完成“提交 → 审批 → 退出 → 接管” |
| **C-Enhanced：完整日常交互** | T2.2 其余部分、T2.5、T2.6；T4.1～T4.4；T5.1～T5.7 | T6 已明确延后的功能 | 15 个命令的端到端测试；S1/S2/S3 契约测试 |

### 8.3 任务拆解

#### T1 终端与渲染基础（`jcode-tui`）

| 任务 | 内容 | 前置 |
|---|---|---|
| T1.1 模块骨架与文本工具 | 创建 `jcode-tui`、根 POM 固定 JLine、Enforcer、模块 `AGENTS.md`；字素、列宽、`AnsiText`、`Style`/`Color`/`ColorMode`、`TerminalTextSanitizer` | — |
| T1.2 Terminal 抽象与 VirtualTerminal | `Terminal` 接口、能力描述；测试用 VT 模拟器 | T1.1 |
| T1.3 差分渲染器 | `Component`/`Container`/`Text`/`TruncatedText`/`Spacer`/`Box`；渲染合并；冻结区与活动区、重放上限；同步输出（可关闭）、行宽校验、`stop()` 顺序 | T1.1、T1.2 |
| T1.4 输入解析 | `InputBuffer`、legacy `KeyParser`、`KeyEvent`/`KeyId`；焦点路由与优先级 | T1.3 |
| T1.5 JLine 终端 | `JLineTerminal`、能力检测与 provider fallback；shutdown hook 与 finally 恢复；Ctrl+Z 挂起/恢复 | T1.3、T1.4 |

#### T2 交互组件（`jcode-tui`）

| 任务 | 内容 | 前置 |
|---|---|---|
| T2.1 焦点与 Overlay | `Focusable`、`CURSOR_MARKER`；overlay 定位、焦点栈、合成 | T1.3 |
| T2.2 Keybindings | 动作注册、默认键位与匹配（C-MVP）；用户覆盖、冲突诊断、Kitty 键盘协议（C-Enhanced） | T1.4 |
| T2.3 基础交互组件 | `Input`、`SelectList`、`SettingsList`、`Loader` | T2.1、T2.2 |
| T2.4 Editor 核心 | 文本模型、换行与光标映射、Ctrl+J 换行、编辑与按词操作、历史、undo、滚动、IME 光标 | T2.1、T2.2 |
| T2.5 Editor 粘贴与 kill ring | paste marker 与 registry、undo 联动；kill ring；跳转字符 | T2.4 |
| T2.6 自动补全 | `AutocompleteProvider`、请求序号与取消、补全列表、`PathCompletionProvider` | T2.3、T2.4 |

#### T3 客户端纵向切片

| 任务 | 内容 | 前置 |
|---|---|---|
| T3.1 协议客户端与显式连接 | 创建 `jcode-client`；`jcode-server` 发布 test-jar；`connect(dataDirectory)`、鉴权、命令身份与重试、错误映射；SSE 解析；`ObservedSession`（快照、游标、连接代次、串行 executor、读取线程背压、回执不回退、实例变化不重提） | — |
| T3.2 自动拉起与工作区注册 | 客户端：启动协调锁、默认 `server.json`、三路标准流重定向、脱离终端会话、就绪等待、cwd 工作区解析；服务端 S4（显式配置路径、可变注册表、幂等、串行、先持久化后发布）；`jcode server start|stop|status`；`jcode-dist` 打包 | T3.1、T3.3 |
| T3.3 jcode-cli 骨架与 print 模式 | 创建 `jcode-cli`；`Main`、参数框架、`--config-dir`、`--no-start`；新建/`-c`/`--session`；§5.8 的全部规则；stdin、`@文件` | T3.1 |
| T3.4 事件接入与 UI 线程 | UI executor 接入 `ObservedSession`、线程断言；纯文本消息、通用工具视图、运行状态、基础 header 与 footer | T1.5、T3.3 |
| T3.5 交互主循环 | 布局（header、转录、待处理输入、状态行、Editor、footer）；提交路由（prompt / steer / follow-up）；焦点优先级下的 Esc 取消；输入状态展示与 `NOT_APPLIED` 恢复草稿；退出与后台提示 | T2.4、T3.4 |
| T3.6 审批对话框 | 审批弹窗、允许/拒绝本次、多客户端竞争后的同步关闭 | T2.3、T3.5 |
| T3.7 历史与接管 | §5.4 的连接顺序与合并规则、最近一页与按需向上加载；启动会话选择器（`-r`）；连接已托管会话时从快照恢复运行、输入与审批 | T2.3、T3.5 |

#### T4 富渲染

| 任务 | 内容 | 前置 |
|---|---|---|
| T4.1 协议粒度（S3） | §6.3 的字段、能力声明与兼容测试 | —（服务端，可并行） |
| T4.2 主题 | `Theme` token、dark/light/auto、OSC 11 探测与降级；`--use-theme`、`--verbose` | T3.5 |
| T4.3 消息视图 | thinking 块与 Ctrl+T、错误与中止、摘要类条目；header 的资源与诊断详情 | T3.7、T4.1、T4.2、T5.2 |
| T4.4 工具视图 | 按 §6.3 实际字段的 7 个内置工具渲染与通用渲染、条件性 edit diff、耗时、Ctrl+O | T3.7、T4.1、T4.2 |

#### T5 命令与选择器

| 任务 | 内容 | 前置 |
|---|---|---|
| T5.1 服务维护与资源命令（S1） | 维护操作的 `operationId` 与冲突语义；资源命令运行（`RunCommand` 展开选项） | —（服务端，可并行） |
| T5.2 服务查询与配置写入（S2） | 只读查询（含会话名）；settings 与信任写入 | —（服务端，可并行） |
| T5.3 命令框架与补全 | 命令注册与提交路由；`/`、`@`、Tab 补全；`/name` `/session` `/copy` `/export` `/hotkeys` `/debug` `/quit` | T2.6、T3.5、T5.1、T5.2 |
| T5.4 选择器与会话命令 | `/model` + Ctrl+L、`/thinking` + Shift+Tab、`/new`、`/resume`、`/compact`、`/reload`；`--model`、`--thinking`、`-n`、`--list-models` | T2.3、T3.7、T5.3 |
| T5.5 输入交互增强 | 待处理输入区的多条浏览与选择恢复；他端输入的状态与来源展示 | T3.5 |
| T5.6 配置与信任 | `keybindings.json`、`tui.json` 与 `/reload`；启动信任选择器与 `/trust`；Ctrl+G 外部编辑器 | T2.2、T5.3 |
| T5.7 设置面板 | `/settings`：服务端 Agent 设置与本地界面设置 | T2.3、T5.6 |

### 8.4 依赖关系与并行

```text
tui 主线     T1.1 → T1.2 → T1.3 → T1.4 → T1.5
组件         T1.3 → T2.1 ─┐
             T1.4 → T2.2 ─┴→ T2.3、T2.4 → T2.5
                               T2.3 + T2.4 → T2.6
客户端主线   T3.1 → T3.3（与 T1/T2 并行）→ T3.2
             T1.5 + T3.3 → T3.4 → (+T2.4) T3.5 → (+T2.3) T3.6、T3.7
服务端补口   T4.1、T5.1、T5.2（与任意阶段并行）
富渲染       T3.5 → T4.2；T3.7 + T4.1 + T4.2 → T4.4；再 + T5.2 → T4.3
命令         T2.6 + T3.5 + T5.1 + T5.2 → T5.3 → T5.4、T5.6 → T5.7
输入交互     T3.5 → T5.5
```

- 建议起点：**T3.1（协议客户端与显式连接）与 T1.1～T1.3 并行**，尽早跑通“输入一条 prompt → 处理审批 → 退出 → 接管”。
- 关键路径：T1.1 → … → T1.5 → T3.4 → T3.5 → T3.6/T3.7。
- C-MVP 在 T3.6 与 T3.7 都完成时达成；只完成 T3.7 而没有审批能力，不算可用。

每个阶段开工前另写详细实施计划，放在 `docs/plans/`，完成后归档。需要 GitHub 跟踪时按 [Issue tracker](../agents/issue-tracker.md) 的 Wayfinder 约定建立 map 与子 issue。

## 9. 有意差异

- **TUI 是服务客户端**：pi 的交互模式在同一进程内驱动 Agent；Jcode 按多端架构让 TUI 与 WebUI/Desktop 平等，换取退出不中断与多端接管。
- **不承诺撤回 pending 输入**：pi 的队列在同一进程内可以直接取回；Jcode 的输入由内核权威结算，首版只恢复确认未应用的输入。
- **首版只做 main-screen**：constrained layout 只对 alt-screen 成立，不给 main-screen 伪造 sticky 语义。
- **不提供扩展 UI API**：Jcode 扩展是显式 Java 实例，服务端尚无装配方案，扩展命令与 UI 另立方案。
- **客户端偏好本地保存**：主题、快捷键不进入服务或协议，与上位设计“草稿、滚动位置和面板布局各自保存”一致。
- **不做的周边能力**：OAuth、分享、遥测、更新检查、包管理、彩蛋、LaTeX/Mermaid、原生剪贴板 addon、OKLCH 色彩系统。

## 10. 风险

| 风险 | 处置 |
|---|---|
| 自动拉起的服务随终端关闭被连带终止 | 三路标准流重定向 + 脱离终端会话；“退出 TUI”与“关闭终端窗口”分别验收；未验证平台要求显式启动 |
| 并发启动多个客户端导致重复拉起 | 客户端启动协调锁 + 锁内复查；服务 `server.lock` 保留最终排他 |
| 本地可信注册被滥用 | Token 文件仅所有者可读；注册不授信项目配置；文档明确“持有 Token 即本机完全信任” |
| 客户端处理慢导致本地积压 | 读取线程背压，本地无无界队列；由服务端重同步兜底 |
| 历史与实时视图重复或回退 | §5.4 合并规则 + 连接代次 + 回执不回退；§7.3 专门场景 |
| 协议字段演进导致旧客户端卡死 | §6.3 兼容规则；无法解析时停在只读状态，不无限重连 |
| 长会话重绘开销 | 冻结区 + 重放上限；首期只加载最近历史 |
| JNI provider 在某些平台不可用 | fallback 到 exec（`stty`）；Windows 原生终端放到 T6 |
| 崩溃后终端残留 raw mode | shutdown hook + finally + 固定 stop 顺序，专门测试 |
| 服务文本注入控制序列 | §4.5 的文本清理，保留换行与 Tab |

## 11. 决策记录

### 11.1 已确认

**2026-09-27 讨论：**

1. **运行模型**：TUI 与 `-p` 只做 `jcode-server` 的客户端；没有服务时自动拉起后台服务。
2. **模块**：`jcode-tui`（零依赖界面库）、`jcode-client`（Java 客户端 SDK）、`jcode-cli`（入口）。
3. **工作区**：本地可信注册，Token 鉴权的注册接口，cwd 自动注册并写回 `server.json`，不自动授信。
4. **终端底层**：JLine，只用 `jline-terminal`，经自有 `Terminal` 接口隔离。
5. **配置目录**：`~/.jcode`，可由 `$JCODE_HOME` 或 `--config-dir` 覆盖。
6. **工具审批**：默认不询问（服务 `approvalTools` 默认为空，与 pi 一致）；服务开启审批时 TUI 必须能处理审批。
7. **Markdown**：首版不做，延后到 T6。
8. **print 模式**：保留，经服务运行。
9. **首版斜杠命令**：§5.10 的 15 个；`/tree`、`/fork`、`/clone`、`/import`、HTML 导出、`/login`、`/logout`、`/scoped-models`、扩展命令延后到 T6。

**`33ac240` 审阅修订：**

10. **补充输入**：不承诺撤回 pending 输入；只有确认 `NOT_APPLIED` 的输入可以由用户显式恢复为草稿；取消不触发回填（§5.6）。
11. **状态所有权**：`jcode-client` 的 `ObservedSession` 是快照、游标与历史合并的唯一所有者，在调用方提供的串行 executor 上归约；读取线程背压；回执不回退状态；连接代次隔离；实例变化不自动重提（§5.3）。
12. **连接顺序**：先恢复活动状态再异步加载历史，按 §5.4 的规则合并。
13. **print 模式规则**：stdout/stderr 分离；审批时退出码 4 并保留任务；截断时退出码 5；只取消本次 Run；§5.8 的退出码表。
14. **先显式连接后自动拉起**：`connect(dataDirectory)` 与 `--no-start` 先交付；实例路径从配置解析；启动锁与服务锁分离；三路标准流重定向；S4 采用 §5.2 的注册契约。
15. **服务补口分类**：只读查询、Session 维护、用户配置写入、资源命令运行分开处理；S3 字段与兼容规则见 §6.3。
16. **终端规则**：Ctrl+J 换行；焦点优先级；字素与列宽分开测试；能力可检测与降级；冻结区与重放上限；控制字符处理保留换行与 Tab。
17. **里程碑**：C-MVP 与 C-Enhanced（§8.2）；C-MVP 必须包含审批。
18. **测试依赖**：Enforcer 约束生产依赖；客户端模块可在 test scope 依赖 `jcode-server` 及其 test-jar。

**2026-09-27 待确认项结论（均按建议）：**

19. **会话装配参数**：首版不做，延后到 T6 与 S5 一起设计。
20. **`--no-session`、`--session-dir`**：删除。
21. **服务管理子命令**：提供 `jcode server start|stop|status`，放在 T3.2；`stop` 不强制取消运行中任务。
22. **发行形态**：新增纯打包模块 `jcode-dist`，产出 `bin/` + `lib/` 发行目录；开发期用 `JCODE_SERVER_JAR`。
23. **print 等待审批**：首版不做，遇到审批按退出码 4 结束。
24. **完整输出读取**：首版不做，C-Enhanced 阶段视需要再定；截断按退出码 5 结束。

### 11.2 待确认

暂无。
