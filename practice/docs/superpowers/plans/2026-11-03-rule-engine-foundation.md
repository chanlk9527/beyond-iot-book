# Rule Engine Foundation Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans; track completed work in ../../rule-engine-implementation.md.

**Goal:** 交付 JetLinks 风格的规则图和本地运行基础，明确生产能力尚未交付。
**Architecture:** JDK-only rule-component owns graph, validation, executor SPI and deterministic evaluation. Managers and bootstrap wiring follow later.
**Tech Stack:** Java 21, Maven, JUnit Jupiter.
**Spec:** ../../rule-engine-implementation.md；用户已授权开始分阶段实施。

## Global Constraints

- 不改写现有 EventBus，不引入管理模块反向依赖。
- 不覆盖用户已有修改；本次不自动提交。
- 本地文本使用严格 UTF-8 File.ReadAllText 或 rg 读取。

## Review Focus

重复/悬空/不可达图；不合法配置触发副作用；分支相互污染；数字与缺失字段比较；失败后兄弟分支隔离。均纳入测试。

## Task 1: 图和运行基础

- [x] 新建 rule-component/pom.xml、src/test/java/com/nebula/iot/rule/LocalRuleEngineTest.java；登记 components reactor 和父依赖管理。
- [x] 测试 RuleModel/RuleNode/RuleLink、RuleData、Condition、ExecutionContext、TaskExecutorProvider/TaskExecutor、LocalRuleEngine 和 RunResult 契约；先观察缺少类型导致编译失败。
- [x] 在 src/main/java/com/nebula/iot/rule/ 实现上述契约。validate(model) 无执行副作用；execute(model, tenantId, runId, eventId, input) 返回 RunResult。
- [x] 验证 `mvn -pl platform-components/rule-component -am test`：11 个契约测试通过。

## Task 2: 文档与审核

- [x] 添加组件 README，更新产品文档和架构文档中的实际交付范围。
- [x] 独立审核边界、失败语义、可变数据和测试遗漏；补充测试并回归。
- [x] 记录完成项、命令结果、仍缺失能力；不将组件测试当作生产链路验收。
