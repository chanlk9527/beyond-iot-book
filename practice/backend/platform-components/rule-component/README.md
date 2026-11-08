# Rule Component

JetLinks 风格的节点/连线模型与执行器扩展基础。仅依赖 JDK 21；模型和运行时由本项目实现，没有复制 JetLinks 源码，也没有引入其运行依赖。

详细设计、取舍、完成状态及后续任务统一维护在[实施记录](../../../docs/rule-engine-implementation.md)。

## 当前能力

- `RuleModel`、`RuleNode`、`RuleLink`：一个入口，最多 256 节点、255 连线；支持分支，拒绝合流和循环。出边顺序遵循模型中的连线顺序，运行采用广度优先顺序。
- `TaskExecutorProvider`：唯一类型注册、配置校验、为每个节点/运行创建执行器；`BuiltinExecutors.providers()` 提供 identity、filter、mapping、action-preview 四种纯计算节点，没有生产 IO 节点。
- `TaskExecutor`：同步计算，返回零或一个 `RuleData`。空输出停止当前分支，运行异常产生 FAILED 记录，其他分支继续。
- `RuleCondition`：`Condition` 叶子支持对象点路径、EQ/NE/GT/GE/LT/LE；`ConditionGroup` 支持嵌套 AND/OR，最多 16 层、256 项且不允许空组。数字按数值比较，排序仅支持数字，缺失字段不命中。显式 null 可以参与 EQ/NE；点号键和数组索引暂不支持。
- `RuleData` 和节点配置递归复制 JSON 值，支持 null，拒绝非有限数字、任意对象及超深嵌套。
- `ExecutionContext`：可信租户、运行、事件、规则版本和节点身份。身份校验与整个图的校验都早于执行器创建/执行。
- `RunResult.successful()` 仅表示没有节点计算失败；全部过滤也返回 true，不表示规则命中或外部业务成功。未访问节点没有记录。

## 使用示例

调用方注册 `TaskExecutorProvider`，其中 `type()` 返回 `identity`、`validate` 拒绝非空配置、`create` 返回 `(context, input) -> Optional.of(input)` 后：

```java
var model = new RuleModel("rule-1", 1, "source",
    List.of(new RuleNode("source", "identity", Map.of()),
            new RuleNode("hot", "identity", Map.of())),
    List.of(new RuleLink("source", "hot",
            new Condition("temperature", Condition.Operator.GT, 30))));
engine.validate(model);
var result = engine.execute(model, "tenant-1", "run-1", "event-1",
    new RuleData(Map.of("temperature", 35)));
```

可运行的完整用例见 `src/test/java/com/nebula/iot/rule/LocalRuleEngineTest.java`。

## 第二阶段：计算、转换与样例

`ConditionConfiguration` 在结构化条件与 JSON 对象之间转换。叶子格式为 `{"field":"temperature","operator":"GT","expected":30}`；组合格式为 `{"operator":"AND","children":[...]}`。未知字段、缺少 expected、空组、过深/过大条件树均拒绝。

内置节点配置：

| executor | configuration | 行为 |
| --- | --- | --- |
| identity | `{}` | 原样输出 |
| filter | `{"condition":{...}}` | 不命中返回空输出 |
| mapping | `{"fields":[{"target":"tempF","source":"temperature","multiplier":1.8,"offset":32},{"target":"label","value":"sensor"}]}` | 投影输出；原始其他字段不会透传 |
| action-preview | `{"id":"action-1","kind":"connection","target":"connection-1","parameters":{}}` | 保留原载荷，由样例服务关联节点配置形成 PreviewAction，不发送 |

映射的目标是唯一的顶层键，来源为点路径；支持固定 JSON 值（含 null），数字换算采用 BigDecimal，规则为 `value * multiplier + offset`。缺失来源或换算非数字会使该节点失败，后续动作不执行。未配置 multiplier/offset 时原样保留类型；显式配置换算参数要求来源为数字。

`AutomationDefinition.Flow` 表达条件、映射和连接引用；`Scene` 表达条件和独立动作列表。`AutomationCompiler` 生成 source → 可选 filter → 可选 mapping → 多个 action-preview 分支，同时保留独立 `SourceScope`。空映射表示关闭映射，不等于 mapping 节点允许空 fields。所有配置版本要求大于零，是计算输入，不是数据库草稿实体。

```java
var scope = new SourceScope("tenant-1", Set.of("product-1"), Set.of(), Set.of("property"));
var flow = new AutomationDefinition.Flow("flow-1", 1, scope,
    new Condition("temperature", Condition.Operator.GT, 30),
    List.of(FieldMapping.field("tempF", "temperature", new BigDecimal("1.8"), new BigDecimal("32"))),
    List.of("connection-1", "connection-2"));
var result = new RuleSampleService().test(AutomationCompiler.compile(flow),
    new SourceScope.EventSource("tenant-1", "product-1", "device-1", "property"),
    "sample-run-1", "sample-event-1", new RuleData(Map.of("temperature", 35)));
// result.status() == MATCHED; 两个动作的 payload 都含 tempF=95.0，没有真实投递。
```

SourceScope 必须指定租户、消息类型和至少一个产品/设备范围；同时给产品和设备时取交集。EventSource 必须由可信接入层提供，payload 中的身份字段不参与匹配。这不是权限校验，连接/模板/设备是否存在、是否授权需由后续 manager 验证。

样例服务仅注册上述内置执行器，不允许注入真实 IO 执行器。状态为 OUT_OF_SCOPE（未运行）、FILTERED（无拟执行动作）、MATCHED（产生拟执行动作）、FAILED（计算失败）。FAILED 可携带其他独立分支的预览动作，仍未发送。完整运行记录含输入/输出数据，不能直接作为公开日志。

这是 Java 样例 API；HTTP API 与前端原型接线归第三阶段。场景当前仅支持独立 device/notification/connection 动作预览；delay、顺序执行、手动触发和时间条件仍待后续实现。动作参数仅验证 JSON 结构，不声称已通过设备物模型或通知模板校验。可运行用例见 `StageTwoTest.java`。

## 尚未交付

管理 API、规则持久化与发布、EventBus 订阅、worker 装配、持久动作意图、事务去重、超时取消、重试及外部投递均未实现。禁止将本地同步执行器直接用于生产网络 IO；SPI 不承诺副作用只发生一次。配置创建失败整体抛出，执行过程中 RuntimeException 转为通用 EXECUTOR_FAILED，不回传异常文本。后续需要受控诊断渠道。

## 验证

在 `practice/backend` 执行：

```powershell
mvn -B -pl platform-components/rule-component -am test
```

本机沙箱 Maven 测试编译可能看不到已生成的类，正常用户进程可通过；这是进程文件视图差异，不能据此判断源码损坏，也不需要改编码或调整 POM。
