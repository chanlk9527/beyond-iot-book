<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import { ElAlert, ElButton, ElDrawer, ElInput, ElMessage, ElMessageBox, ElRadioButton, ElRadioGroup, ElSkeleton, ElTable, ElTableColumn, ElTag } from 'element-plus'
import type { ModelSpec } from '../api/catalog'
import DataRuleForm from './DataRuleForm.vue'
import { historyApi, type HistoryCapabilities } from '../api/history'
import { errorMessage, isCancelled } from '../api/management'
import { copyPolicy, defaultDataPolicy, defaultDataRule, parsePolicy, ruleSummary, eventRuleSummary, validateEventRule, validateRule, type DataRule } from './dataPolicy'

const props = defineProps<{ model: ModelSpec; disabled?: boolean; scope: string; productId: string }>()
const emit = defineEmits<{ dirty: [value: boolean]; busy: [value: boolean] }>()
const capabilities = ref<HistoryCapabilities>()
const revision = ref(0), loadingPolicy = ref(false), persisting = ref(false), loaded = ref(false)
const locked = computed(() => !!props.disabled || loadingPolicy.value || persisting.value || !loaded.value)
const life = new AbortController(); let generation = 0
const policy = ref(defaultDataPolicy())
const saved = ref(JSON.stringify(policy.value))
const savedAt = ref('')
const storageError = ref('')
const search = ref('')
const filter = ref('all')
const editor = ref<string | null>(null)
const editorKind = ref<'properties' | 'events'>('properties')
const editing = ref<DataRule>(defaultDataRule())
const dirty = computed(() => JSON.stringify(policy.value) !== saved.value)
const valid = computed(() => validateRule(policy.value.defaults) && Object.values(policy.value.overrides).every(validateRule)
  && validateEventRule(policy.value.eventDefaults) && Object.values(policy.value.eventOverrides).every(validateEventRule))
const properties = computed(() => Object.entries(props.model.properties).map(([id, raw]) => {
  const spec = raw as { name?: string; schema?: { type?: string } }
  const type = spec?.schema?.type || 'UNKNOWN'
  return { id, name: spec?.name || id, type, numeric: ['INTEGER', 'DECIMAL'].includes(type), custom: Object.hasOwn(policy.value.overrides, id) }
}))
const rows = computed(() => properties.value.filter(p => (filter.value !== 'custom' || p.custom)
  && `${p.id} ${p.name}`.toLowerCase().includes(search.value.trim().toLowerCase())))
const customCount = computed(() => properties.value.filter(p => p.custom).length)
const historyCount = computed(() => properties.value.filter(p => effective(p.id).history !== 'none').length)
const orphaned = computed(() => Object.keys(policy.value.overrides).filter(id => !Object.hasOwn(props.model.properties, id)))
const events = computed(() => Object.entries(props.model.events).map(([id, raw]) => ({ id, name: (raw as {name?:string})?.name || id, type: 'EVENT', numeric: false, fields: Object.keys((raw as {fields?:object})?.fields || {}).length, custom: Object.hasOwn(policy.value.eventOverrides, id) })))
const eventOrphans = computed(() => Object.keys(policy.value.eventOverrides).filter(id => !Object.hasOwn(props.model.events,id)))
const selected = computed(() => (editorKind.value === 'events' ? events.value : properties.value).find(p => p.id === editor.value))
const selectedCustom = computed(() => selected.value && Object.hasOwn(editorKind.value === 'events' ? policy.value.eventOverrides : policy.value.overrides, selected.value.id))
const drawerOpen = computed({ get: () => editor.value !== null, set: (value: boolean) => { if (!value) editor.value = null } })
const writeLabels = { every: '每次上报', change: '仅值变化', interval: '固定间隔取样' }

