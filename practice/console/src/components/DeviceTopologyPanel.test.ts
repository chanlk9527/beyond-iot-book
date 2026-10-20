// @vitest-environment happy-dom
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {createApp,defineComponent,h,nextTick} from 'vue'
import DeviceTopologyPanel from './DeviceTopologyPanel.vue'
import {catalogApi,defaultProductConfiguration,type Device,type DeviceDetail} from '../api/catalog'
const context=vi.hoisted(()=>({push:vi.fn()}))
vi.mock('vue-router',()=>({useRouter:()=>({push:context.push})}))
vi.mock('../api/catalog',async original=>({...await original<typeof import('../api/catalog')>(),catalogApi:{topology:vi.fn(),bindGateway:vi.fn(),unbindGateway:vi.fn(),detail:vi.fn()}}))
const child:Device={id:'child',tenantId:'ta',productId:'pc',productName:'Sensor',code:'sensor',name:'Sensor',modelVersion:'v1',lifecycle:'ACTIVE',credentialVersion:1,revision:3}
const gateway:Device={...child,id:'gateway',productId:'pg',name:'Gateway'}
function detail(type:'CHILD'|'GATEWAY'):DeviceDetail{return {device:type==='CHILD'?child:gateway,product:{id:'p',tenantId:'ta',code:'p',name:'P',description:'',enabled:true,latestVersion:'v1',revision:0,draft:{properties:{},events:{},operations:{}},configuration:{...defaultProductConfiguration(),deviceType:type}},model:{version:'v1',model:{properties:{},events:{},operations:{}},deviceCount:0,publishedAt:''}}}
const topology=(bound:Device|null=null)=>({gateway:bound,children:{items:[],total:0,page:1,size:20}})
let dispose:()=>void
async function settle(){for(let i=0;i<8;i++)await nextTick()}
function mount(type:'CHILD'|'GATEWAY'='CHILD',writable=true){
 const root=document.createElement('div');document.body.appendChild(root);const app=createApp(DeviceTopologyPanel,{scope:'/catalog/platform',detail:detail(type),writable})
 app.component('el-button',defineComponent({props:['nativeType','disabled','loading'],setup:(p,c)=>()=>h('button',{...c.attrs,type:p.nativeType||'button',disabled:p.disabled||p.loading},c.slots.default?.())}))
 app.component('el-input',defineComponent({props:['modelValue'],emits:['update:modelValue'],setup:(p,c)=>()=>h('input',{...c.attrs,value:p.modelValue,onInput:(e:Event)=>c.emit('update:modelValue',(e.target as HTMLInputElement).value)})}))
 app.component('el-alert',defineComponent({props:['title'],setup:p=>()=>h('p',{'role':'alert'},p.title)}))
 app.component('el-skeleton',defineComponent(()=>()=>null))
 app.component('el-table',defineComponent({props:['data'],setup:p=>()=>h('pre',JSON.stringify(p.data))}))
 app.component('el-table-column',defineComponent(()=>()=>null))
 app.component('el-pagination',defineComponent(()=>()=>null))
 app.mount(root);dispose=()=>{app.unmount();root.remove()};return root
}
beforeEach(()=>{vi.clearAllMocks();vi.mocked(catalogApi.topology).mockResolvedValue(topology());vi.mocked(catalogApi.bindGateway).mockResolvedValue(topology(gateway));vi.mocked(catalogApi.unbindGateway).mockResolvedValue(topology());vi.mocked(catalogApi.detail).mockResolvedValue(detail('CHILD'))})
afterEach(()=>dispose?.())
it('uses the actual tenant scope and hides write controls for readers',async()=>{
 const root=mount('CHILD',false);await settle()
 expect(catalogApi.topology).toHaveBeenCalledWith('/catalog/tenants/ta','child',1,expect.any(AbortSignal))
 expect(root.textContent).toContain('尚未绑定网关');expect(root.querySelector('form')).toBeNull()
})
it('binds a child with its revision and retains input on failure',async()=>{
 vi.mocked(catalogApi.bindGateway).mockRejectedValue(new Error('修订号已变化'))
 const root=mount();await settle();const input=root.querySelector('input')!;input.value='gateway';input.dispatchEvent(new Event('input'));await settle()
 root.querySelector('form')!.dispatchEvent(new Event('submit',{cancelable:true}));await settle()
 expect(catalogApi.bindGateway).toHaveBeenCalledWith('/catalog/tenants/ta',child,'gateway',expect.any(AbortSignal))
 expect(input.value).toBe('gateway');expect(root.textContent).toContain('修订号已变化')
})
it('reads the selected child before binding it from a gateway page',async()=>{
 const root=mount('GATEWAY');await settle();const input=root.querySelector('input')!;input.value='child';input.dispatchEvent(new Event('input'));await settle()
 root.querySelector('form')!.dispatchEvent(new Event('submit',{cancelable:true}));await settle()
 expect(catalogApi.detail).toHaveBeenCalledWith('/catalog/tenants/ta','child',expect.any(AbortSignal))
 expect(catalogApi.bindGateway).toHaveBeenCalledWith('/catalog/tenants/ta',child,'gateway',expect.any(AbortSignal));expect(root.querySelector('input')!.value).toBe('')
})
it('shows the current gateway and unbinds that exact relation',async()=>{
 vi.mocked(catalogApi.topology).mockResolvedValue(topology(gateway))
 const root=mount();await settle();expect(root.querySelector('form')).toBeNull()
 const button=[...root.querySelectorAll('button')].find(b=>b.textContent==='解绑网关')!;button.click();await settle()
 expect(catalogApi.unbindGateway).toHaveBeenCalledWith('/catalog/tenants/ta',child,'gateway',expect.any(AbortSignal))
})