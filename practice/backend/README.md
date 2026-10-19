# Java 后端使用说明

需要 JDK 21、Maven 3.6.3及以上。版本见 [父 POM](pom.xml)，整体规则见 [项目架构](../docs/architecture.md)。当前包含 core、通用接入契约、Vert.x MQTT 5实现、身份／租户管理基础、业务模板和启动应用。

## 构建与启动

前后端一起开发时，优先使用 [管理端快捷启动](../STARTUP.md)：在 `practice/` 执行 macOS 的 `./manage.sh` 或 Windows 的 `./Manage.ps1`，默认复用已有 JAR，不重复构建。修改后端代码后分别用 `./manage.sh restart --build` 或 `./Manage.ps1 -Action Restart -Build`。

在本目录执行：

```powershell
mvn -B -ntp -s .mvn/settings-central.xml verify
java -jar platform-bootstrap/target/platform-bootstrap-0.1.0-SNAPSHOT.jar
```

本机默认Maven镜像尚未提供Vert.x 5.1.10，上述项目配置显式从Maven Central解析，不改全局设置。镜像已具备相应版本时可直接使用 `mvn -B -ntp verify`。

默认同时启动本地HTTP健康端口 `127.0.0.1:8080` 和 MQTT 5 接入端口 `127.0.0.1:1883`；管理业务HTTP路由通过management profile开启。MQTT可通过`MQTT_ENABLED=false`关闭，`MQTT_PORT`覆盖默认端口。`SERVER_ADDRESS`、`SERVER_PORT` 可覆盖HTTP监听配置。健康与探针路径为 `/actuator/health`、`/actuator/health/liveness`、`/actuator/health/readiness`。

## 启动管理后端

基础组合为 Spring MVC、Spring Security、MyBatis、PostgreSQL 和 Flyway。`platform-manager` 聚合 `identity-manager`、`tenant-manager`、`device-manager`，由现有 bootstrap 统一装配。管理功能通过 `management` profile 开启；未启用management的默认启动仍不需要数据库，此时MQTT缺少设备目录会拒绝认证；正常后台启动使用持久化登记设备目录。

先准备 [本地 PostgreSQL](../infra/README.md) 或已有数据库，再在本目录执行：

```powershell
$env:DATABASE_PASSWORD = Read-Host '输入数据库密码' -MaskInput
# 首次初始化显式创建平台超级管理员；普通账号必须在租户内创建。
$env:NEBULA_MANAGEMENT_INITIAL_ACCOUNT_PLATFORM_ADMIN = 'true'
$env:NEBULA_MANAGEMENT_INITIAL_ACCOUNT_USERNAME = Read-Host '输入初始账号名'
$env:NEBULA_MANAGEMENT_INITIAL_ACCOUNT_PASSWORD = Read-Host '输入初始账号密码（非空，最多72 UTF-8字节）' -MaskInput
java -jar platform-bootstrap/target/platform-bootstrap-0.1.0-SNAPSHOT.jar --spring.profiles.active=management
```

可覆盖 `DATABASE_URL`、`DATABASE_USERNAME`；默认 URL 为 `jdbc:postgresql://127.0.0.1:5432/nebula`，用户为 `nebula`。`management,mqtt-dev` 可组合启用，此时仍需 `MQTT_DEV_PASSWORD`；管理账号与设备凭证独立。

初始化账号仅在用户名不存在时创建，密码使用带算法标识的 BCrypt 哈希；重启不会覆盖现有密码。首次启动后移除三个初始化账号环境变量。账号停用或删除后，下一次管理 API 请求使已有会话失效。当前 Session 位于单进程内，重启需要重新登录；部署 HTTPS 时设置 `SESSION_COOKIE_SECURE=true`。本阶段不提供公开注册或密码重置；平台超级管理员通过下方显式初始化配置授权。

### 用本地文件配置数据库

可以复制`.env.example`为`.env.local`，填写数据库连接信息及密码，再在本目录启动：

```powershell
./scripts/Start-Management.ps1 -ValidateOnly
./scripts/Start-Management.ps1
```

