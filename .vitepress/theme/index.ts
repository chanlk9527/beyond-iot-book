import type { Theme } from 'vitepress'
import DefaultTheme from 'vitepress/theme'
import { h } from 'vue'
import BookHome from './BookHome.vue'
import WritingNotice from './WritingNotice.vue'
import ImagePreview from './ImagePreview.vue'
import './style.css'

export default {
  extends: DefaultTheme,
  Layout: () => h(DefaultTheme.Layout, null, {
    'doc-before': () => h(WritingNotice),
    'doc-after': () => h(ImagePreview)
  }),
  enhanceApp({ app }) {
    app.component('BookHome', BookHome)
  }
} satisfies Theme
