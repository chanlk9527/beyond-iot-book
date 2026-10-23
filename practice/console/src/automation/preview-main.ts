// Isolated Vite development entry. This does not import or change the app's router,
// authenticate against the backend, or grant permissions to any real session.
import { createApp, defineComponent, h } from 'vue'
import { createPinia } from 'pinia'
import { createRouter, createWebHashHistory, RouterView } from 'vue-router'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import '../styles/main.css'
import { useSessionStore } from '../stores/session'
import ConsoleLayout from '../layouts/ConsoleLayout.vue'
import AutomationPreviewView from '../views/AutomationPreviewView.vue'

if (!import.meta.env.DEV) throw new Error('This preview entry is only available in development')
const app = createApp(defineComponent({ render: () => h(RouterView) }))
const pinia = createPinia()
app.use(pinia)
const session = useSessionStore(pinia)
session.account = { id: 'demo-only', username: '演示工作空间', platformAdmin: true, menus: [] }
session.status = 'ready'
session.refreshTenant = async () => {}
session.logout = async () => { location.href = '/login' }
const router = createRouter({ history: createWebHashHistory(), scrollBehavior: () => ({ top: 0 }), routes: [{ path: '/', component: ConsoleLayout, children: [
  ...[['flows', '数据流转'], ['scenes', '场景自动化'], ['connections', '连接管理'], ['notifications', '通知管理'], ['runs', '运行记录']].map(([section, title]) => ({ path: `automation/${section}`, component: AutomationPreviewView, meta: { title, automationSection: section } })),
  { path: ':pathMatch(.*)*', redirect: '/automation/flows' },
] }] })
app.use(router)
app.use(ElementPlus, { locale: zhCn })
app.mount('#app')
