// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import ElementPlus from 'element-plus'
import AccessNodesView from './AccessNodesView.vue'
import { accessNodesApi, type AccessNodesSnapshot } from '../api/accessNodes'

vi.mock('../api/accessNodes', () => ({ accessNodesApi: { snapshot: vi.fn() } }))
const data: AccessNodesSnapshot = {
  scope: 'LOCAL_PROCESS', sampledAt: '2026-10-19T02:00:00Z', nodes: [{
    nodeId: 'instance-1', name: 'local-access', protocol: 'MQTT 5', status: 'RUNNING',
    processStartedAt: '2026-10-19T01:00:00Z', processUptimeMillis: 3_600_000,
    metrics: { running: true, host: '127.0.0.1', port: 1883, connections: 13, onlineDevices: 12,
      maxConnections: 1000, acceptedMessages: 765, rejectedMessages: 2, activeWorkers: 1,
      workerThreads: 4, queuedWork: 3, maxQueuedWork: 256 },
  }],
}
let close: (() => void) | undefined
const settle = async () => { for (let i = 0; i < 12; i++) await nextTick() }
async function mount() {
  const root = document.createElement('div'); document.body.append(root)
  const app = createApp(AccessNodesView); app.use(ElementPlus); app.mount(root)
  close = () => { app.unmount(); root.remove() }
  await settle(); return root
}
beforeEach(() => { vi.clearAllMocks(); vi.mocked(accessNodesApi.snapshot).mockResolvedValue(structuredClone(data)) })
afterEach(() => { close?.(); close = undefined; vi.useRealTimers(); vi.restoreAllMocks(); document.body.innerHTML = '' })
it('displays server metrics and identifies the local observation scope', async () => {
  const root = await mount()
  expect(root.textContent).toContain('当前单节点')
  expect(root.textContent).toContain('尚未汇总集群节点')
  expect(root.textContent).toContain('local-access')
  expect(root.textContent).toContain('12')
  expect(root.textContent).toContain('765 / 2')
  expect(root.textContent).toContain('13 / 1,000')
})
it('shows disabled metrics as unavailable rather than inventing zero values', async () => {
  const disabled = structuredClone(data); disabled.nodes[0]!.status = 'DISABLED'; disabled.nodes[0]!.metrics = null
  vi.mocked(accessNodesApi.snapshot).mockResolvedValue(disabled)
  const root = await mount()
  expect(root.textContent).toContain('未启用')
  expect(root.textContent).toContain('—')
  expect(root.textContent).not.toContain('765')
})
it('retains the last sample and warns when a refresh fails', async () => {
  const root = await mount()
  vi.mocked(accessNodesApi.snapshot).mockRejectedValueOnce(new Error('服务不可用'))
  const refresh = [...root.querySelectorAll('button')].find(button => button.textContent?.trim() === '刷新')!
  refresh.click(); await settle()
  expect(root.textContent).toContain('上次采样数据')
  expect(root.textContent).toContain('服务不可用')
  expect(root.textContent).toContain('765 / 2')
})
it('does not overlap polling requests and aborts the active request on unmount', async () => {
  vi.useFakeTimers()
  let resolve!: (value: AccessNodesSnapshot) => void
  vi.mocked(accessNodesApi.snapshot).mockImplementation(() => new Promise(done => { resolve = done }))
  await mount()
  await vi.advanceTimersByTimeAsync(15_000)
  expect(accessNodesApi.snapshot).toHaveBeenCalledTimes(1)
  const signal = vi.mocked(accessNodesApi.snapshot).mock.calls[0]![0]!
  close?.(); close = undefined
  expect(signal.aborted).toBe(true)
  resolve(data); await settle()
  await vi.advanceTimersByTimeAsync(15_000)
  expect(accessNodesApi.snapshot).toHaveBeenCalledTimes(1)
})
it('refreshes visible pages and stops polling after unmount', async () => {
  vi.useFakeTimers()
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
  await mount()
  await vi.advanceTimersByTimeAsync(5000)
  expect(accessNodesApi.snapshot).toHaveBeenCalledTimes(2)
  close?.(); close = undefined
  await vi.advanceTimersByTimeAsync(10_000)
  expect(accessNodesApi.snapshot).toHaveBeenCalledTimes(2)
})
it('pauses refresh while the page is hidden and resumes when visible', async () => {
  vi.useFakeTimers()
  const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(true)
  await mount()
  await vi.advanceTimersByTimeAsync(10_000)
  expect(accessNodesApi.snapshot).toHaveBeenCalledTimes(1)
  hidden.mockReturnValue(false)
  await vi.advanceTimersByTimeAsync(5000)
  expect(accessNodesApi.snapshot).toHaveBeenCalledTimes(2)
})
it('uses the authoritative device total rather than adding per-node session snapshots', async () => {
  const clustered = structuredClone(data)
  clustered.scope = 'CLUSTER_MEMBERSHIP'; clustered.onlineDevices = 7; clustered.complete = true
  clustered.totalNodes = 1; clustered.nodes[0]!.lifecycle = 'DRAINING'; clustered.nodes[0]!.fresh = true
  vi.mocked(accessNodesApi.snapshot).mockResolvedValue(clustered)
  const root = await mount()
  expect(root.textContent).toContain('节点成员目录')
  expect(root.textContent).toContain('排空中')
  const card = [...root.querySelectorAll('.summary-card')].find(item => item.textContent?.includes('在线设备'))!
  expect(card.querySelector('strong')?.textContent).toBe('7')
})
it('loads historical instances only when requested and keeps current aggregates available', async () => {
  const current = structuredClone(data)
  current.scope = 'CLUSTER_MEMBERSHIP'; current.complete = true; current.totalNodes = 1
  current.nodes[0]!.lifecycle = 'ACTIVE'; current.nodes[0]!.fresh = true
  const history = structuredClone(current)
  history.totalNodes = 2
  history.nodes.push({ ...history.nodes[0]!, nodeId: 'old-instance', status: 'STOPPED', lifecycle: 'UNREACHABLE', fresh: false, metrics: null })
  vi.mocked(accessNodesApi.snapshot).mockResolvedValueOnce(current).mockResolvedValueOnce(history)
  const root = await mount()
  expect(vi.mocked(accessNodesApi.snapshot).mock.calls[0]![1]).toBe(false)
  expect(root.textContent).not.toContain('old-instance')
  root.querySelector<HTMLElement>('[aria-label="查看历史实例"]')!.click()
  await settle()
  expect(vi.mocked(accessNodesApi.snapshot).mock.calls[1]![1]).toBe(true)
  expect(root.textContent).toContain('old-instance')
  expect(root.textContent).toContain('765 / 2')
  expect(root.textContent).not.toContain('连接与消息总量暂不可用')
})
it('does not show partial connection aggregates as a complete cluster total', async () => {
  const clustered = structuredClone(data)
  clustered.scope = 'CLUSTER_MEMBERSHIP'; clustered.onlineDevices = 0; clustered.complete = false
  clustered.nodes[0]!.lifecycle = 'UNREACHABLE'; clustered.nodes[0]!.fresh = false; clustered.nodes[0]!.metrics = null
  vi.mocked(accessNodesApi.snapshot).mockResolvedValue(clustered)
  const root = await mount()
  expect(root.textContent).toContain('已失联')
  expect(root.textContent).toContain('连接与消息总量暂不可用')
  expect(root.textContent).not.toContain('765')
})
