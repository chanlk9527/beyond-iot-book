# 星云 MQTT 默认设备协议 1.0

本协议是星云 IoT 平台自有的默认设备协议，协议 ID 为 `nebula-mqtt-v1`，载荷版本为 `1.0`，基于 MQTT 5.0。这里的“官方”指本项目提供并维护，不代表 MQTT 标准组织定义的业务协议。设备与平台均按本文实现；原联调用 `type/occurredAt` 格式停止使用。

## 1. 接入和 Topic

设备使用平台注册的 ClientId、用户名及设备凭证连接。用户名的具体签发规则由设备注册域决定；本地开发清单使用 `tenant-a/warmer-1`，不据此推导生产认证规则。ClientId 必须与凭证绑定；认证后平台将连接绑定到唯一的 `(tenantId, deviceId)`。载荷不允许传入租户、产品、设备身份或平台接收时间。

| 项目 | 首版约定 |
| --- | --- |
| MQTT | 5.0，Clean Start=true，Session Expiry Interval=0 |
| 上行 Topic | `nebula/v1/up`，设备向平台发送 |
| 下行 Topic | `nebula/v1/down`，平台向指定连接发送 |
| 订阅 | **设备不发送 SUBSCRIBE**；注册全局 PUBLISH 回调接收下行。订阅请求被拒绝 |
| QoS | 上行允许0或1，可靠报告、回复及校时使用1；下行固定1；不支持2 |
| Retain / Will | 不支持；Retain=false，CONNECT不携带遗嘱 |
| Topic Alias / 增强认证 | 首版不支持 |
| 保活 | 推荐 Keep Alive=60秒，MQTT PINGREQ/PINGRESP维持连接，无需业务心跳消息 |
| 编码和大小 | 严格UTF-8 JSON对象，单个载荷最多60KiB；还受网关和客户端最大报文限制 |
| 会话 | 单节点在线投递，无离线队列和持久会话；重连后重新认证 |

两个 Topic 对所有设备相同。平台按认证会话选择目标设备，Topic 不参与跨设备广播或租户路由。设备不能通过向下行 Topic 发布来控制其他设备。相同设备新连接替换旧连接，不同设备不能占用同一个 ClientId。

本地默认地址为 `127.0.0.1:1883`，正常management启动默认开启MQTT，使用后台登记设备的ClientId／用户名／密钥；设备需为ACTIVE且产品及所属租户启用。轮换密钥或停用设备会断开会话。开发模式仍使用独立清单。启动步骤见 [后端使用说明](../backend/README.md)。当前监听是明文TCP，正式公网接入前需补齐TLS终止与凭证签发；本文不将这些能力视为已完成。

## 2. 统一信封

所有消息必须包含 `version`、`messageId`、`method`、`timestamp`、`data`。下行另外包含 `modelVersion`、`expiresAt`；回复另外包含 `replyTo`、`result`。未定义的字段、重复JSON键、尾随第二个JSON、未知method和错误方向均拒绝，不静默忽略。

| 字段 | 类型 | 语义 |
| --- | --- | --- |
| version | string | 固定 `1.0`，与物模型版本分别管理 |
| messageId | string | 逻辑消息编号，设备内跨重连唯一；建议UUID或持久化计数器。重传同一逻辑消息保持编号和内容 |
| method | string | 见后续方法表，区分大小写 |
| timestamp | integer | 消息产生时间，Unix epoch毫秒，非负64位整数；未校时可为0，表示未知，不能据此排序 |
| data | object | 方法数据，必填，允许为空的方法也必须写 `{}` |
| modelVersion | string | 仅下行；平台注册的物模型版本，设备执行前核对 |
| expiresAt | integer | 仅下行；绝对Unix毫秒，到达或超过此时刻不得开始执行，必须晚于timestamp |
| replyTo | string | 仅回复，等于请求的messageId，回复自身必须使用新messageId |
| result | object | 仅回复，`code`必填，`message`为可选的非空说明，最多512字符 |

