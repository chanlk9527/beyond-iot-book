<script setup lang="ts">
import { ElForm, ElFormItem, ElInputNumber, ElOption, ElRadioButton, ElRadioGroup, ElSelect, ElSwitch } from 'element-plus'
import type { DataRule } from './dataPolicy'
import type { HistoryCapabilities } from '../api/history'
const rule = defineModel<DataRule>({ required: true })
defineProps<{ disabled?: boolean; numeric?: boolean; event?: boolean; capabilities?: HistoryCapabilities }>()
</script>
<template>
  <el-form label-position="top" :disabled="disabled" class="data-rule-form">
    <p v-if="event" class="rule-help">每次事件连同全部字段作为一条完整记录保存，不按字段拆分；不同事件消息即使内容相同也分别记录。</p>
    <el-form-item v-if="!event" label="最新状态">
      <el-switch v-model="rule.latest" aria-label="保留最新值" active-text="保留最新值" />
      <p class="rule-help">用于设备状态展示，独立于历史记录保留。</p>
    </el-form-item>
    <el-form-item label="历史保留方式">
      <el-radio-group v-model="rule.history" aria-label="历史保留方式">
        <el-radio-button value="none">不存历史</el-radio-button>
        <el-radio-button value="time" :disabled="capabilities && (!capabilities.enabled || !capabilities.timeRetention)">按时间保留</el-radio-button>
        <el-radio-button value="count" :disabled="capabilities && !capabilities.countRetention">按条数保留</el-radio-button>
      </el-radio-group>
    </el-form-item>
    <div v-if="rule.history !== 'none'" class="rule-fields">
      <el-form-item v-if="rule.history === 'time'" label="原始历史保留天数">
        <div><el-input-number v-model="rule.days" :min="1" :max="capabilities?.maximumRetentionDays || 36500" :precision="0" aria-label="原始历史保留天数" /> <span>天</span></div>
      </el-form-item>
      <el-form-item v-else :label="event ? '每台设备、每种事件保留次数' : '每台设备、每个属性保留条数'">
        <div><el-input-number v-model="rule.count" :min="1" :max="1000000" :precision="0" aria-label="历史保留条数" /> <span>条</span></div>
        <p class="rule-help">{{event ? '按事件发生时间保留最近 N 次完整事件；同一消息的重试只算一次。' : '按观测时间保留最新 N 个去重点；不是列表每页条数。'}}</p>
      </el-form-item>
      <el-form-item v-if="!event" label="历史记录时机">
        <el-select v-model="rule.write" aria-label="历史记录时机">
          <el-option value="every" label="每次上报都记录" />
          <el-option value="change" label="仅值变化时记录" :disabled="capabilities && !capabilities.changeRecording" />
          <el-option value="interval" label="按固定间隔取样" :disabled="capabilities && !capabilities.intervalSampling" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="!event && rule.write === 'interval'" label="取样间隔">
        <div><el-input-number v-model="rule.intervalSeconds" :min="1" :max="86400" :precision="0" aria-label="取样间隔秒数" /> <span>秒</span></div>
        <p class="rule-help">每个窗口保留最后一个实际观测值；没有上报时不补点，不计算平均值。</p>
      </el-form-item>
    </div>
    <el-form-item v-if="!event" label="长期统计（数值属性）">
      <el-switch v-model="rule.aggregate" :disabled="disabled || numeric === false || (capabilities && !capabilities.persistentAggregates)" aria-label="保留每小时统计" active-text="另外保留每小时统计" />
      <p class="rule-help">每小时的最小值、最大值、平均值和样本数独立保留；非数值属性不生成统计。</p>
    </el-form-item>
    <el-form-item v-if="!event && rule.aggregate && numeric !== false" label="小时统计保留天数">
      <div><el-input-number v-model="rule.aggregateDays" :min="1" :max="36500" :precision="0" aria-label="小时统计保留天数" /> <span>天</span></div>
    </el-form-item>
  </el-form>
</template>
<style scoped>
.rule-fields{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 24px}.rule-help{width:100%;color:#7f9290;font-size:12px;line-height:1.7;margin:7px 0 0}.data-rule-form :deep(.el-select){width:100%}.data-rule-form :deep(.el-form-item__label){color:#425f58}.rule-fields span{color:#758c85;margin-left:6px}
@media(max-width:680px){.rule-fields{grid-template-columns:1fr}.data-rule-form :deep(.el-radio-button__inner){padding:9px 11px}}
</style>
