// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import ElementPlus from 'element-plus'
import DownlinkResult from './DownlinkResult.vue'
import { catalogApi, type DownlinkStatus } from '../api/catalog'

vi.mock('../api/catalog', () => ({ catalogApi: { downlink: vi.fn() } }))
const initial: DownlinkStatus = { messageId:'m1',commandId:null,kind:'PROPERTY_WRITE',status:'QUEUED',code:null,message:'等待路由',createdAt:'2026-10-19T01:00:00Z',expiresAt:'2026-10-19T01:00:30Z' }
let close: (() => void) | undefined
async function settle(){for(let i=0;i<8;i++)await nextTick()}
async function mount(value=initial){
  const root=document.createElement('div');document.body.append(root)
  const app=createApp(DownlinkResult,{scope:'/catalog/tenants/ta',deviceId:'d1',initial:value});app.use(ElementPlus);app.mount(root)
  close=()=>{app.unmount();root.remove()};await settle();return root
}
beforeEach(()=>{vi.useFakeTimers();vi.clearAllMocks()})
afterEach(()=>{close?.();close=undefined;vi.useRealTimers()})
it('continues polling queued and routed commands until a final device result',async()=>{
  vi.mocked(catalogApi.downlink).mockResolvedValueOnce({...initial,status:'ROUTED'}).mockResolvedValueOnce({...initial,status:'SUCCEEDED'})
  const root=await mount();expect(root.textContent).toContain('已保存，等待路由')
  await vi.advanceTimersByTimeAsync(1000);await settle();expect(root.textContent).toContain('已路由，等待接入节点')
  await vi.advanceTimersByTimeAsync(1000);await settle();expect(root.textContent).toContain('设备执行成功')
  await vi.advanceTimersByTimeAsync(5000);expect(catalogApi.downlink).toHaveBeenCalledTimes(2)
})
it('shows an uncertain send without automatically polling or requesting another command',async()=>{
  const root=await mount({...initial,status:'UNKNOWN',message:'传输结果未知，不自动重发'})
  expect(root.textContent).toContain('投递结果未知')
  await vi.advanceTimersByTimeAsync(5000);expect(catalogApi.downlink).not.toHaveBeenCalled()
})
