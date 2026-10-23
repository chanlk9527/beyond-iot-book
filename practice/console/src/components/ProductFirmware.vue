<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import { firmwareApi, type FirmwareChannel, type FirmwareVersion, type NewFirmwareVersion, type FirmwareFile, type FirmwareFileCapabilities } from '../api/firmware'
import { errorMessage, isCancelled } from '../api/management'
const props=defineProps<{scope:string;productId:string;enabled:boolean;writable:boolean;publishable:boolean}>()
const emit=defineEmits<{dirty:[value:boolean];busy:[value:boolean]}>()
const channels=ref<FirmwareChannel[]>([]),selected=ref<number|null>(null),versions=ref<FirmwareVersion[]>([])
const page=ref(1),total=ref(0),loading=ref(true),versionsLoading=ref(false),busy=ref(false),error=ref(''),versionError=ref(''),actionError=ref('')
const addingChannel=ref(false),addingVersion=ref(false)
const channelForm=ref({channel:0,component:'main',name:'主模块固件',versionRule:'TUYA' as 'TUYA'|'OPAQUE'})
const freshVersion=()=>({version:'',description:'',sources:'',hardware:'',url:'',size:1,sha256:'',keyId:'',signature:''})
const versionForm=ref(freshVersion())
const capabilities=ref<FirmwareFileCapabilities>({enabled:false,maximumUploadBytes:0,signingKeyIds:[]})
const packageMode=ref<'upload'|'external'>('external'),file=ref<File|null>(null),uploaded=ref<FirmwareFile|null>(null)
const uploadPhase=ref(''),downloadLinks=ref<Record<string,{url:string;expiresAt:string}>>({})
watch([file,()=>versionForm.value.keyId,()=>versionForm.value.signature],()=>{uploaded.value=null})
const channel=computed(()=>channels.value.find(c=>c.channel===selected.value))
watch([addingChannel,addingVersion],()=>emit('dirty',addingChannel.value||addingVersion.value))
watch(busy,value=>emit('busy',value))
const life=new AbortController();let listGeneration=0,versionGeneration=0
onScopeDispose(()=>{life.abort();listGeneration++;versionGeneration++})
async function load(){
  const generation=++listGeneration;loading.value=true;error.value=''
  try{const [result,files]=await Promise.all([firmwareApi.channels(props.scope,props.productId,life.signal),firmwareApi.capabilities(props.scope,props.productId,life.signal)]);if(generation!==listGeneration)return;capabilities.value=files;packageMode.value=files.enabled?'upload':'external';channels.value=result;if(!result.some(c=>c.channel===selected.value))selected.value=result[0]?.channel??null;await loadVersions()}
  catch(e){if(generation===listGeneration&&!isCancelled(e))error.value=errorMessage(e)}
  finally{if(generation===listGeneration)loading.value=false}
}
async function loadVersions(){
  const generation=++versionGeneration;versions.value=[];total.value=0;versionError.value='';versionsLoading.value=false
  if(selected.value===null)return
  versionsLoading.value=true
  try{const result=await firmwareApi.versions(props.scope,props.productId,selected.value,page.value,life.signal);if(generation!==versionGeneration)return;versions.value=result.items;total.value=result.total}
  catch(e){if(generation===versionGeneration&&!isCancelled(e))versionError.value=errorMessage(e)}
  finally{if(generation===versionGeneration)versionsLoading.value=false}
}
function chooseChannel(){page.value=1;void loadVersions()}
function suggestComponent(){const standard:Record<number,[string,string]>={0:['main','主模块固件'],1:['bluetooth','蓝牙固件'],3:['zigbee','Zigbee 固件'],9:['mcu','MCU 固件']};const value=standard[channelForm.value.channel];channelForm.value.component=value?.[0]??'';channelForm.value.name=value?.[1]??''}
async function saveChannel(){
  if(busy.value||!props.writable||!props.enabled)return
  busy.value=true;actionError.value=''
  try{const c=await firmwareApi.createChannel(props.scope,props.productId,{...channelForm.value},life.signal);channels.value=[...channels.value,c].sort((a,b)=>a.channel-b.channel);selected.value=c.channel;page.value=1;addingChannel.value=false;await loadVersions()}
  catch(e){if(!isCancelled(e))actionError.value=errorMessage(e)}finally{busy.value=false}
}
const split=(value:string)=>value.split(/[,，\n]/).map(s=>s.trim()).filter(Boolean)
async function saveVersion(){
  if(busy.value||selected.value===null||!props.writable||!props.enabled)return
  if(packageMode.value==='upload'&&(!file.value||!capabilities.value.enabled)){actionError.value='请选择固件文件并启用文件存储。';return}
  if(packageMode.value==='upload'&&file.value!.size>capabilities.value.maximumUploadBytes){actionError.value='固件文件超过允许上传大小。';return}
  busy.value=true;actionError.value='';const form=versionForm.value
  const value:NewFirmwareVersion={version:form.version,description:form.description,sourceVersions:split(form.sources),hardwareVersions:split(form.hardware),artifact:{url:form.url,size:form.size,sha256:form.sha256,keyId:form.keyId,signature:form.signature}}
  try{
    if(packageMode.value==='upload'){
      uploadPhase.value='正在上传并校验固件签名…'
      if(!uploaded.value)uploaded.value=await firmwareApi.upload(props.scope,props.productId,selected.value,file.value!,form.keyId,form.signature,life.signal)
      value.artifact={url:null,size:uploaded.value.size,sha256:uploaded.value.sha256,keyId:uploaded.value.keyId,signature:form.signature,fileId:uploaded.value.fileId}
    }
    uploadPhase.value='正在保存固件版本…'
    await firmwareApi.createVersion(props.scope,props.productId,selected.value,value,life.signal);addingVersion.value=false;versionForm.value=freshVersion();file.value=null;uploaded.value=null;page.value=1;await loadVersions()
  }
  catch(e){if(!isCancelled(e))actionError.value=errorMessage(e)}finally{busy.value=false;uploadPhase.value=''}
}
function chooseFile(event:Event){file.value=(event.target as HTMLInputElement).files?.[0]??null;uploaded.value=null}
async function download(v:FirmwareVersion){
  if(busy.value)return;busy.value=true;actionError.value=''
  try{downloadLinks.value[v.id]=await firmwareApi.download(props.scope,props.productId,v,life.signal)}
  catch(e){if(!isCancelled(e))actionError.value=errorMessage(e)}finally{busy.value=false}
}
async function toggle(v:FirmwareVersion){
  if(busy.value||!props.publishable||!v.enabled&&!props.enabled)return
  busy.value=true;actionError.value=''
  try{const result=await firmwareApi.status(props.scope,props.productId,v,life.signal);versions.value=versions.value.map(row=>row.id===result.id?result:row)}
  catch(e){if(!isCancelled(e))actionError.value=errorMessage(e)}finally{busy.value=false}
}
void load()
</script>
<template>
<div class="firmware-panel">
  <div class="section-heading"><div><h2>固件管理</h2><p class="muted">按通道管理固件。登记与启用版本后，还需创建升级任务并完成设备验证。</p></div><button type="button" :disabled="busy||loading||addingChannel||addingVersion" @click="load">刷新固件列表</button></div>
  <p class="firmware-notice">支持上传完整固件包或登记外部 HTTPS 地址。上传文件会计算大小与 SHA-256，并验证签名；外部地址仅校验信息格式。启用仅表示版本可供后续任务选择，不代表完成设备验证或下发升级。</p>
  <p v-if="!enabled" class="firmware-notice">产品已停用，可查看固件和停用版本；启用产品后可继续登记。</p>
  <p v-if="loading" role="status">正在加载固件…</p>
  <p v-else-if="error" role="alert">固件加载失败：{{error}}</p>
  <template v-else>
    <p v-if="actionError" role="alert">{{actionError}}</p>
    <div class="firmware-toolbar"><label>升级通道 <select v-model="selected" aria-label="升级通道" :disabled="busy||addingVersion" @change="chooseChannel"><option v-for="c in channels" :key="c.channel" :value="c.channel">{{c.name}}（{{c.channel}} · {{c.component}}）</option></select></label><button v-if="writable" type="button" :disabled="busy||!enabled||channels.length>=16||addingVersion" @click="addingChannel=true;actionError=''">新增通道</button><button v-if="writable&&channel" type="button" :disabled="busy||!enabled||addingChannel" @click="addingVersion=true;actionError=''">登记固件版本</button></div>
    <form v-if="addingChannel" class="firmware-form" @submit.prevent="saveChannel">
      <h3>新增升级通道</h3><p class="muted">通道、组件标识和版本规则创建后固定，每个产品最多16个通道。</p>
      <fieldset :disabled="busy||!enabled||!writable">
        <label>通道编号<input v-model.number="channelForm.channel" aria-label="通道编号" type="number" min="0" max="255" required @change="suggestComponent"></label>
        <small>0 主模块 · 1 蓝牙 · 3 Zigbee · 9 MCU · 10～255 扩展</small>
        <label>组件标识<input v-model="channelForm.component" aria-label="组件标识" maxlength="64" pattern="[a-z][a-z0-9_]{0,63}" required></label>
        <label>通道名称<input v-model="channelForm.name" aria-label="通道名称" maxlength="100" required></label>
        <label>版本规则<select v-model="channelForm.versionRule" aria-label="版本规则"><option value="TUYA">标准三段数字（0.0.0～99.99.99）</option><option value="OPAQUE">自定义版本（显式指定源版本）</option></select></label>
        <button type="submit">创建通道</button>
      </fieldset><button type="button" :disabled="busy" @click="addingChannel=false">取消新增通道</button>
    </form>
    <p v-if="!channels.length&&!addingChannel" class="muted">尚无固件通道，先为产品创建主模块或 MCU 通道。</p>
    <template v-if="channel">
      <p class="muted">{{channel.versionRule==='TUYA'?'标准三段数字版本，按数字大小比较。':'自定义版本不自动比较大小，必须明确允许的源版本。'}}版本、包信息及兼容范围登记后不能覆盖。</p>
      <form v-if="addingVersion" class="firmware-form" @submit.prevent="saveVersion">
        <h3>登记 {{channel.name}} 版本</h3>
        <fieldset :disabled="busy||!enabled||!writable">
          <label>固件版本<input v-model="versionForm.version" aria-label="固件版本" maxlength="100" :placeholder="channel.versionRule==='TUYA'?'例如 1.2.0':'例如 A7_R12'" required></label>
          <label>允许的源版本<textarea v-model="versionForm.sources" aria-label="允许的源版本" placeholder="必填，多个版本以逗号或换行分隔" required/></label>
          <label>适用硬件版本<textarea v-model="versionForm.hardware" aria-label="适用硬件版本" placeholder="必填，例如 HW-A、HW-B，以逗号或换行分隔" required/></label>
          <label>更新说明<textarea v-model="versionForm.description" aria-label="更新说明" maxlength="1000"/></label>
          <label>固件包来源<select v-model="packageMode" aria-label="固件包来源"><option value="upload" :disabled="!capabilities.enabled">上传至对象存储</option><option value="external">登记外部 HTTPS 地址</option></select></label>
          <small v-if="!capabilities.enabled">平台文件存储尚未启用，当前可登记已有 HTTPS 地址。</small>
          <template v-if="packageMode==='upload'">
            <label>固件文件<input type="file" aria-label="固件文件" required @change="chooseFile"></label>
            <small>最大 {{Math.floor(capabilities.maximumUploadBytes/1024/1024)}} MiB。{{file?`已选 ${file.size} 字节。`:''}}大小与 SHA-256 由服务端计算。</small>
            <small v-if="!capabilities.signingKeyIds.length">尚未配置可信签名公钥，请联系平台管理员配置后上传。</small>
            <label>签名密钥标识<select v-model="versionForm.keyId" aria-label="签名密钥标识" required><option value="" disabled>请选择可信签名密钥</option><option v-for="id in capabilities.signingKeyIds" :key="id" :value="id">{{id}}</option></select></label>
          </template>
          <template v-else>
          <label>HTTPS 下载地址<input v-model="versionForm.url" aria-label="HTTPS 下载地址" type="url" maxlength="2048" required></label>
          <label>文件大小（字节）<input v-model.number="versionForm.size" aria-label="文件大小" type="number" min="1" step="1" required></label>
          <label>SHA-256<input v-model="versionForm.sha256" aria-label="SHA-256" pattern="[0-9a-f]{64}" maxlength="64" required></label>
          <label>签名密钥标识<input v-model="versionForm.keyId" aria-label="签名密钥标识" maxlength="100" required></label>
          </template>
          <label>Ed25519 签名（Base64）<textarea v-model="versionForm.signature" aria-label="Ed25519 签名" required/></label>
          <small>填写对固件原始完整字节生成的签名。设备需预置对应可信公钥。</small>
          <p v-if="uploaded" role="status">文件已上传并通过签名校验，SHA-256：{{uploaded.sha256}}。可修改版本信息后重试保存。</p>
          <button type="submit">保存固件版本</button>
        </fieldset><p v-if="uploadPhase" role="status">{{uploadPhase}}</p><button type="button" :disabled="busy" @click="addingVersion=false;versionForm=freshVersion();file=null;uploaded=null">取消登记版本</button>
      </form>
      <p v-if="versionsLoading" role="status">正在加载固件版本…</p>
      <p v-else-if="versionError" role="alert">版本列表加载失败：{{versionError}} <button type="button" :disabled="busy" @click="loadVersions">重试版本列表</button></p>
      <div v-else class="firmware-versions">
        <p v-if="!versions.length" class="muted">该通道尚无固件版本。</p>
        <article v-for="v in versions" :key="v.id" class="firmware-version">
          <div class="section-heading"><h3>{{v.version}} <span class="firmware-status">{{v.enabled?'可用':'停用'}}</span></h3><button v-if="publishable" type="button" :disabled="busy||!v.enabled&&!enabled" @click="toggle(v)">{{v.enabled?'停用版本':'启用版本'}}</button></div>
          <p>{{v.description||'暂无更新说明'}}</p><dl><dt>允许的源版本</dt><dd>{{v.sourceVersions.join('、')}}</dd><dt>适用硬件版本</dt><dd>{{v.hardwareVersions.join('、')}}</dd><dt>固件包</dt><dd>{{v.artifact.size}} 字节 · Ed25519 · {{v.artifact.keyId}}</dd><dt>SHA-256</dt><dd class="firmware-digest">{{v.artifact.sha256}}</dd></dl>
          <p>{{v.artifact.fileId?'平台托管 · 上传时签名已验证':'外部登记 · 文件内容未验证'}}</p>
          <template v-if="v.artifact.fileId"><button type="button" :disabled="busy" @click="download(v)">生成临时下载链接</button><p v-if="downloadLinks[v.id]"><a :href="downloadLinks[v.id].url" target="_blank" rel="noopener noreferrer">下载固件</a> · 有效期至 {{new Date(downloadLinks[v.id].expiresAt).toLocaleString('zh-CN')}}</p></template>
        </article>
        <div v-if="total>20" class="firmware-toolbar"><button type="button" :disabled="busy||versionsLoading||page<=1" @click="page--;loadVersions()">上一页</button><span>第 {{page}} 页 · 共 {{total}} 个版本</span><button type="button" :disabled="busy||versionsLoading||page*20>=total" @click="page++;loadVersions()">下一页</button></div>
      </div>
    </template>
  </template>
