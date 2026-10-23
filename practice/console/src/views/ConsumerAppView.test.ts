// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, defineComponent, h, nextTick } from 'vue'
import { createPinia } from 'pinia'
import ConsumerAppView from './ConsumerAppView.vue'

let root: HTMLDivElement, dispose: () => void
beforeEach(() => {
  localStorage.clear()
  vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('Demo must work offline'))))
})
afterEach(() => { dispose?.(); vi.unstubAllGlobals() })
async function mount() {
  root = document.createElement('div'); document.body.append(root)
  const app = createApp(ConsumerAppView)
  app.use(createPinia())
  app.component('RouterLink', defineComponent({ setup: (_, ctx) => () => h('a', ctx.slots.default?.()) }))
  app.component('el-dialog', defineComponent({ props:['modelValue'], setup: (props, ctx) => () => props.modelValue ? h('section', ctx.slots.default?.()) : null }))
  app.mount(root); await nextTick(); dispose = () => { app.unmount(); root.remove() }
  await click('查看界面演示 →')
}
async function click(text: string) {
  const button = [...root.querySelectorAll('button')].find(b => b.textContent?.trim() === text)
  expect(button, `button ${text}`).toBeTruthy(); button!.click(); await nextTick()
}
it('runs the monitor and event experience without management or camera services', async () => {
  await mount(); await click('看宝宝')
  expect(root.textContent).toContain('插画演示 · 非实时视频')
  await click('夜视模式')
  expect(root.querySelector('.nursery-scene.night')).toBeTruthy()
  await click('暂停预览')
  expect(root.querySelector('.nursery-scene.moving')).toBeNull()
  await click('消息'); await click('模拟一条哭声事件')
  expect(root.querySelectorAll('.event-card')).toHaveLength(4)
  await click('声音')
  expect(root.querySelectorAll('.event-card')).toHaveLength(2)
  expect(fetch).not.toHaveBeenCalled()
})
it('persists reminder preferences and restores a locally added virtual device', async () => {
  await mount(); await click('我的')
  const checkbox = root.querySelector<HTMLInputElement>('[aria-label="哭声提醒"]')!
  checkbox.click(); await nextTick()
  await click('首页')
  root.querySelector<HTMLButtonElement>('[aria-label="添加设备"]')!.click(); await nextTick()
  const name = root.querySelector<HTMLInputElement>('input[maxlength="24"]')!
  name.value = '午睡房'; name.dispatchEvent(new Event('input', {bubbles:true}))
  root.querySelector('form')!.dispatchEvent(new Event('submit',{cancelable:true,bubbles:true})); await nextTick()
  expect(root.querySelector('.extra-device')?.textContent).toContain('午睡房')
  dispose(); await mount()
  expect(root.querySelector('.extra-device')?.textContent).toContain('午睡房')
  await click('我的')
  expect(root.querySelector<HTMLInputElement>('[aria-label="哭声提醒"]')!.checked).toBe(false)
  expect(fetch).not.toHaveBeenCalled()
})
it('recovers from invalid local storage', async () => {
  localStorage.setItem('nebula-baby-demo-v1', '{invalid')
  await mount(); expect(root.textContent).toContain('宝宝的房间')
  await click('我的'); expect(root.querySelector<HTMLInputElement>('[aria-label="哭声提醒"]')!.checked).toBe(true)
})
