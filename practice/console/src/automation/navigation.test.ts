// @vitest-environment happy-dom
import { beforeEach, expect, it, vi } from 'vitest'
const session = vi.hoisted(() => ({ authenticated: true, status: 'ready', account: { platformAdmin: true }, ensureSession: vi.fn(), can: () => true }))
vi.mock('../stores/session', () => ({ useSessionStore: () => session }))
import router from '../router'
beforeEach(() => { session.authenticated = true; session.account.platformAdmin = true })
it.each(['flows', 'scenes', 'connections', 'notifications', 'runs'])('opens the %s preview only for platform administrators', async section => {
  await router.push(`/automation/${section}`)
  expect(router.currentRoute.value.meta.automationSection).toBe(section)
  expect(router.currentRoute.value.meta.platformAdminOnly).toBe(true)
  session.account.platformAdmin = false
  await router.push('/overview')
  await router.push(`/automation/${section}`)
  expect(router.currentRoute.value.name).toBe('overview')
})
it('preserves the existing login boundary for preview routes', async () => {
  session.authenticated = false
  await router.push('/automation/flows')
  expect(router.currentRoute.value.name).toBe('login')
})