function effective(id: string) { return Object.hasOwn(policy.value.overrides, id) ? policy.value.overrides[id] : policy.value.defaults }
function edit(id: string) {
  editorKind.value = 'properties'
  editing.value = copyPolicy(effective(id))
  if (!properties.value.find(p => p.id === id)?.numeric) editing.value.aggregate = false
  editor.value = id
}
function effectiveEvent(id: string) { return Object.hasOwn(policy.value.eventOverrides,id) ? policy.value.eventOverrides[id] : policy.value.eventDefaults }
function editEvent(id: string) { editorKind.value = 'events'; editing.value = copyPolicy(effectiveEvent(id)); editor.value = id }
function applyOverride() {
  if (!selected.value || locked.value || !validateRule(editing.value)) return
  if (editorKind.value === 'events') {
    if (!validateEventRule(editing.value)) return
    policy.value.eventOverrides = {...policy.value.eventOverrides,[selected.value.id]:copyPolicy(editing.value)}
    editor.value = null
    return
  }
  if (!selected.value.numeric) editing.value.aggregate = false
  policy.value.overrides = { ...policy.value.overrides, [selected.value.id]: copyPolicy(editing.value) }
  editor.value = null
}
function inheritEvent(id: string) { const next = {...policy.value.eventOverrides}; delete next[id]; policy.value.eventOverrides = next }
function restoreSelected() { if (!selected.value) return; if (editorKind.value === 'events') inheritEvent(selected.value.id); else inherit(selected.value.id); editor.value = null }
function inherit(id: string) {
  const next = { ...policy.value.overrides }; delete next[id]; policy.value.overrides = next
}
function preset(mode: 'latest' | 'trend' | 'recent') {
  const rule = defaultDataRule()
  if (mode === 'trend') Object.assign(rule, { history: 'time', days: Math.min(30, capabilities.value?.maximumRetentionDays || 30) })
  if (mode === 'recent') Object.assign(rule, { history: 'count', count: 100, write: 'change' })
  policy.value.defaults = rule
}
function restore() { policy.value = parsePolicy(saved.value); editor.value = null }
async function save() {
  if (locked.value || !valid.value) return
    const currentGeneration = generation
    try { await ElMessageBox.confirm('保存后对后续上报生效。缩短保留周期会清理超期历史，延长周期不能恢复已删除数据；关闭历史只停止新写入。', '保存数据策略', {confirmButtonText:'保存策略',cancelButtonText:'返回检查',type:'warning'}) } catch { return }
    if (currentGeneration !== generation || locked.value) return
    persisting.value = true
    try {
      const result = await historyApi.save(props.scope, props.productId, copyPolicy(policy.value), revision.value, life.signal)
      if (currentGeneration !== generation) return
      policy.value = parsePolicy(JSON.stringify(result.policy)); saved.value = JSON.stringify(policy.value)
      revision.value = result.revision; capabilities.value = result.capabilities; savedAt.value = new Date().toISOString(); storageError.value = ''
      ElMessage.success('数据策略已保存，后续上报按新策略处理')
    } catch (e) { if (currentGeneration === generation && !isCancelled(e)) storageError.value = errorMessage(e) }
    finally { if (currentGeneration === generation) persisting.value = false }
}
async function loadServer() {
  const current = ++generation; loadingPolicy.value = true; loaded.value = false; storageError.value = ''
  try {
    const result = await historyApi.policy(props.scope, props.productId, life.signal)
    if (current !== generation) return
    policy.value = parsePolicy(JSON.stringify(result.policy)); saved.value = JSON.stringify(policy.value)
    revision.value = result.revision; capabilities.value = result.capabilities; loaded.value = true
  } catch(e) { if (current === generation && !isCancelled(e)) storageError.value = `数据策略加载失败：${errorMessage(e)}` }
  finally { if (current === generation) loadingPolicy.value = false }
}
watch(() => [props.scope, props.productId], () => {
  policy.value = defaultDataPolicy(); savedAt.value = ''; storageError.value = ''; editor.value = null
  saved.value = JSON.stringify(policy.value)
  void loadServer()
}, { immediate: true })
watch(dirty, value => emit('dirty', value), { immediate: true })
watch(() => loadingPolicy.value || persisting.value, value => emit('busy', value), { immediate: true })
onScopeDispose(() => { generation++; life.abort(); emit('dirty', false); emit('busy',false) })
defineExpose({
  effective, edit, effectiveEvent, editEvent, save, restore, valid,
  isCustom: (id: string) => Object.hasOwn(policy.value.overrides, id),
  isEventCustom: (id: string) => Object.hasOwn(policy.value.eventOverrides, id),
})
</script>

