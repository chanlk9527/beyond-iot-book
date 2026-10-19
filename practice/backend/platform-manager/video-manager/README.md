# 音视频直播

`video-manager` 管理观看授权、直播会话与统一信令；`media-component` 提供媒体端口和 AWS KVS WebRTC 适配。业务代码只依赖 `MediaProvider`，更换自建 WebRTC 服务时实现同一端口并装配 Bean。当前支持管理端观看实时音视频，不提供对讲、App 登录／绑定、设备推流凭证下发、录像、回放或转码。

## C 端 App 观看

启用 `nebula.app.video.enabled=true` 后，App Server 可调用 `/api/app/video/sessions` 创建观看会话，并继续调用同一路径下的 `signals` 和关闭接口。App Server 必须先完成 App 登录、设备绑定和通道授权，再使用平台与 App Server 之间的共享密钥生成短时 HMAC 绑定声明。平台只信任签名声明中的用户、租户、设备和通道，不接受 App 直接提交的未签名授权字段；平台仍会复核设备与产品为 ACTIVE／启用状态。

签名请求头为 `X-Nebula-App-Id`、`X-Nebula-App-Subject`、`X-Nebula-App-Tenant`、`X-Nebula-App-Device`、`X-Nebula-App-Channel`、`X-Nebula-App-Timestamp`、`X-Nebula-App-Request-Id` 和 `X-Nebula-App-Signature`。签名内容是这些字段按 appId、subject、tenant、device、channel、timestamp、requestId 顺序以换行连接，算法为 HMAC-SHA256，结果使用无填充 Base64URL；默认时间偏差窗口为 60 秒。同一 requestId 在有效时间窗口内只能使用一次。应用 ID 通过 `nebula.app.video.app-id` 注入，密钥通过 `nebula.app.video.signing-secret` 注入，不能提交到仓库。创建、信令轮询和关闭请求都必须重新签名并携带原始绑定目标，平台会核对该目标与会话一致。

该接口只负责观看信令，不通过 MQTT 下发开始／停止直播指令。设备侧仍需作为 KVS MASTER 持续推流；如果业务要求按观看会话动态启停设备推流，应另行增加受信设备控制和对应物模型操作。

## 授权

管理接口沿用平台 Session 与 CSRF。创建会话以及每次收发信令都校验：

- 当前账号启用，具有 `video:watch`、`device:read`。
- 根据数据库中的实际设备归属校验租户／成员，平台范围沿用超级管理员边界。
- 设备为 ACTIVE，产品启用，通道在服务端配置中存在。
- 信令和关闭操作只允许会话创建者。未知会话和他人会话均返回 404。

平台超管自动获得注册的 `video:watch` 权限。普通账号需显式授权；可在现有菜单管理中将 `video:watch` 加入设备管理页面所需能力，再按角色分配页面权限。注意这会通过现有菜单授权流程影响该页面的授权角色，不会仅授权某台设备。租户成员关系仍独立校验，不因功能权限获得跨租户访问。

`LiveAccessPolicy` 是业务授权端口。MANAGEMENT身份沿用上述后台授权；APP身份默认关闭，显式启用后由App路由验证HMAC声明、构造身份，并限定会话与声明目标一致，再复核设备／产品资格。App账号、绑定关系和通道授权由App Server负责，平台不提供这套账号／绑定管理，也不使用OpenAPI Bearer令牌替代该HMAC入口。客户端不能自行指定可信身份或未签名的资源归属。

## 启用 KVS

默认 `nebula.video.provider=disabled`，启动不读取 AWS 凭证、不连接 AWS。使用现有 management profile，并通过额外配置文件传入：

```yaml
nebula:
  video:
    provider: kvs
    region: ap-southeast-1
    session-ttl: PT3M         # 允许 PT10S 至 PT5M
    maximum-sessions: 100     # 单进程容量，允许 1 至 1000
    channels:
      - scope: your-tenant-id # 平台所属设备使用 platform；必须匹配真实归属
        device-id: your-device-id
        channel-id: main     # 平台业务通道标识
        resource: arn:aws:kinesisvideo:ap-southeast-1:123456789012:channel/your-camera/1234567890
```

例如通过 `--spring.config.additional-location=file:/绝对路径/video-local.yml` 加载。不要将实际账号配置和密钥提交到仓库。AWS 凭证使用 SDK 默认凭证链，部署时优先使用工作负载 IAM Role；不向管理端返回 AWS Access Key、签名 URL 或 Channel ARN。

KVS signaling channel 由运维提前创建，摄像头作为 MASTER 持续接入，服务端只作为 VIEWER。设备侧 KVS Master 集成和其 IAM 凭证需单独部署，本模块不自动创建／删除云资源，不修改设备现有 MQTT 凭证。服务端 IAM 最小操作为以下三项，Resource 限定到配置的通道 ARN：

```json
{
  "Effect": "Allow",
  "Action": [
    "kinesisvideo:GetSignalingChannelEndpoint",
    "kinesisvideo:GetIceServerConfig",
    "kinesisvideo:ConnectAsViewer"
  ],
  "Resource": "arn:aws:kinesisvideo:ap-southeast-1:123456789012:channel/your-camera/1234567890"
}
```

