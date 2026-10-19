<script setup lang="ts">
import { computed, onScopeDispose, ref } from 'vue'
import { useSessionStore } from '../stores/session'
import { developerApi, scopeOptions, type DeveloperApp, type AppAudit } from '../api/developer'
import { errorMessage, isCancelled } from '../api/management'
import { ElMessage, ElMessageBox } from 'element-plus'
import StatePanel from '../components/StatePanel.vue'
const session = useSessionStore()
const platform = computed(() => !!session.account?.platformAdmin)
const rows = ref<DeveloperApp[]>([]), loading = ref(false), busy = ref(false), error = ref('')
const dialog = ref(false), selected = ref<DeveloperApp|null>(null), name = ref(''), scopes = ref<string[]>([]), note = ref('')
const dialogError = ref(''), secret = ref(''), secretVisible = ref(false), detail = ref<DeveloperApp|null>(null), history = ref<AppAudit[]>([])
const lifetime = new AbortController()
const statusLabel = (app: DeveloperApp) => app.reviewStatus === 'PENDING' ? '待审核' : app.reviewStatus === 'REJECTED' ? '已驳回' : app.enabled ? '已启用' : '已停用'
const scopeLabel = (value: string) => value.split(' ').filter(Boolean).map(s => scopeOptions.find(o => o.value === s)?.label || s).join('、') || '尚未授予'
async function load() {
  loading.value = true; error.value = ''
  try { rows.value = platform.value ? await developerApi.reviews(lifetime.signal) : await developerApi.list(session.account?.tenantId || '', lifetime.signal) }
  catch (e) { if (!isCancelled(e)) error.value = errorMessage(e) }
  finally { loading.value = false }
}
function open(app: DeveloperApp|null) {
  selected.value = app; name.value = ''; scopes.value = app?.requestedScopes.split(' ').filter(Boolean) || []
  note.value = ''; dialogError.value = ''; dialog.value = true
}
async function save(approved = true) {
  if (busy.value) return
  busy.value = true; dialogError.value = ''
  try {
    if (platform.value && selected.value) await developerApi.review(selected.value, approved, scopes.value, note.value, lifetime.signal)
    else if (selected.value) await developerApi.submit(selected.value, scopes.value, lifetime.signal)
    else await developerApi.create(session.account?.tenantId || '', name.value.trim(), scopes.value, lifetime.signal)
    dialog.value = false; ElMessage.success(platform.value ? '审核结果已保存' : '申请已提交'); await load()
  } catch (e) { if (!isCancelled(e)) dialogError.value = errorMessage(e) }
  finally { busy.value = false }
}
async function rotate(app: DeveloperApp) {
  try { await ElMessageBox.confirm('将生成新密钥并使已有令牌失效。新密钥只展示一次，请立即保存。', '获取或轮换凭证', { type: 'warning' }) }
  catch { return }
  busy.value = true
  try { const result = await developerApi.secret(app, lifetime.signal); secret.value = result.clientSecret; secretVisible.value = true; await load() }
  catch(e) { if (!isCancelled(e)) ElMessage.error(errorMessage(e)) }
  finally { busy.value = false }
}
async function toggle(app: DeveloperApp) {
  try { await ElMessageBox.confirm(app.enabled ? '停用后，已有令牌在下一次请求时失效。' : '重新启用不会恢复旧令牌。', app.enabled ? '停用应用' : '启用应用') }
  catch { return }
  busy.value = true
  try { await developerApi.status(app, lifetime.signal); await load() }
  catch(e) { if (!isCancelled(e)) ElMessage.error(errorMessage(e)) }
  finally { busy.value = false }
}
async function showDetail(app: DeveloperApp) {
  history.value = []; detail.value = app
  {
    try { const records = await (platform.value ? developerApi.platformAudit(app, lifetime.signal) : developerApi.audit(app, lifetime.signal)); if(detail.value?.appId === app.appId)history.value = records }
    catch(e) { if (!isCancelled(e)) ElMessage.error(errorMessage(e)) }
  }
}
onScopeDispose(() => { lifetime.abort(); secret.value = '' })
void load()
</script>
<template>
  <div class="page-heading"><div><p class="eyebrow">OPEN PLATFORM</p><h1>{{ platform ? '开放平台管理' : '我的应用' }}</h1><p>{{ platform ? '审核租户应用的能力申请，管理应用启停。' : '创建应用、申请能力并管理接入凭证。' }}</p></div><el-button v-if="!platform" type="primary" :disabled="busy" @click="open(null)">创建应用</el-button></div>
  <el-alert title="能力审批决定应用可以调用的接口类型；具体设备的访问权限还需要资源授权。" type="info" :closable="false" show-icon />
  <section class="panel developer-panel">
    <div class="tenant-context-toolbar"><h2>应用列表</h2><el-button :disabled="busy" :loading="loading" @click="load">刷新</el-button></div>
    <el-skeleton v-if="loading" :rows="4" animated />
    <StatePanel v-else-if="error" title="应用加载失败" :description="error"><el-button @click="load">重试</el-button></StatePanel>
    <StatePanel v-else-if="!rows.length" title="暂无应用" :description="platform ? '租户提交申请后会显示在这里。' : '创建应用并选择需要的能力，提交后等待平台审核。'" />
    <el-table v-else :data="rows" row-key="appId">
      <el-table-column prop="name" label="应用" min-width="150" />
      <el-table-column v-if="platform" prop="tenantName" label="所属租户" min-width="140" />
      <el-table-column label="状态" width="110"><template #default="{row}"><el-tag :type="row.reviewStatus === 'APPROVED' && row.enabled ? 'success' : row.reviewStatus === 'REJECTED' ? 'danger' : 'info'">{{ statusLabel(row) }}</el-tag></template></el-table-column>
      <el-table-column label="已授予能力" min-width="180"><template #default="{row}">{{ scopeLabel(row.scopes) }}</template></el-table-column>
      <el-table-column label="操作" min-width="260"><template #default="{row}">
        <el-button link @click="showDetail(row)">详情</el-button>
        <template v-if="platform"><el-button v-if="row.reviewStatus === 'PENDING'" link type="primary" :disabled="busy" @click="open(row)">审核</el-button><el-button v-if="row.reviewStatus === 'APPROVED'" link :disabled="busy" @click="toggle(row)">{{ row.enabled ? '停用' : '启用' }}</el-button></template>
        <template v-else><el-button v-if="row.reviewStatus !== 'PENDING'" link :disabled="busy" @click="open(row)">重新申请</el-button><el-button v-if="row.reviewStatus === 'APPROVED' && row.enabled" link type="primary" :disabled="busy" @click="rotate(row)">获取 / 轮换凭证</el-button></template>
      </template></el-table-column>
    </el-table><p class="muted small">显示最近 100 个应用。</p>
  </section>
  <el-dialog v-model="dialog" :title="platform ? '审核能力申请' : selected ? '重新申请能力' : '创建应用'" width="min(560px, 94vw)" :close-on-click-modal="!busy" :show-close="!busy" :close-on-press-escape="!busy">
    <el-form label-position="top" :disabled="busy" @submit.prevent>
      <el-form-item v-if="!selected" label="应用名称"><el-input v-model="name" maxlength="100" /></el-form-item>
      <p v-else>{{ selected.name }} · {{ selected.tenantName }}</p>
      <el-form-item :label="platform ? '批准的能力（可缩小范围）' : '申请能力'"><el-checkbox-group v-model="scopes"><el-checkbox v-for="option in scopeOptions.filter(o => !platform || selected?.requestedScopes.split(' ').includes(o.value))" :key="option.value" :value="option.value">{{ option.label }}</el-checkbox></el-checkbox-group></el-form-item>
      <el-form-item v-if="platform" label="审核说明（驳回时必填）"><el-input v-model="note" type="textarea" maxlength="800" /></el-form-item>
      <el-alert v-if="selected && !platform" title="重新申请后应用暂停使用，旧令牌失效，审核通过后需重新换取令牌。" type="warning" :closable="false" />
      <el-alert v-if="dialogError" :title="dialogError" type="error" :closable="false" />
    </el-form>
    <template #footer><el-button :disabled="busy" @click="dialog = false">取消</el-button><el-button v-if="platform" type="danger" :disabled="busy || !note.trim()" @click="save(false)">驳回</el-button><el-button type="primary" :loading="busy" :disabled="!scopes.length || (!selected && !name.trim())" @click="save(true)">{{ platform ? '批准' : '提交申请' }}</el-button></template>
  </el-dialog>
  <el-dialog v-model="secretVisible" title="应用密钥 · 仅展示一次" width="min(600px, 94vw)" @closed="secret = ''"><el-alert title="请保存到服务端密钥配置，不要放入 App、网页或代码仓库。关闭后无法再次查看。" type="warning" :closable="false" /><pre class="secret-text">{{ secret }}</pre></el-dialog>
  <el-dialog :model-value="!!detail" title="应用详情与接入说明" width="min(720px, 94vw)" @update:model-value="detail = null">
    <template v-if="detail"><el-descriptions :column="1" border><el-descriptions-item label="App ID">{{ detail.appId }}</el-descriptions-item><el-descriptions-item label="租户">{{ detail.tenantName }}</el-descriptions-item><el-descriptions-item label="申请能力">{{ scopeLabel(detail.requestedScopes) }}</el-descriptions-item><el-descriptions-item label="审核说明">{{ detail.reviewNote || '暂无' }}</el-descriptions-item></el-descriptions>
      <h3>服务端接入</h3><p>向 <code>POST /api/open/v1/oauth/token</code> 提交以下 JSON，申请的 scopes 需要在已批准范围内。</p><pre>{{ JSON.stringify({ appId: detail.appId, clientSecret: '你的应用密钥', scopes: detail.scopes.split(' ').filter(Boolean) }, null, 2) }}</pre><p>使用 <code>Authorization: Bearer &lt;accessToken&gt;</code> 请求 <code>GET /api/open/v1/me</code> 验证身份。令牌有效期为 30 分钟。</p><p>设备绑定和用户代调用仍在后续阶段建设；获批能力不表示对应业务接口已经全部开放。</p>
      <h3>操作记录</h3><el-table :data="history"><el-table-column prop="action" label="操作" /><el-table-column prop="actor" label="操作者" /><el-table-column prop="detail" label="说明" /><el-table-column prop="occurredAt" label="时间" min-width="180" /></el-table>
    </template>
  </el-dialog>
</template>
<style scoped>.developer-panel{margin-top:20px;padding:20px}pre{white-space:pre-wrap;overflow-wrap:anywhere;background:var(--el-fill-color-light);padding:16px;border-radius:8px}.secret-text{user-select:all}h3{margin-top:24px}</style>
