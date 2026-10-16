import { beforeEach, describe, expect, it, vi } from 'vitest'

const state = vi.hoisted(() => ({
  guard: undefined as undefined | ((to: any) => Promise<unknown>),
  routes: [] as any[],
  session: {
    authenticated: true, status: 'ready',
    account: { platformAdmin: false, tenantId: 'ta', permissions: [] as string[] },
    ensureSession: vi.fn(), can: (code: string): boolean => state.session.account.permissions.includes(code),
  },
}))
vi.mock('../stores/session', () => ({ useSessionStore: () => state.session }))
vi.mock('vue-router', () => ({
  createWebHistory: vi.fn(),
  createRouter: (options: any) => {
    state.routes = options.routes[1].children
    return { beforeEach: (guard: typeof state.guard) => { state.guard = guard }, afterEach: vi.fn() }
  },
}))
import './index'

function visit(path: string, params: Record<string, string> = {}) {
  const route = state.routes.find(route => route.path === path)
  return state.guard!({ name: route.name, meta: route.meta || {}, params })
}
describe('page access guard', () => {
  beforeEach(() => {
    state.session.authenticated = true; state.session.status = 'ready'
    state.session.account = { platformAdmin: false, tenantId: 'ta', permissions: [] }
    state.session.ensureSession.mockReset()
  })
  it('does not infer page access from shared API permissions', async () => {
    state.session.account.permissions = ['product:read', 'iam:role:read']
    for (const path of ['catalog/tenants/:tenantId/products', 'catalog/tenants/:tenantId/categories', 'access/roles', 'access/menus']) {
      expect(await visit(path, { tenantId: 'ta' })).toEqual({ name: 'overview' })
    }
  })
  it('separates product pages and protects direct detail links', async () => {
    state.session.account.permissions = ['page:categories']
    expect(await visit('catalog/tenants/:tenantId/categories', { tenantId: 'ta' })).toBeUndefined()
    expect(await visit('catalog/tenants/:tenantId/products/:id', { tenantId: 'ta' })).toEqual({ name: 'overview' })
    expect(await visit('catalog')).toBe('/catalog/tenants/ta/categories')
  })
  it('opens only granted access tabs and picks an available landing page', async () => {
    state.session.account.permissions = ['page:menus']
    expect(await visit('access')).toBe('/access/menus')
    expect(await visit('access/menus')).toBeUndefined()
    expect(await visit('access/users')).toEqual({ name: 'overview' })
    expect(await visit('access/roles')).toEqual({ name: 'overview' })
  })
  it('keeps tenant and platform scope restrictions with a granted page', async () => {
    state.session.account.permissions = ['page:products']
    expect(await visit('catalog/platform/products')).toEqual({ name: 'catalog' })
    expect(await visit('catalog/tenants/:tenantId/products', { tenantId: 'tb' })).toEqual({ name: 'catalog' })
  })
  it('refreshes grants before navigation and blocks a revoked page', async () => {
    state.session.account.permissions = ['page:products']
    state.session.ensureSession.mockImplementationOnce(async () => { state.session.account.permissions = [] })
    expect(await visit('catalog/tenants/:tenantId/products', { tenantId: 'ta' })).toEqual({ name: 'overview' })
    expect(state.session.ensureSession).toHaveBeenCalledWith(true)
  })
  it('does not open a protected page when grant refresh fails', async () => {
    state.session.ensureSession.mockImplementationOnce(async () => { state.session.status = 'error' })
    expect(await visit('access/menus')).toEqual({ name: 'overview' })
  })
})
