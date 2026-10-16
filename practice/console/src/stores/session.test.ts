import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { cancelRequests, setUnauthorizedHandler } from '../api/management'
import { useSessionStore } from './session'

const fetcher = vi.fn<typeof fetch>()
const json = (value: unknown) => new Response(JSON.stringify(value), { status: 200 })
const account = { id: 'a', username: 'alice', tenantId: 'tenant-a' }
const tenant = { id: 'tenant-a', name: 'A', role: 'OWNER' }

beforeEach(() => {
  setActivePinia(createPinia())
  cancelRequests()
  fetcher.mockReset()
  vi.stubGlobal('fetch', fetcher)
  setUnauthorizedHandler(() => useSessionStore().clear())
})
afterEach(() => { vi.unstubAllGlobals(); cancelRequests() })

describe('session lifecycle', () => {
  it('restores the account from the server, without trusting browser storage', async () => {
    fetcher.mockResolvedValueOnce(json(account))
    const store = useSessionStore()
    await store.ensureSession()
    expect(store.authenticated).toBe(true)
    expect(store.account).toEqual(account)
    await store.ensureSession()
    expect(fetcher).toHaveBeenCalledOnce()
  })
  it('keeps the console mounted while navigation refreshes account grants', async () => {
    let resolve!: (response: Response) => void
    fetcher.mockImplementationOnce(() => new Promise<Response>(done => { resolve = done }))
    const store = useSessionStore()
    store.account = account
    store.status = 'ready'
    const pending = store.ensureSession(true)
    expect(store.status).toBe('ready')
    expect(store.authenticated).toBe(true)
    expect(store.account).toEqual(account)
    const updated = { ...account, permissions: ['page:products'] }
    resolve(json(updated))
    await pending
    expect(store.account).toEqual(updated)
    expect(store.status).toBe('ready')
  })
  it('treats an expired cookie as anonymous and removes old account and tenant data', async () => {
    const store = useSessionStore()
    store.account = account
    store.tenant = tenant
    fetcher.mockResolvedValueOnce(new Response(null, { status: 401 }))
    await store.ensureSession(true)
    expect(store.status).toBe('anonymous')
    expect(store.account).toBeNull()
    expect(store.tenant).toBeNull()
  })
  it('keeps an unavailable backend distinct from an anonymous session and allows retry', async () => {
    const store = useSessionStore()
    fetcher.mockRejectedValueOnce(new TypeError('Failed to fetch'))
    await store.ensureSession()
    expect(store.status).toBe('error')
    fetcher.mockResolvedValueOnce(json(account))
    await store.ensureSession(true)
    expect(store.authenticated).toBe(true)
    expect(store.error).toBe('')
  })
  it('ignores a delayed account response after the session was cleared', async () => {
    let resolve!: (response: Response) => void
    fetcher.mockImplementationOnce(() => new Promise<Response>(done => { resolve = done }))
    const store = useSessionStore()
    const pending = store.ensureSession()
    store.clear()
    resolve(json(account))
    await pending
    expect(store.status).toBe('anonymous')
    expect(store.account).toBeNull()
  })
  it('does not restore tenant data from an in-flight request after logout', async () => {
    let resolve!: (response: Response) => void
    fetcher.mockImplementationOnce(() => new Promise<Response>(done => { resolve = done }))
    const store = useSessionStore()
    store.account = account
    const pending = store.refreshTenant()
    store.clear()
    resolve(json(tenant))
    await pending
    expect(store.tenant).toBeNull()
    expect(store.tenantStatus).toBe('idle')
  })
  it('ignores an older tenant refresh that finishes after a newer refresh', async () => {
    let resolve!: (response: Response) => void
    fetcher.mockImplementationOnce(() => new Promise<Response>(done => { resolve = done }))
      .mockResolvedValueOnce(json(null))
    const store = useSessionStore()
    store.account = account
    const older = store.refreshTenant()
    await store.refreshTenant()
    resolve(json(tenant))
    await older
    expect(store.tenant).toBeNull()
    expect(store.tenantStatus).toBe('ready')
  })
  it('keeps the session visible when logout fails, so the UI does not claim success', async () => {
    const store = useSessionStore()
    store.account = account
    store.status = 'ready'
    fetcher.mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 't' }))
      .mockResolvedValueOnce(new Response(null, { status: 503 }))
    await expect(store.logout()).rejects.toThrow()
    expect(store.authenticated).toBe(true)
  })
  it('clears account and tenant data after the server accepts logout', async () => {
    const store = useSessionStore()
    store.account = account
    store.status = 'ready'
    store.tenant = tenant
    fetcher.mockResolvedValueOnce(json({ headerName: 'X-CSRF-TOKEN', token: 't' }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
    await store.logout()
    expect(store.authenticated).toBe(false)
    expect(store.tenant).toBeNull()
  })
})

describe('fixed tenant binding',()=>{
  it('loads only the tenant bound to the authenticated account',async()=>{
    const store=useSessionStore();store.account=account
    fetcher.mockResolvedValueOnce(json(tenant))
    await store.refreshTenant()
    expect(fetcher.mock.calls[0]?.[0]).toBe('/api/management/tenants/tenant-a')
    expect(store.tenant).toEqual(tenant)
  })
  it('does not fetch a tenant list for an independent platform administrator',async()=>{
    const store=useSessionStore();store.account={id:'root',username:'root',platformAdmin:true,tenantId:null}
    await store.refreshTenant()
    expect(store.tenant).toBeNull();expect(fetcher).not.toHaveBeenCalled()
  })
})