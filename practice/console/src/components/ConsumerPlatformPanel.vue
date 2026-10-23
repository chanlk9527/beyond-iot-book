<script setup lang="ts">
import { computed, onMounted, onScopeDispose, ref, watch } from 'vue'
import { catalogApi, resourceScope, type DeviceDetail, type DeviceShadow, type DownlinkStatus, type ShadowScalar } from '../api/catalog'
import { errorMessage, isCancelled } from '../api/management'
import { useSessionStore } from '../stores/session'
import { readPresence, useDeviceSubscription } from '../realtime/deviceSubscription'
import { snapshotRefresh } from '../realtime/snapshotRefresh'
import { initialValue, type ValueSchema } from './modelSchema'
import { validateValue } from '../simulator/protocol'
import DeviceValueInput from './DeviceValueInput.vue'
import DownlinkResult from './DownlinkResult.vue'
import Icon from './BabyAppIcon.vue'

const props = defineProps<{ tab: string }>()
const emit = defineEmits<{ connected: [name: string] }>()
type Field = { id: string; name?: string; access?: string; schema: ValueSchema; required?: boolean }
type Operation = { id: string; name?: string; inputs: Record<string, Field> }
type Change = { id: number; title: string; detail: string; time: string }
const storageKey = 'nebula-baby-platform-device-v1'
const session = useSessionStore()
const detail = ref<DeviceDetail>(), shadow = ref<DeviceShadow>(), deviceId = ref(''), boundScope = ref('')
const bindingOpen = ref(false), busy = ref(false), error = ref(''), syncError = ref(''), sendError = ref('')
const connected = ref(false), presence = ref('UNKNOWN'), changes = ref<Change[]>([]), sending = ref(false), pending = ref(false)
const subscriptionError = ref('')
const result = ref<DownlinkStatus>(), editing = ref<Field>(), operation = ref<Operation>()
const value = ref<ShadowScalar>(false), inputs = ref<Record<string, ShadowScalar>>({}), included = ref<Record<string, boolean>>({})
const query = ref(''), bindingAccount = ref('')
let controller = new AbortController(), generation = 0, logId = 0
const fields = computed(() => Object.values(detail.value?.model.model.properties || {}) as Field[])
const visibleFields = computed(() => fields.value.filter(f => [f.id, f.name].some(v => v?.toLocaleLowerCase().includes(query.value.trim().toLocaleLowerCase()))))
const operations = computed(() => Object.values(detail.value?.model.model.operations || {}) as Operation[])
const enabled = computed(() => session.authenticated && session.account?.id === bindingAccount.value && session.can('device:write') && detail.value?.device.lifecycle === 'ACTIVE' && detail.value.product.enabled)
const scope = computed(() => detail.value ? resourceScope(boundScope.value, detail.value.device) : '')
const presenceLabel = computed(() => presence.value === 'ONLINE' ? '在线' : presence.value === 'OFFLINE' ? '离线' : '状态未知')
const shortTime = (text?: string | null) => text ? new Date(text).toLocaleTimeString('zh-CN', { hour12: false, timeZone: 'Asia/Shanghai' }) : '—'
function format(field: Field, actual: ShadowScalar | undefined): string {
  if (actual === undefined) return '尚未上报'
  if (field.schema.type === 'ENUM') return field.schema.enumValues?.[String(actual)] || String(actual)
  if (typeof actual === 'boolean') return actual ? '开启' : '关闭'
  return Array.isArray(actual) ? JSON.stringify(actual) : String(actual)
}
function reset() {
  generation++; controller.abort(); controller = new AbortController(); refresh.cancel()
  detail.value = undefined; shadow.value = undefined; result.value = undefined; editing.value = undefined; operation.value = undefined
  connected.value = false; presence.value = 'UNKNOWN'; syncError.value = ''; subscriptionError.value = ''; sendError.value = ''; changes.value = []; bindingAccount.value = ''; sending.value = false; pending.value = false
  emit('connected', '')
}
async function readSnapshot() {
  if (!detail.value) return
  const current = generation, device = detail.value.device, signal = controller.signal, currentScope = scope.value
  const results = await Promise.allSettled([catalogApi.shadow(currentScope, device.id, signal), readPresence({ tenantId: device.tenantId, deviceId: device.id }, signal)])
  if (current !== generation || signal.aborted) return
  const [state, online] = results
  const failures: string[] = []
  if (state.status === 'fulfilled') {
    const next = state.value, previous = shadow.value
    if (!previous || next.version >= previous.version) {
      if (previous && next.version > previous.version) for (const field of fields.value) {
        const actual = next.reported[field.id], old = previous.reported[field.id]
        if (actual && (!old || actual.messageId !== old.messageId || JSON.stringify(actual.value) !== JSON.stringify(old.value))) {
          changes.value.unshift({ id: ++logId, title: field.name || field.id, detail: `${format(field, actual.value)}${field.schema.unit || ''} · ${field.id}`, time: shortTime(actual.receivedAt) })
        }
      }
      changes.value = changes.value.slice(0, 100); shadow.value = next
    }
  } else if (!isCancelled(state.reason)) failures.push('属性状态：' + errorMessage(state.reason))
  if (online.status === 'fulfilled') presence.value = online.value.state
  else if (!isCancelled(online.reason)) { presence.value = 'UNKNOWN'; failures.push('在线状态：' + errorMessage(online.reason)) }
  syncError.value = failures.join('；')
}
const refresh = snapshotRefresh(readSnapshot)
useDeviceSubscription(() => detail.value && session.authenticated && session.account?.id === bindingAccount.value ? [{ tenantId: detail.value.device.tenantId, deviceId: detail.value.device.id }] : [], notice => {
  if (notice.type === 'denied') { connected.value = false; subscriptionError.value = '实时订阅被拒绝，请检查登录状态与设备权限后重新连接。'; return }
  if (notice.type === 'disconnected') { connected.value = false; presence.value = 'UNKNOWN'; refresh.cancel(); return }
  if (notice.type === 'resync') { connected.value = true; subscriptionError.value = '' }
  refresh.schedule()
})
async function bind(restore = false) {
  const id = deviceId.value.trim()
  if (!id || busy.value) return
  const beforeAuth = generation
  busy.value = true; error.value = ''
  try {
    await session.ensureSession(true)
    if (beforeAuth !== generation) return
    if (!session.authenticated || !session.account) throw Error('请先登录平台账号，再绑定有权限访问的设备。')
    if (!session.can('device:read')) throw Error('当前账号没有设备读取权限。')
    if (restore && bindingAccount.value && bindingAccount.value !== session.account.id) throw Error('上次设备属于其他登录账号，请重新输入设备 ID 绑定。')
    const currentScope = session.account.platformAdmin ? '/catalog/platform' : session.account.tenantId ? `/catalog/tenants/${encodeURIComponent(session.account.tenantId)}` : ''
    if (!currentScope) throw Error('当前账号未关联租户，无法绑定设备。')
    // Load the exact registered device and its bound model version; never use the product draft.
    const accountId = session.account.id
    reset(); const current = generation
    const next = await catalogApi.detail(currentScope, id, controller.signal)
    if (current !== generation || !session.authenticated || session.account?.id !== accountId) return
    bindingAccount.value = accountId; boundScope.value = currentScope; detail.value = next; deviceId.value = next.device.id; query.value = ''
    try { localStorage.setItem(storageKey, JSON.stringify({ deviceId: next.device.id, accountId })) } catch { /* Binding works without persistence. */ }
    emit('connected', next.device.name); bindingOpen.value = false
    refresh.schedule()
  } catch (failure) { if (!isCancelled(failure)) error.value = errorMessage(failure) }
  finally { busy.value = false }
}
function unbind() { reset(); error.value = ''; try { localStorage.removeItem(storageKey) } catch { /* No persistent storage. */ } }
function openBinding() { bindingOpen.value = true }
defineExpose({ openBinding })
function edit(field: Field) {
  editing.value = field; operation.value = undefined; sendError.value = ''
  value.value = shadow.value?.reported[field.id]?.value ?? initialValue(field.schema)
}
function call(item: Operation) {
  editing.value = undefined; operation.value = item; sendError.value = ''; inputs.value = {}; included.value = {}
  for (const f of Object.values(item.inputs)) { inputs.value[f.id] = initialValue(f.schema); included.value[f.id] = !!f.required }
}
async function send() {
  if (!detail.value || !enabled.value || sending.value || pending.value) return
  const current = generation, signal = controller.signal, device = detail.value.device
  sendError.value = ''
  const properties: Record<string, ShadowScalar> = {}
  if (editing.value) {
    if (!validateValue(editing.value.schema, value.value)) { sendError.value = '属性值不符合当前物模型约束。'; return }
    properties[editing.value.id] = value.value
  } else if (operation.value) {
    for (const f of Object.values(operation.value.inputs)) {
      if (!f.required && !included.value[f.id]) continue
      if (!validateValue(f.schema, inputs.value[f.id])) { sendError.value = `${f.name || f.id} 不符合物模型约束。`; return }
      properties[f.id] = inputs.value[f.id]!
    }
  } else return
  sending.value = true; result.value = undefined
  try {
    const response = editing.value
      ? await catalogApi.writeProperties(scope.value, device.id, properties, signal)
      : await catalogApi.invoke(scope.value, device.id, operation.value!.id, properties, signal)
    if (current !== generation || signal.aborted) return
    result.value = response; editing.value = undefined; operation.value = undefined
    // A dispatch receipt is not a reported value. Only a later device snapshot updates the UI.
    refresh.schedule()
  } catch (failure) { if (current === generation && !isCancelled(failure)) sendError.value = errorMessage(failure) }
  finally { if (current === generation) sending.value = false }
}
watch(() => [session.authenticated, session.account?.id], () => { if (detail.value && (!session.authenticated || session.account?.id !== bindingAccount.value)) { reset(); error.value = '登录状态已变化，请重新登录并绑定设备。' } })
onMounted(() => {
  try {
    const saved = JSON.parse(localStorage.getItem(storageKey) || 'null')
    if (typeof saved?.deviceId === 'string' && typeof saved?.accountId === 'string') {
      deviceId.value = saved.deviceId; bindingAccount.value = saved.accountId; void bind(true)
    }
  } catch { /* Ignore malformed local preference. */ }
})
onScopeDispose(() => { controller.abort(); generation++; refresh.dispose() })
</script>

