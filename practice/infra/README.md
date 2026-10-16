# 运行环境

管理后端使用 PostgreSQL。已提供本地开发编排，需要 Docker Compose；也可使用自行准备的 PostgreSQL 数据库。

在本目录执行，密码由使用者显式提供：

```powershell
$env:DATABASE_PASSWORD = Read-Host '输入数据库密码' -MaskInput
docker compose up -d --wait
```

数据库为 `127.0.0.1:5432/nebula`，用户为 `nebula`，仅监听本机。命名卷持久化数据；`docker compose stop` 停止服务，`docker compose down` 移除容器并保留卷。已有卷的密码不会因修改环境变量自动更新。

管理后端启动时由 Flyway 执行各领域的版本迁移，不自动导入演示租户或默认账号。启动与接口联调见 [后端说明](../backend/README.md)。此编排仅用于本地开发；备份恢复、升级演练与生产部署仍待实现。当前环境未安装 Docker／psql，编排与真实 PostgreSQL 尚未实测。