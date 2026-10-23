# 固件管理与 OTA 实施

已交付产品固件通道、版本台账、标准 S3 文件上传，以及设备实际固件记录与静态兼容预检。入口分别为产品详情的「固件管理」和设备详情的「固件与升级」。固件版本独立于物模型版本、生产批次目标版本以及设备实际上报版本。

## 接入约定

参考涂鸦的固件通道和版本管理方式：0 为主模块（`main`），1 为蓝牙（`bluetooth`），3 为 Zigbee（`zigbee`），9 为 MCU（`mcu`），10～255 为扩展通道。通道编号和组件标识在产品内唯一；单产品最多16个通道，与现有 `ota.version.report` 的组件数量上限一致。这些数字是管理侧通道编号，下行仍使用现有协议的组件标识，不改变 MQTT 报文格式。

通道创建后，其组件标识和版本规则固定。默认 `TUYA` 规则采用三段数字，每段0～99，例如 `1.10.0` 大于 `1.9.0`；本平台采用无前导零的规范形式，避免同一版本存在多个字符串表示。`OPAQUE` 是面向存量设备的本平台扩展，保留厂商版本原文，不自动判断大小。版本须为1～100字符，无首尾空白或控制字符。

每个固件版本明确列出1～100个允许的源版本和1～100个适用硬件版本，不能为空或重复。不使用空清单表示全部，也不将 `*` 解释为通配符。源版本可以尚未登记，以覆盖已出厂设备。标准规则要求所有源版本低于目标版本；自定义规则只检查源版本与目标不同，升级方向由维护者明确指定。当前不支持降级、同版本重装、范围表达式或多组件依赖。

固件包支持两种来源：上传到平台配置的 S3 私有桶，或登记已有 HTTPS 地址。签名对象是原始完整固件字节，设备须预置信任公钥。托管上传使用通用 [file-component](../backend/platform-components/file-component/README.md)，由服务端计算实际字节大小与 SHA-256，并在上传 S3 前使用平台配置的 Ed25519 公钥验证文件签名。外部地址仍只校验元数据格式，不主动下载，不验证文件存在、文件大小、摘要或签名真实性。

托管文件保存桶、对象键及可用的 S3 versionId，不持久化临时 URL。版本登记传入 `artifact.fileId` 后，服务端按产品和通道查找上传记录，使用实际文件元数据，忽略客户端伪造的大小、摘要等字段。`artifact.url` 对托管文件为 null，外部版本的 `fileId` 为 null。下载时按权限签发临时 HTTPS 地址，响应 `Cache-Control: no-store`，支持过期后重新生成。当前下载接口供管理端检查固件使用，设备任务仍待接入。

### 可信签名公钥

在平台部署配置中维护公钥，客户端不能随上传请求添加信任公钥：

```yaml
nebula:
  firmware:
    trusted-keys:
      factory-key: ${FIRMWARE_FACTORY_PUBLIC_KEY}
```

值为 Ed25519 X.509 SubjectPublicKeyInfo 公钥 DER 的 Base64（不含 PEM 头尾），上传时选择对应 keyId，填写64字节 Ed25519 签名的 Base64。私钥由固件构建／签名系统保管，平台不生成或接收私钥。可信公钥未配置时不能上传固件。S3 部署、端点、凭证、大小限制见组件说明。

上传前检查产品、通道和写权限，S3 网络请求不持有数据库事务；上传后重新检查资格，在同一事务保存文件记录与审计。保存记录失败会尝试删除刚上传的对象。S3 与数据库不具备原子性，删除补偿失败或进程中断仍可能遗留孤立对象，自动对账清理尚未实现。签名验证只证明文件来自可信私钥，不解析固件内的组件、版本、硬件或启动信息；这些校验仍需后续任务与设备安装流程完成。

上传与版本登记分两步，版本登记失败后前端保留已上传 fileId，修改版本信息重试不会重复上传。取消登记可能留下尚未被版本引用的上传记录，当前保留以便后续关联与清理，不提供自动过期删除。页面明确区分“上传时签名已验证”和“外部登记、文件内容未验证”。

版本初始停用；可以显式启用和停用。启用仅代表后续任务可以选择，不代表完成设备验证、灰度发布或升级成功。版本号、包信息、说明、源版本与硬件范围不可覆盖；当前保留台账，不提供删除功能，配置错误须停用并登记新版本。存在固件通道的产品不能物理删除。

## 管理 API

相对 `/api/management/catalog/platform` 或 `/api/management/catalog/tenants/{tenantId}`：

