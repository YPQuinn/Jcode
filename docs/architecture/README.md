# Jcode 架构解读文档集

本文档集采用 [mdBook](https://rust-lang.github.io/mdBook/) 搭建，源码位于 `src/`，入口目录位于 `src/SUMMARY.md`。

## 本地使用

```bash
# 安装 mdBook
cargo install mdbook

# 构建静态站点
mdbook build docs/architecture

# 本地预览，默认监听 http://localhost:3000
mdbook serve docs/architecture --open
```

构建产物输出到仓库根目录下的 `target/mdbook/architecture/`，不会纳入版本管理。

## 目录结构

```text
docs/architecture/
├── AGENTS.md           # 架构文档写作规则
├── README.md           # mdBook 使用说明
├── book.toml           # mdBook 配置
├── theme/
│   └── custom.css      # HTML 输出样式覆盖
└── src/
    ├── SUMMARY.md      # mdBook 目录入口
    ├── index.md
    └── 00-overview.md
```

## 写作约定

- 正文使用中文，代码标识符、模块名、类名、协议名保留英文原名。
- 每篇文档优先回答三个问题：解决什么问题、核心抽象是什么、哪些边界不能破坏。
- 架构解读必须以当前源码为准；历史方案可引用 `docs/plans/archived/`，但不能作为当前实现依据。
- 新增文档时保持数字前缀顺序，并同步维护 `src/SUMMARY.md`。