短文本标识包括messageId、replyTo、method、属性ID、版本、任务编号等，限制为1～128个Unicode码点，不允许空白字符串或控制字符。`result.code`为1～64字符，匹配 `[A-Z][A-Z0-9_]{0,63}`，`SUCCESS`表示成功。`data`中的属性、参数、配置及事件字段值当前只支持布尔、64位有符号整数、小数和字符串；不允许null、数组和嵌套对象。每个字段映射最多256项，具体值范围由物模型或对应业务校验。

上行不发送modelVersion；平台从注册信息解析当前版本并校验。设备升级期间模型变更需要平台注册域与固件协同，不能用载荷覆盖注册版本。尚未校时的设备先执行校时；在无法判断有效期时拒绝有副作用的下行，返回 `CLOCK_NOT_SYNCHRONIZED`。校时回复本身可在尚未校时的状态下接收。

### 确认、关联和重复

MQTT PUBACK只确认传输接收；上行在编解码、模型校验和接收器成功后确认，不等于已入库。下行的 `DeliveryReceipt` / 指令兼容入口的 `DispatchReceipt` 在成功PUBACK后完成，不等于读取、执行或升级完成。业务结果必须看 `.reply` 或后续报告。

下行请求需要对应的应用回复；报告默认使用QoS 1传输确认，不自动发送应用回复。平台业务模块需要表达持久化／业务受理结果时，可显式发送对应 `.report.reply`，其data固定为空对象；设备不能仅凭未收到此可选回复判断传输失败。

设备以 `(messageId, method)` 去重，重复有副作用的请求不得再次执行，返回已保存的结果；命令额外按commandId防重复，OTA按taskId、attemptId关联。推荐去重至少覆盖请求有效期及业务重试窗口。

平台业务接收器应按 `(tenantId, deviceId, messageId)` 等稳定业务身份去重，并保存请求、预期回复类型和业务状态；相同ID但不同内容应视为冲突。这是各业务域的设计要求，不表示已建立通用Inbox。网络层和开发内存收件箱不提供通用持久化去重或业务状态机。

后台属性写入／操作调用已实现回复关联校验；显式cluster-commands模式还持久化命令、状态历史及专用Outbox，核对设备、绑定模型、replyTo、反馈类型与commandId，重复反馈不回退最终状态，可能已发送的动作不自动重发。该能力不覆盖全部上行、配置或OTA；其他业务仍须按所属领域建立去重、关联和恢复机制。

超时表示结果未知，不能直接判失败并重执行。重连不会恢复旧MQTT会话，业务重试需沿用同一逻辑ID，重新投递仍受expiresAt限制。兼容入口 `send(DeviceCommand)` 以commandId文本生成messageId，一条逻辑命令对应一条下行；通用入口可以显式指定两种ID。

## 3. 属性、指令、事件和设备信息

| 方向 | method | data字段 | 回复／说明 |
| --- | --- | --- | --- |
| 上行 | `thing.property.report` | properties：非空属性值对象 | 部分属性上报，按物模型校验可上报性和值 |
| 下行 | `thing.property.read` | properties：1～256个不重复属性ID的数组 | `thing.property.read.reply` |
| 上行 | `thing.property.read.reply` | 成功时properties：读取到的值 | 失败时data={} |
| 下行 | `thing.property.write` | properties：非空属性值对象 | `thing.property.write.reply` |
| 上行 | `thing.property.write.reply` | 成功时properties：实际生效的值 | 失败时data={} |
| 下行 | `thing.command.invoke` | commandId、operationId、inputs：参数对象 | `thing.command.reply`，操作和参数按物模型校验 |
| 上行 | `thing.command.reply` | commandId、status | 状态见下文，失败也保留这两个字段 |
| 上行 | `thing.event.report` | eventId、fields：字段对象 | 事件及字段按物模型校验 |
| 上行 | `thing.info.report` | firmwareVersion、hardwareVersion、bootId、clockSynced；sdkVersion可选 | 每次连接后报告，重启必须生成新bootId |

