// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import App from './App.vue'
const state=vi.hoisted(()=>({status:'idle',error:'offline',ensureSession:vi.fn(),authenticated:false}))
vi.mock('./stores/session',()=>({useSessionStore:()=>state}))
let unmount:()=>void
async function mount(path:string,status:string) {
  state.status=status
  const router=createRouter({history:createMemoryHistory(),routes:[
    {path:'/developer',component:{template:'<p>公开开发资料</p>'},meta:{public:true}},
    {path:'/overview',component:{template:'<p>受保护的管理数据</p>'}},
  ]})
  await router.push(path);await router.isReady()
  const root=document.createElement('div');document.body.append(root)
  const app=createApp(App);app.use(router)
  app.component('el-config-provider',defineComponent({setup:(_,ctx)=>()=>h('div',ctx.slots.default?.())}))
  app.component('el-button',defineComponent({setup:(_,ctx)=>()=>h('button',ctx.slots.default?.())}))
  app.mount(root);await nextTick();unmount=()=>{app.unmount();root.remove()};return root
}
afterEach(()=>unmount?.())
it.each(['idle','error'])('shows public docs when management session state is %s',async status=>{
  const root=await mount('/developer',status)
  expect(root.textContent).toContain('公开开发资料')
  expect(root.textContent).not.toContain('正在连接')
})
it('keeps the loading gate on management pages',async()=>{
  const root=await mount('/overview','idle')
  expect(root.textContent).toContain('正在连接')
  expect(root.textContent).not.toContain('受保护的管理数据')
})
it('keeps the connection error gate on management pages',async()=>{
  const root=await mount('/overview','error')
  expect(root.textContent).toContain('暂时无法连接控制台')
  expect(root.textContent).not.toContain('受保护的管理数据')
})