| 方法与路径 | 行为 |
| --- | --- |
| GET `/products/{id}/firmware/channels` | 列出产品通道 |
| POST `/products/{id}/firmware/channels` | `{channel,component,name,versionRule?}`，默认 TUYA，返回201 |
| GET `/products/{id}/firmware/channels/{channel}/versions?page=1&size=20` | 分页列出版本，size最多100，按登记时间倒序，不表示版本大小 |
| POST `/products/{id}/firmware/channels/{channel}/versions` | `{version,description,sourceVersions,hardwareVersions,artifact:{url,size,sha256,keyId,signature}}`，返回201，初始停用 |
| PUT `/products/{id}/firmware/channels/{channel}/versions/{versionId}/status` | `{enabled,revision}`，返回更新后的版本 |
| GET `/products/{id}/firmware/files/capabilities` | 文件存储是否启用、实际字节上限和可信 keyId 列表，不返回凭证 |
| POST `/products/{id}/firmware/channels/{channel}/files` | multipart：`file`、`keyId`、`signature`；返回201及 `{fileId,size,sha256,keyId,signatureVerified}` |
| GET `/products/{id}/firmware/channels/{channel}/versions/{versionId}/download` | 托管版本签发临时 `{url,expiresAt}`，外部版本返回409 |

读取及下载签发需要 `product:read`，创建及上传还需 `product:write`，版本启停还需 `product:publish`。沿用产品页面现有权限，不增加独立菜单或自动扩权。普通租户成员写入须为 OWNER／ADMIN，保留 Session、CSRF 和资源归属校验。平台聚合读取可以查看租户产品，写入和审计使用产品实际归属。产品停用后禁止创建通道、上传文件、登记版本和启用版本，仍可停用固件版本。未启用对象存储时保留外部登记功能，上传返回503；文件超限413，签名无效400，S3失败502。

写事务使用 READ_COMMITTED 和产品行锁，串行化通道数量、版本登记与启停；重复通道／版本或过期 revision 返回409。启停无变化时不增加 revision 或重复审计。所有实际变更在同一事务写入管理审计。V30 同时提供 PostgreSQL／H2 与 MySQL 迁移；外键约束通道与产品归属、版本与通道关联。

## 设备实际固件与兼容预检

Worker 通过现有 EventBus 消费 `info/report` 和 `ota/version-report`，将 `thing.info.report` 的硬件、启动标识、SDK、时钟状态和主模块版本，以及 `ota.version.report` 的组件版本快照写入数据库。只接受经可信接入链路产生的消息；处理时重新核对设备归属、绑定模型、产品及租户接入资格。未登记的 mqtt-dev 测试设备不生成管理台账。没有供管理账号直接覆盖实际上报值的 HTTP 接口。

`ota.version.report` 每次提交1～16项完整快照，未包含的组件不继续沿用上一份 OTA 快照；`main` 可由设备信息报告补充，两种来源中较新的主模块信息优先。组件名采用小写字母开头的字母、数字或下划线，最多64字符；版本和硬件信息最多100字符，bootId 最多128字符。版本原文不符合通道规则时仍保留展示，由预检返回具体错误，不伪造或自动转换版本。

排序使用可信接入节点的 `receivedAt`，不依赖设备时钟；`occurredAt` 仅展示和参与重传内容一致性校验。相同接收时间按 messageId 字典序确定稳定次序，不代表真实安装先后。设备信息与 OTA 快照独立排序，延迟到达的旧消息不会覆盖较新的同类记录。按设备、方法类别、messageId 保存七天去重指纹，相同内容重传不会刷新版本时效或修订号，同 ID 不同内容拒绝处理。超过七天的接收时间直接忽略，每小时分批清理最多1000条过期去重记录；当前状态不随去重清理删除。产品行锁串行化并发首次上报及设备生命周期变更，使用 READ_COMMITTED。

首次发现或变更 bootId 时记录该次启动的观察时间。重复上报同一 bootId 不改变启动观察时间；早于新启动的组件报告仍可查看，但预检阻止使用，要求重新上报。设备应先报告启动信息，再报告全部组件版本。仅 MQTT 重连保持 bootId 不变。未上报的状态显示未知，不从产品档案或生产目标补齐。

报告默认有效24小时，通过 `FIRMWARE_REPORT_MAXIMUM_AGE`／`nebula.firmware.report-maximum-age` 配置正时长，最大七天。预检核对当前产品及通道下的目标固件、设备和产品启用状态、租户接入资格、目标版本启用状态、实际源版本与硬件清单、当前报告时效和启动关联。同版本重装拒绝。外部登记文件返回内容未验证提示；托管版本检查文件记录是否存在。静态检查不请求 S3，不签发下载地址，不判断在线、电量、工作状态、Bootloader 或实际安装能力；这些条件仍须在后续任务及设备端确认。

