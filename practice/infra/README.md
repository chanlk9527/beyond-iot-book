# 运行环境

管理后端默认使用 PostgreSQL，也支持 MySQL 8.4。已提供本地开发编排，需要 Docker Compose；也可使用自行准备的 PostgreSQL 或 MySQL 8.4 数据库；Compose 的 MySQL 服务使用 `--profile mysql` 启用。

在本目录执行，密码由使用者显式提供：

```powershell
$env:DATABASE_PASSWORD = Read-Host '输入数据库密码' -MaskInput
docker compose up -d --wait
```

数据库为 `127.0.0.1:5432/nebula`，用户为 `nebula`，仅监听本机。命名卷持久化数据；`docker compose stop` 停止服务，`docker compose down` 移除容器并保留卷。已有卷的密码不会因修改环境变量自动更新。

管理后端启动时由 Flyway 执行各领域的版本迁移，不自动导入演示租户或默认账号。启动与接口联调见 [后端说明](../backend/README.md)。此编排仅用于本地开发；备份恢复、升级演练与生产部署仍待实现。当前环境未安装 Docker／psql，编排与真实 PostgreSQL 尚未实测。
## Kafka 开发环境

独立编排见 [compose.kafka.yml](compose.kafka.yml)，无需启动数据库即可运行。启动与跨机器地址配置见 [Kafka EventBus](../backend/EVENTBUS.md)。这是单节点开发样例。

## TDengine 历史存储开发环境

独立编排见 [compose.tdengine.yml](compose.tdengine.yml)，在本目录执行 `docker compose -f compose.tdengine.yml up -d --wait`。固定 TDengine 3.3.6.13，仅向本机开放 WebSocket 端口 6041，数据与日志保存在命名卷。镜像初始开发账号为 `root`／`taosdata`，生产环境需自行维护凭证。应用配置、策略启用、真实服务器验收和保留周期限制见 [历史存储组件](../backend/platform-components/storage-component/README.md)。本机复用 WSL 中已有的 `tdengine/tsdb:3.3.8.8` 开发容器，已通过真实适配器验收；此独立编排尚未单独运行。
