<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { ElDrawer } from 'element-plus'
import { eventbusApi, type EventSnapshot, type EventSubscription, type Backlog } from '../api/eventbus'
import { errorMessage, isCancelled } from '../api/management'
const snapshot=ref<EventSnapshot|null>(null), subscriptions=ref<EventSubscription[]>([])
const loading=ref(false), error=ref(''), automatic=ref(true), page=ref(1), total=ref(0)
const selectedNode=ref(''), selectedSubscription=ref<EventSubscription|null>(null), detail=ref<Backlog|null>(null), detailError=ref('')
const life=new AbortController()
let timer:ReturnType<typeof setTimeout>|undefined
let detailRequest:AbortController|undefined
const node=computed(()=>snapshot.value?.nodes.find(n=>n.directory.id===selectedNode.value))
const number=(v:number|null|undefined)=>v==null?'—':v.toLocaleString('zh-CN')
const time=(v:string|number|null|undefined)=>v==null?'—':new Date(v).toLocaleString('zh-CN',{hour12:false})
const label=(s:string)=>({READY:'就绪',STARTING:'启动中',DRAINING:'排空中',OFFLINE:'已退出',UNREACHABLE:'不可达',UNAVAILABLE:'采集不可用',AVAILABLE:'可观测',DEGRADED:'未就绪'}[s]||s)
const sourceLabel=(s:string)=>({LOCAL:'仅本地',REMOTE:'仅远程',ALL:'本地和远程'}[s]||'本地和远程')
const sum=(key:'published'|'matched'|'routed'|'rpcSent'|'rpcReceived'|'completed'|'failed')=>{
  const valid=snapshot.value?.nodes.filter(n=>n.metrics)
  return valid?.length?valid.reduce((a,n)=>a+n.metrics![key],0):null
}
function schedule(){
  if(life.signal.aborted)return
  timer=setTimeout(()=>{if(automatic.value&&!document.hidden)void load();else schedule()},5000)
}
async function load(){
  if(loading.value||life.signal.aborted)return
  if(timer)clearTimeout(timer)
  loading.value=true
  try {
    const [s,p]=await Promise.all([eventbusApi.snapshot(life.signal),eventbusApi.subscriptions(page.value-1,life.signal)])
    if(life.signal.aborted)return
    snapshot.value=s;subscriptions.value=p.items;total.value=p.total;error.value=''
  }catch(e){if(!isCancelled(e))error.value=errorMessage(e)}
  finally{loading.value=false;schedule()}
}
async function showSubscription(value:EventSubscription){
  detailRequest?.abort();detailRequest=new AbortController()
  const current=detailRequest;selectedSubscription.value=value;detail.value=null;detailError.value=''
  try{const data=await eventbusApi.backlog(value.id,AbortSignal.any([life.signal,current.signal]));if(!current.signal.aborted)detail.value=data}
  catch(e){if(!isCancelled(e))detailError.value=errorMessage(e)}
}
function memberState(id:string){
  const member=snapshot.value?.nodes.find(n=>n.directory.id===id)
  return member?`${label(member.directory.state)} · ${label(member.observation)} · 版本 ${number(member.metrics?.revision)} / ${number(snapshot.value?.directory?.revision)}`:'未采集'
}
onMounted(load)
onBeforeUnmount(()=>{life.abort();detailRequest?.abort();if(timer)clearTimeout(timer)})
</script>

