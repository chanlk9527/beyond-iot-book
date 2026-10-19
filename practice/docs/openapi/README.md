# OpenAPI 接口规范与接入指南

适用范围：当前仓库的应用认证、开发者中心和平台审核接口。核对日期：2026-10-19。

- [机器可读接口定义](openapi.json)：OpenAPI 3.0.3，可导入支持该格式的接口工具。
- [PowerShell 接入示例](examples.ps1)：换取令牌、查询身份，以及按需撤销令牌。
- [核心模块实现说明](../../backend/platform-manager/identity-manager/README.md)。

本文记录已实现行为。后续接口设计遵循这里的基础约定；尚未实现的治理能力列在末尾。

开发者门户的 `/developer/docs/openapi` 展示本文，`/developer/examples` 提供接口定义与脚本下载。openapi.json 是仓库中的静态契约，目前没有额外提供 Swagger UI 或自动生成的后端文档接口。

## 1. 接入范围

```text
租户管理员创建应用 → 平台审核能力 → 租户管理员获取密钥
→ 外部服务换取令牌 → 携带 Bearer 令牌调用开放接口
```

目前开放端只有令牌签发、当前调用身份和令牌撤销三个接口。设备绑定、用户代调用、设备资源授权尚未完成。获批 `video:watch` 不表示已经能通过这套 Bearer 机制观看设备视频。

应用密钥保存在外部服务端。C 端 App 和浏览器不能持有密钥，也不使用管理端账号密码调用开放接口。

## 2. 通用约定

| 项目 | 当前约定 |
| --- | --- |
| 基础地址 | 由部署方提供；生产环境使用 HTTPS，本地联调可用 HTTP |
| 开放接口前缀 | `/api/open/v1` |
| 管理接口前缀 | `/api/management`，用于管理控制台，不属于外部应用权限 |
| 启用条件 | 后端启用 `management` profile；开发者界面需对应前端版本 |
| 请求与响应 | JSON，`Content-Type: application/json`；登录使用表单 |
| 字段 | camelCase；成功时直接返回对象或数组，不包裹 `data` |
| 空响应 | HTTP 204，不返回 JSON |
| ID | 字符串，调用方按不透明标识处理；放入路径前进行 URL 编码 |
| 时间 | `expiresAt`、`occurredAt` 为带时区的 ISO 8601 字符串 |
| 数组与集合 | scopes 数组无顺序含义，不依赖服务端返回顺序 |
| 缓存 | 开放接口和返回密钥的响应带 `Cache-Control: no-store` |

开放接口版本在 URL 中。管理接口暂未版本化。增加可选响应字段时，客户端应忽略不认识的字段；删除字段、改变类型或鉴权含义应通过新版本交付。当前还没有版本退役通知机制。

## 3. 身份与权限

### 外部应用

签发令牌时提交 `appId`、`clientSecret`、`scopes`。其他开放请求使用：

```http
Authorization: Bearer <accessToken>
```

当前 `Bearer ` 前缀区分大小写。令牌是不透明字符串，客户端不解析内容。令牌有效期 1800 秒，没有 refresh token；过期后用应用密钥重新申请。

租户应用的 `subject` 当前等于 `appId`，`tenantId` 由平台从应用归属记录取得。令牌请求不能指定外部用户或租户；`subject`、`tenantId` 应省略，兼容字段为 null 时也不会用于授权。

OpenAPI 使用独立的无状态安全链，不接受管理端 Session，也不需要 CSRF。管理端 Session 不能替代 Bearer；Bearer 不能替代管理端登录。

### 管理账号

- 租户有效 OWNER / ADMIN 可以管理本租户的应用，VIEWER 无权操作。
- 平台超管使用平台审核入口。平台身份不会自动获得租户入口的成员资格。
- 管理接口使用登录 Session；POST、PUT、DELETE 需要 CSRF。
- GET `/api/management/auth/csrf` 返回 `{headerName,token}`。写入时将 token 放入返回的 headerName 对应请求头，并保留 Session Cookie。
- 登录为 POST `/api/management/auth/login`，表单字段 `username`、`password`。登录成功为 204，之后重新获取 CSRF；退出为 POST `/api/management/auth/logout`。

开发者面板沿用该登录机制。外部服务无需调用这些管理接口，申请、审批和凭证操作可直接在页面完成。

### 能力范围

