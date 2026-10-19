# 面向管理端、App 与第三方应用的实时订阅

## 1. 架构定位

实时订阅是平台的北向应用能力。设备接入、业务状态、实时分发是三个独立边界：

`设备会话 / 业务写入 → 状态提交 → 内置 EventBus → 对外订阅网关 → WebSocket → 管理端 / App`

设备 MQTT 接入用于认证设备、校验协议和物模型、维护可信会话、接收上报和投递下行。应用客户端通过平台身份访问授权资源，不持有设备凭证，不订阅接入服务内部主题。WebSocket 是对外传输适配器；将来可以替换或补充 SSE、Webhook，不改变设备接入链路。

本版采用**状态失效通知 + HTTP 快照**。连接承载“哪些状态变了”，快照 API 承载“当前状态是什么”。在线变化可以快速合并；属性采用已有影子版本控制。这适合列表、设备详情和 App 前台状态展示，避免对外绑定 MQTT 报文和内部 Java 对象结构。

状态展示与可靠事件消费应采用不同契约。告警、计费、审计、设备事件历史需要持久化事件流与消费游标，不能依赖这个允许合并的实时通道。

## 2. 已落地的边界

| 层 | 当前实现 | 责任 |
| --- | --- | --- |
| 会话事实 | `VertxMqttGateway` 连接观察口 | 认证并绑定会话后上线；当前会话关闭后离线；旧连接关闭不覆盖新连接 |
| 在线查询与通知 | `DevicePresence` | 未启用共享目录时读取本机在线集合；cluster模式读取有效会话归属；连接变化发布EventBus通知 |
| 属性事实 | `DeviceShadowService` | reported / desired 成功 CAS 后发布 `DeviceStateChanged`；无变化、校验失败、CAS 失败不发布 |
| 事件适配 | `StateEventBridge` | 将业务变更转换为供订阅网关消费的 EventBus 事件 |
| 订阅策略与通道 | `SubscriptionGateway` | 资源解析、票据、持续授权、限额、通知合并、慢连接隔离 |
| 接入组装 | `SubscriptionConfiguration` / `SubscriptionController` | HTTP、WebSocket、会话认证和同源检查 |
| Web 客户端 | `deviceSubscription.ts` | 页面组件共用一个连接、资源集合重建、心跳、重连、快照恢复 |

本版对外公开 `presence` 与 `shadow` 两类变更。属性展示仍读取现有 shadow API；在线快照由新的 presence API 提供。设备模型事件历史、配置、OTA、命令结果的订阅属于后续扩展，当前已有下行结果查询继续工作。

实现放在 bootstrap 的 subscription 包做运行期组装，设备业务模块只发布业务事件。形成多个传输适配器、多个业务订阅源时，应将订阅协议、策略接口和投递引擎抽取为独立 subscription-component；bootstrap 保留授权与设备投影的适配组装。

## 3. 对外协议 v1

### 3.1 获得连接票据

完成管理端会话登录后，携带现有 CSRF token：

```http
POST /api/management/realtime/tickets
Content-Type: application/json
```

```json
{"devices":[{"tenantId":"tenant-a","deviceId":"device-1"}]}
```

```json
{"ticket":"一次性随机值","expiresAt":"2026-10-17T00:00:30Z"}
```

`tenantId: null` 表示平台目录授权入口。平台管理员从全局列表选中租户设备时，服务端会解析真实归属，绑定实际 tenantId + deviceId。客户端不能传入内部 topic、通配符、任意账号、事件源或设备凭证。

票据有效 30 秒，一次消费，绑定签发账号和 HTTP session；当前票据与登录Session均在本机，签发、握手及后续请求须固定到同一management实例。单连接最多 100 个明确设备；每账号最多 10 个待用票据与活动连接；进程最多 2000 个待用票据与活动连接，建立连接时再次检查活动连接限额。票据响应不可缓存。

### 3.2 建立 WebSocket

```text
wss://平台域名/api/management/realtime/socket?ticket=一次性随机值
```

仍需携带签发时的登录 session cookie。WebSocket 沿用 Spring 默认同源检查，认证交由现有安全链；POST 签发保留 CSRF 校验。票据是附加的短期授权证明，不是跨账号或跨会话登录凭证。代理不能记录票据查询参数，部署示例已关闭此路径的访问日志。

