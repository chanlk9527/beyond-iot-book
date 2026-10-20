# 星云 IoT 实战

建设多租户管理后台与 Java IoT 平台，作为《IoT 平台设计》的配套项目。母婴暖奶器用于贯通验证，平台能力面向多产品。

当前具备账号与租户管理、RBAC 和菜单维护、产品与模型版本、设备登记、凭证管理和 Redis 设备影子，以及默认开启的 MQTT 5 接入和默认设备协议。后台登记设备的凭证已接入 MQTT 认证；新增可适配其他数据库的 TDengine 历史存储首版，支持产品策略、属性／事件采集、按天保留与设备历史查询，部署见 [历史存储组件](backend/platform-components/storage-component/README.md)。

已支持后台属性写入／操作调用、Kafka／Dubbo 跨进程传输、共享节点／设备会话目录，以及显式cluster-commands模式的持久化命令与跨节点下行。新增[生产与交付管理](backend/platform-manager/device-manager/README.md#生产与交付管理)，贯通批次、设备生成、人工烧录／测试、放行和交付记录；三个独立服务的运行方式见[服务说明](backend/platform-services/README.md)。通用可靠交付、影子可靠发布、共享登录／实时票据、历史高级策略和OTA仍待完成，完整集群尚未验收。

## 阅读入口

管理前端启动后可直接访问 `/developer`，公开阅读接入指南、OpenAPI、MQTT、示例和常见问题；“我的应用”需要租户管理员登录，平台审核仍位于管理控制台。

- [管理端快捷启动](STARTUP.md)：一条命令启动／重启／停止前后端，日常复用构建产物和依赖。
- [平台架构与开发约定](docs/architecture.md)：模块边界、依赖方向和实现范围。
- [物模型定义与支持范围](docs/thing-model.md)：属性、功能、事件、枚举、数组与版本边界。
- [EventBus](backend/EVENTBUS.md)：模式选择、Dubbo 路由、监控与交付边界。
- [MQTT 默认设备协议](docs/mqtt-protocol.md)：直连设备与网关子设备的连接、Topic、报文和交互规则。
- [OpenAPI 接口规范与接入指南](docs/openapi/README.md)：应用认证、开发者申请、平台审核、错误处理及调用示例。
- [后端使用说明](backend/README.md)：构建、启动和管理接口；各模块 README 只补充本模块 API。
- [前端使用说明](console/README.md)：开发、构建和部署。

`backend/` 放 Java 代码，`console/` 放管理后台，`infra/` 放本地运行环境。仓库根目录的 `manuscript/` 放书稿，阅读顺序由根目录 `SUMMARY.md` 管理。进度以代码、测试和各入口文档为准，不再维护独立的计划副本。

## 应用实时订阅

管理端与后续 App 通过内置 EventBus 驱动的 WebSocket 订阅获取设备状态变化，详见[实时订阅架构与协议](REALTIME-SUBSCRIPTIONS.md)。当前已接入在线状态、属性影子变更与重连快照恢复。

## 音视频直播

设备详情提供基于标准 WebRTC 的观看入口，直播会话与媒体提供者分离，首个适配为 AWS KVS；默认关闭，需配置现有通道。管理端沿用账号／租户授权，App 授权未接入时拒绝访问。配置、接口与强制停流限制见 [直播模块](backend/platform-manager/video-manager/README.md)。
