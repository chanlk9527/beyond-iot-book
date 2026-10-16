<script setup lang="ts">
import { useSessionStore } from '../stores/session'
import AppIcon from '../components/AppIcon.vue'
const session=useSessionStore()
</script>
<template>
  <div class="page-heading"><div><p class="eyebrow">CONSOLE OVERVIEW</p><h1>控制台概览</h1><p>{{ session.account?.platformAdmin ? '管理平台租户、账号权限和平台级业务。' : '管理所属租户的设备与业务。' }}</p></div></div>
  <section class="welcome-banner"><div><span class="banner-label">YOUR CONNECTED WORLD</span><h2>你好，{{session.account?.username}}。</h2><p>{{session.account?.platformAdmin ? '当前身份：平台超级管理员' : `当前租户：${session.tenant?.name || '加载中'}`}}</p><RouterLink v-if="session.can('product:read')||session.can('device:read')" to="/catalog" class="banner-link">进入产品与设备<AppIcon name="arrow" :size="18"/></RouterLink></div></section>
  <div class="summary-grid"><section class="panel summary-card"><p>当前账号</p><strong class="account-metric">{{session.account?.username}}</strong><span class="metric-caption">{{session.account?.id}}</span></section><section class="panel summary-card"><p>{{session.account?.platformAdmin?'管理身份':'所属租户'}}</p><strong class="account-metric">{{session.account?.platformAdmin?'平台超级管理员':session.tenant?.name||'—'}}</strong><span class="metric-caption">{{session.account?.platformAdmin?'平台账号独立管理':session.account?.tenantId}}</span></section><section class="panel summary-card"><p>访问范围</p><strong class="scope-metric">{{session.account?.platformAdmin?'平台管理':'固定租户'}}</strong><span class="metric-caption">{{session.account?.platformAdmin?'按平台权限执行操作':'账号固定绑定一个租户，无需选择或切换'}}</span></section></div>
  <section v-if="session.tenantStatus==='error'" class="panel tenant-section"><el-alert :title="session.tenantError" type="error" :closable="false"/><el-button @click="session.refreshTenant">重新加载租户信息</el-button></section>
</template>