<template>
  <section class="data-policy">
    <header class="policy-heading">
      <div><h2>数据策略 <el-tag :type="capabilities?.enabled ? 'success' : 'info'" effect="plain">{{loaded ? capabilities?.enabled ? '历史存储已启用' : '历史存储未启用' : loadingPolicy ? '加载中' : '加载失败'}}</el-tag></h2><p>分别配置属性历史与完整事件的记录和保留方式。</p></div>
      <el-tag :type="dirty ? 'warning' : 'info'">{{ dirty ? '有未保存修改' : loaded ? `策略版本 ${revision}` : '尚未加载策略' }}</el-tag>
    </header>
    <el-alert v-if="loaded" :title="capabilities?.enabled ? '策略保存到服务端，对后续属性和事件上报生效。' : '历史存储尚未启用，可配置最新值；启用存储后才能记录历史。'" description="当前支持逐次记录和按时间保留；严格条数保留、变化记录、间隔采样和长期小时统计尚未开放。" type="info" :closable="false" show-icon />
    <el-alert v-if="storageError" :title="storageError" type="error" :closable="false" />
    <el-button v-if="storageError" :disabled="loadingPolicy || persisting" @click="loadServer">重新加载服务端策略</el-button>
    <el-skeleton v-if="loadingPolicy" :rows="6" animated />
    <template v-if="loaded">
    <div class="policy-metrics">
      <div><strong>{{ properties.length }}</strong><span>草稿属性</span></div>
      <div><strong>{{ properties.length - customCount }}</strong><span>继承产品默认</span></div>
      <div><strong>{{ customCount }}</strong><span>单独设置</span></div>
      <div><strong>{{ historyCount }}</strong><span>计划记录历史</span></div>
    </div>
    <div class="policy-layout">
      <section class="policy-card">
        <div class="policy-step"><span>01</span><div><h3>产品默认策略</h3><p>新属性默认继承此处，单独设置的属性保持自己的策略。</p></div></div>
        <div class="policy-presets" aria-label="快捷方案">
          <el-button :disabled="locked" @click="preset('latest')">仅最新值</el-button>
          <el-button :disabled="locked || !capabilities?.enabled" @click="preset('trend')">历史 30 天</el-button>
          <el-button :disabled="locked || !capabilities?.countRetention" @click="preset('recent')">最近记录</el-button>
        </div>
        <DataRuleForm v-model="policy.defaults" :disabled="locked" :capabilities="capabilities" />
        <div class="policy-result"><strong>{{dirty ? '待保存的默认策略' : '已保存的默认策略'}}</strong><p>{{ policy.defaults.latest ? '更新最新状态' : '不维护最新状态' }} · {{ ruleSummary(policy.defaults) }}</p><p v-if="policy.defaults.aggregate">数值属性另外保留每小时统计 {{ policy.defaults.aggregateDays }} 天。</p></div>
      </section>
      <aside class="policy-guide">
        <h3>一个产品，多种需要</h3>
        <p>温度保留 30 天，开关状态保留 7 天，信号强度仅维护最新值。可以在下方为每个属性单独设置。</p>
        <div><strong>最新值与历史分别管理</strong><p>历史到期后仍可保留最新状态；不存历史也不影响实时上报和规则处理。</p></div>
        <div><strong>从下一次上报开始记录</strong><p>保存策略不会补录过去的数据。缩短保留周期会清理超期记录，延长周期不能恢复已清理数据。</p></div>
        <div><strong>原始值与统计值独立保留</strong><p>短期看细节、长期看趋势。小时统计直接基于上报样本计算，不能还原已删除的原始值。</p></div>
        <div><strong>修改周期不会补回旧数据</strong><p>未来接入执行后，延长周期无法恢复已删除记录；缩短周期需要确认清理影响。</p></div>
      </aside>
    </div>
    <section class="policy-card policy-properties">
      <div class="policy-step"><span>02</span><div><h3>属性策略</h3><p>基于已保存的物模型草稿配置；所有设备继承产品策略，策略独立保存。</p></div></div>
      <div class="policy-toolbar">
        <el-input v-model="search" placeholder="搜索属性名称或标识" clearable aria-label="搜索策略属性" />
        <el-radio-group v-model="filter" aria-label="属性策略筛选"><el-radio-button value="all">全部属性</el-radio-button><el-radio-button value="custom">单独设置 {{ customCount }}</el-radio-button></el-radio-group>
      </div>
      <el-table :data="rows" :empty-text="properties.length ? '没有符合筛选条件的属性' : '还没有属性，请先在物模型页添加并保存草稿'" class="policy-table">
        <el-table-column label="属性" min-width="180"><template #default="{ row }"><strong>{{ row.name }}</strong><small>{{ row.id }} · {{ row.type }}</small></template></el-table-column>
        <el-table-column label="策略来源" width="120"><template #default="{ row }"><el-tag :type="row.custom ? 'success' : 'info'" effect="plain">{{ row.custom ? '单独设置' : '产品默认' }}</el-tag></template></el-table-column>
        <el-table-column label="最新值" width="95"><template #default="{ row }">{{ effective(row.id).latest ? '保留' : '不保留' }}</template></el-table-column>
        <el-table-column label="历史保留" min-width="160"><template #default="{ row }">{{ effective(row.id).history === 'none' ? '不存历史' : effective(row.id).history === 'time' ? `${effective(row.id).days} 天` : `最近 ${effective(row.id).count} 条` }}<small v-if="effective(row.id).history !== 'none'">{{ writeLabels[effective(row.id).write] }}{{ effective(row.id).write === 'interval' ? ` · ${effective(row.id).intervalSeconds} 秒` : '' }}</small></template></el-table-column>
        <el-table-column label="小时统计" min-width="120"><template #default="{ row }">{{ !row.numeric ? '不适用' : effective(row.id).aggregate ? `${effective(row.id).aggregateDays} 天` : '不保留' }}</template></el-table-column>
        <el-table-column label="操作" width="180" fixed="right"><template #default="{ row }"><el-button link type="primary" :disabled="locked" @click="edit(row.id)">{{ row.custom ? '编辑' : '单独设置' }}</el-button><el-button v-if="row.custom" link :disabled="locked" @click="inherit(row.id)">恢复继承</el-button></template></el-table-column>
      </el-table>
      <div v-if="orphaned.length" class="policy-orphans"><span>{{ orphaned.length }} 项覆盖对应的属性已不在当前草稿中；已发布版本的设备仍可能使用。</span><el-button link :disabled="locked" @click="orphaned.forEach(inherit)">清除这些覆盖</el-button></div>
    </section>
    <section class="policy-card policy-events">
      <div class="policy-step"><span>03</span><div><h3>事件记录策略</h3><p>独立于属性默认策略。按每台设备、每种事件保存完整记录，不维护属性影子、不按值变化过滤、不做属性小时统计。</p></div></div>
      <div class="policy-layout"><div><h4>事件默认策略</h4><DataRuleForm v-model="policy.eventDefaults" event :disabled="locked" :capabilities="capabilities" /></div><div class="policy-result"><strong>{{dirty ? '待保存的事件默认策略' : '已保存的事件默认策略'}}</strong><p>{{eventRuleSummary(policy.eventDefaults)}}</p><p>不保存历史仍可进行实时推送和规则处理；故障事件记录与告警确认、恢复记录分别管理。</p></div></div>
      <el-table :data="events" empty-text="还没有事件，请先在物模型页添加并保存草稿" class="event-policy-table">
        <el-table-column label="事件" min-width="180"><template #default="{row}"><strong>{{row.name}}</strong><small>{{row.id}} · {{row.fields}} 个字段</small></template></el-table-column>
        <el-table-column label="策略来源" width="120"><template #default="{row}"><el-tag :type="row.custom?'success':'info'" effect="plain">{{row.custom?'单独设置':'事件默认'}}</el-tag></template></el-table-column>
        <el-table-column label="事件历史" min-width="220"><template #default="{row}">{{eventRuleSummary(effectiveEvent(row.id))}}</template></el-table-column>
        <el-table-column label="操作" width="180" fixed="right"><template #default="{row}"><el-button link type="primary" :disabled="locked" @click="editEvent(row.id)">{{row.custom?'编辑':'单独设置'}}</el-button><el-button v-if="row.custom" link :disabled="locked" @click="inheritEvent(row.id)">恢复继承</el-button></template></el-table-column>
      </el-table>
      <div v-if="eventOrphans.length" class="policy-orphans"><span>{{eventOrphans.length}} 项事件覆盖已不在草稿中；已发布版本的设备仍可能使用。</span><el-button link :disabled="locked" @click="eventOrphans.forEach(inheritEvent)">清除事件覆盖</el-button></div>
    </section>
    <footer class="policy-footer">
      <div><strong>{{ dirty ? '修改尚未保存' : loaded ? `服务端策略版本 ${revision}` : '尚未加载策略' }}</strong><p>{{ savedAt ? `上次保存：${new Date(savedAt).toLocaleString()}` : '策略独立保存，无需发布物模型；关闭历史不会立即清除已有记录。' }}</p></div>
      <div><el-button :disabled="locked || !dirty" @click="restore">撤销修改</el-button><el-button type="primary" :loading="persisting" :disabled="locked || !dirty || !valid" @click="save">保存数据策略</el-button></div>
    </footer>
    </template>
    <el-drawer v-model="drawerOpen" :title="`${editorKind==='events'?'事件':'属性'}策略 · ${selected?.name || ''}`" size="min(560px, 100vw)" append-to-body :close-on-click-modal="false">
      <template v-if="selected"><el-alert :title="editorKind==='events'?'单独设置将完整覆盖此事件的事件默认策略。':'单独设置将完整覆盖此属性的产品默认策略。'" :description="'应用后还需保存数据策略；物模型定义保持不变。'" type="info" :closable="false" /><p class="policy-drawer-caption">{{ selected.id }} · {{ selected.type }}<span v-if="editorKind==='properties'&&!selected.numeric"> · 非数值属性不生成小时统计</span></p><DataRuleForm v-model="editing" :disabled="locked" :numeric="selected.numeric" :event="editorKind==='events'" :capabilities="capabilities" /></template>
      <template #footer><el-button @click="editor = null">取消</el-button><el-button v-if="selectedCustom" :disabled="locked" @click="restoreSelected">{{editorKind==='events'?'恢复事件默认':'恢复产品默认'}}</el-button><el-button type="primary" :disabled="locked || !selected || !(editorKind==='events'?validateEventRule(editing):validateRule(editing))" @click="applyOverride">应用设置</el-button></template>
    </el-drawer>
  </section>
