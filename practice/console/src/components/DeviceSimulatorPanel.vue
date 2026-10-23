<script setup lang="ts">
import {computed,onScopeDispose,ref,watch} from 'vue'
import {catalogApi,type DeviceDetail,type ShadowScalar} from '../api/catalog'
import {errorMessage,isCancelled} from '../api/management'
import {initialValue} from './modelSchema'
import DeviceValueInput from './DeviceValueInput.vue'
import {SimulatorClient,type Trace} from '../simulator/client'
import {envelope,SimulatedDevice,validateValue,type Envelope,type Field,type ModelEvent} from '../simulator/protocol'

const props=defineProps<{scope:string;detail:DeviceDetail;allowed:boolean;presence:string;embedded?:boolean;focusMessageId?:string}>()
const emit=defineEmits<{'connection-state':[state:string]}>()
const status=ref<'connecting'|'connected'|'disconnected'>('disconnected'),busy=ref(false),error=ref(''),autoReply=ref(false),confirmed=ref(false)
const logs=ref<Trace[]>([]),pending=ref<Envelope[]>([]),values=ref<Record<string,ShadowScalar>>({}),selected=ref<Record<string,boolean>>({})
const replyDrafts=ref<Record<string,string>>({}),replyErrors=ref<Record<string,string>>({}),replySending=ref<Record<string,boolean>>({})
const eventId=ref(''),eventValues=ref<Record<string,ShadowScalar>>({}),eventIncluded=ref<Record<string,boolean>>({})
const intervalSeconds=ref(5),running=ref(false)
const connectionNotice=ref(''),connectedAt=ref('')
const workspace=ref<'properties'|'events'|'firmware'|'downlinks'|'messages'>(props.embedded?'downlinks':'properties')
const firmwareReport=ref({mainVersion:'1.0.0',hardwareVersion:'HW-A',bootId:'sim-'+crypto.randomUUID(),versions:'{\n  "main": "1.0.0"\n}'})
const firmwareAdvanced=ref(false),firmwareNotice=ref('')
watch([()=>firmwareReport.value.mainVersion,()=>firmwareReport.value.hardwareVersion,()=>firmwareReport.value.bootId,()=>firmwareReport.value.versions,firmwareAdvanced],()=>{firmwareNotice.value=''})
function newBootId(){firmwareReport.value.bootId='sim-'+crypto.randomUUID()}
watch(status,state=>emit('connection-state',state),{immediate:true})
watch(()=>props.focusMessageId,()=>{if(props.embedded)workspace.value='downlinks'})
const propertyQuery=ref(''),propertyPage=ref(1),eventQuery=ref(''),eventPage=ref(1)
const matches=(f:Field,q:string)=>[f.id,f.name].some(value=>value?.toLocaleLowerCase().includes(q.trim().toLocaleLowerCase()))
const filteredProperties=computed(()=>fields.value.filter(f=>matches(f,propertyQuery.value)))
const visibleProperties=computed(()=>filteredProperties.value.slice((propertyPage.value-1)*8,propertyPage.value*8))
const filteredEventFields=computed(()=>eventFields.value.filter(f=>matches(f,eventQuery.value)))
const visibleEventFields=computed(()=>filteredEventFields.value.slice((eventPage.value-1)*8,eventPage.value*8))
const selectedCount=computed(()=>fields.value.filter(f=>selected.value[f.id]).length)
watch(propertyQuery,()=>{propertyPage.value=1})
watch(eventQuery,()=>{eventPage.value=1})
watch(eventId,()=>{eventQuery.value='';eventPage.value=1})
function selectPage(value:boolean){for(const f of visibleProperties.value)selected.value[f.id]=value}
const life=new AbortController()
let disposed=false,attempt=0,timer:ReturnType<typeof setTimeout>|undefined
const endpoint=`${location.protocol==='https:'?'wss:':'ws:'}//${location.host}/mqtt`
const model=computed(()=>props.detail.model.model)
const fields=computed(()=>(Object.values(model.value.properties) as Field[]).filter(f=>f.access!=='WRITE_ONLY'))
const events=computed(()=>Object.values(model.value.events) as ModelEvent[])
const eventFields=computed(()=>Object.values(events.value.find(e=>e.id===eventId.value)?.fields||{}))
const connectionBlocker=computed(()=>!props.allowed?'当前账号缺少设备写入或凭证权限':props.detail.device.lifecycle!=='ACTIVE'?'设备未启用，请先在设备概览中启用':!props.detail.product.enabled?'所属产品已停用，请先启用产品':props.detail.product.configuration?.deviceType==='CHILD'?'网关子设备需通过网关接入，暂不支持直接模拟':'')
const eligible=computed(()=>!connectionBlocker.value)
const connected=computed(()=>status.value==='connected'&&eligible.value)
const connectionTitle=computed(()=>connected.value?'设备已连接':status.value==='connecting'?'正在连接设备':connectionNotice.value?(connectionNotice.value.startsWith('连接未成功')?'连接未成功':'连接已断开'):'等待连接设备')
const connectionHint=computed(()=>connected.value?'可以上报数据，或从平台端下发指令，在这里接收并应答。':status.value==='connecting'?'正在验证设备身份并建立 MQTT 连接，请稍候…':connectionBlocker.value||connectionNotice.value||(confirmed.value?'身份模拟已确认，点击「连接设备」开始调试。':'先确认下方模拟说明，再连接设备开始调试。'))
let device=new SimulatedDevice(model.value,props.detail.device.modelVersion)
const client=new SimulatorClient(row=>{if(!disposed){logs.value.unshift(row);logs.value.length=Math.min(logs.value.length,200)}},state=>{
 if(disposed)return
 if(state==='connected'){connectedAt.value=new Date().toLocaleTimeString();connectionNotice.value=''}
 if(state==='disconnected'&&status.value!=='disconnected'){connectionNotice.value=status.value==='connecting'?'连接未成功，请检查接入服务或在消息时间线查看原因。':'设备连接已中断，请重新连接后继续调试。';connectedAt.value=''}
 status.value=state
 if(state==='disconnected'){stopTimer();pending.value=[];replyDrafts.value={};replyErrors.value={}}
},message=>{
 if(message.method==='system.time.reply'||message.method.endsWith('.reply'))return
 if(!['thing.property.write','thing.property.read','thing.command.invoke'].includes(message.method)){error.value='暂不支持此下行方法：'+message.method;return}
 if(pending.value.length>=32){error.value='待处理下行已达 32 条，请处理后继续';return}
 if(!pending.value.some(p=>p.messageId===message.messageId&&p.method===message.method)){pending.value.push(message);fillReply(message,true)}
 if(autoReply.value)void respond(message,true)
})
watch(fields,list=>{propertyPage.value=1;values.value={};selected.value={};for(const f of list){values.value[f.id]=initialValue(f.schema);selected.value[f.id]=true}},{immediate:true})
watch(events,list=>{if(!list.some(e=>e.id===eventId.value))eventId.value=list[0]?.id||''},{immediate:true})
watch(eventFields,list=>{eventValues.value={};eventIncluded.value={};for(const f of list){eventValues.value[f.id]=initialValue(f.schema);eventIncluded.value[f.id]=!!f.required}},{immediate:true})
watch(()=>[props.detail.device.id,props.detail.device.credentialVersion,eligible.value],()=>disconnect())

