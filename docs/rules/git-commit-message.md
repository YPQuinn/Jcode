请遵循 Conventional Commits 规范，格式如下：
<type>(<scope>): <subject>
<body>

其中：
- type 必须从以下类型中选择：
    - feat：新增功能
    - fix：修复问题
    - docs：文档变更
    - style：代码格式调整，不影响逻辑
    - refactor：代码重构，非新增功能或修复问题
    - perf：性能优化
    - test：测试相关变更
    - build：构建系统或依赖变更
    - ci：CI/CD 配置变更
    - chore：其他杂项变更
    - revert：回滚提交

生成要求：
1. subject 简洁概括本次变更，控制在 50 字以内。
2. scope 根据变更模块自动判断，如 api、auth、ui、db、config、docs 等；无法判断时可省略 scope。
3. body 可选。如果变更较复杂，请用 1-3 条简短 bullet 说明主要改动。
4. 不要夸大变更范围，不要添加未体现的内容。
5. 如果同时包含多个独立变更，请优先概括主要变更。
6. 只输出最终 commit message，不要解释原因，不要添加额外说明。
7. 全部使用英文。