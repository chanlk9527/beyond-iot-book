# Rule Engine Calculation Implementation Plan

**Goal:** 在第一阶段节点模型上交付组合条件、内置计算节点、场景/流转转换和安全样例运行。
**Spec:** ../../rule-engine-implementation.md
**Architecture:** 继续使用 JDK-only rule-component；业务配置是计算输入契约，不在组件中实现管理或授权。转换结果包含来源范围与 RuleModel。样例 API 只使用内置计算/预览执行器，无用户提供的副作用执行器。

## 决策与约束

- 条件树采用 sealed RuleCondition；已有 Condition 为叶子，ConditionGroup 为 AND/OR。禁止空组，深度最多 16、总项最多 256；JSON 配置只接受已知字段。
- 映射为输出投影：顶层目标字段，可读取点路径、固定 JSON 值、数值 multiplier/offset。缺失字段和错误类型失败，不隐式转数字、不回退原样输出；空映射在流转业务中表示关闭映射。
- SourceScope 从可信事件元数据匹配租户/产品/设备/消息类型，不从 payload 提取，不替代授权。没有配置资源范围不能发布样例配置。
- 场景动作本阶段为独立分支，无顺序/延时；转换生成 action-preview 节点。预览结果明确是拟执行动作，不代表持久任务或投递成功。
- 样例测试 API 指 Java API；HTTP 路由、前端原型接线与权限一并归第三阶段，避免暴露未授权试运行端点。

## Tasks

- [x] 新增 StageTwoTest：组合条件、映射、范围拒绝、流转多目标、场景动作、配置错误、安全预览；运行观察失败。
- [x] 实现 RuleCondition/ConditionGroup、内置节点解析和映射；保证原测试通过。
- [x] 实现 AutomationDefinition/AutomationCompiler 与 RuleSampleService，验证来源范围和动作预览。
- [x] 运行 rule-component 全部测试、独立审核实现、修复预览深度边界、更新 README 和实施记录，记录仍缺失能力。最终 25 tests 全部通过。

验证命令：`mvn -B -pl platform-components/rule-component -am test`。本机使用正常用户进程，原因见第一阶段记录。
