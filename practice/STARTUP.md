# 管理端快捷启动

## macOS

需要 Python 3、JDK 21、Maven 和 Node.js 24。可通过 Homebrew 安装：

```bash
brew install openjdk@21 maven node@24
```

`manage.sh` 自动查找 Apple Silicon 和 Intel Homebrew 的 JDK 21、Node.js 24，无需修改全局 Java 设置。先复制 `backend/.env.example` 为 `backend/.env.local`，填写 PostgreSQL 或 MySQL 连接信息；配置按字面读取，不执行其中的 shell 内容。需要创建首个管理账号时，填写示例中的初始账号、密码和平台管理员开关。

从仓库根目录执行：

```bash
# 首次构建、安装依赖并启动前后端
./practice/manage.sh --build --install
# 日常启动 / 状态 / 停止 / 重启
./practice/manage.sh
./practice/manage.sh status
./practice/manage.sh stop
./practice/manage.sh restart
# Java 代码变更后重新构建
./practice/manage.sh restart --build
# 单独启动前端或后端
./practice/manage.sh --frontend-only
./practice/manage.sh --backend-only
# 仅检查配置、工具、端口、产物
./practice/manage.sh --validate-only
```

入口固定为 <http://127.0.0.1:5173> 和 <http://127.0.0.1:8080>；默认等待就绪 90 秒，可传 `--timeout 180`。后台进程在终端退出后继续运行，日志存于 `practice/.run/{backend,frontend}.log` 和对应 `.err.log`，macOS 进程记录为 `*.mac.json`。停止时校验 PID 和启动时间，只向本脚本创建的进程组发送信号；端口被其他进程占用时不会自动结束它。启动失败只停止本次创建的服务。后端使用独立 JAR 副本，避免重新构建影响正在运行的服务；历史副本保留在 `.run/backend-runtime/`，可在停止后手动清理。

macOS 本地需要历史存储和 Kafka 时，先启动依赖：

```bash
docker compose -f practice/infra/compose.tdengine.yml up -d --wait
docker compose -f practice/infra/compose.kafka.yml up -d --wait
```

在 `backend/.env.local` 增加以下配置（TDengine 密码填写本地实例的实际密码），再执行 `./practice/manage.sh restart --backend-only`：

```dotenv
HISTORY_PROVIDER=tdengine
TDENGINE_URL=jdbc:TAOS-WS://127.0.0.1:6041/
TDENGINE_USERNAME=root
TDENGINE_PASSWORD=__FILL_TDENGINE_PASSWORD__
TDENGINE_DATABASE=nebula_history
EVENTBUS_MODE=kafka
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
```

## Windows PowerShell

需要 PowerShell 7、JDK 21、Maven 3.6.3+ 和 Node.js 24。在 `practice/` 目录执行；脚本内部使用绝对路径，也可以从仓库根目录运行 `./practice/Manage.ps1`。

先复制 `backend/.env.example` 为 `backend/.env.local`，填写 PostgreSQL 或 MySQL 连接配置。文件按 `KEY=value` 原样读取，不执行配置内容，不写入系统环境变量。

```powershell
# 日常启动：复用已有 JAR 和 node_modules，前后端并行启动
./Manage.ps1

# 首次准备，或后端代码／依赖有变更
./Manage.ps1 -Build -Install

# 状态、停止、重启
./Manage.ps1 -Action Status
./Manage.ps1 -Action Stop
./Manage.ps1 -Action Restart

# 改过 Java 代码后重新构建并启动（前端无需重新安装）
./Manage.ps1 -Action Restart -Build

# 单独操作一端
./Manage.ps1 -FrontendOnly
./Manage.ps1 -Action Restart -BackendOnly -Build

# 仅检查本地配置、工具、端口和产物，不启动、不连接数据库
./Manage.ps1 -ValidateOnly
```

前端为 <http://127.0.0.1:5173>，管理 API 为 <http://127.0.0.1:8080>。Vite 自动把 `/api/management` 代理到后端。快捷入口固定使用这两个端口，请勿同时设置其他 `SERVER_PORT`、`SERVER_ADDRESS` 或 `API_PROXY_TARGET`；自定义端口可分别使用后端脚本和 `npm run dev`。

默认启动不会执行 Maven、安装 npm 依赖、运行测试或打包前端。后端直接运行已构建的 JAR；修改 Java 代码后需加 `-Build`，否则运行的仍是旧代码。前端使用 Vite，修改 Vue／TS／CSS 会热更新；仅在 `package-lock.json` 变化或首次运行时使用 `-Install`（执行 `npm ci`）。`-Build` 执行增量 `package -DskipTests`，不执行 `clean`；完整验收仍按后端／前端 README 的测试命令进行。