| scope | 含义 |
| --- | --- |
| `device:read` | 查询设备 |
| `device:control` | 控制设备 |
| `video:watch` | 实时视频 |
| `video:recording` | 录像访问 |
| `event:read` | 读取事件 |

令牌申请至少包含一项，只能申请应用已获批范围的子集。scope 表示接口类型，不代表对任意设备的授权。业务接口必须另外检查应用、租户和设备关系。

## 4. 外部接口

### POST `/api/open/v1/oauth/token`

无需 Bearer；使用请求体中的应用凭证认证。成功为 200。

```json
{"appId":"app-example","clientSecret":"你的应用密钥","scopes":["device:read"]}
```

```json
{"accessToken":"不透明访问令牌","tokenType":"Bearer","expiresIn":1800,"scopes":["device:read"]}
```

`appId`、`clientSecret` 和非空 scopes 数组必填。密钥错误、应用不可用返回 401；申请超出获批范围返回 403；未知 scope 或指定用户/租户返回 400。

虽然路径包含 `oauth`，当前协议使用 JSON、camelCase 和自定义响应，未实现标准 OAuth 2.0 的 form 请求、grant_type、标准响应字段、授权码或刷新流程。不能直接按标准 OAuth 客户端默认配置接入。

### GET `/api/open/v1/me`

需要 Bearer；成功为 200，用于验证应用接入。

```json
{"appId":"app-example","subject":"app-example","tenantId":"tenant-example","scopes":["device:read"],"expiresAt":"2026-10-19T08:30:00Z"}
```

历史平台应用没有租户归属，tenantId 可以为 null。密钥、令牌摘要不会出现在身份响应中。

### POST `/api/open/v1/oauth/revoke`

需要 Bearer，无请求体；撤销请求头中的当前令牌，成功为 204。撤销后该令牌的下一次请求返回 401。重复撤销同一令牌也返回 401，不能按“每次都返回 204”的幂等接口处理。

## 5. 开发者与审核接口

以下路径省略前缀 `/api/management/developer`。请求体字段在机器可读文档中完整列出。

| 方法与路径 | 使用者 | 请求体 | 成功结果 |
| --- | --- | --- | --- |
| GET `/tenants/{tenantId}/apps` | 本租户管理员 | 无 | 200，App 数组 |
| POST `/tenants/{tenantId}/apps` | 本租户管理员 | `{name,scopes}` | 201，待审核 App，无密钥 |
| POST `/tenants/{tenantId}/apps/{appId}/submit` | 本租户管理员 | `{revision,scopes}` | 200，待审核 App |
| POST `/tenants/{tenantId}/apps/{appId}/secret` | 本租户管理员 | `{revision}` | 200，`{clientSecret}` |
| GET `/tenants/{tenantId}/apps/{appId}/audit` | 本租户管理员 | 无 | 200，操作记录数组 |
| GET `/reviews` | 平台超管 | 无 | 200，全部租户应用数组 |
| GET `/reviews/{appId}/audit` | 平台超管 | 无 | 200，操作记录数组 |
| POST `/reviews/{appId}` | 平台超管 | `{revision,approved,scopes,note}` | 200，审核后的 App |
| PUT `/reviews/{appId}/status` | 平台超管 | `{revision,enabled}` | 200，修改后的 App |

应用名称为非空字符串，最多 100 字符。批准时 scopes 必须为申请范围的非空子集；驳回时可传空数组。note 必须是字符串，最多 1000 字符，驳回时不能为空。

App 返回字段：`appId`、`name`、`tenantId`、`tenantName`、`requestedScopes`、`scopes`、`reviewStatus`、`reviewNote`、`enabled`、`revision`。这里两个能力字段是**空格分隔字符串**，没有能力时是空字符串；与令牌接口的 scopes 数组不同。

reviewStatus 为 `PENDING`、`APPROVED`、`REJECTED`；enabled 为独立的运行状态。只有审核通过且启用的应用可用。待审核时不能重复提交；被驳回或已经通过的应用可以重新申请。重新申请会暂停应用、清空已授予范围，并使旧令牌失效。

获取密钥实际上是轮换操作，只能对已批准且启用的应用调用。首次获取也生成新密钥，关闭响应/页面后无法找回，丢失时需再次轮换。重新审批本身不轮换密钥，重新批准后可以用仍有效的密钥换取新令牌。

修改请求使用列表中最新的 revision，不自行推算。密钥接口只返回密钥，成功后应重新读取列表获取 revision。版本过期、状态不允许或已完成的审核返回 409。

