<script setup lang="ts">
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { useRouter, useRoute } from 'vue-router'
import { useSessionStore } from './stores/session'
import StatePanel from './components/StatePanel.vue'
const session = useSessionStore()
const router = useRouter()
const route = useRoute()
async function retry() {
  await session.ensureSession(true)
  if (session.status !== 'error') await router.replace(session.authenticated ? '/overview' : '/login')
}
</script>
<template>
  <el-config-provider :locale="zhCn">
  <RouterView v-if="route.meta.public" />
  <main v-else-if="session.status === 'error'" class="full-state">
    <StatePanel title="暂时无法连接控制台" :description="session.error" icon="connection">
      <el-button type="primary" @click="retry">重新连接</el-button>
    </StatePanel>
  </main>
  <main v-else-if="session.status === 'idle' || session.status === 'loading'" class="full-state" aria-live="polite">
    <div class="boot-brand"><span class="brand-symbol">N</span><p>正在连接星云 IoT…</p></div>
  </main>
  <RouterView v-else />
  </el-config-provider>
</template>
