<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import { catalogApi, resourceScope, type Device, type ModelSpec, type ShadowScalar, type DownlinkStatus } from '../api/catalog'
import { errorMessage, isCancelled } from '../api/management'
import DeviceValueInput from './DeviceValueInput.vue'
import DownlinkResult from './DownlinkResult.vue'
const props=defineProps<{scope:string;device:Device;model:ModelSpec;writable:boolean}>()
type Schema={type:string;minimum?:number|null;maximum?:number|null;maxLength?:number|null}
type Field={id:string;schema:Schema;required?:boolean;access?:string}
type Operation={id:string;inputs:Record<string,Field>}
const operations=computed(()=>Object.values(props.model.operations) as Operation[])
const properties=computed(()=>(Object.values(props.model.properties) as Field[]).filter(p=>p.access!=='READ_ONLY'))
const operationId=ref(operations.value[0]?.id||'')
const fields=computed(()=>Object.values(operations.value.find(o=>o.id===operationId.value)?.inputs||{}))
const inputs=ref<Record<string,ShadowScalar>>({}), included=ref<Record<string,boolean>>({})
const propertyValues=ref<Record<string,ShadowScalar>>({}), selected=ref<Record<string,boolean>>({})
const busy=ref(false),pending=ref(false),error=ref(''),result=ref<DownlinkStatus|null>(null)
const life=new AbortController()
const scope=computed(()=>resourceScope(props.scope,props.device))
const canSend=computed(()=>props.writable&&props.device.lifecycle==='ACTIVE'&&!busy.value&&!pending.value)
const initial=(s:Schema):ShadowScalar=>s.type==='BOOLEAN'?false:s.type==='TEXT'?'':s.minimum??0
watch(fields,value=>{inputs.value={};included.value={};for(const f of value){inputs.value[f.id]=initial(f.schema);included.value[f.id]=!!f.required}},{immediate:true})
watch(properties,value=>{for(const p of value)propertyValues.value[p.id]??=initial(p.schema)},{immediate:true})
async function send(kind:'operation'|'property'){
 if(!canSend.value)return
 busy.value=true;error.value='';result.value=null
 try{
  if(kind==='operation'){
   const values:Record<string,ShadowScalar>={}
   for(const f of fields.value)if(f.required||included.value[f.id])values[f.id]=inputs.value[f.id]!
   result.value=await catalogApi.invoke(scope.value,props.device.id,operationId.value,values,life.signal)
  }else{
   const values:Record<string,ShadowScalar>={}
   for(const p of properties.value)if(selected.value[p.id])values[p.id]=propertyValues.value[p.id]!
   if(!Object.keys(values).length){error.value='请选择需要下发的属性';return}
   result.value=await catalogApi.writeProperties(scope.value,props.device.id,values,life.signal)
  }
 }catch(e){if(!isCancelled(e))error.value=errorMessage(e)}finally{busy.value=false}
}
onScopeDispose(()=>life.abort())
</script>
<template>
 <div class="command-panel">
  <h2>设备控制</h2>
  <el-alert v-if="!writable||device.lifecycle!=='ACTIVE'" title="当前设备不可控制，请检查操作权限及设备、产品启用状态。" type="info" :closable="false"/>
  <h3>属性写入</h3><p>直接下发，不修改期望状态。</p>
  <div v-for="p in properties" :key="p.id" class="command-field">
   <el-checkbox v-model="selected[p.id]" :disabled="!canSend">{{p.id}}</el-checkbox>
   <DeviceValueInput v-model="propertyValues[p.id]" :schema="p.schema" :disabled="!canSend||!selected[p.id]" :label="p.id+' 写入值'"/>
  </div>
  <p v-if="!properties.length">当前模型没有可写属性。</p>
  <el-button v-if="properties.length" type="primary" :disabled="!canSend" :loading="busy" @click="send('property')">下发属性</el-button>
  <h3>物模型操作</h3>
  <template v-if="operations.length">
   <el-select v-model="operationId" :disabled="!canSend" aria-label="物模型操作"><el-option v-for="o in operations" :key="o.id" :value="o.id" :label="o.id"/></el-select>
   <div v-for="f in fields" :key="operationId+f.id" class="command-field">
    <span v-if="f.required">{{f.id}}（必填）</span><el-checkbox v-else v-model="included[f.id]" :disabled="!canSend">{{f.id}}（选填）</el-checkbox>
    <DeviceValueInput v-model="inputs[f.id]" :schema="f.schema" :disabled="!canSend||(!f.required&&!included[f.id])" :label="f.id+' 参数'"/>
   </div>
   <p v-if="!fields.length">此操作无需输入参数。</p>
   <el-button type="primary" :disabled="!canSend||!operationId" :loading="busy" @click="send('operation')">调用功能</el-button>
  </template><p v-else>当前模型没有可调用功能。</p>
  <el-alert v-if="error" :title="error" type="error" :closable="false"/>
  <DownlinkResult v-if="result" :scope="scope" :device-id="device.id" :initial="result" @pending="pending=$event"/>
 </div>
</template>
<style scoped>
.command-panel{max-width:800px}.command-panel h2{font-size:16px}.command-panel h3{font-size:14px;margin:28px 0 16px}.command-panel p{font-size:13px;line-height:1.8;color:#789188}.command-field{display:grid;grid-template-columns:200px minmax(160px,320px);gap:16px;align-items:center;margin:16px 0}.command-panel>.el-select{width:320px}.command-panel>.el-alert{margin-top:16px}@media(max-width:600px){.command-field{grid-template-columns:1fr}.command-panel>.el-select{width:100%}}
</style>
