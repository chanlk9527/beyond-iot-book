# EventBus：Kafka 与 Dubbo 定向路由

## 只读管理面板

平台超管可从控制台「事件总线」（`/platform/eventbus`）查看 Dubbo 集群。管理 API `/api/management/eventbus` 每次都校验平台超管身份；仅 management 角色开放。Kafka／local 模式明确显示集群观测未提供。

共享目录提供节点状态、租约、订阅与积压；各节点通过带短时签名的 Dubbo `inspect` 返回内存运行快照。管理端最多并发采集 4 个节点，一轮最多等待 3 秒，汇总结果缓存 5 秒。面板显示采集覆盖范围，失败节点的指标为未知，保留最近成功采集时间；实例标识变化表示新进程，累计计数随重启重置。节点列表仅展示当前发现且租约有效、未退出的实例：Nacos 模式使用当前发现的进程集合，显式 JDBC 模式使用有效租约；重启前的旧进程记录不展示，也不计入节点总数。Nacos 连接不可用时本轮采集失败，不回退展示历史节点。当前实例采集失败仍显示不可达。数据库中的历史进程状态继续保留用于投递恢复。筛选后按租约倒序最多展示 100 个实例，订阅每页 20 条，设备样例最多 20 个、成员最多 100 个，积压详情按需查询并短时缓存。统计不读取消息正文或共享凭据。

发布、匹配、持久化逻辑投递、远端确认条目和回调完成分别计数，累计值包含重复调用／重试，不是严格守恒的唯一消息数。回调完成表示本地处理成功，不保证业务副作用恰好执行一次。当前提供累计计数、平均回调耗时和队列使用情况；不保存历史时序或异常堆栈，不提供排空、停订阅或重放操作。

## Dubbo 发布侧订阅路由

订阅来源范围由订阅者指定，与共享／广播消费方式独立：

```java
// 每个 access 只存储自己发布的设备消息。
bus.subscribe(
    SubscriptionSpec.shared("access-storage", "device/**")
        .withSourceScope(SubscriptionSpec.SourceScope.LOCAL),
    storageHandler
);

// 多个规则实例分担事件，允许本地及远程来源（也是旧 API 的默认值）。
bus.subscribe(SubscriptionSpec.shared("rules", "device/**"), rulesHandler);

// 当前实例只接收其他进程最初发布的事件。
bus.subscribe(
    SubscriptionSpec.shared("remote-worker", "device/**")
        .withSourceScope(SubscriptionSpec.SourceScope.REMOTE),
    remoteHandler
);
```

`LOCAL`、`REMOTE`、`ALL` 根据持久记录的原始 `source_node` 与接收进程比较，故障接管的转发节点不会改变消息来源。共享组各成员必须使用相同定义及来源范围；`REMOTE` 共享订阅会排除原发布进程，即使消息已经接收，也不能由原发布进程抢占处理。没有合格远端成员时保留共享积压，等待其他成员加入，不回退为本地执行。广播则逐目标过滤来源。

`LOCAL` 订阅绑定进程，每个进程可用同名订阅处理自己的消息，不跨节点竞争；仍沿用持久化、后台回调和重试。进程退出或该订阅关闭后，剩余本地投递可取消，不迁移给新进程。需要跨进程故障恢复的存储应使用 `ALL + SHARED`。新 API 的来源字段默认 `ALL`，旧定义可继续读取；V28 增加来源列。升级时应先停止发布并协调升级所有 EventBus 节点，不能让旧节点参与新范围订阅。

`LocalEventBus` 中所有事件都是本地来源，因此 `REMOTE` 订阅不会收到事件。Kafka 模式目前只支持 `ALL`；对 `LOCAL`／`REMOTE` 显式拒绝注册，避免共享消费组先取走消息再过滤造成消息丢失。

`EVENTBUS_MODE=dubbo` 启用独立于 Kafka 的投递模式；不使用 HTTP、Kafka topic 或每设备消费者实现远端投递。现有 `kafka` 和 `local` 模式保留；运行中的集群和启动快捷入口不会自动切换。一个部署内所有参与消息交互的角色应使用同一模式，当前没有 Kafka／Dubbo 双向桥接或自动追赶历史位点。

