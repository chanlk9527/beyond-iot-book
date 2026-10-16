# 业务管理模块模板

普通 JAR 脚手架，参与父工程编译检查，不加入应用运行依赖。新模块继承父 POM，不依赖模板 JAR或统一业务基类。

分层、依赖方向以及能力组件／业务管理／运行应用三类模板规划，统一维护在 [项目架构](../../docs/architecture.md#父工程与三类模板)。本模板已有 api、application、domain、infrastructure、interfaces 包；小模块按需使用。

创建命令、脚本能力和限制见 [后端使用说明](../README.md#创建业务模块)。模板不预装数据库、Web、Redis或MQTT依赖，子模块按需声明，由父工程管理版本；只有运行应用启用 Boot 重打包。
