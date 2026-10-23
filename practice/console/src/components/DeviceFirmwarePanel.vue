<script setup lang="ts">
import { onScopeDispose, ref, watch } from 'vue'
import { deviceFirmwareApi, type FirmwareInventory, type FirmwarePreflight } from '../api/deviceFirmware'
import { firmwareApi, type FirmwareChannel, type FirmwareVersion } from '../api/firmware'
import { resourceScope, type Device } from '../api/catalog'
import { errorMessage, isCancelled } from '../api/management'
const props=defineProps<{scope:string;device:Device;canReadProduct:boolean}>()
const inventory=ref<FirmwareInventory|null>(null),loading=ref(true),error=ref(''),targetError=ref(''),checking=ref(false),result=ref<FirmwarePreflight|null>(null)
const channels=ref<FirmwareChannel[]>([]),channel=ref<number|null>(null),versions=ref<FirmwareVersion[]>([]),versionId=ref(''),page=ref(1),total=ref(0),targetsLoading=ref(false)
let life=new AbortController(),generation=0,targetGeneration=0,checkGeneration=0
const date=(value:string)=>new Date(value).toLocaleString('zh-CN')
function invalidateCheck(){checkGeneration++;result.value=null;checking.value=false}
async function load(){
 const current=++generation;loading.value=true;error.value='';inventory.value=null;invalidateCheck()
 try{const snapshot=await deviceFirmwareApi.inventory(props.scope,props.device.id,life.signal);if(current!==generation)return;inventory.value=snapshot}
 catch(e){if(current===generation&&!isCancelled(e))error.value=errorMessage(e)}finally{if(current===generation)loading.value=false}
}
async function loadTargets(){
 const current=++targetGeneration;invalidateCheck();versions.value=[];versionId.value='';total.value=0;targetError.value=''
 if(channel.value===null||!props.canReadProduct)return
 targetsLoading.value=true
 try{const response=await firmwareApi.versions(resourceScope(props.scope,props.device),props.device.productId,channel.value,page.value,life.signal);if(current!==targetGeneration)return;versions.value=response.items;total.value=response.total}
 catch(e){if(current===targetGeneration&&!isCancelled(e))targetError.value=errorMessage(e)}finally{if(current===targetGeneration)targetsLoading.value=false}
}
async function loadChannels(){
 if(!props.canReadProduct)return
 const current=++targetGeneration;targetError.value=''
 try{const response=await firmwareApi.channels(resourceScope(props.scope,props.device),props.device.productId,life.signal);if(current!==targetGeneration)return;channels.value=response;channel.value=response[0]?.channel??null;await loadTargets()}
 catch(e){if(current===targetGeneration&&!isCancelled(e))targetError.value=errorMessage(e)}
}
async function check(){
 if(checking.value||channel.value===null||!versionId.value)return
 const current=++checkGeneration;checking.value=true;result.value=null;targetError.value=''
 try{const response=await deviceFirmwareApi.preflight(props.scope,props.device.id,channel.value,versionId.value,life.signal);if(current!==checkGeneration)return;result.value=response}
 catch(e){if(current===checkGeneration&&!isCancelled(e))targetError.value=errorMessage(e)}finally{if(current===checkGeneration)checking.value=false}
}
function chooseChannel(){page.value=1;void loadTargets()}
function refresh(){void load();void loadChannels()}
watch(()=>[props.scope,props.device.id,props.canReadProduct],()=>{life.abort();life=new AbortController();generation++;targetGeneration++;checkGeneration++;channels.value=[];channel.value=null;versions.value=[];versionId.value='';targetsLoading.value=false;page.value=1;refresh()},{immediate:true})
onScopeDispose(()=>{life.abort();generation++;targetGeneration++;checkGeneration++})
</script>
<template>
<section class="device-firmware">
 <div class="section-heading"><div><h2>实际固件与升级预检</h2><p class="muted">来自设备的实际报告，与生产计划和产品档案分别管理。</p></div><button type="button" :disabled="loading||checking" @click="refresh">刷新固件状态</button></div>
 <p v-if="loading" role="status">正在读取设备固件状态…</p>
 <p v-else-if="error" role="alert">固件状态加载失败：{{error}}</p>
 <template v-else-if="inventory">
  <p v-if="!inventory.information&&!inventory.components.length" class="muted">设备尚未上报固件信息。请发送 thing.info.report 和 ota.version.report。</p>
  <dl v-if="inventory.information" class="firmware-info"><dt>实际硬件版本</dt><dd>{{inventory.information.hardwareVersion}}</dd><dt>启动标识</dt><dd>{{inventory.information.bootId}}</dd><dt>SDK 版本</dt><dd>{{inventory.information.sdkVersion||'未上报'}}</dd><dt>设备时钟</dt><dd>{{inventory.information.clockSynced?'已同步':'未同步'}}</dd><dt>平台接收时间</dt><dd>{{date(inventory.information.receivedAt)}} <strong v-if="inventory.information.stale" class="invalid">已过期，请重新上报</strong></dd></dl>
  <div v-if="inventory.components.length" class="firmware-scroll"><table><thead><tr><th>组件</th><th>实际版本</th><th>来源</th><th>平台接收时间</th><th>信息状态</th></tr></thead><tbody><tr v-for="c in inventory.components" :key="c.component"><td>{{c.component}}</td><td>{{c.version}}</td><td>{{c.source}}</td><td>{{date(c.receivedAt)}}</td><td :class="{invalid:c.stale||c.beforeCurrentBoot}">{{c.beforeCurrentBoot?'早于本次启动':c.stale?'已过期':'时效内'}}</td></tr></tbody></table></div>
  <p class="muted">上报有效期 {{Math.round(inventory.maximumReportAgeSeconds/3600*10)/10}} 小时 · 状态修订 {{inventory.revision}}。平台接收时间用于排序，设备时间仅作参考。</p>
 </template>
 <div v-if="canReadProduct" class="preflight-panel">
  <h3>检查目标固件兼容性</h3><p class="muted">选择当前产品的固件通道与目标版本，检查源版本、硬件和上报时效。</p>
  <p v-if="targetError" role="alert">{{targetError}} <button type="button" :disabled="checking" @click="loadChannels">重新加载目标固件</button></p>
  <p v-if="!channels.length&&!targetError" class="muted">产品尚未配置固件通道。</p>
  <div v-if="channels.length" class="preflight-controls"><label>固件通道<select v-model="channel" aria-label="预检固件通道" :disabled="checking" @change="chooseChannel"><option v-for="c in channels" :key="c.channel" :value="c.channel">{{c.name}} · {{c.component}}</option></select></label><label>目标版本<select v-model="versionId" aria-label="预检目标版本" :disabled="targetsLoading||checking" @change="invalidateCheck"><option value="" disabled>请选择目标版本</option><option v-for="v in versions" :key="v.id" :value="v.id">{{v.version}} · {{v.enabled?'可用':'停用'}}</option></select></label><button type="button" :disabled="!versionId||checking||targetsLoading||loading||!!error" @click="check">{{checking?'检查中…':'检查升级兼容性'}}</button></div>
  <p v-if="targetsLoading" role="status">正在加载目标固件…</p>
  <p v-else-if="channels.length&&!versions.length&&!targetError" class="muted">该通道尚无固件版本。</p>
  <div v-if="total>20" class="preflight-controls"><button type="button" :disabled="page<=1||targetsLoading||checking" @click="page--;loadTargets()">上一页</button><span>第 {{page}} 页 · 共 {{total}} 个版本</span><button type="button" :disabled="page*20>=total||targetsLoading||checking" @click="page++;loadTargets()">下一页</button></div>
  <div v-if="result" class="preflight-result" :class="{invalid:!result.compatible}" aria-live="polite"><h3>{{result.compatible?'静态兼容检查通过':'暂不满足升级条件'}}</h3><p>{{result.component}}：{{result.currentVersion||'未上报'}} → {{result.targetVersion}} · 硬件 {{result.hardwareVersion||'未上报'}}</p><ul v-if="result.reasons.length"><li v-for="reason in result.reasons" :key="reason.code">{{reason.message}}</li></ul><ul v-if="result.warnings.length" class="preflight-warnings"><li v-for="warning in result.warnings" :key="warning.code">{{warning.message}}</li></ul><small>检查时间 {{date(result.checkedAt)}} · 状态修订 {{result.inventoryRevision}} · 目标修订 {{result.targetRevision}}</small></div>
  <p class="muted">预检不会下发升级；在线状态、安装条件及任务执行时的资格仍需重新确认。</p>
 </div>
