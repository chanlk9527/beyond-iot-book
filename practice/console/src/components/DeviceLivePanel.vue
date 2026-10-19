<script setup lang="ts">
import { onScopeDispose, ref, watch } from 'vue'
import type { Device } from '../api/catalog'
import { liveViewer } from '../media/liveViewer'
const props = defineProps<{ device: Device; enabled: boolean }>()
const video = ref<HTMLVideoElement>(), channel = ref('main'), status = ref('未连接'), error = ref(''), active = ref(false)
let viewer: ReturnType<typeof liveViewer> | undefined
function stop() { viewer?.stop(); viewer = undefined; active.value = false; status.value = '未连接' }
function start() {
  stop(); error.value = ''
  if (!video.value || !props.enabled || !channel.value.trim()) return
  active.value = true
  viewer = liveViewer({ tenantId: props.device.tenantId, deviceId: props.device.id, channelId: channel.value.trim(), video: video.value,
    status: message => { status.value = message },
    failed: message => { error.value = message; active.value = false; status.value = '已结束' },
  })
}
watch(() => [props.device.id, props.device.tenantId, props.enabled], stop)
onScopeDispose(stop)
</script>
<template>
  <div class="live-panel">
    <div class="section-heading"><div><h2>音视频直播</h2><p>{{status}} · 仅观看</p></div>
      <el-button v-if="active" @click="stop">结束观看</el-button><el-button v-else type="primary" :disabled="!enabled||!channel.trim()" @click="start">开始观看</el-button>
    </div>
    <label class="live-channel">直播通道 <el-input v-model="channel" maxlength="64" :disabled="active" placeholder="main"/></label>
    <el-alert v-if="error" :title="error" type="error" :closable="false"/>
    <video ref="video" controls autoplay muted playsinline aria-label="设备直播画面"/>
    <p class="muted">默认静音，可通过播放器开启声音。观看到期后需重新连接。</p>
  </div>
</template>
<style scoped>
.live-panel{max-width:960px}.live-channel{display:flex;align-items:center;gap:14px;max-width:360px;margin:18px 0}.live-channel .el-input{flex:1}.live-panel video{display:block;width:100%;aspect-ratio:16/9;background:#172820;border-radius:10px;margin-top:18px}.muted{color:#789188;font-size:13px}
</style>
