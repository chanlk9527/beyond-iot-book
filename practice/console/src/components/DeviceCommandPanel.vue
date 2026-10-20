<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import { initialValue, schemaSummary, type ValueSchema } from './modelSchema'
import { catalogApi, resourceScope, type Device, type ModelSpec, type ShadowScalar, type DownlinkStatus } from '../api/catalog'
import { errorMessage, isCancelled } from '../api/management'
import DeviceValueInput from './DeviceValueInput.vue'
import DownlinkResult from './DownlinkResult.vue'

const props = defineProps<{ scope:string; device:Device; model:ModelSpec; writable:boolean }>()
const emit = defineEmits<{dispatched:[messageId:string]}>()
type Capability = { id:string; name?:string; description?:string }
type Field = Capability & { schema:ValueSchema; required?:boolean; access?:string }
type Operation = Capability & { inputs:Record<string,Field> }
type Draft = { values:Record<string,ShadowScalar>; included:Record<string,boolean> }
const PAGE_SIZE = 8
const mode = ref<'property'|'operation'>('property')
const query = ref(''), parameterQuery = ref('')
const browserPage = ref(1), editorPage = ref(1), parameterPage = ref(1)
const operations = computed(() => Object.values(props.model.operations) as Operation[])
const properties = computed(() => (Object.values(props.model.properties) as Field[]).filter(p => p.access !== 'READ_ONLY'))
const operationId = ref('')
const operation = computed(() => operations.value.find(o => o.id === operationId.value))
const drafts = ref<Record<string,Draft>>({})
const draft = computed(() => drafts.value[operationId.value] || { values:{}, included:{} })
const fields = computed(() => Object.values(operation.value?.inputs || {}))
const propertyValues = ref<Record<string,ShadowScalar>>({}), selected = ref<string[]>([])
const selectedProperties = computed(() => properties.value.filter(p => selected.value.includes(p.id)))
const capabilities = computed<Capability[]>(() => mode.value === 'property' ? properties.value : operations.value)
const matches = (item:Capability, search:string) => [item.id,item.name,item.description].some(v => v?.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase()))
const filteredCapabilities = computed(() => capabilities.value.filter(item => matches(item,query.value)))
const visibleCapabilities = computed(() => filteredCapabilities.value.slice((browserPage.value-1)*PAGE_SIZE,browserPage.value*PAGE_SIZE))
const visibleProperties = computed(() => selectedProperties.value.slice((editorPage.value-1)*PAGE_SIZE,editorPage.value*PAGE_SIZE))
const filteredFields = computed(() => fields.value.filter(f => matches(f,parameterQuery.value)))
const visibleFields = computed(() => filteredFields.value.slice((parameterPage.value-1)*PAGE_SIZE,parameterPage.value*PAGE_SIZE))
const busy = ref(false), pending = ref(false), error = ref(''), result = ref<DownlinkStatus|null>(null), resultLabel = ref('')
const life = new AbortController()
const scope = computed(() => resourceScope(props.scope,props.device))
const editable = computed(() => props.writable && props.device.lifecycle === 'ACTIVE')
const canSend = computed(() => editable.value && !busy.value && !pending.value)
const requestValues = computed(() => {
  const values:Record<string,ShadowScalar> = {}
  if (mode.value === 'property') {
    for (const p of selectedProperties.value) values[p.id] = propertyValues.value[p.id]!
  } else {
    for (const f of fields.value) if (f.required || draft.value.included[f.id]) values[f.id] = draft.value.values[f.id]!
  }
  return values
})
const preview = computed(() => JSON.stringify(mode.value === 'property' ? {properties:requestValues.value} : {operationId:operationId.value,inputs:requestValues.value},null,2))
const includedCount = computed(() => fields.value.filter(f => f.required || draft.value.included[f.id]).length)