</section>
</template>
<style scoped>
.device-firmware{padding-top:8px}.device-firmware h2{font-size:17px}.device-firmware h3{font-size:15px}.device-firmware button,.device-firmware select{font:inherit;padding:8px 12px;border:1px solid var(--line);border-radius:6px;background:var(--surface);color:var(--text-primary)}.device-firmware button{cursor:pointer}.device-firmware button:disabled{opacity:.5;cursor:default}.firmware-info{display:grid;grid-template-columns:130px 1fr;gap:10px;font-size:13px;line-height:1.8}.firmware-info dt{color:var(--text-secondary)}.firmware-info dd{margin:0;overflow-wrap:anywhere}.firmware-scroll{overflow-x:auto}.device-firmware table{width:100%;border-collapse:collapse;font-size:13px}.device-firmware th,.device-firmware td{text-align:left;border-bottom:1px solid var(--line);padding:12px;white-space:nowrap}.device-firmware th{color:var(--text-secondary);font-weight:500}.device-firmware [role=alert],.invalid{color:var(--el-color-danger)}.preflight-panel{border-top:1px solid var(--line);margin-top:24px;padding-top:16px}.preflight-controls{display:flex;gap:12px;align-items:end;flex-wrap:wrap;margin:16px 0}.preflight-controls label{display:grid;gap:8px;font-size:13px}.preflight-controls select{min-width:170px;max-width:300px}.preflight-result{padding:16px 20px;border:1px solid var(--line);border-radius:8px;background:var(--surface-secondary);margin:18px 0}.preflight-result ul{padding-left:20px;line-height:1.9}.preflight-warnings{color:var(--text-secondary)}@media(max-width:600px){.firmware-info{grid-template-columns:1fr}.preflight-controls{align-items:stretch;flex-direction:column}.preflight-controls select{max-width:100%}}
</style>
