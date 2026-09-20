# 构建与测试

## 工具链

- Java release：21。
- 构建与依赖管理：系统 Maven（`mvn`）。
- 仓库没有 Maven Wrapper。
- 测试使用 JUnit 5，不使用 Mockito；小型显式测试替身放在 `support` 包。

## 命令

```bash
# 全 reactor 测试
mvn test

# 最终 reactor 校验，包含 Enforcer 规则
mvn verify

# 清理并安装到本地 Maven 仓库
mvn clean install

# 单模块及所需 reactor 依赖
mvn -pl ai test
mvn -pl ai-providers -am test
mvn -pl agent-core -am test
mvn -pl coding-agent -am test

# 检查 coding-agent 依赖
mvn -pl coding-agent dependency:tree
```

迭代时先运行最小相关测试；仓库级改动完成前运行 `mvn verify`。

## 测试职责

- 环境可运行性检查放在 `smoke/`。
- 保留针对流终止行为、取消、事件 backpressure、工具三阶段管道与并行排序的契约测试。
- 不为运行测试而增加伪 `main` 方法或 Spring Boot 入口。
- Native 本地工具 smoke test 及其显式 executable 参数见 [`coding-agent/AGENTS.md`](../../coding-agent/AGENTS.md)。

## 架构文档

修改 mdBook 时使用：

```bash
mdbook build docs/architecture
mdbook serve docs/architecture --open
```

同时遵循 [`docs/architecture/AGENTS.md`](../architecture/AGENTS.md)；新增章节时必须更新 `src/SUMMARY.md`。
