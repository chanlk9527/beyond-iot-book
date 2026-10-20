# 星云 MQTT 默认设备协议 1.0

本文供设备固件、网关和平台接入开发者使用，定义连接、寻址、报文和交互规则。直连设备与网关子设备共用本协议；网关负责本地 Zigbee、BLE、Modbus 等协议的转换。

| 项目 | 约定 |
| --- | --- |
| 协议标识 | `nebula-mqtt-v1` |
| 业务报文版本 | `version: "1.0"` |
| 传输协议 | MQTT 5.0 |
| 业务编码 | UTF-8 JSON |
| 设备类型 | 直连设备 `DIRECT`、网关 `GATEWAY`、网关子设备 `CHILD` |

文中的“必须”“不得”是接入约定，“建议”是实现建议。本文规定报文如何交互；各业务是否已经提供完整服务，见第 12 节。旧联调格式 `type/occurredAt` 已停止使用。

阅读顺序：首次接入先看第 1～5 节；开发网关再看第 6 节；按设备能力查阅第 7～10 节；失败处理查阅第 11 节。平台启动见[后端使用说明](../backend/README.md)，产品、物模型和设备登记见[设备管理说明](../backend/platform-manager/device-manager/README.md)。

## 1. 接入流程与设备身份

### 1.1 直连设备

1. 在平台发布产品物模型，登记设备并取得凭证。设备绑定一个已发布的物模型版本。
2. 安装全局下行消息回调，再使用凭证连接 MQTT 5 服务。**不发送 SUBSCRIBE。**
3. 时钟未知时请求校时，再报告启动信息和当前属性。
4. 持续处理下行、MQTT 确认和保活；执行控制后发送应用回复。
5. 断线后重新认证，根据实际状态重新上报；旧 MQTT 会话不会恢复。

### 1.2 网关与子设备

网关自身是一台经过认证的设备。子设备拥有独立的平台设备 ID、产品和物模型，通过已绑定网关的连接通信。

平台先建立同范围的 `GATEWAY → CHILD` 关系；网关连接后，为实际可通信的子设备声明上线，再代理其消息。**绑定关系、网关在线、子设备在线是三个不同的事实。** 完整流程见第 6 节。

### 1.3 身份从哪里取得

| 消息对象 | 平台确定身份的依据 |
| --- | --- |
| 直连设备或网关自身 | CONNECT 认证后绑定的 `(tenantId, deviceId)` |
| 网关代理的子设备 | 已认证网关的租户、Topic 中的子设备 ID、平台保存的绑定关系及接入资格 |

业务报文不得携带 `tenantId`、`deviceId`、`productId` 或平台接收时间来覆盖身份。Topic 中的子设备 ID 是寻址输入，只有通过代理关系校验后才可使用。

平台按目标设备的登记信息查找物模型：网关自身消息使用网关模型，子设备消息使用子设备模型。协议版本 `1.0` 与物模型版本 `v1`、`v2` 分别管理。

## 2. MQTT 连接约定

### 2.1 连接参数

| 参数 | 要求 |
| --- | --- |
| MQTT 版本 | 必须为 5.0；MQTT 3 连接被拒绝 |
| ClientId、用户名、密码 | 使用平台注册签发的值，三者必须匹配 |
| Clean Start | `true` |
| Session Expiry Interval | `0`，短会话 |
| Keep Alive | 建议 60 秒，使用 PINGREQ／PINGRESP 保活，无需业务心跳报文 |
| SUBSCRIBE | 不支持；设备通过全局 PUBLISH 回调接收指定连接的下行 |
| 上行 QoS | 允许 0、1；需要确认的报告、应用回复、校时和子设备状态声明应使用 1 |
| 下行 QoS | 固定为 1 |
| QoS 2 | 不支持 |
| Retain | 必须为 `false` |
| Will | CONNECT 不携带遗嘱 |
| Topic Alias、增强认证 | 不支持 |
| 离线投递 | 不提供离线队列，平台只向有效在线会话发送 |

正常登记模式下，ClientId 与用户名均为平台生成的设备 ID，密码为该设备当前密钥。子设备不能使用自己的凭证直接建立 MQTT 连接，网关只使用网关自己的凭证。

`mqtt-dev` 使用独立的开发清单，例如用户名 `tenant-a/warmer-1`；开发清单不改变正常登记模式的认证规则。默认本地地址为 `127.0.0.1:1883`，实际地址以部署配置为准；当前内置监听为明文 TCP，公网 TLS 接入需要部署方配置。

### 2.2 连接资格与替换

设备必须处于 `ACTIVE`，其产品及所属租户必须启用。相同设备的新连接替换旧连接；不同设备不能通过复用 ClientId 占用对方会话。

重连必须重新认证。密钥轮换、停用、退役或授权关系变化后，平台按当前资格拒绝后续接入或操作；跨节点的主动断连和状态收敛边界见[设备管理说明](../backend/platform-manager/device-manager/README.md)。

### 2.3 大小与流量控制

