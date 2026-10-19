// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, defineComponent, nextTick, reactive } from 'vue'
import ElementPlus from 'element-plus'
import { ElMessageBox } from 'element-plus'
import { historyApi } from '../api/history'
import { defaultDataPolicy } from '../components/dataPolicy'
import ProductDetailView from './ProductDetailView.vue'
import { catalogApi, defaultProductConfiguration, type Product } from '../api/catalog'

const context = vi.hoisted(() => ({ route: null as any, can: vi.fn((_permission: string) => true) }))
vi.mock('vue-router', () => ({ useRoute: () => context.route, useRouter: () => ({ replace: ({ query }: any) => { context.route.query = query }, push: vi.fn() }), onBeforeRouteLeave: vi.fn(), onBeforeRouteUpdate: vi.fn() }))
vi.mock('../stores/session', () => ({ useSessionStore: () => ({ account: { id: 'admin', platformAdmin: true }, can: context.can }) }))
vi.mock('../api/catalog', async original => ({ ...await original<typeof import('../api/catalog')>(), catalogApi: { product: vi.fn(), models: vi.fn(), save: vi.fn(), publish: vi.fn() } }))
vi.mock('../api/history', () => ({ historyApi: { policy: vi.fn(), save: vi.fn() } }))
vi.mock('../components/CatalogNavigation.vue', () => ({ default: defineComponent(() => () => null) }))
vi.mock('../components/ProductConfigurationForm.vue', () => ({ default: defineComponent(() => () => null) }))
vi.mock('./DevicesView.vue', () => ({ default: defineComponent(() => () => null) }))

const product = (): Product => ({ id: 'p', tenantId: null, code: 'warmer', name: '暖奶器', description: '', enabled: true, latestVersion: null, revision: 1, configuration: defaultProductConfiguration(), draft: { properties: { temperature: { id: 'temperature', schema: { type: 'DECIMAL' }, access: 'READ_ONLY' }, power: { id: 'power', schema: { type: 'BOOLEAN' }, access: 'READ_WRITE' } }, events: {}, operations: {} } })
let dispose: () => void
async function settle() { for (let i = 0; i < 15; i++) await nextTick() }
function mount() {
  const root = document.createElement('div'); document.body.appendChild(root)
  const app = createApp(ProductDetailView); app.use(ElementPlus); app.mount(root)
  dispose = () => { app.unmount(); root.remove() }; return root
}
function button(label: string, root: ParentNode = document.body) { return Array.from(root.querySelectorAll('button')).find(b => b.textContent?.trim() === label)! }
beforeEach(() => {
  localStorage.clear(); vi.clearAllMocks(); context.can.mockReturnValue(true)
  context.route = reactive({ meta: { catalogPlatform: true }, params: { id: 'p' }, query: { tab: 'model' }, path: '/catalog/platform/products/p' })
  vi.mocked(catalogApi.product).mockResolvedValue(product()); vi.mocked(catalogApi.models).mockResolvedValue([])
  const capabilities = { provider: 'test', enabled: true, timeRetention: true, countRetention: true, changeRecording: true, intervalSampling: true, persistentAggregates: true, maximumRetentionDays: 3650 }
  vi.mocked(historyApi.policy).mockResolvedValue({ policy: defaultDataPolicy(), revision: 0, capabilities })
  vi.mocked(historyApi.save).mockImplementation(async (_scope, _id, policy, revision) => ({ policy: JSON.parse(JSON.stringify(policy)), revision: revision + 1, capabilities }))
  vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as any)
})
afterEach(() => { dispose?.(); document.body.innerHTML = ''; vi.restoreAllMocks() })

it('edits a property from the model and shares the preview with the data tab without saving the model', async () => {
  const root = mount(); await settle()
  const configure = root.querySelector<HTMLButtonElement>('[aria-label="配置 temperature 数据策略"]')!
  expect(configure).toBeTruthy()
  configure.click(); await settle()
  expect(document.body.querySelector('.el-drawer')?.textContent).toContain('属性策略 · temperature')
  button('应用设置').click(); await settle()
  expect(root.querySelector('.thing-model')?.textContent).toContain('单独设置')
  expect(button('保存数据策略', root).disabled).toBe(false)
  context.route.query.tab = 'basic'; await settle()
  context.route.query.tab = 'data'; await settle()
  expect(root.querySelector('.policy-table')?.textContent).toContain('单独设置')
  button('历史 30 天', root).click(); await settle()
  context.route.query.tab = 'model'; await settle()
  const modelTable = root.querySelector('.thing-model .el-table')!
  expect(modelTable.textContent).toContain('历史 30 天')
  expect(modelTable.textContent).toContain('不存历史')
  button('保存数据策略', root).click(); await settle()
  const stored = vi.mocked(historyApi.save).mock.calls[0][2]
  expect(stored.overrides.temperature.history).toBe('none')
  expect(stored.defaults.history).toBe('time')
  expect(localStorage.length).toBe(0)
  expect(catalogApi.save).not.toHaveBeenCalled(); expect(catalogApi.publish).not.toHaveBeenCalled()
})

