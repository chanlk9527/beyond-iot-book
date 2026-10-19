import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import * as api from '../api/management'
import { closeDeviceSubscriptions } from '../realtime/deviceSubscription'
import { hasPermission } from '../security/permissions'

export const useSessionStore = defineStore('session', () => {
  const account = ref<api.Account | null>(null)
  const can = (permission: string) => hasPermission(account.value, permission)
  const status = ref<'idle' | 'loading' | 'ready' | 'anonymous' | 'error'>('idle')
  const error = ref('')
  const tenant = ref<api.AccessibleTenant | null>(null)
  const tenantStatus = ref<'idle' | 'loading' | 'ready' | 'error'>('idle')
  const tenantError = ref('')
  const authenticated = computed(() => status.value === 'ready' && account.value !== null)
  let revision = 0
  let listRevision = 0
  let pending: Promise<void> | undefined

  function clear() {
    revision++
    listRevision++
    api.cancelRequests()
    closeDeviceSubscriptions()
    pending = undefined
    account.value = null
    tenant.value = null
    status.value = 'anonymous'
    tenantStatus.value = 'idle'
    error.value = ''
    tenantError.value = ''
  }

  async function ensureSession(force = false): Promise<void> {
    if (pending) return pending
    if (!force && (status.value === 'ready' || status.value === 'anonymous')) return
    const version = revision
    // Keep the mounted console visible while navigation revalidates the account.
    if (!authenticated.value) status.value = 'loading'
    error.value = ''
    pending = (async () => {
      try {
        const result = await api.currentAccount()
        if (version !== revision) return
        account.value = result
        status.value = 'ready'
      } catch (failure) {
        if (version !== revision || api.isCancelled(failure)) return
        if (failure instanceof api.ApiError && failure.status === 401) clear()
        else { error.value = api.errorMessage(failure); status.value = 'error' }
      } finally {
        if (version === revision) pending = undefined
      }
    })()
    return pending
  }

  async function login(username: string, password: string) {
    await api.signIn(username, password)
    clear()
    await ensureSession(true)
    if (!authenticated.value) throw new Error(error.value || '登录状态未能恢复，请重新登录。')
  }

  async function logout() {
    await api.signOut()
    clear()
  }

  async function refreshTenant() {
    const version = ++listRevision
    const sessionVersion = revision
    tenant.value = null
    tenantStatus.value = 'loading'
    tenantError.value = ''
    try {
      const tenantId=account.value?.tenantId
      const result = tenantId ? await api.getTenant(tenantId) : null
      if (version !== listRevision || sessionVersion !== revision) return
      tenant.value = result
      tenantStatus.value = 'ready'
    } catch (failure) {
      if (version !== listRevision || sessionVersion !== revision || api.isCancelled(failure)) return
      tenantError.value = api.errorMessage(failure)
      tenantStatus.value = 'error'
    }
  }

  return { can, account, status, error, authenticated, tenant, tenantStatus, tenantError,
    clear, ensureSession, login, logout, refreshTenant }
})
