import { managementMutation, request } from './management'
import type { DataPolicy } from '../components/dataPolicy'
export interface HistoryCapabilities {
  provider: string; enabled: boolean; timeRetention: boolean; countRetention: boolean
  changeRecording: boolean; intervalSampling: boolean; persistentAggregates: boolean; maximumRetentionDays: number
}
export interface HistoryPolicyView { policy: DataPolicy; revision: number; capabilities: HistoryCapabilities }
export interface HistoryItem { occurredAt: string; receivedAt: string; messageId: string; modelVersion: string; valueType: string; payload: string }
export interface HistoryPage { items: HistoryItem[]; nextCursor: string | null }
export const historyApi = {
  policy: (scope: string, id: string, signal: AbortSignal) => request<HistoryPolicyView>(`${scope}/products/${encodeURIComponent(id)}/data-policy`, { signal }),
  save: (scope: string, id: string, policy: DataPolicy, revision: number, signal: AbortSignal) => managementMutation<HistoryPolicyView>(`${scope}/products/${encodeURIComponent(id)}/data-policy`, 'PUT', { policy, revision }, signal),
  query: (scope: string, id: string, kind: 'PROPERTY' | 'EVENT', identifier: string, from: string, to: string, cursor: string | null, signal: AbortSignal) => {
    const query = new URLSearchParams({ from, to, limit: '50', ...(cursor ? { cursor } : {}) })
    return request<HistoryPage>(`${scope}/devices/${encodeURIComponent(id)}/history/${kind}/${encodeURIComponent(identifier)}?${query}`, { signal })
  },
}
