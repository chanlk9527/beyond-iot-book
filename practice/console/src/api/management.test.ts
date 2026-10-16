import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, cancelRequests, currentAccount, getTenant, setUnauthorizedHandler, managementMutation, signIn, signOut } from './management'

const fetcher = vi.fn<typeof fetch>()
const json = (value: unknown) => new Response(JSON.stringify(value), { status: 200 })
const csrf = (token: string) => json({ headerName: 'X-CSRF-TOKEN', token })

beforeEach(() => {
  cancelRequests()
  setUnauthorizedHandler(() => {})
  fetcher.mockReset()
  vi.stubGlobal('fetch', fetcher)
})
afterEach(() => { vi.unstubAllGlobals(); cancelRequests() })

describe('management session transport', () => {
  it('uses a same-origin cookie and the server-provided CSRF token for a form login', async () => {
    fetcher.mockResolvedValueOnce(csrf('before-login')).mockResolvedValueOnce(new Response(null, { status: 204 }))
    await signIn('user+name', 'a&b=秘密')
    expect(fetcher.mock.calls[0]?.[0]).toBe('/api/management/auth/csrf')
    const [url, options] = fetcher.mock.calls[1]!
    expect(url).toBe('/api/management/auth/login')
    expect(options?.credentials).toBe('same-origin')
    expect(new Headers(options?.headers).get('X-CSRF-TOKEN')).toBe('before-login')
    expect((options?.body as URLSearchParams).get('password')).toBe('a&b=秘密')
    expect((options?.body as URLSearchParams).get('username')).toBe('user+name')
  })
  it('fetches a new CSRF token for logout after login has rotated the token', async () => {
    fetcher.mockResolvedValueOnce(csrf('old')).mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(csrf('rotated')).mockResolvedValueOnce(new Response(null, { status: 204 }))
    await signIn('alice', 'secret')
    await signOut()
    const [url, options] = fetcher.mock.calls[3]!
    expect(url).toBe('/api/management/auth/logout')
    expect(new Headers(options?.headers).get('X-CSRF-TOKEN')).toBe('rotated')
  })
  it('notifies the session owner when a protected API returns 401', async () => {
    const expired = vi.fn()
    setUnauthorizedHandler(expired)
    fetcher.mockResolvedValue(new Response(null, { status: 401 }))
    await expect(currentAccount()).rejects.toBeInstanceOf(ApiError)
    expect(expired).toHaveBeenCalledOnce()
  })
  it('does not trigger a global expiration redirect for a rejected login', async () => {
    const expired = vi.fn()
    setUnauthorizedHandler(expired)
    fetcher.mockResolvedValueOnce(csrf('token')).mockResolvedValueOnce(new Response(null, { status: 401 }))
    await expect(signIn('alice', 'wrong')).rejects.toMatchObject({ status: 401 })
    expect(expired).not.toHaveBeenCalled()
  })
  it('discards an old tenant response after the request scope is cancelled', async () => {
    let resolve!: (value: Response) => void
    fetcher.mockImplementationOnce(() => new Promise<Response>(done => { resolve = done }))
    const request = getTenant('tenant-a')
    const assertion = expect(request).rejects.toMatchObject({ name: 'AbortError' })
    cancelRequests()
    resolve(json({ id: 'tenant-a', name: 'A', role: 'OWNER' }))
    await assertion
  })
  it('does not display internal server text to the user', async () => {
    fetcher.mockResolvedValue(new Response('secret database exception', { status: 500 }))
    await expect(currentAccount()).rejects.toThrow('服务暂时不可用')
  })
})

describe('tenant management mutations', () => {
  it('sends JSON with a fresh CSRF token and preserves role and revision values', async () => {
    fetcher.mockResolvedValueOnce(csrf('tenant-write')).mockResolvedValueOnce(json({ id: 't' }))
    await managementMutation('/platform/tenants/t', 'PUT', { name: '研发', enabled: false, revision: 3 })
    const [url, options] = fetcher.mock.calls[1]!
    expect(url).toBe('/api/management/platform/tenants/t')
    expect(options?.method).toBe('PUT')
    expect(new Headers(options?.headers).get('Content-Type')).toBe('application/json')
    expect(new Headers(options?.headers).get('X-CSRF-TOKEN')).toBe('tenant-write')
    expect(JSON.parse(String(options?.body))).toEqual({ name: '研发', enabled: false, revision: 3 })
  })
  it('does not retry a conflicting tenant write', async () => {
    fetcher.mockResolvedValueOnce(csrf('token')).mockResolvedValueOnce(new Response(null, { status: 409 }))
    await expect(managementMutation('/platform/tenants/t', 'PUT', {})).rejects.toMatchObject({ status: 409 })
    expect(fetcher).toHaveBeenCalledTimes(2)
  })
})