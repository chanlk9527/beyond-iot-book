# OpenAPI 核心

完整接口契约见 [OpenAPI 接口规范与接入指南](../../../docs/openapi/README.md)，机器可读定义见 [openapi.json](../../../docs/openapi/openapi.json)。

identity-manager 现在提供第一版外部 OpenAPI 基础能力：应用注册、应用密钥、权限范围、访问令牌、令牌校验和撤销。

## 接口

- `POST /api/management/openapi/apps`：平台管理员注册外部应用。响应中的 `clientSecret` 只在创建时返回，调用方应立即保存。
- `POST /api/open/v1/oauth/token`：用 `appId`、`clientSecret` 和申请的 `scopes` 换取 30 分钟 Bearer Token。
- `POST /api/open/v1/oauth/revoke`：携带 Bearer Token 撤销当前令牌。
- `GET /api/open/v1/me`：返回已认证的应用调用上下文。
- `PUT /api/management/openapi/apps/{appId}/status`：管理员提交 `{enabled,revision}` 启停应用。
- `POST /api/management/openapi/apps/{appId}/secret`：管理员提交 `{revision}` 轮换密钥，新密钥只返回一次。

当前允许的范围为 `device:read`、`device:control`、`video:watch`、`video:recording` 和 `event:read`。应用注册时授予范围，换取令牌时只能申请其中的子集。

令牌只以 SHA-256 摘要保存，应用密钥使用平台密码编码器保存。业务模块使用 `OpenApiService.authenticate(token, requiredScope)` 得到 `OpenApiPrincipal`，再进行设备、租户和能力授权判断。

## 调用方式与边界

令牌请求使用 JSON，例如：

```json
{"appId":"home-app","clientSecret":"由注册接口返回","scopes":["device:read"]}
```

调用开放接口时携带 `Authorization: Bearer <accessToken>`。`/api/open/**` 使用独立的无状态安全链，不接受管理端登录 Session，也不要求管理端 CSRF。管理接口继续要求登录、CSRF 和平台管理员权限。令牌和密钥响应禁止缓存。

当前令牌代表应用自身，subject 等于 appId；租户应用的 tenantId 来自归属记录，历史平台应用的 tenantId 为空。不接受调用方直接指定用户或租户。scope 只说明应用可以申请哪类能力，不能证明它有权访问任意设备；业务接口还必须检查资源授权。

应用停用或密钥轮换会递增 revision，旧令牌在下一次请求时失效，重新启用也不会恢复旧令牌。不会中止已经执行中的业务操作。令牌和密钥的字符串表示会隐藏敏感值。

迁移采用全局版本 V19，提供 PostgreSQL/H2 和 MySQL 脚本。端点目前使用项目自定义 JSON 协议，尚不宣称完整兼容 OAuth 2.0 标准。

后续接入用户映射、资源授权、调用审计与限流，再由设备和音视频等模块使用统一上下文。已有应用管理操作审计不等于 API 调用审计。当前 HMAC 音视频入口仍是独立的已有入口。

## 验证

`OpenApiCoreTest` 通过真实 Spring 安全链、Flyway 和 MyBatis，在 H2 中验证应用注册、签发、上下文、撤销、停用、密钥轮换、令牌过期、权限范围及登录隔离。H2 验证不代替真实 PostgreSQL/MySQL 验证。

## 管理端开发者流程

- 租户 OWNER / ADMIN 登录后，从侧栏“开放平台 → 开发者中心”进入 `/developer/apps`。普通 VIEWER 不显示入口，直接请求接口也会被拒绝。
- 创建应用时填写名称并选择能力，应用 ID 由平台生成，所属租户固定，状态为待审核。此时应用不能使用，后台不返回密钥。
- 平台超管从“开放平台管理”进入 `/platform/openapi`，可批准申请能力的子集，也可填写说明后驳回。不能批准租户未申请的能力。
- 审核通过后，租户管理员可“获取 / 轮换凭证”。密钥只展示一次，关闭对话框后清除；再次获取会轮换并使旧令牌失效。
- 驳回后可以修改能力重新申请。已批准应用重新申请时暂停使用、撤销旧令牌，等待重新审核。平台可启停已批准应用。
- 应用创建、申请、审核、启停和凭证轮换都记录操作者与时间。租户只能读取自己的应用和记录，平台可查看全部审核记录。

开发者接口位于 `/api/management/developer/tenants/{tenantId}/apps`，平台审核接口位于 `/api/management/developer/reviews`。写入操作继续要求 CSRF，修改与审核均携带 revision，过期版本返回 409。列表与审计首版各展示最近 100 条。

V20 迁移增加租户归属、能力申请和审计表。V19 的历史平台应用保持原有行为，不会自动归属某个租户；开发者面板展示的是有明确租户归属的应用。租户应用令牌中的 tenantId 由平台从归属记录读取，不能由调用方指定；租户停用后拒绝签发及后续认证。

这两个入口依据平台身份和租户管理角色开放，不通过普通页面菜单授权赋予跨租户权限。应用的能力审批尚不包含设备资源授权，具体业务 API 仍需单独接入。
