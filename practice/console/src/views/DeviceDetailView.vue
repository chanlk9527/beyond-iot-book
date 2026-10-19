<script setup lang="ts">
import { computed, onScopeDispose, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { catalogApi, catalogScope, resourceScope, type Credential, type DeviceDetail } from '../api/catalog'
import { snapshotRefresh } from '../realtime/snapshotRefresh'
import { useDeviceSubscription,readPresence } from '../realtime/deviceSubscription'
import { errorMessage, isCancelled } from '../api/management'
import { useSessionStore } from '../stores/session'
import CatalogNavigation from '../components/CatalogNavigation.vue'
import StatePanel from '../components/StatePanel.vue'
import DeviceShadowPanel from '../components/DeviceShadowPanel.vue'
import DeviceCapabilitiesPanel from '../components/DeviceCapabilitiesPanel.vue'
import DeviceCommandPanel from '../components/DeviceCommandPanel.vue'
import DeviceLivePanel from '../components/DeviceLivePanel.vue'
import DeviceHistoryPanel from '../components/DeviceHistoryPanel.vue'

const route=useRoute(), router=useRouter(), session=useSessionStore()
const scope=catalogScope(route.meta.catalogPlatform===true,route.params.tenantId), id=String(route.params.id)
const detail=ref<DeviceDetail|null>(null), loading=ref(true), error=ref(''), actionError=ref(''), busy=ref(false), credential=ref<Credential|null>(null)
const editName=ref(''), editLifecycle=ref(''), activeTab=ref('overview')
const life=new AbortController()
const presence=ref('UNKNOWN')
let presenceRevision=0
const presenceRefresh=snapshotRefresh(refreshPresence)
async function refreshPresence(){if(!detail.value)return;const current=++presenceRevision;try{const result=await readPresence({tenantId:detail.value.device.tenantId,deviceId:id},life.signal);if(current===presenceRevision&&!life.signal.aborted)presence.value=result.state}catch{if(current===presenceRevision)presence.value='UNKNOWN'}}
useDeviceSubscription(()=>detail.value?[{tenantId:detail.value.device.tenantId,deviceId:id}]:[],notice=>{
 if(notice.type==='disconnected'){presenceRevision++;presenceRefresh.cancel();presence.value='UNKNOWN';return}
 if(notice.type!=='resync'&&notice.kind!=='presence')return
 presenceRefresh.schedule()
})
const writable=computed(()=>!!session.account?.platformAdmin||(route.meta.catalogPlatform!==true&&['OWNER','ADMIN'].includes((session.tenant?.id===route.params.tenantId?session.tenant.role:undefined)||'')))
const canWrite=computed(()=>writable.value&&session.can('device:write'))
const canCredential=computed(()=>session.can('device:credential'))
const states=[{value:'ACTIVE',label:'启用'},{value:'DISABLED',label:'停用'},{value:'RETIRED',label:'退役'}]
const stateName=(v:string)=>states.find(s=>s.value===v)?.label||v
const stateType=(v:string)=>v==='ACTIVE'?'success':v==='DISABLED'?'warning':v==='RETIRED'?'info':'primary'
async function load(){loading.value=true;error.value='';try{const result=await catalogApi.detail(scope,id,life.signal);detail.value=result;presenceRefresh.schedule();editName.value=result.device.name;editLifecycle.value=result.device.lifecycle}catch(e){if(!isCancelled(e))error.value=errorMessage(e)}finally{loading.value=false}}
async function save(){if(!detail.value||busy.value||!canWrite.value)return;busy.value=true;actionError.value='';try{const result=await catalogApi.updateDevice(scope,{...detail.value.device,name:editName.value,lifecycle:editLifecycle.value},life.signal);detail.value.device=result;editName.value=result.name;editLifecycle.value=result.lifecycle;ElMessage.success('设备信息已保存')}catch(e){if(!isCancelled(e))actionError.value=errorMessage(e)}finally{busy.value=false}}
async function remove(){if(!detail.value||busy.value||!canWrite.value||detail.value.device.lifecycle!=='DISABLED')return;try{await ElMessageBox.confirm(`确定删除设备“${detail.value.device.name}”？删除后设备身份、凭证和管理记录将无法从设备列表恢复。`,'删除设备',{confirmButtonText:'删除设备',cancelButtonText:'取消',type:'warning'})}catch{return}busy.value=true;actionError.value='';try{await catalogApi.removeDevice(scope,detail.value.device,life.signal);ElMessage.success('设备已删除');await router.push(resourceScope(scope,detail.value.device)+'/devices')}catch(e){if(!isCancelled(e))actionError.value=errorMessage(e)}finally{busy.value=false}}
async function viewCredential(){if(!detail.value||busy.value)return;busy.value=true;actionError.value='';try{credential.value=await catalogApi.credential(scope,detail.value.device,life.signal)}catch(e){if(!isCancelled(e))actionError.value=errorMessage(e)}finally{busy.value=false}}
async function rotate(){if(!detail.value||busy.value)return;busy.value=true;actionError.value='';try{credential.value=await catalogApi.rotate(scope,detail.value.device,life.signal);const refreshed=await catalogApi.detail(scope,id,life.signal);detail.value=refreshed;editName.value=refreshed.device.name;editLifecycle.value=refreshed.device.lifecycle;ElMessage.success('设备凭证已轮换')}catch(e){if(!isCancelled(e))actionError.value=errorMessage(e)}finally{busy.value=false}}
onScopeDispose(()=>{life.abort();presenceRefresh.dispose()}); void load()
</script>
<template>
  <CatalogNavigation/>
  <div v-if="loading" class="panel device-detail-loading"><el-skeleton :rows="8" animated/></div>
  <section v-else-if="error" class="panel"><StatePanel title="设备详情加载失败" :description="error"><el-button @click="load">重试</el-button></StatePanel></section>
  <template v-else-if="detail">
    <div class="page-heading device-heading"><div><p class="eyebrow">DEVICE DETAIL / {{detail.device.code}}</p><h1>{{detail.device.name}}</h1><p>{{detail.product.name}} · 模型 {{detail.device.modelVersion}} · <el-tag :type="stateType(detail.device.lifecycle)" effect="plain">{{stateName(detail.device.lifecycle)}}</el-tag></p></div><div class="device-heading-actions"><el-button @click="router.push(resourceScope(scope,detail.device)+'/devices')">返回设备列表</el-button><el-button v-if="canCredential" :disabled="busy" @click="viewCredential">查看凭证</el-button><el-button v-if="canCredential&&detail.device.lifecycle!=='RETIRED'" :disabled="busy" @click="rotate">轮换凭证</el-button></div></div>
    <el-alert v-if="actionError" :title="actionError" type="error" :closable="false" class="detail-alert"/>
    <section class="device-hero panel"><div class="device-avatar">{{detail.device.name.slice(0,1).toUpperCase()}}</div><div><p class="eyebrow">IDENTITY</p><h2>{{detail.device.code}}</h2><p class="muted">设备 ID：<span class="mono">{{detail.device.id}}</span></p></div><div class="device-hero-stat"><span>凭证轮换序号</span><strong>v{{detail.device.credentialVersion}}</strong></div><div class="device-hero-stat"><span>接入状态</span><strong :class="{'status-muted':presence!=='ONLINE'}">{{presence==='ONLINE'?'在线':presence==='OFFLINE'?'离线':'未知'}}</strong></div></section>
    <section class="panel device-detail-panel"><el-tabs v-model="activeTab"><el-tab-pane name="overview" label="设备概览"/><el-tab-pane name="state" label="设备状态"/><el-tab-pane name="history" label="设备历史"/><el-tab-pane name="call" label="模拟功能调用"/><el-tab-pane v-if="session.can('video:watch')" name="live" label="音视频直播"/><el-tab-pane name="events" label="事件列表"/><el-tab-pane name="capability" label="能力模型"/><el-tab-pane name="management" label="生命周期管理"/></el-tabs>
      <div v-if="activeTab==='overview'" class="device-overview"><div><h2>身份信息</h2><dl class="detail-grid"><div><dt>设备名称</dt><dd>{{detail.device.name}}</dd></div><div><dt>设备编码</dt><dd class="mono">{{detail.device.code}}</dd></div><div><dt>所属产品</dt><dd>{{detail.product.name}} <span class="muted">({{detail.product.code}})</span></dd></div><div><dt>当前绑定模型版本</dt><dd>{{detail.model.version}}</dd></div><div><dt>数据归属</dt><dd>{{detail.device.tenantId||'平台目录'}}</dd></div><div><dt>编辑修订号</dt><dd>revision {{detail.device.revision}}</dd></div></dl></div></div>
      <DeviceShadowPanel v-else-if="activeTab==='state'" :scope="scope" :device="detail.device" :model="detail.model.model" :writable="canWrite&&detail.product.enabled"/>
      <DeviceHistoryPanel v-else-if="activeTab==='history'" :scope="scope" :device="detail.device" :model="detail.model.model"/>
      <DeviceCommandPanel v-else-if="activeTab==='call'" :scope="scope" :device="detail.device" :model="detail.model.model" :writable="canWrite&&detail.product.enabled"/>
      <DeviceLivePanel v-else-if="activeTab==='live'&&session.can('video:watch')" :device="detail.device" :enabled="detail.device.lifecycle==='ACTIVE'&&detail.product.enabled"/>
      <DeviceHistoryPanel v-else-if="activeTab==='events'" :scope="scope" :device="detail.device" :model="detail.model.model" initial-kind="EVENT" event-only/>
      <DeviceCapabilitiesPanel v-else-if="activeTab==='capability'" :detail="detail" :scope="scope"/>
      <div v-else class="management-view"><div class="section-heading"><div><h2>生命周期管理</h2><p>停用后可删除设备。</p></div><div class="management-actions"><el-button v-if="canWrite" type="primary" :loading="busy" :disabled="!editName.trim()" @click="save">保存修改</el-button><el-button v-if="canWrite&&detail.device.lifecycle==='DISABLED'" type="danger" plain :loading="busy" @click="remove">删除设备</el-button></div></div><div class="management-form"><label>设备名称</label><el-input v-model="editName" maxlength="100" :disabled="!canWrite||busy"/><label>生命周期</label><el-select v-model="editLifecycle" :disabled="!canWrite||busy"><el-option v-for="state in states" :key="state.value" :label="state.label" :value="state.value"/></el-select></div></div>
    </section>
  </template>
  <el-dialog :model-value="!!credential" title="设备凭证" width="min(600px,94vw)" :close-on-click-modal="false" @update:model-value="(open:boolean)=>{if(!open)credential=null}"><div v-if="credential" class="tenant-form"><el-alert title="轮换后旧凭证立即失效。" type="info" :closable="false"/><label>ClientId</label><el-input :model-value="credential.clientId" readonly/><label>设备用户名</label><el-input :model-value="credential.username" readonly/><label>设备密钥</label><el-input :model-value="credential.secret" readonly/><div class="tenant-form-actions"><el-button type="primary" @click="credential=null">关闭</el-button></div></div></el-dialog>
</template>
<style scoped>
.device-detail-loading{padding:34px}.device-heading{margin-bottom:22px}.device-heading-actions{display:flex;gap:10px;flex-wrap:wrap}.detail-alert{margin-bottom:18px}.device-hero{display:flex;align-items:center;gap:18px;padding:25px 29px;margin-bottom:20px}.device-avatar{width:56px;height:56px;display:grid;place-items:center;border-radius:15px;background:#e6f2eb;color:#35745e;font-size:24px;font-weight:600}.device-hero h2{font-size:20px;margin:8px 0}.device-hero-stat{margin-left:auto;border-left:1px solid #e6eeea;padding-left:28px;min-width:110px}.device-hero-stat span{display:block;color:#8b9f97;font-size:11px}.device-hero-stat strong{display:block;margin-top:9px;font-size:18px;color:#3a765f}.device-hero-stat+.device-hero-stat{margin-left:0}.status-muted{color:#849a9d!important;font-size:14px!important}.device-detail-panel{padding:0 28px 30px}.device-detail-panel :deep(.el-tabs__header){margin:0 -28px 25px;padding:0 28px}.device-overview{display:block}.device-overview h2,.management-view h2{font-size:16px;margin:4px 0}.device-note{background:#f4f8f5;border:1px solid #dcebe1;border-radius:9px;padding:25px}.device-note h3{font-size:17px;margin:13px 0}.device-note p:not(.eyebrow){color:#789188;font-size:12px;line-height:1.8;margin-bottom:18px}.management-view{max-width:700px}.management-view .section-heading{margin-bottom:25px}.management-actions{display:flex;gap:10px;flex-wrap:wrap}.management-form{display:grid;grid-template-columns:120px 1fr;gap:15px;align-items:center}.management-form label{color:#536f68;font-size:13px}.management-form .el-select{width:100%}.muted{color:#8a9c97}.mono{font-family:Consolas,monospace;font-size:12px;overflow-wrap:anywhere}
.runtime-view{min-height:300px}.runtime-view .section-heading{margin-bottom:20px}.runtime-view :deep(.el-table){border:1px solid #e4ece8;border-radius:8px}.todo-view{max-width:720px;border:1px dashed #cbded3;background:#f7faf8;border-radius:10px;padding:36px}.todo-view .todo-icon{width:44px;height:44px;display:grid;place-items:center;border-radius:12px;background:#e4f1e9;color:#41836a;font-size:25px;margin-bottom:18px}.todo-view h2{font-size:21px;margin:12px 0}.todo-view>p:not(.eyebrow){color:#789188;font-size:13px;line-height:1.9;margin-bottom:20px}.operation-list{margin-top:24px;border-top:1px solid #dfebe4}.operation-list>div{display:flex;align-items:center;justify-content:space-between;padding:15px 0;border-bottom:1px solid #dfebe4}.operation-list strong{font-family:Consolas,monospace;font-size:12px;color:#53766a}
@media(max-width:760px){.device-hero{align-items:flex-start;flex-wrap:wrap}.device-hero-stat{margin-left:0!important;padding-left:0;border:0;min-width:100px}.device-overview{grid-template-columns:1fr}.device-detail-panel{padding:0 20px 24px}.device-detail-panel :deep(.el-tabs__header){margin-left:-20px;margin-right:-20px;padding:0 20px}.management-form{grid-template-columns:1fr}}
</style>