服务器先注册订阅，再发送：

```json
{"protocol":"nebula.subscription.v1","type":"ready","resyncRequired":true}
```

收到 ready 必须重新读取已订阅资源的 HTTP 快照。即使页面已提前加载过一次，也需要再同步：这样能覆盖初始读取和订阅生效之间的空窗。订阅安装后的通知也应触发快照读取；ready 与通知到达先后不影响最终状态。

变更通知示例：

```json
{
  "protocol":"nebula.subscription.v1",
  "type":"invalidate",
  "eventId":"平台生成的事件标识",
  "tenantId":"tenant-a",
  "deviceId":"device-1",
  "kind":"shadow",
  "occurredAt":"2026-10-17T00:00:00Z"
}
```

presence 通知结构相同，kind 为 `presence`。平台目录设备的通知 tenantId 使用现有内部范围标识 `platform`，请求资源中的平台目录使用 null；客户端已处理此映射。通知不包含内部主题、设备原始报文、属性载荷、设备密钥或完整模型。

### 3.3 获取快照

```http
GET /api/management/realtime/presence/{deviceId}?tenantId=tenant-a
GET /api/management/catalog/tenants/tenant-a/devices/{deviceId}/shadow
```

平台目录 presence 请求省略 tenantId。返回 `{"state":"ONLINE"}`、`OFFLINE` 或 `UNKNOWN`。未配置共享目录时，接入组件关闭或未运行返回UNKNOWN，本机接入运行且没有当前会话返回OFFLINE。配置共享目录后，management即使没有本地MQTT监听也按有效会话归属返回ONLINE／OFFLINE；共享存储查询失败走请求错误，不伪装成OFFLINE。在线表示当前平台持有连接，不保证设备可控、业务健康或命令执行成功。

shadow 沿用现有版本与字段时间戳。实时读取只更新展示数据；编辑表单保留最初读取时的版本与目标集合，直到用户显式刷新或保存成功，防止实时更新把旧编辑伪装成基于新版本的写入。HTTP 409 时保留编辑并要求用户刷新、确认后重试。

### 3.4 心跳与连接恢复

服务器每 15 秒发送 `{"protocol":"nebula.subscription.v1","type":"ping"}`，客户端发送文本 `pong`。45 秒未收到 pong 的连接关闭。客户端连续 45 秒未收到服务器消息也主动断开。其他客户端文本命令被拒绝，取消订阅通过关闭连接完成；本版资源集合变化时换票重建连接。

断线后客户端将在线状态标为未知、属性标为最近快照；采用带随机扰动的指数退避，约 1 秒起步，基准上限 30 秒。每次重连重新获取票据，ready 后重新拉取快照。400 / 401 / 403 / 404 签发失败停止自动重试，等待会话或资源范围改变。页面离开、退出登录会清理连接与请求。

## 4. 授权与流量隔离

签发票据、握手消费，以及每次发送通知或心跳前都会检查账号状态、device:read、租户成员资格和资源是否仍然存在。退出登录、成员移除、账号禁用或权限撤销后，后续发送会被拒绝，空闲连接通常在下一个心跳时关闭。权限检查与发送之间不是分布式原子操作，不能承诺撤权瞬间收回已在途的数据。

EventBus 回调只做资源匹配与入队；数据库复核和网络发送使用独立的 4 个发送工作线程与 1024 项任务队列。每连接按“设备 + 状态类别”合并未发送通知，最多 200 个状态项以及 ready / heartbeat 两个控制项。WebSocket 并发发送使用 Spring `ConcurrentWebSocketSessionDecorator`，发送缓冲上限 64 KiB，发送超时配置 5 秒，定时巡检会关闭超时连接。关闭连接也使用独立的有界执行器。只允许短 pong 文本帧。

这组限额约束内存和线程使用，避免慢客户端反压设备上报。由于容器网络关闭耗时和操作系统缓冲存在差异，生产部署仍需做慢消费者压力测试，配置容器及代理的网络超时。当前每次发送复核整个订阅集合、列表快照逐设备读取，优先保证清晰的安全边界；大规模接入时应改为批量授权、带撤权版本的短期授权缓存、批量快照和分片资源索引，并测量数据库与扇出成本。

