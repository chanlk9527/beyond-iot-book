// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from 'vitest'
import { historyApi } from './history'
import { defaultDataPolicy } from '../components/dataPolicy'
afterEach(() => vi.unstubAllGlobals())
it('uses the real scoped management endpoint and preserves cursor query encoding', async () => {
  const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ items: [], nextCursor: null }), { status: 200 }))
  vi.stubGlobal('fetch', fetcher)
  await historyApi.query('/catalog/tenants/tenant', 'device', 'EVENT', 'fault', '2026-10-01T00:00:00Z', '2026-10-02T00:00:00Z', 'abc=+/def', new AbortController().signal)
  const url = fetcher.mock.calls[0][0] as string
  expect(url).toContain('/api/management/catalog/tenants/tenant/devices/device/history/EVENT/fault?')
  expect(new URL(url, 'http://localhost').searchParams.get('cursor')).toBe('abc=+/def')
})
it('saves policy with independent revision and CSRF rather than publishing a model', async () => {
  const fetcher = vi.fn()
    .mockResolvedValueOnce(new Response(JSON.stringify({ headerName: 'X-CSRF', token: 'csrf' }), { status: 200 }))
    .mockResolvedValueOnce(new Response(JSON.stringify({ policy: defaultDataPolicy(), revision: 4 }), { status: 200 }))
  vi.stubGlobal('fetch', fetcher)
  await historyApi.save('/catalog/platform', 'p', defaultDataPolicy(), 3, new AbortController().signal)
  expect(fetcher.mock.calls[0][0]).toBe('/api/management/auth/csrf')
  expect(fetcher.mock.calls[1][0]).toBe('/api/management/catalog/platform/products/p/data-policy')
  expect(fetcher.mock.calls[1][1]).toMatchObject({ method: 'PUT', headers: { 'X-CSRF': 'csrf' } })
  expect(JSON.parse(fetcher.mock.calls[1][1].body)).toEqual({ revision: 3, policy: defaultDataPolicy() })
})