业务载荷最大为 **60 KiB（61,440 字节）**，还必须满足接入端和客户端声明的最大 MQTT 报文大小；Topic、报文头和属性也占用报文空间。子设备状态声明使用第 6 节规定的固定载荷。

客户端应遵守 CONNACK 宣告的 Receive Maximum、Maximum Packet Size 等限制。一个网关连接上的自身下行和所有子设备下行共享 MQTT packetId、在途窗口及 PUBACK 处理，不能为每个子设备独立复用同一个在途 packetId。

## 3. Topic 与消息路由

“上行”指设备／网关发往平台，“下行”指平台发往设备／网关。

| 对象 | 方向／用途 | Topic | 载荷 |
| --- | --- | --- | --- |
| 直连设备或网关自身 | 上行 | `nebula/v1/up` | 第 4 节的统一信封 |
| 直连设备或网关自身 | 下行 | `nebula/v1/down` | 第 4 节的统一信封 |
| 子设备 | 声明上线 | `nebula/v1/child/{deviceId}/online` | 精确的 `{}` |
| 子设备 | 声明下线 | `nebula/v1/child/{deviceId}/offline` | 精确的 `{}` |
| 子设备 | 上行 | `nebula/v1/child/{deviceId}/up` | 第 4 节的统一信封 |
| 子设备 | 下行 | `nebula/v1/child/{deviceId}/down` | 第 4 节的统一信封 |

前两个 Topic 对所有连接相同，但下行只发送给指定的认证会话，不发生跨设备广播。设备向 `/down` 发布不能控制其他设备。

子设备的四类 Topic 全部使用**父网关自己的 MQTT 连接**。`{deviceId}` 必须替换为平台生成的子设备 ID，不能使用设备编码、MAC 地址、Modbus 从站地址或网关 ID；本地地址与设备 ID 的映射由网关维护。该路径段只接受 `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`，不得包含通配符、额外路径段或空字符串。

Topic 只选择消息对象；属性、事件和命令种类仍由 `method` 决定。子设备业务消息不增加一层“网关转发”JSON，也不在 JSON 中重复携带设备身份。

## 4. 统一报文格式

### 4.1 信封字段

除子设备 `/online`、`/offline` 的固定 `{}` 外，所有业务载荷均为单个 UTF-8 JSON 对象。

| 字段 | JSON 类型 | 使用范围 | 含义 |
| --- | --- | --- | --- |
| `version` | string | 全部业务消息，必填 | 固定为 `"1.0"` |
| `messageId` | string | 全部业务消息，必填 | 本条逻辑消息的唯一编号 |
| `method` | string | 全部业务消息，必填 | 方法标识，区分大小写 |
| `timestamp` | integer | 全部业务消息，必填 | 消息产生时刻，非负 64 位 Unix 毫秒；上行未校时可填 0 表示未知 |
| `data` | object | 全部业务消息，必填 | 方法数据；允许无数据时仍填写 `{}` |
| `modelVersion` | string | 所有下行，必填；上行不得携带 | 目标设备当前绑定的物模型版本 |
| `expiresAt` | integer | 所有下行，必填；上行不得携带 | 绝对 Unix 毫秒，必须晚于 `timestamp`；到达该时刻不得开始执行 |
| `replyTo` | string | 应用回复，必填；请求和报告不得携带 | 原请求或原报告的 `messageId` |
| `result` | object | 应用回复，必填；请求和报告不得携带 | `code` 必填，`message` 可选 |

回复本身必须使用新的 `messageId`，不能与 `replyTo` 相同。下行回复，例如 `system.time.reply` 和可选报告确认，同时具有下行字段和回复字段。

上行的模型版本由平台注册信息确定，设备不能通过报文指定。设备收到下行后应核对 `modelVersion`、支持的能力和有效期；不能执行时按对应方法返回失败结果。

### 4.2 字段和值的约束

| 内容 | 约束 |
| --- | --- |
| 短文本标识 | `messageId`、`replyTo`、`method`、版本、任务编号等为 1～128 个 Unicode 码点，不得为全空白或含控制字符 |
| 物模型能力 ID | 属性、操作、事件及其字段还须满足物模型定义：字母开头，只含字母、数字、下划线，最多 64 字符 |
| `result.code` | `[A-Z][A-Z0-9_]{0,63}`；`SUCCESS` 表示该方法约定的成功或受理 |
| `result.message` | 可选；存在时为非空白字符串，最多 512 个 Unicode 码点 |
| 值映射 | `properties`、`inputs`、事件的 `fields`、配置的 `values` 最多 256 项；具体方法可以要求非空 |
| 属性／参数／配置／事件的单个值 | 布尔、64 位有符号整数、小数、字符串，或一维标量数组 |
| 数组值 | 最多 1,000 项，元素不能为 null、对象或数组；物模型进一步约束元素类型和项数 |

`null`、结构体值和嵌套数组不受支持。这个限制针对映射中的**业务值**，不禁止协议本身定义的 `data`、`properties`、OTA `package` 等对象，以及属性读取清单等数组结构。

