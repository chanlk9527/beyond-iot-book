# 规则与集成交互预览

开发服务器运行后，访问 `/automation-preview.html#/automation/flows`，无需后端或账号即可体验独立的示例工作空间。该开发入口使用独立 Hash 路由和内存示例身份，不导入管理端认证路由、不登录后端、不调用管理 API；默认生产构建不包含此 HTML 入口。

管理端登录后，平台超管可从「规则与集成 · 预览」进入 `/automation/flows`、`/automation/scenes`、`/automation/connections`、`/automation/notifications` 和 `/automation/runs`。租户权限和后端 API 尚未交付，因此预览路由不向普通账号开放。

- 数据流转：列表搜索、状态筛选、新建、编辑、复制、删除、启停、演示草稿／发布；产品／设备和消息类型选择；可选数字条件与字段映射、倍数换算；多个目标；JSON 样例实际本地计算。
- SQL 仅展示表单生成的表达示意，不提供任意 SQL 编辑、解析或执行。
- 场景：温度上报触发、每日生效时间、冷却配置、顺序动作和一键执行。单次试运行判断温度及时间，预览动作计划，不模拟连续事件的冷却、实际延时或设备执行。
- 连接：HTTPS、Kafka、RocketMQ 示例配置，地址格式校验，不实际探测网络、不存储凭证。
- 通知：邮件、短信、App 推送示例渠道和变量模板，不实际发送。
- 运行记录：查看模拟输入输出与结果、人工模拟重试，最多 100 条。

所有变更仅保存在内存，页面间共享，刷新重置。对外投递、EventBus、规则运行服务和设备下行均未接入；界面始终标记演示边界。

验证：`npm.cmd test -- --configLoader runner src/automation src/components/automation`，`npm.cmd run build -- --configLoader runner`。`runner` 避免本机部分原生进程无法读取配置文件时的 esbuild 配置加载问题。