切换模式前先停止新的上行和业务写入、排空原模式积压与在途投递，再统一切换所有角色；原 Kafka 位点或 Dubbo 待投递记录不会由另一种模式自动处理。Flyway 可解析切换前后的迁移，只表示数据库模式兼容，不代表消息积压已经迁移。

链路为：可信设备事件 → 发布节点内存订阅索引 → 命中的逻辑订阅 → SQL 持久化待投递 → Dubbo `dubbo://` 批量 RPC → 持久化接收状态 → 后台业务回调。Nacos 负责基础实例注册、健康实例发现及地址变更；SQL 控制面维护命名空间、逻辑订阅、成员和业务进程状态／租约。EventBus 按订阅选定进程 ID，再使用 Nacos 提供的地址通过 Dubbo 定向访问，关闭 injvm 与框架自动重试。订阅索引按全局／租户／租户产品／租户设备拆分，内部使用主题树；未命中事件无需编码、消息级 SQL 写入或 RPC。产品选择器已实现，字段与标签选择器尚未实现。

### 产品级订阅

资源范围支持全局 → 租户 → 产品 → 设备。产品订阅无需枚举设备，也无需在产品新增设备时重新注册：

```java
Subscription productEvents = bus.subscribe(
    SubscriptionSpec.shared("product-warmer-v1", "device/**"),
    EventSelector.product("tenant-a", "warmer"),
    event -> handle(event));
productEvents.ready(); // 等待异步结果成功后再标记订阅生效。
```

此订阅接收 tenant-a 下 warmer 产品所有设备的 `device/**` 事件；将主题改为 `device/*/property/report` 可只接收属性上报。使用 `new EventSelector("tenant-a", "warmer", Set.of("d1", "d2"))` 时，租户、产品和设备集合取交集。产品必须指定租户，其他租户的同名产品不会命中。共享／广播和 LOCAL／REMOTE／ALL 仍为独立选项。

`EventMessage.productId` 为独立元数据，不改变既有业务主题。MQTT 网关从已经校验的设备描述（包括子设备自己的产品）绑定产品身份，映射器将其传入事件信封，不信任设备报文自报的产品或 payload／headers 中的同名字段。未提供产品的旧事件仍可被原有订阅消费，但不匹配产品范围；应用自行发布产品事件时也须显式提供可信产品 ID。

Local 在本地匹配资源范围；Dubbo 在发布端使用租户产品索引匹配；Kafka 在消费端按相同选择器过滤，不为产品创建物理 topic 或消费组。Kafka 同一共享组各成员必须使用相同主题和资源范围，不同产品需求应使用不同订阅名称。管理面板列表和详情显示产品范围。

订阅定义仍保存为 JSON，无需新增数据库迁移；旧定义缺失 productId 时保持全部产品。新旧节点不要混用：启用前须协调升级所有 EventBus 节点，旧版本不能识别新的产品信封与选择器字段。

### Nacos 实例发现

Dubbo 模式默认 `EVENTBUS_DUBBO_DISCOVERY=nacos`。每个进程通过 Nacos Naming SDK 注册一个临时实例，端口为自己的 Dubbo 端口；实例元数据只含 EventBus namespace、进程 UUID、运行角色和协议。地址变更通过 Nacos 订阅通知更新本地缓存，消息投递路径不逐条查询 Nacos，也不把主题、产品／设备集合或事件正文放入 Nacos。

业务订阅仍在 SQL 中持久保存，Worker 全部离线不会删除长期共享需求。SQL 的 `STARTING/READY/DRAINING/OFFLINE`、订阅版本传播、源进程租约及领取令牌继续保护业务就绪和恢复；它们不能由 Nacos 的网络在线状态替代。只有被 Nacos 发现且 SQL 标为 READY 的成员才进入远端投递候选。SQL 中保留的 endpoint 仅供诊断与显式兼容模式使用，Nacos 模式的投递和监控 RPC 不回退到它。

