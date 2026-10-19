# Message Component

平台内部业务事件的发布／订阅边界，提供 `LocalEventBus` 与 `KafkaEventBus`。设备消息经协议解码、身份绑定和物模型校验后映射为 `EventMessage`。

- Local：同 JVM 按主题匹配、并发回调，默认等待回调结果；没有持久化。
- Kafka：共享事件 topic、租户与设备键分区；具名服务消费组、节点广播、业务主题过滤、明确 JSON 类型、发布确认、消费位点、重试及失败 topic。
- `SubscriptionSpec` 支持单设备、通配符和多主题。App 订阅仍在实时网关内管理，不创建用户级 Kafka topic 或 group。
- `PublishResult.confirmation()` 明确区分 `LOCAL_COMPLETED` 与 `BROKER_ACCEPTED`，后者不表示业务完成，订阅数量以 `-1` 表示未知。

完整配置、调用示例、交付语义和当前边界见 [Kafka EventBus](../../EVENTBUS.md)。EventBus 传输不替代 Outbox、业务幂等和跨节点设备会话路由。