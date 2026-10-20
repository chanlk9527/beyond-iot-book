<script setup lang="ts">
import { computed, onScopeDispose, ref } from 'vue'
import { useRouter } from 'vue-router'
import { catalogApi, resourceScope, type Device, type DeviceDetail, type GatewayTopology } from '../api/catalog'
import { errorMessage, isCancelled } from '../api/management'
const props=defineProps<{scope:string;detail:DeviceDetail;writable:boolean}>()
const emit=defineEmits<{changed:[]}>()
const router=useRouter(), life=new AbortController()
const data=ref<GatewayTopology|null>(null), loading=ref(false), busy=ref(false), error=ref(''), targetId=ref(''), page=ref(1)
const isGateway=computed(()=>props.detail.product.configuration.deviceType==='GATEWAY')
const actualScope=computed(()=>resourceScope(props.scope,props.detail.device))
async function load(){loading.value=true;error.value='';try{data.value=await catalogApi.topology(actualScope.value,props.detail.device.id,page.value,life.signal)}catch(e){if(!isCancelled(e))error.value=errorMessage(e)}finally{loading.value=false}}
async function bind(){if(!targetId.value.trim()||busy.value)return;busy.value=true;error.value='';try{
 const child=isGateway.value?(await catalogApi.detail(actualScope.value,targetId.value.trim(),life.signal)).device:props.detail.device
 await catalogApi.bindGateway(actualScope.value,child,isGateway.value?props.detail.device.id:targetId.value.trim(),life.signal)
 targetId.value='';await load();emit('changed')
}catch(e){if(!isCancelled(e))error.value=errorMessage(e)}finally{busy.value=false}}
async function unbind(child:Device,gatewayId:string){if(busy.value)return;busy.value=true;error.value='';try{
 await catalogApi.unbindGateway(actualScope.value,child,gatewayId,life.signal);await load();emit('changed')
}catch(e){if(!isCancelled(e))error.value=errorMessage(e)}finally{busy.value=false}}
function open(device:Device){void router.push(`${resourceScope(props.scope,device)}/devices/${encodeURIComponent(device.id)}`)}
onScopeDispose(()=>life.abort());void load()
</script>
<template>
 <section class="topology-panel" aria-label="网关拓扑">
  <div class="section-heading"><div><h2>{{isGateway?'下挂子设备':'所属网关'}}</h2><p>{{isGateway?'网关连接与子设备在线状态分别管理。':'子设备通过已绑定网关接入，不能使用自身凭证直接连接云端。'}}</p></div><el-button :disabled="loading||busy" @click="load">刷新拓扑</el-button></div>
  <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon/>
  <el-skeleton v-if="loading" :rows="3" animated/>
  <template v-else-if="data">
   <template v-if="isGateway">
    <p>已绑定 {{data.children.total}} 台子设备</p>
    <el-table :data="data.children.items"><el-table-column prop="name" label="设备名称"/><el-table-column prop="id" label="设备 ID" min-width="180"/><el-table-column prop="productName" label="产品"/><el-table-column label="操作" width="150"><template #default="{row}"><el-button link type="primary" @click="open(row)">查看</el-button><el-button v-if="writable" link type="danger" :disabled="busy" @click="unbind(row,detail.device.id)">解绑</el-button></template></el-table-column></el-table>
    <el-pagination v-if="data.children.total>20" v-model:current-page="page" :total="data.children.total" :page-size="20" layout="prev,pager,next" @current-change="load"/>
   </template>
   <div v-else-if="data.gateway" class="bound-gateway"><el-button link type="primary" @click="open(data.gateway)">{{data.gateway.name}} · {{data.gateway.id}}</el-button><el-button v-if="writable" type="danger" plain :disabled="busy" @click="unbind(detail.device,data.gateway.id)">解绑网关</el-button></div>
   <p v-else class="muted">尚未绑定网关。</p>
   <form v-if="writable&&(isGateway||!data.gateway)" class="binding-form" @submit.prevent="bind"><label for="topology-target">{{isGateway?'子设备 ID':'网关设备 ID'}}</label><el-input id="topology-target" v-model="targetId" maxlength="36" :disabled="busy" :placeholder="isGateway?'输入同一租户下的子设备 ID':'输入同一租户下的网关设备 ID'"/><el-button type="primary" native-type="submit" :disabled="!targetId.trim()" :loading="busy">{{isGateway?'绑定子设备':'绑定网关'}}</el-button></form>
  </template>
 </section>
</template>
<style scoped>
.topology-panel h2{font-size:16px}.topology-panel .el-alert{margin-bottom:16px}.binding-form{display:flex;align-items:center;gap:12px;margin-top:24px;max-width:700px}.binding-form label{white-space:nowrap;font-size:13px}.bound-gateway{display:flex;align-items:center;gap:20px;flex-wrap:wrap}.el-pagination{margin-top:16px}@media(max-width:680px){.binding-form{flex-wrap:wrap}.binding-form .el-input{flex-basis:100%}}
</style>