枚举通过字符串编码传输，单位由物模型定义，不随每个数值重复发送。整数 `70`、小数 `70.0`、字符串 `"70"` 是不同的输入类型：INTEGER 不接收字符串或小数，DECIMAL 可接收整数。数组是否同类型及字段值是否合法，由目标设备绑定的物模型校验。定义详见[物模型说明](thing-model.md)。

### 4.3 严格解析与版本

未定义字段、重复 JSON 键、尾随第二个 JSON、非法 UTF-8、未知 `method`、错误消息方向或不符合方法结构的 `data` 都会被拒绝。不能通过追加自定义信封字段扩展协议。

新增不兼容字段或方法语义时，应明确协议版本和兼容方案；物模型升级不能替代协议版本升级，发布新物模型也不会自动更改已登记设备的绑定版本。

本文示例中的设备 ID、消息 ID、物模型能力和时间戳用于说明结构。联调时使用实际登记 ID、已发布能力与当前时间，不能直接复用过期的 `expiresAt`。

## 5. 传输确认、执行结果与重试

### 5.1 三种结果分别表示什么

| 观察到的结果 | 可以确认的事实 |
| --- | --- |
| 上行 QoS 1 成功 PUBACK | 平台已完成本次接入处理；是否已持久化，取决于已配置的业务接收链路 |
| 下行 QoS 1 成功 PUBACK | 对端 MQTT 客户端收到传输；对于子设备，只能确认父网关收到 |
| 应用回复或完成报告 | 按 `method`、`result.code` 和业务状态判断受理、执行或升级结果 |

**PUBACK 不能代替执行成功。** 超时或断线也不能证明设备没有执行。平台接入实现会在上行解析、必要的模型校验与接收处理成功后确认；业务数据存储失败时如何影响接入确认，仍取决于消息传输与消费者的配置。

### 5.2 关联与重复

`messageId` 在同一设备内跨重连保持唯一；不同子设备拥有各自的消息编号空间。重传同一逻辑消息时，保持 ID 和内容不变。

请求发出后，以**目标设备身份、请求 `messageId`、预期回复方法**关联结果；命令还必须核对 `commandId`，OTA 还需核对 `taskId`、`attemptId`。同一个 ID 携带不同内容应当作为冲突处理。

设备应按 `(messageId, method)` 保存去重结果。重复有副作用的请求不得再次执行，应返回已保存的结果；命令额外按 `commandId` 防重复，OTA 按任务及尝试防重复。保存窗口至少覆盖请求有效期与业务重试窗口。

请求过期后不得重新开始执行。`expiresAt` 约束开始执行的时刻；已经受理的长任务如何超时或取消，由该业务定义。结果未知时先核实状态，不能仅因缺少 PUBACK 或应用回复就生成新 ID 重做动作。

### 5.3 报告的可选应用确认

属性、事件、设备信息、配置及 OTA 报告默认只使用 MQTT 确认，不自动生成应用回复。业务接收器需要明确表达受理结果时，可发送下列下行方法：

| 上行报告 | 可选下行确认 |
| --- | --- |
| `thing.property.report` | `thing.property.report.reply` |
| `thing.event.report` | `thing.event.report.reply` |
| `thing.info.report` | `thing.info.report.reply` |
| `thing.config.report` | `thing.config.report.reply` |
| `ota.version.report` | `ota.version.report.reply` |
| `ota.progress.report` | `ota.progress.report.reply` |

这些回复的 `data` 固定为 `{}`，必须包含 `replyTo`、`result`、`modelVersion` 和 `expiresAt`。设备不得仅因未收到可选确认便认定报告传输失败。

## 6. 网关与子设备会话

### 6.1 代理前提

同一实际租户或平台范围内，只有 `GATEWAY` 设备可以代理已绑定的 `CHILD` 设备。每个子设备只能绑定一个网关，每个网关最多绑定 1,024 个子设备；当前支持单层关系。

网关、子设备及其产品必须启用，所属租户也必须启用。平台在代理上下行时复核关系与资格。网关负责验证本地子设备和本地地址的对应关系；云端不需要理解 Zigbee、BLE 或 Modbus 报文。

拓扑通过平台管理 API 建立，不通过 MQTT 自行添加或覆盖。管理步骤、绑定接口、修订号和删除约束见[设备管理说明](../backend/platform-manager/device-manager/README.md)。

### 6.2 上线与下线声明

网关向子设备的 `/online` 或 `/offline` Topic 发布固定载荷：

```json
{}
```

这是统一信封的明确例外：**载荷必须精确为 UTF-8 两个字节 `{}`**，不附加 BOM、空格、换行或 `version`、`messageId` 等字段。建议使用 QoS 1；成功 PUBACK 表示平台已处理声明，失败 PUBACK 表示本次声明未被接受。没有额外应用回复，QoS 0 无法取得处理确认。

| 当前状态／事件 | 行为 |
| --- | --- |
| 已绑定但尚未声明上线 | 不能代理业务上报或接收下行 |
| 有效绑定收到 `/online` | 创建子设备逻辑会话，允许业务通信 |
| 已有有效会话再次收到 `/online` | 保留原逻辑会话，不重复创建 |
| 收到 `/offline` | 移除该子设备会话；没有该会话时无须创建任何状态 |
| 父网关断开、被接管或停止 | 清理其子设备会话 |
| 父网关重新连接 | 只恢复网关自身连接；必须重新声明实际在线的子设备 |
| 绑定撤销或设备资格失效 | 拒绝后续代理消息和下行，并清理失效子设备会话 |

