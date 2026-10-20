# 运行环境

管理后端默认使用 PostgreSQL，也支持 MySQL 8.4。已提供本地开发编排，需要 Docker Compose；也可使用自行准备的 PostgreSQL 或 MySQL 8.4 数据库；Compose 的 MySQL 服务使用 `--profile mysql` 启用。

在本目录执行，密码由使用者显式提供：

```powershell
$env:DATABASE_PASSWORD = Read-Host '输入数据库密码' -MaskInput
docker compose up -d --wait
```

数据库为 `127.0.0.1:5432/nebula`，用户为 `nebula`，仅监听本机。命名卷持久化数据；`docker compose stop` 停止服务，`docker compose down` 移除容器并保留卷。已有卷的密码不会因修改环境变量自动更新。

管理后端启动时由 Flyway 执行各领域的版本迁移，不自动导入演示租户或默认账号。启动与接口联调见 [后端说明](../backend/README.md)。此编排仅用于本地开发；备份恢复、升级演练与生产部署仍待实现。关系数据库兼容性需通过显式集成测试验证；此 Compose 的本机运行状态不作为仓库保证，需在目标环境实际启动检查。
## Kafka 开发环境

独立编排见 [compose.kafka.yml](compose.kafka.yml)，无需启动数据库即可运行。启动与跨机器地址配置见 [Kafka EventBus](../backend/EVENTBUS.md)。这是单节点开发样例。

## TDengine 历史存储开发环境

独立编排见 [compose.tdengine.yml](compose.tdengine.yml)，在本目录执行 `docker compose -f compose.tdengine.yml up -d --wait`。固定 TDengine 3.3.6.13，仅向本机开放 WebSocket 端口 6041，数据与日志保存在命名卷。镜像初始开发账号为 `root`／`taosdata`，生产环境需自行维护凭证。应用配置、策略启用、真实服务器验收和保留周期限制见 [历史存储组件](../backend/platform-components/storage-component/README.md)。已有真实 TDengine 适配器验收记录不代表此固定版本编排已经验收；目标环境需按存储组件说明显式运行真实服务器测试。

## Nacos 开发环境

Dubbo EventBus 默认通过 Nacos 发现实例。已有 Nacos 可以直接复用；没有时执行 `docker compose -f practice/infra/compose.nacos.yml up -d`。编排固定 Nacos 2.5.1，只绑定本机 8848 和客户端 gRPC 9848，关闭鉴权，仅用于本机开发。正式环境需配置鉴权和可用性保障。Windows 本机默认 WSL 依赖已有 Nacos，请勿重复占用端口。配置与职责边界见 [EventBus](../backend/EVENTBUS.md#nacos-实例发现)。
