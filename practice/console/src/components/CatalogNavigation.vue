<script setup lang="ts">
import { computed } from 'vue'
import { useRoute,useRouter } from 'vue-router'
import { useSessionStore } from '../stores/session'
import { visibleMenus, menuPath } from '../security/menus'
const session=useSessionStore(),route=useRoute(),router=useRouter()
const menus=computed(()=>visibleMenus(session.account).filter(menu=>menu.path.startsWith('/catalog/')))
const label=computed(()=>String(route.name).includes('categories') ? '平台共享 · 所有租户共用' : route.meta.catalogPlatform ? '全部租户及无租户数据' : (session.tenant?.id===route.params.tenantId ? session.tenant.name : undefined)||'租户范围')
</script>
<template><div class="catalog-navigation"><div><span class="subtle-label">当前范围</span><strong>{{ label }}</strong></div><div class="access-tabs"><el-button v-for="menu in menus" :key="menu.code" :type="route.meta.permission===menu.code?'primary':'default'" @click="router.push(menuPath(menu,session.account,route.meta.catalogPlatform===true ? undefined : String(route.params.tenantId)))">{{ menu.name }}</el-button></div></div></template>
