# Kafka EventBus

## 已实现的边界

`EVENTBUS_MODE=local` 保持单 JVM 开发方式；`kafka` 启用跨进程事件发布／订阅。已实现 JSON 编码、具名共享消费组、节点广播、业务主题过滤、发布确认、处理超时、有界重试和失败事件保留。设备属性订阅和实时通知入口已接入。

这次交付是集群消息传输基础，不是整个平台集群验收。独立运行入口与现有会话基础见 [三服务说明](platform-services/README.md)；跨节点命令闭环已在显式cluster-commands模式完成独立专项验收；通用事务交付、影子可靠发布、共享登录／实时票据和完整跨节点缓存撤销仍待完成。数据库／Redis 更新与 Kafka 发布不是共同事务；Kafka producer 幂等也不等于业务幂等。

## 主题和订阅规模

默认只有两个物理 topic：

| Kafka topic | 内容 |
| --- | --- |
| `iot.platform.events.v1` | 平台业务事件，保留原始业务主题 |
| `iot.platform.events.failed.v1` | 无法处理的原始消息及失败上下文 |

`device/d001/property/report` 等是消息信封中的业务主题，不会变成 Kafka topic。分区 key 是租户 ID 长度、租户 ID 与设备 ID 的无歧义组合。同一设备进入同一分区；分区数改变、多个生产者并发和失败重试下仍需业务版本校验，不承诺事件发生时间有序或全局有序。

Kafka 消费者按服务订阅建立，不按设备、用户或 WebSocket 连接建立。每个 EventBus 最多允许 64 个服务订阅。当前实时网关的一个 `realtime-v1` 节点订阅覆盖全部本地客户端；设备授权与连接分发仍在网关完成。10 万个 App 订阅不会创建 10 万个 Kafka topic／group。

第一阶段实时通知采用各节点独立消费组读取事件并过滤本地连接，会随网关数量放大读取流量。尚未实现“单组 dispatcher + 设备到网关目录 + 定向转发”，也未完成 10 万在线连接压测。

## 服务代码

```java
// 同组实例分担分区；不同服务订阅使用不同名称。
Subscription shadow = bus.subscribe(
    SubscriptionSpec.shared("device-shadow-v1", "device/*/property/report"),
    event -> shadows.handle(event));

// 每个节点收到通知；同一订阅支持多个表达式，重叠表达式只调用一次。
Subscription selected = bus.subscribe(
    SubscriptionSpec.broadcast("selected-devices-v1", "device/d001/**", "device/d002/**"),
    event -> notifications.handle(event));

bus.publish(event).whenComplete((result, error) -> {
    // Kafka: BROKER_ACCEPTED 表示 broker 确认，不表示消费者已经完成。
    // Kafka: subscriberCount = -1，表示数量未知。
    // Local: LOCAL_COMPLETED 表示匹配回调的 CompletionStage 全部成功。
});

shadow.close(); // 停止服务消费者；不会删除 Kafka 的消费位点。
```

`*` 匹配一层，末尾 `**` 匹配零层或多层；也支持精确主题。匹配发生在消费者侧，Kafka 本身不按业务主题过滤。无关事件会推进该服务消费组的位点。

消费组名：`namespace.subscriptionName`；广播组名：`namespace.subscriptionName.node.nodeId`。同一共享组的所有实例必须保持相同主题集合和处理职责。修改范围需要规划消费位点和历史回放，必要时升级订阅名称；代码当前不提供跨节点订阅配置一致性注册表。不要让不同设备范围的处理器误用同一组。

Kafka 模式拒绝未命名的 `subscribe(pattern, callback)` 和优先级订阅，避免隐式创建不稳定消费组；已有两个业务订阅已迁移。Local 模式保留原调用方式。

## 编码与交付

