# 产品与设备管理

Spring MVC、MyBatis、PostgreSQL／MySQL管理模块，随`management` profile启用。拥有产品、已发布物模型、设备注册、操作审计、历史策略／待写记录，以及显式cluster-commands模式下的命令与专用Outbox表。它提供持久化设备认证与接入目录；正常management启动默认启用MQTT。显式mqtt-dev仍使用独立开发目录。

## 范围与权限

产品设备接口从以下两个范围之一调用：

- `/api/management/catalog/platform`：仅平台超管可用；读取全部租户及无租户产品设备，创建产品时归属为 NULL。
- `/api/management/catalog/tenants/{tenantId}`：普通账号验证租户启用及有效成员；平台超管可直接访问。写操作使用资源实际归属。

普通账号的租户读取需要对应 RBAC 功能权限及成员关系。写入还要求租户角色 OWNER 或 ADMIN，VIEWER 仅可读。无租户数据即使拥有product／device功能权限，普通账号仍无权访问。客户端传入的额外tenantId字段不会改变服务端路由确定的归属。

| 权限点 | 操作 |
| --- | --- |
| product:read | 产品列表、详情和已发布版本查询 |
| product:write | 创建产品、保存草稿与基本信息 |
| product:publish | 发布模型版本、启停产品 |
| device:read | 设备列表与详情 |
| device:write | 登记设备及修改名称／生命周期 |
| device:credential | 查询和轮换凭证；登记设备生成凭证时也必须具备此权限 |

前端登记设备还需要product:read以选择已发布产品；角色配置应同时考虑页面读取和写入能力。平台超管自动拥有新增的注册功能权限，普通角色需显式配置。数据权限未新增，继续保留原有租户归属边界。

## API

下表路径相对上述范围前缀。写请求保留Session／CSRF校验。分页page从1开始，size默认20、最多100，query最多100字符。编码为1～64字符的字母、数字、点、下划线或连字符，首字符须为字母或数字，区分大小写并在同一范围唯一。产品与设备ID由服务端生成，编码、归属及设备产品绑定不能在编辑中更改。

| 方法与路径 | 请求与结果 |
| --- | --- |
| GET `/products` | query、page、size；返回items／total／page／size |
| POST `/products` | `{code,name,description}`；返回产品，初始空草稿、未发布、启用 |
| GET `/products/{id}` | 产品详情、draft、latestVersion、revision |
| PUT `/products/{id}/draft` | `{name,description,model,revision}`保存草稿及基本信息 |
| POST `/products/{id}/publish` | `{revision,confirmEmpty?}`冻结已保存草稿，服务端自动分配 v1、v2…；空模型需 confirmEmpty=true |
| PUT `/products/{id}/status` | `{enabled,revision}`启停产品 |
| DELETE `/products/{id}` | revision 查询参数；停用且无设备引用时可删除 |
| GET `/products/{id}/models` | 返回不可变 version、model、publishedAt 和 deviceCount（该版本绑定设备数，含停用／退役设备） |
| GET `/devices` | query、productId（可选）、page、size；返回分页设备列表 |
| POST `/devices` | `{productId,code,name,modelVersion}`；仅接受同范围启用产品的已发布版本，返回设备与当前凭证 |
| GET `/devices/{id}` | 设备安全投影，不返回哈希；凭证通过凭证接口读取 |
| GET `/devices/{id}/credential` | 按 `device:credential` 权限返回当前设备凭证 |
| PUT `/devices/{id}` | `{name,lifecycle,revision}`修改设备名称或生命周期 |
| DELETE `/devices/{id}` | revision 查询参数；删除设备登记，保留审计 |
| POST `/devices/{id}/credential` | `{revision}`轮换凭证，返回新的设备投影和当前凭证 |

