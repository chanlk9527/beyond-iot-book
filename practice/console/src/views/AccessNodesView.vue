<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { accessNodesApi, type AccessNode, type AccessNodesSnapshot } from '../api/accessNodes'
import { errorMessage, isCancelled } from '../api/management'
import StatePanel from '../components/StatePanel.vue'

const snapshot = ref<AccessNodesSnapshot | null>(null)
const loading = ref(false)
const error = ref('')
const automatic = ref(true)
const showHistory = ref(false)
const selectedId = ref('')
const life = new AbortController()
let timer: ReturnType<typeof setTimeout> | undefined
const nodes = computed(() => snapshot.value?.nodes ?? [])
const selected = computed(() => nodes.value.find(node => node.nodeId === selectedId.value))
const clustered = computed(() => snapshot.value?.scope === 'CLUSTER_MEMBERSHIP')
const available = computed(() => nodes.value.filter(node => node.metrics !== null && node.fresh !== false))
const sum = (key: 'onlineDevices' | 'connections' | 'acceptedMessages' | 'rejectedMessages') =>
  available.value.length && !(clustered.value && snapshot.value?.complete === false) ? available.value.reduce((total, node) => total + node.metrics![key], 0) : null
const number = (value: number | null | undefined) => value == null ? '—' : value.toLocaleString('zh-CN')
const time = (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
const status = { RUNNING: '监听中', STOPPED: '已停止', DISABLED: '未启用' } as const
const lifecycle = { STARTING:'启动中', ACTIVE:'接入中', DRAINING:'排空中', STOPPING:'停止中', OFFLINE:'已退出', UNREACHABLE:'已失联' } as const
const nodeStatus = (node: AccessNode) => node.lifecycle ? (node.fresh === false && node.lifecycle === 'ACTIVE' ? '指标过期' : lifecycle[node.lifecycle]) : status[node.status]
const duration = (millis: number) => {
  const minutes = Math.floor(millis / 60_000)
  return `${Math.floor(minutes / 60)} 小时 ${minutes % 60} 分钟`
}
const usage = (node: AccessNode) => node.metrics ? Math.min(100, Math.round(node.metrics.connections / node.metrics.maxConnections * 100)) : 0
async function load() {
  if (loading.value || life.signal.aborted) return
  if (timer) clearTimeout(timer)
  loading.value = true
  try {
    const value = await accessNodesApi.snapshot(life.signal, showHistory.value)
    if (life.signal.aborted) return
    snapshot.value = value
    error.value = ''
  } catch (cause) {
    if (!isCancelled(cause)) error.value = errorMessage(cause)
  } finally {
    loading.value = false
    if (!life.signal.aborted) timer = setTimeout(() => {
      if (automatic.value && !document.hidden) void load()
      else schedule()
    }, 5000)
  }
}
function schedule() {
  if (life.signal.aborted) return
  timer = setTimeout(() => {
    if (automatic.value && !document.hidden) void load()
    else schedule()
  }, 5000)
}
onMounted(() => { void load() })
onBeforeUnmount(() => { life.abort(); if (timer) clearTimeout(timer) })
</script>

<template>
  <div class="page-heading">
    <div><p class="eyebrow">DEVICE ACCESS</p><h1>设备接入</h1><p>查看接入节点的运行情况、设备连接与消息处理。</p></div>
    <div class="node-actions"><el-switch v-if="clustered" v-model="showHistory" :disabled="loading" active-text="查看历史实例" aria-label="查看历史实例" @change="load"/><el-switch v-model="automatic" active-text="每 5 秒刷新" aria-label="自动刷新"/><el-button :loading="loading" @click="load">刷新</el-button></div>
  </div>
  <div class="node-scope"><el-tag effect="plain">{{ clustered ? '节点成员目录' : '当前单节点' }}</el-tag><span>{{ clustered ? '节点按心跳采样，在线设备按有效会话归属去重。' : '仅展示当前后端进程，尚未汇总集群节点。' }}</span><span v-if="snapshot">采样时间：{{ time(snapshot.sampledAt) }}</span></div>
  <el-alert v-if="error && snapshot" class="node-error" type="error" :closable="false" :title="`刷新失败，以下为上次采样数据：${error}`"/>
  <section v-if="!snapshot && loading" class="panel node-panel"><el-skeleton :rows="6" animated/></section>
  <StatePanel v-else-if="!snapshot && error" title="接入节点加载失败" :description="error"><el-button @click="load">重试</el-button></StatePanel>
  <template v-else-if="snapshot">
    <el-alert v-if="clustered && snapshot.complete === false" class="node-error" title="部分节点指标已过期或未完整展示，连接与消息总量暂不可用。" type="warning" :closable="false"/>
    <div class="node-summary">
      <section class="panel summary-card"><p>接入节点</p><strong>{{ nodes.filter(node => node.status === 'RUNNING').length }}<small> / {{ nodes.length }}</small></strong><span class="metric-caption">{{ clustered ? '可观测 / 已展示' : '监听中 / 当前进程' }}</span></section>
      <section class="panel summary-card"><p>在线设备</p><strong>{{ number(clustered ? snapshot.onlineDevices : sum('onlineDevices')) }}</strong><span class="metric-caption">当前认证设备会话数</span></section>
      <section class="panel summary-card"><p>网络连接</p><strong>{{ number(sum('connections')) }}</strong><span class="metric-caption">包含认证中的连接</span></section>
      <section class="panel summary-card"><p>上行处理成功 / 拒绝</p><strong class="node-message-count">{{ number(sum('acceptedMessages')) }} / {{ number(sum('rejectedMessages')) }}</strong><span class="metric-caption">网关实例累计，重启后重置</span></section>
    </div>
    <section class="panel node-panel">
      <div class="node-section-heading"><div><h2>{{ showHistory ? '接入实例（含历史）' : '接入节点' }}</h2><p>{{ clustered ? '默认隐藏已退出和超过 2 分钟未心跳的失联实例；近期失联仍保留，便于发现故障。' : '连接数与会话数为采样值，变化期间可能有短暂差异。' }}</p></div><span>{{ nodes.length }} / {{ snapshot.totalNodes ?? nodes.length }} 个节点</span></div>
      <el-table :data="nodes" row-key="nodeId" empty-text="暂无接入节点">
        <el-table-column label="节点" min-width="190"><template #default="{row}"><strong>{{ row.name }}</strong><div class="node-id">{{ row.nodeId }}</div></template></el-table-column>
        <el-table-column label="运行状态" width="110"><template #default="{row}"><el-tag :type="row.status === 'RUNNING' ? 'success' : 'info'" effect="plain">{{ nodeStatus(row) }}</el-tag></template></el-table-column>
        <el-table-column prop="protocol" label="接入协议" width="105"/>
        <el-table-column label="在线设备" width="110"><template #default="{row}">{{ number(row.metrics?.onlineDevices) }}</template></el-table-column>
        <el-table-column label="连接容量" min-width="170"><template #default="{row}"><template v-if="row.metrics"><div>{{ number(row.metrics.connections) }} / {{ number(row.metrics.maxConnections) }}</div><el-progress :percentage="usage(row)" :stroke-width="5" :show-text="false" :status="usage(row) >= 90 ? 'warning' : undefined"/></template><span v-else>—</span></template></el-table-column>
        <el-table-column label="进程运行时间" min-width="145"><template #default="{row}">{{ duration(row.processUptimeMillis) }}</template></el-table-column>
        <el-table-column label="操作" width="90"><template #default="{row}"><el-button link type="primary" @click="selectedId = row.nodeId">详情</el-button></template></el-table-column>
      </el-table>
    </section>
    <p class="node-footnote">在线表示当前持有认证会话，不代表设备业务执行正常。上行处理成功不等于业务持久化完成；拒绝数不包含连接认证失败。</p>
  </template>
  <el-drawer :model-value="!!selected" title="节点详情" size="min(560px, 96vw)" @close="selectedId = ''">
    <template v-if="selected">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="节点名称">{{ selected.name }}</el-descriptions-item>
        <el-descriptions-item label="实例标识">{{ selected.nodeId }}</el-descriptions-item>
        <el-descriptions-item label="运行状态">{{ nodeStatus(selected) }}</el-descriptions-item>
        <el-descriptions-item :label="clustered ? '节点注册时间' : '进程启动时间'">{{ time(selected.processStartedAt) }}</el-descriptions-item>
        <el-descriptions-item :label="clustered ? '最近记录运行时间' : '进程运行时间'">{{ duration(selected.processUptimeMillis) }}</el-descriptions-item>
        <el-descriptions-item v-if="selected.lastHeartbeat" label="最后心跳">{{ time(selected.lastHeartbeat) }}</el-descriptions-item>
        <template v-if="selected.metrics">
          <el-descriptions-item label="监听地址">{{ selected.metrics.host }}</el-descriptions-item>
          <el-descriptions-item label="监听端口">{{ selected.metrics.port ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="在线设备">{{ number(selected.metrics.onlineDevices) }}</el-descriptions-item>
          <el-descriptions-item label="网络连接 / 上限">{{ number(selected.metrics.connections) }} / {{ number(selected.metrics.maxConnections) }}</el-descriptions-item>
          <el-descriptions-item label="上行处理成功">{{ number(selected.metrics.acceptedMessages) }}</el-descriptions-item>
          <el-descriptions-item label="上行拒绝">{{ number(selected.metrics.rejectedMessages) }}</el-descriptions-item>
          <el-descriptions-item label="工作线程忙碌 / 配置">{{ selected.metrics.activeWorkers }} / {{ selected.metrics.workerThreads }}</el-descriptions-item>
          <el-descriptions-item label="工作队列 / 上限">{{ selected.metrics.queuedWork }} / {{ selected.metrics.maxQueuedWork }}</el-descriptions-item>
        </template>
      </el-descriptions>
      <p class="node-footnote">{{ selected.metrics ? '实例标识在进程重启后变化，累计指标不跨重启保留。监听状态不代表所有下游依赖健康。' : (clustered ? '节点指标过期或暂不可用，请查看最后心跳。' : '当前进程未启用 MQTT 接入，连接和消息指标不可用。') }}</p>
    </template>
  </el-drawer>
</template>

<style scoped>
.node-actions,.node-scope,.node-section-heading{display:flex;align-items:center;gap:16px;flex-wrap:wrap}
.node-scope{font-size:13px;color:var(--el-text-color-secondary);margin-bottom:20px}
.node-scope>span:last-child{margin-left:auto}
.node-summary{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:18px;margin-bottom:24px}
.node-summary small{font-size:18px;font-weight:400;color:var(--el-text-color-secondary)}
.node-summary .node-message-count{font-size:28px}
.node-panel{padding:24px;overflow:hidden}.node-section-heading{justify-content:space-between;margin-bottom:18px}
.node-section-heading h2{margin:0;font-size:18px}.node-section-heading p,.node-footnote{font-size:13px;color:var(--el-text-color-secondary);line-height:1.7}
.node-section-heading p{margin:8px 0 0}.node-id{font-size:11px;color:var(--el-text-color-secondary);overflow-wrap:anywhere;margin-top:6px}
.node-error{margin-bottom:20px}.node-footnote{margin-top:16px}.el-progress{margin-top:8px}
@media(max-width:1100px){.node-summary{grid-template-columns:repeat(2,minmax(0,1fr))}}
@media(max-width:600px){.node-summary{grid-template-columns:1fr}.node-scope>span:last-child{margin-left:0}.node-panel{padding:16px}.node-actions{margin-top:12px}}
</style>
