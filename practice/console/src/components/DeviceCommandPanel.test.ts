// @vitest-environment happy-dom
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {createApp,defineComponent,h,nextTick} from 'vue'
import ElementPlus from 'element-plus'
import DeviceCommandPanel from './DeviceCommandPanel.vue'
import {catalogApi,type ModelSpec,type Device,type DownlinkStatus} from '../api/catalog'

const context=vi.hoisted(()=>({pending:undefined as undefined|((value:boolean)=>void)}))
vi.mock('../api/catalog',async original=>({...await original<typeof import('../api/catalog')>(),catalogApi:{writeProperties:vi.fn(),invoke:vi.fn()}}))
vi.mock('./DeviceValueInput.vue',()=>({default:defineComponent({props:['modelValue','schema','disabled','label'],emits:['update:modelValue'],setup:(p,c)=>()=>h('input',{'aria-label':p.label,value:p.modelValue,disabled:p.disabled,onInput:(e:Event)=>c.emit('update:modelValue',p.schema.type==='INTEGER'?Number((e.target as HTMLInputElement).value):(e.target as HTMLInputElement).value)})})}))
vi.mock('./DownlinkResult.vue',()=>({default:defineComponent({props:['initial'],emits:['pending'],setup:(p,c)=>{context.pending=value=>c.emit('pending',value);return()=>h('div',{'data-result':p.initial.status},p.initial.message)}})}))
const device:Device={id:'d1',tenantId:'tenant-a',productId:'p1',productName:'P',code:'d1',name:'Device',modelVersion:'v1',lifecycle:'ACTIVE',credentialVersion:1,revision:0}
const success:DownlinkStatus={messageId:'m1',commandId:null,kind:'PROPERTY_WRITE',status:'SUCCEEDED',code:null,message:'成功',createdAt:'',expiresAt:''}
function model():ModelSpec{
 return {properties:Object.fromEntries(Array.from({length:100},(_,i)=>{const id='field'+String(i).padStart(2,'0');return[id,{id,name:'属性 '+i,description:i===98?'室内温度':'说明',schema:{type:'INTEGER',minimum:0,maximum:100},access:i===99?'READ_ONLY':'READ_WRITE'}]})),events:{},operations:Object.fromEntries(Array.from({length:100},(_,i)=>{const id='operation'+String(i).padStart(2,'0');return[id,{id,name:'操作 '+i,inputs:i===99?Object.fromEntries(Array.from({length:100},(_,n)=>['arg'+n,{id:'arg'+n,name:'参数 '+n,schema:{type:'INTEGER'},required:true}])):{target:{id:'target',name:'目标',schema:{type:'INTEGER'},required:true},note:{id:'note',name:'备注',schema:{type:'TEXT'},required:false}}}]}))}
}
let close:()=>void
async function settle(){for(let i=0;i<8;i++)await nextTick()}
async function mount(writable=true,data=model()){
 const root=document.createElement('div');document.body.appendChild(root)
 const app=createApp(DeviceCommandPanel,{scope:'/catalog/platform',device,model:data,writable});app.use(ElementPlus);app.mount(root)
 close=()=>{app.unmount();root.remove()};await settle();return root
}
function button(root:HTMLElement,text:string){return [...root.querySelectorAll('button')].find(b=>b.textContent?.trim()===text)!}
async function search(root:HTMLElement,text:string,label='搜索控制能力'){
 const input=root.querySelector<HTMLInputElement>(`input[aria-label="${label}"]`)!;input.value=text;input.dispatchEvent(new Event('input'));await settle()
}
async function value(root:HTMLElement,label:string,text:string){const input=root.querySelector<HTMLInputElement>(`input[aria-label="${label}"]`)!;input.value=text;input.dispatchEvent(new Event('input'));await settle()}
async function choose(root:HTMLElement,id:string){const item=[...root.querySelectorAll<HTMLButtonElement>('.capability-item')].find(b=>b.querySelector('code')?.textContent===id)!;item.click();await settle()}
beforeEach(()=>{vi.clearAllMocks();context.pending=undefined;vi.mocked(catalogApi.writeProperties).mockResolvedValue(success);vi.mocked(catalogApi.invoke).mockResolvedValue({...success,kind:'OPERATION'})})
afterEach(()=>close?.())