function initializeDraft() {
  const current = operation.value
  if (!current || drafts.value[current.id]) return
  const next:Draft = {values:{},included:{}}
  for (const f of Object.values(current.inputs)) { next.values[f.id] = initialValue(f.schema); next.included[f.id] = !!f.required }
  drafts.value[current.id] = next
}
watch(() => [props.device.id,props.device.modelVersion,props.model], () => {
  propertyValues.value = {}; selected.value = []; drafts.value = {}
  for (const p of properties.value) propertyValues.value[p.id] = initialValue(p.schema)
  operationId.value = operations.value[0]?.id || ''
  mode.value = properties.value.length ? 'property' : 'operation'
  query.value = ''; parameterQuery.value = ''; browserPage.value = 1; editorPage.value = 1; parameterPage.value = 1
  initializeDraft()
}, {immediate:true})
watch(operationId, () => { initializeDraft(); parameterQuery.value = ''; parameterPage.value = 1; error.value = '' })
watch(mode, () => { query.value = ''; browserPage.value = 1; error.value = '' })
watch(query, () => { browserPage.value = 1 })
watch(parameterQuery, () => { parameterPage.value = 1 })
watch(() => selectedProperties.value.length, count => { editorPage.value = Math.min(editorPage.value,Math.max(1,Math.ceil(count/PAGE_SIZE))) })
function toggleProperty(id:string) {
  if (!canSend.value) return
  if (selected.value.includes(id)) selected.value = selected.value.filter(value => value !== id)
  else { selected.value.push(id); editorPage.value = Math.ceil(selectedProperties.value.length/PAGE_SIZE) }
}
function selectVisible() {
  if (!canSend.value) return
  selected.value = [...new Set([...selected.value,...visibleCapabilities.value.map(item => item.id)])]
}
function meta(item:Capability) {
  if (mode.value === 'property') return schemaSummary((item as Field).schema)
  return Object.keys((item as Operation).inputs).length + ' 个参数'
}
async function send() {
  if (!canSend.value || (mode.value === 'operation' && !operation.value)) return
  if (mode.value === 'property' && !selectedProperties.value.length) { error.value = '请先从左侧选择要写入的属性'; return }
  const kind = mode.value, values = {...requestValues.value}, currentOperation = operationId.value
  const label = kind === 'property' ? `属性写入 · ${selectedProperties.value.length} 项` : `操作调用 · ${operation.value?.name || currentOperation}`
  busy.value = true; error.value = ''; result.value = null; pending.value = false
  try {
    result.value = kind === 'operation'
      ? await catalogApi.invoke(scope.value,props.device.id,currentOperation,values,life.signal)
      : await catalogApi.writeProperties(scope.value,props.device.id,values,life.signal)
    resultLabel.value = label
    emit('dispatched',result.value.messageId)
  } catch (e) { if (!isCancelled(e)) error.value = errorMessage(e) }
  finally { busy.value = false }
}
onScopeDispose(() => life.abort())
</script>
<template>
 <div class="command-panel">
  <header class="control-heading"><div><h2>设备控制</h2><p>选择能力，填写本次请求，再查看设备执行结果。</p></div><span class="model-label">绑定模型 {{device.modelVersion}}</span></header>
  <el-alert v-if="!editable" title="当前设备不可控制，请检查操作权限及设备、产品启用状态。" type="info" :closable="false"/>
  <div class="control-modes" role="group" aria-label="控制类型">
   <button type="button" :class="{active:mode==='property'}" :aria-pressed="mode==='property'" @click="mode='property'"><span>属性写入</span><small>{{properties.length}} 项可写</small></button>
   <button type="button" :class="{active:mode==='operation'}" :aria-pressed="mode==='operation'" @click="mode='operation'"><span>操作调用</span><small>{{operations.length}} 项操作</small></button>
  </div>
  <div class="control-workspace">
   <aside class="capability-browser" aria-label="能力列表">
    <div class="browser-header"><el-input v-model="query" clearable :placeholder="mode==='property'?'搜索属性名称、标识或说明':'搜索操作名称、标识或说明'" aria-label="搜索控制能力"/><div class="browser-summary"><span>{{query.trim()?'匹配 '+filteredCapabilities.length+' 项':'共 '+capabilities.length+' 项'}}</span><el-button v-if="mode==='property'&&visibleCapabilities.length" link type="primary" :disabled="!canSend" @click="selectVisible">选中本页</el-button></div></div>
    <div class="capability-list">
     <p v-if="!capabilities.length" class="control-empty">{{mode==='property'?'当前绑定模型没有可写属性。':'当前绑定模型没有可调用操作。'}}</p>
     <div v-else-if="!filteredCapabilities.length" class="control-empty"><p>没有匹配的{{mode==='property'?'属性':'操作'}}</p><el-button link type="primary" @click="query=''">清除搜索</el-button></div>
     <button v-for="item in visibleCapabilities" :key="mode+item.id" type="button" class="capability-item" :class="{chosen:mode==='property'?selected.includes(item.id):operationId===item.id}" :disabled="mode==='property'&&!canSend" :aria-pressed="mode==='property'?selected.includes(item.id):operationId===item.id" @click="mode==='property'?toggleProperty(item.id):operationId=item.id">
      <span class="capability-mark" aria-hidden="true">{{mode==='property'?(selected.includes(item.id)?'✓':'+'):(operationId===item.id?'●':'○')}}</span><span class="capability-info"><strong>{{item.name||item.id}}</strong><code>{{item.id}}</code><small>{{meta(item)}}</small></span>
     </button>
    </div>
    <el-pagination v-if="filteredCapabilities.length>8" v-model:current-page="browserPage" :page-size="8" :total="filteredCapabilities.length" layout="prev, pager, next" :pager-count="5" size="small" aria-label="能力列表分页"/>
   </aside>
   <section class="request-editor" aria-label="请求编辑">
    <template v-if="mode==='property'">
     <div class="editor-header"><div><h3>本次属性写入 <span>{{selectedProperties.length}} 项</span></h3><p>只发送已选属性，直接下发，不修改期望状态。</p></div><el-button v-if="selectedProperties.length" link :disabled="!canSend" @click="selected=[]">清空选择</el-button></div>
     <div v-if="!selectedProperties.length" class="editor-empty"><span class="empty-symbol" aria-hidden="true">＋</span><h3>选择本次要写入的属性</h3><p>从左侧搜索并添加属性，未选中的属性不会下发。</p></div>
     <div v-else class="editor-fields">
      <article v-for="p in visibleProperties" :key="p.id" class="command-field" :data-property="p.id"><div class="field-heading"><div><strong>{{p.name||p.id}}</strong><code>{{p.id}}</code></div><el-button link :disabled="!canSend" :aria-label="'移除 '+p.id" @click="toggleProperty(p.id)">移除</el-button></div><p v-if="p.description" class="field-description">{{p.description}}</p><DeviceValueInput v-model="propertyValues[p.id]" :schema="p.schema" :disabled="!canSend" :label="p.id+' 写入值'"/><small class="schema-hint">{{schemaSummary(p.schema)}}</small></article>
     </div>
     <div v-if="selectedProperties.length>8" class="editor-pagination"><span>已选 {{selectedProperties.length}} 项，所有页均会下发</span><el-pagination v-model:current-page="editorPage" :page-size="8" :total="selectedProperties.length" layout="prev, pager, next" :pager-count="5" size="small" aria-label="已选属性分页"/></div>
    </template>
    <template v-else-if="operation">
     <div class="editor-header"><div><h3>{{operation.name||operation.id}}</h3><code>{{operation.id}}</code><p v-if="operation.description">{{operation.description}}</p><p>{{fields.filter(f=>f.required).length}} 项必填 · {{fields.filter(f=>!f.required).length}} 项选填 · 本次提交 {{includedCount}} 项</p></div></div>
     <div v-if="fields.length>8" class="parameter-search"><el-input v-model="parameterQuery" clearable placeholder="搜索参数名称、标识或说明" aria-label="搜索操作参数"/><span>{{filteredFields.length}} 项参数</span></div>
     <div class="editor-fields">
      <p v-if="!fields.length" class="control-empty">此操作无需输入参数，可以直接调用。</p><p v-else-if="!filteredFields.length" class="control-empty">没有匹配的参数，其他页已填写的参数仍会提交。</p>
      <article v-for="f in visibleFields" :key="operationId+f.id" class="command-field" :data-parameter="f.id"><div class="field-heading"><div><strong v-if="f.required">{{f.name||f.id}} <span class="required-label">必填</span></strong><el-checkbox v-else v-model="draft.included[f.id]" :disabled="!canSend">{{f.name||f.id}} <span class="optional-label">选填</span></el-checkbox><code>{{f.id}}</code></div></div><p v-if="f.description" class="field-description">{{f.description}}</p><DeviceValueInput v-model="draft.values[f.id]" :schema="f.schema" :disabled="!canSend||(!f.required&&!draft.included[f.id])" :label="f.id+' 参数'"/><small class="schema-hint">{{schemaSummary(f.schema)}}</small></article>
     </div>
     <div v-if="filteredFields.length>8" class="editor-pagination"><span>所有页的必填和勾选参数均会提交</span><el-pagination v-model:current-page="parameterPage" :page-size="8" :total="filteredFields.length" layout="prev, pager, next" :pager-count="5" size="small" aria-label="操作参数分页"/></div>
    </template>
    <div v-else class="editor-empty"><h3>选择要调用的操作</h3><p>从左侧搜索操作，再填写对应参数。</p></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="control-error"/>
    <footer class="request-footer"><div><strong>{{pending?'正在等待设备回执':mode==='property'?'已选 '+selectedProperties.length+' 项属性':operation?'调用 '+(operation.name||operation.id):'尚未选择操作'}}</strong><p>{{pending?'可继续浏览能力，当前命令结果显示在下方。':'切换能力保留本页输入，离开设备控制后清除。'}}</p></div><el-button type="primary" :disabled="!canSend||(mode==='property'?!selectedProperties.length:!operation)" :loading="busy" @click="send">{{mode==='property'?'下发属性':'调用功能'}}</el-button></footer>
   </section>
  </div>
  <details v-if="mode==='property'?selectedProperties.length:operation" class="request-preview"><summary>查看本次请求 JSON</summary><pre>{{preview}}</pre></details>
  <section v-if="result" class="control-result"><h3>最近一次下发 <span>{{resultLabel}}</span></h3><DownlinkResult :scope="scope" :device-id="device.id" :initial="result" @pending="pending=$event"/></section>
 </div>