脚本在后台启动两个进程，终端返回后服务继续运行。重复运行会复用本脚本管理的进程；端口被其他程序占用时立即报错，不自动杀进程。`Stop` 只停止 `.run/` 中记录且启动时间匹配的进程及其子进程。`Restart` 会使内存登录会话失效。不要在服务运行时删除 `.run/`，否则需要手动停止对应进程。

启动后检查前端 HTTP 与后端 `/actuator/health`，默认等待最多 90 秒，可用 `-TimeoutSeconds 180` 调整。超时或进程提前退出会停止本次新启动的服务，保留日志；原本已运行的服务继续保留。数据库慢、Flyway 迁移、网络和开启后的 Redis 连接仍会影响就绪时间，不通过关闭迁移或跳过健康检查隐藏问题。

日志在已被 Git 忽略的 `practice/.run/`：

- `backend.log`、`backend.err.log`：后端标准输出和错误。
- `frontend.log`、`frontend.err.log`：Vite 标准输出和错误。
- `backend.json`、`frontend.json`：进程 ID 与启动时间，不保存连接密码。

新一轮启动覆盖同名日志。完整读取文本请使用 PowerShell 严格 UTF-8：

```powershell
[IO.File]::ReadAllText((Join-Path $PWD '.run/backend.log'), [Text.UTF8Encoding]::new($false, $true))
```

本机安全工具可能让不同进程看到不同文件视图。若构建出现既有模块类不可见，或 esbuild 报无法读取已存在的 `vite.config.ts`，应检查运行进程的透明解密能力和访问权限。修改控制台代码页无法解决此问题。

Redis 配置也由同一 `.env.local` 加载：取消示例中 `REDIS_ENABLED` 和 `REDIS_URL` 的注释，填写 Upstash 的 `rediss://` Redis TCP 连接地址。HTTPS REST 地址与 REST token 不用于此连接。以后自建 Redis 只替换 URL；设备影子和设备模型缓存已使用 Redis，登录 Session 与实时票据仍在本机内存。Redis 关闭时不需要此服务。

## macOS 三服务快捷启动

从仓库根目录运行 `./practice/cluster.sh`；也可在 Finder 双击 `practice/Start-Cluster.command`。先在 `backend/.env.local` 配置可用的数据库和 Redis（`REDIS_URL`），Kafka 默认连接本机 9092。脚本在需要时启动 Docker Desktop，使用仓库 Compose 启动 Kafka、初始化业务 topic；配置 `HISTORY_PROVIDER=tdengine` 时同时启动本地 TDengine。数据库和 Redis 复用配置中的现有服务，需要预先可用。

```bash
# 首次启动：构建独立 JAR；若 manage.sh 的组合后端正在运行，显式切换
./practice/cluster.sh --build --replace-combined
# 日常启动、查看状态、重启、停止
./practice/cluster.sh
./practice/cluster.sh status
./practice/cluster.sh restart
./practice/cluster.sh stop
# 更新代码，先构建成功再重启
./practice/cluster.sh restart --build
# 仅重启指定角色
./practice/cluster.sh restart --services management worker
# 添加第二个接入节点（HTTP 8083 / MQTT 1884）
./practice/cluster.sh --second-access
# 预检；依赖由外部维护时可加 --skip-deps
./practice/cluster.sh --validate-only
```

若已有组合模式数据库先应用了 V23、尚未应用集群 V21，Flyway 会拒绝较旧迁移。核对待补迁移内容后，首次切换命令显式加 `--migrate-out-of-order`，仅对本次 management 启用补迁移；不跳过校验、不修改已应用迁移记录。正常启动无需此参数。

默认依次启动 management（HTTP 8080）、access（HTTP 8081 / MQTT 1883）、worker（HTTP 8082），每个服务通过健康与 readiness 检查后再启动下一个。三个独立入口启用 `management,cluster,service` profiles，强制使用 Kafka 和 Redis；management、worker 共享本地历史存储配置。前端继续使用 `manage.sh --frontend-only`，HTTP 代理地址保持 8080。切换或重启 management 后需重新登录，已有产品和设备保留。

日志与带启动时间的进程记录位于 `practice/.run/services/<角色>/`，运行 JAR 使用独立副本。重复启动会复用已运行实例；陌生进程占用端口会报错；启动失败只清理本次新建的服务。默认 stop 包含第二接入节点，仅停止本脚本管理的 Java 进程，依赖容器保留。Finder 停止入口为 `practice/Stop-Cluster.command`。不要在服务运行时删除 `.run/`。默认等待每个服务最多 180 秒，可用 `--timeout` 调整。

## 三个独立服务

已提供 management-service、access-service、worker-service 三个可执行 JAR，复用现有业务装配并固定运行角色。构建、启动脚本、端口、共享依赖及跨进程验收见 [独立服务说明](backend/platform-services/README.md)。原组合启动方式保留。部署边界已拆分，领域数据适配暂时共享；跨节点命令需额外启用cluster-commands，已完成独立专项验收；通用事务交付、共享登录及完整业务集群验收仍待完成。
