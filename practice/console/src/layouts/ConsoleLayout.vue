<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useSessionStore } from '../stores/session'
import { errorMessage } from '../api/management'
import AppIcon from '../components/AppIcon.vue'
import { visibleMenus, menuPath } from '../security/menus'
const session = useSessionStore()
const route = useRoute()
const router = useRouter()
const menuOpen = ref(false)
const loggingOut = ref(false)
const menus = computed(() => visibleMenus(session.account))
const groups = computed(() => [...new Set(menus.value.map(menu=>menu.group))])
const currentTitle = computed(() => menus.value.find(menu=>menu.code===route.meta.permission)?.name || route.meta.title)
function navigationPath(menu: typeof menus.value[number]) { return menuPath(menu,session.account,route.path.startsWith('/catalog/tenants/') ? String(route.params.tenantId) : undefined) }
onMounted(() => { void session.refreshTenant() })
async function logout() {
  if (loggingOut.value) return
  loggingOut.value = true
  try { await session.logout(); await router.replace('/login') }
  catch (error) { ElMessage.error(errorMessage(error)) }
  finally { loggingOut.value = false }
}
function navigate(path: string) { menuOpen.value = false; void router.push(path) }
</script>
<template>
  <div class="console-shell">
    <button v-if="menuOpen" class="sidebar-backdrop" aria-label="关闭导航菜单" @click="menuOpen = false"></button>
    <aside class="sidebar" :class="{ 'is-open': menuOpen }">
      <RouterLink to="/overview" class="brand"><span class="brand-symbol">N</span><span>星云 IoT<small>NEBULA PLATFORM</small></span></RouterLink>
      <div class="sidebar-context"><span class="context-dot"></span><span>管理控制台</span><span class="version-label">v0.1</span></div>
      <nav aria-label="主导航">
        <div class="nav-caption">工作台</div>
        <button class="nav-item" :class="{ active: route.name === 'overview' }" :aria-current="route.name === 'overview' ? 'page' : undefined" @click="navigate('/overview')"><AppIcon name="grid" />控制台概览</button>
        <template v-for="group in groups" :key="group">
          <div class="nav-caption roadmap-caption">{{ group }}</div>
          <button v-for="menu in menus.filter(menu=>menu.group===group)" :key="menu.code" class="nav-item" :class="{active:route.meta.permission===menu.code}" :aria-current="route.meta.permission===menu.code ? 'page' : undefined" @click="navigate(navigationPath(menu))"><AppIcon :name="menu.path.startsWith('/catalog/') ? 'device' : menu.path.startsWith('/access/') ? 'user' : 'shield'"/>{{ menu.name }}</button>
        </template>
        <span v-for="item in [{ icon: 'workflow', label: '规则与联动' }, { icon: 'bell', label: '告警中心' }, { icon: 'shield', label: '系统管理' }]" :key="item.label" class="nav-item unavailable" aria-disabled="true"><AppIcon :name="item.icon" />{{ item.label }}<span class="nav-unavailable-dot"></span></span>
      </nav>
      <div class="sidebar-footer"><div class="sidebar-footer-icon"><AppIcon name="connection" /></div><strong>连接，正在发生。</strong><p>从一个设备开始，<br />构建你的物联网。</p><span>NEBULA / PRACTICE</span></div>
    </aside>
    <div class="main-shell">
      <header class="topbar"><div class="breadcrumb"><button class="mobile-menu" aria-label="打开导航菜单" @click="menuOpen = true"><AppIcon name="menu" /></button><span>工作台</span><span class="breadcrumb-divider">/</span><strong>{{ currentTitle }}</strong></div>
        <div class="topbar-actions"><span class="subtle-label">{{ session.account?.platformAdmin ? '平台管理' : session.tenant?.name || '所属租户' }}</span><span class="topbar-divider"></span><div class="user-avatar">{{ session.account?.username.slice(0, 1).toUpperCase() }}</div><span class="user-name">{{ session.account?.username }}</span><el-tag v-if="session.account?.platformAdmin" size="small" effect="plain">平台超管</el-tag><el-button text :loading="loggingOut" aria-label="退出登录" @click="logout"><AppIcon name="logout" :size="18" /></el-button></div>
      </header>
      <main class="page-content"><RouterView :key="route.path" /></main>
      <footer class="app-footer"><span>星云 IoT · 让连接产生价值</span><span>NEBULA PLATFORM</span></footer>
    </div>
  </div>
</template>

<style scoped>.catalog-submenu{margin-left:25px;padding-left:12px;border-left:1px solid var(--el-border-color)}.catalog-submenu .nav-item{font-size:13px;min-height:40px}</style>