</template>
<style scoped>
.command-panel{width:100%;min-width:0}.control-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:16px;margin-bottom:20px}.control-heading h2{font-size:18px;margin:0 0 8px}.command-panel p{font-size:13px;line-height:1.65;color:var(--text-secondary);margin:7px 0}.model-label{font-size:12px;white-space:nowrap;background:var(--surface-secondary);border:1px solid var(--line);border-radius:6px;padding:7px 10px;color:var(--text-secondary)}.control-modes{display:flex;gap:8px;margin:18px 0}.control-modes button{border:1px solid var(--line);background:var(--surface);color:var(--text-secondary);border-radius:9px;padding:10px 16px;cursor:pointer;text-align:left;display:flex;align-items:center;gap:12px;font:inherit;font-size:13px}.control-modes button.active{border-color:var(--el-color-primary);background:var(--el-color-primary-light-9);color:var(--el-color-primary)}.control-modes small{font-size:11px;opacity:.8}.control-workspace{display:grid;grid-template-columns:minmax(230px,300px) minmax(0,1fr);border:1px solid var(--line);border-radius:12px;overflow:hidden}.capability-browser{background:var(--surface-secondary);border-right:1px solid var(--line);display:flex;flex-direction:column;min-width:0}.browser-header{padding:16px 16px 8px}.browser-summary{display:flex;justify-content:space-between;align-items:center;min-height:32px;font-size:12px;color:var(--text-secondary)}.capability-list{flex:1;padding:0 8px 8px;max-height:575px;overflow-y:auto}.capability-item{width:100%;display:flex;gap:10px;align-items:flex-start;padding:8px 10px;margin:2px 0;text-align:left;border:1px solid transparent;border-radius:8px;background:transparent;color:var(--text-primary);font:inherit;cursor:pointer}.capability-item:hover{background:var(--surface)}.capability-item.chosen{background:var(--el-color-primary-light-9);border-color:var(--el-color-primary-light-7)}.capability-item:disabled{cursor:not-allowed;opacity:.6}.capability-item:focus-visible,.control-modes button:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:-2px}.capability-mark{color:var(--el-color-primary);width:16px;flex-shrink:0;font-size:16px;line-height:20px}.capability-info{display:flex;flex-direction:column;gap:2px;min-width:0}.capability-info strong{line-height:1.3;font-size:13px;font-weight:600;overflow-wrap:anywhere}.capability-info code{line-height:1.3;font-size:11px;color:var(--text-secondary);overflow-wrap:anywhere}.capability-info small{font-size:11px;color:var(--text-secondary);line-height:1.5;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}.capability-browser>.el-pagination{padding:10px 12px;justify-content:center;border-top:1px solid var(--line)}.request-editor{display:flex;flex-direction:column;min-width:0;background:var(--surface)}.editor-header{padding:20px 24px 16px;border-bottom:1px solid var(--line);display:flex;justify-content:space-between;align-items:flex-start;gap:12px}.editor-header h3,.control-result h3{font-size:15px;margin:0 0 8px;font-weight:600}.editor-header h3 span,.control-result h3 span{font-size:12px;color:var(--text-secondary);font-weight:400;margin-left:8px}.editor-header code{font-size:12px;color:var(--text-secondary);overflow-wrap:anywhere}.editor-header p{margin-bottom:0}.editor-fields{max-height:490px;overflow-y:auto;padding:0 24px;flex:1;min-height:160px}.command-field{padding:18px 0;border-bottom:1px solid var(--line)}.command-field:last-child{border-bottom:0}.field-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:12px;margin-bottom:12px}.field-heading strong{font-size:13px;font-weight:600}.field-heading code{display:block;color:var(--text-secondary);font-size:11px;margin-top:5px;overflow-wrap:anywhere}.field-heading .el-checkbox{height:auto;white-space:normal}.required-label,.optional-label{font-size:10px;font-weight:400;margin-left:6px;color:var(--text-secondary)}.required-label{color:var(--el-color-danger)}.command-field .field-description{margin:-3px 0 12px;overflow-wrap:anywhere}.command-field :deep(.el-input-number){width:min(100%,320px)}.command-field :deep(.el-select){width:100%}.schema-hint{display:block;color:var(--text-secondary);font-size:11px;line-height:1.6;margin-top:8px;overflow-wrap:anywhere}.editor-empty{min-height:300px;display:flex;flex-direction:column;justify-content:center;align-items:center;padding:32px;text-align:center;flex:1}.editor-empty h3{font-size:15px;margin:16px 0 4px}.empty-symbol{height:44px;width:44px;border-radius:12px;background:var(--surface-secondary);color:var(--el-color-primary);font-size:24px;display:grid;place-items:center}.control-empty{padding:24px 12px;text-align:center}.parameter-search{padding:14px 24px 0;display:flex;align-items:center;gap:12px}.parameter-search>span{font-size:12px;white-space:nowrap;color:var(--text-secondary)}.editor-pagination{display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:8px;padding:12px 20px;border-top:1px solid var(--line)}.editor-pagination>span{font-size:11px;color:var(--text-secondary)}.request-footer{display:flex;justify-content:space-between;align-items:center;gap:16px;padding:16px 24px;background:var(--surface-secondary);border-top:1px solid var(--line);margin-top:auto}.request-footer strong{font-size:13px;font-weight:500}.request-footer p{font-size:11px;margin:4px 0 0}.request-footer>.el-button{flex-shrink:0}.request-preview{margin-top:16px;border:1px solid var(--line);border-radius:8px;padding:12px 16px}.request-preview summary{font-size:12px;color:var(--text-secondary);cursor:pointer}.request-preview pre{max-height:260px;overflow:auto;white-space:pre-wrap;overflow-wrap:anywhere;font-size:12px;line-height:1.6}.control-error{margin:12px 16px;width:auto;flex-shrink:0}.control-result{margin-top:24px}.control-result :deep(.downlink-result){margin-top:12px}.command-panel code,.command-panel pre{font-family:Consolas,monospace}@media(max-width:850px){.control-workspace{grid-template-columns:minmax(190px,240px) minmax(0,1fr)}.editor-header,.request-footer{padding:16px}.editor-fields{padding:0 16px}}@media(max-width:600px){.control-heading{flex-wrap:wrap}.control-modes button{padding:10px;flex:1;justify-content:space-between;gap:6px}.control-modes small{font-size:10px}.control-workspace{grid-template-columns:1fr}.capability-browser{border-right:0;border-bottom:1px solid var(--line)}.capability-list{max-height:235px}.editor-fields{max-height:420px}.editor-empty{min-height:190px;padding:24px}.request-footer{align-items:flex-start;gap:10px}.parameter-search{padding:14px 16px 0}.editor-pagination{padding:10px 12px}}
</style>
