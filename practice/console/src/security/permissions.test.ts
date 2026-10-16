import { describe, expect, it } from 'vitest'
import { hasPermission, hasAnyPermission } from './permissions'
describe('functional permission checks', () => {
  it('denies missing identity and missing permission lists', () => {
    expect(hasPermission(null, 'tenant:write')).toBe(false)
    expect(hasPermission({ id: 'a', username: 'admin', platformAdmin: true }, 'tenant:write')).toBe(false)
  })
  it('uses server returned permissions instead of username or wildcard expansion', () => {
    const account = { id: 'a', username: 'admin', permissions: ['tenant:read'] }
    expect(hasPermission(account, 'tenant:read')).toBe(true)
    expect(hasPermission(account, 'tenant:write')).toBe(false)
    expect(hasAnyPermission(account, ['iam:user:read', 'tenant:read'])).toBe(true)
    expect(hasPermission({ ...account, permissions: ['*'] }, 'tenant:write')).toBe(false)
  })
})