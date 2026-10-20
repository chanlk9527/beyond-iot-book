<script setup lang="ts">
import {defineAsyncComponent,ref} from 'vue'
import type {DeviceDetail} from '../api/catalog'
import DeviceCommandPanel from './DeviceCommandPanel.vue'
defineProps<{scope:string;detail:DeviceDetail;writable:boolean;canSimulate:boolean;presence:string}>()
const Simulator=defineAsyncComponent(()=>import('./DeviceSimulatorPanel.vue').then(module=>module.default))
const opened=ref(false),expanded=ref(true),simulatorState=ref('disconnected'),messageId=ref('')
function dispatched(id:string){messageId.value=id;if(opened.value)expanded.value=true}
</script>
<template>
 <div class="device-debug">
  <header class="debug-heading"><div><h2>设备调试</h2><p>在同一画面下发控制、接收模拟设备消息并查看执行结果。</p></div><div class="debug-actions"><span v-if="opened" role="status">模拟设备：{{simulatorState==='connected'?'已连接':simulatorState==='connecting'?'连接中':'未连接'}}</span><el-button v-if="canSimulate" @click="opened?expanded=!expanded:(opened=true,expanded=true)">{{!opened?'打开模拟设备':expanded?'收起模拟面板':'展开模拟面板'}}</el-button></div></header>
  <div class="debug-columns" :class="{'with-simulator':opened&&expanded&&canSimulate}">
   <section class="platform-side" aria-label="平台端控制"><DeviceCommandPanel :scope="scope" :device="detail.device" :model="detail.model.model" :writable="writable&&detail.product.enabled" @dispatched="dispatched"/></section>
   <aside v-if="opened&&canSimulate" v-show="expanded" class="device-side" aria-label="模拟设备端"><Simulator :scope="scope" :detail="detail" :allowed="writable&&canSimulate" :presence="presence" embedded :focus-message-id="messageId" @connection-state="simulatorState=$event"/></aside>
  </div>
 </div>
</template>
<style scoped>
.debug-heading{display:flex;justify-content:space-between;align-items:flex-start;gap:16px;margin-bottom:24px;flex-wrap:wrap}.debug-heading h2{font-size:18px;margin:0 0 8px}.debug-heading p{font-size:13px;color:var(--text-secondary);margin:0;line-height:1.7}.debug-actions{display:flex;gap:12px;align-items:center;flex-wrap:wrap}.debug-actions>span{font-size:12px;color:var(--text-secondary)}.debug-columns{display:grid;grid-template-columns:minmax(0,1fr);gap:24px;align-items:start}.debug-columns.with-simulator{grid-template-columns:minmax(0,1.15fr) minmax(360px,1fr)}.platform-side,.device-side{min-width:0}.device-side{background:var(--surface);border:1px solid var(--line);border-radius:12px;padding:18px;position:sticky;top:16px;max-height:calc(100vh - 48px);overflow:auto}.platform-side :deep(.control-heading h2){font-size:15px}.platform-side :deep(.control-workspace){grid-template-columns:minmax(170px,210px) minmax(0,1fr)}.platform-side :deep(.control-modes button){padding:10px;gap:8px}.platform-side :deep(.editor-header),.platform-side :deep(.request-footer){padding:16px}.platform-side :deep(.editor-fields){padding:0 16px}@media(max-width:1200px){.debug-columns.with-simulator{grid-template-columns:minmax(0,1fr)}.device-side{position:static;max-height:none}.platform-side :deep(.control-workspace){grid-template-columns:minmax(200px,280px) minmax(0,1fr)}}@media(max-width:600px){.platform-side :deep(.control-workspace){grid-template-columns:1fr}.device-side{padding:12px}.debug-actions{width:100%}}
</style>
