<script setup lang="ts">
import { useData, withBase } from 'vitepress'
const { theme } = useData()
</script>

<template>
  <div class="book-home">
    <section class="book-hero" aria-labelledby="book-title">
      <div class="book-intro">
        <p class="book-eyebrow">IoT PLATFORM <span>在线书稿</span></p>
        <h1 id="book-title">IoT 平台<br><span>设计方法</span></h1>
        <p class="book-subtitle">从设备接入、数据语义到平台运行与演进</p>
        <p class="book-description">从 IoT 世界地图出发，逐步设计面向设备厂商、开发者与用户的平台。</p>
        <div class="book-actions">
          <a class="book-primary" :href="withBase(theme.book.introduction.link)">开始阅读 <span aria-hidden="true">→</span></a>
          <a class="book-secondary" :href="withBase('/contents')">浏览全书目录 <span aria-hidden="true">↗</span></a>
        </div>
        <p class="book-status">持续写作中 · 从第一章开始</p>
      </div>
      <div class="book-cover book-cover-placeholder" aria-label="IoT 平台设计书稿封面">
        <span>IoT<br>平台</span>
      </div>
    </section>

    <section class="book-path" aria-labelledby="reading-path-title">
      <div class="section-heading">
        <div><p class="book-eyebrow">READING PATH</p><h2 id="reading-path-title">从世界地图进入平台设计</h2></div>
        <p>先理清设备、连接、角色和产品形态，<br>再逐步展开平台建设中的设计取舍。</p>
      </div>
      <div class="part-list">
        <a v-for="(part, index) in theme.book.parts" :key="part.link" class="part-row" :href="withBase(part.link)">
          <span class="part-number">{{ String(index + 1).padStart(2, '0') }}</span>
          <div class="part-copy"><h3>{{ part.text.replace(/^第.+?部分：/, '') }}</h3><p>{{ part.items.map((item: { text: string }) => item.text.replace(/^第 \d+ 章：/, '')).join(' / ') }}</p></div>
          <span class="part-count">{{ part.items.filter((item: { text: string }) => /^第 \d+ 章：/.test(item.text)).length }} 章<span v-if="part.items.some((item: { text: string }) => item.text.startsWith('结语：'))"> + 结语</span></span>
          <span class="part-arrow" aria-hidden="true">→</span>
        </a>
      </div>
    </section>
    <footer class="book-home-footer"><span>《IoT 平台设计》</span><span>先把平台中的条件和边界说清楚。</span></footer>
  </div>
</template>