移除子设备会话不会主动断开仍有效的网关或其他子设备。失效状态在其他节点可能需要经过资格复核或租约到期收敛；不能据父网关在线推断子设备仍在线。

### 6.3 一次完整的子设备交互

假设子设备平台 ID 为 `abcdefghjkmnpqrs`，已绑定当前网关，并定义了可读写的 `power` 属性。

1. 网关以自己的凭证连接，向 `nebula/v1/child/abcdefghjkmnpqrs/online` 发布精确的 `{}`，检查 PUBACK。
2. 网关向 `nebula/v1/child/abcdefghjkmnpqrs/up` 上报子设备状态：

```json
{
  "version": "1.0",
  "messageId": "child-report-1",
  "method": "thing.property.report",
  "timestamp": 1792022400000,
  "data": {"properties": {"power": true}}
}
```

3. 平台以子设备身份处理报告；需要控制时，在同一个网关连接上向 `nebula/v1/child/abcdefghjkmnpqrs/down` 发送：

```json
{
  "version": "1.0",
  "messageId": "child-write-1",
  "method": "thing.property.write",
  "timestamp": 1792022401000,
  "modelVersion": "v1",
  "expiresAt": 1792022431000,
  "data": {"properties": {"power": false}}
}
```

4. 网关根据 Topic 定位本地子设备，转换并转发控制。子设备执行后，网关向该子设备的 `/up` 返回：

```json
{
  "version": "1.0",
  "messageId": "child-reply-1",
  "method": "thing.property.write.reply",
  "timestamp": 1792022401200,
  "replyTo": "child-write-1",
  "result": {"code": "SUCCESS"},
  "data": {"properties": {"power": false}}
}
```

网关不得把“本地发送成功”当作子设备执行成功。网关自己的报告、校时和控制使用 `nebula/v1/up`、`nebula/v1/down`；子设备的同类方法使用自己的 Topic 和物模型。

### 6.4 网关实现注意事项

网关应保持云端消息标识与本地请求的关联，并为每个子设备保留独立的去重及结果状态。收到下行后应及时处理 MQTT 确认，在后续本地执行完成时另发应用回复。

不能在 MQTT 消息回调线程中同步等待另一次发布的 PUBACK；该线程还需要继续处理下行和确认。平台的校时回复可能在原上行 PUBACK 之前到达，设备同样应持续接收。

## 7. 属性、操作、事件与设备信息

本节方法对直连设备、网关自身、子设备均适用。选择消息对象的 Topic 后，`data` 结构相同。

### 7.1 方法索引

表中列出 `data` 的字段，字段均为必填，除非明确标注可选。所有回复仍须使用第 4 节的回复信封。

| 方向 | `method` | `data` | 结果 |
| --- | --- | --- | --- |
| 上行 | `thing.property.report` | `properties`：非空属性值对象 | 可部分上报 |
| 下行 | `thing.property.read` | `properties`：1～256 个不重复属性 ID 的数组 | `thing.property.read.reply` |
| 上行 | `thing.property.read.reply` | 成功：`properties` 值对象；失败：`{}` | 读取结果 |
| 下行 | `thing.property.write` | `properties`：非空属性值对象 | `thing.property.write.reply` |
| 上行 | `thing.property.write.reply` | 成功：`properties` 值对象；失败：`{}` | 实际生效值 |
| 下行 | `thing.command.invoke` | `commandId`、`operationId`、`inputs` 参数对象 | `thing.command.reply` |
| 上行 | `thing.command.reply` | `commandId`、`status`，失败时也保留 | 受理或执行状态 |
| 上行 | `thing.event.report` | `eventId`、`fields` 字段对象 | 事件报告 |
| 上行 | `thing.info.report` | `firmwareVersion`、`hardwareVersion`、`bootId`、`clockSynced`；可选 `sdkVersion` | 启动与设备信息 |

属性访问权限从平台视角定义：READ_ONLY 允许平台读取和设备上报；WRITE_ONLY 允许平台写入；READ_WRITE 同时允许。读写一组属性时按整组处理，任一字段无效则整组失败。

成功读回复应覆盖本次请求的全部属性。成功写回复应只包含本次请求属性的实际生效值；WRITE_ONLY 的回值只用于请求确认，不转成公开可读属性。平台接入层校验字段和值，回复是否完整匹配原请求还需关联业务检查。

空参数的操作和空字段的事件合法，分别填写 `inputs: {}` 和 `fields: {}`；非空能力必须符合对应物模型的必填项和类型约束。

### 7.2 属性读取及失败回复

平台下行到目标设备的 `/down`：

```json
{
  "version": "1.0",
  "messageId": "read-100",
  "method": "thing.property.read",
  "timestamp": 1792022400000,
  "modelVersion": "v1",
  "expiresAt": 1792022430000,
  "data": {"properties": ["power", "heatingLevel"]}
}
```