列表最多返回最近 100 个应用，按创建时间倒序，再按 appId 排序；不支持分页、total 和筛选。操作记录最多 100 条，包含 `actor`、`action`、`detail`、`occurredAt`，按时间和记录 ID 倒序。action 包括 CREATED、SUBMITTED、APPROVED、REJECTED、SECRET_ROTATED、ENABLED、DISABLED；detail 是展示信息，不作为结构化业务协议解析。

### 历史平台应用接口

平台超管仍可使用 `/api/management/openapi/apps` 下的旧接口：POST 根路径 `{appId,name,scopes}` 直接注册平台应用，返回 `{application,clientSecret}`；PUT `/{appId}/status` 返回 204；POST `/{appId}/secret` 返回 `{clientSecret}`。后二者请求体分别为 `{enabled,revision}`、`{revision}`。

旧注册方式没有租户和审批流程，旧应用不会出现在租户开发者列表中。租户接入应使用开发者流程；旧接口不列为租户自助接入方案。

## 6. 错误与重试

先按 HTTP 状态处理，再读取可选错误体。当前错误体没有完全统一。

| 状态 | 含义与处理 |
| --- | --- |
| 400 | 参数或能力无效，修正请求后再试 |
| 401 | 登录/凭证/令牌无效或应用不可用；检查状态与凭证，不能无限换令牌重试 |
| 403 | 无角色、跨租户、范围不足或 CSRF 无效；修正授权或重新获取 CSRF |
| 404 | 当前访问范围内没有该资源 |
| 409 | revision 过期、编码重复或当前状态不允许；读取最新状态后处理 |
| 5xx | 服务异常，记录状态并排查；写请求结果不明时先查询状态 |

控制器显式处理的错误可能为 `application/json`：

```json
{"error":"invalid_request"}
```

已存在的 error 值有 `invalid_request`、`invalid_client_or_token`、`insufficient_permission`、`application_already_exists`。开发者控制器目前只显式返回 invalid_request；权限/状态错误可能由安全链或 Spring 处理。

安全链与 Spring 状态异常可能为 `application/problem+json`，包含 `type`、`title`、`status`、`detail` 等字段。Bearer 认证失败的安全链响应带 `WWW-Authenticate: Bearer`；令牌签发控制器的 401 不保证该头。不要依赖英文 detail 进行业务分支。

GET 可在临时故障后有限次退避重试。创建、审核、重新申请和密钥轮换没有幂等键支持，不要自动重放。密钥轮换响应丢失时，读取最新 revision 后再次轮换，并使用最后一次成功保存的密钥。签发令牌每次会生成新令牌，重试也不会返回上一次结果。

应用停用、轮换密钥、重新申请都会递增应用 revision；旧令牌在后续认证时失效，重新启用不会恢复旧令牌。租户停用时拒绝签发和认证；当前没有租户授权版本机制，不能承诺重新启用租户后旧令牌永久无效。这些检查不会强制终止已经执行中的操作或既有媒体流。

## 7. 可运行示例

先在开发者中心完成申请并保存密钥，再从服务端环境读取凭证。示例不输出密钥或令牌：

```powershell
$env:NEBULA_OPENAPI_BASE_URL = 'http://127.0.0.1:8080'
$env:NEBULA_OPENAPI_APP_ID = '你的 App ID'
# NEBULA_OPENAPI_CLIENT_SECRET 由本机环境或密钥管理工具注入。
& ./practice/docs/openapi/examples.ps1
# 带 -RevokeAfterCheck 会在验证完成后撤销本次签发的令牌。
```

脚本默认只申请 device:read，可用 `-Scopes 'video:watch'` 指定已获批范围。调用失败时不自动重试，也不回显包含凭证的请求体。

## 8. 后续需要统一的规范

这些是后续开发项，本轮文档不表示已经提供：

- 统一错误体与稳定业务错误码，覆盖参数解析、安全链和业务层。
- 统一 scopes 的数组/字符串表达，以及版本化的分页协议。
- 明确标准 OAuth 2.0 兼容方案；不能只改文档字段让现有接口看起来兼容。
- 请求 ID、调用审计、应用配额、限流和 Retry-After。
- 幂等键、版本退役流程、Webhook 签名与重试约定。
- 用户身份映射、设备资源授权，以及具体业务 API 的开放清单。

更新接口时同步修改控制器、本文、openapi.json、示例和对应接口测试。不要把尚未接入统一鉴权的设备或 HMAC 音视频接口加入本开放清单。