</template>

<style scoped>
.policy-events{margin-top:24px}.policy-events h4{font-size:13px;margin:0 0 16px}.event-policy-table small{display:block;color:#8b9b94;font-size:11px;margin-top:5px}.policy-events .policy-result{align-self:start}
.data-policy{padding-top:8px;color:#35564d}.policy-heading{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-bottom:18px}.policy-heading h2{display:flex;align-items:center;gap:12px}.policy-heading p,.policy-step p{font-size:12px;color:#81938d;line-height:1.8;margin:6px 0 0}.data-policy>.el-alert+.el-alert{margin-top:12px}.policy-metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));margin:24px 0;border:1px solid #e3ebe7;border-radius:8px;padding:20px 0;background:#fafcfb}.policy-metrics>div{display:flex;align-items:center;gap:12px;padding:0 24px;border-right:1px solid #e3ebe7}.policy-metrics>div:last-child{border:0}.policy-metrics strong{font-size:25px;font-weight:550;color:#287568}.policy-metrics span{font-size:12px;color:#7c9087}.policy-layout{display:grid;grid-template-columns:minmax(0,1fr) 285px;gap:24px;margin-bottom:24px}.policy-card{border:1px solid #e0e9e4;border-radius:9px;padding:24px;min-width:0}.policy-step{display:flex;gap:12px;align-items:flex-start;margin-bottom:22px}.policy-step>span{display:grid;place-items:center;flex-shrink:0;background:#edf5f0;border-radius:7px;color:#4d8b74;width:30px;height:30px;font-size:12px}.policy-step h3,.policy-guide h3{font-size:15px;margin:4px 0 0}.policy-presets{display:flex;flex-wrap:wrap;gap:8px;margin-bottom:24px}.policy-presets .el-button+.el-button{margin-left:0}.policy-result{padding:16px 18px;background:#eff6f2;border-radius:7px;font-size:12px;line-height:1.8;color:#557b69}.policy-result strong{font-weight:600}.policy-result p{margin:6px 0 0;overflow-wrap:anywhere}.policy-guide{padding:22px;background:#f7f9f8;border-radius:9px;align-self:start}.policy-guide p{font-size:12px;line-height:1.9;color:#7b9086;margin:10px 0 0}.policy-guide>div{border-top:1px solid #e2eae5;margin-top:20px;padding-top:18px}.policy-guide strong{font-size:12px;font-weight:600}.policy-toolbar{display:flex;gap:16px;justify-content:space-between;flex-wrap:wrap;margin-bottom:18px}.policy-toolbar>.el-input{max-width:280px}.policy-table strong{font-size:13px;font-weight:500}.policy-table small{display:block;color:#8b9b94;font-size:11px;margin-top:5px}.policy-orphans{display:flex;gap:10px;align-items:center;flex-wrap:wrap;font-size:12px;color:#8a7857;margin-top:16px}.policy-footer{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:20px 0 0;margin-top:8px}.policy-footer strong{font-size:13px;font-weight:500}.policy-footer p{font-size:11px;color:#8b9c94;margin:6px 0 0}.policy-footer>div:last-child{display:flex;flex-wrap:wrap;gap:8px}.policy-footer .el-button+.el-button{margin:0}.policy-drawer-caption{font-size:12px;color:#7d9188;margin:20px 0}
@media(max-width:1100px){.policy-layout{grid-template-columns:1fr}.policy-guide{display:none}.policy-metrics>div{flex-direction:column;gap:5px;padding:0 8px}}
@media(max-width:680px){.policy-heading,.policy-footer{align-items:flex-start;flex-direction:column}.policy-card{padding:16px}.policy-metrics{grid-template-columns:repeat(2,minmax(0,1fr));gap:20px 0}.policy-metrics>div:nth-child(2){border:0}.policy-toolbar>.el-input{max-width:none}}
</style>
