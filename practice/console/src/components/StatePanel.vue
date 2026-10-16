<script setup lang="ts">
import AppIcon from './AppIcon.vue'
withDefaults(defineProps<{ title: string; description?: string; icon?: string }>(), { icon: 'layers' })
</script>
<template>
  <section class="state-panel" role="status">
    <div class="state-icon"><AppIcon :name="icon" :size="30" /></div>
    <h2>{{ title }}</h2><p v-if="description">{{ description }}</p>
    <div v-if="$slots.default" class="state-actions"><slot /></div>
  </section>
</template>
