<script setup lang="ts">
import { onScopeDispose, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useSessionStore } from '../stores/session'
import { ElMessage } from 'element-plus'
import { tenantsApi, type Tenant } from '../api/tenants'
import { errorMessage, isCancelled } from '../api/management'
import StatePanel from '../components/StatePanel.vue'
const router = useRouter()
const session = useSessionStore()
const rows = ref<Tenant[]>([])
const query = ref('')
const page = ref(1)
const total = ref(0)
const loading = ref(true)
const error = ref('')
const showCreate = ref(false)
const name = ref('')
const saving = ref(false)
const saveError = ref('')
let request = new AbortController()
const lifetime = new AbortController()
let version = 0
async function load() {
  request.abort(); request = new AbortController()
  const current = ++version
  rows.value = []; loading.value = true; error.value = ''
  try {
    const data = await tenantsApi.list(query.value, page.value, request.signal)
    if (current !== version) return
    rows.value = data.items; total.value = data.total
  } catch (failure) { if (current === version && !isCancelled(failure)) error.value = errorMessage(failure) }
  finally { if (current === version) loading.value = false }
}
function search() { page.value = 1; void load() }
async function create() {
  if (!name.value.trim() || saving.value) return
  saving.value = true; saveError.value = ''
  try {
    const created = await tenantsApi.create(name.value.trim(), lifetime.signal)
    showCreate.value = false
    ElMessage.success('租户已创建，请添加成员')
    await router.push(`/platform/tenants/${encodeURIComponent(created.id)}`)
  } catch (failure) { if (!isCancelled(failure)) saveError.value = errorMessage(failure) }
  finally { saving.value = false }
}
onScopeDispose(() => { version++; request.abort(); lifetime.abort() })
void load()
</script>
<template>
  <div class="page-heading"><div><p class="eyebrow">PLATFORM / TENANTS</p><h1>租户管理</h1><p>管理租户及成员归属，保持各租户的数据边界。</p></div><el-button v-if="session.can('tenant:write')" type="primary" @click="showCreate = true; name = ''; saveError = ''">新建租户</el-button></div>
  <div class="tenant-context-info"><span class="info-icon">◎</span><div><strong>平台管理范围</strong><p>根据角色权限开放租户功能。平台功能授权不自动获得租户成员资格，租户仍按成员关系展示。</p></div></div>
  <section class="panel">
    <form class="tenant-context-toolbar" @submit.prevent="search"><h2>全部租户 <span>{{ total }}</span></h2><div class="tenant-search"><el-input v-model="query" maxlength="100" clearable placeholder="搜索租户名称或 ID" aria-label="搜索租户" /><el-button native-type="submit" :loading="loading">查询</el-button><el-button :loading="loading" @click="load">刷新</el-button></div></form>
    <div v-if="loading" class="tenant-context-loading"><el-skeleton :rows="5" animated /></div>
    <StatePanel v-else-if="error" title="租户列表加载失败" :description="error"><el-button @click="load">重试</el-button></StatePanel>
    <StatePanel v-else-if="!rows.length" title="暂无匹配的租户" description="创建租户后，为已有账号分配成员角色。无租户数据仍属于平台级数据。" />
    <div v-else class="tenant-table"><el-table :data="rows" row-key="id"><el-table-column prop="name" label="租户名称" min-width="160" /><el-table-column prop="id" label="租户 ID" min-width="290" /><el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" effect="plain">{{ row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column><el-table-column prop="memberCount" label="有效成员" width="100" /><el-table-column label="操作" width="110"><template #default="{ row }"><el-button link type="primary" @click="router.push(`/platform/tenants/${encodeURIComponent(row.id)}`)">管理租户</el-button></template></el-table-column></el-table></div>
    <div class="tenant-pagination"><el-pagination :current-page="page" :total="total" :page-size="20" layout="prev, pager, next" @current-change="(value: number) => { page = value; load() }" /></div>
  </section>
  <el-dialog v-model="showCreate" title="新建租户" width="min(480px, 92vw)" :close-on-click-modal="!saving" :show-close="!saving" :close-on-press-escape="!saving">
    <form class="tenant-form" @submit.prevent="create"><label for="tenant-name">租户名称</label><el-input id="tenant-name" v-model="name" maxlength="100" placeholder="例如：研发团队" :disabled="saving" /><p class="muted small">创建后默认启用，不会自动把平台管理员加入租户。</p><el-alert v-if="saveError" :title="saveError" type="error" :closable="false" /><div class="tenant-form-actions"><el-button :disabled="saving" @click="showCreate = false">取消</el-button><el-button type="primary" native-type="submit" :loading="saving" :disabled="!name.trim()">创建租户</el-button></div></form>
  </el-dialog>
</template>
