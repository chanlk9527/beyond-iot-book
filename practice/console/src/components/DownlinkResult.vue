<script setup lang="ts">
import { onScopeDispose, ref, watch } from 'vue'
import { catalogApi, type DownlinkStatus } from '../api/catalog'
import { errorMessage, isCancelled } from '../api/management'
const props=defineProps<{scope:string;deviceId:string;initial:DownlinkStatus}>()
const emit=defineEmits<{pending:[value:boolean]}>()
const current=ref(props.initial), error=ref('')
const waiting=(status:string)=>['SENDING','DELIVERED','ACCEPTED'].includes(status)
const labels:Record<string,string>={SENDING:'正在发送',DELIVERED:'已投递，等待设备回执',ACCEPTED:'设备已受理，等待执行结果',SUCCEEDED:'设备执行成功',FAILED:'设备执行失败',DISPATCH_FAILED:'下发未确认',TIMED_OUT:'回执超时，结果未知'}
let timer:ReturnType<typeof setTimeout>|undefined, request=new AbortController(), disposed=false
function stop(){if(timer)clearTimeout(timer);timer=undefined}
function schedule(){
 stop();emit('pending',waiting(current.value.status))
 if(waiting(current.value.status)&&!disposed)timer=setTimeout(()=>void refresh(),1000)
}
async function refresh(){
 stop();error.value=''
 try {
  const result=await catalogApi.downlink(props.scope,props.deviceId,current.value.messageId,request.signal)
  if(disposed)return
  current.value=result;schedule()
 } catch(e){if(!isCancelled(e)){error.value=errorMessage(e);emit('pending',false)}}
}
watch(()=>props.initial,value=>{stop();request.abort();request=new AbortController();current.value=value;error.value='';schedule()},{immediate:true})
onScopeDispose(()=>{disposed=true;stop();request.abort()})
</script>
<template>
 <div class="downlink-result" role="status">
  <el-alert :title="labels[current.status]||current.status" :type="current.status==='SUCCEEDED'?'success':['FAILED','DISPATCH_FAILED','TIMED_OUT'].includes(current.status)?'warning':'info'" :closable="false"/>
  <p>{{current.message}} <span v-if="current.code">（{{current.code}}）</span></p>
  <p class="mono">消息 ID：{{current.messageId}}<template v-if="current.commandId"> · 指令 ID：{{current.commandId}}</template></p>
  <p v-if="error" class="result-error">回执查询失败：{{error}}。执行结果尚未确认。</p>
  <el-button size="small" @click="refresh">刷新执行结果</el-button>
 </div>
</template>
<style scoped>
.downlink-result{margin:20px 0;padding:16px;border:1px solid #dcebe1;border-radius:8px}.downlink-result p{font-size:13px;line-height:1.7;overflow-wrap:anywhere}.mono{font-family:Consolas,monospace;color:#789188}.result-error{color:#b66a32}
</style>
