// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick, reactive } from 'vue'
import ConsumerPlatformPanel from './ConsumerPlatformPanel.vue'
import { catalogApi, defaultProductConfiguration, type DeviceDetail, type DeviceShadow } from '../api/catalog'
import type { DeviceNotice, DeviceResource } from '../realtime/deviceSubscription'
import { useSessionStore } from '../stores/session'

const state = vi.hoisted(() => ({
  receive: undefined as undefined | ((notice: DeviceNotice) => void), resources: undefined as undefined | (() => DeviceResource[]),
}))
vi.mock('../api/catalog', async original => ({...await original<typeof import('../api/catalog')>(), catalogApi: {detail:vi.fn(),shadow:vi.fn(),writeProperties:vi.fn(),invoke:vi.fn()}}))
vi.mock('../stores/session', () => ({useSessionStore:vi.fn()}))
vi.mock('../realtime/deviceSubscription', () => ({readPresence:vi.fn(async () => ({state:'ONLINE'})), useDeviceSubscription:(resources: () => DeviceResource[], receive:(notice:DeviceNotice)=>void) => {state.resources=resources;state.receive=receive}}))
const session = reactive({authenticated:true,account:{id:'account-a',username:'tester',platformAdmin:false,tenantId:'tenant-a'},ensureSession:vi.fn(async()=>{}),can:(permission:string)=>['device:read','device:write'].includes(permission)})
const model = {
  properties:{ nurseryTemp:{id:'nurseryTemp',name:'育儿房温度',access:'READ_ONLY',schema:{type:'DECIMAL',unit:'°C'}}, infrared:{id:'infrared',name:'红外模式',access:'READ_WRITE',schema:{type:'ENUM',enumValues:{auto:'自动',on:'开启'}}} },
  operations:{move:{id:'move',name:'转动摄像头',inputs:{angle:{id:'angle',name:'角度',required:true,schema:{type:'INTEGER',minimum:0,maximum:90}}}}}, events:{},
}
const device = (id='user-entered-id'):DeviceDetail => ({device:{id,tenantId:'tenant-a',productId:'product-a',productName:'BBM',code:'bbm-code',name:'我的婴儿监视器',modelVersion:'v7',lifecycle:'ACTIVE',credentialVersion:1,revision:0},product:{id:'product-a',tenantId:'tenant-a',code:'bbm',name:'BBM',description:'',enabled:true,latestVersion:'v8',revision:0,draft:{properties:{draftOnly:{}},operations:{},events:{}},configuration:defaultProductConfiguration()},model:{version:'v7',model,publishedAt:'',deviceCount:1}})
const snapshot = (version=1, temperature=23.2):DeviceShadow => ({version,modelVersion:'v7',reported:{nurseryTemp:{value:temperature,observedAt:'',receivedAt:'2026-10-21T04:00:00Z',messageId:'report-'+version},infrared:{value:'auto',observedAt:'',receivedAt:'2026-10-21T04:00:00Z',messageId:'report-'+version}},desired:{},delta:{},updatedAt:'2026-10-21T04:00:00Z'})
let root: HTMLDivElement, dispose:()=>void, setTab:(tab:string)=>void
async function flush(){await nextTick();await Promise.resolve();await Promise.resolve();await nextTick()}
async function settle(){await flush();await vi.advanceTimersByTimeAsync(180);await flush()}
async function mount(){
  root=document.createElement('div');document.body.append(root)
  const props=reactive({tab:'home'});setTab=tab=>props.tab=tab
  const app=createApp({setup:()=>()=>h(ConsumerPlatformPanel,props)})
  app.component('RouterLink',defineComponent({setup:(_,ctx)=>()=>h('a',ctx.slots.default?.())}))
  app.component('el-dialog',defineComponent({props:['modelValue'],setup:(p,ctx)=>()=>p.modelValue?h('section',ctx.slots.default?.()):null}))
  app.component('el-alert',defineComponent({setup:()=>()=>h('div')}))
  app.component('el-button',defineComponent({setup:(_,ctx)=>()=>h('button',ctx.slots.default?.())}))
  app.component('el-input',defineComponent({setup:()=>()=>h('input')}))
  app.component('el-select',defineComponent({props:['modelValue'],emits:['update:modelValue'],setup:(p,ctx)=>()=>h('select',{value:p.modelValue,onChange:(e:Event)=>ctx.emit('update:modelValue',(e.target as HTMLSelectElement).value)},ctx.slots.default?.())}))
  app.component('el-option',defineComponent({props:['value','label'],setup:p=>()=>h('option',{value:p.value},p.label)}))
  app.component('el-input-number',defineComponent({props:['modelValue'],emits:['update:modelValue'],setup:(p,ctx)=>()=>h('input',{type:'number',value:p.modelValue,onInput:(e:Event)=>ctx.emit('update:modelValue',Number((e.target as HTMLInputElement).value))})}))
  app.mount(root);dispose=()=>{app.unmount();root.remove()};await flush()
}
async function click(text:string){const b=[...root.querySelectorAll('button')].find(b=>b.textContent?.trim()===text);expect(b,text).toBeTruthy();b!.click();await flush()}
async function bind(id='user-entered-id'){
  await click('绑定平台设备');const input=root.querySelector<HTMLInputElement>('#baby-platform-id')!;input.value=id;input.dispatchEvent(new Event('input',{bubbles:true}))
  input.closest('form')!.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await settle()
}
beforeEach(()=>{
  vi.useFakeTimers();localStorage.clear();vi.clearAllMocks();vi.mocked(useSessionStore).mockReturnValue(session as unknown as ReturnType<typeof useSessionStore>)
  session.authenticated=true;session.account.id='account-a';session.account.platformAdmin=false
  vi.mocked(catalogApi.detail).mockImplementation(async (_scope,id)=>device(id))
  vi.mocked(catalogApi.shadow).mockResolvedValue(snapshot())
  const reply={messageId:'down-1',commandId:null,kind:'PROPERTY_WRITE',status:'SUCCEEDED',code:'SUCCESS',message:null,createdAt:'',expiresAt:''}
  vi.mocked(catalogApi.writeProperties).mockResolvedValue(reply);vi.mocked(catalogApi.invoke).mockResolvedValue(reply)
})
afterEach(()=>{dispose?.();vi.useRealTimers()})
it('binds the entered ID and uses the device model version rather than the latest draft',async()=>{
  await mount();await bind('arbitrary-device-42')
  expect(catalogApi.detail).toHaveBeenCalledWith('/catalog/tenants/tenant-a','arbitrary-device-42',expect.any(AbortSignal))
  expect(state.resources?.()).toEqual([{tenantId:'tenant-a',deviceId:'arbitrary-device-42'}])
  expect(root.textContent).toContain('模型 v7');expect(root.textContent).not.toContain('draftOnly')
  expect(root.querySelector('[data-property="nurseryTemp"]')?.textContent).toContain('23.2')
  expect(root.querySelector('[aria-label="设置 nurseryTemp"]')).toBeNull()
})
it('refreshes MQTT-projected state on subscription ready and shadow invalidation, and marks disconnects stale',async()=>{
  await mount();await bind();state.receive?.({type:'resync'});await settle()
  expect(root.textContent).toContain('实时订阅已连接')
  vi.mocked(catalogApi.shadow).mockResolvedValue(snapshot(2,26.8));state.receive?.({type:'invalidate',kind:'shadow'});await settle()
  expect(root.querySelector('[data-property="nurseryTemp"]')?.textContent).toContain('26.8')
  setTab('events');await flush();expect(root.textContent).toContain('育儿房温度');expect(root.textContent).toContain('26.8°C')
  state.receive?.({type:'disconnected'});await flush();expect(root.textContent).toContain('显示最近快照');expect(root.textContent).toContain('状态未知')
})
it('sends typed property writes and operations without overwriting the last device report',async()=>{
  await mount();await bind();root.querySelector<HTMLButtonElement>('[aria-label="设置 infrared"]')!.click();await flush()
  const input=root.querySelector<HTMLSelectElement>('select')!;input.value='on';input.dispatchEvent(new Event('change',{bubbles:true}));await click('通过平台下发到设备');await settle()
  expect(catalogApi.writeProperties).toHaveBeenCalledWith('/catalog/tenants/tenant-a','user-entered-id',{infrared:'on'},expect.any(AbortSignal))
  expect(root.querySelector('[data-property="infrared"] .property-actual')?.textContent).toContain('自动')
  root.querySelector<HTMLButtonElement>('.platform-operation')!.click();await flush()
  const angle=root.querySelector<HTMLInputElement>('input[type="number"]')!;angle.value='100';angle.dispatchEvent(new Event('input',{bubbles:true}));await click('通过平台下发到设备')
  expect(catalogApi.invoke).not.toHaveBeenCalled();expect(angle.validity.rangeOverflow).toBe(true)
  angle.value='40';angle.dispatchEvent(new Event('input',{bubbles:true}));await click('通过平台下发到设备');await settle()
  expect(catalogApi.invoke).toHaveBeenCalledWith('/catalog/tenants/tenant-a','user-entered-id','move',{angle:40},expect.any(AbortSignal))
})
it('clears visible data and subscriptions immediately when the session is lost',async()=>{
  await mount();await bind();state.receive?.({type:'resync'});await settle()
  session.authenticated=false;await flush()
  expect(state.resources?.()).toEqual([]);expect(root.querySelector('[data-property="nurseryTemp"]')).toBeNull()
  expect(root.textContent).toContain('登录状态已变化')
})
it('preserves newer snapshots and exposes subscription authorization errors',async()=>{
  await mount();await bind();vi.mocked(catalogApi.shadow).mockResolvedValue(snapshot(3,27))
  state.receive?.({type:'resync'});await settle();vi.mocked(catalogApi.shadow).mockResolvedValue(snapshot(2,20))
  state.receive?.({type:'invalidate',kind:'shadow'});await settle()
  expect(root.querySelector('[data-property="nurseryTemp"]')?.textContent).toContain('27')
  state.receive?.({type:'denied'});await flush();expect(root.textContent).toContain('实时订阅被拒绝')
})
it('does not rebind another account\'s saved device or issue unauthenticated device requests',async()=>{
  localStorage.setItem('nebula-baby-platform-device-v1',JSON.stringify({accountId:'another-account',deviceId:'private-id'}))
  await mount();await settle();expect(catalogApi.detail).not.toHaveBeenCalled();expect(root.textContent).toContain('其他登录账号')
  session.authenticated=false;await bind();expect(catalogApi.detail).not.toHaveBeenCalled();expect(root.textContent).toContain('请先登录平台账号')
})
it('drops late snapshots after switching the bound device',async()=>{
  await mount();await bind('first')
  let resolve!:(value:DeviceShadow)=>void
  vi.mocked(catalogApi.shadow).mockImplementationOnce(()=>new Promise(r=>resolve=r))
  state.receive?.({type:'invalidate',kind:'shadow'});await settle()
  setTab('profile');await flush();await click('更换绑定设备')
  const input=root.querySelector<HTMLInputElement>('#baby-platform-id')!;input.value='second';input.dispatchEvent(new Event('input',{bubbles:true}));input.closest('form')!.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await flush()
  resolve(snapshot(99,99));await settle();setTab('home');await flush()
  expect(state.resources?.()).toEqual([{tenantId:'tenant-a',deviceId:'second'}]);expect(root.textContent).not.toContain('99°C')
  expect(root.querySelector('[data-property="nurseryTemp"]')?.textContent).toContain('23.2')
})