Spring 的同源和并发发送约束参考：[Spring WebSocket API](https://docs.spring.io/spring-framework/reference/6.2/web/websocket/server.html)。

## 5. 一致性与交付保证

- 只在已提交状态上产生 shadow 通知，避免与原始 property/report 的异步投影发生竞态。
- 同一设备重连接管时，旧会话关闭不会发布虚假的离线；网关停止会清理在线投影。
- 通知可以合并、重复、乱序；eventId 可用于诊断，不是可恢复消费游标。客户端读取最新状态，以 shadow.version 防止旧读取覆盖新状态。
- EventBus已支持Local／Kafka：Local无持久化；Kafka通过各management节点的独立广播消费组转发本地授权连接。cluster模式的在线快照读取共享会话目录。票据、登录Session和WebSocket连接仍在本机，management重启后需重新登录／建连；对外通知没有持久化回放、离线补投或恰好一次保证。
- 状态提交与事件发布尚未使用 outbox，进程在两者之间崩溃可能丢通知。该情况依赖连接恢复后的快照收敛；对必须完整消费的事件不能使用此保证。
- 三服务已验证access经Kafka上行、worker更新影子、management转发授权通知；该验收使用H2及测试影子适配，不能替代真实Redis／生产数据库验收。多management实例仍需固定客户端归属，随机负载均衡前须补齐共享登录与票据；完整集群实时状态验收尚未完成。

## 6. App、第三方应用与集群演进

### 客户端身份

当前可运行的入口沿用管理端 session 身份。后续 App 可以先用同一登录会话和协议；正式 App / 第三方开放平台应增加 OAuth / OIDC 或应用令牌的票据签发适配器，将登录主体映射为统一的 subscription principal，仍走资源授权。设备绑定关系、家庭成员共享、应用 scope、用户撤销授权都属于授权策略层，不能直接复用“知道 deviceId 即可订阅”的假设。

原生 App 不应内置设备密钥或复用平台管理员账号。移动端进入后台后 WebSocket 不保证常驻；恢复前台需重新订阅并同步快照，离线告警走 APNs / FCM 或可靠通知服务。Webhook 使用独立签名、重试、幂等、死信与应用级限流，实现可靠第三方事件交付。

### 业务事件

扩展 `alarm` / `command` / `ota` 等类型时，先确定是状态投影还是不可合并事件：命令当前结果可以通知后查询；告警发生与解除需要持久化记录、顺序键、schemaVersion、租户与资源键、eventId、保留期和消费游标。可靠流采用事务 outbox → 持久化消息流 → 各投递适配器，重放仍重新鉴权。不能把本版失效通知直接升级为可靠事件承诺。

### 多节点

1. 已接入Kafka跨节点传输，实时网关按节点独立广播消费并过滤本地连接；尚未实现设备到网关的订阅归属目录及定向分发，广播成本随节点数增长。
2. cluster模式已实现带nodeId、sessionId、epoch及租约的共享会话目录，在线快照据此查询。租约失效可使后续快照显示离线，但当前没有保证节点崩溃／租约到期必然产生可靠的presence通知。
3. 共享登录和票据尚未实现；当前须固定management实例。后续票据需跨节点验证、原子单次消费且有期限，不能直接共享对本机HttpSession对象的引用；撤权策略也须通过多节点验收。
4. 引入租户 / 应用 / 账号 / 连接多级限额，批量快照、投递延迟及错误指标；对持续失败客户端限流，避免大量重连放大数据库压力。
5. 观测连接数、票据拒绝、鉴权耗时、通知延迟、合并次数、队列饱和、慢连接关闭、重连次数和快照失败。当前尚未暴露这些专用指标。

## 7. 本地验证

管理端通过 Vite 代理自动升级 WebSocket；生产 Nginx 示例包含 Upgrade 配置。启动管理服务与控制台后，在设备列表或详情打开页面，再使用已有 MQTT 示例让已登记设备连接、上报、断开。列表 / 详情在线状态与属性面板将自动更新；网络恢复后重新同步。影子仍要求现有 Redis 配置。

自动化覆盖实际嵌入式服务器的登录、CSRF、跨站拒绝、一次性票据握手、事件通知与退出关闭；单元测试覆盖设备范围、跨租户隔离、撤权、会话失效、客户端不允许发布；前端覆盖连接复用、资源重建、心跳、重连、拒绝停止以及实时刷新保留编辑并发版本。