<template>
  <header class="page-heading"><div><h1>事件总线</h1><p>查看集群路由、节点运行状态与订阅积压</p></div><div class="actions"><el-switch v-model="automatic" active-text="每 5 秒刷新"/><el-button :loading="loading" @click="load">刷新</el-button></div></header>
  <el-alert v-if="error" type="error" :closable="false" :title="error" :description="snapshot?'当前显示上次采样数据，已过期。':''"/>
  <p v-if="!snapshot&&!error">正在采集事件总线状态…</p>
  <template v-if="snapshot">
    <div class="scope"><el-tag>{{ snapshot.mode.toUpperCase() }}</el-tag><span>{{ snapshot.directory?.namespace }}</span><span>采样于 {{ time(snapshot.sampledAt) }}</span></div>
    <el-alert v-if="!snapshot.directory" title="当前模式未提供集群观测" :description="`当前使用 ${snapshot.mode} 模式，此面板的节点与订阅观测适用于 Dubbo 模式。`" type="info" :closable="false"/>
    <template v-else>
      <el-alert v-if="!snapshot.complete" title="采集不完整" description="部分节点不可达或未采集，下方流量仅包含成功采集的实例；不可达不代表零流量。" type="warning" :closable="false"/>
      <div class="cards">
        <section class="panel summary-card"><p>已采集 / 有效节点</p><strong>{{ snapshot.collected }} / {{ snapshot.directory.activeNodes }}</strong><span>当前实例 {{ snapshot.directory.totalNodes }} 个</span></section>
        <section class="panel summary-card"><p>活跃逻辑订阅</p><strong>{{ number(snapshot.directory.subscriptions) }}</strong><span>目录版本 {{ snapshot.directory.revision }}</span></section>
        <section class="panel summary-card"><p>待发送 / 待处理</p><strong>{{ number(snapshot.directory.backlog.pending) }} / {{ number(snapshot.directory.backlog.received) }}</strong><span>最早积压 {{ time(snapshot.directory.backlog.oldest) }}</span></section>
        <section class="panel summary-card"><p>有重试的未完成投递</p><strong>{{ number(snapshot.directory.backlog.retrying) }}</strong><span>按投递记录计数</span></section>
      </div>
      <section class="panel section"><h2>消息处理链路 <small>{{ snapshot.complete?'当前采集实例':'部分采集实例' }}累计</small></h2>
        <div class="flow"><div v-for="item in [{title:'发布事件',key:'published'},{title:'匹配事件',key:'matched'},{title:'持久化逻辑投递',key:'routed'},{title:'远端投递确认',key:'rpcSent'},{title:'回调完成',key:'completed'}] as const" :key="item.key"><span>{{ item.title }}</span><strong>{{ number(sum(item.key)) }}</strong></div></div>
        <p class="muted">累计值随实例重启重置，包含重试；一条事件可生成多条逻辑投递。远端投递确认按事件条目计数，不包含本地投递。</p>
      </section>
      <section class="panel section"><h2>节点 <small>展示当前的 {{ snapshot.nodes.length }} 个实例，最多 100 个；已退出实例自动移除</small></h2>
        <el-table :data="snapshot.nodes" row-key="directory.id" empty-text="暂无节点">
          <el-table-column label="节点 / 角色" min-width="220"><template #default="{row}"><span class="id">{{ row.directory.id }}</span><div class="muted">{{ row.metrics?.role || '—' }}</div></template></el-table-column>
          <el-table-column label="目录状态" width="110"><template #default="{row}">{{ label(row.directory.state) }}</template></el-table-column>
          <el-table-column label="采集状态" width="120"><template #default="{row}"><el-tag :type="row.observation==='AVAILABLE'?'success':'warning'">{{ label(row.observation) }}</el-tag></template></el-table-column>
          <el-table-column label="本地 / 全局版本" width="150"><template #default="{row}">{{ number(row.metrics?.revision) }} / {{ snapshot.directory.revision }}</template></el-table-column>
          <el-table-column label="发布 / 匹配" width="150"><template #default="{row}">{{ number(row.metrics?.published) }} / {{ number(row.metrics?.matched) }}</template></el-table-column>
          <el-table-column label="回调失败" width="100"><template #default="{row}">{{ number(row.metrics?.failed) }}</template></el-table-column>
          <el-table-column width="80"><template #default="{row}"><el-button link type="primary" @click="selectedNode=row.directory.id">详情</el-button></template></el-table-column>
        </el-table>
      </section>
      <section class="panel section"><h2>订阅</h2>
        <el-table :data="subscriptions" row-key="id" empty-text="暂无活跃订阅">
          <el-table-column prop="name" label="名称" min-width="180"/>
          <el-table-column label="类型 / 代次" width="150"><template #default="{row}">{{ row.mode==='SHARED'?'共享':'广播' }} / {{ row.generation }}</template></el-table-column>
          <el-table-column label="消息来源" width="130"><template #default="{row}">{{ sourceLabel(row.sourceScope) }}</template></el-table-column>
          <el-table-column label="主题" min-width="180"><template #default="{row}">{{ row.patterns.join(', ') }}</template></el-table-column>
          <el-table-column label="范围" min-width="150"><template #default="{row}">{{ row.tenantId || '全部租户' }} · {{ row.productId ? '产品 '+row.productId+' · ' : '' }}{{ row.deviceCount?`${row.deviceCount} 台设备`:'全部设备' }}</template></el-table-column>
          <el-table-column label="已展示成员" width="110"><template #default="{row}">{{ row.members.length }}</template></el-table-column>
          <el-table-column width="90"><template #default="{row}"><el-button link type="primary" @click="showSubscription(row)">详情</el-button></template></el-table-column>
        </el-table>
        <el-pagination v-model:current-page="page" :disabled="loading" :page-size="20" :total="total" layout="prev, pager, next, total" @current-change="load"/>
      </section>
    </template>
  </template>
  <el-drawer :model-value="!!node" title="节点详情" direction="rtl" size="min(620px,100vw)" append-to-body destroy-on-close @close="selectedNode=''">
    <template v-if="node">
      <div class="node-detail-heading">
        <h2 class="node-detail-id">{{ node.directory.id }}</h2>
        <div class="node-detail-status"><el-tag>{{ label(node.directory.state) }}</el-tag><el-tag :type="node.observation==='AVAILABLE'?'success':'warning'">{{ label(node.observation) }}</el-tag><span class="muted">{{ node.metrics?.role || '角色未采集' }}</span></div>
      </div>
      <el-descriptions :column="1" border>
        <el-descriptions-item label="实例">{{ node.directory.id }}</el-descriptions-item>
        <el-descriptions-item label="Dubbo 地址">{{ node.directory.endpoint }}</el-descriptions-item>
        <el-descriptions-item label="租约期限">{{ time(node.directory.leaseUntil) }}</el-descriptions-item>
        <el-descriptions-item label="最近成功采集">{{ time(node.lastSuccessAt) }}</el-descriptions-item>
        <el-descriptions-item label="目录 / 实际应用版本">{{ node.directory.revision }} / {{ number(node.metrics?.revision) }}</el-descriptions-item>
        <template v-if="node.metrics">
          <el-descriptions-item label="实例启动时间">{{ time(node.metrics.startedAt) }}</el-descriptions-item>
          <el-descriptions-item label="距上次路由同步">{{ number(node.metrics.syncAgeMillis) }} ms</el-descriptions-item>
          <el-descriptions-item label="本地订阅数">{{ node.metrics.subscriptions }}</el-descriptions-item>
          <el-descriptions-item label="RPC 条目发送 / 接收">{{ number(node.metrics.rpcSent) }} / {{ number(node.metrics.rpcReceived) }}</el-descriptions-item>
          <el-descriptions-item label="回调成功 / 失败">{{ number(node.metrics.completed) }} / {{ number(node.metrics.failed) }}</el-descriptions-item>
          <el-descriptions-item label="平均处理耗时">{{ node.metrics.completed+node.metrics.failed ? (node.metrics.handlerNanos/1e6/(node.metrics.completed+node.metrics.failed)).toFixed(2)+' ms':'—' }}</el-descriptions-item>
          <el-descriptions-item label="处理中回调">{{ node.metrics.activeHandlers }} / 4</el-descriptions-item>
          <el-descriptions-item label="持久化队列">{{ node.metrics.persistenceQueued }} / {{ node.metrics.persistenceCapacity }}</el-descriptions-item>
        </template>
      </el-descriptions>
      <p v-if="!node.metrics" class="muted">当前指标不可用，未用零值代替。</p>
    </template>
  </el-drawer>
  <el-drawer :model-value="!!selectedSubscription" title="订阅详情" direction="rtl" size="min(620px,100vw)" append-to-body destroy-on-close @close="selectedSubscription=null;detailRequest?.abort()">
    <template v-if="selectedSubscription">
      <div class="subscription-heading">
        <h2>{{ selectedSubscription.name }}</h2>
        <div class="subscription-badges"><el-tag>{{ selectedSubscription.mode==='SHARED'?'共享订阅':'广播订阅' }}</el-tag><el-tag type="info" effect="plain">代次 {{ selectedSubscription.generation }}</el-tag></div>
        <p class="subscription-route"><span>路由 ID</span><code>{{ selectedSubscription.id }}</code></p>
      </div>
      <section class="subscription-section" aria-labelledby="subscription-backlog-title">
        <div class="subscription-section-heading"><h3 id="subscription-backlog-title">投递积压</h3><span class="muted">打开详情时采样</span></div>
        <el-alert v-if="detailError" :title="detailError" type="error" :closable="false"/>
        <template v-else-if="detail">
          <dl class="backlog-metrics"><div v-for="item in [{key:'pending',title:'待发送'},{key:'received',title:'待处理'},{key:'retrying',title:'有重试'}] as const" :key="item.key"><dt>{{ item.title }}</dt><dd>{{ number(detail[item.key]) }}</dd></div></dl>
          <p class="subscription-note">最早积压：{{ time(detail.oldest) }}</p>
        </template>
        <p v-else class="subscription-note" role="status">正在读取积压…</p>
      </section>
      <section class="subscription-section" aria-labelledby="subscription-rules-title">
        <div class="subscription-section-heading"><h3 id="subscription-rules-title">订阅规则</h3></div>
        <dl class="subscription-rules">
          <div><dt>消息来源</dt><dd>{{ sourceLabel(selectedSubscription.sourceScope) }}<span class="subscription-note">以最初发布进程为准</span></dd></div>
          <div><dt>主题</dt><dd class="subscription-topics"><code v-for="pattern in selectedSubscription.patterns" :key="pattern">{{ pattern }}</code><span v-if="!selectedSubscription.patterns.length" class="muted">暂无主题</span></dd></div>
          <div><dt>租户范围</dt><dd>{{ selectedSubscription.tenantId || '全部租户' }}</dd></div>
          <div><dt>产品范围</dt><dd>{{ selectedSubscription.productId || '全部产品' }}</dd></div>
          <div><dt>设备范围</dt><dd>{{ selectedSubscription.deviceCount?`${number(selectedSubscription.deviceCount)} 台设备`:'全部设备' }}</dd></div>
          <div v-if="selectedSubscription.deviceCount"><dt>设备样例<span class="subscription-note">最多 20 个</span></dt><dd class="subscription-devices"><code v-for="device in selectedSubscription.deviceSample" :key="device">{{ device }}</code><span v-if="!selectedSubscription.deviceSample.length" class="muted">暂无样例</span></dd></div>
        </dl>
      </section>
      <section class="subscription-section" aria-labelledby="subscription-members-title">
        <div class="subscription-section-heading"><h3 id="subscription-members-title">接收成员 <span class="member-count">{{ selectedSubscription.members.length }}</span></h3><span class="muted">最多展示 100 个</span></div>
        <ul v-if="selectedSubscription.members.length" class="subscription-members"><li v-for="member in selectedSubscription.members" :key="member"><code>{{ member }}</code><span class="subscription-note">{{ memberState(member) }}</span></li></ul>
        <div v-else class="subscription-empty"><p>当前无接收成员</p><span v-if="selectedSubscription.mode==='SHARED'" class="subscription-note">共享订阅积压会保留，等待成员接收。</span></div>
      </section>
    </template>
  </el-drawer>