订阅路由需要选定具体进程，因此当前使用 Naming SDK 发现实例后保留 Dubbo 定向引用；Dubbo 内置注册中心仍为 `N/A`，避免重复注册及任意负载均衡把事件送到不具备该订阅的实例。Nacos 控制台显示一个服务下的多个进程实例，无需按产品／设备创建 Nacos 服务。

配置项：`NACOS_SERVER_ADDR`（默认 `127.0.0.1:8848`，多地址逗号分隔）、`NACOS_NAMESPACE`（namespace ID，默认空/public）、`NACOS_GROUP`（默认 `BEYOND_IOT`）、`NACOS_EVENTBUS_SERVICE`（默认 `nebula-eventbus`）、`NACOS_USERNAME`、`NACOS_PASSWORD`。同一部署的节点须使用一致的 Nacos 隔离设置和 `EVENTBUS_NAMESPACE`。密码仅通过环境或受保护配置传递。

初次注册必须获得服务端确认，并有界等待最多五秒使本进程出现在发现视图中；失败即启动失败。运行时 Nacos SDK 报告断连或本实例不在健康发现视图时，readiness 失败并拒绝新的发布、接收与后台领取；已提交任务保留，恢复连接后可继续。现有在途回调可以完成。断连检测依赖 SDK，不承诺瞬时感知。关闭时注销临时实例并释放监听与客户端；排空期间依旧由 SQL DRAINING 阻止路由，排空完成后注销。

`EVENTBUS_DUBBO_DISCOVERY=jdbc` 仅显式保留旧部署／隔离测试的 SQL 地址发现，不是 Nacos 失败后的自动降级。部署内应统一发现模式。接入 Nacos 不改变投递表结构、SQL 容量锁、回调并发或可靠交付语义，也不会自动重启已运行的节点。

本地已有 Nacos 可直接复用；没有时，在仓库根目录执行 `docker compose -f practice/infra/compose.nacos.yml up -d`。该编排固定 Nacos 2.5.1、仅绑定回环地址、关闭鉴权，只供本机开发；正式部署使用受保护且具备鉴权和可用性保障的 Nacos。2.x SDK 需要可访问服务端 8848 和客户端 gRPC 9848 端口（修改主端口时同步处理 gRPC 偏移）。本机 Windows 快捷脚本默认 WSL 编排已经包含 Nacos，无需再启动同端口的仓库编排。若 Windows 的 8848 可达而 9848 不通，应检查 WSL 端口转发；也可将 NACOS_SERVER_ADDR 配置为 Windows 可直连的 WSL 主机地址（该地址可能随 WSL 重启变化），不能只以 HTTP 控制台可访问判断 SDK 可用。

### 配置与启动

需要可访问的 Nacos、共享 PostgreSQL 或 MySQL、`management` profile 及每个实例可互访的独立 Dubbo 端口。管理数据库统一纳入 V26 路由表、V27 生命周期与 V28 来源范围迁移，使切回 Kafka／local 后 Flyway 仍能解析已应用版本；MySQL 使用 LONGTEXT 和 UTC DATETIME(3)，PostgreSQL 使用 TEXT 与 UTC timestamp。先启动 management 完成迁移，再启动其他角色。服务角色仍由原入口控制，Dubbo 端口与用于探针的 HTTP 端口相互独立。

```powershell
$env:EVENTBUS_MODE = 'dubbo'
$env:NACOS_SERVER_ADDR = '127.0.0.1:8848'
# 启用鉴权的 Nacos 另设置 NACOS_USERNAME / NACOS_PASSWORD。
$env:EVENTBUS_DUBBO_CREDENTIAL = Read-Host '输入所有节点共享的随机凭据（至少32字符）' -MaskInput
$env:EVENTBUS_DUBBO_HOST = '127.0.0.1'
# 在不同终端启动，沿用已有 DATABASE_PASSWORD、REDIS_URL 等业务配置。
./scripts/Start-Service.ps1 -Role management -InstanceId management-a -EventBusMode dubbo -DubboPort 20880
./scripts/Start-Service.ps1 -Role access -InstanceId access-a -EventBusMode dubbo -DubboPort 20881
./scripts/Start-Service.ps1 -Role worker -InstanceId worker-a -EventBusMode dubbo -DubboPort 20882
```

