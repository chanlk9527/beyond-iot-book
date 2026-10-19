# 开始接入星云 IoT

开发者中心集中提供应用接入和设备开发资料。阅读文档不需要登录；应用申请和凭证管理需要有效的租户管理员账号。

## 选择你的接入方式

| 开发任务 | 从哪里开始 |
| --- | --- |
| 为 App 或行业后台调用平台能力 | 申请应用，阅读 OpenAPI 接入指南 |
| 让设备连接平台 | 阅读设备接入指南和 MQTT 协议 |
| 验证应用凭证 | 运行 PowerShell 示例，查询当前应用身份 |

## 应用开发的第一步

1. 使用管理员为你开通的账号登录，在“我的应用”创建应用。
2. 填写名称，选择需要的能力，提交后等待平台审核。
3. 审核通过后获取密钥，将密钥保存到外部服务端。
4. 用应用 ID 和密钥换取短期 Bearer 令牌。
5. 调用身份接口，确认返回的应用和租户符合预期。

请求示例：

```http
POST /api/open/v1/oauth/token
Content-Type: application/json

{"appId":"你的应用 ID","clientSecret":"你的应用密钥","scopes":["device:read"]}
```

然后携带 `Authorization: Bearer <accessToken>` 调用 `GET /api/open/v1/me`。

完整字段、错误处理及权限边界见 [OpenAPI 接入指南](../openapi/README.md)。可运行的脚本见 [接入示例](../openapi/examples.ps1)，接口工具可导入 [OpenAPI 定义](../openapi/openapi.json)。

## 设备开发的第一步

先由管理端登记产品、发布物模型并登记设备。设备使用平台生成的独立凭证连接 MQTT，按协议上报属性和事件，并处理平台命令。

设备凭证和应用密钥是两类凭证，各有用途，不能互换。具体步骤见 [设备接入指南](device-development.md) 和 [MQTT 协议](../mqtt-protocol.md)。

## 当前开放范围

应用认证、申请审核和凭证管理已经提供。用户代调用、设备绑定和设备资源授权仍在建设；获批某个 scope 不表示所有业务 API 都已开放。

现有音视频入口与统一 OpenAPI 的接入边界不同，不能直接把这里的 Bearer 令牌用于所有设备或音视频接口。遇到问题可先查看 [常见问题](faq.md)。