相同资源不能重复绑定到多个平台通道，以防配置误将其他设备画面关联到当前设备。服务端信令走 HTTPS／WSS，须可访问 AWS；客户端须能访问 ICE 中的 TURN 地址。ICE 只返回短期 TURN 用户名／凭证及标准地址，地址可能含云服务域名；屏蔽的是专有调用协议、资源标识和云凭证，不承诺网络地址隐藏。当前强制获取 TURN 配置，不拼接特定区域的 STUN 地址。

AWS 接口依据：[获取 ICE 配置](https://docs.aws.amazon.com/kinesisvideostreams/latest/APIReference/API_signaling_GetIceServerConfig.html)、[Viewer 连接](https://docs.aws.amazon.com/kinesisvideostreams-webrtc-dg/latest/devguide/ConnectAsViewer.html)、[发送 SDP](https://docs.aws.amazon.com/kinesisvideostreams-webrtc-dg/latest/devguide/SendSdpOffer.html)。KVS 消息采用 Base64 JSON，封装与 SigV4 签名仅发生在适配器内部。

## 平台 API

统一前缀 `/api/management/video`，响应包含敏感连接配置，设置 `Cache-Control: no-store`。写操作使用已有 CSRF 请求头。

| 方法与路径 | 行为 |
| --- | --- |
| POST `/sessions` | `{tenantId,deviceId,channelId}` 创建观看会话，201；平台范围 tenantId 为 null |
| POST `/sessions/{sessionId}/signals` | `{type,payload}` 发送 OFFER 或 ICE，204 |
| GET `/sessions/{sessionId}/signals` | 非阻塞取走按接收顺序排列的信令数组；空数组表示暂无新消息 |
| DELETE `/sessions/{sessionId}` | 释放自己会话的信令资源，204；重复关闭仍成功 |

创建响应示例：

```json
{
  "sessionId": "平台生成的不透明标识",
  "protocol": "webrtc",
  "expiresAt": "2026-10-19T01:03:00Z",
  "iceServers": [{"urls":["turn:example.invalid:443?transport=tcp"],"username":"短期用户","credential":"短期凭证"}]
}
```

`payload` 是标准 WebRTC 对象的 JSON 字符串，不是 KVS Base64 报文。例如：

```json
{"type":"OFFER","payload":"{\"type\":\"offer\",\"sdp\":\"标准 SDP 文本\"}"}
```

客户端只发送 OFFER、ICE；服务端返回 ANSWER、ICE。OFFER 必须只包含 recvonly／inactive 音视频媒体段，不接受发布媒体的方向、ANSWER 或数据通道。通用入口限制 payload 为 16000 字符；KVS 适配器额外限制 Base64 后不超过 10000 字符，超过时关闭会话。发送成功表示信令已交给传输连接，不表示设备应答或媒体已连通；KVS 异步错误导致该会话关闭。

浏览器客户端先创建 `RTCPeerConnection`，添加 recvonly 音视频 transceiver，再发送 OFFER，随后有序发送 ICE；收到 ANSWER 后再应用缓存的远端 ICE。管理端设备详情的“音视频直播”页已经实现此流程，默认通道 `main`，播放器默认静音。客户端以 700ms 间隔串行轮询信令，离开页面、失败或到期时关闭 PeerConnection 并尽力释放服务端会话，不自动无限重连。

## 期限、撤销与部署限制

会话到期时间取平台 TTL 与 ICE 配置有效期中较早者。服务端每 5 秒进行一次过期、连接状态和业务授权复核，关闭失效会话的信令；每次信令操作同步复核。创建连接后也再次授权，防止远端连接建立期间的撤权绕过检查。拥有者在权限撤销后仍可主动释放资源。

这是单节点实现：会话和最多 256 条待收信令在内存中，重启丢失；多实例需要会话粘滞路由或进一步实现归属路由。队列溢出／消息超限／上游断连时关闭会话，客户端重新申请。轮询是瞬时、破坏性读取，不保证响应丢失后的信令重放。当前远端建连和操作采用同步串行会话管理，适用于首版小规模联调，不是高并发媒体网关。

**关闭信令、撤销平台权限、TURN 到期都不保证已建立的点对点媒体立即停止。** 管理端会在错误或期限到达时关闭本地媒体连接；恶意客户端不受该行为约束。要求解绑／撤权后立即停流时，必须增加设备端受信控制或可强制断流的媒体节点，以及 App Server 的授权撤销事件。当前不能宣称已实现强制停流；要求即时撤销观看权限的App场景须先完成该闭环。观看 SDP 限制也不替代设备端按协商方向限制媒体接收。

## 验证

在 backend 运行：

```sh
mvn -B -ntp -s .mvn/settings-central.xml -pl platform-bootstrap -am verify
```

单元测试覆盖授权拒绝、资源映射、会话归属、容量、期限、撤权、SDP 校验和上游错误脱敏；KVS 签名测试使用独立 HMAC 计算校验临时凭证的签名。`LiveApiTest` 使用 H2、真实迁移及管理安全链测试登录／CSRF、权限、租户和设备状态。前端测试覆盖协商、ICE 顺序、异步创建取消、轮询授权失败与本地过期。

自动化测试不访问 AWS。真实 KVS／设备媒体连通与浏览器播放需用实际通道单独联调，本次不将替身测试视为云端验收。
