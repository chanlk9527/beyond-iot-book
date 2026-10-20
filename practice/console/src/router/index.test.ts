import { beforeEach, describe, expect, it, vi } from 'vitest'

const state = vi.hoisted(() => ({
  guard: undefined as undefined | ((to: any) => Promise<unknown>),
  routes: [] as any[],
  session: {
    authenticated: true, status: 'ready',
    account: { platformAdmin: false, tenantId: 'ta', permissions: [] as string[] },
    ensureSession: vi.fn(), can: (code: string): boolean => state.session.account.permissions.includes(code),
    tenant: {role:'VIEWER'}, refreshTenant: vi.fn(),
  },
}))
vi.mock('../stores/session', () => ({ useSessionStore: () => state.session }))
vi.mock('vue-router', () => ({
  createWebHistory: vi.fn(),
  createRouter: (options: any) => {
    function flatten(routes: any[], parent = ''): any[] {
      return routes.flatMap(route => {
        const path = route.path.startsWith('/') ? route.path.slice(1) : [parent,route.path].filter(Boolean).join('/')
        return [{...route,path},...flatten(route.children || [],path)]
      })
    }
    state.routes = flatten(options.routes)
    return { beforeEach: (guard: typeof state.guard) => { state.guard = guard }, afterEach: vi.fn() }
  },
}))
import './index'

function visit(path: string, params: Record<string, string> = {}) {
  const route = state.routes.find(route => route.path === path && route.name)
  return state.guard!({ name: route.name, meta: route.meta || {}, params, query: {} })
}
describe('page access guard', () => {
  beforeEach(() => {
    state.session.authenticated = true; state.session.status = 'ready'
    state.session.account = { platformAdmin: false, tenantId: 'ta', permissions: [] }
    state.session.ensureSession.mockReset()
    state.session.tenant.role='VIEWER'; state.session.refreshTenant.mockReset()
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
  it('opens the developer panel only after verifying the tenant administrator role', async () => {
    expect(await visit('developer/apps')).toEqual({name:'overview'})
    state.session.refreshTenant.mockImplementationOnce(async()=>{state.session.tenant.role='ADMIN'})
    expect(await visit('developer/apps')).toBeUndefined()
    expect(state.session.refreshTenant).toHaveBeenCalled()
  })
  it('restricts access node observations to platform administrators', async () => {
    expect(await visit('platform/access-nodes')).toEqual({name:'overview'})
    state.session.account.platformAdmin=true
    expect(await visit('platform/access-nodes')).toBeUndefined()
  })
  it('restricts event bus observations to platform administrators', async () => {
    expect(await visit('platform/eventbus')).toEqual({name:'overview'})
    state.session.account.platformAdmin=true
    expect(await visit('platform/eventbus')).toBeUndefined()
  })
  it('restricts platform reviews and redirects platform administrators to their own panel', async () => {
    expect(await visit('platform/openapi')).toEqual({name:'overview'})
    state.session.account.platformAdmin=true
    expect(await visit('platform/openapi')).toBeUndefined()
    expect(await visit('developer/apps')).toEqual({name:'openapi-management'})
  })
  it('allows anonymous public documentation without contacting management APIs', async () => {
    state.session.authenticated=false; state.session.status='anonymous'
    for (const path of ['developer','developer/docs/:slug?','developer/examples']) expect(await visit(path)).toBe(true)
    expect(state.session.ensureSession).not.toHaveBeenCalled()
  })
  it('keeps public documentation available when the backend is unavailable', async () => {
    state.session.status='error'
    expect(await visit('developer/docs/:slug?')).toBe(true)
    expect(state.session.ensureSession).not.toHaveBeenCalled()
  })
  it('remembers the application destination while requiring login', async () => {
    state.session.authenticated=false;state.session.status='anonymous'
    expect(await visit('developer/apps')).toEqual({name:'login',query:{redirect:'/developer/apps'}})
  })
})
