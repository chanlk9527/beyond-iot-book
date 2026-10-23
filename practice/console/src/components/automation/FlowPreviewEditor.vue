<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import AppIcon from '../AppIcon.vue'
import { devices, products, kindNames, fieldOptions, evaluateFlow, flowSql, sampleMessage, validateFlow, type Flow, type RuleStatus, useAutomationPreview } from '../../automation/preview'
const props = defineProps<{ flow: Flow }>()
const emit = defineEmits<{ close: []; save: [flow: Flow] }>()
const store = useAutomationPreview()
const form = ref<Flow>(JSON.parse(JSON.stringify(props.flow)))
const expressionView = ref(false), error = ref(''), sample = ref(''), result = ref<ReturnType<typeof evaluateFlow> | null>(null), testError = ref('')
const selectedDevices = computed(() => devices.filter(d => d.product === form.value.product))
const selectedDevice = computed({ get: () => form.value.device || '__all__', set: (value: string) => { form.value.device = value === '__all__' ? '' : value } })
const targets = computed(() => store.connections.filter(c => form.value.connectionIds.includes(c.id)))
const scopeName = computed(() => form.value.device ? devices.find(d => d.id === form.value.device)?.name : `${products.find(p => p.id === form.value.product)?.name} · 全部设备`)
watch(() => form.value.product, () => { form.value.device = '' })
watch(() => [form.value.product, form.value.device, form.value.kind], () => resetSample(), { immediate: true })
watch(form, () => { result.value = null; testError.value = ''; error.value = '' }, { deep: true })
watch(sample, () => { result.value = null; testError.value = '' })
function resetSample() { sample.value = JSON.stringify(sampleMessage(form.value.product, form.value.kind, form.value.device), null, 2); result.value = null }
function test() {
  testError.value = ''; result.value = null
  try {
    const issue = validateFlow({ ...form.value, name: form.value.name || '样例测试' }, store.connections)
    if (issue) throw new Error(issue)
    result.value = evaluateFlow(form.value, JSON.parse(sample.value))
  } catch (e) { testError.value = e instanceof Error ? e.message : '请输入有效的 JSON 消息' }
}
function save(status: RuleStatus) {
  error.value = validateFlow(form.value, store.connections)
  if (error.value) return
  emit('save', { ...JSON.parse(JSON.stringify(form.value)), status, version: form.value.version + (status === 'running' ? 1 : 0) })
}
</script>

