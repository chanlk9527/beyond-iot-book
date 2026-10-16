# Platform Core

Java 21 的普通 JAR，主代码只依赖 JDK。这里定义多个能力组件与业务管理模块之间共享的 IoT 语义、值对象和扩展接口；没有 Spring Bean、数据库实体、控制器、网络监听或注册中心实现。

## 包结构

| 包 | 已实现内容 |
| --- | --- |
| identity | TenantId、DeviceId、ProductId、MessageId、CommandId；保留大小写与原始身份，不静默裁剪或自动生成编号 |
| device | 带租户的设备／产品键、设备描述、生命周期、连接状态、DeviceRegistry 与 DeviceOperator |
| model | 版本化产品模型、属性／事件／操作、字段与约束、不可变标量值、报告／读写／操作／事件校验 |
| command | 带有效期的控制意图及通用DeviceDownlink、DeliveryReceipt、属性／配置／校时／OTA下行与执行阶段 |
| message | 属性、事件、指令、信息、配置、校时、OTA上行与带replyTo／ReplyResult的信封 |
| auth | 设备认证请求、明确的通过／拒绝结果及异步认证接口 |
| protocol | 可扩展传输标识、协议 SPI、编解码接口、解码上下文和不可变二进制报文 |
| internal | 构造参数检查，仅供 core 内部使用，不是模块公共契约 |

Core 的类型是公共契约，不机械套用业务模块的五层目录。其他模块通过 Maven 显式依赖 `platform-core`，具体实现放在 components 或 manager；不能将业务管理模块依赖反向引入 core。

```xml
<dependency>
    <groupId>com.nebula.iot</groupId>
    <artifactId>platform-core</artifactId>
</dependency>
```

版本已在后端父 POM 的 dependencyManagement 中管理。继承父 POM不会自动获得 core 依赖。

## 当前模型能力

支持 BOOLEAN、INTEGER（long）、DECIMAL（BigDecimal）和 TEXT。数值范围为闭区间；文本长度按 Unicode 码点计算。DECIMAL 可接收整数值，INTEGER 不把小数或字符串自动转换为整数。

属性报告允许部分字段，但拒绝未知属性、不可报告属性、错误类型和越界数据。属性访问类型从平台读写角度定义，WRITE_ONLY 属性不作为可查询报告。事件和操作校验必填字段、未知字段和字段值；字段定义的索引键必须与标识一致。

模型关联产品键与版本。处理报告或下发前，需要由调用方核对设备描述的产品和模型版本，查找精确模型后校验。构造消息并不会自动执行模型查找或业务权限校验。

数组、对象、枚举、单位、复杂约束、模型兼容迁移及操作输出模式尚未实现，不能将当前类型称为完整物模型标准。物理效果与“执行完成”的证明条件仍由操作及实际协议约定。

## 不可混淆的语义

- DeviceKey 与 ProductKey 包含 TenantId；设备与产品必须同租户。创建标识或持有 DeviceOperator 不代表通过认证或有权访问资源。
- 生命周期与连接状态分别表达。ACTIVE 不表示在线，ONLINE 不表示可控；绑定、组织和空间关系由业务模块管理。
- MessageId 标识逻辑消息，CommandId 标识一次执行意图。重传保留相应关联；新收到报文时随意生成 ID 无法自动解决设备重传去重。
- DispatchReceipt 只表示传输接受工作，不表示设备收到或执行成功。DeviceOperator 的实现还需校验目标、生命周期、有效期和模型约束。
- ExecutionStatus 只表示设备反馈的 ACCEPTED／SUCCEEDED／FAILED。等待超时由命令服务单独记录，不被当成设备执行失败。
- occurredAt 与 receivedAt 分别记录来源时间和平台接收时间。设备时钟可能漂移，不强制来源时间早于接收时间，也不能只按时间戳判断跨重启消息顺序。
- 模型、消息与输入 Map 复制为不可修改快照；标量值不可变。BinaryPayload 防御性复制字节，ProtocolPacket 的日志表示不包含报文内容和属性值。

## 协议接入顺序

1. 具体协议将连接认证材料封装为 DeviceAuthenticationRequest 子类型。principal 和 tenantHint 都是未验证输入，不能直接作为可信设备身份；材料的生命周期与日志脱敏由子类型及认证组件负责。
2. DeviceAuthenticator 验证证明材料与设备策略，返回 Accepted(DeviceKey) 或 Rejected(code)。基础设施错误通过 CompletionStage 异常完成；认证成功不代表用户或应用资源授权。
3. 接入组件查找设备描述和对应模型，创建 DecodeContext，并将原始报文交给该传输对应的 DeviceMessageCodec。
4. Codec 返回 DecodedMessage，不能指定租户、设备或平台接收时间；接入组件用 context.bind 补齐统一信封，再做模型校验和路由。
5. 连接变化由可信会话组件生成，设备解码结果不能生成 ConnectionChanged。网关代理子设备需要额外校验代理与子设备关系，当前不提供绕过关系校验的直接构造机制。

Decoder 是同步的本地映射接口，禁止在其中执行阻塞网络／存储 I/O。注册查询、设备操作与认证使用 JDK CompletionStage，具体执行器、线程调度和超时策略由实现组件负责。核心不依赖 Reactor 或特定 Broker，也不提供可靠消息交付保证。

默认协议实现在components/protocol-component，线协议见 [设备协议](../../docs/mqtt-protocol.md)。core仅定义SPI，没有实现插件发现、协议热加载、真实凭证验证、会话路由或网络客户端。Transport 中存在某个名称不代表已支持对应通信协议。

## 暖奶器模型示例

```java
var tenant = new TenantId("tenant-a");
var product = new ProductKey(tenant, new ProductId("baby-warmer"));
var heatingLevel = new ValueSchema(ValueType.INTEGER,
        BigDecimal.ONE, BigDecimal.valueOf(100), null);
var operation = new OperationDefinition("setHeatingLevel", Map.of(
        "heatingLevel", new FieldDefinition("heatingLevel", heatingLevel, true)));
var model = new DeviceModel(product, "v1", Map.of(
        "heatingLevel", new PropertyDefinition("heatingLevel", heatingLevel, PropertyAccess.READ_WRITE)),
        Map.of(), Map.of(operation.id(), operation));
model.validateOperation("setHeatingLevel", Map.of("heatingLevel", new Value.IntegerValue(70)));
```

亮度 1～100 是这个示例产品的范围，不是全平台约束。

## 构建与测试

从后端根目录执行：

```powershell
mvn -B -ntp -pl platform-core -am verify
mvn -B -ntp verify
```

测试覆盖租户键隔离、跨租户产品引用、物模型约束、缺失／未知字段、精确数值与 Unicode、输入快照、有效期边界、解码身份绑定、连接事件来源和报文防御性复制。

整体模块归属与开发约定见 [项目架构](../../docs/architecture.md)，本机文件视图问题见 [后端使用说明](../README.md#本机文件视图)。
