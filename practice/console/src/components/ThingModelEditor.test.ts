// @vitest-environment happy-dom
import {afterEach,expect,it} from 'vitest'
import {createApp,defineComponent,h,nextTick,ref} from 'vue'
import ElementPlus from 'element-plus'
import Editor from './ThingModelEditor.vue'
let dispose:()=>void
async function settle(){for(let i=0;i<8;i++)await nextTick()}
afterEach(()=>{dispose?.();document.body.innerHTML=''})
it('preserves names and rejects duplicate enum codes before applying a visual edit',async()=>{
 const model=ref(JSON.stringify({properties:{mode:{id:'mode',name:'工作模式',description:'设备模式',access:'READ_WRITE',schema:{type:'ENUM',enumValues:{heat:'加热',warm:'保温'}}}},events:{},operations:{}}))
 const root=document.createElement('div');document.body.appendChild(root)
 const app=createApp(defineComponent(()=>()=>h(Editor,{modelValue:model.value,'onUpdate:modelValue':(v:string)=>model.value=v})))
 app.use(ElementPlus);app.mount(root);dispose=()=>{app.unmount();root.remove()};await settle()
 expect(root.textContent).toContain('工作模式');expect(root.textContent).toContain('heat = 加热')
 Array.from(root.querySelectorAll('button')).find(b=>b.textContent?.trim()==='编辑')!.click();await settle()
 const dialog=document.body.querySelector('.el-dialog')!
 const code=dialog.querySelector<HTMLInputElement>('[aria-label="枚举值 2"]')!
 code.value='heat';code.dispatchEvent(new Event('input',{bubbles:true}));await settle();
 dialog.querySelector('form')!.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await settle()
 expect(dialog.textContent).toContain('枚举值不能重复')
 expect(JSON.parse(model.value).properties.mode.schema.enumValues).toEqual({heat:'加热',warm:'保温'})
 code.value='warm';code.dispatchEvent(new Event('input',{bubbles:true}));await settle()
 dialog.querySelector('form')!.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await settle()
 Array.from(dialog.querySelectorAll('button')).find(b=>b.textContent?.trim()==='添加选项')!.click();await settle()
 expect(dialog.querySelector('[aria-label="枚举值 3"]')).toBeTruthy()
 dialog.querySelector<HTMLButtonElement>('[aria-label="删除枚举选项 3"]')!.click();await settle()
 expect(dialog.querySelector('[aria-label="枚举值 3"]')).toBeNull()
 expect(JSON.parse(model.value).properties.mode.name).toBe('工作模式')
 expect(JSON.parse(model.value).properties.mode.schema.enumValues).toEqual({heat:'加热',warm:'保温'})
})
it('edits array element enum options and retains array constraints',async()=>{
 const model=ref(JSON.stringify({properties:{modes:{id:'modes',schema:{type:'ARRAY',minItems:1,maxItems:3,items:{type:'ENUM',enumValues:{heat:'加热'}}},access:'READ_WRITE'}},events:{},operations:{}}))
 const root=document.createElement('div');document.body.appendChild(root)
 const app=createApp(defineComponent(()=>()=>h(Editor,{modelValue:model.value,'onUpdate:modelValue':(v:string)=>model.value=v})))
 app.use(ElementPlus);app.mount(root);dispose=()=>{app.unmount();root.remove()};await settle()
 Array.from(root.querySelectorAll('button')).find(b=>b.textContent?.trim()==='编辑')!.click();await settle()
 const dialog=document.body.querySelector('.el-dialog')!
 expect(dialog.textContent).toContain('数组元素定义')
 const name=dialog.querySelector<HTMLInputElement>('[aria-label="枚举名称 1"]')!
 name.value='加热模式';name.dispatchEvent(new Event('input',{bubbles:true}));await settle()
 dialog.querySelector('form')!.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await settle()
 const schema=JSON.parse(model.value).properties.modes.schema
 expect(schema.minItems).toBe(1);expect(schema.maxItems).toBe(3)
 expect(schema.items.enumValues).toEqual({heat:'加热模式'})
})