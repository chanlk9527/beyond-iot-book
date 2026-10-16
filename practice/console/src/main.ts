import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { ElCascader, ElDivider, ElTree, ElTabs, ElTabPane, ElInputNumber, ElCheckbox, ElRadioGroup, ElRadioButton, ElDialog, ElSwitch, ElTable, ElTableColumn, ElPagination, ElAlert, ElButton, ElConfigProvider, ElInput, ElOption, ElSelect, ElSkeleton, ElTag } from 'element-plus'
import 'element-plus/dist/index.css'
import './styles/main.css'
import App from './App.vue'
import router from './router'
import { useSessionStore } from './stores/session'
import { setUnauthorizedHandler } from './api/management'

const app = createApp(App)
app.use(createPinia())
setUnauthorizedHandler(() => {
  useSessionStore().clear()
  if (router.currentRoute.value.name !== 'login') void router.replace({ name: 'login' })
})
app.use(router)
for (const component of [ElCascader, ElDivider, ElTree, ElTabs, ElTabPane, ElInputNumber, ElCheckbox, ElRadioGroup, ElRadioButton, ElDialog, ElSwitch, ElTable, ElTableColumn, ElPagination, ElAlert, ElButton, ElConfigProvider, ElInput, ElOption, ElSelect, ElSkeleton, ElTag]) app.use(component)
app.mount('#app')