设备通过自己的 `/up` 回复：

```json
{
  "version": "1.0",
  "messageId": "read-reply-100",
  "method": "thing.property.read.reply",
  "timestamp": 1792022400100,
  "replyTo": "read-100",
  "result": {"code": "SUCCESS"},
  "data": {"properties": {"power": true, "heatingLevel": 70}}
}
```

不能读取时，仍使用 `thing.property.read.reply`，填写非 SUCCESS 码并将 `data` 置为 `{}`，例如：

```json
{
  "version": "1.0",
  "messageId": "read-failed-100",
  "method": "thing.property.read.reply",
  "timestamp": 1792022400100,
  "replyTo": "read-100",
  "result": {"code": "BUSY", "message": "设备正在执行校准"},
  "data": {}
}
```

属性写入的成功往返见第 6.3 节；直连设备使用 `nebula/v1/up`、`nebula/v1/down` 即可复用该示例。

### 7.3 操作调用与执行状态

平台下行：

```json
{
  "version": "1.0",
  "messageId": "invoke-100",
  "method": "thing.command.invoke",
  "timestamp": 1792022400000,
  "modelVersion": "v1",
  "expiresAt": 1792022430000,
  "data": {
    "commandId": "command-100",
    "operationId": "setHeatingLevel",
    "inputs": {"heatingLevel": 70}
  }
}
```

`messageId` 标识报文，`commandId` 标识一次执行意图；回复的 `replyTo` 引用 `invoke-100`，不是 `command-100`。

| `status` | `result.code` | 含义 |
| --- | --- | --- |
| `ACCEPTED` | `SUCCESS` | 已受理，之后必须另发最终结果 |
| `SUCCEEDED` | `SUCCESS` | 执行成功 |
| `FAILED` | 非 `SUCCESS` | 执行失败 |

同步完成时可直接回复最终状态；异步完成时，各阶段使用不同的回复 `messageId`，保持相同的 `replyTo` 和 `commandId`。最终状态不得被迟到的 ACCEPTED 覆盖。

```json
{
  "version": "1.0",
  "messageId": "invoke-reply-100",
  "method": "thing.command.reply",
  "timestamp": 1792022400100,
  "replyTo": "invoke-100",
  "result": {"code": "SUCCESS"},
  "data": {"commandId": "command-100", "status": "SUCCEEDED"}
}
```

操作失败时，保留 `commandId`、`status: "FAILED"`，例如 `result: {"code":"UNSUPPORTED_METHOD"}`；不能套用属性失败的空 `data` 格式。当前操作回复只表达执行状态，不定义操作输出参数。

### 7.4 事件与启动信息

```json
{
  "version": "1.0",
  "messageId": "event-100",
  "method": "thing.event.report",
  "timestamp": 1792022400000,
  "data": {"eventId": "fault", "fields": {"reason": "overheat"}}
}
```

```json
{
  "version": "1.0",
  "messageId": "info-100",
  "method": "thing.info.report",
  "timestamp": 0,
  "data": {
    "firmwareVersion": "1.0.0",
    "hardwareVersion": "A",
    "bootId": "boot-001",
    "sdkVersion": "1.0.0",
    "clockSynced": false
  }
}
```

每次连接后应报告设备信息；设备重启必须生成新的 `bootId`，仅 MQTT 重连不代表设备已经重启。子设备的启动信息描述子设备本身。`clockSynced` 为 JSON 布尔值。

上述 `power`、`heatingLevel`、`setHeatingLevel` 和 `fault` 都是示例能力，必须先在目标产品的已发布物模型中定义。

## 8. 配置管理

配置与实时属性分开，以 `scope` 标识配置域，例如 `sampling`。配置 `version` 表示设备已应用的配置版本，与信封 `version`、物模型 `modelVersion` 分别管理。

| 方向 | `method` | `data` |
| --- | --- | --- |
| 下行 | `thing.config.get` | `scope` |
| 上行 | `thing.config.get.reply` | 成功：`scope`、`version`、`values`；失败：`{}` |
| 下行 | `thing.config.set` | `scope`、`version`、`values`；可选 `expectedVersion` |
| 上行 | `thing.config.set.reply` | 成功：`scope`、`version`；失败：`{}` |
| 上行 | `thing.config.report` | `scope`、`version`、`values`，表示实际配置 |

`thing.config.set` 携带 `expectedVersion` 时，设备必须先比较当前版本，不一致则返回 `VERSION_CONFLICT`；省略时允许覆盖。设备应整组校验并原子应用，重启后恢复已确认配置。

```json
{
  "version": "1.0",
  "messageId": "config-100",
  "method": "thing.config.set",
  "timestamp": 1792022400000,
  "modelVersion": "v1",
  "expiresAt": 1792022430000,
  "data": {
    "scope": "sampling",
    "version": "r2",
    "expectedVersion": "r1",
    "values": {"intervalSeconds": 60}
  }
}
```