async function connect(){
 if(!eligible.value||!confirmed.value||status.value!=='disconnected')return
 const current=++attempt
 status.value='connecting';error.value='';connectionNotice.value=''
 try{
  const credential=await catalogApi.credential(props.scope,props.detail.device,life.signal)
  if(disposed||current!==attempt)return
  device=new SimulatedDevice(model.value,props.detail.device.modelVersion)
  client.start(endpoint,credential)
 }catch(e){if(current===attempt&&!disposed){status.value='disconnected';if(!isCancelled(e))connectionNotice.value='连接未成功：'+errorMessage(e)}}
}
function stopTimer(){if(timer)clearTimeout(timer);timer=undefined;running.value=false}
function disconnect(){attempt++;stopTimer();client.stop();status.value='disconnected';connectionNotice.value='';connectedAt.value='';pending.value=[];replyDrafts.value={};replyErrors.value={}}
function fillReply(message:Envelope,success:boolean){
 try{const reply=device.preview(message,success);replyDrafts.value[message.messageId]=JSON.stringify({result:reply.result,data:reply.data},null,2);delete replyErrors.value[message.messageId]}
 catch(e){replyErrors.value[message.messageId]=errorMessage(e)}
}
function propertyPayload(){
 const result:Record<string,ShadowScalar>={}
 for(const f of fields.value)if(selected.value[f.id]){
  const value=values.value[f.id]
  if(!validateValue(f.schema,value))throw Error((f.name||f.id)+' 的值不符合物模型约束')
  result[f.id]=value!
 }
 if(!Object.keys(result).length)throw Error('请选择至少一个上报属性')
 return result
}
async function report(){
 if(!connected.value||busy.value)return
 busy.value=true;error.value=''
 try{const properties=propertyPayload();await client.publish(envelope('thing.property.report',{properties}));Object.assign(device.values,properties)}
 catch(e){if(!disposed){error.value=errorMessage(e);stopTimer()}}finally{busy.value=false}
}
async function repeat(){
 await report()
 if(running.value&&connected.value)timer=setTimeout(()=>void repeat(),intervalSeconds.value*1000)
}
function toggleTimer(){
 if(running.value){stopTimer();return}
 try{propertyPayload();if(!Number.isInteger(intervalSeconds.value)||intervalSeconds.value<1||intervalSeconds.value>3600)throw Error('上报间隔须为 1～3600 秒');running.value=true;void repeat()}
 catch(e){error.value=errorMessage(e)}
}
async function reportEvent(){
 if(!connected.value||busy.value||!eventId.value)return
 busy.value=true;error.value=''
 try{
  const fields:Record<string,ShadowScalar>={}
  for(const f of eventFields.value)if(f.required||eventIncluded.value[f.id]){if(!validateValue(f.schema,eventValues.value[f.id]))throw Error((f.name||f.id)+' 的值不符合物模型约束');fields[f.id]=eventValues.value[f.id]!}
  await client.publish(envelope('thing.event.report',{eventId:eventId.value,fields}))
 }catch(e){if(!disposed)error.value=errorMessage(e)}finally{busy.value=false}
}
async function reportFirmware(){
 if(!connected.value||busy.value)return
 busy.value=true;error.value='';firmwareNotice.value=''
 try{
  const form=firmwareReport.value
  const validText=(value:unknown,max:number)=>typeof value==='string'&&value.length>0&&value.length<=max&&value.trim()===value&&!/[\u0000-\u001f\u007f]/.test(value)
  if(!validText(form.mainVersion,100)||!validText(form.hardwareVersion,100)||!validText(form.bootId,128))throw Error('请填写有效的主模块版本、硬件版本和启动标识')
  let versions:Record<string,unknown>
  try{versions=firmwareAdvanced.value?JSON.parse(form.versions):{main:form.mainVersion}}catch{throw Error('组件版本 JSON 格式无效')}
  if(!versions||typeof versions!=='object'||Array.isArray(versions)||Object.keys(versions).length<1||Object.keys(versions).length>16)throw Error('组件版本须为包含1～16项的对象')
  for(const [component,version] of Object.entries(versions))if(!/^[a-z][a-z0-9_]{0,63}$/.test(component)||!validText(version,100))throw Error('组件标识或版本无效')
  if(firmwareAdvanced.value&&Object.hasOwn(versions,'main')&&versions.main!==form.mainVersion)throw Error('组件 JSON 中的 main 必须与主模块固件版本一致')
  await client.publish(envelope('thing.info.report',{firmwareVersion:form.mainVersion,hardwareVersion:form.hardwareVersion,bootId:form.bootId,sdkVersion:'browser-simulator',clockSynced:true}))
  firmwareNotice.value='设备信息已获 MQTT 接收确认，正在上报组件版本…'
  await client.publish(envelope('ota.version.report',{versions}))
  firmwareNotice.value='设备与组件报告均已获 MQTT 接收确认。业务入库可能稍有延迟，请在设备概览或固件与升级页刷新查看。'
 }catch(e){if(!disposed){firmwareNotice.value=firmwareNotice.value?'设备信息已发送，组件报告未确认，请重试完整上报。':'';error.value=errorMessage(e)}}finally{busy.value=false}
}
async function respond(message:Envelope,success:boolean,edited=false){
 if(!connected.value||replySending.value[message.messageId])return
 replySending.value[message.messageId]=true
 delete replyErrors.value[message.messageId]
 error.value=''
 try{
  let reply:Envelope
  if(edited){
   let content:unknown
   try{content=JSON.parse(replyDrafts.value[message.messageId]||'')}catch{throw Error('应答 JSON 格式无效，请检查逗号、引号和括号')}
   reply=device.respondCustom(message,content)
  }else reply=device.respond(message,success)
  await client.publish(reply)
  pending.value=pending.value.filter(p=>p!==message)
  delete replyDrafts.value[message.messageId]
  if(reply.result?.code==='SUCCESS'&&message.method==='thing.property.write'){
   const reported:Record<string,ShadowScalar>={}
   for(const f of fields.value)if(Object.hasOwn(device.values,f.id)){values.value[f.id]=device.values[f.id]!;if(Object.hasOwn(message.data.properties as object,f.id))reported[f.id]=device.values[f.id]!}
   if(Object.keys(reported).length)await client.publish(envelope('thing.property.report',{properties:reported}))
  }
 }catch(e){if(!disposed){error.value=errorMessage(e);replyErrors.value[message.messageId]=errorMessage(e)}}
 finally{delete replySending.value[message.messageId]}
}
onScopeDispose(()=>{disposed=true;life.abort();disconnect()})
</script>

