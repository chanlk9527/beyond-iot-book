export interface NavigationMenu { code: string; name: string; group: string; path: string; sortOrder: number }
export interface Account { id: string; username: string; platformAdmin?: boolean; permissions?: string[]; tenantId?: string | null; menus?: NavigationMenu[] }
export interface AccessibleTenant { id: string; name: string; role: string }
interface CsrfToken { headerName: string; token: string }

export class ApiError extends Error {
  constructor(public readonly status: number, message: string) { super(message) }
}

let scope = new AbortController()
let onUnauthorized = () => {}
export function setUnauthorizedHandler(handler: () => void) { onUnauthorized = handler }
export function cancelRequests() {
  scope.abort()
  scope = new AbortController()
}
export function isCancelled(error: unknown) {
  return error instanceof Error && (error.name === 'AbortError' || error.name === 'TimeoutError')
}
export function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : '请求失败，请稍后重试。'
}

export async function request<T>(path: string, options: RequestInit = {}, notifyUnauthorized = true): Promise<T> {
  const currentScope = scope
  const signals = [currentScope.signal, AbortSignal.timeout(15_000)]
  if (options.signal) signals.push(options.signal)
  let response: Response
  try {
    response = await fetch(`/api/management${path}`, {
      ...options,
      credentials: 'same-origin',
      cache: 'no-store',
      signal: AbortSignal.any(signals),
      headers: { Accept: 'application/json', ...options.headers },
    })
  } catch (error) {
    if (error instanceof Error && error.name === 'TimeoutError') throw new Error('请求超时，请检查服务状态后重试。')
    if (isCancelled(error)) throw error
    throw new Error('暂时无法连接服务，请检查网络或后端是否已启动。')
  }
  currentScope.signal.throwIfAborted()
  if (!response.ok) {
    if (response.status === 401 && notifyUnauthorized) onUnauthorized()
    const messages: Record<number, string> = {
      400: '输入无效，请检查字段内容、模型定义或账号状态。',
      409: '数据已变更、编码重复或当前状态不允许此操作，请刷新并检查后重试。',
      401: '账号或密码错误，或登录状态已失效。',
      403: '没有访问权限，或安全凭证已过期。请刷新后重试。',
      404: '请求的资源不存在。',
      429: '操作过于频繁，请稍后重试。',
    }
    throw new ApiError(response.status, messages[response.status] || '服务暂时不可用，请稍后重试。')
  }
  if (response.status === 204) return undefined as T
  try {
    const value = await response.json() as T
    currentScope.signal.throwIfAborted()
    return value
  } catch (error) {
    if (isCancelled(error)) throw error
    throw new Error('服务响应格式异常，请检查接口代理配置。')
  }
}

export const currentAccount = () => request<Account>('/me')
export const listTenants = () => request<AccessibleTenant[]>('/tenants')
export const getTenant = (id: string, signal?: AbortSignal) =>
  request<AccessibleTenant>(`/tenants/${encodeURIComponent(id)}`, { signal })

async function formPost(path: string, body?: URLSearchParams, notifyUnauthorized = true) {
  // Obtain a fresh token for every mutation, including after login rotates it.
  const csrf = await request<CsrfToken>('/auth/csrf', {}, notifyUnauthorized)
  return request<void>(path, {
    method: 'POST',
    headers: { [csrf.headerName]: csrf.token, 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  }, notifyUnauthorized)
}
export const signIn = (username: string, password: string) =>
  formPost('/auth/login', new URLSearchParams({ username, password }), false)
export const signOut = () => formPost('/auth/logout')

export async function managementMutation<T>(path: string, method: 'POST' | 'PUT' | 'DELETE', value: unknown, signal?: AbortSignal): Promise<T> {
  const csrf = await request<CsrfToken>('/auth/csrf', { signal })
  return request<T>(path, {
    method, signal,
    headers: { [csrf.headerName]: csrf.token, 'Content-Type': 'application/json' },
    body: JSON.stringify(value),
  })
}
