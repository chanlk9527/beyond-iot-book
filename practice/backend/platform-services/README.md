# 独立运行服务

三种服务各有独立 main、可执行 JAR、HTTP 探针和固定运行角色；业务逻辑复用已有模块，数据库与 Redis 暂时共享。`platform-bootstrap` 继续支持原组合运行方式。当前三个 JAR 都复用完整 bootstrap 依赖集，属于部署与运行边界拆分，尚未裁剪各服务依赖。

| 服务 | 默认端口 | 启用内容 | 关闭内容 |
| --- | --- | --- | --- |
| management-service | HTTP 8080 | 管理／OpenAPI、WebSocket、领域查询 | MQTT、影子报告消费者 |
| access-service | HTTP 8081、MQTT 1883 | 设备认证／模型查询、MQTT、会话租约、上行事件发布 | 管理／OpenAPI、WebSocket、影子报告消费者 |
| worker-service | HTTP 8082 | 影子报告消费者、领域处理、状态通知发布 | MQTT、管理／OpenAPI、WebSocket |

access／worker 的 HTTP 仅允许健康探针。运行角色不允许通过配置改成另一个角色；分离服务要求 Kafka 或 Dubbo，误设 local 会启动失败。默认仍为 Kafka；Dubbo 使用 Nacos 实例发现、独立二进制 RPC 端口和 SQL 业务订阅目录，启动参数与交付边界见 [EventBus](../EVENTBUS.md#dubbo-发布侧订阅路由)。三个入口自动添加 `management,cluster,service` profiles，service 配置默认开启持久化跨节点命令。这里 `management` profile 提供共享数据库与领域适配，不表示 access／worker 开放管理 API。

## 构建

在 `practice/backend` 执行：

```powershell
mvn -pl platform-services/service-verification -am package -DskipTests
```

产物：

```text
platform-services/management-service/target/management-service-0.1.0-SNAPSHOT-exec.jar
platform-services/access-service/target/access-service-0.1.0-SNAPSHOT-exec.jar
platform-services/worker-service/target/worker-service-0.1.0-SNAPSHOT-exec.jar
```

bootstrap 额外输出 `-library.jar`，用于服务装配；原 bootstrap 可执行 JAR 保留。不要运行没有 `-exec` 后缀的服务普通类库 JAR。

## 启动三个进程

先准备一个共享 PostgreSQL（或现有支持的 MySQL）和 Redis。以下示例使用默认 Kafka 模式，另需 Kafka 及两个预创建 topic；Dubbo 模式不依赖 Kafka，按 [Dubbo 启动说明](../EVENTBUS.md#配置与启动) 准备 Nacos、RPC 端口与共享凭据。Kafka 开发编排和 topic 初始化见 [EventBus](../EVENTBUS.md)。数据库启动见 [后端 README](../README.md)。首次先启动 management 完成迁移，再启动其他服务；不要对现有库并发尝试未经验证的迁移。

每个终端设置相同的依赖连接参数；密码使用本机环境配置，不写进仓库：

```powershell
$env:DATABASE_URL = 'jdbc:postgresql://127.0.0.1:5432/nebula'
$env:DATABASE_USERNAME = 'nebula'
$env:DATABASE_PASSWORD = '<本机数据库密码>'
$env:REDIS_URL = 'redis://127.0.0.1:6379'
$env:KAFKA_BOOTSTRAP_SERVERS = '127.0.0.1:9092'
```

首次管理账号仍按 [管理启动说明](../README.md) 配置。随后在三个终端分别运行（路径相对于 `practice/backend`）：

```powershell
./scripts/Start-Service.ps1 -Role management -InstanceId management-a
./scripts/Start-Service.ps1 -Role access -InstanceId access-a
./scripts/Start-Service.ps1 -Role worker -InstanceId worker-a
```

脚本在当前终端运行，Ctrl+C 停止；`-Build` 可先构建对应服务。脚本通过 `-EventBusMode kafka|dubbo` 显式选择模式，默认 Kafka，并传入实例标识，避免 local 开发配置造成误用。所有服务使用相同模式与 EventBus namespace；Kafka 模式还需相同物理 topic，Dubbo 模式需一致的 Nacos 设置及独立 `-DubboPort`；实例 ID 必须唯一。

也可以直接启动 JAR：

```powershell
java -jar platform-services/management-service/target/management-service-0.1.0-SNAPSHOT-exec.jar --nebula.eventbus.node-id=management-a
java -jar platform-services/access-service/target/access-service-0.1.0-SNAPSHOT-exec.jar --nebula.eventbus.node-id=access-a --nebula.access.node-name=access-a
java -jar platform-services/worker-service/target/worker-service-0.1.0-SNAPSHOT-exec.jar --nebula.eventbus.node-id=worker-a
```

直接启动时，清除或改正已有 `EVENTBUS_MODE=local`；清除与目标服务冲突的 `NEBULA_RUNTIME_ROLE`，角色错误会明确拒绝启动。Redis 默认开启，必须提供可用的 `REDIS_URL`。服务默认 HTTP 仍监听回环地址；跨机器部署按实际入口设置 `SERVER_ADDRESS`，并使用正确的 Kafka advertised listener 地址及生产认证。

## Kafka 上行验证拓扑

```text
设备 --MQTT--> access --Kafka 上行--> worker --Redis--> 影子
                                      |
                                 Kafka 状态通知
                                      |
App <--WebSocket-- management <--------+
```

在 management 登记产品／发布物模型／注册激活设备，将设备 MQTT 地址改为 access 的地址，前端 HTTP 和 WebSocket 入口保持 management。

1. 只启动 management 和 access，连接设备并上报：MQTT QoS 1 可在 Kafka 确认后完成，影子暂不更新。
2. 启动 worker：消费保留的上报、更新影子，management 将状态通知转发给已授权客户端。
3. 停止 worker 后继续上报，再启动 worker：检查积压恢复与 App 重新查询的影子结果。
4. 再启动 access-b 和 worker-b：分别覆盖端口，验证会话归属与共享消费组分担。

```powershell
./scripts/Start-Service.ps1 -Role access -InstanceId access-b -HttpPort 8083 -MqttPort 1884
./scripts/Start-Service.ps1 -Role worker -InstanceId worker-b -HttpPort 8084
```

健康探针为各服务的 `/actuator/health/liveness` 和 `/actuator/health/readiness`。management 的接入运维面板读取共享节点目录。当前 readiness 包含成员存储检查，不能替代 Kafka 消费分配和 Redis 业务读写验收。

接入节点默认隐藏已正常退出的实例，以及最后心跳超过 2 分钟且租约已失效的实例；近期失联实例仍保留，便于发现故障。开启“查看历史实例”可查看旧记录（接口参数 `includeHistory=true`），历史记录不参与当前节点指标完整性判断。该筛选不删除节点历史数据。

## Kafka 上行自动验收

```powershell
mvn -pl platform-services/service-verification -am verify '-Dtest=RuntimeRoleEnvironmentTest,MessageConfigurationTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dit.test=SeparatedServicesIT' '-Dfailsafe.failIfNoSpecifiedTests=false'
```

Kafka 消费者在 Spring 生命周期阶段启动，保证领域事件监听器先注册，再处理历史积压，避免“影子已更新但启动阶段漏通知”。

测试真实构建并启动三个可执行 JAR，使用隔离的 H2 TCP 数据库、嵌入式 Kafka、真实 MQTT 和授权 WebSocket，验证只有 worker 更新影子、积压恢复和服务接口隔离。测试通过 Boot PropertiesLauncher 额外加载 H2 驱动与**仅存在于测试 JAR 中的共享 JDBC CAS 影子适配**，不依赖本机 Redis，不触碰现有数据库。生产 JAR 不包含这个测试适配，正常运行仍使用 Redis。测试将 Kafka 消费者 session timeout 配为 6 秒、heartbeat interval 配为 1 秒，使进程退出检测在测试窗口内完成；生产恢复时间须结合实际 Kafka 参数验收。该测试不等于真实 Redis／PostgreSQL 或跨物理机器验收。

## 当前边界

- 设备认证／物模型仍通过复用的领域适配读取共享数据库；尚未改成远程目录服务。
- 状态通知仍有 Redis 更新与 EventBus 发布间的故障窗口，业务幂等／可靠发布继续按架构规划完善。
- 实时通知当前每个 management 节点独立读取并过滤本地连接，未实现订阅归属定向分发和 10 万在线连接验收。
- 登录和 WebSocket 票据仍在本机，先让客户端固定连接一个 management；不要把新增第二实例当作会话共享已完成。
- 独立服务默认开启持久化跨节点命令；SeparatedServicesIT 同时覆盖真实 MQTT over WebSocket 上行、管理端属性下发、Worker 路由、access 定向下发与设备回执查询。跨节点故障恢复仍以 cluster-commands 的六进程专项测试范围为准。
## 本机重启后的快捷入口

本电脑 WSL Ubuntu 的依赖统一维护在 `/home/admini/workspace/dev-env/docker-compose.yml`，包含原有 PostgreSQL、Redis、MySQL、Nacos 及新纳入的 Kafka。Kafka 配置 `restart: always`，数据卷 `nebula-eventbus_kafka-data` 作为 external volume 复用；`kafka-init` 仅负责幂等建 topic，完成后退出是正常状态。此路径和容器名称是 Windows 快捷脚本的默认值，其他电脑需按实际环境覆盖。

重启电脑后，双击仓库的 `practice/Start-Cluster.cmd`：启动 WSL Docker、执行原 Compose 的 `up -d`、确认 topic 初始化，再后台启动三个 Java 服务并检查总体健康。已有的本项目进程会复用，陌生进程占用端口时明确报错。可双击 `practice/Stop-Cluster.cmd` 只停止三个后端，依赖容器继续运行。

PowerShell 入口为 `backend/scripts/Start-LocalCluster.ps1` 和 `Stop-LocalCluster.ps1`。首次缺少产物或更新代码后，运行 `./Start-Cluster.cmd -Build`。默认 WSL 发行版和 Compose 路径是本机配置，可通过 PowerShell 的 `-Distribution`、`-ComposePath` 参数覆盖。

更新运行中的服务需显式传 `-Restart`；只重启部分角色可在 `practice` 目录执行 `./backend/scripts/Start-LocalCluster.ps1 -Restart -Services management,worker`。重启前校验记录的 PID／启动时间和构建产物；默认仍复用运行进程。脚本从 `.env.local` 加载历史存储的 `HISTORY_*`、`TDENGINE_*` 配置，管理与 Worker 使用同一存储设置。历史默认关闭，启用与产品策略说明见 [历史存储组件](../platform-components/storage-component/README.md)。

快捷启动读取本机 `backend/.env.local` 中的数据库与管理账号配置，Redis 密码从本机 `redis-dev` 容器配置取得，仅传入子进程环境，不写回仓库或日志。Java 使用实际 JDK 路径并记录 PID 与进程创建时间，防止 Oracle javapath 转发进程和 PID 重用导致误停。运行 JAR 独立复制到 `practice/.run/services/<role>/`，构建不会覆盖运行中的文件。

`restart: always` 只在 Docker 启动后恢复容器，不代表 Windows 启动时 WSL 和 Java 一定已启动。本机入口通过双击完成这一步，没有注册 Windows 登录计划任务。需自行确认目标 WSL 的 Docker 服务可用，脚本不保证系统启动配置。

第二个本机接入节点：运行 `./Start-Cluster.cmd -SecondAccess`，额外启动 access-b（HTTP 8083、MQTT 1884），复用已有进程。重启电脑后带相同参数恢复双接入节点。停止入口也会停止 access-b；日志和进程记录位于 `practice/.run/services/access-b/`。

本机快捷入口支持 `-EventBusMode kafka|dubbo`，省略时读取 `.env.local` 的 `EVENTBUS_MODE`，未配置时保持 Kafka。切换运行模式请重启全部节点，例如在 `practice` 目录执行 `./Start-Cluster.cmd -Restart -SecondAccess -EventBusMode dubbo`。Dubbo 需在 `.env.local` 中为所有节点设置同一个 `EVENTBUS_DUBBO_CREDENTIAL`（至少32字符）；本机使用 `EVENTBUS_DUBBO_HOST=127.0.0.1`，并通过 `NACOS_SERVER_ADDR=127.0.0.1:8848` 复用 WSL 中现有 Nacos；需要鉴权时配置 `NACOS_USERNAME`／`NACOS_PASSWORD`。默认启用 Nacos 发现，SQL 仍维护业务订阅与任务状态。management、access、worker、access-b 的 RPC 端口分别为 20880、20881、20882、20883，HTTP／MQTT 端口不变。凭据仅通过子进程环境传递，不进入进程参数或日志；进程记录保存所用模式和 RPC 端口。切换模式不会迁移 Kafka 积压，现有依赖容器继续保留。
## macOS 快捷入口

macOS 使用仓库内 `practice/cluster.sh`，提供三服务的后台启停、构建、部分角色重启和第二接入节点；Finder 入口为 `Start-Cluster.command`、`Stop-Cluster.command`。从组合模式切换执行 `./practice/cluster.sh --build --replace-combined`。配置、参数、依赖启动边界与日志见 [macOS 三服务快捷启动](../../STARTUP.md#macos-三服务快捷启动)。

Windows 集群快捷启动在未配置 NACOS_SERVER_ADDR 且默认本机地址的 8848／9848 不完整可达时，会检测当前 WSL IPv4 地址，选择两个端口都可达的地址，仅传给本次子进程；不会写入固定 WSL IP 或修改显式配置的注册中心。没有可达地址时启动失败并提示修复连接。