设备读属性只能访问READ_ONLY或READ_WRITE；设备写属性只能访问WRITE_ONLY或READ_WRITE。平台检查权限和值类型，设备也必须校验。读写请求首版约定整组原子处理：任一属性无效则整组失败，不用部分SUCCESS掩盖失败。成功读回复包含请求的全部属性；成功写回复包含本次写入属性的实际生效值，不附带无关属性。WRITE_ONLY的写回复只用于请求确认，业务不得把其转成公开可读属性。当前接入层校验回复字段合法性和权限，是否与请求清单完全一致由后续关联业务检查。

commandId是业务命令标识；messageId是报文标识，replyTo始终引用下行报文编号。`status=ACCEPTED`与`SUCCEEDED`对应result.code=SUCCESS；`FAILED`必须使用非SUCCESS码。ACCEPTED表示已受理、稍后另发新消息报告SUCCEEDED或FAILED；同步操作可直接发最终状态。已终结的命令不得被迟到的ACCEPTED覆盖。设备不能支持的operationId返回UNSUPPORTED_METHOD或INVALID_ARGUMENT。

### 属性上报

```json
{"version":"1.0","messageId":"up-100","method":"thing.property.report","timestamp":1792022400000,"data":{"properties":{"power":true,"heatingLevel":70}}}
```

### 读取与回复

```json
{"version":"1.0","messageId":"read-100","method":"thing.property.read","timestamp":1792022400000,"modelVersion":"v1","expiresAt":1792022430000,"data":{"properties":["power","heatingLevel"]}}
```

```json
{"version":"1.0","messageId":"up-101","method":"thing.property.read.reply","timestamp":1792022400100,"replyTo":"read-100","result":{"code":"SUCCESS"},"data":{"properties":{"power":true,"heatingLevel":70}}}
```

### 写入与失败回复

```json
{"version":"1.0","messageId":"write-100","method":"thing.property.write","timestamp":1792022400000,"modelVersion":"v1","expiresAt":1792022430000,"data":{"properties":{"heatingLevel":50}}}
```

```json
{"version":"1.0","messageId":"up-102","method":"thing.property.write.reply","timestamp":1792022400100,"replyTo":"write-100","result":{"code":"BUSY","message":"设备当前无法修改亮度"},"data":{}}
```

### 指令与执行完成

```json
{"version":"1.0","messageId":"invoke-100","method":"thing.command.invoke","timestamp":1792022400000,"modelVersion":"v1","expiresAt":1792022430000,"data":{"commandId":"command-100","operationId":"setHeatingLevel","inputs":{"heatingLevel":70}}}
```

```json
{"version":"1.0","messageId":"up-103","method":"thing.command.reply","timestamp":1792022400100,"replyTo":"invoke-100","result":{"code":"SUCCESS"},"data":{"commandId":"command-100","status":"SUCCEEDED"}}
```

### 事件和启动信息

```json
{"version":"1.0","messageId":"up-104","method":"thing.event.report","timestamp":1792022400000,"data":{"eventId":"fault","fields":{"reason":"overheat"}}}
```

```json
{"version":"1.0","messageId":"up-105","method":"thing.info.report","timestamp":0,"data":{"firmwareVersion":"1.0.0","hardwareVersion":"A","bootId":"boot-001","sdkVersion":"1.0.0","clockSynced":false}}
```

示例暖奶器物模型仅定义power、heatingLevel与setPower、setHeatingLevel；fault事件需要在正式物模型中定义才能通过接入校验。

## 4. 配置管理

配置与实时属性分开。scope标识配置域，例如sampling；version是设备实际应用的配置版本。`thing.config.set`中的expectedVersion可选，存在时设备必须比较当前版本，不符则返回VERSION_CONFLICT；省略时允许覆盖当前版本。整组校验并原子应用，成功后回复新版本。重启后应能恢复已确认配置；设备如何持久化由设备实现。

| 方向 | method | data字段 |
| --- | --- | --- |
| 下行 | `thing.config.get` | scope |
| 上行 | `thing.config.get.reply` | 成功：scope、version、values；失败：{} |
| 下行 | `thing.config.set` | scope、version、values；expectedVersion可选 |
| 上行 | `thing.config.set.reply` | 成功：scope、version；失败：{} |
| 上行 | `thing.config.report` | scope、version、values，报告实际配置 |

