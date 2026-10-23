# 标准 S3 文件组件

`file-component` 提供通用 `ObjectStorage` 接口及标准 S3 实现，供固件、后续设备云存储等业务复用。与存储设备历史数据的 `storage-component` 分开。业务代码不依赖 AWS SDK 类型，不按厂商增加分支。

能力：有界磁盘暂存、实际字节大小及 SHA-256 计算、私有对象上传、流式读取、删除、临时 GET 地址签发。保存 `Reference(bucket,key,versionId)`，不持久化预签名 URL。桶启用版本控制时保留 S3 返回的 versionId，读取／下载／删除均定位该版本。未启用版本控制时，调用方须使用唯一对象键并避免外部覆盖。

## 配置

组件通过 Spring Boot 自动配置加载，默认关闭，不连接对象存储。`nebula.files.*` 可由应用配置或 Spring 环境绑定；管理服务的 `application-management.yml` 同时提供以下环境变量入口：

| 环境变量 | 默认值／作用 |
| --- | --- |
| `FILES_ENABLED` | `false`，显式启用 |
| `FILES_BUCKET` | 必填，已创建的私有桶 |
| `FILES_REGION` | `us-east-1`，AWS 需与桶一致；兼容服务按其要求配置 |
| `FILES_ENDPOINT` | 留空使用 AWS 区域端点；兼容服务填写 HTTP(S) origin，不含桶、路径或认证信息 |
| `FILES_PATH_STYLE_ACCESS` | `false`；兼容服务常需设为 `true` |
| `FILES_ACCESS_KEY` / `FILES_SECRET_KEY` | 同时填写，或同时留空使用 AWS 默认凭证链 |
| `FILES_MAXIMUM_UPLOAD_BYTES` | `67108864`，业务暂存上限，默认64 MiB |
| `FILES_DOWNLOAD_LIFETIME` | `PT10M`，可配置1秒～1小时 |
| `FILE_MULTIPART_MAX_FILE_SIZE` | 管理服务 Servlet 层文件上限，默认 `64MB` |
| `FILE_MULTIPART_MAX_REQUEST_SIZE` | 管理服务请求总大小上限，默认 `65MB`，须容纳文件和表单开销 |

默认凭证链支持标准 AWS 环境变量（包括临时会话 token）、配置文件及实例／容器角色。生产环境建议通过角色或密钥管理注入凭证，不提交到仓库。组件不创建桶、不修改 ACL 或公开读策略，桶与 IAM 策略由部署环境管理。

控制台的 Nginx 部署示例已将管理 API 请求上限设为65 MiB，代理等待时间设为300秒。修改文件大小时需同步调整 Servlet、业务暂存、Nginx 及实际入口网关的限制。

兼容服务示例：

```yaml
nebula:
  files:
    enabled: true
    bucket: nebula-files
    region: us-east-1
    endpoint: https://s3.example.com
    path-style-access: true
    # access-key / secret-key 由环境或密钥管理系统提供
```

AWS 使用相同配置，省略 endpoint，按需要关闭 path-style-access。HTTP 端点只适用于本地开发；固件业务签发下载地址要求 HTTPS。当前 S3 客户端和预签名器使用相同端点，该地址必须能被下载客户端访问。暂不支持内部上传端点与外部 CDN 地址分别配置。

为兼容第三方实现，SDK 按协议需要计算／验证可选 checksum，并禁用 aws-chunked 编码。业务 SHA-256 来自暂存的实际字节，不能使用 ETag 替代摘要。使用 AWS SDK v2 的 SigV4 请求认证和 URL 签名。[AWS checksum 说明](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/s3-checksums.html)、[S3 预签名器说明](https://docs.aws.amazon.com/java/api/latest/software/amazon/awssdk/services/s3/presigner/S3Presigner.html)。

## 业务接入

```java
try (StagedUpload staged = StagedUpload.read(inputStream, maximumBytes)) {
    // 业务可以先通过 staged.open() 验证签名或文件内容，再上传。
    ObjectStorage.StoredObject stored = storage.put(
        "cloud/tenant-id/device-id/unique-id.bin", staged, "application/octet-stream");
    // 业务持久化 stored.reference()、size、sha256，管理归属与权限。
}
```

调用方拥有输入流生命周期；`StagedUpload` 在关闭时清理临时文件，失败暂存也会清理。组件不会把文件整体读入内存。`open()` 返回的对象流须由调用方关闭。上传使用可重读的暂存文件，支持 SDK 请求重试。对象键采用安全子集，禁止路径跳转，不接收用户文件名作为存储路径；业务负责生成唯一键、校验租户归属和设置配额。

组件本身不提供无业务归属的公共 HTTP 上传／删除 API，不管理租户权限、文件台账、固件签名或业务事务。数据库与 S3 不具备原子提交，调用方须设计失败补偿和孤立对象清理。网络中断或进程退出可能留下无业务引用对象；不要给正在使用的对象前缀设置无条件过期策略。

当前为单次 PutObject 与服务端中转上传，不包含 S3 multipart 分片续传、浏览器直传、目录检索、多桶路由、配额计费或自动迁移。更换 endpoint／bucket 是连接配置变更，不会自动搬迁旧对象；已有引用须配合数据迁移保持可访问。

## 验证

`ObjectStorageTest` 使用实际 AWS S3 SDK 对接本机 HTTP 测试服务，验证上传原始字节、SigV4 认证头、path style、摘要元数据、版本引用、流读取、删除及临时链接；同时验证 AWS 默认端点签名、配置校验和暂存大小限制。它不是完整 S3 服务验收，部署时仍需使用目标 AWS／兼容桶验证权限、TLS、端点、版本控制与下载可达性。