| 方法与相对路径 | 权限与结果 |
| --- | --- |
| GET `/devices/{id}/firmware` | `device:read`，返回实际信息、组件、修订号、检查时间和报告有效期 |
| GET `/devices/{id}/firmware/channels/{channel}/versions/{versionId}/preflight` | `device:read` + `product:read`，目标必须属于该设备产品的该通道；返回 `compatible`、全部阻断原因和提示、当前／目标版本、硬件、状态及目标修订号 |

两接口沿用平台聚合与租户范围校验；跨租户或跨产品目标不可读。`compatible=true` 仅表示检查时的静态兼容条件满足，不代表升级已执行或必定成功。结果为临时快照，后续任务创建和下发必须重新校验。

设备详情的调试模拟器新增「固件信息上报」，使用真实 MQTT 路径依次发送设备信息与组件快照，可生成新的模拟重启标识。仅在显式确认身份、取得凭证并连接后发送；模拟上报会影响该设备的真实平台记录，不会下载或安装固件。单机默认 Local EventBus 没有持久投递保障；分离部署需使用已有 Kafka／Dubbo 消息模式，并在 worker 启用业务订阅。

设备列表增加「实际固件版本」列，按当前页设备 ID 批量读取，展示全部已报告组件及硬件；与模型版本分列。过期或早于本次启动的组件分别标注。详情的「设备概览」也直接展示版本，可进入「固件与升级」查看完整信息。固件读取失败单独提示并允许重试，不冒充尚未上报；列表翻页后忽略旧页响应。批量接口 GET `/devices/firmware-inventory?ids=id1,id2` 支持1～100台不同设备，逐台校验 `device:read` 与范围后执行一条固件状态查询；任一设备不可读则整批拒绝。

快速模拟：在设备详情进入「设备调试」并显式确认身份、连接模拟设备，选择「固件信息上报」，填写主模块版本和硬件版本后点击「上报设备与组件固件信息」。默认不用编辑 JSON，`main` 自动使用同一主模块版本；该模式提交仅含 main 的完整 OTA 快照，会替换原组件清单。需要保留其他组件时打开「高级：上报完整多组件快照」，填写全部组件 JSON；其中 main 如存在必须与主模块版本一致。模拟器提示两条报告的 MQTT 接收确认或部分失败，不把 PUBACK 当作业务入库成功；上报后到概览／固件页刷新，离开调试回到概览时也会重新读取。

V32 新增设备固件状态与去重表；设备删除后级联清理运行状态。

## 后续步骤

1. 已完成：固件文件上传、大小／摘要计算、可信公钥签名校验和下载地址签发。尚缺上传记录／孤立对象自动对账清理及公钥轮换管理界面。
2. 已完成：设备实际组件版本、硬件与启动信息持久化，以及源版本／硬件／时效的静态兼容预检。
3. 指定设备验证任务，接入现有 MQTT OTA 指令、进度、重连和实际安装版本确认；任务及尝试持久化并关联现有集群下行机制。
4. 验证后发布，支持源版本筛选、稳定灰度分组、暂停与结果统计。

当前不创建升级任务，不下发 OTA，不提供差分升级或回滚能力。V31 新增产品／通道范围的文件记录表，旧版本 JSON 保持兼容。

## 验证

`FirmwareApiTest` 使用真实 Flyway、H2 和管理安全链验证通道／版本登记、数字排序与不透明版本、显式兼容清单、包格式、权限、租户隔离、CSRF、revision、并发登记和产品删除保护。前端 `ProductFirmware.test.ts` 验证服务端数据展示、版本启停、失败保留输入、只读与停用限制、加载恢复及过期响应处理。H2 测试不能替代真实 PostgreSQL／MySQL 并发与迁移验收；没有执行真实设备安装测试。

`DeviceFirmwareApiTest` 验证事件订阅落库、幂等与冲突重传、乱序、重启、过期、自定义版本、硬件与源版本预检、停用与权限隔离、并发和删除清理。`DeviceFirmwarePanel.test.ts`、`DeviceDetailView.test.ts` 和 `DeviceSimulatorPanel.test.ts` 验证实际报告展示、全部失败原因、权限门槛、请求过期处理及模拟上报行为。

参考：[涂鸦 OTA 协议](https://developer.tuya.com/en/docs/iot/OTA_FIRMWARE?id=Kbt4xp0kr2u57)、[涂鸦固件更新流程](https://developer.tuya.com/en/docs/iot/firmware-upgrade-operation-guide?id=K93ixsft1w3to)、[本平台 MQTT OTA 协议](mqtt-protocol.md#10-ota-控制与进度)。