<template>
 <div class="simulator" :class="{embedded}">
  <div class="section-heading"><div><p class="eyebrow">MQTT DEVICE SIMULATOR</p><h2>{{embedded?'模拟设备端':'设备模拟'}}</h2><p>{{embedded?'接收平台指令，模拟设备上报与应答。':'在浏览器中模拟当前设备，验证真实接入、数据上报与控制应答。'}}</p></div></div>
  <el-alert v-if="presence==='ONLINE'&&status==='disconnected'" title="当前设备在线：使用相同身份连接将替换现有连接，请优先使用测试设备。" type="warning" :closable="false"/>
  <section class="sim-connection" :class="{'is-connected':connected,'is-connecting':status==='connecting','is-interrupted':!!connectionNotice&&status==='disconnected'}" aria-label="模拟设备连接">
   <div class="connection-summary">
    <div class="connection-symbol" aria-hidden="true"><svg v-if="connected" viewBox="0 0 24 24" fill="none"><path d="m5 12 4 4L19 6"/></svg><span v-else-if="status==='connecting'" class="connection-spinner"/><svg v-else viewBox="0 0 24 24" fill="none"><path d="M12 3v9M6.3 5.8a8 8 0 1 0 11.4 0"/></svg></div>
    <div class="connection-copy" role="status" aria-live="polite" aria-atomic="true"><strong>{{connectionTitle}}</strong><p>{{connectionHint}}</p></div>
    <div class="connection-actions"><el-button v-if="!connected" type="primary" :loading="status==='connecting'" :disabled="!eligible||!confirmed||status!=='disconnected'" @click="connect">{{status==='connecting'?'连接中…':'连接设备'}}</el-button><el-button v-if="status!=='disconnected'" :type="connected?'success':undefined" plain @click="disconnect">断开连接</el-button></div>
   </div>
   <div v-if="connected" class="connection-established"><span class="connection-dot"/>MQTT 5 会话已建立<span class="connection-time">{{connectedAt}} 连接成功</span></div>
   <div class="connection-meta"><span>绑定模型 <b>{{detail.device.modelVersion}}</b></span><details><summary>连接地址</summary><code>{{endpoint}}</code></details></div>
   <div v-if="status==='disconnected'" class="connection-consent"><el-checkbox v-model="confirmed">我确认使用此设备身份进行模拟</el-checkbox><p>上报将写入真实业务数据，请使用测试设备。</p></div>
  </section>
  <p class="connection-footnote">切换本页页签保持连接 · 离开设备详情自动断开</p>
  <el-alert v-if="error" :title="error" type="error" :closable="false"/>
  <div v-if="embedded" class="reply-mode"><el-checkbox v-model="autoReply">自动模拟成功应答</el-checkbox><span>{{autoReply?'自动应答':'手动应答'}}</span></div>
  <nav class="simulator-navigation" aria-label="模拟工作区">
   <button type="button" :aria-pressed="workspace==='properties'" @click="workspace='properties'">属性上报 <span>{{fields.length}}</span><small v-if="running">定时运行中</small></button>
   <button type="button" :aria-pressed="workspace==='events'" @click="workspace='events'">事件上报 <span>{{events.length}}</span></button>
   <button type="button" :aria-pressed="workspace==='firmware'" @click="workspace='firmware'">固件信息上报</button>
   <button type="button" :aria-pressed="workspace==='downlinks'" :class="{'has-pending':pending.length>0}" @click="workspace='downlinks'">下行处理 <span aria-live="polite">{{pending.length}} 待处理</span></button>
   <button type="button" :aria-pressed="workspace==='messages'" @click="workspace='messages'">消息时间线 <span>{{logs.length}}</span></button>
  </nav>
  <p v-if="pending.length&&workspace!=='downlinks'" class="downlink-notice" role="status">收到 {{pending.length}} 条下行消息，等待模拟应答。<el-button link type="primary" @click="workspace='downlinks'">立即处理</el-button></p>
  <div class="simulator-workspace">
   <section v-show="workspace==='properties'" class="simulation-section"><h3>属性上报</h3><p class="muted">已选 {{selectedCount}} / {{fields.length}} 项。搜索和翻页不改变选择，上报包含所有页的已选属性。</p>
    <div class="workspace-toolbar"><el-input v-model="propertyQuery" clearable placeholder="搜索属性名称或标识" aria-label="搜索模拟属性"/><el-button :disabled="busy||!visibleProperties.length" @click="selectPage(true)">选中本页</el-button><el-button :disabled="busy||!visibleProperties.length" @click="selectPage(false)">取消本页</el-button></div>
    <div class="workspace-scroll">
    <div v-for="f in visibleProperties" :key="f.id" class="simulation-field" :data-sim-property="f.id"><el-checkbox v-model="selected[f.id]" :disabled="busy">{{f.name||f.id}} <span class="muted">{{f.id}}</span></el-checkbox><DeviceValueInput v-model="values[f.id]" :schema="f.schema" :disabled="busy||!selected[f.id]" :label="f.id+' 模拟值'"/></div>
    <p v-if="!fields.length" class="muted">当前绑定模型没有可上报属性。</p>
    <p v-else-if="!filteredProperties.length" class="muted">没有匹配的属性，其他页选择仍会提交。</p></div>
    <div v-if="filteredProperties.length>8" class="workspace-pagination"><span>匹配 {{filteredProperties.length}} 项</span><el-button :disabled="propertyPage===1" @click="propertyPage--">上一页属性</el-button><span>{{propertyPage}} / {{Math.ceil(filteredProperties.length/8)}}</span><el-button :disabled="propertyPage*8>=filteredProperties.length" @click="propertyPage++">下一页属性</el-button></div>
    <div class="simulation-actions"><el-button type="primary" :disabled="!connected||busy||!fields.length" @click="report">上报属性</el-button><el-input-number v-model="intervalSeconds" :min="1" :max="3600" :precision="0" :disabled="running" aria-label="上报间隔秒数"/><span>秒</span><el-button :disabled="!connected||!fields.length||busy&&!running" @click="toggleTimer">{{running?'停止定时上报':'定时上报'}}</el-button></div>
   </section>
   <section v-show="workspace==='events'" class="simulation-section"><h3>事件上报</h3>
    <template v-if="events.length"><el-select v-model="eventId" filterable aria-label="模拟事件" :disabled="busy"><el-option v-for="event in events" :key="event.id" :value="event.id" :label="event.name||event.id"/></el-select>
     <div v-if="eventFields.length>8" class="workspace-toolbar"><el-input v-model="eventQuery" clearable placeholder="搜索事件参数" aria-label="搜索事件参数"/></div>
     <div class="workspace-scroll"><div v-for="f in visibleEventFields" :key="eventId+f.id" class="simulation-field" :data-event-field="f.id"><span v-if="f.required">{{f.name||f.id}}（必填）</span><el-checkbox v-else v-model="eventIncluded[f.id]" :disabled="busy">{{f.name||f.id}}（选填）</el-checkbox><DeviceValueInput v-model="eventValues[f.id]" :schema="f.schema" :disabled="busy||!f.required&&!eventIncluded[f.id]" :label="f.id+' 事件值'"/></div><p v-if="eventFields.length&&!filteredEventFields.length" class="muted">没有匹配的事件参数。</p></div>
     <div v-if="filteredEventFields.length>8" class="workspace-pagination"><el-button :disabled="eventPage===1" @click="eventPage--">上一页参数</el-button><span>{{eventPage}} / {{Math.ceil(filteredEventFields.length/8)}}</span><el-button :disabled="eventPage*8>=filteredEventFields.length" @click="eventPage++">下一页参数</el-button></div>
     <p class="muted">所有页的必填和已勾选参数均会提交。</p>
     <el-button type="primary" :disabled="!connected||busy" @click="reportEvent">上报事件</el-button>
    </template><p v-else class="muted">当前绑定模型没有定义事件。</p>
   </section>
   <section v-show="workspace==='firmware'" class="simulation-section"><h3>固件信息上报</h3><p class="muted">通过真实 MQTT 链路上报测试信息，更新当前设备的实际固件记录。仅 MQTT 重连保持启动标识不变；模拟重启时生成新标识。组件版本 JSON 每次发送完整快照。</p><div class="simulation-field"><label>主模块固件版本</label><el-input v-model="firmwareReport.mainVersion" aria-label="模拟主模块固件版本" maxlength="100" :disabled="busy"/></div><div class="simulation-field"><label>实际硬件版本</label><el-input v-model="firmwareReport.hardwareVersion" aria-label="模拟硬件版本" maxlength="100" :disabled="busy"/></div><div class="simulation-field"><label>启动标识</label><el-input v-model="firmwareReport.bootId" aria-label="模拟启动标识" maxlength="128" :disabled="busy"/></div><el-button :disabled="busy" @click="newBootId">生成模拟重启标识</el-button><p class="muted">快速上报：填写主模块版本和硬件版本即可，组件 main 自动使用同一版本。默认值是测试示例，请按测试场景修改。</p><el-checkbox v-model="firmwareAdvanced" :disabled="busy">高级：上报完整多组件快照</el-checkbox><p v-if="!firmwareAdvanced" class="muted">当前将上报 main {{firmwareReport.mainVersion}}；此模式会用单组件快照替换已有 OTA 组件清单。</p><div v-if="firmwareAdvanced" class="simulation-field"><label>组件版本 JSON</label><el-input v-model="firmwareReport.versions" aria-label="模拟组件版本 JSON" type="textarea" :rows="6" maxlength="4096" :disabled="busy"/></div><el-button type="primary" :disabled="!connected||busy" @click="reportFirmware">上报设备与组件固件信息</el-button><p v-if="firmwareNotice" role="status">{{firmwareNotice}}</p><p class="muted">此操作不下载、安装或验证真实固件。上报后可到“固件与升级”页刷新并检查兼容性。</p></section>
  <section v-show="workspace==='downlinks'" class="simulation-section"><div class="section-heading"><h3>下行处理 <span class="muted">{{pending.length}} 条待处理</span></h3><el-checkbox v-if="!embedded" v-model="autoReply">自动模拟成功应答</el-checkbox></div><p class="muted">属性写入成功后更新模拟值并上报；操作成功仅表示模拟执行，不推断硬件行为。可在平台端控制区发起下发。</p>
   <div class="workspace-scroll downlink-scroll">
   <p v-if="!pending.length" class="muted">等待平台下发属性或操作。</p>
   <article v-for="message in pending" :key="message.messageId" class="pending-message" :class="{'focused-message':message.messageId===focusMessageId}"><el-tag v-if="message.messageId===focusMessageId" size="small">本次平台下发</el-tag><strong>{{message.method}}</strong><code>{{message.messageId}}</code>
    <details class="original-downlink"><summary>查看收到的原始命令</summary><pre>{{JSON.stringify(message,null,2)}}</pre></details>
    <div class="reply-editor-heading"><strong>应答内容</strong><div><el-button link :disabled="replySending[message.messageId]" @click="fillReply(message,true)">填入成功模板</el-button><el-button link :disabled="replySending[message.messageId]" @click="fillReply(message,false)">填入失败模板</el-button></div></div>
    <p class="muted">可编辑 result 和 data；replyTo、消息 ID 和应答方法由系统生成。</p>
    <el-input v-model="replyDrafts[message.messageId]" type="textarea" :rows="9" :maxlength="64000" :disabled="replySending[message.messageId]" :aria-label="message.messageId+' 应答 JSON'" spellcheck="false"/>
    <p v-if="replyErrors[message.messageId]" class="reply-error" role="alert">{{replyErrors[message.messageId]}}</p>
    <div class="reply-actions"><el-button type="primary" :disabled="!connected||replySending[message.messageId]" :loading="replySending[message.messageId]" @click="respond(message,true,true)">发送编辑后的应答</el-button><el-button :disabled="!connected||replySending[message.messageId]" @click="respond(message,true)">模拟成功</el-button><el-button :disabled="!connected||replySending[message.messageId]" @click="respond(message,false)">模拟失败</el-button></div><p class="muted">“模拟成功／失败”直接使用默认应答，不使用编辑内容。</p></article>
   </div>
  </section>
  <section v-show="workspace==='messages'" class="simulation-section"><div class="section-heading"><h3>消息时间线 <span class="muted">最近 200 条</span></h3><el-button @click="logs=[]">清空记录</el-button></div><p class="muted">PUBACK 只确认 MQTT 接收，业务执行结果以应用回执为准。</p><p v-if="!logs.length" class="muted">连接后将在这里显示真实收发记录。</p>
   <div class="workspace-scroll">
   <details v-for="(row,index) in logs" :key="index" class="trace"><summary><time>{{new Date(row.time).toLocaleTimeString()}}</time><el-tag size="small" :type="row.direction==='下行'?'warning':'info'">{{row.direction}}</el-tag><code>{{row.topic}}</code><span>{{row.status}}</span></summary><pre v-if="row.body">{{row.body}}</pre></details>
   </div>
  </section>
  </div>
 </div>
