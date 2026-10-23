<script setup lang="ts">
import type { FirmwareInventory } from '../api/deviceFirmware'
defineProps<{inventory?:FirmwareInventory;loading?:boolean;failed?:boolean}>()
</script>
<template>
 <span class="firmware-summary">
  <span v-if="loading">读取中…</span><span v-else-if="failed" class="firmware-warning">固件读取失败</span>
  <template v-else-if="inventory">
   <span v-if="!inventory.components.length" class="firmware-muted">尚未上报</span>
   <span v-for="c in inventory.components" :key="c.component" class="firmware-summary-item" :title="`${c.source} · 平台接收 ${new Date(c.receivedAt).toLocaleString('zh-CN')}`">{{c.component}} {{c.version}}<small v-if="c.beforeCurrentBoot" class="firmware-warning">需重新上报</small><small v-else-if="c.stale" class="firmware-warning">已过期</small></span>
   <small v-if="inventory.information" class="firmware-muted">硬件 {{inventory.information.hardwareVersion}}<span v-if="inventory.information.stale"> · 信息已过期</span></small>
  </template>
  <span v-else class="firmware-muted">尚未上报</span>
 </span>
</template>
<style scoped>
.firmware-summary{display:inline-grid;gap:4px;font-size:13px;line-height:1.6;overflow-wrap:anywhere}.firmware-summary-item{display:block}.firmware-summary small{font-size:11px}.firmware-summary-item small{margin-left:8px}.firmware-warning{color:var(--el-color-warning-dark-2)}.firmware-muted{color:var(--text-secondary)}
</style>
