// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import { ElAlert, ElButton, ElDescriptions, ElDescriptionsItem, ElPagination, ElSwitch, ElTable, ElTableColumn, ElTag } from 'element-plus'
import EventBusView from './EventBusView.vue'
import { eventbusApi, type EventSnapshot } from '../api/eventbus'
vi.mock('../api/eventbus',()=>({eventbusApi:{snapshot:vi.fn(),subscriptions:vi.fn(),backlog:vi.fn()}}))
const data:EventSnapshot={
  mode:'dubbo',sampledAt:'2026-10-20T08:00:00Z',collected:0,complete:false,
  directory:{namespace:'test',revision:5,totalNodes:1,activeNodes:1,subscriptions:0,sampledAt:'2026-10-20T08:00:00Z',backlog:{pending:12,received:3,retrying:2,oldest:null}},
  nodes:[{directory:{id:'worker-a',endpoint:'127.0.0.1:20880',state:'READY',leaseUntil:'2026-10-20T08:00:10Z',revision:4,alive:true},observation:'UNREACHABLE',metrics:null,lastSuccessAt:null}],
}
let close:(()=>void)|undefined
async function settle(){for(let i=0;i<15;i++)await nextTick()}
async function mount(){
  const root=document.createElement('div');document.body.append(root)
  const app=createApp(EventBusView)
  // Match the console's selective registration; drawers must be imported by the view.
  for(const component of [ElAlert,ElButton,ElDescriptions,ElDescriptionsItem,ElPagination,ElSwitch,ElTable,ElTableColumn,ElTag])app.use(component)
  app.mount(root)
  close=()=>{app.unmount();root.remove()};await settle();return root
}
beforeEach(()=>{
  vi.clearAllMocks()
  vi.mocked(eventbusApi.snapshot).mockResolvedValue(structuredClone(data))
  vi.mocked(eventbusApi.subscriptions).mockResolvedValue({total:0,page:0,items:[]})
})
afterEach(()=>{close?.();close=undefined;vi.useRealTimers();vi.restoreAllMocks();document.body.innerHTML=''})
it('shows partial collection and unknown metrics without inventing zero traffic',async()=>{
  const root=await mount()
  expect(root.textContent).toContain('采集不完整');expect(root.textContent).toContain('不可达')
  expect(root.textContent).toContain('worker-a');expect(root.textContent).toContain('12 / 3')
  expect(root.querySelector('.flow')?.textContent).toContain('—')
})
it('explains modes that do not support distributed observation',async()=>{
  vi.mocked(eventbusApi.snapshot).mockResolvedValue({...data,mode:'local',directory:null,nodes:[]})
  const root=await mount()
  expect(root.textContent).toContain('当前模式未提供集群观测')
  expect(root.textContent).not.toContain('待发送 / 待处理')
})
it('opens node details in a right-hand overlay outside the page and closes it',async()=>{
  const root=await mount()
  expect(document.querySelector('.el-drawer')?.closest('.el-overlay')?.getAttribute('style')).toContain('display: none')
  ;[...root.querySelectorAll('button')].find(b=>b.textContent?.trim()==='详情')!.click()
  await settle()
  const drawer=document.body.querySelector<HTMLElement>('.el-drawer')!
  expect(drawer).not.toBeNull()
  expect(root.contains(drawer)).toBe(false)
  expect(drawer.classList.contains('rtl')).toBe(true)
  expect(drawer.getAttribute('aria-modal')).toBe('true')
  expect(drawer.textContent).toContain('节点详情')
  expect(drawer.querySelector('.node-detail-heading')?.textContent).toContain('worker-a')
  expect(drawer.querySelector('.node-detail-heading')?.textContent).toContain('不可达')
  expect(drawer.textContent).toContain('当前指标不可用')
  drawer.querySelector<HTMLButtonElement>('.el-drawer__close-btn')!.click()
  await vi.waitFor(()=>expect(drawer.closest('.el-overlay')?.getAttribute('style')).toContain('display: none'))
})
it('opens subscription details in an overlay with backlog feedback',async()=>{
  vi.mocked(eventbusApi.subscriptions).mockResolvedValue({total:1,page:0,items:[{
    id:'route-a',name:'access-storage',mode:'SHARED',sourceScope:'LOCAL',generation:1,
    patterns:['device/**'],tenantId:null,productId:null,deviceCount:0,deviceSample:[],members:['worker-a'],
  }]})
  vi.mocked(eventbusApi.backlog).mockResolvedValue({pending:12,received:3,retrying:2,oldest:null})
  const root=await mount()
  const buttons=[...root.querySelectorAll('button')].filter(b=>b.textContent?.trim()==='详情')
  buttons[1]!.click();await settle()
  const drawer=document.body.querySelector('.el-drawer[aria-label="订阅详情"]')!
  expect(root.contains(drawer)).toBe(false)
  expect(drawer.textContent).toContain('订阅详情')
  expect(drawer.textContent).toContain('access-storage')
  const metrics=[...drawer.querySelectorAll('.backlog-metrics>div')]
  expect(metrics.map(item=>[item.querySelector('dt')?.textContent,item.querySelector('dd')?.textContent])).toEqual([['待发送','12'],['待处理','3'],['有重试','2']])
  expect(drawer.querySelector('.subscription-heading')?.textContent).toContain('共享订阅')
  expect(drawer.querySelector('.subscription-heading')?.textContent).toContain('代次 1')
  expect(drawer.querySelector('.subscription-topics code')?.textContent).toBe('device/**')
  expect(drawer.querySelector('.subscription-rules')?.textContent).toContain('全部设备')
  expect(drawer.querySelector('.subscription-members code')?.textContent).toBe('worker-a')
  expect(drawer.querySelector('.subscription-members')?.textContent).toContain('不可达')
  expect(eventbusApi.backlog).toHaveBeenCalledWith('route-a',expect.any(AbortSignal))
})
it('shows the subscriber source scope separately from its delivery mode',async()=>{
  vi.mocked(eventbusApi.subscriptions).mockResolvedValue({total:1,page:0,items:[{
    id:'a'.repeat(64),name:'access-storage',mode:'SHARED',sourceScope:'LOCAL',generation:1,
    patterns:['device/**'],tenantId:null,productId:null,deviceCount:0,deviceSample:[],members:['worker-a'],
  }]})
  const root=await mount()
  expect(root.textContent).toContain('access-storage')
  expect(root.textContent).toContain('仅本地')
  expect(root.textContent).toContain('共享')
})
it('shows the product scope in both the subscription list and details',async()=>{
  vi.mocked(eventbusApi.subscriptions).mockResolvedValue({total:1,page:0,items:[{
    id:'p'.repeat(64),name:'product-storage',mode:'SHARED',sourceScope:'ALL',generation:1,
    patterns:['device/**'],tenantId:'tenant-a',productId:'product-a',deviceCount:0,deviceSample:[],members:[],
  }]})
  vi.mocked(eventbusApi.backlog).mockResolvedValue({pending:0,received:0,retrying:0,oldest:null})
  const root=await mount()
  expect(root.textContent).toContain('产品 product-a')
  const buttons=[...root.querySelectorAll('button')].filter(b=>b.textContent?.trim()==='详情')
  buttons[1]!.click();await settle()
  const rules=document.body.querySelector('.el-drawer[aria-label="订阅详情"] .subscription-rules')!
  expect(rules.textContent).toContain('产品范围')
  expect(rules.textContent).toContain('product-a')
  expect(rules.textContent).toContain('全部设备')
})
it('retains the old sample and labels it stale after a refresh failure',async()=>{
  const root=await mount()
  vi.mocked(eventbusApi.snapshot).mockRejectedValueOnce(new Error('服务不可用'))
  ;[...root.querySelectorAll('button')].find(b=>b.textContent?.trim()==='刷新')!.click()
  await settle()
  expect(root.textContent).toContain('上次采样数据，已过期')
  expect(root.textContent).toContain('worker-a')
})
it('does not overlap requests and aborts them on leaving the page',async()=>{
  vi.useFakeTimers()
  let resolve!:(s:EventSnapshot)=>void
  vi.mocked(eventbusApi.snapshot).mockImplementation(()=>new Promise(done=>{resolve=done}))
  await mount();await vi.advanceTimersByTimeAsync(15000)
  expect(eventbusApi.snapshot).toHaveBeenCalledTimes(1)
  const signal=vi.mocked(eventbusApi.snapshot).mock.calls[0]![0]!
  close?.();close=undefined;expect(signal.aborted).toBe(true)
  resolve(data);await settle();await vi.advanceTimersByTimeAsync(10000)
  expect(eventbusApi.snapshot).toHaveBeenCalledTimes(1)
})
