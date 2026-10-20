<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useSessionStore } from '../stores/session'
import { errorMessage } from '../api/management'
const session = useSessionStore(), router = useRouter()
const error = ref(''), busy = ref(false)
async function logout() {
  busy.value = true; error.value = ''
  try { await session.logout(); await router.replace('/developer') }
  catch(e) { error.value = errorMessage(e) }
  finally { busy.value = false }
}
</script>
<template>
  <div class="developer-portal">
    <header class="developer-header">
      <RouterLink to="/developer" class="developer-brand"><span class="brand-symbol">N</span><span>星云 IoT <small>开发者中心</small></span></RouterLink>
      <nav aria-label="开发者导航"><RouterLink to="/developer" exact-active-class="active">首页</RouterLink><RouterLink to="/developer/docs" active-class="active">文档</RouterLink><RouterLink to="/developer/examples" active-class="active">示例与工具</RouterLink><RouterLink to="/developer/apps" active-class="active">我的应用</RouterLink></nav>
      <div class="developer-account"><RouterLink to="/overview">管理控制台 ↗</RouterLink><template v-if="session.authenticated"><span>{{ session.account?.username }}</span><button type="button" :disabled="busy" @click="logout">退出</button></template><RouterLink v-else :to="{name:'login',query:{redirect:'/developer/apps'}}">登录</RouterLink></div>
    </header>
    <p v-if="error" role="alert">{{ error }}</p>
    <main class="developer-main"><RouterView :key="$route.path" /></main>
    <footer class="developer-footer"><span>星云 IoT · 开发者中心</span><RouterLink to="/developer/docs/changelog">更新记录</RouterLink><RouterLink to="/developer/docs/faq">常见问题</RouterLink></footer>
  </div>
</template>
<style>
.developer-portal{min-height:100vh;background:#f5f5f7;color:#1d1d1f}.developer-header{display:flex;align-items:center;gap:34px;min-height:82px;padding:14px max(24px,calc((100vw - 1260px)/2));background:white;border-bottom:1px solid #e5e5ea;flex-wrap:wrap}.developer-brand{display:flex;align-items:center;gap:10px;text-decoration:none;color:#1d1d1f;font-size:19px;font-weight:600;white-space:nowrap}.developer-brand small{display:block;font-size:12px;color:#6e6e73;margin-top:3px}.developer-header nav{display:flex;gap:24px}.developer-header nav a,.developer-account a{color:#515154;text-decoration:none;font-size:14px}.developer-header nav a.active{color:#0071e3;font-weight:700}.developer-account{margin-left:auto;display:flex;align-items:center;gap:16px;font-size:13px}.developer-account button{border:0;background:none;color:#515154;cursor:pointer}.developer-main{max-width:1260px;padding:38px 24px 70px;margin:auto;min-height:calc(100vh - 170px)}.developer-footer{max-width:1260px;margin:auto;display:flex;gap:24px;flex-wrap:wrap;padding:24px;border-top:1px solid #e5e5ea;color:#6e6e73;font-size:13px}.developer-footer span{margin-right:auto}.developer-footer a{color:inherit}.developer-portal a:focus-visible,.developer-portal button:focus-visible{outline:2px solid #0071e3;outline-offset:4px}.developer-card{display:block;background:white;border:1px solid #e5e5ea;border-radius:14px;padding:24px;color:inherit;text-decoration:none}.developer-card h2,.developer-card h3{margin:0 0 10px}.developer-card p{line-height:1.7;color:#6e6e73;margin:0}.developer-portal .developer-button{display:inline-block;background:#0071e3;color:white;text-decoration:none;padding:12px 20px;border-radius:8px;font-weight:600}.developer-portal .developer-button.secondary{background:white;color:#0071e3;border:1px solid #d6e5f8}@media(max-width:900px){.developer-header{gap:18px;padding:18px 24px}.developer-header nav{order:3;width:100%;overflow-x:auto;white-space:nowrap;padding:7px 0}.developer-account{gap:12px}.developer-main{padding-top:24px}}@media(prefers-reduced-motion:reduce){.developer-portal *{scroll-behavior:auto!important}}
</style>