</template>
<style scoped>
.actions,.scope{display:flex;gap:16px;align-items:center;flex-wrap:wrap}.scope{margin:18px 0;color:var(--el-text-color-secondary)}
.cards{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:18px;margin:20px 0}.section{padding:24px;margin-bottom:20px}
h2{font-size:18px;margin:0 0 18px}small,.muted,.summary-card>span{font-size:12px;color:var(--el-text-color-secondary);font-weight:400}.id{overflow-wrap:anywhere;font-size:12px}
.flow{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:16px}.flow div{display:flex;flex-direction:column;gap:8px}.flow strong{font-size:25px}.el-pagination{margin-top:18px}.el-alert{margin-bottom:16px}
.node-detail-heading{margin-bottom:24px}.node-detail-id{overflow-wrap:anywhere;line-height:1.5;margin-bottom:12px}.node-detail-status{display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.subscription-heading{margin-bottom:28px}.subscription-heading h2{font-size:22px;line-height:1.4;overflow-wrap:anywhere;margin-bottom:12px}.subscription-badges{display:flex;gap:8px;flex-wrap:wrap}.subscription-route{margin:16px 0 0;display:flex;flex-direction:column;gap:6px;font-size:12px;color:var(--el-text-color-secondary)}.subscription-route code{line-height:1.6;overflow-wrap:anywhere}
.subscription-section{border-top:1px solid var(--el-border-color-lighter);padding-top:22px;margin-top:24px}.subscription-section-heading{display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap;margin-bottom:16px}.subscription-section h3{font-size:15px;font-weight:600;margin:0}.subscription-note{display:block;font-size:12px;color:var(--el-text-color-secondary);line-height:1.7;margin:6px 0 0;overflow-wrap:anywhere}
.backlog-metrics{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin:0}.backlog-metrics>div{padding:16px 12px;background:var(--el-fill-color-light);border:1px solid var(--el-border-color-lighter);border-radius:10px}.backlog-metrics dt{font-size:12px;color:var(--el-text-color-secondary)}.backlog-metrics dd{font-size:24px;font-weight:600;line-height:1.4;margin:8px 0 0;overflow-wrap:anywhere;font-variant-numeric:tabular-nums}
.subscription-rules{margin:0}.subscription-rules>div{display:grid;grid-template-columns:88px minmax(0,1fr);gap:16px;padding:12px 0}.subscription-rules dt{font-size:13px;color:var(--el-text-color-secondary);line-height:1.7}.subscription-rules dd{margin:0;font-size:13px;line-height:1.7;overflow-wrap:anywhere}.subscription-topics{display:flex;flex-wrap:wrap;gap:8px}.subscription-topics code{background:var(--el-fill-color-light);border-radius:5px;padding:2px 8px;white-space:normal;overflow-wrap:anywhere;max-width:100%}.subscription-devices code{display:block;overflow-wrap:anywhere}
.member-count{font-size:12px;font-weight:400;color:var(--el-text-color-secondary);margin-left:6px}.subscription-members{list-style:none;padding:0;margin:0;border:1px solid var(--el-border-color-lighter);border-radius:10px;overflow:hidden}.subscription-members li{padding:14px 16px}.subscription-members li+li{border-top:1px solid var(--el-border-color-lighter)}.subscription-members code{font-size:13px;line-height:1.6;overflow-wrap:anywhere}.subscription-empty{padding:20px;background:var(--el-fill-color-light);border-radius:10px}.subscription-empty p{margin:0;font-size:13px}
@media(max-width:1000px){.cards,.flow{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:600px){.cards,.flow{grid-template-columns:1fr}.section{padding:16px}}
</style>
