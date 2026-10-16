import { request, managementMutation } from './management'
export interface AccessUser { id: string; username: string; enabled: boolean; platformAdmin: boolean; revision: number; roleIds: string[]; tenantId: string | null }
export interface AccessRole { id: string; code: string; name: string; enabled: boolean; revision: number; permissions: string[] }
export interface Permission { code: string; name: string; group: string }
export interface Menu { code: string; name: string; group: string; path: string; permissions: string[]; description: string; sortOrder: number; revision: number }
export const accessApi = {
  users: (query: string, page: number, signal: AbortSignal) => request<{items: AccessUser[]; total: number}>(`/iam/users?${new URLSearchParams({ query, page: String(page) })}`, { signal }),
  roles: (signal: AbortSignal) => request<AccessRole[]>('/iam/roles', { signal }),
  catalog: (signal: AbortSignal) => request<Permission[]>('/iam/permissions', { signal }),
  menus: (signal: AbortSignal) => request<Menu[]>('/iam/menus', { signal }),
  menuPages: (signal: AbortSignal) => request<Menu[]>('/iam/menu-pages', { signal }),
  createMenu: (menu: Menu, signal: AbortSignal) => managementMutation<Menu>('/iam/menus','POST',menu,signal),
  updateMenu: (menu: Menu, signal: AbortSignal) => managementMutation<Menu>(`/iam/menus/${encodeURIComponent(menu.code)}`,'PUT',menu,signal),
  deleteMenu: (menu: Menu, signal: AbortSignal) => managementMutation<void>(`/iam/menus/${encodeURIComponent(menu.code)}`,'DELETE',{revision:menu.revision},signal),
  createUser: (username: string, password: string, tenantId: string, role: string, signal: AbortSignal) => managementMutation<AccessUser>('/iam/users','POST',{ username, password, tenantId, role },signal),
  updateUser: (u: AccessUser, signal: AbortSignal) => managementMutation<AccessUser>(`/iam/users/${encodeURIComponent(u.id)}`,'PUT',{ enabled: !u.enabled, revision: u.revision },signal),
  grant: (u: AccessUser, roleIds: string[], signal: AbortSignal) => managementMutation<AccessUser>(`/iam/users/${encodeURIComponent(u.id)}/roles`,'PUT',{ roleIds, revision: u.revision },signal),
  createRole: (code: string, name: string, menus: string[], signal: AbortSignal) => managementMutation<AccessRole>('/iam/roles','POST',{ code, name, menus },signal),
  updateRole: (role: AccessRole, menus: string[], signal: AbortSignal) => managementMutation<AccessRole>(`/iam/roles/${encodeURIComponent(role.id)}`,'PUT',{name:role.name,enabled:role.enabled,revision:role.revision,menus},signal),
}