```json
{
  "version": "1.0",
  "messageId": "config-reply-100",
  "method": "thing.config.set.reply",
  "timestamp": 1792022400100,
  "replyTo": "config-100",
  "result": {"code": "SUCCESS"},
  "data": {"scope": "sampling", "version": "r2"}
}
```

配置值不直接套用设备属性的 schema。配置域校验、版本比较和持久化需要配置业务及设备固件实现，当前平台支持的范围见第 12 节。

## 9. 时间与校时

`timestamp` 是消息来源时间，平台另存可信的接收时间。不能将设备时间用于身份授权、租户隔离或唯一性判断，也不能只凭时间戳推断跨重启顺序。时钟未知时，设备应先校时；不能判断下行有效期时，拒绝有副作用的请求并返回 `CLOCK_NOT_SYNCHRONIZED`。

| 方向 | `method` | `data` |
| --- | --- | --- |
| 上行 | `system.time.request` | `clientSendTime`：t0，设备发送时刻，非负毫秒，可为 0 |
| 下行 | `system.time.reply` | `clientSendTime` 原样回传；`serverReceiveTime`：t1；`serverSendTime`：t2 |

```json
{
  "version": "1.0",
  "messageId": "time-100",
  "method": "system.time.request",
  "timestamp": 0,
  "data": {"clientSendTime": 1234}
}
```

```json
{
  "version": "1.0",
  "messageId": "time-reply-100",
  "method": "system.time.reply",
  "timestamp": 1792022400010,
  "modelVersion": "v1",
  "expiresAt": 1792022410010,
  "replyTo": "time-100",
  "result": {"code": "SUCCESS"},
  "data": {
    "clientSendTime": 1234,
    "serverReceiveTime": 1792022400000,
    "serverSendTime": 1792022400015
  }
}
```

设备接收时记录本地时刻 t3：

```text
时钟偏移估计 = ((t1 - t0) + (t2 - t3)) / 2
网络往返耗时 = (t3 - t0) - (t2 - t1)
```

t0、t3 必须来自同一个连续的本地计时基准，测量过程中不能调钟。设备应核对 `replyTo`、回显值，丢弃负耗时或异常大的延迟，然后换算为 UTC 时间并设置 `clockSynced=true`。校时回复允许在设备尚未校时时接收。

平台自动响应校时，回复有效期为 10 秒；t1 取平台 MQTT 接入端收到报文的时间，t2 在编码时生成。子设备经网关转发时，链路耗时包含本地转发时间；这不是高精度授时协议。平台时钟本身也必须正确。

## 10. OTA 控制与进度

MQTT 传递升级控制与进度，固件通过 HTTPS 下载，不通过 MQTT 分片传输。当前不定义差分升级、并行升级多个组件、设备自签发下载地址或任意脚本执行。

### 10.1 方法与任务身份

| 方向 | `method` | `data` |
| --- | --- | --- |
| 上行 | `ota.version.report` | `versions`：组件名到实际版本的对象，1～16 项 |
| 下行 | `ota.upgrade` | `taskId`、`attemptId`、`component`、`fromVersion`、`targetVersion`、`hardwareVersion`、`package` |
| 上行 | `ota.upgrade.reply` | 成功受理：`taskId`、`attemptId`；失败：`{}` |
| 上行 | `ota.progress.report` | `taskId`、`attemptId`、`upgradeMessageId`、`component`、`targetVersion`、`stage`、`progress`；`installedVersion`、`errorCode` 见下文 |
| 下行 | `ota.cancel` | `taskId`、`attemptId` |
| 上行 | `ota.cancel.reply` | 成功受理：`taskId`、`attemptId`；失败：`{}` |

`taskId` 标识业务任务，`attemptId` 标识一次升级尝试。重传同一次尝试必须保持标识，不得启动第二次升级；重新尝试生成新的 `attemptId`，保留 `taskId`。进度中的 `upgradeMessageId` 引用该次 `ota.upgrade` 的消息 ID，旧尝试的迟到进度不得覆盖新尝试。

### 10.2 固件描述与升级请求

| `package` 字段 | 要求 |
| --- | --- |
| `url` | HTTPS 地址，不能包含 URL 用户信息或片段 |
| `size` | 固件原始字节数，正整数 |
| `sha256` | 64 位小写十六进制摘要 |
| `signatureAlgorithm` | 固定 `Ed25519` |
| `keyId` | 设备预置的可信公钥标识 |
| `signature` | 64 字节签名的标准 Base64 编码 |

```json
{
  "version": "1.0",
  "messageId": "ota-100",
  "method": "ota.upgrade",
  "timestamp": 1792022400000,
  "modelVersion": "v1",
  "expiresAt": 1792022460000,
  "data": {
    "taskId": "task-100",
    "attemptId": "attempt-1",
    "component": "main",
    "fromVersion": "1.0.0",
    "targetVersion": "1.1.0",
    "hardwareVersion": "A",
    "package": {
      "url": "https://firmware.example.com/main-1.1.0.bin",
      "size": 1024,
      "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "signatureAlgorithm": "Ed25519",
      "keyId": "firmware-key-1",
      "signature": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=="
    }
  }
}
```

