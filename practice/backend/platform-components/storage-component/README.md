# 历史存储组件

`HistoryStore` 是设备历史业务使用的技术端口，接口只含序列身份、不可变观测、能力、批量写入、时间范围游标查询及按时间删除。组件不依赖 manager、设备认证或物模型实现。`device-manager` 拥有产品策略、模型／资源校验、归一化、消费决定、清理目录及查询授权；启动层负责组装连接池与 Worker。

首个适配器为 TDengine，经官方 `taos-jdbcdriver 3.9.1` WebSocket JDBC 接入，不依赖本地 taosc。适配器的数据源封装于独立资源对象，不作为第二个 Spring DataSource 干扰管理库。配置启用时会检查连接与建表能力，失败阻止启动；默认 disabled，不启动 TDengine 连接。没有可用存储时查询返回 503，不以空数据或内存存储降级。

连接及复合主键设计参考官方 [Java 连接器](https://docs.tdengine.com/developer-guide/connectors-reference/java/) 和 [超级表说明](https://docs.tdengine.com/tdengine-sql/ddl/stable/)。写入、查询使用参数绑定；清理使用由生成表名和 epoch 整数组成的时间范围 DELETE，避免 WebSocket 预编译更新接口只接受 INSERT 的限制。

## 数据模型与交付语义

- 统一超级表 `observations_v1`，按租户、产品、设备、数据种类和标识的 SHA-256 生成子表。一种设备属性或事件类型是一条序列，属性与事件即使同名也隔离。
- 属性仅把本次携带的值拆成观测，不补全、填零或复制影子；单个、部分、全量上报使用同一链路。事件连同全部字段作为一条记录。
- 主键为设备协议的毫秒时间戳和消息 ID；相同时间不同消息保留，相同主键重试幂等。消息 ID 上限 128 UTF-8 字节。保留观测时间、首次接收时间、模型版本和值类型；值以规范 JSON 文本保存，大整数和高精度小数不经过 DOUBLE 转换。每条 payload 最多 16 KiB，每批最多 100 点；这是首版查询／保留优先的模型，未做原生数值列聚合或吞吐容量验收。
- 独立 `device-history-v1` EventBus 消费组接收属性和事件。管理库事务先保存不可变待写批次及消息指纹，之后写入时序库，最后标记完成。批量可能部分成功，重试使用原主键。待写批次可被 Worker 定时恢复，策略修改不改变已接受批次。不同内容复用消息 ID 被拒绝。Inbox 的已完成决定保存至少七天；超出窗口的外部重放需要另外确认策略语义。
- 接入消息发布到 EventBus 前的崩溃窗口仍继承现有接入链路边界；此实现没有建设通用接入 Outbox，不能宣称全链路零丢失。

## 首版能力

实际支持属性最新值开关、逐次记录、不存历史、按天保留、属性覆盖、事件默认及覆盖。初始属性和事件均不写历史；保存启用历史后才记录后续上报。关闭属性最新值只停止后续该属性的 reported 更新，不立即删除已有影子；desired 不受此开关影响。

严格最近 N 条、仅变化记录、间隔采样及长期小时聚合尚未交付。适配器能力声明均为 false，前端禁用相应选择，后端保存返回 422；不能将旧本地预览自动发布成这些未支持的策略。旧预览仍留在原浏览器，新页面以服务端策略为准。

管理 API（沿用实际资源租户隔离、功能权限、成员权限和 CSRF）：

- GET／PUT `/api/management/catalog/{platform|tenants/{tenantId}}/products/{id}/data-policy`。PUT 包含 `{revision,policy}`，使用独立 revision；同产品行锁下检查并保存，写审计，与物模型版本和产品 revision 独立。
- GET `/api/management/catalog/{platform|tenants/{tenantId}}/devices/{id}/history/{PROPERTY|EVENT}/{identifier}`。参数 `from`／`to` 为 UTC Instant，范围左闭右开且不超过 366 天，`limit` 1～100，`cursor` 为返回的不透明游标。按观测时间及消息 ID 倒序翻页。响应 payload 为原始 JSON 文本以保留精度。可查询旧模型标识；每次查询先校验当前设备资源授权。

保留周期按每条序列执行：查询即时过滤超期数据；Worker 每分钟有界扫描 100 条序列，删除其时间界限之前的记录。关闭历史停止新写入，旧记录仍按最后有效周期到期；缩短时间周期会清理超期历史，延长不能恢复已清理数据。删除标记不代表磁盘已立即释放，最终物理回收还取决于 TDengine。数据库 KEEP 为配置的最大保留周期加三天；这是新库创建参数，修改部署上限前需由运维同步核对既有数据库 KEEP。本版固定一个物理存储，禁止在线切换后假装旧数据已迁移。

## 启动与验证

在 `practice/infra` 启动隔离的本地服务：

```powershell
docker compose -f compose.tdengine.yml up -d
```

单体模式在后端 `.env.local` 中显式配置，由既有管理启动脚本加载。独立服务模式通过进程环境变量为 management 和 worker 提供相同配置（`Start-Service.ps1` 不读取 `.env.local`）：

```dotenv
HISTORY_PROVIDER=tdengine
TDENGINE_URL=jdbc:TAOS-WS://127.0.0.1:6041/
TDENGINE_USERNAME=root
TDENGINE_PASSWORD=本地TDengine实际密码
TDENGINE_DATABASE=nebula_history
HISTORY_MAXIMUM_RETENTION_DAYS=3650
```

官方本地镜像初始 root 密码为 `taosdata`；实际连接使用已设置的当前密码。数据库账号需要新库初始化 DDL、写入、查询及 DELETE 权限，凭证不返回浏览器。TDengine 仅需要 management／worker 访问，access 角色不建立它的连接。

常规测试：

```powershell
mvn.cmd -B -ntp -s .mvn/settings-central.xml -pl platform-bootstrap -am test '-Dtest=DeviceHistoryApiTest,DeviceShadowApiTest,TdengineHistoryStoreTest,TdengineLiveTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

`TdengineLiveTest` 只有显式提供 `TDENGINE_TEST_URL`、`TDENGINE_TEST_PASSWORD`（可选 `TDENGINE_TEST_USERNAME`）时才运行，创建自己独立的测试库并在结束删除该测试库。它校验真实复合主键、精度、重复写入、同时间多消息、事件隔离、游标及删除；没有真实服务时会明确跳过，JDBC mock 与 H2 不代替该验收。

本机已使用 WSL 的 `tdengine/tsdb:3.3.8.8` 开发容器通过真实适配器验收；仓库独立 Compose 的 3.3.6.13 镜像编排尚未单独运行。产品页已移除本地预览模式，设备“事件列表”与“设备历史”均调用实际历史 API。

`DeviceHistoryLiveFlowTest` 为运行中服务的完整验收：显式设置 `HISTORY_FLOW_BASE_URL=http://127.0.0.1:5173/api/management`、`HISTORY_FLOW_USERNAME`、`HISTORY_FLOW_PASSWORD` 后运行同名 Maven 测试。它通过前端代理登录，创建独立测试产品与设备，保存策略，连接本机 1883 上报属性和事件，经 Kafka／Worker 写入 TDengine 后从管理 API 读回，检查部分上报、重复报告及小数精度。测试结束删除本次产品与设备；测试历史按一天周期到期。仅允许本机地址，凭证通过进程环境提供，不写入测试源码。

2026-10-19 已在本机真实运行的前端代理、独立管理／接入／Worker、Kafka 与 TDengine 3.3.8.8 上通过此完整验收。联调修复了正式 MQTT 默认接收器未接收事件、Kafka JSON 解码小数精度损失两处问题；产品和设备测试资源已在验收后删除。

## 新增其他适配器

实现 `HistoryStore` 并由启动层显式装配。业务模块不得根据 provider 拼接厂商 SQL。适配器必须通过同一组批量部分成功／幂等、同时间不同消息、空数据与失败区分、游标稳定性、精度及序列隔离验收，并准确申报能力。新增 InfluxDB／TimescaleDB 时分别确定物理映射、时间保留及精度，不能为宣称接口兼容而静默降级。
