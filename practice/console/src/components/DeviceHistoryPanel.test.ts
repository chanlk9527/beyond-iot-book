// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import DeviceHistoryPanel from './DeviceHistoryPanel.vue'
import { historyApi } from '../api/history'
vi.mock('../api/history', () => ({ historyApi: { query: vi.fn() } }))
let dispose: () => void
async function settle() { for (let i = 0; i < 20; i++) await nextTick() }
function mount(options: Record<string, unknown> = {}) {
  const root = document.createElement('div'); document.body.append(root)
  const app = createApp(DeviceHistoryPanel, {
    scope: '/catalog/platform', device: { id: 'd', tenantId: 'tenant' },
    model: { properties: { temperature: { id: 'temperature' } }, events: { fault: { id: 'fault' } }, operations: {} },
    ...options,
  } as any)
  app.mount(root); dispose = () => { app.unmount(); root.remove() }; return root
}
function button(root: ParentNode, label: string) { return Array.from(root.querySelectorAll('button')).find(b => b.textContent?.trim() === label)! }
async function select(root: HTMLElement, index: number, text: string) {
  root.querySelectorAll<HTMLElement>('.el-select__wrapper')[index].click(); await settle()
  const option = Array.from(document.body.querySelectorAll<HTMLElement>('.el-select-dropdown__item')).find(el => el.textContent?.trim() === text)!
  option.click(); await settle()
}
beforeEach(() => { vi.resetAllMocks() })
afterEach(() => { dispose?.(); document.body.innerHTML = '' })
it('queries the actual resource scope, preserves JSON precision and follows the returned cursor', async () => {
  const item = { occurredAt: '2026-10-19T00:00:00Z', receivedAt: '2026-10-19T00:00:01Z', messageId: 'm', modelVersion: 'v1', valueType: 'DECIMAL', payload: '25.1234567890123456789' }
  vi.mocked(historyApi.query).mockResolvedValueOnce({ items: [item], nextCursor: 'opaque' }).mockResolvedValueOnce({ items: [], nextCursor: null })
  const root = mount(); await select(root, 1, 'temperature')
  button(root, '查询历史').click(); await settle()
  expect(historyApi.query).toHaveBeenCalledWith('/catalog/tenants/tenant', 'd', 'PROPERTY', 'temperature', expect.any(String), expect.any(String), null, expect.any(AbortSignal))
  expect(root.querySelector('pre')?.textContent).toBe(item.payload)
  button(root, '更早记录').click(); await settle()
  expect(vi.mocked(historyApi.query).mock.calls[1][6]).toBe('opaque')
  expect(button(root, '更早记录').disabled).toBe(true)
  expect(root.textContent).toContain('该时间范围内没有保留的记录')
})
it('discards a previous query when its criteria change and shows current failures', async () => {
  let finish!: (result: any) => void
  vi.mocked(historyApi.query).mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
  const root = mount(); await select(root, 1, 'temperature')
  button(root, '查询历史').click(); await settle()
  const signal = vi.mocked(historyApi.query).mock.calls[0][7]
  const start = root.querySelector<HTMLInputElement>('[aria-label="历史开始时间"]')!
  start.value = '2026-10-18T00:00'; start.dispatchEvent(new Event('input', { bubbles: true })); await settle()
  expect(signal.aborted).toBe(true)
  finish({ items: [{ payload: 'stale' }], nextCursor: 'stale' }); await settle()
  expect(root.textContent).not.toContain('stale')
  vi.mocked(historyApi.query).mockRejectedValueOnce(new Error('connection unavailable'))
  button(root, '查询历史').click(); await settle()
  expect(root.querySelector('.el-alert')).toBeTruthy()
  expect(root.querySelector('pre')).toBeNull()
})
it('opens the event-only entry on actual event history and refreshes its time range', async () => {
  vi.mocked(historyApi.query).mockResolvedValue({ items: [], nextCursor: null })
  const root = mount({ eventOnly: true }); await settle()
  expect(root.textContent).toContain('事件记录')
  expect(root.querySelectorAll('.el-select')).toHaveLength(1)
  button(root, '最近 24 小时').click(); await settle()
  const call = vi.mocked(historyApi.query).mock.calls[0]
  expect(call.slice(0, 4)).toEqual(['/catalog/tenants/tenant', 'd', 'EVENT', 'fault'])
  expect(Date.parse(call[5])).toBeGreaterThanOrEqual(Date.now() - 1000)
})
