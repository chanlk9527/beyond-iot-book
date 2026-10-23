<script setup lang="ts">
defineProps<{ night?: boolean; moving?: boolean }>()
</script>
<template>
  <svg class="nursery-scene" :class="{ night, moving }" viewBox="0 0 640 420" role="img" aria-label="婴儿房插画演示画面，并非真实摄像头影像" xmlns="http://www.w3.org/2000/svg">
    <defs>
      <linearGradient id="nursery-wall" x2="0" y2="1"><stop stop-color="#c8c5b4"/><stop offset="1" stop-color="#e4dfcb"/></linearGradient>
      <linearGradient id="nursery-blanket" x2="1" y2="1"><stop stop-color="#d3c1a3"/><stop offset="1" stop-color="#aa9275"/></linearGradient>
      <filter id="nursery-soft"><feGaussianBlur stdDeviation="12"/></filter>
    </defs>
    <rect width="640" height="420" fill="url(#nursery-wall)"/>
    <path d="M0 295H640V420H0" fill="#ad9c82"/>
    <path d="M0 308H640M120 308 70 420M290 308 280 420M470 308 510 420" stroke="#b9a98f" stroke-width="2"/>
    <rect x="30" width="145" height="259" fill="#e9e4d4"/><path d="M54 0v251M85 0v252M119 0v253M152 0v254" stroke="#d4cfbf" stroke-width="13" opacity=".65"/>
    <ellipse cx="325" cy="354" rx="225" ry="43" fill="#6e6655" opacity=".18" filter="url(#nursery-soft)"/>
    <path d="M451 80a35 35 0 1 0 38 50 30 30 0 0 1-38-50" fill="#f1e7bc"/>
    <path d="m410 71 2 6 6 2-6 2-2 6-2-6-6-2 6-2zM512 59l2 5 5 2-5 2-2 5-2-5-5-2 5-2z" fill="#eee8cf"/>
    <path d="M570 142v159M533 147h74l-18-63h-38z" stroke="#8e8067" stroke-width="5" fill="#e8dfc5"/>
    <ellipse cx="570" cy="304" rx="24" ry="5" fill="#897c67"/>
    <rect x="123" y="231" width="364" height="92" rx="35" fill="#f1e9d8" stroke="#c1ae8d" stroke-width="9"/>
    <ellipse cx="310" cy="257" rx="167" ry="52" fill="#ece4d2"/>
    <ellipse cx="260" cy="244" rx="62" ry="33" fill="#faf3e3" transform="rotate(-10 260 244)"/>
    <g class="sleeping-baby">
      <ellipse cx="271" cy="241" rx="28" ry="26" fill="#e6b79a" transform="rotate(-10 271 241)"/>
      <path d="M246 234q-3-34 26-26 18 1 24 22-12-14-24-8-15 7-26 12" fill="#80684f"/>
      <path d="m257 241 7 2m12-2 7-3" stroke="#97735f" stroke-width="2" stroke-linecap="round"/>
      <path d="M292 234q63-16 100 26l-11 28q-65 9-101-13z" fill="url(#nursery-blanket)"/>
      <path d="M310 243q19 22 18 40M352 247l-5 39" stroke="#e3d3b6" stroke-width="3" opacity=".5"/>
    </g>
    <path d="M127 249v116M487 249v116M130 315h355" stroke="#b39a73" stroke-width="10" stroke-linecap="round"/>
    <path d="M158 284v51m35-43v43m36-39v39m36-38v38m36-37v37m36-38v38m36-41v41m36-47v47m36-55v55" stroke="#c3ac88" stroke-width="6" stroke-linecap="round"/>
    <path d="M127 278q180 50 360 0" fill="none" stroke="#c4ae8b" stroke-width="9"/>
    <path d="M204 202V89q0-15 15-15h89" fill="none" stroke="#a39276" stroke-width="4"/>
    <path d="m250 76-27 28h89l-30-28m-58 28v33m44-33v46m43-46v31" fill="none" stroke="#a39276" stroke-width="2"/>
    <path d="m221 132-9 14h19zM266 144a9 9 0 1 0 0 17 8 8 0 0 1 0-17" fill="#d3b56e"/>
    <circle cx="311" cy="142" r="7" fill="#a4b4a0"/>
    <rect width="640" height="420" fill="#202820" opacity=".07"/>
  </svg>
</template>
<style scoped>
.nursery-scene{width:100%;height:100%;display:block;object-fit:cover}.night{filter:grayscale(1) brightness(.65) contrast(1.2)}.moving .sleeping-baby{animation:demo-breath 4s ease-in-out infinite;transform-origin:320px 264px}@keyframes demo-breath{50%{transform:scale(1.006,1.025)}}@media(prefers-reduced-motion:reduce){.moving .sleeping-baby{animation:none}}
</style>
