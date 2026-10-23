// @vitest-environment happy-dom
import { afterEach, expect, it } from 'vitest'
import { createApp, nextTick } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import FlowPreviewEditor from './FlowPreviewEditor.vue'
import { newFlow, type Flow } from '../../automation/preview'
let close: (() => void) | undefined
afterEach(() => { close?.(); document.body.innerHTML = '' })
async function settle() { for (let i = 0; i < 15; i++) await nextTick() }
function button(text: string) { const found = [...document.querySelectorAll('button')].find(b => b.textContent?.trim() === text); if (!found) throw new Error(`Missing button ${text}`); return found }
async function mount(flow: Flow, onSave = (_flow: Flow) => {}) {
  const root = document.createElement('div'); document.body.append(root)
  const app = createApp(FlowPreviewEditor, { flow, onSave }); app.use(createPinia()); app.use(ElementPlus); app.mount(root)
  close = () => { app.unmount(); root.remove() }; await settle()
}
it('blocks publishing an unnamed rule without losing the form', async () => {
  let saved = false
  await mount(newFlow(), () => { saved = true })
  button('模拟发布').click(); await settle()
  expect(saved).toBe(false); expect(document.body.textContent).toContain('请填写规则名称')
  expect(document.querySelector('[aria-label="规则名称"]')).toBeTruthy()
})
it('tests edited sample input and clears stale output when the sample changes', async () => {
  await mount({ ...newFlow(), name: '温度转发', processing: true })
  button('运行样例').click(); await settle()
  expect(document.body.textContent).toContain('条件命中')
  const sample = document.querySelector('textarea[aria-label="样例消息"]') as HTMLTextAreaElement
  sample.value = '{invalid'; sample.dispatchEvent(new Event('input', { bubbles: true })); await settle()
  expect(document.body.textContent).not.toContain('条件命中')
  button('运行样例').click(); await settle()
  expect(document.querySelector('.el-alert--error')).toBeTruthy()
})
it('publishes an independent draft copy and preserves the source fixture', async () => {
  const source = { ...newFlow(), name: '我的转发' }
  let result: Flow | undefined
  await mount(source, flow => { result = flow })
  button('模拟发布').click(); await settle()
  expect(result).toMatchObject({ status: 'running', version: 1, name: '我的转发' })
  expect(source.status).toBe('draft'); expect(source.version).toBe(0)
})
