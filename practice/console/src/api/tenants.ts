import { request, managementMutation } from './management'
export interface Tenant { id: string; name: string; enabled: boolean; revision: number; memberCount: number }
export interface TenantMember { accountId: string; username: string | null; role: string; enabled: boolean; revision: number }
export interface TenantAudit { id: string; actorId: string; action: string; subjectId: string | null; occurredAt: string }
export interface TenantPage { items: Tenant[]; total: number; page: number; size: number }
const base = '/platform/tenants'
const path = (id: string) => `${base}/${encodeURIComponent(id)}`
export const tenantsApi = {
  list: (query: string, page: number, signal: AbortSignal) => request<TenantPage>(`${base}?${new URLSearchParams({ query, page: String(page), size: '20' })}`, { signal }),
  get: (id: string, signal: AbortSignal) => request<Tenant>(path(id), { signal }),
  create: (name: string, signal: AbortSignal) => managementMutation<Tenant>(base, 'POST', { name }, signal),
  update: (tenant: Tenant, signal: AbortSignal) => managementMutation<Tenant>(path(tenant.id), 'PUT', { name: tenant.name, enabled: tenant.enabled, revision: tenant.revision }, signal),
  members: (id: string, signal: AbortSignal) => request<TenantMember[]>(`${path(id)}/members`, { signal }),
  addMember: (id: string, username: string, role: string, signal: AbortSignal) => managementMutation<void>(`${path(id)}/members`, 'POST', { username, role }, signal),
  createUser: (id: string, username: string, password: string, role: string, signal: AbortSignal) => managementMutation<TenantMember>(`${path(id)}/users`, 'POST', { username, password, role }, signal),
  updateMember: (id: string, member: TenantMember, signal: AbortSignal) => managementMutation<void>(`${path(id)}/members/${encodeURIComponent(member.accountId)}`, 'PUT', { role: member.role, enabled: member.enabled, revision: member.revision }, signal),
  audit: (id: string, signal: AbortSignal) => request<TenantAudit[]>(`${path(id)}/audit`, { signal }),
}