```json
{"version":"1.0","messageId":"config-100","method":"thing.config.set","timestamp":1792022400000,"modelVersion":"v1","expiresAt":1792022430000,"data":{"scope":"sampling","version":"r2","expectedVersion":"r1","values":{"intervalSeconds":60}}}
```

```json
{"version":"1.0","messageId":"up-106","method":"thing.config.set.reply","timestamp":1792022400100,"replyTo":"config-100","result":{"code":"SUCCESS"},"data":{"scope":"sampling","version":"r2"}}
```

当前协议实现字段转换；配置schema、版本比较、持久化及管理接口尚未实现，后续配置域负责校验，不直接复用设备属性schema。

## 5. 时间上报与校时

timestamp表示每条消息的设备产生时间，平台另外保存可信receivedAt。不能把设备时间作为租户隔离、唯一性、接收顺序或授权依据。设备通过thing.info.report的clockSynced报告时钟状态。

| 方向 | method | data字段 |
| --- | --- | --- |
| 上行 | `system.time.request` | clientSendTime：t0，设备发送时刻，毫秒，可为0 |
| 下行 | `system.time.reply` | clientSendTime：原样回传；serverReceiveTime：t1；serverSendTime：t2 |

```json
{"version":"1.0","messageId":"time-100","method":"system.time.request","timestamp":0,"data":{"clientSendTime":1234}}
```

```json
{"version":"1.0","messageId":"time-reply-100","method":"system.time.reply","timestamp":1792022400010,"modelVersion":"v1","expiresAt":1792022410010,"replyTo":"time-100","result":{"code":"SUCCESS"},"data":{"clientSendTime":1234,"serverReceiveTime":1792022400000,"serverSendTime":1792022400015}}
```

设备接收时记录t3，估算偏移 `((t1-t0)+(t2-t3))/2`，网络往返耗时 `(t3-t0)-(t2-t1)`。t0、t3必须来自同一连续计时基准，计算期间不能调钟；丢弃负耗时、过大延迟和不匹配replyTo的响应。按偏移调整设备UTC时钟后再设置clockSynced=true。网络不对称会带来误差，这不是高精度授时协议。

默认提供者已经自动响应校时；t1取网关收到报文的时间，t2在编码时生成，回复有效期10秒。该路径不要求物模型定义操作，也不进入业务收件箱。设备MQTT回调必须能持续处理下行和PUBACK，不能在回调线程里阻塞等待另一个同步发布完成。校时不能弥补不正确的平台时钟，部署需维护服务端时钟同步。

## 6. OTA

OTA采用HTTPS下载固件，MQTT只传控制、版本和进度，不通过MQTT分片传固件。首版不定义差分升级、并行升级多个组件、设备自签发下载地址或任意脚本执行。

| 方向 | method | data字段 |
| --- | --- | --- |
| 上行 | `ota.version.report` | versions：组件名到实际版本的对象，1～16项 |
| 下行 | `ota.upgrade` | taskId、attemptId、component、fromVersion、targetVersion、hardwareVersion、package |
| 上行 | `ota.upgrade.reply` | 成功受理：taskId、attemptId；失败：{} |
| 上行 | `ota.progress.report` | taskId、attemptId、upgradeMessageId、component、targetVersion、stage、progress；installedVersion、errorCode按状态填写 |
| 下行 | `ota.cancel` | taskId、attemptId |
| 上行 | `ota.cancel.reply` | 成功受理：taskId、attemptId；失败：{} |

package字段：url为HTTPS下载地址，不含URL用户信息及片段；size为正整数，单位字节；sha256为64位小写十六进制摘要；signatureAlgorithm固定Ed25519；keyId为预置可信公钥标识；signature为64字节签名的标准Base64。签名验证对象是**下载固件的原始完整字节**。摘要用于完整性检查，不能代替可信签名。公钥通过可信出厂／注册流程预置，不能接受同一升级报文附带的任意公钥。固件必须包含设备端可验证的组件、目标版本和硬件兼容性信息；设备把经签名保护的固件元数据与请求字段比较，防止改写未签名的JSON元数据绕过兼容及版本策略。

