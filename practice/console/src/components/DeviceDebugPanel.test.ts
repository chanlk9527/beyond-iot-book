// @vitest-environment happy-dom
import {afterEach,expect,it,vi} from 'vitest'
import {createApp,defineComponent,h,nextTick,reactive} from 'vue'
import DeviceDebugPanel from './DeviceDebugPanel.vue'
import {defaultProductConfiguration,type DeviceDetail} from '../api/catalog'
const mock=vi.hoisted(()=>({mounts:0,unmounts:0,dispatched:undefined as any}))
vi.mock('./DeviceCommandPanel.vue',()=>({default:defineComponent({emits:['dispatched'],setup:(_,c)=>{mock.dispatched=(id:string)=>c.emit('dispatched',id);return()=>h('div','平台命令表单')}})}))
vi.mock('./DeviceSimulatorPanel.vue',async()=>{const {onUnmounted}=await import('vue');return {default:defineComponent({props:['focusMessageId'],emits:['connection-state'],setup:(p,c)=>{mock.mounts++;onUnmounted(()=>mock.unmounts++);return()=>h('button',{'data-simulator':p.focusMessageId,onClick:()=>c.emit('connection-state','connected')},'模拟连接')}})}})
const detail:DeviceDetail={device:{id:'d',tenantId:null,productId:'p',productName:'P',code:'d',name:'D',modelVersion:'v1',lifecycle:'ACTIVE',credentialVersion:1,revision:0},product:{id:'p',tenantId:null,code:'p',name:'P',description:'',enabled:true,latestVersion:'v1',revision:0,draft:{properties:{},events:{},operations:{}},configuration:defaultProductConfiguration()},model:{version:'v1',model:{properties:{},events:{},operations:{}},publishedAt:'',deviceCount:1}}
let close:()=>void
async function settle(){await new Promise(resolve=>setTimeout(resolve,0));for(let i=0;i<8;i++)await nextTick()}
function mount(canSimulate=true){mock.mounts=0;mock.unmounts=0;const props=reactive({scope:'/catalog/platform',detail,writable:true,canSimulate,presence:'OFFLINE'});const root=document.createElement('div');document.body.appendChild(root);const app=createApp({setup:()=>()=>h(DeviceDebugPanel,props)});app.component('el-button',defineComponent({setup:(_,c)=>()=>h('button',c.attrs,c.slots.default?.())}));app.mount(root);close=()=>{app.unmount();root.remove()};return {root,props}}
afterEach(()=>close?.())
it('opens a single simulator beside controls, preserves it when collapsed and focuses dispatched requests',async()=>{
 const {root}=mount();expect(root.textContent).toContain('平台命令表单');expect(mock.mounts).toBe(0)
 root.querySelector<HTMLButtonElement>('.debug-actions button')!.click();await settle();expect(mock.mounts).toBe(1);expect(root.querySelector('.with-simulator')).not.toBeNull()
 root.querySelector<HTMLButtonElement>('[data-simulator]')!.click();await settle();expect(root.textContent).toContain('模拟设备：已连接')
 root.querySelector<HTMLButtonElement>('.debug-actions button')!.click();await settle();expect(root.querySelector<HTMLElement>('aside')!.style.display).toBe('none');expect(mock.unmounts).toBe(0)
 mock.dispatched('request-42');await settle();expect(root.querySelector<HTMLElement>('aside')!.style.display).not.toBe('none');expect(root.querySelector('[data-simulator]')!.getAttribute('data-simulator')).toBe('request-42');expect(mock.mounts).toBe(1)
 close();expect(mock.unmounts).toBe(1)
})
it('keeps platform controls available without credential access and disposes a revoked simulator',async()=>{
 const {root,props}=mount(false);expect(root.textContent).toContain('平台命令表单');expect(root.querySelector('.debug-actions button')).toBeNull()
 props.canSimulate=true;await settle();root.querySelector<HTMLButtonElement>('.debug-actions button')!.click();await settle();expect(mock.mounts).toBe(1)
 props.canSimulate=false;await settle();expect(mock.unmounts).toBe(1);expect(root.querySelector('aside')).toBeNull();expect(root.querySelector('.with-simulator')).toBeNull()
})
