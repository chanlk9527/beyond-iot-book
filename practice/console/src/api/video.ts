import { managementMutation, request } from './management'
export interface LiveSession { sessionId: string; protocol: 'webrtc'; expiresAt: string; iceServers: RTCIceServer[] }
export interface LiveSignal { type: 'OFFER' | 'ANSWER' | 'ICE'; payload: string }
const path = (id: string) => `/video/sessions/${encodeURIComponent(id)}`
export const videoApi = {
  create: (tenantId: string | null, deviceId: string, channelId: string, signal?: AbortSignal) =>
    managementMutation<LiveSession>('/video/sessions', 'POST', { tenantId, deviceId, channelId }, signal),
  send: (id: string, value: LiveSignal, signal?: AbortSignal) => managementMutation<void>(`${path(id)}/signals`, 'POST', value, signal),
  receive: (id: string, signal?: AbortSignal) => request<LiveSignal[]>(`${path(id)}/signals`, { signal }),
  close: (id: string) => managementMutation<void>(path(id), 'DELETE', undefined),
}
