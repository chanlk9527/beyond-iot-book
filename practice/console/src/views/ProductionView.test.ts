// @vitest-environment happy-dom
import { afterEach,beforeEach,expect,it,vi } from 'vitest'
import { createApp,nextTick } from 'vue'
import ElementPlus from 'element-plus'
import ProductionView from './ProductionView.vue'
import { productionApi,type ProductionDetail } from '../api/production'
const session=vi.hoisted(()=>({account:{platformAdmin:true},tenant:null,can:vi.fn((_p:string)=>true)}))
vi.mock('../stores/session',()=>({useSessionStore:()=>session}))
vi.mock('vue-router',()=>({useRoute:()=>({meta:{catalogPlatform:true},params:{}})}))
vi.mock('../components/CatalogNavigation.vue',()=>({default:{render:()=>null}}))
vi.mock('../api/production',async original=>({...await original<typeof import('../api/production')>(),productionApi:{list:vi.fn(),detail:vi.fn(),create:vi.fn(),generate:vi.fn(),credentials:vi.fn(),evidence:vi.fn(),release:vi.fn(),deliver:vi.fn()}}))
vi.mock('../api/catalog',async original=>({...await original<typeof import('../api/catalog')>(),catalogApi:{products:vi.fn(),models:vi.fn()}}))
const fixture:ProductionDetail={batch:{id:'batch-1',tenantId:'ta',scopeKey:'ta',code:'WAR-01',name:'暖奶器首批',productId:'p',productName:'暖奶器',modelVersion:'v1',quantity:1,hardwareVersion:'hw1',firmwareVersion:'1.0',status:'PRODUCING',revision:1,recipient:'',deliveryRef:'',createdAt:1792420000000,deliveredAt:null},items:[{deviceId:'device-1',code:'WAR-01-0001',name:'Warmer',sequenceNumber:1,lifecycle:'ACTIVE',credentialVersion:1,burnedVersion:null,testedVersion:null,testResult:null}],events:[{id:'e1',deviceId:null,actor:'operator',action:'DEVICES_GENERATED',station:'',result:'',note:'',credentialVersion:null,occurredAt:1792420000000}],totalEvents:1}
let close:()=>void
const settle=async()=>{for(let i=0;i<20;i++)await nextTick()}
function button(label:string):HTMLButtonElement {const el=[...document.querySelectorAll('button')].find(b=>b.textContent?.trim()===label);if(!el)throw new Error('Missing button '+label);return el}
async function mount(data=structuredClone(fixture)){
 vi.mocked(productionApi.list).mockResolvedValue({items:[data.batch],total:1,page:1,size:20});vi.mocked(productionApi.detail).mockResolvedValue(data)
 const root=document.createElement('div');document.body.append(root);const app=createApp(ProductionView);app.use(ElementPlus);app.mount(root);close=()=>{app.unmount();root.remove()}
 await settle();button('查看批次').click();await settle();return root
}
function station(value:string){const el=document.querySelector('.el-dialog input') as HTMLInputElement;el.value=value;el.dispatchEvent(new Event('input',{bubbles:true}))}
beforeEach(()=>{vi.clearAllMocks();session.can.mockReturnValue(true)})
afterEach(()=>{close?.();vi.restoreAllMocks();document.body.innerHTML=''})
it('blocks testing before burning and release before all devices pass',async()=>{await mount();expect(button('整批放行').disabled).toBe(true);expect(button('出厂测试').disabled).toBe(true);expect(document.body.textContent).toContain('0 / 1');expect(productionApi.credentials).not.toHaveBeenCalled()})
it('hides write and secret controls from a read-only account',async()=>{session.can.mockImplementation(p=>p==='production:read');await mount();for(const text of ['新建生产批次','导出烧录凭证','确认烧录','整批放行'])expect([...document.querySelectorAll('button')].some(b=>b.textContent?.trim()===text)).toBe(false);expect(document.body.textContent).toContain('生成设备')})
it('records the chosen device and current credential version',async()=>{
 const after=structuredClone(fixture);after.batch.revision=2;after.items[0]!.burnedVersion=1;vi.mocked(productionApi.evidence).mockResolvedValue(after)
 await mount();button('确认烧录').click();await settle();station('bench-7');await settle();button('确认').click();await settle()
 expect(productionApi.evidence).toHaveBeenCalledWith('/catalog/tenants/ta','batch-1','device-1','burn',expect.objectContaining({revision:1,credentialVersion:1,station:'bench-7'}),expect.any(AbortSignal));expect(button('出厂测试').disabled).toBe(false)
})
it('blocks delivery when the credential changed after release',async()=>{const d=structuredClone(fixture);d.batch.status='RELEASED';Object.assign(d.items[0]!,{credentialVersion:2,burnedVersion:1,testedVersion:1,testResult:'PASS'});await mount(d);expect(button('登记交付').disabled).toBe(true);expect(document.body.textContent).toContain('需重新测试')})
it('requires acknowledgement before exporting a secret file and never renders secrets',async()=>{
 await mount();button('导出烧录凭证').click();await settle();expect(button('下载凭证文件').disabled).toBe(true);expect(productionApi.credentials).not.toHaveBeenCalled()
 const download=vi.spyOn(HTMLAnchorElement.prototype,'click').mockImplementation(()=>{});vi.spyOn(URL,'createObjectURL').mockReturnValue('blob:test');vi.spyOn(URL,'revokeObjectURL').mockImplementation(()=>{})
 vi.mocked(productionApi.credentials).mockResolvedValue([{deviceId:'device-1',code:'WAR-01-0001',clientId:'device-1',username:'device-1',secret:'test-secret-do-not-display',credentialVersion:1}])
 const check=document.querySelector('.el-dialog input[type=checkbox]') as HTMLInputElement;check.checked=true;check.dispatchEvent(new Event('change',{bubbles:true}));await settle();button('下载凭证文件').click();await settle()
 expect(productionApi.credentials).toHaveBeenCalledWith('/catalog/tenants/ta','batch-1',1,expect.any(AbortSignal));expect(download).toHaveBeenCalledTimes(1);expect(document.body.textContent).not.toContain('test-secret-do-not-display')
})
it('preserves the evidence form on failure',async()=>{vi.mocked(productionApi.evidence).mockRejectedValue(new Error('版本已变化'));await mount();button('确认烧录').click();await settle();station('bench-1');await settle();button('确认').click();await settle();expect(document.body.textContent).toContain('版本已变化');expect((document.querySelector('.el-dialog input') as HTMLInputElement).value).toBe('bench-1')})
