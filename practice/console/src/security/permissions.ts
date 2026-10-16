import type { Account } from '../api/management'
export function hasPermission(account: Account | null, permission: string): boolean {
  return account?.permissions?.includes(permission) === true
}
export function hasAnyPermission(account: Account | null, permissions: readonly string[]): boolean {
  return permissions.some(permission => hasPermission(account, permission))
}