```json
{"version":"1.0","messageId":"ota-100","method":"ota.upgrade","timestamp":1792022400000,"modelVersion":"v1","expiresAt":1792022460000,"data":{"taskId":"task-100","attemptId":"attempt-1","component":"main","fromVersion":"1.0.0","targetVersion":"1.1.0","hardwareVersion":"A","package":{"url":"https://firmware.example.com/main-1.1.0.bin","size":1024,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","signatureAlgorithm":"Ed25519","keyId":"firmware-key-1","signature":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=="}}}
```

示例地址、摘要与签名仅展示格式，不能用于真实升级。生产由固件域签名并生成受限下载地址；平台和设备需限制可信下载源、验证HTTPS证书，控制重定向、超时、大小和下载资源占用，不在日志记录带凭证的URL。协议代码只校验地址与摘要／签名格式，未实现签名生成、下载或验签。

设备先检查有效期、模型、硬件、组件、当前版本、目标版本、空间、电量和任务冲突。fromVersion必须匹配当前版本，首版不允许同版本升级；降级策略由可信设备策略明确允许，不能只按字符串比较版本。SUCCESS的ota.upgrade.reply仅表示受理。expiresAt约束开始执行，已受理的升级不因到期自动中止；执行超时由OTA任务域另行管理。

stage可取 `RECEIVED`、`DOWNLOADING`、`VERIFYING`、`INSTALLING`、`REBOOTING`、`SUCCEEDED`、`FAILED`、`CANCELED`；progress是整个尝试的0～100整数进度，不是分阶段百分比。设备依次下载、核对大小及SHA-256、验签、检查固件元数据、安装和重启；不得跳过校验进入安装。支持的设备建议采用双分区及失败回滚，具体实现由设备端决定。

每次重试生成新attemptId，保留taskId；进度的upgradeMessageId引用该尝试的ota.upgrade消息。相同attemptId的重投递不能启动第二次升级；不同尝试的迟到进度不能覆盖当前状态。FAILED必须带非空errorCode，其他状态不能带errorCode；SUCCEEDED必须progress=100且installedVersion等于targetVersion。安装后重启并验证运行版本，才允许SUCCEEDED；下载完成或请求受理不算升级完成。

```json
{"version":"1.0","messageId":"up-107","method":"ota.upgrade.reply","timestamp":1792022400100,"replyTo":"ota-100","result":{"code":"SUCCESS"},"data":{"taskId":"task-100","attemptId":"attempt-1"}}
```

```json
{"version":"1.0","messageId":"up-108","method":"ota.progress.report","timestamp":1792022500000,"data":{"taskId":"task-100","attemptId":"attempt-1","upgradeMessageId":"ota-100","component":"main","targetVersion":"1.1.0","stage":"SUCCEEDED","progress":100,"installedVersion":"1.1.0"}}
```

重连后重新报告ota.version.report、thing.info.report及未终结任务进度。平台后续OTA域需核对任务目标、尝试、安装版本和重启信息后才能标记完成，不能只信任单条SUCCEEDED。取消请求只能用于可安全取消的阶段；受理后再报告CANCELED。进入不可中断阶段返回CANCEL_NOT_ALLOWED，不将取消解释为卸载或回滚。已经完成的尝试返回已保存结果。

## 7. 应用结果与协议错误

建议业务码如下。当前实现接受格式合法的业务码，具体产生条件由设备及后续业务域实现，不假装已经具备完整错误处理服务。