跨机器将 `EVENTBUS_DUBBO_HOST` 设置为本机可绑定且其他节点可达的 IP／主机名；不能使用 `0.0.0.0` 作为目录地址。同机端口不能重复。实际目录进程 ID 在配置名称后追加随机 UUID，旧进程租约过期后不得恢复，需要重启形成新身份。`EVENTBUS_MAXIMUM_PENDING` 默认 100000，控制整个 namespace 未完成的逻辑投递数，达到上限时发布失败并向调用链施加背压；不能将发布失败当作 MQTT 成功接收。

固定 RPC 方法只传字符串／字节数组，事件沿用明确 schemaVersion、payloadType 的 JSON codec，不传任意 Java 类名。请求使用绑定 namespace、投递 ID、路由 ID 与消息内容的 HMAC-SHA256，凭据本身不随请求发送；错误签名、错误接收目标及与持久记录不符的内容被拒绝。该认证不提供消息加密，端口应部署在受保护的内部网络；本阶段没有配置跨信任边界的 TLS 或证书生命周期。

### 订阅与生效

```java
Subscription selected = bus.subscribe(
    SubscriptionSpec.shared("rules-input-v1", "device/*/property/report"),
    new EventSelector("tenant-a", Set.of("device-1", "device-2")),
    event -> handle(event));
selected.ready(); // CompletionStage：等待所有当前有租约的发布节点应用订阅与成员版本。
```

调用方必须等待 `ready()` 成功，才将依赖此订阅的规则标记为生效；发布节点在此前可能没有新需求。启动先装载快照再准入，每 500ms 同步控制面快照；只在版本变化时重新加载定义和编译索引。节点先以 `STARTING` 注册，完成一次快照应用后变为 `READY`；排空时变为 `DRAINING`，不再进入发布路由，排空完成后标记 `OFFLINE`。路由快照超过两秒未成功同步即拒绝发布／接收，十秒目录租约失效后不得心跳复活。readiness 包含路由与 Nacos 连接／本实例发现健康检查。订阅注册期间新增节点会推进版本，不能仅检查订阅定义版本而忽略接收节点尚未传播。

`SHARED` 表示一个长期逻辑订阅，注册的多个实例分担，停掉所有 Worker 仍保存订阅和待投递数据。`close()` 只撤销当前接收成员；显式停用长期需求需先停止接收成员，再通过 `JdbcEventRoutes.retireShared(name)` 撤销。相同共享名称必须保持完全相同的主题和资源选择器，修改定义使用新的版本名称并等待生效，再撤销旧需求；规则版本切换及重叠期间的业务去重仍归规则模块。

`BROADCAST` 表示逐节点实时订阅，仅对目录中当前有租约的目标创建投递；节点离线或订阅撤销后，未完成通知可清理，不向替代节点重放。它不适合作为必须离线保留的业务事实。现有命令 dispatch 仍保留广播后按持久目标过滤，未在本次改成按会话节点精准订阅；命令域原有的重新路由和状态机继续负责恢复。当前实时网关也仍注册宽范围广播，尚未把各客户端设备集合聚合为动态资源订阅。

### 可靠交付和边界

