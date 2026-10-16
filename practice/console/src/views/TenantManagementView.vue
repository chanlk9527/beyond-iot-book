<script setup lang="ts">
import { onScopeDispose, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { tenantsApi, type Tenant, type TenantMember, type TenantAudit } from '../api/tenants'
import { errorMessage, isCancelled } from '../api/management'
import { useSessionStore } from '../stores/session'
import StatePanel from '../components/StatePanel.vue'
const route = useRoute()
const session = useSessionStore()
const id = String(route.params.tenantId)
const tenant = ref<Tenant | null>(null)
const members = ref<TenantMember[]>([])
const audits = ref<TenantAudit[]>([])
const loading = ref(true)
const error = ref('')
const actionError = ref('')
const busy = ref(false)
const memberDialog = ref(false)
const editingMember = ref<TenantMember | null>(null)
const username = ref('')
const creatingUser = ref(false)
const password = ref('')
const role = ref('VIEWER')
const memberEnabled = ref(true)
const editName = ref('')
const editEnabled = ref(true)
const lifetime = new AbortController()
let request = new AbortController()
let revision = 0
const actions: Record<string,string> = { TENANT_CREATED: '创建租户', TENANT_UPDATED: '修改租户', MEMBER_ADDED: '添加成员', MEMBER_USER_CREATED: '新建租户用户', MEMBER_UPDATED: '调整成员' }
const roles = [{ value: 'OWNER', label: '所有者' }, { value: 'ADMIN', label: '管理员' }, { value: 'VIEWER', label: '观察者' }]
async function load() {
  request.abort(); request = new AbortController(); const current = ++revision
  loading.value = true; error.value = ''; tenant.value = null; members.value = []; audits.value = []
  try {
    const [info, list, history] = await Promise.all([tenantsApi.get(id,request.signal),(session.can('tenant:member:read') ? tenantsApi.members(id,request.signal) : Promise.resolve([])),tenantsApi.audit(id,request.signal)])
    if (current !== revision) return
    tenant.value = info; editName.value = info.name; editEnabled.value = info.enabled; members.value = list; audits.value = history
  } catch (failure) { if (current === revision && !isCancelled(failure)) error.value = errorMessage(failure) }
  finally { if (current === revision) loading.value = false }
}
async function updateTenant() {
  if (!tenant.value || busy.value) return
  busy.value = true; actionError.value = ''
  try {
    await tenantsApi.update({ ...tenant.value, name: editName.value.trim(), enabled: editEnabled.value },lifetime.signal)
    ElMessage.success('租户设置已保存'); await load(); await session.refreshTenant()
  } catch (failure) { if (!isCancelled(failure)) actionError.value = errorMessage(failure) }
  finally { busy.value = false }
}
function openMember(member?: TenantMember, createUser = false) {
  creatingUser.value = createUser; password.value = ''
  editingMember.value = member ? { ...member } : null
  username.value = member?.username || ''; role.value = member?.role || 'VIEWER'; memberEnabled.value = member?.enabled ?? true
  actionError.value = ''; memberDialog.value = true
}
async function saveMember() {
  if (busy.value) return
  busy.value = true; actionError.value = ''
  try {
    if (editingMember.value) await tenantsApi.updateMember(id,{ ...editingMember.value, role: role.value, enabled: memberEnabled.value },lifetime.signal)
    else if (creatingUser.value) await tenantsApi.createUser(id,username.value,password.value,role.value,lifetime.signal)
    else await tenantsApi.addMember(id,username.value,role.value,lifetime.signal)
    password.value = ''; memberDialog.value = false; ElMessage.success('成员设置已保存'); await load(); await session.refreshTenant()
  } catch (failure) { if (!isCancelled(failure)) actionError.value = errorMessage(failure) }
  finally { busy.value = false }
}
onScopeDispose(() => { password.value = ''; revision++; request.abort(); lifetime.abort() })
void load()
</script>
<template>
  <div class="page-heading"><div><p class="eyebrow">PLATFORM / TENANT</p><h1>{{ tenant?.name || '租户详情' }}</h1><p>租户信息、成员资格与操作记录。</p></div><RouterLink to="/platform/tenants"><el-button>返回租户列表</el-button></RouterLink></div>
  <section v-if="loading" class="panel tenant-context-loading"><el-skeleton :rows="6" animated /></section>
  <section v-else-if="error" class="panel"><StatePanel title="租户加载失败" :description="error"><el-button @click="load">重新加载</el-button></StatePanel></section>
  <template v-else-if="tenant">
    <section class="panel tenant-section"><div class="section-heading"><div><h2>基本信息</h2><p class="mono">{{ tenant.id }}</p></div><el-tag :type="tenant.enabled ? 'success' : 'info'">{{ tenant.enabled ? '启用' : '停用' }}</el-tag></div>
      <form class="tenant-settings" @submit.prevent="updateTenant"><div><label for="edit-tenant-name">租户名称</label><el-input id="edit-tenant-name" v-model="editName" maxlength="100" :disabled="busy || !session.can('tenant:write')" /></div><div><label for="tenant-enabled">租户状态</label><el-switch id="tenant-enabled" v-model="editEnabled" active-text="启用" inactive-text="停用" :disabled="busy || !session.can('tenant:write')" /></div><el-button v-if="session.can('tenant:write')" type="primary" native-type="submit" :loading="busy" :disabled="!editName.trim()">保存设置</el-button></form><p class="muted small">停用后，所有成员均无法进入该租户；重新启用保留原有成员关系。不会删除业务数据。</p><el-alert v-if="actionError && !memberDialog" :title="actionError" type="error" :closable="false" class="form-error" /></section>
    <section v-if="session.can('tenant:member:read')" class="panel tenant-section"><div class="section-heading"><div><h2>租户成员 · {{ members.length }}</h2><p>角色仅在本租户内有效，不能授予平台超级管理员权限。</p></div><div><el-button v-if="session.can('tenant:member:write') && session.can('iam:user:write')" type="primary" :disabled="busy || !tenant.enabled" @click="openMember(undefined, true)">新建用户</el-button></div></div><el-table :data="members" row-key="accountId" class="member-table"><el-table-column label="账号" min-width="150"><template #default="{ row }">{{ row.username || '账号已不可用' }}</template></el-table-column><el-table-column prop="accountId" label="账号 ID" min-width="290" /><el-table-column label="租户角色" width="120"><template #default="{ row }">{{ roles.find(r => r.value === row.role)?.label || row.role }}</template></el-table-column><el-table-column label="成员状态" width="100"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" effect="plain">{{ row.enabled ? '有效' : '已停用' }}</el-tag></template></el-table-column><el-table-column label="操作" width="100"><template #default="{ row }"><el-button v-if="session.can('tenant:member:write')" link type="primary" :disabled="busy" @click="openMember(row)">调整成员</el-button></template></el-table-column></el-table><p v-if="!members.length" class="tenant-empty">尚无成员。新建用户将固定绑定当前租户。</p></section>
    <section class="panel tenant-section"><div class="section-heading"><div><h2>操作记录</h2><p>最近 50 条平台管理操作，按时间倒序展示。</p></div></div><el-table :data="audits" class="member-table"><el-table-column label="时间" min-width="180"><template #default="{ row }">{{ new Date(row.occurredAt).toLocaleString('zh-CN') }}</template></el-table-column><el-table-column label="操作" min-width="130"><template #default="{ row }">{{ actions[row.action] || row.action }}</template></el-table-column><el-table-column prop="actorId" label="操作者账号 ID" min-width="290" /><el-table-column prop="subjectId" label="目标成员 ID" min-width="290" /></el-table></section>
  </template>
  <el-dialog v-model="memberDialog" :title="editingMember ? '调整成员' : creatingUser ? '新建租户用户' : '添加已有账号'" @closed="password = ''" width="min(480px, 92vw)" :close-on-click-modal="!busy" :show-close="!busy" :close-on-press-escape="!busy"><form class="tenant-form" @submit.prevent="saveMember"><label for="member-username">账号名称</label><el-input id="member-username" v-model="username" :disabled="busy || !!editingMember" maxlength="100" :placeholder="creatingUser ? '设置登录名（全平台唯一）' : '填写已有账号的登录名'" autocomplete="off" /><template v-if="creatingUser"><label for="member-password">登录密码</label><el-input id="member-password" v-model="password" type="password" show-password autocomplete="new-password" :disabled="busy" /><p class="muted small">新账号固定绑定当前租户，不能再关联其他租户。后台功能权限需在权限管理中分配角色。</p></template><label for="member-role">租户角色</label><el-select id="member-role" v-model="role" :disabled="busy"><el-option v-for="item in roles" :key="item.value" :label="item.label" :value="item.value" /></el-select><template v-if="editingMember"><label>成员状态</label><el-switch v-model="memberEnabled" active-text="有效" inactive-text="停用" :disabled="busy" aria-label="成员状态" /><p class="muted small">停用后立即失去该租户访问资格，保留成员记录。</p></template><el-alert v-if="actionError" :title="actionError" type="error" :closable="false" /><div class="tenant-form-actions"><el-button :disabled="busy" @click="memberDialog = false">取消</el-button><el-button type="primary" native-type="submit" :loading="busy" :disabled="!username.trim() || (creatingUser && !password.trim())">{{ creatingUser ? '创建并加入租户' : '保存成员' }}</el-button></div></form></el-dialog>
</template>
