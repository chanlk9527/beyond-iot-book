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

## C 端 App 与 MQTT 联调

前端运行后访问 `/app`，或从开发者中心的「示例与工具」进入「星云宝宝」。默认进入平台联调模式，点击「绑定平台设备」，自行输入任意有访问权限的设备 ID，例如 BBM 婴儿监视器。设备 ID 不写死，也不是设备编码；绑定失败可以重新输入，绑定成功后可在「我的」中更换或解除本机绑定。

联调步骤：

1. 使用同一浏览器登录平台账号（绑定弹窗有登录入口，登录后返回 `/app`）。需要 `device:read`，下发还需要 `device:write`。本版复用平台会话，不新增 C 端账号和设备归属授权。
2. 绑定设备详情中的设备 ID。App 加载设备实际绑定的已发布模型版本，动态展示属性、读写权限和操作参数，不使用产品草稿，也不预设温湿度等属性标识。
3. 在管理端「设备调试」的 MQTT 模拟器或外部 MQTT 客户端，使用该设备凭证连接，并按 [MQTT 协议](docs/mqtt-protocol.md) 向 `nebula/v1/up` 上报 `thing.property.report`。App 使用现有实时 WebSocket 通知刷新设备影子，自动显示新值；「消息」显示本次会话观察到的上报变化。Redis 影子服务和实时订阅链路需要可用。
4. 在 App 中点击可写属性的「设置」，或选择设备功能并输入参数。属性写入通过平台 `downlinks/properties` 接口，操作通过 `downlinks/operations` 接口；设备模拟端接收 `nebula/v1/down` 中的 `thing.property.write` 或 `thing.command.invoke`，按协议回复。App 分开展示下发回执与设备实际值，发送成功不会提前改写 reported 状态。

绑定只在本机浏览器保存账号 ID 和设备 ID，不保存设备密钥，也不修改设备归属。重开页面会重新校验当前账号和设备权限；离开 App 或解除绑定会清理订阅，断线重连后同步快照。当前实时通道覆盖 presence/shadow，消息页的变化记录不替代模型事件历史或可靠告警流。

「查看界面演示」保留原有插画、夜视效果、SVG 插画截图、本机摇篮曲试听、模拟事件及本地设置。演示模式与平台数据模式分开。平台模式暂未连接视频播放、对讲和录像，相关动作不伪装成平台能力。

本机安全工具可能限制 esbuild 进程读取 Vite 配置。遇到配置读取失败时可使用 `npm.cmd run dev -- --configLoader runner` 或 `npm.cmd run build -- --configLoader runner`，由 Vite 的 runner 加载现有配置。