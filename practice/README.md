# 星云 IoT 实战

建设多租户管理后台与 Java IoT 平台，作为《IoT 平台设计》的配套项目。母婴暖奶器用于贯通验证，平台能力面向多产品。

当前具备账号与租户管理、RBAC 和菜单维护、产品与模型版本、设备登记、凭证管理和 Redis 设备影子，以及默认开启的 MQTT 5 接入和默认设备协议。后台登记设备的凭证已接入MQTT认证，可靠上行存储、控制和 OTA 业务仍需完善。

## 阅读入口

- [管理端快捷启动](STARTUP.md)：一条命令启动／重启／停止前后端，日常复用构建产物和依赖。
- [平台架构与开发约定](docs/architecture.md)：模块边界、依赖方向和实现范围。
- [MQTT 默认设备协议](docs/mqtt-protocol.md)：设备端格式、Topic 和交互规则。
- [后端使用说明](backend/README.md)：构建、启动和管理接口；各模块 README 只补充本模块 API。
- [前端使用说明](console/README.md)：开发、构建和部署。

`backend/` 放 Java 代码，`console/` 放管理后台，`infra/` 放本地运行环境。书稿位于 `manuscript/`，阅读顺序由 `SUMMARY.md` 管理。进度以代码、测试和各入口文档为准，不再维护独立的计划副本。
