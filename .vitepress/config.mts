import { defineConfig } from 'vitepress'
import { dirname, relative, resolve } from 'node:path'
import { readBook, readText, projectRoot, toPageLink, flattenItems, pageAliases } from './book.mts'

const book = readBook()
const readingOrder = [...book.parts.flatMap(part => [
  { text: part.text, link: part.link }, ...flattenItems(part.items)
])]

export default defineConfig({
  lang: 'zh-CN',
  title: 'IoT 平台设计',
  description: '从设备接入、数据语义到平台运行与演进的在线书稿。',
  cleanUrls: true,
  srcExclude: ['README.md', 'BOOK-DESIGN.md', 'WRITING.md', 'VOICE.md', 'ILLUSTRATIONS.md', 'DEPLOYMENT.md'],
  rewrites: {
    ...pageAliases,
    'manuscript/:part/_index.md': ':part/index.md',
    'manuscript/:rest*': ':rest*',
    'SUMMARY.md': 'contents.md',
  },
  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: '/favicon.svg' }],
    ['meta', { name: 'theme-color', content: '#faf9f6' }]
  ],
  markdown: {
    theme: { light: 'github-light', dark: 'github-dark' },
    image: { lazyLoading: true },
    config(md) {
      const renderImage = md.renderer.rules.image!
      md.renderer.rules.image = (tokens, index, options, env, renderer) => {
        const token = tokens[index]
        token.attrSet('tabindex', '0')
        token.attrSet('role', 'button')
        token.attrSet('aria-label', `放大图示：${token.content}`)
        return renderImage(tokens, index, options, env, renderer)
      }
      // Resolve links against the manuscript source, before URL rewrites move pages.
      md.core.ruler.after('inline', 'book-source-links', state => {
        for (const block of state.tokens) {
          for (const token of block.children || []) {
            if (token.type !== 'link_open') continue
            const href = token.attrGet('href') || ''
            if (/^(?:[a-z]+:|\/\/|#)/i.test(href)) continue
            const [, pathname, suffix] = href.match(/^([^?#]*)(.*)$/)!
            const originalPath = pathname.startsWith('/')
              ? resolve(projectRoot, pathname.slice(1))
              : resolve(dirname(state.env.realPath || state.env.path), pathname)
            const source = relative(projectRoot, originalPath).replace(/\\/g, '/')
            if (source.startsWith('manuscript/') && pathname.endsWith('.md')) token.attrSet('href', toPageLink(source) + suffix)
            else if (source === 'SUMMARY.md') token.attrSet('href', '/contents' + suffix)
          }
        }
      })
    }
  },
  transformPageData(pageData) {
    if (pageData.filePath.startsWith('manuscript/')) {
      const content = readText(resolve(projectRoot, pageData.filePath))
      pageData.frontmatter.writing = /^## 本章任务\s*$/m.test(content)
      const position = readingOrder.findIndex(item => item.link === toPageLink(pageData.filePath))
      if (position >= 0) {
        pageData.frontmatter.prev = readingOrder[position - 1] || false
        pageData.frontmatter.next = readingOrder[position + 1] || false
      }
    }
    if (pageData.filePath === 'SUMMARY.md') {
      Object.assign(pageData.frontmatter, { title: '全书目录', outline: false, prev: false, next: false })
    }
  },
  themeConfig: {
    siteTitle: '隐藏复杂性',
    nav: [
      { text: '开始阅读', link: book.introduction.link },
      { text: '全书目录', link: '/contents' },
    ],
    sidebar: [
      { text: '阅读入口', items: [{ text: '全书目录', link: '/contents' }] },
      ...book.parts.map(part => ({
        text: part.text,
        collapsed: true,
        items: [{ text: '分部导读', link: part.link }, ...part.items]
      }))
    ],
    book,
    outline: { level: [2, 3], label: '本页目录' },
    docFooter: { prev: '上一节', next: '下一节' },
    sidebarMenuLabel: '全书目录',
    outlineTitle: '本页目录',
    returnToTopLabel: '回到顶部',
    darkModeSwitchLabel: '阅读主题',
    lightModeSwitchTitle: '切换为浅色主题',
    darkModeSwitchTitle: '切换为深色主题',
    externalLinkIcon: true,
    search: {
      provider: 'local',
      options: {
        miniSearch: {
          options: {
            tokenize(text) {
              const tokens: string[] = []
              for (const { segment, isWordLike } of new Intl.Segmenter('zh-CN', { granularity: 'word' }).segment(text)) {
                if (!isWordLike) continue
                tokens.push(segment)
                if (/^[\p{Script=Han}]+$/u.test(segment) && segment.length > 2) {
                  for (let i = 0; i < segment.length - 1; i++) tokens.push(segment.slice(i, i + 2))
                }
              }
              return tokens
            }
          },
          searchOptions: { prefix: true, fuzzy: false }
        },
        translations: {
          button: { buttonText: '搜索', buttonAriaLabel: '搜索全书' },
          modal: {
            displayDetails: '显示详细结果',
            resetButtonTitle: '清空搜索',
            backButtonTitle: '关闭搜索',
            noResultsText: '没有找到相关内容',
            footer: { selectText: '选择', navigateText: '切换', closeText: '关闭' }
          }
        }
      }
    }
  }
})
