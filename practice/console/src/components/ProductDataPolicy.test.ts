// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import ProductDataPolicy from './ProductDataPolicy.vue'
import { defaultDataPolicy, parsePolicy } from './dataPolicy'
import { historyApi } from '../api/history'
import { ElMessageBox } from 'element-plus'
import { ApiError } from '../api/management'

const model = { properties: { temperature: { schema: { type: 'DECIMAL' } }, power: { schema: { type: 'BOOLEAN' } } }, events: {}, operations: {} }
const capabilities = { provider: 'tdengine', enabled: true, timeRetention: true, countRetention: false, changeRecording: false, intervalSampling: false, persistentAggregates: false, maximumRetentionDays: 3650 }
const initial = () => { const policy = defaultDataPolicy(); policy.eventDefaults.history = 'none'; return policy }
let cleanups: Array<() => void> = []
async function settle() { for (let i = 0; i < 16; i++) await nextTick() }
function mount(productId = 'p', disabled = false) {
  const root = document.createElement('div'); document.body.appendChild(root)
  const onDirty = vi.fn(), warnings: string[] = []
  const app = createApp(ProductDataPolicy, { model, scope: '/catalog/platform', productId, disabled, onDirty })
  app.config.warnHandler = message => warnings.push(message)
  app.mount(root)
  const dispose = () => { app.unmount(); root.remove() }; cleanups.push(dispose)
  return { root, onDirty, dispose, warnings }
}
function button(label: string, root: ParentNode = document.body) { return Array.from(root.querySelectorAll('button')).find(b => b.textContent?.trim() === label)! }
beforeEach(() => {
  localStorage.clear()
  vi.spyOn(historyApi, 'policy').mockResolvedValue({ policy: initial(), revision: 2, capabilities })
  vi.spyOn(historyApi, 'save').mockImplementation(async (_scope, _id, policy, revision) => ({ policy: JSON.parse(JSON.stringify(policy)), revision: revision + 1, capabilities }))
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as any)
})
afterEach(() => { for (const dispose of cleanups) dispose(); cleanups = []; vi.restoreAllMocks(); document.body.innerHTML = '' })

it('loads and saves server policy with independent revision, ignoring old browser previews', async () => {
  localStorage.setItem('old-preview', JSON.stringify({ policy: { defaults: { history: 'count' } } }))
  const page = mount(); await settle()
  expect(historyApi.policy).toHaveBeenCalledWith('/catalog/platform', 'p', expect.any(AbortSignal))
  expect(page.root.textContent).not.toContain('本地预览')
  button('历史 30 天', page.root).click(); await settle()
  button('保存数据策略', page.root).click(); await settle()
  expect(historyApi.save).toHaveBeenCalledWith('/catalog/platform', 'p', expect.objectContaining({ defaults: expect.objectContaining({ history: 'time', days: 30, write: 'every', aggregate: false }) }), 2, expect.any(AbortSignal))
  expect(page.root.textContent).toContain('策略版本 3')
  expect(page.onDirty).toHaveBeenLastCalledWith(false)
  expect(localStorage.length).toBe(1)
})
it('preserves overrides while defaults change and restores the server policy on undo', async () => {
  const policy = initial(); policy.overrides.power = { ...policy.defaults, history: 'time', days: 7 }
  vi.mocked(historyApi.policy).mockResolvedValue({ policy, revision: 2, capabilities })
  const { root } = mount(); await settle()
  button('历史 30 天', root).click(); await settle()
  expect(root.querySelector('.policy-table')?.textContent).toContain('7 天')
  button('恢复继承', root).click(); await settle()
  expect(root.querySelector('.policy-table')?.textContent).not.toContain('7 天')
  button('撤销修改', root).click(); await settle()
  expect(root.querySelector('.policy-table')?.textContent).toContain('7 天')
})
it('opens the actual drawer without missing components and saves a boolean override', async () => {
  const { root, warnings } = mount(); await settle()
  button('历史 30 天', root).click(); await settle()
  Array.from(root.querySelectorAll('button')).filter(b => b.textContent?.trim() === '单独设置')[1].click(); await settle()
  expect(document.body.querySelector('[role="dialog"]')).toBeTruthy()
  expect(warnings.filter(w => w.includes('Failed to resolve component'))).toEqual([])
  button('应用设置').click(); await settle()
  button('保存数据策略', root).click(); await settle()
  expect(vi.mocked(historyApi.save).mock.calls[0][2].overrides.power.aggregate).toBe(false)
})
it('keeps edits after revision conflicts and respects declared storage capabilities', async () => {
  vi.mocked(historyApi.save).mockRejectedValue(new ApiError(409, '策略版本冲突'))
  const { root, onDirty } = mount(); await settle()
  expect(button('最近记录', root).disabled).toBe(true)
  button('历史 30 天', root).click(); await settle()
  button('保存数据策略', root).click(); await settle()
  expect(root.textContent).toContain('策略版本冲突')
  expect(onDirty).toHaveBeenLastCalledWith(true)
  expect(vi.mocked(historyApi.save).mock.calls[0][3]).toBe(2)
})
it('shows load failures without editable placeholder policies and can retry', async () => {
  vi.mocked(historyApi.policy).mockRejectedValueOnce(new Error('offline'))
  const { root } = mount(); await settle()
  expect(root.textContent).toContain('数据策略加载失败')
  expect(root.querySelector('.policy-table')).toBeNull()
  expect(button('保存数据策略', root)).toBeUndefined()
  button('重新加载服务端策略', root).click(); await settle()
  expect(root.querySelector('.policy-table')).toBeTruthy()
  expect(root.textContent).toContain('策略版本 2')
})
it('loads policy for read-only users and shows disabled storage truthfully', async () => {
  vi.mocked(historyApi.policy).mockResolvedValue({ policy: initial(), revision: 2, capabilities: { ...capabilities, provider: 'disabled', enabled: false } })
  const { root } = mount('p', true); await settle()
  expect(root.textContent).toContain('历史存储未启用')
  expect(button('仅最新值', root).disabled).toBe(true)
  expect(button('保存数据策略', root).disabled).toBe(true)
})
it('rejects event rules that request property-only behavior', () => {
  const policy = initial()
  expect(() => parsePolicy(JSON.stringify({ ...policy, eventDefaults: { ...policy.eventDefaults, write: 'change' } }))).toThrow()
  expect(() => parsePolicy(JSON.stringify({ ...policy, eventOverrides: { fault: { ...policy.eventDefaults, latest: true } } }))).toThrow()
})