示例地址、摘要和签名仅说明格式。签名对象是**下载固件的原始完整字节**，SHA-256 不能代替签名校验；不能接受同一报文附带的任意公钥。可信公钥通过出厂或注册流程预置。

固件必须包含可验证的组件、版本与硬件兼容信息。设备将签名保护的固件元数据与请求比较，避免通过改写未签名的 JSON 字段绕过兼容性或版本限制。

### 10.3 受理与执行

执行前应检查有效期、模型、硬件、组件、当前版本、空间、电量和任务冲突。`fromVersion` 必须匹配当前版本，当前不允许同版本升级；降级必须由可信设备策略明确允许，不能简单按版本字符串排序。

`ota.upgrade.reply` 的 `SUCCESS` 只表示受理，示例：

```json
{
  "version": "1.0",
  "messageId": "ota-reply-100",
  "method": "ota.upgrade.reply",
  "timestamp": 1792022400100,
  "replyTo": "ota-100",
  "result": {"code": "SUCCESS"},
  "data": {"taskId": "task-100", "attemptId": "attempt-1"}
}
```

受理后，按下载、摘要校验、验签、兼容性检查、安装、重启和运行版本验证的顺序处理。设备应限制下载源、重定向、耗时及大小，验证 HTTPS 证书，不记录带凭证的下载 URL；支持的硬件可采用双分区和失败回滚。

### 10.4 进度、完成与取消

`stage` 可为 `RECEIVED`、`DOWNLOADING`、`VERIFYING`、`INSTALLING`、`REBOOTING`、`SUCCEEDED`、`FAILED`、`CANCELED`。`progress` 为整个尝试的 0～100 整数进度，不是单个阶段的百分比。

| 条件 | 附加要求 |
| --- | --- |
| `stage=FAILED` | 必须携带非空 `errorCode` |
| 其他阶段 | 不得携带 `errorCode` |
| `stage=SUCCEEDED` | `progress=100`，且 `installedVersion` 等于 `targetVersion` |

```json
{
  "version": "1.0",
  "messageId": "ota-progress-100",
  "method": "ota.progress.report",
  "timestamp": 1792022500000,
  "data": {
    "taskId": "task-100",
    "attemptId": "attempt-1",
    "upgradeMessageId": "ota-100",
    "component": "main",
    "targetVersion": "1.1.0",
    "stage": "SUCCEEDED",
    "progress": 100,
    "installedVersion": "1.1.0"
  }
}
```

只有安装、重启并验证运行版本后，设备才能报告 SUCCEEDED；下载完成或升级请求受理不算完成。执行期失败使用进度报告的 `stage=FAILED` 和 `errorCode`，报告信封不携带 `result`。

设备重连后应重新报告实际组件版本、启动信息和未终结任务进度。平台 OTA 业务还须核对目标、尝试、安装版本与重启证据，不能只凭一条 SUCCEEDED 终结任务。

取消只允许发生在可安全取消的阶段。`ota.cancel.reply` 成功表示受理，之后报告 CANCELED；进入不可中断阶段返回 `CANCEL_NOT_ALLOWED`。取消不表示卸载或回滚，已完成的尝试返回已保存结果。

## 11. 错误与连接恢复

### 11.1 应用结果码

应用结果放在回复的 `result.code`，设备和业务可使用符合格式的扩展码。下表是本协议约定的建议码；是否触发取决于实际能力和业务实现。

| `code` | 含义 |
| --- | --- |
| `SUCCESS` | 成功或受理，结合方法和状态判断 |
| `INVALID_ARGUMENT`、`UNSUPPORTED_METHOD` | 参数错误、不支持的方法或能力 |
| `MODEL_VERSION_MISMATCH`、`EXPIRED` | 模型不匹配、请求已过期 |
| `CLOCK_NOT_SYNCHRONIZED`、`BUSY` | 无法判断时效、设备忙 |
| `VERSION_CONFLICT`、`NOT_FOUND` | 配置版本冲突、目标不存在 |
| `INTERNAL_ERROR` | 执行异常；说明中不暴露凭证或敏感内部信息 |
| `OTA_INCOMPATIBLE`、`OTA_DOWNLOAD_FAILED` | 固件不兼容、下载失败 |
| `OTA_HASH_MISMATCH`、`OTA_SIGNATURE_INVALID` | 摘要或签名不符 |
| `OTA_INSTALL_FAILED`、`CANCEL_NOT_ALLOWED` | 安装失败、当前阶段不可取消 |

属性读写、配置读取／设置和 OTA 升级／取消请求失败时，回复 `data` 为 `{}`。操作调用失败仍保留 `commandId` 和 `status=FAILED`。`result.message` 只供人阅读，不能代替机器判断的结果码。

### 11.2 MQTT 拒绝与处理范围