名称1～100字符，description必填字符串、最多1000字符。新模型版本由服务端自动分配，不接收客户端指定的版本号。已有手写版本保持不变，标准 vN 数字标签的最大值加一作为下一个版本（例如已有 v9、v2，则生成 v10）；其他历史标签不参与递增。所有修改使用revision，过期或冲突返回409；范围内无对应资源返回404，越权403，模型／参数错误400。

模型JSON结构为`{properties:{},events:{},operations:{}}`，复用core的PropertyDefinition／EventDefinition／OperationDefinition／ValueSchema。定义ID须匹配Map键，使用字母开头的字母、数字、下划线，最多64字符。支持 BOOLEAN、INTEGER、DECIMAL、TEXT、ENUM、ARRAY，以及范围／长度、枚举选项、数组项数和单位元数据。定义格式与限制见 [物模型说明](../../../docs/thing-model.md)。每类最多100个定义，事件和操作各最多100个字段，序列化模型最多65536字符。

```json
{
  "properties": {
    "power": {"id":"power","schema":{"type":"BOOLEAN"},"access":"READ_WRITE"},
    "heatingLevel": {"id":"heatingLevel","schema":{"type":"INTEGER","minimum":1,"maximum":100},"access":"READ_WRITE"}
  },
  "events": {},
  "operations": {
    "setHeatingLevel": {"id":"setHeatingLevel","inputs":{"heatingLevel":{"id":"heatingLevel","schema":{"type":"INTEGER","minimum":1,"maximum":100},"required":true}}}
  }
}
```

前端先保存草稿再发布，不自动发布未保存的编辑值。物模型页区分“有未保存修改”“已保存但尚未发布”“与最新发布版一致”，并提供直接发布入口；版本页展示属性／操作／事件的新增、修改和移除摘要。空模型需要明确确认，后端也执行校验；草稿与最新版本内容一致时拒绝重复发布。自动编号、草稿快照、latestVersion、revision 和审计在同一产品行锁和事务内更新，并发请求使用旧 revision 返回409，不会额外消耗版本号。发布v2不改变v1，也不会自动升级已经绑定v1的设备。设备能力页分别展示当前绑定和产品最新发布版本；草稿与绑定版本不同不等于草稿未发布。设备模型迁移和固件 OTA 本次不实现，继续保持原绑定。数据库外键同时确保设备与产品范围一致，以及设备绑定确实存在的模型版本。停用产品会阻止发布、登记和设备激活，保留已有模型与设备记录。

## 二级产品目录

目录为平台共享数据，通过 `/api/management/catalog/shared/categories` 读取和保存。读取要求 `product:read`，维护要求平台超管及 `product:write`，保存使用 revision 防止覆盖并发修改。读取只返回数据库记录，不从代码生成兜底目录。

品类 ID 每段为 3～4 个小写英文字母，二级 ID 为父 ID 加连字符和英文码，如 `feed-warm`。ID 创建后固定，中文名称可修改；产品的 `configuration.category` 保存二级 ID。删除品类前检查所有租户及无租户产品的引用，改名不改变 ID 或产品关联。

GET `/products` 的 category 参数为空时返回全部，一级 ID 匹配其下全部二级品类，二级 ID 精确匹配。品类、名称及范围筛选均在分页前执行，total 使用同一条件。

## 产品配置

产品详情已提供「固件管理」，支持产品固件通道、标准／自定义版本规则、不可覆盖的固件包元数据、显式源版本和硬件兼容清单，以及版本启停。支持通过通用 file-component 上传至标准 S3 私有桶，计算实际大小与摘要、使用可信公钥验证签名并按需签发临时下载地址；外部 HTTPS 登记仍只校验元数据格式。设备升级任务与灰度发布仍待实现，详见[固件管理与 OTA 实施](../../../docs/firmware-management.md)。产品档案中的 firmwareVersion 不表示设备实际上报版本。