</div>
</template>
<style scoped>
.firmware-panel{padding-bottom:16px}.firmware-notice{padding:12px 16px;background:var(--surface-secondary);border-left:3px solid var(--el-color-primary);line-height:1.8}.firmware-toolbar{display:flex;gap:12px;align-items:center;flex-wrap:wrap;margin:20px 0}.firmware-toolbar label{display:flex;align-items:center;gap:10px}.firmware-panel button,.firmware-panel select,.firmware-panel input,.firmware-panel textarea{font:inherit;border:1px solid var(--line);border-radius:6px;padding:8px 12px;background:var(--surface);color:var(--text-primary)}.firmware-panel button{cursor:pointer}.firmware-panel button:disabled{cursor:default;opacity:.5}.firmware-panel [role=alert]{color:var(--el-color-danger)}.firmware-form{max-width:700px;padding:20px;border:1px solid var(--line);border-radius:8px;margin:20px 0}.firmware-form fieldset{border:0;padding:0;display:grid;gap:12px;margin-bottom:12px}.firmware-form label{display:grid;gap:6px}.firmware-form textarea{min-height:64px;resize:vertical}.firmware-form small{color:var(--text-secondary);line-height:1.7}.firmware-version{border:1px solid var(--line);padding:16px 20px;border-radius:8px;margin:16px 0}.firmware-version h3{margin:0}.firmware-status{font-weight:400;font-size:12px;color:var(--text-secondary);margin-left:8px}.firmware-version dl{display:grid;grid-template-columns:130px 1fr;gap:8px;font-size:13px;line-height:1.8}.firmware-version dt{color:var(--text-secondary)}.firmware-version dd{margin:0;overflow-wrap:anywhere}.firmware-digest{font-family:monospace}@media(max-width:600px){.firmware-version dl{grid-template-columns:1fr}.firmware-toolbar select{max-width:220px}}
</style>
