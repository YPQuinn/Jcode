# 开发与 Git 工作流

## 实施前

开始新功能前，先阅读两个本地参考仓库：

- `docs/references/pi`
- `docs/references/pi-book`

如果缺失，将其克隆到 `docs/references/`：

```bash
git clone https://github.com/earendil-works/pi.git docs/references/pi
git clone https://github.com/ZhangHanDong/pi-book.git docs/references/pi-book
```

参考其设计意图，但必须适配本仓库的 Java 架构，不得照搬实现。

任务涉及 issue 时，按需遵循 [GitHub issue tracker](issue-tracker.md)、[triage labels](triage-labels.md) 与 [domain documentation](domain.md) 规则。

## 方案与文档

- 进行中的方案放在 `docs/plans/`。
- 已实现或放弃的方案移动到 `docs/plans/archived/`。
- GitHub Issues 是权威 issue tracker；`docs/issues/` 只保存本地分析或未发布草稿。
- 改动影响架构、行为、命令或长期约定时，更新最近的模块 `AGENTS.md` 和相关主题指南；不要仅为记录日常实现历史而修改指令文件。

## 提交

- 任务完成后询问用户是否需要提交改动。
- 提交前确认受影响的指令文档仍然准确。
- Commit message 必须遵循 [`docs/rules/git-commit-message.md`](../rules/git-commit-message.md)。
- 不得把无关工作区改动纳入提交。