</template>
<style scoped>
.sim-connection{--connection-color:var(--text-secondary);--connection-fill:var(--surface-secondary);margin:20px 0 10px;border:1px solid var(--line);border-radius:12px;overflow:hidden;background:var(--surface);transition:border-color .2s,background-color .2s}.sim-connection.is-connected{--connection-color:var(--el-color-success-dark-2);--connection-fill:var(--el-color-success-light-9);border-color:var(--el-color-success-light-5);background:var(--el-color-success-light-9)}.sim-connection.is-connecting{--connection-color:var(--el-color-primary);--connection-fill:var(--el-color-primary-light-9);border-color:var(--el-color-primary-light-5)}.sim-connection.is-interrupted{--connection-color:var(--el-color-warning-dark-2);--connection-fill:var(--el-color-warning-light-9);border-color:var(--el-color-warning-light-5)}.connection-summary{display:flex;align-items:flex-start;gap:12px;padding:18px;flex-wrap:wrap}.connection-symbol{display:flex;align-items:center;justify-content:center;flex:0 0 40px;width:40px;height:40px;border-radius:50%;color:var(--connection-color);background:var(--connection-fill);border:1px solid currentColor}.connection-symbol svg{width:22px;height:22px;stroke:currentColor;stroke-width:2;stroke-linecap:round;stroke-linejoin:round}.connection-copy{flex:1;min-width:150px}.connection-copy>strong{display:block;color:var(--connection-color);font-size:16px;line-height:1.5}.simulator .connection-copy>p{margin:5px 0 0;color:var(--text-secondary);font-size:12px;line-height:1.7}.sim-connection .connection-actions{display:flex;flex-wrap:wrap;gap:8px;margin:2px 0 0 auto}.sim-connection .connection-actions .el-button+.el-button{margin-left:0}.connection-established{display:flex;align-items:center;flex-wrap:wrap;gap:8px;padding:0 18px 14px;color:var(--connection-color);font-size:12px;font-weight:500}.connection-dot{width:7px;height:7px;flex:0 0 7px;border-radius:50%;background:currentColor;box-shadow:0 0 0 3px var(--el-color-success-light-8)}.connection-time{margin-left:auto;font-weight:400;font-variant-numeric:tabular-nums}.connection-meta{display:flex;align-items:baseline;flex-wrap:wrap;gap:12px 20px;padding:12px 18px;border-top:1px solid var(--line);font-size:12px;color:var(--text-secondary)}.connection-meta b{margin-left:6px;color:var(--text-primary);font-weight:600}.connection-meta details{min-width:0;flex:1}.connection-meta summary{cursor:pointer;width:fit-content}.connection-meta code{display:block;margin-top:8px;overflow-wrap:anywhere;font-size:11px}.connection-consent{border-top:1px solid var(--line);padding:12px 18px;background:var(--surface-secondary)}.simulator .connection-consent .el-checkbox{font-size:12px}.connection-consent :deep(.el-checkbox__label){font-size:12px}.simulator .connection-consent p{margin:4px 0 0 22px;font-size:12px;color:var(--text-secondary);line-height:1.6}.simulator .connection-footnote{font-size:11px;color:var(--text-secondary);margin:8px 0 12px}.connection-spinner{width:19px;height:19px;border:2px solid currentColor;border-right-color:transparent;border-radius:50%;animation:connection-spin .8s linear infinite}@keyframes connection-spin{to{transform:rotate(360deg)}}@media(prefers-reduced-motion:reduce){.connection-spinner{animation:none}.sim-connection{transition:none}}@media(max-width:600px){.connection-summary{padding:14px}.sim-connection .connection-actions{width:100%;margin:0 0 0 52px}.connection-meta,.connection-consent{padding:12px 14px}.connection-established{padding:0 14px 14px}.connection-time{margin-left:15px}}
.reply-editor-heading{display:flex;gap:8px;align-items:center;justify-content:space-between;flex-wrap:wrap;margin-top:14px}.reply-editor-heading strong{font-size:13px}.reply-actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:12px}.reply-actions .el-button+.el-button{margin-left:0}.reply-error{color:var(--el-color-danger)}.original-downlink summary{font-size:12px;color:var(--text-secondary);cursor:pointer}.pending-message :deep(textarea){font-family:Consolas,monospace;font-size:12px;line-height:1.6}
.reply-mode{display:flex;align-items:center;justify-content:space-between;gap:8px;padding:12px 0}.reply-mode>span{font-size:11px;color:var(--text-secondary)}.focused-message{border:1px solid var(--el-color-primary-light-5);border-radius:8px;padding:12px;background:var(--el-color-primary-light-9)}.embedded .connection-card{padding:12px;gap:12px;margin-top:12px}.embedded .connection-actions{margin-left:0}.embedded .simulation-section{padding:14px}.embedded .simulator-navigation{position:static}.embedded .simulator-navigation button{padding:8px;font-size:12px}.embedded .simulation-field{grid-template-columns:1fr}.embedded .section-heading .eyebrow{display:none}.embedded .downlink-scroll{max-height:340px}.embedded .workspace-scroll{max-height:340px}
.simulator-navigation{position:sticky;top:0;z-index:5;display:flex;flex-wrap:wrap;gap:8px;padding:12px 0;background:var(--surface);border-bottom:1px solid var(--line);margin-top:12px}.simulator-navigation button{display:flex;align-items:center;gap:8px;border:1px solid var(--line);border-radius:8px;padding:10px 14px;background:var(--surface-secondary);color:var(--text-primary);cursor:pointer;font:inherit;font-size:13px}.simulator-navigation button[aria-pressed=true]{border-color:var(--el-color-primary);color:var(--el-color-primary);background:var(--el-color-primary-light-9)}.simulator-navigation span{font-size:11px}.simulator-navigation small{color:var(--el-color-success);font-size:10px}.simulator-navigation .has-pending span{background:var(--el-color-warning-light-9);color:var(--el-color-warning-dark-2);padding:3px 6px;border-radius:5px}.downlink-notice{padding:10px 14px;background:var(--el-color-warning-light-9);border-radius:8px}.workspace-toolbar{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin:16px 0}.workspace-toolbar>.el-input{max-width:360px}.workspace-scroll{max-height:420px;overflow:auto;padding-right:8px;overscroll-behavior:contain}.workspace-pagination{display:flex;align-items:center;flex-wrap:wrap;gap:12px;padding:14px 0;border-top:1px solid var(--line);font-size:12px;color:var(--text-secondary)}.simulator-workspace .simulation-section{margin-top:12px}.downlink-scroll{max-height:500px}.simulator-navigation button:focus-visible{outline:2px solid var(--el-color-primary);outline-offset:2px}@media(max-width:600px){.simulator-navigation button{padding:9px;flex:1 1 40%;justify-content:space-between}.workspace-scroll{max-height:340px}.workspace-toolbar>.el-input{max-width:none}}
.simulator h2{font-size:18px;margin:8px 0}.simulator h3{font-size:15px;margin:0}.simulator p{font-size:13px;line-height:1.8}.simulator .el-alert{margin:14px 0}.connection-card{display:flex;gap:24px;align-items:center;flex-wrap:wrap;background:var(--surface-secondary);border:1px solid var(--line);padding:20px;border-radius:12px;margin:20px 0 12px}.connection-card code,.connection-card strong{display:block;margin-top:8px;overflow-wrap:anywhere}.connection-actions{margin-left:auto}.simulation-section{border:1px solid var(--line);border-radius:12px;padding:20px;margin-top:20px;min-width:0}.simulation-field{display:grid;grid-template-columns:minmax(100px,1fr) minmax(120px,1fr);align-items:center;gap:12px;margin:16px 0}.simulation-field .el-checkbox{white-space:normal;height:auto}.simulation-field .muted{font-size:11px}.simulation-actions{display:flex;align-items:center;flex-wrap:wrap;gap:8px;margin-top:18px}.simulation-actions .el-input-number{width:110px}.simulator code,.simulator pre{font-family:Consolas,monospace;font-size:12px}.simulator pre{white-space:pre-wrap;overflow-wrap:anywhere;background:var(--surface-secondary);padding:12px;border-radius:8px;max-height:300px;overflow:auto}.pending-message{border-top:1px solid var(--line);padding-top:16px;margin-top:16px}.pending-message>code{display:block;margin:8px 0}.trace{border-top:1px solid var(--line);padding:12px 0}.trace summary{cursor:pointer;font-size:12px;overflow-wrap:anywhere}.trace summary>*{margin-right:12px}.trace time{font-variant-numeric:tabular-nums}.simulator .section-heading{gap:12px;flex-wrap:wrap}.simulator .el-checkbox{max-width:100%;white-space:normal;height:auto;line-height:1.6}@media(max-width:1000px){.connection-actions{margin-left:0}}@media(max-width:600px){.simulation-field{grid-template-columns:1fr}.simulation-section{padding:16px}.trace summary code{display:block;margin-top:8px}}
</style>