it('requires saving changed attribute definitions before configuring their policy', async () => {
  const root = mount(); await settle()
  button('填入暖奶器示例', root).click(); await settle()
  expect(root.querySelector('.thing-model')?.textContent).toContain('请先保存此属性定义，再配置策略')
  expect(root.querySelector('[aria-label="配置 currentTemperature 数据策略"]')).toBeNull()
  expect(root.querySelector('[aria-label="配置 power 数据策略"]')).toBeNull()
})

it('configures complete events independently of same-named attributes and event defaults', async () => {
  const p = product()
  p.draft.events = { power: { id: 'power', fields: { code: { id: 'code', schema: { type: 'TEXT' } } } }, fault: { id: 'fault', fields: {} } }
  vi.mocked(catalogApi.product).mockResolvedValue(p)
  const root = mount(); await settle()
  root.querySelector<HTMLElement>('#tab-events')!.click(); await settle()
  expect(root.querySelector('.thing-model')?.textContent).toContain('完整事件保留 90 天')
  root.querySelector<HTMLButtonElement>('[aria-label="配置 power 事件策略"]')!.click(); await settle()
  const drawer = document.body.querySelector('.el-drawer')!
  expect(drawer.textContent).toContain('事件策略 · power')
  expect(drawer.textContent).toContain('全部字段作为一条完整记录')
  expect(drawer.querySelector('[role="switch"]')).toBeNull()
  expect(drawer.querySelector('[aria-label="历史记录时机"]')).toBeNull()
  drawer.querySelector<HTMLInputElement>('input[type="radio"][value="count"]')!.click(); await settle()
  expect(drawer.textContent).toContain('每台设备、每种事件保留次数')
  button('应用设置').click(); await settle()
  context.route.query.tab = 'data'; await settle()
  expect(root.querySelector('.event-policy-table')?.textContent).toContain('最近 100 次')
  root.querySelector<HTMLInputElement>('.policy-events input[type="radio"][value="none"]')!.click(); await settle()
  expect(root.querySelector('.event-policy-table')?.textContent).toContain('不保存事件历史')
  root.querySelector<HTMLButtonElement>('.event-policy-table button')!.click(); await settle()
  button('恢复事件默认').click(); await settle()
  expect(root.querySelector('.event-policy-table')?.textContent).not.toContain('最近 100 次')
  context.route.query.tab = 'model'; await settle()
  root.querySelector<HTMLElement>('#tab-properties')!.click(); await settle()
  expect(root.querySelector('.thing-model')?.textContent).toContain('保留最新值 · 不存历史')
  expect(root.querySelector('.thing-model')?.textContent).not.toContain('单独设置')
  button('保存数据策略', root).click(); await settle()
  const stored = vi.mocked(historyApi.save).mock.calls[0][2]
  expect(stored.eventDefaults.history).toBe('none')
  expect(stored.overrides).toEqual({})
  expect(stored.eventOverrides).toEqual({})
  expect(catalogApi.save).not.toHaveBeenCalled()
})

it('restores inheritance from the same drawer and keeps read-only policy actions disabled', async () => {
  const root = mount(); await settle()
  root.querySelector<HTMLButtonElement>('[aria-label="配置 power 数据策略"]')!.click(); await settle()
  button('应用设置').click(); await settle()
  root.querySelector<HTMLButtonElement>('[aria-label="配置 power 数据策略"]')!.click(); await settle()
  button('恢复产品默认').click(); await settle()
  expect(root.querySelector('.thing-model')?.textContent).not.toContain('单独设置')
  context.can.mockImplementation(p => p !== 'product:write')
  // A fresh mount reflects a newly revalidated permission set.
  dispose(); const readonlyRoot = mount(); await settle()
  expect(readonlyRoot.querySelector<HTMLButtonElement>('[aria-label="配置 power 数据策略"]')!.disabled).toBe(true)
})
