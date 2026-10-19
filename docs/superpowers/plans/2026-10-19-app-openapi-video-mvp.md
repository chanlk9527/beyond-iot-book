# App OpenAPI 音视频 MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将现有 C 端音视频入口收敛为一条可复用、可防重放的 App OpenAPI 认证闭环。

**Architecture:** 外部 App Server 继续负责 C 端登录、绑定和业务授权；IoT 通过应用 ID、短时 HMAC 绑定声明和一次性请求 ID验证调用者，再由现有设备状态和媒体会话服务完成最终校验。第一轮不引入独立账号库或新的设备绑定表。

**Tech Stack:** Java 21、Spring MVC、Spring Boot、JUnit 5、现有 `video-manager` 和 `device-manager` 模块。

**Spec:** `manuscript/part-01-iot-platform/02-nebula-iot-platform/2.6-open-capability-platform.md`

## Global Constraints

- 外部系统负责 C 端账号、绑定和业务权限；IoT 负责设备状态、媒体会话和平台侧最终校验。
- App 请求只能使用短时、能力范围明确的声明，不能提交内部设备凭证或未签名权限字段。
- 现有 `/api/app/video` 路径保持兼容；未启用配置时接口不可用。
- 认证失败不泄露设备、会话或媒体提供方信息。
- 不修改工作树中与本功能无关的已有改动。

## Review Focus

- 缺少应用身份、签名或请求 ID时必须拒绝。
- 请求时间过期、请求 ID重复或绑定字段被篡改时必须拒绝。
- 重放保护达到上限后不能无限增长内存。
- 会话创建、信令轮询和关闭必须使用同一绑定目标。
- 设备撤权或状态失效后，后续信令必须停止。

---

### Task 1: 固定 App OpenAPI 请求认证契约

**Files:**
- Modify: `practice/backend/platform-manager/video-manager/src/main/java/com/nebula/iot/video/api/AppLiveRequestVerifier.java`
- Test: `practice/backend/platform-manager/video-manager/src/test/java/com/nebula/iot/video/api/AppLiveRequestVerifierTest.java`

**Interfaces:**
- Consumes: 应用 ID、subject、tenant、device、channel、timestamp、request ID、signature。
- Produces: 认证后的绑定声明，并保证同一 request ID 在有效窗口内只能使用一次。

- [ ] **Step 1: Write failing tests** for missing application ID, duplicate request ID, tampered request ID, and successful unique request.
- [ ] **Step 2: Run the focused verifier test** and confirm the new cases fail.
- [ ] **Step 3: Extend verifier input and canonical signature** with `appId` and `requestId`; add a bounded, time-aware replay cache.
- [ ] **Step 4: Run the focused verifier test** and confirm all authentication cases pass.

### Task 2: 接入音视频控制器并保持接口一致

**Files:**
- Modify: `practice/backend/platform-manager/video-manager/src/main/java/com/nebula/iot/video/interfaces/AppLiveController.java`
- Modify: `practice/backend/platform-manager/video-manager/src/main/java/com/nebula/iot/video/infrastructure/LiveConfiguration.java`
- Modify: `practice/backend/platform-manager/video-manager/README.md`

**Interfaces:**
- Consumes: `X-Nebula-App-Id`、`X-Nebula-App-Request-Id`及现有绑定声明头。
- Produces: 原有创建、信令和关闭接口的认证调用；配置项 `nebula.app.video.app-id`。

- [ ] **Step 1: Add controller tests or request-level assertions** for missing and duplicate request headers.
- [ ] **Step 2: Run the focused video tests** and confirm the new request contract fails before implementation.
- [ ] **Step 3: Wire the new verifier contract and configuration** into all four App video operations.
- [ ] **Step 4: Update integration documentation** with canonical signing order and replay behavior.
- [ ] **Step 5: Run video-manager tests** with the narrowest Maven command available.

### Task 3: 回归验证并记录基线限制

**Files:**
- Modify only if needed: existing test or build configuration files directly responsible for failures.

- [ ] **Step 1: Run focused verifier and video service tests.**
- [ ] **Step 2: Run the relevant Maven reactor with tests enabled.**
- [ ] **Step 3: If unrelated baseline failures remain, record the exact module, error and reproduction command without masking them.**
- [ ] **Step 4: Run `git diff --check` and inspect only this feature's diff.**