- 信封包含 `schemaVersion=1`、明确 `payloadType` 和完整事件元数据。设备消息体使用固定别名，支持当前全部 `DeviceMessageBody` 类型和四种 `Value` 类型；普通载荷仅允许 JSON 对象及其 JSON 值。新增事件类型必须显式扩展 codec，不启用任意 Java 类名反序列化。
- Producer 强制 `acks=all` 和幂等生产。发布前编码失败、broker 无法确认等异常通过返回的异步结果表达；调用方须处理异常。副本安全程度仍由集群副本数、ISR 等配置决定。
- Consumer 关闭自动提交，逐条处理后提交位点；每次 poll 最多一条。每个服务订阅有独立的单线程处理器及一个等待槽，默认处理时限 30 秒（包括回调同步执行时间），可配置为不超过 60 秒。
- 失败默认再重试 3 次、间隔 1 秒；失败记录写入失败 topic 并确认后才能跳过。失败发布不成功则继续保留原消费位置并重试恢复。未知版本／类型和无法解码的记录同样保留原始 bytes；失败头包含原 topic、分区、位点、消费组及异常信息。
- 交付是至少一次。崩溃、位点提交失败、超时和恢复都可能造成重复；处理器须设计业务幂等。超时会尝试中断处理线程，但不能撤销外部副作用；忽略中断的处理器会占住其有界资源，应告警并人工处理。
- 失败 topic 是共享保留日志。重放时按原消费组定位，并设计目标组重放流程；直接重新发到公共事件 topic 会使其他业务组再次处理。当前未提供重放管理 API。
- 新共享组从保留范围内最早消息开始；新节点广播组从分配时的最新位置开始。已有组恢复已提交位点。广播订阅建立前可能漏实时通知，客户端需通过 ready／重连快照恢复；不要把实时通知用作业务事实来源。

## 本地启动

以下命令在仓库根目录执行，Docker Compose 样例为单节点开发环境：

```powershell
docker compose -f practice/infra/compose.kafka.yml up -d
docker compose -f practice/infra/compose.kafka.yml logs kafka-init

$env:EVENTBUS_MODE = 'kafka'
$env:EVENTBUS_NODE_ID = 'management-a'
$env:KAFKA_BOOTSTRAP_SERVERS = 'localhost:9092'
```

随后按后端 README 启动应用。另一实例设置不同的 `EVENTBUS_NODE_ID` 和 HTTP／MQTT 端口，并连接同一个 Kafka；节点 ID 同时运行时必须唯一，重启可保持稳定。相同环境使用同一个 `EVENTBUS_NAMESPACE`，隔离环境应使用不同 namespace 和物理 topic。

连接另一台机器时，开发编排需将 `KAFKA_ADVERTISED_HOST` 设置为客户端可访问的 broker 主机地址，并设置监听地址；客户端 `KAFKA_BOOTSTRAP_SERVERS` 指向该地址。生产环境使用正式 Kafka 的 SASL／TLS 和 ACL，通过 `spring.kafka.properties`、`spring.kafka.producer`、`spring.kafka.consumer` 配置，不使用该单节点 PLAINTEXT 样例作为高可用部署。

Kafka 模式启动会验证 broker 可访问且两个 topic 已存在，不会静默退回 LocalEventBus。生产 topic 应预先由运维创建，按吞吐、消费并行度、容量和保留窗口设定分区／副本／ISR；不按用户数设定分区数。消费组与分区规模的关系、消费者失败、lag、失败 topic 增长及生产异常都应纳入监控。

## 验证

```powershell
cd practice/backend
mvn -pl platform-components/message-component -am test
```

测试启动真实的嵌入式 Kafka KRaft broker，不依赖 Docker。覆盖编码与类型拒绝、多模式匹配、共享组两实例分担、节点广播、故障重试／失败保留、消费者离线后恢复，以及独立发布 JVM 到订阅 JVM 的类型化事件传递。跨物理机器、broker 多副本故障和大规模连接验收尚未完成。
## 三个独立服务

已提供 management-service、access-service、worker-service 三个可执行 JAR，复用现有业务装配并固定运行角色。构建、启动脚本、端口、共享依赖及跨进程验收见 [独立服务说明](platform-services/README.md)。原组合启动方式保留。部署边界已拆分，领域数据适配暂时共享；命令闭环需额外启用cluster-commands并按专项验收；通用事务交付与共享登录仍待完成。

Spring 装配下，Kafka 订阅在生命周期阶段统一启动，等待领域事件监听器注册完毕后再处理积压；脱离 Spring 直接构造时仍默认立即启动，可显式调用 deferSubscriptionsUntilStart() 后通过 start() 启动。该顺序由独立服务积压恢复测试覆盖。
