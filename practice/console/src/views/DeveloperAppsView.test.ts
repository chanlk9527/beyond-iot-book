// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import ElementPlus from 'element-plus'
import DeveloperAppsView from './DeveloperAppsView.vue'
import { developerApi, type DeveloperApp } from '../api/developer'

const state=vi.hoisted(()=>({account:{platformAdmin:false,tenantId:'ta'}}))
vi.mock('../stores/session',()=>({useSessionStore:()=>state}))
vi.mock('../api/developer',async original=>({...await original<typeof import('../api/developer')>(),developerApi:{list:vi.fn(),reviews:vi.fn(),audit:vi.fn(),platformAudit:vi.fn()}}))
let close:()=>void
const app:DeveloperApp={appId:'app-1',name:'家居 App',tenantId:'ta',tenantName:'测试租户',requestedScopes:'device:read video:watch',scopes:'',reviewStatus:'PENDING',reviewNote:'',enabled:false,revision:1}
const settle=async()=>{for(let i=0;i<10;i++)await nextTick()}
async function mount(){const root=document.createElement('div');document.body.append(root);const instance=createApp(DeveloperAppsView);instance.use(ElementPlus);instance.mount(root);close=()=>{instance.unmount();root.remove()};await settle();return root}
beforeEach(()=>{vi.clearAllMocks();state.account.platformAdmin=false;vi.mocked(developerApi.list).mockResolvedValue([{...app}]);vi.mocked(developerApi.reviews).mockResolvedValue([{...app}])})
afterEach(()=>{close?.();document.body.innerHTML=''})
it('shows tenant applications and keeps credentials unavailable while awaiting review',async()=>{
 const root=await mount()
 expect(root.textContent).toContain('我的应用')
 expect(root.textContent).toContain('待审核')
 expect(root.textContent).not.toContain('获取 / 轮换凭证')
 expect([...root.querySelectorAll('button')].some(b=>b.textContent==='创建应用')).toBe(true)
 expect(developerApi.list).toHaveBeenCalledWith('ta',expect.any(AbortSignal))
})
it('shows approved capabilities and the credential action to the tenant administrator',async()=>{
 vi.mocked(developerApi.list).mockResolvedValue([{...app,reviewStatus:'APPROVED',enabled:true,scopes:'device:read'}])
 const root=await mount()
 expect(root.textContent).toContain('获取 / 轮换凭证')
 expect(root.textContent).toContain('查询设备')
 expect(root.textContent).not.toContain('clientSecret')
})
it('shows the platform review workflow without tenant credential actions',async()=>{
 state.account.platformAdmin=true
 const root=await mount()
 expect(root.textContent).toContain('开放平台管理')
 expect(root.textContent).toContain('审核')
 expect([...root.querySelectorAll('button')].some(b=>b.textContent==='创建应用')).toBe(false)
 expect(root.textContent).not.toContain('获取 / 轮换凭证')
 expect(developerApi.reviews).toHaveBeenCalled()
})
