import { request, managementMutation } from './management'
export interface DeveloperApp {
  appId: string; name: string; tenantId: string; tenantName: string; requestedScopes: string; scopes: string
  reviewStatus: 'PENDING'|'APPROVED'|'REJECTED'; reviewNote: string; enabled: boolean; revision: number
}
export interface AppAudit { actor: string; action: string; detail: string; occurredAt: string }
export const scopeOptions = [
  { value: 'device:read', label: '查询设备' }, { value: 'device:control', label: '控制设备' },
  { value: 'video:watch', label: '实时视频' }, { value: 'video:recording', label: '录像访问' },
  { value: 'event:read', label: '读取事件' },
]
const base = '/developer'
const tenantPath = (tenant: string) => `${base}/tenants/${encodeURIComponent(tenant)}/apps`
const appPath = (app: DeveloperApp) => `${tenantPath(app.tenantId)}/${encodeURIComponent(app.appId)}`
export const developerApi = {
  list: (tenant: string, signal: AbortSignal) => request<DeveloperApp[]>(tenantPath(tenant), { signal }),
  reviews: (signal: AbortSignal) => request<DeveloperApp[]>(`${base}/reviews`, { signal }),
  create: (tenant: string, name: string, scopes: string[], signal: AbortSignal) => managementMutation<DeveloperApp>(tenantPath(tenant), 'POST', { name, scopes }, signal),
  submit: (app: DeveloperApp, scopes: string[], signal: AbortSignal) => managementMutation<DeveloperApp>(`${appPath(app)}/submit`, 'POST', { revision: app.revision, scopes }, signal),
  secret: (app: DeveloperApp, signal: AbortSignal) => managementMutation<{clientSecret: string}>(`${appPath(app)}/secret`, 'POST', { revision: app.revision }, signal),
  review: (app: DeveloperApp, approved: boolean, scopes: string[], note: string, signal: AbortSignal) => managementMutation<DeveloperApp>(`${base}/reviews/${encodeURIComponent(app.appId)}`, 'POST', { revision: app.revision, approved, scopes, note }, signal),
  status: (app: DeveloperApp, signal: AbortSignal) => managementMutation<DeveloperApp>(`${base}/reviews/${encodeURIComponent(app.appId)}/status`, 'PUT', { revision: app.revision, enabled: !app.enabled }, signal),
  audit: (app: DeveloperApp, signal: AbortSignal) => request<AppAudit[]>(`${appPath(app)}/audit`, { signal }),
  platformAudit: (app: DeveloperApp, signal: AbortSignal) => request<AppAudit[]>(`${base}/reviews/${encodeURIComponent(app.appId)}/audit`, { signal }),
}
