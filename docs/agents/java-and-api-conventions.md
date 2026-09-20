# Java 与 API 约定

## Java 风格

- 目标版本是 Java 21；在能提升清晰度时使用 record、sealed interface、模式匹配和虚拟线程 executor。
- 优先使用显式、可读的 API，不使用隐藏注册或基于反射的装配。
- 集合输入必须防御性复制，通常在 compact constructor 中使用 `List.copyOf()`。
- 公开类型保持不可变，并在构造边界验证不变量。
- 外部依赖版本统一固定在根 POM，不使用版本范围。

## 命名

按职责统一使用已有后缀：

- `*Config`、`*Loop`、`*State`、`*Result`
- `*Source`、`*Sink`、`*Mode`
- `*Request`、`*Client`、`*Spec`、`*Signal`、`*Validator`

## 注释

关键类、接口和方法必须有精炼准确的英文 Javadoc。不要用注释复述代码；应说明契约、不变量、所有权和不直观的失败行为。

## API 边界

- `ai` 只使用 provider-neutral 类型；provider wire 名称和 dialect-specific 状态属于 adapter。
- JSON 形状的公共边界使用 Jackson `JsonNode`，具体工具通过共享 `ObjectMapper` 转换为强类型参数。
- 公开快照不得泄漏可变内部状态。
