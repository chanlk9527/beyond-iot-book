// @vitest-environment happy-dom
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {createApp,defineComponent,h,nextTick} from 'vue'
import ProductDetailView from './ProductDetailView.vue'
import {catalogApi,defaultProductConfiguration,type Product,type ModelSpec} from '../api/catalog'
import {ElMessageBox} from 'element-plus'
const context=vi.hoisted(()=>({can:vi.fn((_permission:string)=>true)}))
vi.mock('vue-router',()=>({useRoute:()=>({meta:{catalogPlatform:true},params:{id:'p'},query:{tab:'model'},path:'/catalog/platform/products/p'}),useRouter:()=>({replace:vi.fn(),push:vi.fn()}),onBeforeRouteLeave:vi.fn(),onBeforeRouteUpdate:vi.fn()}))
vi.mock('../stores/session',()=>({useSessionStore:()=>({account:{platformAdmin:true},can:context.can})}))
vi.mock('element-plus',()=>({ElMessage:{success:vi.fn()},ElMessageBox:{confirm:vi.fn()}}))
vi.mock('../api/catalog',async original=>({...await original<typeof import('../api/catalog')>(),catalogApi:{product:vi.fn(),models:vi.fn(),save:vi.fn(),publish:vi.fn()}}))
vi.mock('../components/CatalogNavigation.vue',()=>({default:defineComponent(()=>()=>null)}))
vi.mock('../components/ProductConfigurationForm.vue',()=>({default:defineComponent(()=>()=>null)}))
vi.mock('../components/ProductDataPolicy.vue',()=>({default:defineComponent(()=>()=>null)}))
vi.mock('./DevicesView.vue',()=>({default:defineComponent(()=>()=>null)}))
vi.mock('../components/ThingModelEditor.vue',()=>({default:defineComponent({props:['modelValue','disabled'],emits:['update:modelValue'],setup:(p,ctx)=>()=>h('textarea',{'data-model':true,value:p.modelValue,disabled:p.disabled,onInput:(e:Event)=>ctx.emit('update:modelValue',(e.target as HTMLTextAreaElement).value)})})}))
const empty=():ModelSpec=>({properties:{},events:{},operations:{}})
const power=():ModelSpec=>({...empty(),properties:{power:{id:'power',schema:{type:'BOOLEAN'},access:'READ_WRITE'}}})
const product=(draft=empty(),latestVersion:string|null=null):Product=>({id:'p',tenantId:null,code:'p',name:'产品',description:'',enabled:true,latestVersion,revision:0,draft,configuration:defaultProductConfiguration()})
let dispose:()=>void
async function settle(){for(let i=0;i<12;i++)await nextTick()}
function mount(){
 const root=document.createElement('div');document.body.appendChild(root);const app=createApp(ProductDetailView)
 for(const tag of ['button','tag','input','skeleton','tabs','tab-pane','table-column'])app.component('el-'+tag,defineComponent({setup:(_,ctx)=>()=>h(tag,ctx.attrs,ctx.slots.default?.())}))
 app.component('el-table',defineComponent({props:['data'],setup:p=>()=>h('pre',JSON.stringify(p.data))}))
 app.component('el-dialog',defineComponent(()=>()=>null))
 app.component('el-alert',defineComponent({props:['title'],setup:p=>()=>h('p',p.title)}))
 app.mount(root);dispose=()=>{app.unmount();root.remove()};return root
}
const publishButton=(root:HTMLElement)=>[...root.querySelectorAll('button')].find(b=>b.textContent==='发布已保存草稿（自动编号）')!
beforeEach(()=>{
 vi.clearAllMocks();context.can.mockReturnValue(true)
 vi.mocked(catalogApi.product).mockResolvedValue(product())
 vi.mocked(catalogApi.models).mockResolvedValue([])
 vi.mocked(ElMessageBox.confirm).mockResolvedValue('confirm' as Awaited<ReturnType<typeof ElMessageBox.confirm>>)
})
afterEach(()=>dispose?.())
it('publishes a confirmed empty draft with an automatically assigned version and updates the status',async()=>{
 const published=product(empty(),'v1');published.revision=1
 vi.mocked(catalogApi.publish).mockResolvedValue(published)
 const root=mount();await settle()
 vi.mocked(catalogApi.models).mockResolvedValue([{version:'v1',model:empty(),deviceCount:0,publishedAt:'2026-10-16T00:00:00Z'}])
 expect(root.querySelector('#publish-version')).toBeNull()
 publishButton(root).click();await settle()
 expect(ElMessageBox.confirm).toHaveBeenCalledWith(expect.stringContaining('当前草稿为空'),expect.any(String),expect.any(Object))
 expect(catalogApi.publish).toHaveBeenCalledWith('/catalog/platform',product(),true,expect.any(AbortSignal))
 expect(root.textContent).toContain('已保存 · 与最新发布版一致')
 expect(publishButton(root).disabled).toBe(true)
})
it('does not publish when confirmation is cancelled',async()=>{
 vi.mocked(ElMessageBox.confirm).mockRejectedValue('cancel')
 const root=mount();await settle();publishButton(root).click();await settle()
 expect(catalogApi.publish).not.toHaveBeenCalled()
})
it('keeps a successful publication committed when refreshing its list fails',async()=>{
 const published=product(empty(),'v1');published.revision=1
 vi.mocked(catalogApi.publish).mockResolvedValue(published)
 const root=mount();await settle()
 vi.mocked(catalogApi.models).mockRejectedValue(new Error('网络中断'))
 publishButton(root).click();await settle()
 expect(root.textContent).toContain('版本 v1 已发布，但版本列表刷新失败')
 expect(publishButton(root).disabled).toBe(true)
 expect(catalogApi.publish).toHaveBeenCalledTimes(1)
})
it('blocks publishing unsaved model edits',async()=>{
 const root=mount();await settle()
 const editor=root.querySelector('textarea')!;editor.value=JSON.stringify(power());editor.dispatchEvent(new Event('input'));await settle()
 expect(root.textContent).toContain('有未保存修改');expect(publishButton(root).disabled).toBe(true)
})
it('does not offer duplicate publication when the saved draft matches the latest version',async()=>{
 vi.mocked(catalogApi.product).mockResolvedValue(product(power(),'v1'))
 vi.mocked(catalogApi.models).mockResolvedValue([{version:'v1',model:power(),deviceCount:3,publishedAt:'2026-10-16T00:00:00Z'}])
 const root=mount();await settle()
 expect(root.textContent).toContain('当前草稿已发布，无需重复发布')
 expect(publishButton(root).disabled).toBe(true)
})
it('hides publish actions from accounts lacking publication permission',async()=>{
 context.can.mockImplementation(permission=>permission!=='product:publish')
 const root=mount();await settle();expect(publishButton(root)).toBeUndefined()
})
