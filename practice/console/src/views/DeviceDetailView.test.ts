// @vitest-environment happy-dom
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {createApp,defineComponent,h,nextTick} from 'vue'
import DeviceDetailView from './DeviceDetailView.vue'
import {catalogApi,defaultProductConfiguration,type DeviceDetail,type ModelSpec} from '../api/catalog'
const context=vi.hoisted(()=>({push:vi.fn(),can:vi.fn(()=>true)}))
vi.mock('vue-router',()=>({useRoute:()=>({meta:{catalogPlatform:true},params:{id:'d1'}}),useRouter:()=>({push:context.push})}))
vi.mock('../stores/session',()=>({useSessionStore:()=>({account:{platformAdmin:true},can:context.can})}))
vi.mock('element-plus',()=>({ElMessage:{success:vi.fn()},ElMessageBox:{confirm:vi.fn()}}))
vi.mock('../api/catalog',async original=>({...await original<typeof import('../api/catalog')>(),catalogApi:{detail:vi.fn(),runtime:vi.fn()}}))
vi.mock('../components/CatalogNavigation.vue',()=>({default:defineComponent(()=>()=>null)}))
vi.mock('../components/DeviceShadowPanel.vue',()=>({default:defineComponent({props:['model'],setup:p=>()=>h('div',{'data-state':true},JSON.stringify(p.model))})}))
const empty=():ModelSpec=>({properties:{},events:{},operations:{}})
const model=():ModelSpec=>({properties:{nightLight:{id:'nightLight',schema:{type:'BOOLEAN'},access:'READ_WRITE'}},events:{alert:{id:'alert',fields:{message:{id:'message',schema:{type:'TEXT'},required:true}}}},operations:{reboot:{id:'reboot',inputs:{}}}})
function detail(bound=model(),draft=bound):DeviceDetail{return {
 device:{id:'d1',tenantId:'ta',productId:'p',productName:'bbm',code:'device',name:'bbm02',modelVersion:'v1',lifecycle:'ACTIVE',credentialVersion:1,revision:0},
 product:{id:'p',tenantId:'ta',code:'bbm',name:'bbm',description:'',enabled:true,latestVersion:'v1',revision:0,draft,configuration:defaultProductConfiguration()},
 model:{version:'v1',model:bound,deviceCount:1,publishedAt:'2026-10-16T00:00:00Z'}}}
let dispose:()=>void
async function settle(){for(let i=0;i<8;i++)await nextTick()}
function mount(){
 const root=document.createElement('div');document.body.appendChild(root);const app=createApp(DeviceDetailView)
 for(const tag of ['button','tag','skeleton','input','select','option','table-column'])app.component('el-'+tag,defineComponent({setup:(_,ctx)=>()=>h(tag,ctx.attrs,ctx.slots.default?.())}))
 app.component('el-tabs',defineComponent({props:['modelValue'],emits:['update:modelValue'],setup:(_,ctx)=>()=>h('nav',{onClick:(e:MouseEvent)=>{const tab=(e.target as HTMLElement).dataset.tab;if(tab)ctx.emit('update:modelValue',tab)}},ctx.slots.default?.())}))
 app.component('el-tab-pane',defineComponent({props:['name','label'],setup:p=>()=>h('button',{'data-tab':p.name},p.label)}))
 app.component('el-dialog',defineComponent(()=>()=>null))
 app.component('el-table',defineComponent({props:['data'],setup:p=>()=>h('pre',JSON.stringify(p.data))}))
 app.component('el-alert',defineComponent({props:['title'],setup:(p,ctx)=>()=>h('div',{class:'test-alert'},[h('strong',p.title),ctx.slots.default?.()])}))
 app.mount(root);dispose=()=>{app.unmount();root.remove()};return root
}
async function tab(root:HTMLElement,name:string){(root.querySelector('[data-tab="'+name+'"]') as HTMLButtonElement).click();await settle()}
beforeEach(()=>{vi.clearAllMocks();context.can.mockReturnValue(true);vi.mocked(catalogApi.detail).mockResolvedValue(detail());vi.mocked(catalogApi.runtime).mockResolvedValue({properties:[],events:[]})})
afterEach(()=>dispose?.())
it('has one device-state tab using the bound model and does not fetch the redundant property snapshot',async()=>{
 const root=mount();await settle()
 const labels=[...root.querySelectorAll('nav button')].map(n=>n.textContent)
 expect(labels).toContain('设备状态');expect(labels).not.toContain('设备影子');expect(labels).not.toContain('物模型数据')
 expect(catalogApi.runtime).not.toHaveBeenCalled()
 await tab(root,'state')
 expect(root.querySelector('[data-state]')?.textContent).toContain('nightLight')
})
it('renders the inherited published properties, operations and event field definitions',async()=>{
 vi.mocked(catalogApi.detail).mockResolvedValue(detail(model(),empty()))
 const root=mount();await settle();await tab(root,'capability')
 expect(root.textContent).toContain('1 项属性');expect(root.textContent).toContain('1 项操作');expect(root.textContent).toContain('1 项事件')
 expect(root.textContent).toContain('nightLight');expect(root.textContent).toContain('reboot');expect(root.textContent).toContain('message')
 expect(root.textContent).toContain('已发布版本 v1')
})
it('explains an empty bound version with unpublished product capabilities without pretending the draft is deployed',async()=>{
 vi.mocked(catalogApi.detail).mockResolvedValue(detail(empty(),model()))
 const root=mount();await settle();await tab(root,'capability')
 expect(root.textContent).toContain('绑定版本为空，产品草稿中已有能力定义')
 expect(root.textContent).toContain('当前绑定版本未定义任何能力')
 expect(root.textContent).toContain('0 项属性')
 const button=[...root.querySelectorAll('button')].find(n=>n.textContent==='查看产品物模型草稿')!
 button.click()
 expect(context.push).toHaveBeenCalledWith({path:'/catalog/tenants/ta/products/p',query:{tab:'model'}})
})
it('keeps the device model available when event loading fails',async()=>{
 vi.mocked(catalogApi.runtime).mockRejectedValue(new Error('Events unavailable'))
 const root=mount();await settle();await tab(root,'events')
 expect(root.textContent).toContain('事件加载失败')
 await tab(root,'capability')
 expect(root.textContent).toContain('nightLight')
 expect(root.textContent).not.toContain('设备详情加载失败')
})
it('explains a newer published version without telling the user to publish it again',async()=>{
 const data=detail(empty(),model());data.product.latestVersion='v2'
 vi.mocked(catalogApi.detail).mockResolvedValue(data)
 const root=mount();await settle();await tab(root,'capability')
 expect(root.textContent).toContain('当前绑定 v1，产品最新发布 v2')
 expect(root.textContent).toContain('草稿差异不代表尚未发布')
 expect(root.textContent).not.toContain('产品草稿需保存并发布为新版本')
})
it('does not offer product navigation without product-page authorization',async()=>{
 context.can.mockImplementation(()=>false)
 vi.mocked(catalogApi.detail).mockResolvedValue(detail(empty(),model()))
 const root=mount();await settle();await tab(root,'capability')
 expect(root.textContent).toContain('当前绑定版本未定义任何能力')
 expect(root.textContent).not.toContain('查看产品物模型草稿')
})

