// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createRenderer, defineComponent, h, nextTick } from 'vue'
import DevicesView from './DevicesView.vue'
import {deviceFirmwareApi} from '../api/deviceFirmware'
vi.mock('../api/deviceFirmware',()=>({deviceFirmwareApi:{inventories:vi.fn()}}))
import { catalogApi, defaultProductConfiguration, type Product } from '../api/catalog'

const context=vi.hoisted(()=>({
 route:{meta:{catalogPlatform:false},params:{tenantId:'tenant-a'}},
 session:{account:{platformAdmin:false},tenant:{id:'tenant-a',role:'OWNER'},can:vi.fn(()=>true)},
 push:vi.fn(),
}))
vi.mock('../realtime/deviceSubscription',()=>({useDeviceSubscription:vi.fn(),readPresence:vi.fn(async()=>({state:'UNKNOWN'}))}))
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
const listedDevice={id:'device-one',tenantId:'tenant-a',productId:'p',productName:'暖奶器',code:'D1',name:'设备一',modelVersion:'v1',lifecycle:'ACTIVE',credentialVersion:1,revision:0}
function mount(product?:Product){
 const root=node('root'),app=renderer.createApp(DevicesView,{product})
 for(const tag of ['button','input','select','option','alert','skeleton','pagination','tag','table']) app.component(`el-${tag}`,defineComponent({setup:(_,ctx)=>()=>h(tag,ctx.attrs,ctx.slots.default?.())}))
 app.component('el-table-column',defineComponent({setup:(_,ctx)=>()=>h('column',ctx.attrs,ctx.slots.default?.({row:listedDevice}))}))
 app.component('el-dialog',defineComponent({props:['modelValue'],setup:(props,ctx)=>()=>props.modelValue?h('dialog',ctx.attrs,ctx.slots.default?.()):null}))
 app.mount(root);unmount=()=>app.unmount();return root
}
const button=(root:Node,label:string)=>all(root).find(n=>n.type==='button'&&text(n)===label)!
async function settle(){for(let i=0;i<5;i++)await nextTick()}
const product=(overrides:Partial<Product>={}):Product=>({id:'p',tenantId:'tenant-a',code:'warmer',name:'暖奶器',description:'',enabled:true,latestVersion:null,revision:0,draft:{properties:{},events:{},operations:{}},configuration:defaultProductConfiguration(),...overrides})

