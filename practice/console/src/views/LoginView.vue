<script setup lang="ts">
import { ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { loginDestination } from '../security/loginDestination'
import { useSessionStore } from '../stores/session'
import { errorMessage, isCancelled } from '../api/management'
import AppIcon from '../components/AppIcon.vue'
const username = ref('')
const password = ref('')
const busy = ref(false)
const error = ref('')
const session = useSessionStore()
const router = useRouter()
const route = useRoute()
async function submit() {
  if (busy.value || !username.value.trim() || !password.value) return
  busy.value = true
  error.value = ''
  try {
    await session.login(username.value, password.value)
    password.value = ''
    await router.replace(loginDestination(route.query.redirect))
  } catch (failure) {
    if (!isCancelled(failure)) error.value = errorMessage(failure)
    password.value = ''
  } finally { busy.value = false }
}
</script>
<template>
  <main class="login-page">
    <section class="login-story">
      <a class="brand login-brand" href="/login"><span class="brand-symbol">N</span><span>星云 IoT<small>NEBULA PLATFORM</small></span></a>
      <div class="login-copy"><span class="eyebrow">CONNECT. UNDERSTAND. CONTROL.</span><h1>连接万物，<br />让每一份数据<br />都有回应<span>。</span></h1><p>从设备连接到业务协同，<br />在一个租户里，管理你的物联网。</p></div>
      <div class="orbit-art" aria-hidden="true"><div class="orbit orbit-one"></div><div class="orbit orbit-two"></div><div class="orbit-core"><AppIcon name="layers" :size="42" /></div><span class="orbit-node node-a"></span><span class="orbit-node node-b"></span><span class="orbit-node node-c"></span></div>
      <p class="login-story-footer">NEBULA IoT <span>为连接而生，为实践而建。</span></p>
    </section>
    <section class="login-form-panel">
      <span class="login-top-label">星云 IoT · 管理控制台</span>
      <div class="login-form-wrap"><span class="eyebrow">WELCOME BACK</span><h2>欢迎回来</h2><p class="muted">登录账号，进入你的租户。</p>
        <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon class="form-error" role="alert" />
        <form class="login-form" @submit.prevent="submit">
          <label for="username">账号</label><el-input id="username" v-model="username" autocomplete="username" placeholder="请输入账号" size="large" :disabled="busy" maxlength="100"><template #prefix><AppIcon name="user" :size="17" /></template></el-input>
          <label for="password">密码</label><el-input id="password" v-model="password" type="password" autocomplete="current-password" placeholder="请输入密码" show-password size="large" :disabled="busy"><template #prefix><AppIcon name="shield" :size="17" /></template></el-input>
          <el-button type="primary" native-type="submit" size="large" :loading="busy" :disabled="!username.trim() || !password" class="login-submit">登录控制台<AppIcon name="arrow" :size="18" /></el-button>
        </form>
        <div class="login-note"><AppIcon name="shield" :size="17" /><span>请使用管理员为你开通的账号登录。</span></div>
        <RouterLink to="/developer">无需登录，浏览开发者文档 →</RouterLink>
      </div>
      <footer class="login-bottom">星云 IoT 实战项目 <span>© {{ new Date().getFullYear() }} Nebula</span></footer>
    </section>
  </main>
</template>
