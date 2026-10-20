// @vitest-environment happy-dom
import {afterEach,expect,it} from 'vitest'
import {createApp,defineComponent,h,nextTick,ref} from 'vue'
import ElementPlus from 'element-plus'
import Input from './DeviceValueInput.vue'
import type {ShadowScalar} from '../api/catalog'
let dispose:()=>void
async function settle(){for(let i=0;i<8;i++)await nextTick()}
afterEach(()=>{dispose?.();document.body.innerHTML=''})
it('adds and removes typed array elements and enforces the count limit',async()=>{
 const value=ref<ShadowScalar>([])
 const root=document.createElement('div');document.body.appendChild(root)
 const app=createApp(defineComponent(()=>()=>h(Input,{modelValue:value.value,'onUpdate:modelValue':(v:ShadowScalar)=>value.value=v,schema:{type:'ARRAY',items:{type:'INTEGER',minimum:2},maxItems:1},disabled:false,label:'采样值'})))
 app.use(ElementPlus);app.mount(root);dispose=()=>{app.unmount();root.remove()};await settle()
 const add=()=>Array.from(root.querySelectorAll('button')).find(b=>b.textContent?.trim()==='添加元素')!
 add().click();await settle();expect(value.value).toEqual([2]);expect(add().disabled).toBe(true)
 root.querySelector<HTMLButtonElement>('[aria-label="采样值 删除第 1 项"]')!.click();await settle()
 expect(value.value).toEqual([]);expect(add().disabled).toBe(false)
})