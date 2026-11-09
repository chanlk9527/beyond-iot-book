<script setup lang="ts">
import { useData } from 'vitepress'
const { frontmatter } = useData()
</script>

<template>
  <aside v-if="frontmatter.writing" class="writing-notice" aria-label="章节写作状态">
    <span class="writing-label">正在写作</span>
    <p>这一章目前是写作提纲，正文完成后会在这里更新。</p>
  </aside>
</template>
