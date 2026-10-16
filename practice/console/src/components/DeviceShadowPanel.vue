<script setup lang="ts">
import { computed, onScopeDispose, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { catalogApi, resourceScope, type Device, type DeviceShadow, type ModelSpec, type ShadowScalar, type DownlinkStatus } from '../api/catalog'
import { errorMessage, isCancelled } from '../api/management'
import StatePanel from './StatePanel.vue'
import DownlinkResult from './DownlinkResult.vue'

const props = defineProps<{ scope:string; device:Device; model:ModelSpec; writable:boolean }>()
type Definition = { id:string; access:string; schema:{ type:string; minimum?:number|null; maximum?:number|null; maxLength?:number|null } }
const definitions = computed(() => Object.values(props.model.properties) as Definition[])
const editable = computed(() => definitions.value.filter(p => p.access === 'READ_WRITE'))
const shadow = ref<DeviceShadow|null>(null), loading = ref(true), busy = ref(false), error = ref(''), saveError = ref('')
const selected = ref<Record<string,boolean>>({}), values = ref<Record<string,ShadowScalar>>({})
const downlink=ref<DownlinkStatus|null>(null), pending=ref(false)
const life = new AbortController()
const canEdit = computed(() => props.writable && !['DISABLED','RETIRED'].includes(props.device.lifecycle))
const rows = computed(() => definitions.value.filter(p=>p.access!=='WRITE_ONLY').map(p=>({
  id:p.id, type:p.schema.type, access:p.access, actual:shadow.value?.reported[p.id], target:shadow.value?.desired[p.id],
  pending:Object.prototype.hasOwnProperty.call(shadow.value?.delta||{},p.id)
})))
function format(value:unknown) { return value === undefined ? '暂无上报' : JSON.stringify(value) }
function time(value?:string|null) { return value ? new Date(value).toLocaleString() : '—' }
async function load() {
  loading.value=true; error.value=''; saveError.value=''
  try {
    shadow.value=await catalogApi.shadow(props.scope,props.device.id,life.signal)
    selected.value={}; values.value={}
    for(const p of editable.value) {
      const target=shadow.value.desired[p.id]
      selected.value[p.id]=!!target
      values.value[p.id]=target?.value ?? (p.schema.type==='BOOLEAN'?false:p.schema.type==='TEXT'?'':(p.schema.minimum??0))
    }
  } catch(e) { if(!isCancelled(e)) error.value=errorMessage(e) }
  finally { loading.value=false }
}
async function save() {
  if(!shadow.value||!canEdit.value||busy.value||pending.value)return
  const desired:Record<string,ShadowScalar|null>={}
  for(const p of editable.value) {
    if(selected.value[p.id]) desired[p.id]=values.value[p.id]??null
    else if(shadow.value.desired[p.id])desired[p.id]=null
  }
  if(!Object.keys(desired).length){ElMessage.info('请选择需要设置的期望属性');return}
  busy.value=true; saveError.value=''
  try {
    downlink.value=null
    const targetScope=resourceScope(props.scope,props.device)
    if (catalogApi.saveAndDispatch) {
      const result=await catalogApi.saveAndDispatch(targetScope,props.device.id,shadow.value.version,desired,life.signal)
      shadow.value=result.shadow;downlink.value=result.downlink
      saveError.value=result.dispatchError?'期望状态已保存，但未下发：'+result.dispatchError:''
      ElMessage.success(result.downlink?'期望状态已保存，请查看下发结果':'期望状态已保存')
    } else {
      shadow.value=await catalogApi.desired(targetScope,props.device.id,shadow.value.version,desired,life.signal)
      ElMessage.success('期望状态已保存')
    }
  } catch(e) { if(!isCancelled(e)) saveError.value=errorMessage(e) }
  finally { busy.value=false }
}
onScopeDispose(()=>life.abort())
void load()
</script>
<template>
  <div v-if="loading"><el-skeleton :rows="5" animated/></div>
  <StatePanel v-else-if="error" title="设备状态暂不可用" :description="error"><el-button @click="load">重试</el-button></StatePanel>
  <div v-else-if="shadow" class="shadow-panel">
    <div class="section-heading"><div><h2>属性最新状态 <el-tag effect="plain">状态修订 #{{shadow.version}}</el-tag></h2><p>最近更新：{{time(shadow.updatedAt)}} · 模型 {{shadow.modelVersion}}</p></div><el-button :disabled="busy" @click="load">刷新状态</el-button></div>
    <el-alert title="期望状态表示待同步目标；差异消失表示上报值已匹配，不作为指令执行回执。" type="info" :closable="false"/>
    <el-table :data="rows" empty-text="当前模型没有可上报属性" class="shadow-table">
      <el-table-column prop="id" label="属性" min-width="140"/>
      <el-table-column prop="type" label="数据类型" width="120"/>
      <el-table-column label="访问方式" width="110"><template #default="{row}">{{row.access==='READ_ONLY'?'只读':'读写'}}</template></el-table-column>
      <el-table-column label="最新上报值" min-width="140"><template #default="{row}">{{format(row.actual?.value)}}</template></el-table-column>
      <el-table-column label="期望值" min-width="140"><template #default="{row}">{{format(row.target?.value)}}</template></el-table-column>
      <el-table-column label="同步状态" width="120"><template #default="{row}"><el-tag :type="row.pending?'warning':'info'" effect="plain">{{row.pending?'存在差异':row.target?'值已匹配':'未设置目标'}}</el-tag></template></el-table-column>
      <el-table-column label="设备观测时间" min-width="180"><template #default="{row}">{{time(row.actual?.observedAt)}}</template></el-table-column>
      <el-table-column label="平台接收时间" min-width="180"><template #default="{row}">{{time(row.actual?.receivedAt)}}</template></el-table-column>
    </el-table>
    <template v-if="canEdit">
      <h3>设置期望状态</h3><p class="muted">勾选属性并填写目标值。取消勾选并保存会移除该目标；发生版本冲突时先刷新再编辑。</p>
      <div v-for="p in editable" :key="p.id" class="shadow-field">
        <el-checkbox v-model="selected[p.id]" :disabled="busy" :aria-label="'设置 '+p.id+' 期望值'">{{p.id}}</el-checkbox>
        <el-select v-if="p.schema.type==='BOOLEAN'" v-model="values[p.id]" :disabled="!selected[p.id]||busy" :aria-label="p.id+' 期望值'"><el-option label="true" :value="true"/><el-option label="false" :value="false"/></el-select>
        <el-input v-else-if="p.schema.type==='TEXT'" v-model="values[p.id]" :maxlength="p.schema.maxLength??4096" :disabled="!selected[p.id]||busy" :aria-label="p.id+' 期望值'"/>
        <el-input-number v-else :model-value="typeof values[p.id]==='number'?Number(values[p.id]):undefined" @update:model-value="(v:number|undefined|null)=>{if(v!==undefined&&v!==null)values[p.id]=v}" :min="p.schema.minimum??undefined" :max="p.schema.maximum??undefined" :precision="p.schema.type==='INTEGER'?0:undefined" :disabled="!selected[p.id]||busy" :aria-label="p.id+' 期望值'"/>
      </div>
      <p v-if="!editable.length" class="muted">当前模型没有可设置期望值的读写属性。</p>
      <el-alert v-if="saveError" :title="saveError" type="error" :closable="false"/>
      <p class="muted">保存后会向在线设备下发选中的属性。设备离线时目标仍会保存，本版需在设备上线后手动再次下发；移除目标不发送设备消息。</p>
      <el-button v-if="editable.length" type="primary" :loading="busy" :disabled="pending" @click="save">保存期望状态</el-button>
    </template>
    <DownlinkResult v-if="downlink" :scope="resourceScope(scope,device)" :device-id="device.id" :initial="downlink" @pending="pending=$event"/>
  </div>
</template>
<style scoped>
.shadow-panel h2{font-size:16px}.shadow-panel .section-heading{margin-bottom:18px}.shadow-table{margin:20px 0 28px}.shadow-field{display:grid;grid-template-columns:180px minmax(160px,320px);align-items:center;gap:16px;margin:16px 0}.shadow-field .el-select{width:100%}.shadow-panel>.el-button{margin-top:14px}.muted{font-size:13px;color:#789188;line-height:1.8}@media(max-width:680px){.shadow-field{grid-template-columns:1fr;gap:6px}}
</style>

