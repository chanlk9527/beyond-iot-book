// @vitest-environment happy-dom
import {afterEach,beforeEach,expect,it,vi} from 'vitest'
import {createApp,defineComponent,h,nextTick,reactive} from 'vue'
import DeviceSimulatorPanel from './DeviceSimulatorPanel.vue'
import {catalogApi,defaultProductConfiguration,type DeviceDetail} from '../api/catalog'
const mock=vi.hoisted(()=>({start:vi.fn(),stop:vi.fn(),publish:vi.fn(),receive:undefined as any,state:undefined as any}))
vi.mock('../simulator/client',()=>({SimulatorClient:class{
 constructor(_trace:any,state:any,receive:any){mock.state=state;mock.receive=receive}
 start=mock.start;stop=mock.stop;publish=mock.publish
}}))
vi.mock('../api/catalog',async original=>({...await original<typeof import('../api/catalog')>(),catalogApi:{credential:vi.fn()}}))
vi.mock('./DeviceValueInput.vue',()=>({default:defineComponent({props:['modelValue'],setup:p=>()=>h('span',String(p.modelValue))})}))
const model={properties:{temperature:{id:'temperature',access:'READ_WRITE',schema:{type:'DECIMAL',minimum:0,maximum:100}},secret:{id:'secret',access:'WRITE_ONLY',schema:{type:'TEXT'}}},events:{fault:{id:'fault',fields:{reason:{id:'reason',required:true,schema:{type:'TEXT'}}}}},operations:{}}
function detail():DeviceDetail{return {device:{id:'device',tenantId:'tenant',productId:'product',productName:'P',code:'d',name:'D',modelVersion:'v1',lifecycle:'ACTIVE',credentialVersion:1,revision:0},product:{id:'product',tenantId:'tenant',name:'P',code:'p',description:'',enabled:true,latestVersion:'v2',revision:0,draft:{properties:{},events:{},operations:{}},configuration:defaultProductConfiguration()},model:{version:'v1',model,deviceCount:1,publishedAt:''}}}
let dispose:()=>void
async function settle(){for(let i=0;i<10;i++)await nextTick()}
function mount(allowed=true,data=detail(),embedded=false){
 const root=document.createElement('div');document.body.appendChild(root)
 const props=reactive({scope:'/catalog/platform',detail:data,allowed,presence:'OFFLINE',embedded,focusMessageId:''})
 const app=createApp({setup:()=>()=>h(DeviceSimulatorPanel,props)})
 app.component('el-button',defineComponent({props:['disabled'],setup:(p,c)=>()=>h('button',{...c.attrs,disabled:p.disabled},c.slots.default?.())}))
 app.component('el-checkbox',defineComponent({props:['modelValue','disabled'],emits:['update:modelValue'],setup:(p,c)=>()=>h('label',[h('input',{type:'checkbox',checked:p.modelValue,disabled:p.disabled,onChange:(e:Event)=>c.emit('update:modelValue',(e.target as HTMLInputElement).checked)}),c.slots.default?.()])}))
 for(const tag of ['select','option','tag'])app.component('el-'+tag,defineComponent({setup:(_,c)=>()=>h('div',c.attrs,c.slots.default?.())}))
 app.component('el-input-number',defineComponent(()=>()=>null))
 app.component('el-input',defineComponent({props:['modelValue'],emits:['update:modelValue'],setup:(p,c)=>()=>h('input',{...c.attrs,value:p.modelValue,onInput:(e:Event)=>c.emit('update:modelValue',(e.target as HTMLInputElement).value)})}))
 app.component('el-alert',defineComponent({props:['title'],setup:p=>()=>h('p',p.title)}))
 app.mount(root);dispose=()=>{app.unmount();root.remove()};return {root,props}
}
function button(root:HTMLElement,label:string){return [...root.querySelectorAll('button')].find(b=>b.textContent===label)!}
async function connect(root:HTMLElement){const confirm=root.querySelector('input')!;confirm.checked=true;confirm.dispatchEvent(new Event('change'));await settle();button(root,'连接设备').click();await settle();mock.state('connected');await settle()}
beforeEach(()=>{vi.clearAllMocks();mock.publish.mockResolvedValue(undefined);vi.mocked(catalogApi.credential).mockResolvedValue({device:detail().device,clientId:'device',username:'device',secret:'secret'})})
afterEach(()=>{dispose?.();vi.useRealTimers()})
it('reads credentials only on explicit connection and reports the bound model',async()=>{
 const {root}=mount();await settle();expect(catalogApi.credential).not.toHaveBeenCalled();expect(button(root,'连接设备').disabled).toBe(true)
 expect(root.textContent).not.toContain('secret');expect(root.textContent).toContain('v1')
 await connect(root)
 expect(catalogApi.credential).toHaveBeenCalledWith('/catalog/platform',detail().device,expect.any(AbortSignal))
 expect(mock.start).toHaveBeenCalledWith(expect.stringMatching(/^ws:\/\/.*\/mqtt$/),expect.objectContaining({secret:'secret'}))
 button(root,'上报属性').click();await settle()
 expect(mock.publish).toHaveBeenCalledWith(expect.objectContaining({method:'thing.property.report',data:{properties:{temperature:0}}}))
})
it('does not connect readers or child devices',async()=>{
 const {root}=mount(false);await settle();expect(button(root,'连接设备').disabled).toBe(true);expect(catalogApi.credential).not.toHaveBeenCalled()
 dispose();const data=detail();data.product.configuration.deviceType='CHILD';const child=mount(true,data);await settle();expect(button(child.root,'连接设备').disabled).toBe(true)
})
it('does not start a connection after a pending credential request is cancelled',async()=>{
 let resolve!:(v:any)=>void;vi.mocked(catalogApi.credential).mockReturnValue(new Promise(r=>resolve=r))
 const {root}=mount();const confirm=root.querySelector('input')!;confirm.checked=true;confirm.dispatchEvent(new Event('change'));await settle();button(root,'连接设备').click();await settle()
 button(root,'断开连接').click();await settle();resolve({device:detail().device,clientId:'device',username:'device',secret:'secret'});await settle()
 expect(mock.start).not.toHaveBeenCalled()
})
it('responds manually to writes with correlation and reports the applied value',async()=>{
 const {root}=mount();await connect(root)
 mock.receive({version:'1.0',messageId:'write-1',method:'thing.property.write',timestamp:Date.now(),modelVersion:'v1',expiresAt:Date.now()+30000,data:{properties:{temperature:40}}});await settle()
 expect(mock.publish).not.toHaveBeenCalled();button(root,'模拟成功').click();await settle()
 expect(mock.publish).toHaveBeenNthCalledWith(1,expect.objectContaining({method:'thing.property.write.reply',replyTo:'write-1',result:{code:'SUCCESS'},data:{properties:{temperature:40}}}))
 expect(mock.publish).toHaveBeenNthCalledWith(2,expect.objectContaining({method:'thing.property.report',data:{properties:{temperature:40}}}))
})
it('stops periodic reports and connections on unmount or credential rotation',async()=>{
 vi.useFakeTimers();const {root,props}=mount();await connect(root);button(root,'定时上报').click();await settle()
 expect(mock.publish).toHaveBeenCalledTimes(1);await vi.advanceTimersByTimeAsync(5000);expect(mock.publish).toHaveBeenCalledTimes(2)
 props.detail.device.credentialVersion++;await settle();expect(mock.stop).toHaveBeenCalled();await vi.advanceTimersByTimeAsync(10000);expect(mock.publish).toHaveBeenCalledTimes(2)
 dispose();expect(mock.stop.mock.calls.length).toBeGreaterThan(1)
})
it('keeps downlink navigation ahead of a large paginated model and preserves all-page selections',async()=>{
 const data=detail();data.model={...data.model,model:{...data.model.model,properties:Object.fromEntries(Array.from({length:100},(_,i)=>['p'+i,{id:'p'+i,name:'属性 '+i,access:'READ_WRITE',schema:{type:'INTEGER'}}]))}}
 const {root}=mount(true,data);await connect(root)
 expect(root.querySelectorAll('[data-sim-property]')).toHaveLength(8)
 const nav=root.querySelector('nav')!,sections=root.querySelector('.simulator-workspace')!
 expect(nav.compareDocumentPosition(sections)&Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
 button(root,'取消本页').click();await settle();button(root,'下一页属性').click();await settle()
 expect(root.querySelector('[data-sim-property]')?.getAttribute('data-sim-property')).toBe('p8')
 const search=root.querySelector<HTMLInputElement>('[aria-label="搜索模拟属性"]')!;search.value='p99';search.dispatchEvent(new Event('input'));await settle()
 expect(root.querySelectorAll('[data-sim-property]')).toHaveLength(1)
 button(root,'上报属性').click();await settle()
 const properties=mock.publish.mock.calls[0]![0].data.properties
 expect(Object.keys(properties)).toHaveLength(92);expect(properties).not.toHaveProperty('p0');expect(properties).toHaveProperty('p99')
 mock.receive({version:'1.0',messageId:'write-many',method:'thing.property.write',timestamp:Date.now(),modelVersion:'v1',expiresAt:Date.now()+30000,data:{properties:{p99:42}}});await settle()
 expect(root.querySelector('.has-pending')?.textContent).toContain('1 待处理');expect(root.querySelector('.downlink-notice')).not.toBeNull()
 button(root,'立即处理').click();await settle()
 const downlinks=root.querySelector('.downlink-scroll')!.closest('section')!
 expect(downlinks.style.display).not.toBe('none');expect(root.querySelector('.downlink-notice')).toBeNull()
 button(root,'模拟成功').click();await settle();expect(root.querySelector('.has-pending')).toBeNull()
 expect(mock.stop).not.toHaveBeenCalled()
 root.querySelector<HTMLButtonElement>('nav button')!.click();await settle();expect(search.value).toBe('p99')
})
it('paginates large event parameters without omitting required fields outside the current page',async()=>{
 const data=detail();data.model={...data.model,model:{...data.model.model,events:{fault:{id:'fault',fields:Object.fromEntries(Array.from({length:100},(_,i)=>['arg'+i,{id:'arg'+i,required:true,schema:{type:'INTEGER'}}]))}}}}
 const {root}=mount(true,data);await connect(root)
 root.querySelectorAll<HTMLButtonElement>('nav button')[1]!.click();await settle()
 expect(root.querySelectorAll('[data-event-field]')).toHaveLength(8)
 button(root,'下一页参数').click();await settle();expect(root.querySelector('[data-event-field]')?.getAttribute('data-event-field')).toBe('arg8')
 button(root,'上报事件').click();await settle();expect(Object.keys(mock.publish.mock.calls[0]![0].data.fields)).toHaveLength(100)
})
it('starts embedded simulation on downlinks and focuses the command sent by the platform side',async()=>{
 const {root,props}=mount(true,detail(),true);await connect(root)
 expect(root.querySelector<HTMLElement>('.downlink-scroll')!.closest('section')!.style.display).not.toBe('none')
 expect(root.querySelectorAll('.reply-mode')).toHaveLength(1)
 root.querySelectorAll<HTMLButtonElement>('nav button')[0]!.click();await settle()
 props.focusMessageId='paired-request';await settle()
 mock.receive({version:'1.0',messageId:'paired-request',method:'thing.property.write',timestamp:Date.now(),modelVersion:'v1',expiresAt:Date.now()+30000,data:{properties:{temperature:40}}});await settle()
 expect(root.querySelector('.focused-message')?.textContent).toContain('本次平台下发')
 expect(root.querySelector<HTMLElement>('.downlink-scroll')!.closest('section')!.style.display).not.toBe('none')
 expect(mock.stop).not.toHaveBeenCalled()
})
it('sends edited replies, reports the edited actual value and preserves invalid input for correction',async()=>{
 const {root}=mount();await connect(root)
 mock.receive({version:'1.0',messageId:'editable',method:'thing.property.write',timestamp:Date.now(),modelVersion:'v1',expiresAt:Date.now()+30000,data:{properties:{temperature:40}}});await settle()
 const editor=root.querySelector<HTMLInputElement>('[aria-label="editable 应答 JSON"]')!
 expect(JSON.parse(editor.value).data.properties.temperature).toBe(40)
 editor.value='{invalid';editor.dispatchEvent(new Event('input'));await settle();button(root,'发送编辑后的应答').click();await settle()
 expect(mock.publish).not.toHaveBeenCalled();expect(editor.value).toBe('{invalid');expect(root.querySelector('.reply-error')?.textContent).toContain('JSON 格式无效')
 editor.value=JSON.stringify({result:{code:'SUCCESS',message:'实际温度39'},data:{properties:{temperature:39}}});editor.dispatchEvent(new Event('input'));await settle();button(root,'发送编辑后的应答').click();await settle()
 expect(mock.publish).toHaveBeenNthCalledWith(1,expect.objectContaining({method:'thing.property.write.reply',replyTo:'editable',result:{code:'SUCCESS',message:'实际温度39'},data:{properties:{temperature:39}}}))
 expect(mock.publish).toHaveBeenNthCalledWith(2,expect.objectContaining({method:'thing.property.report',data:{properties:{temperature:39}}}))
 expect(root.querySelector('[aria-label="editable 应答 JSON"]')).toBeNull()
})