<template>
  <div class="flow-editor">
    <div class="editor-heading"><div><button class="back-link" @click="emit('close')">← 返回数据流转</button><h1>{{ form.id ? '编辑流转规则' : '新建流转规则' }}</h1><p>从选择设备消息开始，按需要处理，再发送到你的业务系统。</p></div><div class="action-row"><el-button @click="save('draft')">保存演示草稿</el-button><el-button type="primary" @click="save('running')"><AppIcon name="workflow" :size="15"/>{{ form.status === 'running' ? '更新演示版本' : '模拟发布' }}</el-button></div></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="editor-alert"/>
    <div class="editor-layout">
      <div class="editor-main">
        <section class="panel config-section"><div class="section-title"><span class="step-number neutral">·</span><div><h2>基本信息</h2><p>为这条数据链路起一个容易识别的名称。</p></div></div><el-form label-position="top"><el-form-item label="规则名称（必填）"><el-input v-model="form.name" aria-label="规则名称" maxlength="60" placeholder="例如：暖奶器设备数据同步"/></el-form-item><el-form-item label="说明"><el-input v-model="form.description" aria-label="规则说明" maxlength="200" placeholder="描述这条规则的用途，方便团队协作"/></el-form-item></el-form></section>
        <section class="panel config-section"><div class="section-title"><span class="step-number">01</span><div><h2>选择消息源</h2><p>只接收所选产品和设备范围内的消息。</p></div><span class="section-chip">输入</span></div><el-form label-position="top"><div class="form-columns"><el-form-item label="产品"><el-select v-model="form.product" aria-label="消息源产品"><el-option v-for="p in products" :key="p.id" :value="p.id" :label="p.name"/></el-select></el-form-item><el-form-item label="设备范围"><el-select v-model="selectedDevice" aria-label="消息源设备"><el-option value="__all__" label="产品下全部设备（含后续新增）"/><el-option v-for="d in selectedDevices" :key="d.id" :value="d.id" :label="d.name"/></el-select></el-form-item></div><el-form-item label="消息类型"><el-radio-group v-model="form.kind" aria-label="消息类型"><el-radio-button v-for="(label, key) in kindNames" :key="key" :value="key">{{ label }}</el-radio-button></el-radio-group></el-form-item></el-form><div class="scope-note"><AppIcon name="shield" :size="15"/>示例租户 · {{ scopeName }} · {{ kindNames[form.kind] }}</div></section>
        <div class="section-connector"><span></span><AppIcon name="arrow" :size="14"/></div>
        <section class="panel config-section"><div class="section-title"><span class="step-number purple">02</span><div><h2>数据处理 <small>可选</small></h2><p>{{ form.processing ? '设置筛选条件和输出字段，整理业务需要的数据。' : '保持关闭，标准设备消息将原样转发。' }}</p></div><el-switch v-model="form.processing" aria-label="启用数据处理"/></div>
          <template v-if="form.processing"><div class="processing-tabs"><button :class="{ selected: !expressionView }" @click="expressionView = false">表单配置</button><button :class="{ selected: expressionView }" @click="expressionView = true">SQL 表达预览</button></div>
            <div v-if="expressionView"><p class="field-help">由表单生成的表达示意，用于检查逻辑；此原型不提供 SQL 编辑或解析。</p><pre class="code-block">{{ flowSql(form) }}</pre></div>
            <div v-else><h3 class="field-heading">筛选条件 <span>满足条件才继续转发</span></h3><div class="condition-row"><el-select v-model="form.field" aria-label="筛选字段"><el-option label="温度 · temperature" value="data.temperature"/><el-option label="湿度 · humidity" value="data.humidity"/></el-select><el-select v-model="form.operator" aria-label="比较方式"><el-option v-for="op in ['>', '>=', '<', '=']" :key="op" :value="op" :label="op"/></el-select><el-input-number v-model="form.threshold" aria-label="筛选阈值" :controls="false"/></div>
              <h3 class="field-heading mapping-heading">输出字段 <span>可改名，对数字进行倍数换算</span></h3><div class="mapping-labels"><span>来源字段</span><span>输出名称</span><span>倍数</span><span></span></div><div v-for="(mapping, index) in form.mappings" :key="index" class="mapping-row"><el-select v-model="mapping.source" :aria-label="`来源字段 ${index + 1}`"><el-option v-for="f in fieldOptions" :key="f" :label="f" :value="f"/></el-select><el-input v-model="mapping.target" :aria-label="`输出名称 ${index + 1}`" maxlength="40"/><el-input-number v-model="mapping.multiplier" :aria-label="`换算倍数 ${index + 1}`" :controls="false"/><el-button text :aria-label="`移除字段 ${index + 1}`" @click="form.mappings.splice(index, 1)"><AppIcon name="close" :size="15"/></el-button></div><el-button link type="primary" :disabled="form.mappings.length >= 10" @click="form.mappings.push({ source: 'meta.occurredAt', target: '', multiplier: 1 })">＋ 添加输出字段</el-button>
            </div>
          </template><div v-else class="passthrough"><AppIcon name="layers" :size="22"/><div><strong>原样转发</strong><span>保留消息元数据和完整设备载荷，无需编写处理逻辑。</span></div><span class="passthrough-label">PASS THROUGH</span></div>
        </section>
        <div class="section-connector"><span></span><AppIcon name="arrow" :size="14"/></div>
        <section class="panel config-section"><div class="section-title"><span class="step-number green">03</span><div><h2>转发目标</h2><p>支持同时发送到多个目标，各自保留投递结果。</p></div><span class="section-chip">输出</span></div><el-checkbox-group v-model="form.connectionIds" class="target-options"><label v-for="c in store.connections" :key="c.id" class="target-option" :class="{ chosen: form.connectionIds.includes(c.id), disabled: !c.enabled }"><el-checkbox :value="c.id" :disabled="!c.enabled" :aria-label="c.name"/><span class="connection-monogram" :class="c.kind.toLowerCase()">{{ c.kind === 'HTTPS' ? 'H' : c.kind === 'Kafka' ? 'K' : 'R' }}</span><span class="target-copy"><strong>{{ c.name }}</strong><small>{{ c.kind }} · {{ c.target || c.address }}</small></span><el-tag v-if="!c.enabled" type="info" size="small">已停用</el-tag></label></el-checkbox-group><p class="field-help">可在「连接管理」维护目标。此处使用示例连接，不产生真实网络投递。</p></section>
      </div>
      <aside class="editor-aside"><section class="panel test-panel"><div class="test-heading"><span class="tiny-dot"></span><h2>消息试运行</h2><span>本地模拟</span></div><p class="field-help">修改样例消息，检查范围、条件与输出内容。</p><div class="sample-label"><label for="flow-sample">输入消息 · JSON</label><el-button link type="primary" @click="resetSample">恢复样例</el-button></div><el-input id="flow-sample" v-model="sample" type="textarea" :rows="13" aria-label="样例消息" spellcheck="false" class="json-input"/><el-button class="test-button" @click="test"><AppIcon name="arrow" :size="16"/>运行样例</el-button><el-alert v-if="testError" :title="testError" type="error" :closable="false"/>
          <div v-if="result" class="test-result" aria-live="polite"><span class="status-pill" :class="result.matched ? 'success' : 'neutral'">{{ result.matched ? '条件命中' : '未命中' }}</span><p>{{ result.reason }}</p><pre v-if="result.matched" class="code-block output-code">{{ JSON.stringify(result.output, null, 2) }}</pre><p v-if="result.matched" class="field-help">模拟目标：{{ targets.map(c => c.name).join('、') }}</p></div><div v-else class="test-placeholder"><AppIcon name="workflow" :size="27"/><p>输出会出现在这里</p><span>先检查，再发布，让每次流转都有把握。</span></div>
        </section><div class="editor-tip"><AppIcon name="layers" :size="17"/><p>数据流转将设备消息交给业务系统。需要控制设备时，使用「场景自动化」。</p></div></aside>
    </div>
  </div>
</template>
