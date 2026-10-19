<script setup lang="ts">
import { computed, nextTick, onScopeDispose, ref, watch } from 'vue'
import { ElButton, ElOption, ElSelect, ElTable, ElTableColumn, ElAlert, ElSkeleton } from 'element-plus'
import { historyApi, type HistoryItem } from '../api/history'
import type { Device, ModelSpec } from '../api/catalog'
import { resourceScope } from '../api/catalog'
import { errorMessage, isCancelled } from '../api/management'
const props = defineProps<{ scope: string; device: Device; model: ModelSpec; initialKind?: 'PROPERTY' | 'EVENT'; eventOnly?: boolean }>()
const kind = ref<'PROPERTY' | 'EVENT'>(props.eventOnly ? 'EVENT' : props.initialKind || 'PROPERTY')
const identifier = ref('')
const localTime = (date: Date) => new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 19)
const to = ref(localTime(new Date(Date.now() + 1000))), from = ref(localTime(new Date(Date.now() - 86400000)))
const items = ref<HistoryItem[]>([]), cursor = ref<string | null>(null), error = ref(''), loading = ref(false), queried = ref(false)
const identifiers = computed(() => Object.keys(kind.value === 'PROPERTY' ? props.model.properties : props.model.events))
let active = new AbortController(), generation = 0
watch(kind, () => { identifier.value = '' })
watch(identifiers, ids => { if (!identifier.value && ids.length) identifier.value = ids[0] }, { immediate: true })
watch(() => [props.scope, props.device.id, kind.value, identifier.value, from.value, to.value], () => {
  generation++; active.abort(); active = new AbortController(); loading.value = false
  items.value = []; cursor.value = null; queried.value = false; error.value = ''
})
async function load(next = false) {
  if (loading.value || !identifier.value.trim()) return
  const start = new Date(from.value), end = new Date(to.value)
  if (!Number.isFinite(start.getTime()) || !Number.isFinite(end.getTime()) || start >= end || end.getTime() - start.getTime() > 366 * 86400000) {
    error.value = '请选择开始早于结束、跨度不超过 366 天的时间范围。'; return
  }
  const current = ++generation; loading.value = true; error.value = ''
  try {
    const result = await historyApi.query(resourceScope(props.scope, props.device), props.device.id, kind.value, identifier.value.trim(), start.toISOString(), end.toISOString(), next ? cursor.value : null, active.signal)
    if (current !== generation) return
    items.value = result.items; cursor.value = result.nextCursor; queried.value = true
  } catch (e) { if (current === generation && !isCancelled(e)) error.value = errorMessage(e) }
  finally { if (current === generation) loading.value = false }
}
onScopeDispose(() => { generation++; active.abort() })
async function recent() {
  const now = Date.now(); from.value = localTime(new Date(now - 86400000)); to.value = localTime(new Date(now + 1000))
  await nextTick(); await load()
}
</script>
<template>
  <section class="device-history">
    <h2>{{eventOnly ? '事件记录' : '设备历史'}}</h2><p>{{eventOnly ? '按事件标识查询设备实际发生的事件，查看发生时间、接收时间和完整字段。' : '属性只记录实际上报的观测，事件保留全部字段；未上报的属性不会补成新历史。'}}</p>
    <div class="history-controls">
      <label v-if="!eventOnly">数据类型<el-select v-model="kind" aria-label="历史数据类型"><el-option value="PROPERTY" label="属性观测"/><el-option value="EVENT" label="完整事件"/></el-select></label>
      <label>{{kind==='PROPERTY'?'属性标识':'事件标识'}}<el-select v-model="identifier" filterable allow-create default-first-option placeholder="选择或输入历史标识" aria-label="历史标识"><el-option v-for="id in identifiers" :key="id" :value="id" :label="id"/></el-select></label>
      <label>开始时间<input v-model="from" type="datetime-local" step="1" aria-label="历史开始时间"/></label>
      <label>结束时间<input v-model="to" type="datetime-local" step="1" aria-label="历史结束时间"/></label>
      <el-button type="primary" :disabled="!identifier.trim()" :loading="loading" @click="load()">查询历史</el-button>
      <el-button :disabled="loading || !identifier.trim()" @click="recent">最近 24 小时</el-button>
    </div>
    <p class="history-note">可输入旧物模型中的标识查询保留期内的历史。结果按观测时间倒序，数值以原始 JSON 展示，避免大整数或小数被浏览器舍入。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false"/>
    <el-skeleton v-if="loading" :rows="4" animated/>
    <el-table v-else :data="items" :empty-text="queried?'该时间范围内没有保留的记录':'选择标识与时间范围后查询'">
      <el-table-column label="观测／发生时间" min-width="185"><template #default="{row}">{{new Date(row.occurredAt).toLocaleString()}}</template></el-table-column>
      <el-table-column label="平台接收时间" min-width="185"><template #default="{row}">{{new Date(row.receivedAt).toLocaleString()}}</template></el-table-column>
      <el-table-column prop="modelVersion" label="模型版本" width="100"/>
      <el-table-column prop="valueType" label="类型" width="100"/>
      <el-table-column label="观测值／事件字段" min-width="240"><template #default="{row}"><pre>{{row.payload}}</pre></template></el-table-column>
      <el-table-column prop="messageId" label="消息 ID" min-width="220"/>
    </el-table>
    <div class="history-pagination"><el-button :disabled="loading||!queried" @click="load()">返回首屏</el-button><el-button :disabled="loading||!cursor" @click="load(true)">更早记录</el-button></div>
  </section>
</template>
<style scoped>
.device-history h2{font-size:16px}.device-history>p{font-size:12px;color:#84998e;line-height:1.8;margin:10px 0 18px}.history-controls{display:flex;gap:14px;align-items:flex-end;flex-wrap:wrap;margin-bottom:18px}.history-controls label{display:flex;flex-direction:column;gap:8px;min-width:160px;font-size:12px;color:#557265}.history-controls .el-select{width:190px}.history-controls input{height:32px;border:1px solid #dce6e0;border-radius:4px;padding:0 8px;color:#45695a;font:inherit}.device-history pre{white-space:pre-wrap;overflow-wrap:anywhere;font:12px/1.7 Consolas,monospace;margin:0}.history-pagination{display:flex;justify-content:flex-end;gap:10px;margin-top:16px}
</style>
