// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'

const session = vi.hoisted(() => ({
  authenticated: true,
  status: 'ready',
  account: { platformAdmin: true },
  ensureSession: vi.fn(),
  can: () => true,
}))
vi.mock('../stores/session', () => ({ useSessionStore: () => session }))

// Exercise the real matcher and lazy page imports; the guard unit tests mock
// vue-router and cannot detect a valid URL falling through to the 404 page.
import router from './index'

describe('console navigation routes', () => {
  beforeEach(() => {
    session.authenticated = true
    session.account.platformAdmin = true
    session.ensureSession.mockClear()
  })

  it.each([
    ['/developer', 'developer-home', '开发者中心'],
    ['/platform/openapi', 'openapi-management', '开放平台管理'],
    ['/platform/access-nodes', 'access-nodes', '设备接入'],
  ])('opens %s with its page component', async (path, name, title) => {
    await router.push(path)
    expect(router.currentRoute.value.name).toBe(name)
    expect(router.currentRoute.value.meta.title).toBe(title)
    expect(router.currentRoute.value.matched.at(-1)?.components?.default).toBeTruthy()
    expect(document.title).toBe(`${title} · 星云 IoT`)
  })

  it('opens the developer center anonymously', async () => {
    session.authenticated = false
    await router.push('/developer')
    expect(router.currentRoute.value.name).toBe('developer-home')
    expect(session.ensureSession).not.toHaveBeenCalled()
  })

  it.each(['/platform/openapi', '/platform/access-nodes'])('protects %s from tenant accounts', async path => {
    session.account.platformAdmin = false
    await router.push(path)
    expect(router.currentRoute.value.name).toBe('overview')
  })

  it('still shows the missing-page view for unknown URLs', async () => {
    await router.push('/page-that-does-not-exist')
    expect(router.currentRoute.value.meta.title).toBe('页面不存在')
  })
})