脚本显式读取该文件，临时设置当前脚本进程及Java子进程的环境变量，退出时还原；不写入系统／用户的永久环境变量。Spring Boot本身不会自动加载`.env.local`。脚本启动时将JAR复制到`practice/.run/backend-runtime/<独立运行目录>/`，使运行进程不受后续Maven构建覆盖`target`产物影响；应用代码更新后仍需重启后端。文件已被Git忽略，示例文件不包含密码；不要将真实密码放进JDBC URL或前端配置。

密码直接填写在`DATABASE_PASSWORD=`后面，不加引号，不做URL编码，`#`、`=`、`$`和反斜杠均按原样保留，不支持变量插值或行内注释。`-ValidateOnly`只校验文件，不连接数据库。默认加载已构建的启动JAR并启用management profile，启动会执行待应用的Flyway迁移。缺少JAR时按上方Maven命令构建。

Supabase连接采用SSL；连接参数应从项目Connect面板复制。Direct connection通常需要IPv6，若本机网络无法直连，应改用Connect面板的Session pooler参数，不猜测pooler地址或用户名。参见[Supabase连接说明](https://supabase.com/docs/guides/database/connecting-to-postgres)。

### Redis（Upstash）

Redis 是可选依赖，当前通过 Spring Data Redis 接入。Upstash 控制台提供的 TLS 地址直接填写到 `REDIS_URL`，例如 `rediss://default:<password>@<host>:6379`，并设置 `REDIS_ENABLED=true`：

```powershell
$env:REDIS_ENABLED = 'true'
$env:REDIS_URL = 'rediss://default:密码@你的upstash主机:6379'
java -jar platform-bootstrap/target/platform-bootstrap-0.1.0-SNAPSHOT.jar --spring.profiles.active=management
```

连接只依赖标准 Redis URI；以后切换自建 Redis 时只需替换 `REDIS_URL`。未开启时不会创建 Redis 客户端，也不会尝试连接本机 Redis。`REDIS_COMMAND_TIMEOUT_MS` 默认 2000 毫秒。设备影子已使用 Redis 保存上报状态、期望状态、差异和并发版本；具体接口与存储边界见 [设备管理说明](platform-manager/device-manager/README.md#设备影子redis)。

### 产品与设备管理

`device-manager` 提供产品、模型版本、设备登记、生命周期与凭证管理。普通账号固定访问所属租户，平台超管可跨租户读取并按资源归属写入；产品目录为平台共享数据。API、凭证策略与模型格式见 [device-manager 说明](platform-manager/device-manager/README.md)。

管理数据库迁移由 `management` profile 自动加载。正常management启动默认开启MQTT并使用数据库登记凭证认证；设备必须为ACTIVE、产品启用、所属租户启用。ClientId与用户名均填写设备ID，密码填写后台生成的当前密钥。无租户设备使用内部platform范围；身份归属由数据库确定，不接受客户端指定租户。轮换凭证或停用设备会在事务提交后断开当前连接，后续认证及消息处理重新校验接入资格；设备启用状态不等于在线。显式mqtt-dev模式仍只使用开发目录。正式上行、控制和OTA按后续阶段实现。

### RBAC 功能权限

后台功能采用“用户 → 角色 → 权限点”授权，数据权限暂不实施，已有租户归属／成员隔离继续保留。角色是平台后台功能角色，与租户成员OWNER／ADMIN／VIEWER标签独立；给账号分配后台角色不会自动加入租户，也不会授予平台超级管理员身份。

权限点由服务端`Permissions.CATALOG`固定注册：

| 权限点 | 功能 |
| --- | --- |
| tenant:read | 查询租户及租户审计 |
| tenant:write | 创建、改名、启停租户 |
| tenant:member:read | 查看租户成员 |
| tenant:member:write | 添加、调整、启停租户成员 |
| iam:user:read | 查询用户 |
| iam:user:write | 创建、启停普通用户 |
| iam:role:read | 查询角色及权限目录 |
| iam:role:write | 创建、修改、启停角色并设置权限点 |
| iam:grant | 分配／撤销用户角色 |

`/me.permissions`返回有效权限集合；平台超管获得全部已注册功能权限，普通账号取全部启用角色的权限并集，无角色默认无平台功能权限。每次请求重新查询授权，不把权限固化到登录Session，角色停用、权限撤销与账号停用无需重新登录即可作用于后续请求。权限字符串精确匹配，不支持通配符和从用户名推断权限。平台超管身份仍独立保存，无租户产品设备未来仍按平台超管专属边界处理，不因普通功能授权自动开放。

前端权限管理入口为`/access`。推荐先由超管创建角色，再新建普通用户并分配角色。用户页面需要iam:user:read，角色／权限目录需要iam:role:read；分配角色UI同时需要这两个读取权限及iam:grant。具有写权限但没有对应读取权限的账号仍不显示对应页面，请按实际职责组合授权。

| 方法与路径 | 行为 |
| --- | --- |
| GET `/api/management/iam/permissions` | 查看已注册权限目录 |
| GET `/api/management/iam/users` | 用户分页查询，query、page、size参数 |
| POST `/api/management/iam/users` | `{username,password,tenantId,role}`创建启用、无角色普通用户，密码非空且最多72 UTF-8字节 |
| PUT `/api/management/iam/users/{id}` | `{enabled,revision}`启停用户 |
| PUT `/api/management/iam/users/{id}/roles` | `{roleIds,revision}`替换角色集合，空列表撤销全部角色 |
| GET `/api/management/iam/roles` | 查看角色及各角色的权限点 |
| POST `/api/management/iam/roles` | `{code,name,menus}`创建角色；code为2～64字符的小写字母、数字、冒号、下划线或连字符，首字符为字母 |
| PUT `/api/management/iam/roles/{id}` | `{name,enabled,revision,menus}`更新角色，code不可修改 |

所有写操作保留CSRF保护。权限目录不可由客户端增加；未知权限／通配符返回400，旧revision和重复账号／角色编码返回409。只能分配自己已拥有的权限，调整现有角色／用户时也核验其原有权限，防止间接提升权限。用户不能修改自己的角色或状态，受保护的超管账号不通过普通用户接口修改；超管授权和恢复仍走独立显式配置通道。

授权相关写操作在同一安全锁下串行执行，检查、变更及审计同事务；角色仅停用，不物理删除。所有普通账号创建时必须绑定唯一租户并建立成员关系，全局用户管理与租户详情使用同一创建流程；平台超管账号独立初始化。身份安全操作记录到identity_audit，不记录密码；本版未提供其查询页面，也未实现密码重置和租户自定义角色。

### 页面菜单授权

`GET /api/management/iam/menus`返回数据库中的菜单配置目录，需要`iam:role:read`。页面包括租户、用户、角色、菜单、产品目录、产品和设备管理；页面标识为`page:*`，通过`/me.permissions`下发。控制台概览默认开放，详情页继承列表页。后端接口仍独立校验原有能力和租户身份。

创建角色可提交`{code,name,menus:["page:devices"]}`；修改角色提交`{name,enabled,revision,menus:[...]}`。服务端验证页面、去重并为新增页面附带所需接口能力，设备页所需`product:read`不会授予产品页面。保留的旧页面沿用已有接口能力，避免把历史只读角色升级为可写角色；移除页面会删除仅由该页面使用的能力，共享能力保留。授权不能超过操作者权限，revision、审计和事务规则沿用角色管理。旧`permissions`入参仍兼容，但不能与`menus`同时提交。

### 菜单定义维护接口

`identity_menu` 和 `identity_menu_permission` 保存菜单展示信息、排序、版本和接口关联，不重写已有角色能力。开发者在`Menus.CATALOG`注册页面标识、合法路由及建议接口，在前端注册对应组件和路由；配置入口只能选择已注册页面，不能注入任意地址或接口权限。

- GET `/api/management/iam/menu-pages`：已注册页面与建议接口，仅平台超管。
- POST `/api/management/iam/menus`：创建未配置页面的菜单，仅平台超管。
- PUT `/api/management/iam/menus/{code}`：编辑菜单；提交`code,name,group,path,description,sortOrder,revision,permissions`。
- DELETE `/api/management/iam/menus/{code}`：提交`{revision}`删除无角色绑定的菜单；维护入口不可删除。

写入保留CSRF校验、全局授权锁、乐观版本与审计。接口关联差异在同一事务同步到绑定角色：新增接口加入角色，移除接口在无其他页面依赖时撤销，更新角色revision并记录`ROLE_MENU_SYNCED`。已有角色中与本次关联差异无关的历史能力保留。名称、分组、排序等展示变更不会扩权。`/me.menus`只下发可见菜单的导航信息，不暴露接口关联；后端接口授权与租户隔离继续独立执行。

### 租户管理与平台权限

`identity_account.platform_admin`为独立平台权限，默认false；租户OWNER／ADMIN角色不会获得平台权限。`/me`返回`platformAdmin`供菜单展示，服务端每次管理请求从数据库重新校验账号与平台权限。平台超管身份与成员资格独立；产品设备模块显式允许超管跨租户访问。

首次授权可在本地`.env.local`中显式设置`NEBULA_MANAGEMENT_INITIAL_ACCOUNT_PLATFORM_ADMIN=true`，同时保留初始账号名和密码，启动后仅将该账号标为平台超管，不重置已有密码。成功后移除三个初始账号配置，避免重启重新授予已撤销权限；此设置是授权，不是完整的平台管理员维护入口，false不撤销已有权限。普通用户名或`admin`名称不会触发授权。

平台接口前缀为`/api/management/platform/tenants`，按上方tenant权限点校验，平台超管拥有全部权限，写操作保留CSRF保护：

| 方法与相对路径 | 行为 |
| --- | --- |
| GET `/` | 按名称／ID搜索，page从1起，size默认20、最多100，返回items／total |
| POST `/` | `{name}`创建启用租户，生成不可变UUID，不自动添加成员 |
| GET `/{id}` | 查看租户，包含状态、revision及有效成员数 |
| PUT `/{id}` | `{name,enabled,revision}`改名或启停；旧revision返回409 |
| GET `/{id}/members` | 查看成员账号、租户角色、状态及revision |
| POST `/{id}/members` | `{username,role}`将已存在且启用的账号加入租户；重复返回409 |
| POST `/{id}/users` | `{username,password,role}`创建账号并加入当前启用租户，返回成员信息；同时需要iam:user:write和tenant:member:write；重名409 |
| PUT `/{id}/members/{accountId}` | `{role,enabled,revision}`调整角色、停用或恢复成员 |
| GET `/{id}/audit` | 最近50条租户与成员管理记录 |

租户角色限定OWNER、ADMIN、VIEWER，当前用于记录成员职责，具体设备等业务授权将在所属模块实施。平台超管可管理没有OWNER的租户，不强制每个租户至少一个OWNER。成员维护操作锁定租户行，修改与审计在同一事务完成；停用保留数据，不提供物理删除接口。租户或成员停用后，租户接口立即拒绝访问。租户详情可新建登录账号并加入租户；账号、成员关系与两份审计在同一事务内提交，任一步失败全部回滚。新账号默认启用且无后台RBAC角色，不自动授予平台权限；后台功能角色在权限管理中另行分配。账号名全平台唯一，账号的tenant_id不可通过成员接口改变；不同租户间不能复用账号，重名不会修改原账号密码。密码沿用全局账号创建规则（非空、最多72个UTF-8字节，无12位最小长度）。不存在或已停用的租户不能新建用户。

### 接口与登录顺序

| 方法与路径 | 作用 |
| --- | --- |
| GET `/api/management/auth/csrf` | 获取 CSRF 请求头名与 token，建立或复用 Session |
| POST `/api/management/auth/login` | 表单字段 username／password，成功204、凭证错误401 |
| POST `/api/management/auth/logout` | 退出并销毁 Session，成功204 |
| GET `/api/management/me` | 当前账号 ID 与用户名，不返回密码或哈希 |
| GET `/api/management/tenants` | 当前账号可进入的有效租户及租户内角色 |
| GET `/api/management/tenants/{tenantId}` | 每次重新校验有效租户与成员资格，无权限403 |

登录与退出均需 CSRF token；登录后 token 轮换，执行下一次写请求前重新获取。会话 Cookie 为 HttpOnly、SameSite=Lax。未登录访问管理 API 返回401，安全错误使用 `application/problem+json`；不使用登录页重定向。浏览器前端建议通过同源代理调用，跨域配置尚未开放。

PowerShell 联调示例（保持同一 WebSession）：

```powershell
$base = 'http://127.0.0.1:8080'
$csrf = Invoke-RestMethod "$base/api/management/auth/csrf" -SessionVariable managementSession
$headers = @{ $csrf.headerName = $csrf.token }
$loginName = Read-Host '账号'
$loginPassword = Read-Host '密码' -MaskInput
Invoke-WebRequest "$base/api/management/auth/login" -Method Post -WebSession $managementSession -Headers $headers -ContentType 'application/x-www-form-urlencoded' -Body @{ username = $loginName; password = $loginPassword }
Invoke-RestMethod "$base/api/management/me" -WebSession $managementSession
Invoke-RestMethod "$base/api/management/tenants" -WebSession $managementSession
$csrf = Invoke-RestMethod "$base/api/management/auth/csrf" -WebSession $managementSession
Invoke-WebRequest "$base/api/management/auth/logout" -Method Post -WebSession $managementSession -Headers @{ $csrf.headerName = $csrf.token }
```

普通账号必须在创建时绑定一个租户。使用租户详情的新建用户，或通过用户管理提交`{username,password,tenantId,role}`；普通租户管理员省略tenantId时固定使用本人租户，不能指定其他租户。平台超管必须显式选择目标租户。

租户成员角色与后台RBAC独立；产品设备管理已接入功能权限和成员校验，控制等后续业务仍需落实对应授权。租户通过 URL 显式指定，不在 Session 中保存可变的当前租户；前端切换仍需清理缓存及订阅，服务端不信任客户端传入的归属标识。现已提供产品与设备登记管理接口，规则、OTA及设备控制等业务仍待后续实现。

### 迁移与验证

Flyway 从各模块的 `db/migration/identity`、`db/migration/tenant` 和 `db/migration/device` 加载迁移，统一使用递增版本；各领域维护自己的表。关闭 clean 和自动 baseline，已有数据库需显式规划迁移，不自动接管旧表。

`ManagementApiTest` 使用仅测试依赖 H2 的 PostgreSQL 兼容模式，执行真实 Flyway 迁移、MyBatis 查询、密码认证和 HTTP 安全链，覆盖两租户隔离、成员撤销、租户停用、账号停用、CSRF与退出。不代表真实 PostgreSQL、生产部署或完整 RBAC 已验收。

## 启动本地 MQTT 5 联调

```powershell
$env:MQTT_DEV_PASSWORD = Read-Host '输入本地联调密码' -MaskInput
java -jar platform-bootstrap/target/platform-bootstrap-0.1.0-SNAPSHOT.jar --spring.profiles.active=mqtt-dev
```

MQTT监听 `127.0.0.1:1883`，可用 `MQTT_PORT` 改端口。客户端必须选择MQTT 5、Clean Start=true、Session Expiry=0，注册全局PUBLISH接收回调，**无需发送SUBSCRIBE**。

| ClientId | 用户名 | 设备归属 |
| --- | --- | --- |
| warmer-1 | tenant-a/warmer-1 | tenant-a／warmer-1 |
| tenant-b-warmer-1 | tenant-b/warmer-1 | tenant-b／warmer-1 |

两者密码均使用上面显式输入的环境变量。用户名与ClientId必须符合开发清单；配置入口为 `application-mqtt-dev.yml`。自定义开发设备通过 `nebula.mqtt.devices` 配置，设备、用户名和ClientId不能重复。

官方默认协议已独立实现于 `protocol-component`。完整 Topic、信封、属性读写、指令、事件、设备信息、校时、配置、OTA、错误码和恢复规则统一维护在 [MQTT 设备协议 1.0](../docs/mqtt-protocol.md)。设备向 `nebula/v1/up` 发布，在全局回调接收 `nebula/v1/down`，不发送 SUBSCRIBE。

上报示例（QoS 1、Retain=false）：

```json
{"version":"1.0","messageId":"m-1","method":"thing.property.report","timestamp":1792022400000,"data":{"properties":{"power":true,"heatingLevel":70}}}
```

下行由业务注入 `DeviceAccessGateway`，通过 `send(DeviceDownlink)` 发送属性读取／写入、配置、OTA及系统回复。指令保留快捷入口：

```java
var key = new DeviceKey(new TenantId("tenant-a"), new DeviceId("warmer-1"));
var command = new DeviceCommand(new CommandId("c-1"), key, "v1", "setHeatingLevel",
        Map.of("heatingLevel", new Value.IntegerValue(70)), Instant.now(), Instant.now().plusSeconds(30));
gateway.send(command);
```

传输回执只表示成功PUBACK，业务完成需核对独立回复。自动校时已实现，其他业务进入接收器；开发收件箱不会自动保存业务状态、调度OTA或生成成功回复。命令／消息过期、离线、模型不符、在途窗口满或PUBACK超时会异常完成。

暂未提供下行HTTP操作接口。可运行网络集成测试验证完整链路：

```powershell
mvn -B -ntp -s .mvn/settings-central.xml -pl platform-components/network-component -am verify
```

## 扩展接口与当前范围

| 接口／配置 | 当前行为与替换方式 |
| --- | --- |
| DeviceAccessGateway | 通用启停与按设备投递；HTTP／TCP等可独立实现，不要求订阅或长连接 |
| DeviceAuthenticator | management下以登记设备的BCrypt哈希验证密钥，ClientId／用户名必须与设备ID一致；未启用management则拒绝认证 |
| DeviceAccessResolver | management下读取同范围ACTIVE设备、启用产品／租户与设备绑定的已发布模型；每次操作重新查询 |
| ProtocolSupport | 默认OfficialMqttProtocol；自定义Bean可替换整个接入实例的协议提供者，动态插件与按产品选择暂未实现 |
| DeviceMessageCodec | 提供者返回的同步编解码器，定义Topic和报文映射，网络层不内置业务格式 |
| DeviceMessageHandler | 默认拒绝接收；正式Bean负责可靠接收、持久化和业务处理 |
| development-mode | 显式开启后使用内存设备目录及1024条有界收件箱，不持久化；通过DevelopmentMessageInbox查看快照 |

MQTT默认开启；management模式接入登记设备，缺少业务接收器时会拒绝对应消息／上行，不会自动降级匿名接入。替换正式Bean时关闭开发模式。后续注册域撤销资格可调用 `VertxMqttGateway.disconnectDevice`，并在认证与设备解析中持续拒绝。

首版是基于Vert.x MQTT的定向设备接入服务端：本地明文TCP、单节点、短会话；不提供通用订阅分发、Retain、遗嘱、QoS 2、TLS、离线队列和持久会话。服务端声明相应能力并拒绝不支持的请求，MQTT 3连接被拒绝。它不等同于完整通用Broker。

上行在模型校验和接收端成功后才发QoS 1确认；开发收件箱成功不代表持久化。真实Outbox／Inbox、跨重连去重、命令状态机、注册与管理后台仍待后续模块实现。网关健康可观察监听状态、连接与会话数、接收／拒绝计数，不代表尚未接入的数据库或其他依赖可用。

## 创建业务模块

```powershell
./scripts/New-Module.ps1 -Name device-manager -PackageSegment device -WhatIf
./scripts/New-Module.ps1 -Name device-manager -PackageSegment device
```

现有脚本仅生成根目录同级业务模块、替换包名并登记reactor；不支持嵌套聚合和模板类型选择。能力组件当前按架构约定手动建立。新模块按需声明依赖，并在实现后显式加入启动应用。

## 本机文件视图

透明加密可能造成进程级文件视图不同。若编译器找不到已生成类而javap可读，先排查执行环境。不要修改编码或固化本机JDK路径处理该现象。

## 音视频直播

`video-manager` 提供管理端直播观看授权、会话和平台信令；`media-component` 封装 AWS KVS WebRTC，通过配置启用。配置、IAM、API、App 授权接入边界及撤销限制见 [直播模块说明](platform-manager/video-manager/README.md)。