beforeEach(()=>{
 vi.mocked(deviceFirmwareApi.inventories).mockResolvedValue({})
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

describe('device product filter',()=>{
 const filter=(root:Node)=>all(root).find(n=>n.props['aria-label']==='按产品筛选')!
 const pagination=(root:Node)=>all(root).find(n=>n.type==='pagination')!
 it('combines product and keyword filtering, resets the page and retains the product on pagination and refresh',async()=>{
  const root=mount();await settle()
  pagination(root).props.onCurrentChange(3);await settle()
  all(root).find(n=>n.props['aria-label']==='搜索设备')!.props['onUpdate:modelValue']('sensor')
  filter(root).props['onUpdate:modelValue']('p');filter(root).props.onChange();await settle()
  expect(catalogApi.devices).toHaveBeenLastCalledWith('/catalog/tenants/tenant-a','sensor',1,expect.any(AbortSignal),'p')
  pagination(root).props.onCurrentChange(2);await settle()
  button(root,'刷新').props.onClick();await settle()
  expect(catalogApi.devices).toHaveBeenLastCalledWith('/catalog/tenants/tenant-a','sensor',2,expect.any(AbortSignal),'p')
  filter(root).props['onUpdate:modelValue']('');filter(root).props.onChange();await settle()
  expect(catalogApi.devices).toHaveBeenLastCalledWith('/catalog/tenants/tenant-a','sensor',1,expect.any(AbortSignal),'')
 })
 it('searches products remotely including disabled and unpublished products and retains the selected label',async()=>{
  const disabled=product({enabled:false,latestVersion:null})
  vi.mocked(catalogApi.products).mockResolvedValue({items:[disabled],total:1,page:1,size:20})
  const root=mount();await settle()
  filter(root).props.onVisibleChange(true);await settle()
  expect(catalogApi.products).toHaveBeenLastCalledWith('/catalog/tenants/tenant-a','',1,expect.any(AbortSignal))
  expect(all(filter(root)).some(n=>n.type==='option'&&n.props.value==='p')).toBe(true)
  filter(root).props['onUpdate:modelValue']('p');filter(root).props.onChange();await settle()
  vi.mocked(catalogApi.products).mockResolvedValue({items:[],total:0,page:1,size:20})
  await filter(root).props['remote-method']('another product');await settle()
  expect(catalogApi.products).toHaveBeenLastCalledWith('/catalog/tenants/tenant-a','another product',1,expect.any(AbortSignal))
  expect(all(filter(root)).some(n=>n.type==='option'&&n.props.label==='暖奶器 · warmer')).toBe(true)
 })
 it('keeps the fixed product filter on a product detail page',async()=>{
  const root=mount(product());await settle()
  expect(filter(root)).toBeUndefined()
  pagination(root).props.onCurrentChange(2);await settle()
  expect(catalogApi.devices).toHaveBeenLastCalledWith('/catalog/tenants/tenant-a','',2,expect.any(AbortSignal),'p')
  expect(catalogApi.products).not.toHaveBeenCalled()
 })
 it('ignores stale product searches and aborts the current search on unmount',async()=>{
  const root=mount();await settle()
  let resolveOld!:(value:any)=>void
  vi.mocked(catalogApi.products).mockImplementationOnce(()=>new Promise(resolve=>{resolveOld=resolve}))
  const old=filter(root).props['remote-method']('old')
  const oldSignal=vi.mocked(catalogApi.products).mock.calls.at(-1)![3]
  await filter(root).props['remote-method']('new');await settle()
  expect(oldSignal.aborted).toBe(true)
  resolveOld({items:[product({id:'stale'})],total:1,page:1,size:20});await old;await settle()
  expect(all(filter(root)).some(n=>n.type==='option'&&n.props.value==='stale')).toBe(false)
  const signal=vi.mocked(catalogApi.products).mock.calls.at(-1)![3]
  unmount();expect(signal.aborted).toBe(true)
 })
 it('shows product loading failures and allows retrying by reopening the dropdown',async()=>{
  vi.mocked(catalogApi.products).mockRejectedValueOnce(new Error('search unavailable'))
  const root=mount();await settle()
  await filter(root).props['remote-method']('');await settle()
  expect(all(root).some(n=>n.type==='alert'&&n.props.title.includes('产品选项加载失败'))).toBe(true)
  filter(root).props.onVisibleChange(true);await settle()
  expect(all(root).some(n=>n.type==='alert'&&n.props.title.includes('产品选项加载失败'))).toBe(false)
 })
})
describe('actual firmware list summary',()=>{
 it('fetches one batch for the current page and renders real components separately from model version',async()=>{
  vi.mocked(catalogApi.devices).mockResolvedValue({items:[listedDevice],total:1,page:1,size:20})
  vi.mocked(deviceFirmwareApi.inventories).mockResolvedValue({'device-one':{revision:1,information:null,components:[{component:'main',version:'1.9.0',source:'ota.version.report',occurredAt:'2026-10-23T00:00:00Z',receivedAt:'2026-10-23T00:00:00Z',stale:false,beforeCurrentBoot:false}],checkedAt:'2026-10-23T00:00:00Z',maximumReportAgeSeconds:86400}})
  const root=mount();await settle()
  expect(deviceFirmwareApi.inventories).toHaveBeenCalledWith('/catalog/tenants/tenant-a',['device-one'],expect.any(AbortSignal))
  expect(text(root)).toContain('main 1.9.0');expect(all(root).some(n=>n.props.label==='实际固件版本')).toBe(true)
 })
 it('does not confuse failed reads with unreported firmware and can retry independently',async()=>{
  vi.mocked(catalogApi.devices).mockResolvedValue({items:[listedDevice],total:1,page:1,size:20})
  vi.mocked(deviceFirmwareApi.inventories).mockRejectedValueOnce(new Error('failed')).mockResolvedValueOnce({})
  const root=mount();await settle();expect(text(root)).toContain('固件读取失败');expect(text(root)).not.toContain('尚未上报')
  button(root,'重试固件信息').props.onClick();await settle();expect(text(root)).toContain('尚未上报');expect(catalogApi.devices).toHaveBeenCalledTimes(1)
 })
 it('ignores a firmware response for a page that has already been replaced',async()=>{
  vi.mocked(catalogApi.devices).mockResolvedValueOnce({items:[listedDevice],total:1,page:1,size:20}).mockResolvedValueOnce({items:[],total:0,page:1,size:20})
  let resolve!:(value:any)=>void;vi.mocked(deviceFirmwareApi.inventories).mockImplementationOnce(()=>new Promise(done=>{resolve=done}))
  const root=mount();await settle();button(root,'刷新').props.onClick();await settle()
  resolve({'device-one':{components:[{component:'main',version:'outdated'}]}});await settle();expect(text(root)).not.toContain('outdated')
 })
})