<template>
  <section class="platform-baby">
    <div v-if="!detail" class="platform-empty"><span><Icon name="camera" :size="40"/></span><h3>连接你的婴儿监视器</h3><p>绑定平台上已登记的 BBM 设备 ID，<br>实时查看 MQTT 上报，并向设备下发控制。</p><button class="platform-primary" @click="openBinding">绑定平台设备</button><small>使用平台当前账号与设备权限</small></div>
    <p v-if="error" role="alert" class="platform-error">{{ error }}</p>
    <template v-if="detail">
      <div class="platform-identity"><span class="platform-camera"><Icon name="camera" :size="25"/></span><div><h3>{{ detail.device.name }}</h3><p>{{ detail.product.name }} · 模型 {{ detail.model.version }}</p></div><span :class="{ offline: presence !== 'ONLINE' }" class="platform-presence">{{ presenceLabel }}</span></div>
      <div class="platform-connection" role="status"><span :class="{ live: connected }">● {{ connected ? '实时订阅已连接' : '实时订阅未就绪 · 显示最近快照' }}</span><button :disabled="busy" @click="bind()">重新连接</button></div>
      <p v-if="subscriptionError" class="platform-error" role="alert">{{ subscriptionError }}</p>
      <p v-if="syncError" class="platform-error" role="alert">{{ syncError }} <button @click="refresh.schedule()">重试读取</button></p>
      <template v-if="tab === 'home' || tab === 'monitor'">
        <div v-if="tab === 'monitor'" class="platform-video-placeholder"><Icon name="camera" :size="35"/><strong>设备数据已接入</strong><span>视频流尚未在此页面连接</span></div>
        <div class="platform-section-title"><h3>设备实时属性 <small>{{ fields.length }}</small></h3><button @click="refresh.schedule()">刷新</button></div>
        <p class="platform-help">{{ shadow ? `上报快照 #${shadow.version} · ${shortTime(shadow.updatedAt)}` : '正在读取设备影子…' }}<br>值由设备上报更新，下发成功后不会提前改写。</p>
        <input v-if="fields.length > 6" v-model="query" class="platform-search" placeholder="搜索属性名称或标识" aria-label="搜索设备属性"/>
        <div class="platform-properties"><article v-for="field in visibleFields" :key="field.id" :data-property="field.id"><div class="property-title"><strong>{{ field.name || field.id }}</strong><span>{{ field.access === 'READ_ONLY' ? '只读' : field.access === 'WRITE_ONLY' ? '只写' : '读写' }}</span></div><code>{{ field.id }}</code><div class="property-actual"><strong>{{ field.access === 'WRITE_ONLY' ? '仅支持下发' : format(field, shadow?.reported[field.id]?.value) }}</strong><small v-if="shadow?.reported[field.id]">{{ field.schema.unit }}</small></div><div class="property-footer"><small>{{ shortTime(shadow?.reported[field.id]?.receivedAt) }}</small><button v-if="field.access !== 'READ_ONLY'" :disabled="!enabled || sending || pending" :aria-label="'设置 ' + field.id" @click="edit(field)">设置</button></div></article></div>
        <p v-if="!visibleFields.length" class="platform-help">当前模型没有匹配的属性。</p>
        <template v-if="operations.length"><div class="platform-section-title"><h3>设备功能</h3><small>{{ operations.length }} 项</small></div><button v-for="item in operations" :key="item.id" class="platform-operation" :disabled="!enabled || sending || pending" @click="call(item)"><span><strong>{{ item.name || item.id }}</strong><code>{{ item.id }}</code></span><Icon name="chevron" :size="17"/></button></template>
        <p v-if="!enabled" class="platform-help">下发需要 device:write 权限，且产品已启用、设备为 ACTIVE。</p>
        <p v-if="sendError && !editing && !operation" role="alert" class="platform-error">{{ sendError }}</p>
        <DownlinkResult v-if="result" :key="detail.device.id + result.messageId" :scope="scope" :device-id="detail.device.id" :initial="result" @pending="pending = $event"/>
      </template>
      <template v-else-if="tab === 'events'"><div class="platform-section-title"><h3>本次连接的属性上报</h3><small>{{ changes.length }} 条</small></div><p class="platform-help">实时记录设备影子中的上报变化，保留最近 100 条。这不是告警或事件历史，断线期间的变化以重连快照为准。</p><article v-for="change in changes" :key="change.id" class="platform-change"><span><Icon name="bell" :size="18"/></span><div><strong>{{ change.title }}</strong><p>{{ change.detail }}</p></div><time>{{ change.time }}</time></article><p v-if="!changes.length" class="platform-help">等待 MQTT 模拟设备上报属性…</p></template>
      <template v-else><div class="platform-section-title"><h3>当前平台关联</h3></div><dl class="platform-details"><dt>登录账号</dt><dd>{{ session.account?.username }}</dd><dt>设备 ID</dt><dd>{{ detail.device.id }}</dd><dt>设备编码</dt><dd>{{ detail.device.code }}</dd><dt>产品编码</dt><dd>{{ detail.product.code }}</dd><dt>租户</dt><dd>{{ detail.device.tenantId || '平台目录' }}</dd><dt>模型版本</dt><dd>{{ detail.model.version }}</dd></dl><p class="platform-help">此处绑定是浏览器中选择一个已授权的平台设备，不修改设备归属。正式家庭绑定后续另接 C 端身份。</p><button class="platform-primary" @click="openBinding">更换绑定设备</button><button class="platform-unbind" @click="unbind">解除本机绑定</button></template>
    </template>
    <el-dialog v-model="bindingOpen" title="绑定平台设备" width="min(400px, calc(100vw - 32px))" :close-on-click-modal="!busy">
      <form class="platform-form" @submit.prevent="bind()"><p>输入平台设备详情中的设备 ID。系统会加载该设备当前绑定的物模型版本。</p><label for="baby-platform-id">设备 ID</label><input id="baby-platform-id" v-model="deviceId" required maxlength="160" placeholder="粘贴 BBM 设备 ID" :disabled="busy"/><p v-if="error" class="platform-error" role="alert">{{ error }}</p><RouterLink v-if="!session.authenticated" class="platform-login" to="/login?redirect=/app">登录平台账号 →</RouterLink><button class="platform-primary" :disabled="busy || !deviceId.trim()" type="submit">{{ busy ? '正在读取平台设备…' : '绑定并接收实时状态' }}</button><small>无需输入 MQTT 密钥。关联仅保存在当前浏览器，后端仍校验账号与租户权限。</small></form>
    </el-dialog>
    <el-dialog :model-value="!!editing || !!operation" :title="editing ? '设置 ' + (editing.name || editing.id) : '调用 ' + (operation?.name || operation?.id)" width="min(400px, calc(100vw - 32px))" :close-on-click-modal="!sending" :show-close="!sending" :close-on-press-escape="!sending" @update:model-value="(open: boolean) => { if (!open && !sending) { editing = undefined; operation = undefined } }">
      <form class="platform-form" @submit.prevent="send"><template v-if="editing"><label>{{ editing.id }}</label><DeviceValueInput v-model="value" :schema="editing.schema" :disabled="sending || !enabled || pending" :label="editing.id + ' 写入值'"/></template><template v-else-if="operation"><div v-for="field in operation.inputs" :key="field.id" class="operation-field"><label><input v-if="!field.required" v-model="included[field.id]" type="checkbox" :disabled="sending"/>{{ field.name || field.id }} · {{ field.required ? '必填' : '选填' }}</label><DeviceValueInput v-model="inputs[field.id]" :schema="field.schema" :disabled="sending || (!field.required && !included[field.id]) || !enabled || pending" :label="field.id + ' 参数'"/></div><p v-if="!Object.keys(operation.inputs).length">此操作无需参数。</p></template><p v-if="sendError" role="alert" class="platform-error">{{ sendError }}</p><button class="platform-primary" type="submit" :disabled="sending || !enabled || pending">{{ sending ? '正在下发…' : '通过平台下发到设备' }}</button><small>下发使用设备物模型定义，平台通过 MQTT 发送；执行状态以设备回执为准。</small></form>
    </el-dialog>
  </section>