| code | 含义 |
| --- | --- |
| SUCCESS | 成功或受理，具体取决于method和status |
| INVALID_ARGUMENT / UNSUPPORTED_METHOD | 参数错误／不支持的方法或能力 |
| MODEL_VERSION_MISMATCH / EXPIRED | 模型不匹配／请求已过期 |
| CLOCK_NOT_SYNCHRONIZED / BUSY | 时钟未知／设备忙 |
| VERSION_CONFLICT / NOT_FOUND | 配置版本冲突／目标不存在 |
| INTERNAL_ERROR | 执行异常，不在message中暴露敏感信息 |
| OTA_INCOMPATIBLE / OTA_DOWNLOAD_FAILED | 硬件或版本不兼容／下载失败 |
| OTA_HASH_MISMATCH / OTA_SIGNATURE_INVALID | 摘要或签名校验失败 |
| OTA_INSTALL_FAILED / CANCEL_NOT_ALLOWED | 安装失败／当前不可取消 |

属性、配置和OTA控制请求失败时data={}；指令失败回复保留commandId、status=FAILED。OTA执行期失败通过ota.progress.report的stage=FAILED及errorCode报告，不在报告信封放result。失败说明使用result.message，不把它当成可机器判断的状态码。

可选报告回复为 `thing.property.report.reply`、`thing.event.report.reply`、`thing.info.report.reply`、`thing.config.report.reply`、`ota.version.report.reply`、`ota.progress.report.reply`，均为下行，data={}，带replyTo、result以及全部下行信封字段。

无法解析、身份注入、未知method、错误Topic、非法值等应用消息只拒绝当前消息，保留连接。QoS 1返回失败PUBACK（校验失败为PAYLOAD_FORMAT_INVALID，处理失败为IMPLEMENTATION_SPECIFIC_ERROR）；QoS 0丢弃并计入拒绝数。失败PUBACK不表示业务受理成功，**不自动生成应用错误回复**。设备接入资格被撤销时仍断开连接。认证错误使用MQTT CONNACK拒绝；订阅返回未授权；QoS、Retain、Topic Alias等能力错误按MQTT原因码拒绝。传输错误与上表业务错误分别处理。

## 8. 代码位置、扩展和交付边界

| 层 | 当前实现 |
| --- | --- |
| platform-core | 通用上／下行语义、回复关联、固件描述、状态值；不依赖JSON、MQTT或Spring |
| protocol-component | OfficialMqttProtocol提供者、OfficialMqttCodec严格编解码、自动校时响应 |
| network-component | Vert.x MQTT 5连接认证、会话路由、背压、过期与PUBACK；Topic由协议编码器给出，不内置业务格式 |
| platform-bootstrap | 默认装配官方提供者；通过ProtocolSupport替换整个接入实例的协议，DeviceMessageHandler提供业务接收器 |

调用 `DeviceAccessGateway.send(DeviceDownlink)` 可发送读取、写入、配置、系统回复和OTA控制。旧的 `send(DeviceCommand)` 保留为指令快捷入口。两种方式均按DeviceKey定位，不允许调用方传原始下行Topic。属性读写、操作及事件经过物模型校验；配置、设备信息、OTA版本和进度按协议结构校验，后续业务域再做授权、状态和关联校验。

`ProtocolSupport`预留协议ID、传输能力、认证器及编解码器，未来供应商协议映射到相同核心消息；当前只有默认提供者。尚未实现动态插件加载、插件隔离、上传安装、热替换、按产品选择协议或同一监听器多协议协商。当前替换Bean是开发扩展点，不等于插件管理已完成。协议1.0严格拒绝未知字段，新增不兼容字段需明确升级版本及兼容策略。

目前已实现本文所有方法的对应方向编解码、传输、模型边界校验和自动校时，并有严格解析单元测试及真实 Paho MQTT 5 无订阅往返测试。正式接入已允许属性与事件上报进入 EventBus；启用 TDengine 并保存产品策略后，由独立 Worker 持久化属性观测及完整事件，配置与保留边界见 [历史存储组件](../backend/platform-components/storage-component/README.md)。持久化命令与集群接入能力见 [后端说明](../backend/README.md)。配置业务、OTA 任务／固件管理、设备侧 SDK、TLS 和离线消息仍未完成；历史 Inbox 的重试幂等不等于接入全链路零丢失。开发收件箱仅用于联调。