参考 JetLinks 的产品档案、设备类型、入网方式、传输协议和消息协议分层。产品的 configuration 保存档案配置，创建与保存草稿均可携带 configuration；旧客户端省略该字段时，创建使用默认值，更新保留已有配置。产品读取及列表返回配置，修改继续使用 product:write、租户角色、revision 和同事务审计。

配置包括 category、manufacturer、modelNumber、deviceType（DIRECT/GATEWAY/CHILD）、networkWay（ETHERNET/WIFI/CELLULAR/NB_IOT/LORA/OTHER）、transportProtocol（MQTT）、messageProtocol（nebula-mqtt-v1）、dataFormat（JSON）、firmwareVersion、supportUrl 与 installationNotes。普通文本最多100字符，文档地址及维护说明最多1000字符，配置序列化后最多8192字符；文档地址仅接受 HTTP(S)。

这些配置属于产品档案和接入规划，不随物模型版本冻结。网关／子设备已支持同范围拓扑绑定、网关代理上下线、上报和下行，设备端接入约定统一见[MQTT 默认设备协议](../../../docs/mqtt-protocol.md)；入网方式不配置网络监听。正常MQTT服务已使用登记凭证认证，设备需为ACTIVE且产品与所属租户启用。产品级数据策略已独立持久化，支持最新属性值开关、逐次历史采集与按天保留，部署及高级策略限制见[历史存储说明](../../platform-components/storage-component/README.md)。动态协议插件及按产品选择协议尚未实现。

参考：https://github.com/jetlinks/jetlinks-community/blob/master/jetlinks-manager/device-manager/src/main/java/org/jetlinks/community/device/entity/DeviceProductEntity.java

## 网关拓扑管理

设备详情的“网关拓扑”页签提供绑定与解绑：网关页输入子设备 ID，子设备页输入网关设备 ID。先分别创建 `GATEWAY`、`CHILD` 产品，发布各自物模型并登记设备，再建立同范围关系。

关系限定为同一实际租户／平台范围内的单层 `GATEWAY → CHILD`，每个子设备只绑定一个网关，每个网关最多1024个子设备。绑定时，双方设备、产品及所属租户须具备有效接入资格。更换网关须先解绑当前关系，不能覆盖旧绑定。已登记设备的产品不能更改设备类型，删除设备前须先解除关联的全部拓扑。

相对本模块范围前缀提供以下接口：

| 方法与路径 | 请求与结果 |
| --- | --- |
| GET `/devices/{id}/topology?page=1&size=20` | 返回 `{gateway,children:{items,total,page,size}}`；gateway 为当前设备的父网关或 null，children 为当前设备的下挂子设备；size 最多100 |
| PUT `/devices/{childId}/gateway` | `{gatewayId,revision}`；revision 为子设备当前修订号 |
| DELETE `/devices/{childId}/gateway?gatewayId=...&revision=...` | 使用子设备当前修订号精确解绑该关系 |

读取要求 `device:read`。绑定和解绑要求 `device:write`、资源范围写资格，返回拓扑还要求 `device:read`；普通租户成员必须是 OWNER／ADMIN。保留登录、CSRF、revision 冲突保护和事务审计。绑定／解绑递增子设备 revision，过期修订号或绑定关系冲突返回409。平台聚合目录读取租户设备后，写操作仍使用设备实际租户路径。

V29 在 PostgreSQL／H2 和 MySQL 迁移目录新增 `device_gateway_child` 表。数据库外键约束双方归属范围，阻止跨范围绑定和带拓扑的设备删除。关系写入按固定顺序取得产品锁，并使用 READ_COMMITTED 复核最新设备状态，避免等待并发写入后继续使用旧快照。

绑定不代表在线。绑定／解绑或设备资格撤销会使相关子设备会话重新校验；其他节点通过逐消息校验与周期复核收敛，不承诺跨节点零延迟撤销。子设备不能使用自身凭证直接连接 MQTT。网关认证、代理 Topic、上下线固定载荷、业务报文和断线重连规则统一维护在[MQTT 默认设备协议](../../../docs/mqtt-protocol.md)。

