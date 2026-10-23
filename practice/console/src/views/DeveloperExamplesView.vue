<script setup lang="ts">
import { downloads } from '../developer/resources'
import example from '../../../docs/openapi/examples.ps1?raw'
</script>
<template>
  <RouterLink class="developer-card consumer-app-entry" to="/app"><div><h2>星云宝宝 · App 体验</h2><p>打开婴儿监视器的手机端交互演示，体验监看、事件提醒和看护设置。</p></div><span>打开体验 →</span></RouterLink>
  <div class="page-heading"><div><p class="eyebrow">RESOURCES / EXAMPLES</p><h1>示例与工具</h1><p>从一条可验证的请求开始，使用与你的平台版本一致的资料。</p></div></div>
  <div class="developer-example-downloads"><a v-for="item in downloads" :key="item.filename" class="developer-card" :href="item.url" :download="item.filename"><h2>{{ item.title }} ↓</h2><p>{{ item.description }}</p><small>{{ item.filename }}</small></a></div>
  <section class="developer-example-guide developer-card"><h2>运行接入示例</h2><p>在已获批应用中获取密钥，配置服务端环境变量后，从 PowerShell 运行下载的脚本。</p><pre>$env:NEBULA_OPENAPI_BASE_URL = 'http://127.0.0.1:8080'
$env:NEBULA_OPENAPI_APP_ID = '你的 App ID'
# NEBULA_OPENAPI_CLIENT_SECRET 由环境或密钥工具注入
./nebula-openapi-example.ps1 -Scopes 'device:read' -RevokeAfterCheck</pre><p>示例会签发令牌、查询应用身份，并按参数撤销令牌。它不会输出密钥或令牌。只申请应用已获批的 scopes。</p><RouterLink to="/developer/docs/openapi">阅读完整鉴权与错误处理说明 →</RouterLink></section>
  <details class="developer-example-source developer-card"><summary>查看示例源码</summary><pre>{{ example }}</pre></details>
  <section class="developer-example-guide"><h2>调试前检查</h2><ul><li>应用已审核通过，应用和租户都已启用。</li><li>使用最新密钥，申请范围在获批能力内。</li><li>先调用身份接口确认应用与租户，再检查具体业务能力。</li></ul><p>在线 API 执行、设备消息调试和 SDK 下载尚未提供，后续按实际交付增加。</p></section>
</template>
<style scoped>.developer-example-downloads{display:grid;grid-template-columns:1fr 1fr;gap:20px}.developer-example-downloads h2{font-size:20px}.developer-example-downloads small{display:block;color:#176b57;margin-top:18px}.developer-example-guide{margin-top:28px;line-height:1.8}.developer-example-guide a{display:inline-block;color:#176b57;margin-top:16px}.developer-example-source{margin-top:20px}.developer-example-source summary{cursor:pointer;font-weight:600}pre{background:#f1f5f8;padding:20px;border-radius:8px;font-size:13px;line-height:1.8;overflow:auto}li{color:#62758b;margin:8px 0}@media(max-width:720px){.developer-example-downloads{grid-template-columns:1fr}}</style>

<style scoped>.consumer-app-entry{display:flex;align-items:center;justify-content:space-between;gap:20px;margin-bottom:24px;background:#edf2e8}.consumer-app-entry h2{font-size:19px}.consumer-app-entry p{font-size:13px;margin-top:10px;line-height:1.8}.consumer-app-entry>span{white-space:nowrap;color:#376858;font-size:13px}@media(max-width:600px){.consumer-app-entry{align-items:flex-start;flex-direction:column}}</style>