</template>

<style scoped>
.platform-baby{color:#3c5342;font-size:12px}.platform-empty{text-align:center;padding:48px 10px 24px}.platform-empty>span{display:grid;place-items:center;width:85px;height:85px;background:#edf1e4;color:#7d956b;border-radius:28px;margin:0 auto 25px}.platform-empty h3{font-size:18px;font-weight:500}.platform-empty p{font-size:12px;color:#899880;line-height:2;margin:18px 0 26px}.platform-primary{width:100%;padding:13px 14px;background:#42674b;color:#fbfcf5;border:0;border-radius:11px;font-size:12px}.platform-primary:disabled{opacity:.5}.platform-empty>small{display:block;color:#a0ab95;margin:16px 0;font-size:10px}.platform-error{color:#a55d47;background:#faf0e9;border:1px solid #eedbd0;border-radius:10px;padding:12px;font-size:11px;line-height:1.8;overflow-wrap:anywhere;margin:10px 0}.platform-error button{border:0;background:none;color:inherit;text-decoration:underline}.platform-identity{display:flex;gap:11px;align-items:center;border-radius:15px;background:#edf1e4;padding:16px 12px}.platform-camera{color:#789466}.platform-identity h3{font-size:14px;font-weight:500}.platform-identity p{font-size:10px;color:#8e9f80;margin-top:6px}.platform-presence{margin-left:auto;font-size:10px;color:#50824b;white-space:nowrap}.platform-presence.offline{color:#a4967d}.platform-connection{display:flex;align-items:center;justify-content:space-between;font-size:9px;color:#a49c80;padding:13px 0}.platform-connection .live{color:#729268}.platform-connection button,.platform-section-title button{font-size:10px;border:0;background:none;color:#698363;padding:3px}.platform-section-title{display:flex;justify-content:space-between;align-items:center;margin:20px 0 11px}.platform-section-title h3{font-size:13px;font-weight:500}.platform-section-title small{font-size:10px;color:#98a48c;margin-left:5px}.platform-help{font-size:10px;line-height:1.9;color:#98a28e;margin:10px 0 16px}.platform-search{width:100%;border:1px solid #dde6d3;border-radius:9px;padding:9px 11px;background:#fff;color:#5e7552;font-size:11px;margin:0 0 12px}.platform-properties{display:grid;grid-template-columns:1fr 1fr;gap:10px}.platform-properties article{padding:13px 11px 10px;background:white;border:1px solid #e6ecdd;border-radius:13px;min-width:0}.property-title{display:flex;justify-content:space-between;align-items:flex-start;gap:4px}.property-title strong{font-size:11px;font-weight:500;overflow-wrap:anywhere}.property-title>span{font-size:8px;color:#9eab90;white-space:nowrap;margin-top:1px}.platform-baby code{display:block;font-size:9px;color:#9ba88d;overflow-wrap:anywhere;margin-top:5px}.property-actual{margin:15px 0;overflow-wrap:anywhere;line-height:1.5}.property-actual strong{font-size:18px;font-weight:500}.property-actual small{font-size:10px;color:#849a72;margin-left:4px}.property-footer{display:flex;justify-content:space-between;align-items:center;min-height:23px}.property-footer small{font-size:8px;color:#a0ac94}.property-footer button{border:0;background:#edf2e6;color:#668354;border-radius:5px;padding:4px 9px;font-size:9px}.property-footer button:disabled{opacity:.4}.platform-operation{display:flex;align-items:center;justify-content:space-between;width:100%;background:#f1f3e9;border:1px solid #e4e9d8;border-radius:11px;padding:13px;margin-top:10px;text-align:left;color:#6f8760}.platform-operation strong{font-size:12px;font-weight:500}.platform-operation:disabled{opacity:.5}.platform-video-placeholder{height:142px;border-radius:15px;background:#e8ece1;color:#91a080;display:flex;align-items:center;justify-content:center;flex-direction:column;gap:10px;margin:9px 0}.platform-video-placeholder strong{font-size:12px;font-weight:500}.platform-video-placeholder span{font-size:10px}.platform-change{display:flex;align-items:center;gap:10px;padding:13px 0;border-bottom:1px solid #ebefe3}.platform-change>span{display:grid;place-items:center;width:32px;height:32px;background:#edf1e4;border-radius:11px;color:#879c75;flex-shrink:0}.platform-change>div{min-width:0}.platform-change strong{font-size:11px;font-weight:500}.platform-change p{font-size:9px;color:#98a58c;overflow-wrap:anywhere;line-height:1.8;margin-top:4px}.platform-change time{margin-left:auto;font-size:9px;color:#a1ac96;white-space:nowrap}.platform-details{display:grid;grid-template-columns:72px minmax(0,1fr);gap:15px 10px;background:white;border:1px solid #e6ecdd;border-radius:13px;padding:18px 13px;font-size:11px}.platform-details dt{color:#97a38c}.platform-details dd{margin:0;overflow-wrap:anywhere}.platform-unbind{width:100%;background:none;border:0;color:#a48b76;padding:16px;font-size:11px}.platform-form{display:flex;flex-direction:column;gap:15px;font-size:12px;color:#607454;line-height:1.8}.platform-form>p,.platform-form>small{font-size:11px;color:#99a48e}.platform-form>input{width:100%;min-width:0;border:1px solid #dce5d3;border-radius:8px;padding:11px;font-size:12px;background:#fafbf6;color:#4b6341}.platform-form .platform-error{color:#a55d47;margin:0}.platform-login{color:#517c4b;text-decoration:underline}.operation-field{display:grid;gap:8px}.operation-field label{display:flex;gap:7px;align-items:center}.platform-baby :deep(.downlink-result){padding:11px;font-size:10px}.platform-baby :deep(.downlink-result p){font-size:10px}
</style>
