<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vitepress'

const route = useRoute()
const dialog = ref<HTMLDialogElement>()
const source = ref('')
const description = ref('')

async function openImage(target: EventTarget | null) {
  if (!(target instanceof HTMLImageElement) || !target.closest('.vp-doc')) return
  source.value = target.currentSrc || target.src
  description.value = target.alt
  target.focus()
  await nextTick()
  dialog.value?.showModal()
}

function clickImage(event: MouseEvent) { openImage(event.target) }
function keyImage(event: KeyboardEvent) {
  if ((event.key === 'Enter' || event.key === ' ') && event.target instanceof HTMLImageElement && event.target.closest('.vp-doc')) {
    event.preventDefault()
    openImage(event.target)
  }
}
watch(() => route.path, () => { dialog.value?.close() })
onMounted(() => {
  document.addEventListener('click', clickImage)
  document.addEventListener('keydown', keyImage)
})
onUnmounted(() => {
  document.removeEventListener('click', clickImage)
  document.removeEventListener('keydown', keyImage)
})
</script>

<template>
  <dialog ref="dialog" class="image-preview" aria-label="图示预览" @click="event => { if (event.target === dialog) dialog?.close() }">
    <button class="image-preview-close" type="button" autofocus @click="dialog?.close()" aria-label="关闭图示预览">关闭 <span aria-hidden="true">×</span></button>
    <figure><img :src="source || undefined" :alt="description"><figcaption>{{ description }}</figcaption></figure>
  </dialog>
</template>
