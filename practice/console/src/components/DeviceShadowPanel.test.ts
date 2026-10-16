// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick } from 'vue'
import DeviceShadowPanel from './DeviceShadowPanel.vue'
import { catalogApi, type Device, type DeviceShadow } from '../api/catalog'

vi.mock('../api/catalog',async original=>({...await original<typeof import('../api/catalog')>(),catalogApi:{shadow:vi.fn(),desired:vi.fn()}}))
vi.mock('element-plus',()=>({ElMessage:{success:vi.fn(),info:vi.fn()}}))
let dispose:()=>void
const snapshot=(version=2):DeviceShadow=>({version,modelVersion:'v1',updatedAt:null,reported:{},desired:{power:{value:true,updatedAt:'2026-10-16T00:00:00Z'}},delta:{power:true}})
async function settle(){for(let i=0;i<8;i++)await nextTick()}
function mount(writable=true) {
 const root=document.createElement('div');document.body.appendChild(root)
 const app=createApp(DeviceShadowPanel,{scope:'/catalog/platform',device:{id:'d1',tenantId:'ta',lifecycle:'ACTIVE'} as Device,
  writable,model:{properties:{power:{id:'power',access:'READ_WRITE',schema:{type:'BOOLEAN'}}},events:{},operations:{}}})
 for(const tag of ['button','tag','skeleton','select','option','input','input-number','table-column'])app.component('el-'+tag,defineComponent({setup:(_,ctx)=>()=>h(tag,ctx.attrs,ctx.slots.default?.())}))
 app.component('el-checkbox',defineComponent({props:['modelValue'],emits:['update:modelValue'],setup:(props,ctx)=>()=>h('input',{...ctx.attrs,type:'checkbox',checked:props.modelValue,onChange:(event:Event)=>ctx.emit('update:modelValue',(event.target as HTMLInputElement).checked)})}))
 app.component('el-alert',defineComponent({props:['title'],setup:p=>()=>h('p',p.title)}))
 app.component('el-table',defineComponent(()=>()=>null))
 app.mount(root);dispose=()=>{app.unmount();root.remove()};return root
}
const button=(root:HTMLElement,label:string)=>[...root.querySelectorAll('button')].find(n=>n.textContent===label)!
beforeEach(()=>{vi.clearAllMocks();vi.mocked(catalogApi.shadow).mockResolvedValue(snapshot());vi.mocked(catalogApi.desired).mockResolvedValue(snapshot(3))})
afterEach(()=>dispose?.())
it('saves the current shadow version to the actual resource tenant and supports clearing a target',async()=>{
 const root=mount();await settle()
 const checkbox=root.querySelector('input[type=checkbox]') as HTMLInputElement
 checkbox.checked=false;checkbox.dispatchEvent(new Event('change'));await settle()
 button(root,'保存期望状态').click();await settle()
 expect(catalogApi.desired).toHaveBeenCalledWith('/catalog/tenants/ta','d1',2,{power:null},expect.any(AbortSignal))
})
it('keeps viewers read only while still loading the shadow',async()=>{
 const root=mount(false);await settle()
 expect(catalogApi.shadow).toHaveBeenCalledWith('/catalog/platform','d1',expect.any(AbortSignal))
 expect(button(root,'保存期望状态')).toBeUndefined()
 expect(root.querySelector('input[type=checkbox]')).toBeNull()
})
it('does not retry an outdated write automatically and preserves the editor on conflict',async()=>{
 vi.mocked(catalogApi.desired).mockRejectedValue(new Error('Shadow changed; refresh before retrying'))
 const root=mount();await settle();button(root,'保存期望状态').click();await settle()
 expect(catalogApi.desired).toHaveBeenCalledTimes(1)
 expect(root.textContent).toContain('Shadow changed')
 expect(root.querySelector('input[type=checkbox]')).not.toBeNull()
})
it('aborts requests when leaving the shadow and shows a retry for storage failures',async()=>{
 vi.mocked(catalogApi.shadow).mockRejectedValue(new Error('Device shadow storage is unavailable'))
 const root=mount();await settle()
 expect(root.textContent).toContain('状态暂不可用')
 const signal=vi.mocked(catalogApi.shadow).mock.calls[0]![2]
 dispose()
 expect(signal.aborted).toBe(true)
})