it('bounds a large model and searches names, identifiers and descriptions without losing selected values',async()=>{
 const root=await mount();expect(root.textContent).toContain('99 项可写');expect(root.querySelectorAll('.capability-item')).toHaveLength(8);expect(root.querySelectorAll('[data-property]')).toHaveLength(0)
 await choose(root,'field00');await value(root,'field00 写入值','42')
 await search(root,'FIELD98');expect(root.querySelectorAll('.capability-item')).toHaveLength(1);await choose(root,'field98')
 await search(root,'室内温度');expect(root.querySelector('.capability-item code')?.textContent).toBe('field98')
 await search(root,'不存在');expect(root.textContent).toContain('没有匹配的属性');expect(root.querySelector<HTMLInputElement>('[aria-label="field00 写入值"]')?.value).toBe('42')
 button(root,'下发属性').click();await settle()
 expect(catalogApi.writeProperties).toHaveBeenCalledWith('/catalog/tenants/tenant-a','d1',{field00:42,field98:0},expect.any(AbortSignal))
})
it('submits all selected pages, limits mounted editors and removes hidden values from the request',async()=>{
 const root=await mount();button(root,'选中本页').click();await settle();await value(root,'field00 写入值','25')
 root.querySelector<HTMLButtonElement>('.capability-browser .btn-next')!.click();await settle();button(root,'选中本页').click();await settle()
 expect(root.querySelectorAll('[data-property]')).toHaveLength(8);expect(root.textContent).toContain('已选 16 项，所有页均会下发')
 button(root,'下发属性').click();await settle();const sent=vi.mocked(catalogApi.writeProperties).mock.calls[0]![2];expect(Object.keys(sent)).toHaveLength(16);expect(sent.field00).toBe(25);expect(sent.field15).toBe(0)
 root.querySelector<HTMLButtonElement>('[aria-label="移除 field00"]')!.click();await settle();button(root,'下发属性').click();await settle()
 expect(vi.mocked(catalogApi.writeProperties).mock.calls[1]![2]).not.toHaveProperty('field00')
 button(root,'清空选择').click();await settle();expect(button(root,'下发属性').disabled).toBe(true)
})
it('preserves each operation draft across searches and mode changes and omits unchecked optional inputs',async()=>{
 const root=await mount();root.querySelector<HTMLButtonElement>('.control-modes button:nth-child(2)')!.click();await settle()
 await value(root,'target 参数','37')
 root.querySelector<HTMLInputElement>('[data-parameter="note"] input[type="checkbox"]')!.click();await settle();await value(root,'note 参数','保留备注')
 await choose(root,'operation01');await value(root,'target 参数','12')
 root.querySelector<HTMLButtonElement>('.control-modes button:nth-child(1)')!.click();await settle();root.querySelector<HTMLButtonElement>('.control-modes button:nth-child(2)')!.click();await settle()
 await choose(root,'operation00');expect(root.querySelector<HTMLInputElement>('[aria-label="target 参数"]')?.value).toBe('37');expect(root.querySelector<HTMLInputElement>('[aria-label="note 参数"]')?.value).toBe('保留备注')
 button(root,'调用功能').click();await settle();expect(catalogApi.invoke).toHaveBeenLastCalledWith('/catalog/tenants/tenant-a','d1','operation00',{target:37,note:'保留备注'},expect.any(AbortSignal))
 await choose(root,'operation01');button(root,'调用功能').click();await settle();expect(catalogApi.invoke).toHaveBeenLastCalledWith('/catalog/tenants/tenant-a','d1','operation01',{target:12},expect.any(AbortSignal))
})
it('finds a late operation and parameter without omitting required fields on other pages',async()=>{
 const root=await mount();root.querySelector<HTMLButtonElement>('.control-modes button:nth-child(2)')!.click();await settle();await search(root,'operation99');await choose(root,'operation99')
 expect(root.querySelectorAll('[data-parameter]')).toHaveLength(8);await search(root,'arg99','搜索操作参数');expect(root.querySelectorAll('[data-parameter]')).toHaveLength(1);await value(root,'arg99 参数','91')
 button(root,'调用功能').click();await settle();const sent=vi.mocked(catalogApi.invoke).mock.calls[0]![3];expect(Object.keys(sent)).toHaveLength(100);expect(sent.arg99).toBe(91);expect(sent.arg0).toBe(0)
})
it('retains inputs after failure and blocks duplicate sends while a result is pending',async()=>{
 vi.mocked(catalogApi.writeProperties).mockRejectedValueOnce(Error('网络失败'))
 const root=await mount();await choose(root,'field00');await value(root,'field00 写入值','19');button(root,'下发属性').click();await settle()
 expect(root.textContent).toContain('网络失败');expect(root.querySelector<HTMLInputElement>('[aria-label="field00 写入值"]')?.value).toBe('19')
 button(root,'下发属性').click();await settle();context.pending!(true);await settle();expect(button(root,'下发属性').disabled).toBe(true)
 await search(root,'field98');expect(root.querySelector('.capability-item code')?.textContent).toBe('field98');expect(root.textContent).toContain('属性写入 · 1 项')
 context.pending!(false);await settle();expect(button(root,'下发属性').disabled).toBe(false)
})
it('allows readers to browse without selecting or sending and explains empty models',async()=>{
 const root=await mount(false);expect(root.querySelector<HTMLButtonElement>('.capability-item')?.disabled).toBe(true);expect(button(root,'下发属性').disabled).toBe(true)
 await search(root,'field98');expect(root.querySelectorAll('.capability-item')).toHaveLength(1);expect(catalogApi.writeProperties).not.toHaveBeenCalled()
 close();const empty=await mount(true,{properties:{},events:{},operations:{}});expect(empty.textContent).toContain('当前绑定模型没有可调用操作。');expect(button(empty,'调用功能').disabled).toBe(true)
})
