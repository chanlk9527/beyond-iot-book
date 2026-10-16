<script setup lang="ts">
withDefaults(defineProps<{ name?: string; size?: number }>(), { name: 'grid', size: 20 })
const paths: Record<string, string> = {
  grid: 'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
  layers: 'm12 3 10 5-10 5L2 8z M2 12l10 5 10-5 M2 16l10 5 10-5',
  device: 'M5 3h14v18H5z M9 17h6 M9 7h6',
  connection: 'M8 8a6 6 0 0 1 10 0 M5 5a10 10 0 0 1 16 0 M10 12a2 2 0 0 1 4 0 M12 16v5 M3 3l18 18',
  arrow: 'M5 12h14 M13 6l6 6-6 6',
  refresh: 'M20 7v5h-5 M4 17v-5h5 M6 7a7 7 0 0 1 12-2l2 2 M4 17l2 2a7 7 0 0 0 12-2',
  user: 'M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0 M4 21v-2a8 8 0 0 1 16 0v2',
  shield: 'm12 2 8 4v6c0 5-8 10-8 10S4 17 4 12V6z m-4 10 3 3 5-6',
  logout: 'M9 3H4v18h5 M10 12h11 M16 7l5 5-5 5',
  bell: 'M5 17h14l-2-3V9A5 5 0 0 0 7 9v5z M10 21h4',
  workflow: 'M3 3h6v6H3z M15 15h6v6h-6z M9 6h9v9 M3 15h6v6H3z M6 9v6',
  menu: 'M3 6h18 M3 12h18 M3 18h18',
  search: 'M17 10a7 7 0 1 1-14 0 7 7 0 0 1 14 0 m-2 5 6 6',
  clock: 'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0 M12 7v5l3 2',
  close: 'm6 6 12 12 M18 6 6 18',
}
</script>
<template><svg :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[name] || paths.grid" /></svg></template>