- `DURABLY_ROUTED` 表示本次匹配目标的投递记录已在同一 SQL 事务提交；零匹配则没有交付目标，不会为未来新增订阅回放这条事件。它不是全量事件归档，也不表示业务回调成功。持久化提交在有界线程池执行，不在 MQTT 通信线程上等待 SQL。
- 同一持久投递记录经历 PENDING → RECEIVED → DONE，分别对应发送 Outbox、接收 Inbox 和回调完成。RPC 只负责 PENDING → RECEIVED 的确认。显式撤销长期需求或清理失效节点广播时，将剩余投递标记为 CANCELLED；取消不代表业务执行成功，也不能撤回已经发生的外部副作用。源节点优先转发自己的记录，源租约失效后其他实例可抢占恢复；RPC 确认丢失和进程崩溃可能重复，投递 ID 沿用订阅和可信事件身份。
- 每轮最多领取16条，按目标分组、每批最多1 MiB，通过四个发送线程投递；单事件上限1 MiB。领取租约覆盖四轮 RPC 超时和余量，接收失败有界并发重试，不创建无界发送任务。跨节点目标按租户／设备键选择，但接收处理可以由共享订阅其他成员接管；不承诺设备消息严格顺序或状态型规则所需的单分片执行。
- 节点排空和进程重启不会依赖旧进程主动释放所有权：每次领取都会生成一次性 `owner_token`，完成／重试必须同时匹配节点与令牌；旧租约迟到的回调会被数据库条件更新丢弃。相同逻辑订阅重新启用时递增订阅代次并生成新路由 ID，避免旧积压误投到新配置。
- `DubboEventBus.drain(timeout)` 拒绝新发布、订阅与 RPC 接收，等待已提交的本地持久化、发送和回调结束后关闭节点；未领取的共享积压交由其他成员继续处理。超时返回失败，节点保持拒绝新工作的状态，调用方可显式 `close()`；这不是清空整个集群积压，也不会撤回业务副作用。普通 `close()` 仍是立即关闭。成员协调留在现有 SQL 路由目录内。
- 接收回调成功后单独标记 DONE。业务效果与该标记不是共同事务，崩溃和超时可能再次回调；业务需按稳定事件 ID 在自己的事务中去重。已有命令发送权与结果未知策略保持不变。回调资源有界；阻塞或忽略取消的业务仍需自身超时治理。
- 完成记录保留七天用于去重，清理后不再承诺跨此窗口去重；失败记录保留并重试，当前没有独立的失败处理后台。持久积压上限以逻辑投递数计算，一个事件命中多个业务会占多条。
- SQL 的 namespace 锁用于定义变更和整批投递容量准入，当前是正确性基线，会限制高吞吐；尚未交付十万设备容量指标、分库分片、完整设备顺序、全量归档或生产 PostgreSQL／MySQL 故障验收。

Nacos 适配单测覆盖健康地址通知、隔离、重复进程 ID 拒绝、注销和注册失败清理。真实 Nacos 验证显式运行：

```powershell
cd practice/backend
mvn -pl platform-components/message-component -am test '-Dtest=NacosEventNodeDiscoveryTest,NacosEventBusIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dnacos.test.address=127.0.0.1:8848'
```

测试使用随机 Nacos 服务名与独立 H2 数据库，覆盖 SQL 地址不可用时仍按 Nacos 投递、节点退出通知、共享积压恢复和独立发布 JVM。需要鉴权时通过安全的测试运行配置传入 `nacos.test.username/password`（独立子 JVM 通过环境接收）。未指定地址时真实注册中心测试跳过，普通 Dubbo 测试使用显式 JDBC 发现。

验证命令沿用下文 `mvn -pl platform-components/message-component -am test`。新增测试使用真实 Dubbo 二进制连接、独立发布 JVM 和隔离的 H2 TCP 数据库，验证选择性投递、租户隔离、共享与广播、签名拒绝、重复去重、停止全部接收端后的积压恢复、源节点租约失效接管和容量拒绝；不连接现有业务数据库或要求 Kafka 才能运行 Dubbo 测试。

`ClusterCommandProcessTest` 还分别在 Kafka 和 Dubbo 模式启动两个 management、两个 access、两个 worker 的真实 Boot JVM，使用隔离 H2 TCP 数据库与真实 MQTT 客户端验证命令受理后 API 强杀、远端设备反馈、重复信号，以及接入节点在可能发送后强杀时不自动重发。该测试覆盖启动装配与命令闭环，不替代真实 PostgreSQL／MySQL、跨机器网络和容量验收。

## Kafka 实现边界

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

- 信封包含 `schemaVersion=1`、明确 `payloadType` 和完整事件元数据。设备消息体使用固定别名，支持当前全部 `DeviceMessageBody` 类型和六种 `Value` 类型（布尔、整数、小数、文本、枚举与数组）；普通载荷仅允许 JSON 对象及其 JSON 值。新增事件类型必须显式扩展 codec，不启用任意 Java 类名反序列化。
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