### 网关接入验证

`GatewayApiTest` 使用隔离 H2、真实 Flyway／MyBatis／HTTP 安全链和 Paho MQTT 5，覆盖拓扑类型、同租户授权、只读／CSRF限制、修订号冲突、删除保护、拒绝子设备直连、非法代理、子设备属性上报、下行执行回复、断线重连与解绑后拒绝下行。测试关闭 Worker 消费者，以独立验证接入路由，不替代 Redis 影子和历史存储验收。

`VertxMqttGatewayTest` 验证子设备身份与模型、父网关断线清理、独立会话令牌、旧令牌拒绝和父网关被接管时拒绝子设备下行。令牌场景使用内存 ownership 适配；尚未专门执行子设备的多 JVM 集群故障或真实 PostgreSQL／MySQL 并发验收。`OfficialMqttCodecTest` 验证子设备寻址、固定状态载荷与非法 Topic。

```powershell
mvn -B -ntp -s .mvn/settings-central.xml -pl platform-bootstrap -am '-Dtest=GatewayApiTest,VertxMqttGatewayTest,OfficialMqttCodecTest,JdbcDeviceSessionDirectoryTest,CatalogApiTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

## 凭证与生命周期

每台设备生成独立的16位小写字母身份，字符集排除容易混淆的 `i`、`l`、`o`；ClientId与用户名均使用该设备ID，MQTT密码使用后台生成的当前密钥。正常启动的MQTT 5服务使用数据库BCrypt哈希校验，拒绝伪造ClientId、跨租户身份和失效凭证。设备登记后默认启用（ACTIVE），可立即使用凭证接入；DISABLED／RETIRED不允许连接；产品或所属租户停用也拒绝接入。数据库同时保存BCrypt哈希和当前凭证明文，设备可通过凭证接口长期查看；登记和轮换响应设置Cache-Control:no-store。当前 practice 阶段暂不处理凭证长期暴露问题，后续再收紧存储和展示策略。

轮换替换旧哈希和明文并递增credentialVersion与revision，事务提交后通过本机事件断开当前进程内的MQTT会话；停用或退役设备也走同一路径。跨节点主动断连与远端缓存精确失效尚未完成，分离部署不能据此承诺旧凭证即时失效。网络失败不自动重试；设备详情可重新读取当前凭证。本版未提供凭证导出、批量导入或自动迁移归属。

生命周期为 ACTIVE、DISABLED、RETIRED。登记成功即启用，启用只表示允许接入，不代表在线；退役后不能重新启用或轮换凭证。V17 将历史 REGISTERED 设备转为 ACTIVE 并递增 revision，保留设备身份、凭证和模型，停用与退役设备保持原状态。产品和模型绑定保持不变。修改与审计同事务，产品级锁与revision保护发布、登记、状态和凭证更新的并发一致性。

## 验证

`CatalogApiTest` 通过 Spring 安全链、Flyway、MyBatis 和 H2 PostgreSQL 兼容模式验证归属、授权、模型版本、凭证、生命周期和并发冲突。H2 测试不替代真实 PostgreSQL 验证。

已接入登记凭证认证与注册解析、在线查询、属性／事件上行、影子／历史处理及属性写入／操作控制。Kafka 模式下上行在 broker 确认后完成接收；Dubbo 模式以匹配目标的持久投递提交为接收边界，均不等待全部业务消费者。Local 模式无持久消息保障。通用可靠交付、影子更新与通知原子发布、OTA和批量导入仍待实现。具体装配由bootstrap或独立服务负责，测试范围见[后端说明](../../README.md)。

## 设备影子（Redis）

设备详情的“设备状态”页签展示 reported（设备最新上报）、desired（平台期望）和 delta（尚未匹配的目标）。数据保存在 Redis 的 `nebula:shadow:v1:<租户编码>:<设备编码>` 键中，编码使用 Base64URL 避免分隔符碰撞；无租户设备使用 platform 范围。影子不设置 TTL，不以进程内缓存或 PostgreSQL 兜底；Redis 的持久化和备份策略决定这份状态的恢复能力。

相对本模块范围前缀提供：

- GET `/devices/{id}/shadow`：要求 device:read 和资源范围权限。未有数据时返回 version=0 的空影子。
- PUT `/devices/{id}/shadow/desired`：要求 device:read、device:write 和范围写权限，请求为 `{"version":0,"desired":{"power":true,"heatingLevel":70}}`。按属性合并，null 删除对应期望，省略的属性保留。只能设置绑定模型的 READ_WRITE 属性，并校验类型和数值／长度约束。停用设备、退役设备或停用产品不接受新目标。

version 是独立的影子版本，与设备登记 revision 无关；desired 和 reported 的有效更新均增加版本。Redis Lua 脚本执行原子比较并写入，过期管理写入返回409，需刷新后重新编辑。请求最多100个属性、64 KiB，完整影子上限512 KiB。Redis 未启用或连接失败返回503，不伪装成空影子或保存成功。

上报从已认证消息进入内部总线后由影子订阅者消费，仅接受 PropertyReport；核对登记范围、ACTIVE 生命周期、启用产品和固定模型。按属性合并并保存设备观测时间、平台接收时间和消息 ID，旧观测不覆盖新值；相同观测时间按消息 ID 排序。设备时间超过平台接收时间5分钟的报告拒绝，最近128个已合并消息 ID 用于重复抑制。Redis 写入失败会使该次消息处理失败。管理 API 不提供伪造 reported 的入口。

期望与上报值相等时从 delta 消失，但 desired 仍保留。delta 只表达值差异，不是命令受理或执行回执；本阶段不自动下发 desired，也不把影子更新当成在线证明。现有 mqtt-dev 清单没有对应管理登记时会跳过影子；正常management模式使用数据库登记设备目录，属性上报通过现有消息总线进入影子，Redis失败会使影子消费失败；Kafka模式下不会撤回已完成的上行PUBACK，而由消费重试／失败保留处理。Local模式默认等待订阅处理结果。

## 设备/模型三级缓存

缓存内核位于 `infrastructure.cache.ThreeLevelCache<K,V>`，不依赖设备领域。设备连接通过 `DeviceConnectionCache` 适配，物模型通过 `ThingModelCache` 适配；后续权限、租户和产品配置可以复用同一内核，只需提供缓存 key、值类型和数据库加载函数。

接入鉴权和物模型校验使用同一份只读快照缓存：请求先查进程内 Caffeine（L1），未命中再查 Redis（L2），最后回源 `device_registration + device_model_version`（L3）。数据库始终是权威数据源；L1未命中后，Redis未启用、超时或反序列化失败会回源数据库，数据库查询失败仍会使接入失败。该降级不保证已有L1中的旧资格即时失效。

缓存值只包含设备资格、BCrypt 密码哈希和已发布模型 JSON，不包含设备密钥明文、影子状态或控制命令。L1 默认最多 10000 条、空闲 5 分钟；L2 默认 30 分钟，可通过 `DEVICE_CACHE_LOCAL_MAXIMUM_SIZE`、`DEVICE_CACHE_LOCAL_TTL` 和 `DEVICE_CACHE_REDIS_TTL` 调整。Redis key 使用版本前缀和全局 generation，产品停用等全量失效无需执行危险的 `KEYS` 扫描。

设备凭证轮换、设备生命周期变更、删除会在事务提交后精确失效；产品启停会递增共享 generation。事务提交后失效避免写事务回滚时提前清除有效快照。精确失效只清除本机L1及共享L2的对应键，不会主动清除其他节点已有的L1；产品全量失效通过共享generation在缓存访问时检查，检查间隔为250毫秒。Redis异常时不保证跨节点失效及时收敛，不能将该缓存机制视为完整的跨节点资格撤销。

## 设备控制与结果查询

相对本模块范围前缀提供以下后台接口，写入保留Session／CSRF、device:read／device:write、实际资源归属及租户OWNER／ADMIN校验；查询使用device:read与资源范围校验。仅启用设备和产品接受新命令，输入按设备绑定模型校验。

| 方法与路径 | 行为 |
| --- | --- |
| POST `/devices/{id}/downlinks/properties` | `{properties:{...}}`下发可写属性 |
| POST `/devices/{id}/downlinks/operations` | `{operationId,inputs:{...}}`调用物模型操作 |
| GET `/devices/{id}/downlinks/{messageId}` | 查询传输与执行状态，不把PUBACK视为执行完成 |
| PUT `/devices/{id}/shadow/desired-and-dispatch` | `{version,desired:{...}}`先保存期望，再下发非null属性；分别返回shadow、downlink或dispatchError |

单独保存desired不会自动下发；desired-and-dispatch的两步不具备跨存储原子性。默认模式使用本机发送器和有界短期内存结果，不支持跨进程查询或重启恢复；分离部署须启用下述cluster-commands模式才具备跨节点命令闭环。属性读取、设备配置和OTA尚无对应后台业务接口。

## 集群命令持久化

显式 `cluster-commands` 模式使用本模块的命令表、状态转换历史和专用 Outbox；原有属性写入与操作调用 API 在授权／模型校验后保存命令及待路由工作。未启用时保留原本进程内的短期命令关联，不把该模式当成跨节点能力。新模式保存提交账号、设备范围、逻辑 ID、有效期、目标会话代次与发送状态，可跨管理节点和重启查询。

Worker 发布及路由命令，access 领取一次发送权并关联认证反馈；重复事件或回执不会重复发送或回退最终状态。可能发送后的异常记为 UNKNOWN／TIMED_OUT，禁止自动重发；迟到最终回执可以解决未知状态，历史保留。模型、设备、replyTo、反馈类型与 commandId 继续核对。没有有效会话的命令以 OFFLINE 明确结束，本阶段不提供离线命令队列。

启动、迁移、状态、限制与真实六进程验证命令统一见 [后端说明](../../README.md#持久化命令与跨节点下行)。当前影子 desired 更新与命令保存不在同一事务，不能将 desired-and-dispatch 接口解读为 Redis 与数据库原子提交。记录清理／保留、历史查询页面和生产容量验证尚未交付。
## 生产与交付管理

管理端入口为 `/catalog/platform/production` 或 `/catalog/tenants/{tenantId}/production`，页面权限为 `page:production`。普通账号只操作本租户，读取需要 `production:read`；创建、生成、烧录确认、测试、放行和交付需要 `production:write`，且为有效租户 OWNER／ADMIN。平台超管可跨租户查询和操作；批次继承所选产品的实际归属，客户端不能另传归属覆盖它。

首版流程：新建批次 → 批量生成设备 → 导出烧录凭证 → 逐台确认烧录 → 登记出厂测试 → 整批放行 → 登记交付。

- 批次固定编码、产品、已发布模型、计划数量、硬件版本和目标固件版本。单批1～50台，设备编码为“批次编码-四位序号”；批次编码在所属范围唯一。生成操作同步执行，设备、凭证、批次明细和记录在同一事务保存，任一编码冲突全部回滚。生成设备复用现有登记用例，额外需要 `device:write`、`device:credential`；创建时查询产品需要 `product:read`。
- 生成后的设备为ACTIVE，可以用于联网测试；生产状态独立于设备生命周期、在线状态和App绑定。批次状态为PLANNED／PRODUCING／RELEASED／DELIVERED，放行不是设备接入准入开关。
- 凭证通过显式POST导出，要求 `production:credential`、`device:credential` 和范围写资格，保留CSRF、`Cache-Control: no-store`及导出操作记录。导出当前凭证和版本，JSON文件包含密钥；列表、详情与生产记录不返回密钥，前端不在浏览器存储中保存凭证。生成前和交付后不提供批次凭证导出。
- 烧录和测试均为人工登记证据，不连接烧录器或自动检测硬件。烧录确认记录当前凭证版本、工位、操作人与说明；确认烧录会清空当前测试结果，历次记录保留。出厂测试要求当前版本已烧录，记录PASS／FAIL、工位和必填的测试项目／结果说明。目标固件版本是生产计划信息，人工测试结果不等于平台已验证设备实际固件。
- 放行需要每台设备使用当前凭证完成烧录并通过测试，设备、产品和租户均具备有效接入资格。交付再次复核这些条件，记录交付对象、单号、时间与操作人。轮换凭证后旧测试不能用于交付；重新确认烧录／登记测试会将已放行批次退回生产中，必须重新放行。交付后不能通过生产接口补写或覆盖烧录、测试和交付证据；设备正常生命周期维护仍保留。
- 交付仅记录物流／业务交接，不迁移设备租户，不建立App用户绑定或家庭共享。首版整批放行、整批交付，不提供拆批、部分交付、批次取消、自动烧录、自动测试或首次联网激活流程。

相对本模块范围前缀：

| 方法与路径 | 请求与结果 |
| --- | --- |
| GET `/production/batches` | query、page、size；分页批次列表，page从1起，size默认20、最多100 |
| POST `/production/batches` | `{code,name,productId,modelVersion,quantity,hardwareVersion,firmwareVersion}`创建批次，201 |
| GET `/production/batches/{id}` | 批次、最多50台设备明细与操作记录；eventPage从1起，每页100条，totalEvents用于翻页 |
| POST `/production/batches/{id}/generate` | `{revision}`生成整批设备，不在响应中返回凭证 |
| POST `/production/batches/{id}/credentials` | `{revision}`导出当前凭证列表；导出留痕，不递增批次revision |
| POST `/production/batches/{id}/devices/{deviceId}/burn` | `{revision,credentialVersion,station,note}`登记人工烧录确认 |
| POST `/production/batches/{id}/devices/{deviceId}/tests` | `{revision,credentialVersion,result,station,note}`登记人工测试；result为PASS／FAIL |
| POST `/production/batches/{id}/release` | `{revision,note}`整批放行，note为必填放行依据 |
| POST `/production/batches/{id}/deliver` | `{revision,recipient,deliveryRef,note}`登记整批交付 |

所有变更使用批次行锁、产品行锁和revision复核；业务写事务使用READ_COMMITTED，避免MySQL默认重复读快照在等待锁后继续使用旧证据。普通变更递增revision，过期版本或非法状态返回409；同一生成请求重复提交不会再生成设备。`note`在烧录和交付时可为空字符串，测试和放行时必填；工位最多100字符，说明最多1000字符，交付对象最多200字符，交付单号最多100字符。

V24新增生产批次、明细和事件表，V25注册页面菜单及所需能力；不自动为已有普通角色授权。数据库外键保留追溯关系：有生产批次的产品和纳入生产的设备不能通过现有删除接口物理删除，可按原规则停用或退役。生产表不复制设备密钥。迁移不删除现有业务数据，也不自动为已有设备补造生产记录。

`ProductionApiTest` 使用H2、真实Flyway、管理安全链与设备登记用例验证完整流程、凭证导出、跨租户／只读／CSRF限制、重复及并发生成、冲突回滚、版本变化、返工和删除保护；`ProductionView.test.ts`验证操作门槛、人工记录、显式凭证下载和失败保留输入。H2验证不代替真实PostgreSQL／MySQL并发验收；硬件烧录与实际出厂测试仍需现场执行。