| 失败位置 | 处理方式 | 连接影响 |
| --- | --- | --- |
| CONNECT 认证或接入条件不满足 | CONNACK 拒绝 | 不建立连接 |
| SUBSCRIBE | 返回未授权 | 可继续定向接收下行 |
| 错误 Topic、未知方法、非法 JSON、身份注入、字段／值校验失败、非法子设备代理 | QoS 1 返回失败 PUBACK，通常为 `PAYLOAD_FORMAT_INVALID`；QoS 0 丢弃并计入拒绝 | 通常只拒绝当前消息 |
| 接收处理失败 | QoS 1 返回失败 PUBACK，通常为 `IMPLEMENTATION_SPECIFIC_ERROR`；QoS 0 无确认 | 通常保留有效连接 |
| QoS、Retain、Topic Alias、接入在途上限违规 | 使用相应 MQTT 原因码拒绝 | 可断开连接 |
| 当前认证身份被撤销或会话失效 | 拒绝继续服务该会话 | 可断开连接；子设备资格失效时可只清理其逻辑会话 |

失败 PUBACK 不代表业务已受理，也**不会自动生成应用层错误回复**。有合法业务请求且设备不能执行时，使用对应的 `.reply` 返回业务结果，不把这两种错误混用。

### 11.3 恢复顺序

断线后，先重新认证和建立下行接收，再按需要校时、报告实际状态。网关还须重新声明实际在线的子设备。对未得到最终结果的请求，保留原关联身份，先查询或核实执行状态，再决定业务重试；不得假定重连会恢复旧会话、离线命令或旧在途结果。

## 12. 当前实现范围与联调入口

以下区分“协议可以表达”与“平台已经交付完整业务”，避免把报文格式当成业务服务已完成。

| 能力 | 当前范围 |
| --- | --- |
| MQTT 5 接入 | 登记凭证认证、短会话、定向下行、流量控制、有效期与 PUBACK 处理 |
| 网关代理 | 同范围单层拓扑、独立子设备逻辑会话、上下线和双向消息路由；底层协议由网关适配 |
| 属性／事件 | 按绑定物模型校验并进入业务消息链路；影子、历史是否写入取决于存储及产品策略 |
| 后台控制 | 属性写入、操作调用及结果关联；启用集群命令配置后具备持久命令和跨节点路由 |
| 校时 | 自动响应，无需在物模型中定义操作 |
| 配置管理 | 已有编解码与传输；配置 schema、版本比较、持久化和管理 API 尚未完整提供 |
| OTA | 已有编解码、结构及状态字段校验；任务调度、固件签发／管理、下载与设备验签仍需业务和固件实现 |
| 报告确认 | 可以编码可选 `.report.reply`；默认不自动产生业务持久化确认 |
| 通用可靠交付 | 没有覆盖全部业务的持久去重与状态恢复；不能承诺全链路零丢失 |
| 其他边界 | 无设备 SDK、通用订阅分发、QoS 2、Retain、遗嘱或离线持久会话；动态协议插件和按产品选择协议尚未实现 |

默认单节点运行；显式启用共享会话和集群命令配置后可跨节点寻址，连接及消息格式不变。网关本地发现、配网、地址管理、休眠、本地重试和协议栈实现不属于云端 MQTT 协议。本版不提供多级网关或多个网关同时代理同一子设备。

| 开发任务 | 入口 |
| --- | --- |
| 启动、认证联调、共享会话、集群命令 | [后端使用说明](../backend/README.md) |
| 产品／模型／设备登记、网关拓扑管理及 API | [设备管理说明](../backend/platform-manager/device-manager/README.md) |
| 枚举、数组、单位及模型约束 | [物模型说明](thing-model.md) |
| 属性与事件历史持久化 | [历史存储组件](../backend/platform-components/storage-component/README.md) |
| 整体模块归属与开发约定 | [项目架构](architecture.md) |

协议编解码实现为 `OfficialMqttCodec`，网络接入为 `VertxMqttGateway`。协议扩展通过 `ProtocolSupport`、编解码器及业务接收器组装；当前替换提供者是开发扩展点，不表示已提供插件安装或热加载。

## 浏览器设备模拟与 WebSocket 接入

设备详情提供真实 MQTT 5 浏览器模拟客户端，使用与 TCP 相同的设备身份、认证、会话目录、接收限额和协议处理。WebSocket 监听默认与 TCP 同时启停，地址为 `ws://127.0.0.1:8084/mqtt`，仍不要求 SUBSCRIBE。两个监听任一绑定失败时整体启动失败并释放监听端口。可配置 `MQTT_WEBSOCKET_ENABLED=false` 关闭，或通过 `MQTT_WEBSOCKET_PORT` 指定端口；未指定端口时为 8084（TCP 使用测试随机端口 0 时，WebSocket 也申请随机端口）。集群快捷启动显式配置 access 为 8084、access-b 为 8085；独立 Start-Service.ps1 可传 `-MqttWebSocketPort`。

控制台通过同源 `/mqtt` 代理连接，不把凭证放到查询参数。模拟数据经过正常协议校验并写入真实业务链路；同一身份连接会替换真实设备连接。模拟器支持属性／事件上报、属性读取／写入和操作应答；配置、OTA、网关代理和物理过程不在首版模拟范围内。操作成功仅表示用户选择了模拟成功，不能据此判断硬件行为。使用及代理部署见 [控制台说明](../console/README.md#设备模拟mqtt-over-websocket)。