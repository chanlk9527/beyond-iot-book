// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createRenderer, defineComponent, h, nextTick } from 'vue'
import DevicesView from './DevicesView.vue'
import { catalogApi, defaultProductConfiguration, type Product } from '../api/catalog'

const context=vi.hoisted(()=>({
 route:{meta:{catalogPlatform:false},params:{tenantId:'tenant-a'}},
 session:{account:{platformAdmin:false},tenant:{id:'tenant-a',role:'OWNER'},can:vi.fn(()=>true)},
 push:vi.fn(),
}))
vi.mock('vue-router',()=>({useRoute:()=>context.route,useRouter:()=>({push:context.push})}))
vi.mock('../stores/session',()=>({useSessionStore:()=>context.session}))
vi.mock('element-plus',()=>({ElMessage:{success:vi.fn()}}))
vi.mock('../components/CatalogNavigation.vue',()=>({default:defineComponent(()=>()=>null)}))
vi.mock('../api/catalog',async original=>({...await original<typeof import('../api/catalog')>(),catalogApi:{devices:vi.fn(),products:vi.fn(),models:vi.fn(),register:vi.fn(),credential:vi.fn()}}))

type Node={type:string;text:string;props:Record<string,any>;children:Node[];parent:Node|null}
const node=(type:string,text=''):Node=>({type,text,props:{},children:[],parent:null})
const renderer=createRenderer<Node,Node>({
 createElement:tag=>node(tag),createText:text=>node('#text',text),createComment:text=>node('#comment',text),
 setText:(n,text)=>{n.text=text},setElementText:(n,text)=>{n.text=text;n.children=[]},
 patchProp:(n,key,_old,value)=>{n.props[key]=value},parentNode:n=>n.parent,
 nextSibling:n=>n.parent?.children[n.parent.children.indexOf(n)+1]||null,
 insert(n,parent,anchor){n.parent=parent;const index=anchor?parent.children.indexOf(anchor):-1;parent.children.splice(index<0?parent.children.length:index,0,n)},
 remove(n){if(n.parent)n.parent.children.splice(n.parent.children.indexOf(n),1)},
})
const text=(n:Node):string=>n.text+n.children.map(text).join('')
const all=(n:Node):Node[]=>[n,...n.children.flatMap(all)]
let unmount:()=>void
function mount(product?:Product){
 const root=node('root'),app=renderer.createApp(DevicesView,{product})
 for(const tag of ['button','input','select','option','alert','skeleton','pagination','tag','table','table-column']) app.component(`el-${tag}`,defineComponent({setup:(_,ctx)=>()=>h(tag,ctx.attrs,ctx.slots.default?.())}))
 app.component('el-dialog',defineComponent({props:['modelValue'],setup:(props,ctx)=>()=>props.modelValue?h('dialog',ctx.attrs,ctx.slots.default?.()):null}))
 app.mount(root);unmount=()=>app.unmount();return root
}
const button=(root:Node,label:string)=>all(root).find(n=>n.type==='button'&&text(n)===label)!
async function settle(){for(let i=0;i<5;i++)await nextTick()}
const product=(overrides:Partial<Product>={}):Product=>({id:'p',tenantId:'tenant-a',code:'warmer',name:'暖奶器',description:'',enabled:true,latestVersion:null,revision:0,draft:{properties:{},events:{},operations:{}},configuration:defaultProductConfiguration(),...overrides})

beforeEach(()=>{
 vi.clearAllMocks();context.session.account.platformAdmin=false;context.session.tenant.role='OWNER';context.session.can.mockReturnValue(true)
 vi.mocked(catalogApi.devices).mockResolvedValue({items:[],total:0,page:1,size:20})
 vi.mocked(catalogApi.models).mockResolvedValue([])
 vi.mocked(catalogApi.products).mockResolvedValue({items:[product({latestVersion:'v1'})],total:1,page:1,size:20})
})
afterEach(()=>unmount?.())
describe('device registration entry',()=>{
 it('opens an unpublished product and directs the user to version management',async()=>{
  const root=mount(product());await settle()
  expect(button(root,'登记设备').props.disabled).toBe(false)
  button(root,'登记设备').props.onClick();await settle()
  expect(all(root).some(n=>n.type==='alert'&&n.props.title.includes('尚无已发布'))).toBe(true)
  expect(button(root,'登记并生成凭证').props.disabled).toBe(true)
  button(root,'前往版本管理').props.onClick()
  expect(context.push).toHaveBeenCalledWith({path:'/catalog/tenants/tenant-a/products/p',query:{tab:'versions'}})
  expect(catalogApi.register).not.toHaveBeenCalled()
 })
 it('explains a disabled product and prevents submission',async()=>{
  const root=mount(product({enabled:false}));await settle()
  button(root,'登记设备').props.onClick();await settle()
  expect(all(root).some(n=>n.type==='alert'&&n.props.title.includes('产品已停用'))).toBe(true)
  expect(button(root,'登记并生成凭证').props.disabled).toBe(true)
 })
 it('allows a platform admin on a tenant page and defaults to the latest published version',async()=>{
  context.session.account.platformAdmin=true;context.session.tenant.role='VIEWER'
  vi.mocked(catalogApi.models).mockResolvedValue([{version:'v1'},{version:'v2'}] as any)
  vi.mocked(catalogApi.register).mockResolvedValue({clientId:'d',username:'d',secret:'test-only',device:{id:'d'}} as any)
  const root=mount(product({latestVersion:'v2'}));await settle()
  button(root,'登记设备').props.onClick();await settle()
  expect(all(root).find(n=>n.props['aria-label']==='登记模型版本')?.props.modelValue).toBe('v2')
  all(root).find(n=>n.props.id==='device-code')!.props['onUpdate:modelValue']('device-1')
  all(root).find(n=>n.props.id==='device-name')!.props['onUpdate:modelValue']('设备一');await settle()
  expect(button(root,'登记并生成凭证').props.disabled).toBe(false)
  await all(root).find(n=>n.type==='form'&&n.props.class==='tenant-form')!.props.onSubmit({preventDefault(){}});await settle()
  expect(catalogApi.register).toHaveBeenCalledWith('/catalog/tenants/tenant-a',{productId:'p',code:'device-1',name:'设备一',modelVersion:'v2'},expect.any(AbortSignal))
  expect(all(root).some(n=>n.type==='dialog'&&n.props.title==='设备凭证')).toBe(true)
 })
 it('keeps tenant viewers read only',async()=>{
  context.session.tenant.role='VIEWER';const root=mount(product());await settle()
  expect(button(root,'登记设备')).toBeUndefined()
 })
 it('clears the old product and version when searching again',async()=>{
  vi.mocked(catalogApi.models).mockResolvedValue([{version:'v1'}] as any)
  const root=mount();await settle();button(root,'登记设备').props.onClick();await settle()
  const select=all(root).find(n=>n.props['aria-label']==='所属产品')!
  select.props['onUpdate:modelValue']('p');await select.props.onChange();await settle()
  const dialog=all(root).find(n=>n.type==='dialog')!
  button(dialog,'查询').props.onClick();await settle()
  expect(select.props.modelValue).toBe('')
  expect(all(root).find(n=>n.props['aria-label']==='登记模型版本')?.props.modelValue).toBe('')
  expect(button(root,'登记并生成凭证').props.disabled).toBe(true)
 })
})
