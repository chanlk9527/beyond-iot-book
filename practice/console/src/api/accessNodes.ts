import { request } from './management'

export interface AccessNodeMetrics {
  running: boolean
  host: string
  port: number | null
  connections: number
  onlineDevices: number
  maxConnections: number
  acceptedMessages: number
  rejectedMessages: number
  activeWorkers: number
  workerThreads: number
  queuedWork: number
  maxQueuedWork: number
}
export interface AccessNode {
  nodeId: string
  name: string
  protocol: string
  status: 'RUNNING' | 'STOPPED' | 'DISABLED'
  processStartedAt: string
  processUptimeMillis: number
  metrics: AccessNodeMetrics | null
  lifecycle?: 'STARTING' | 'ACTIVE' | 'DRAINING' | 'STOPPING' | 'OFFLINE' | 'UNREACHABLE'
  lastHeartbeat?: string | null
  leaseUntil?: string | null
  revision?: number
  fresh?: boolean
}
export interface AccessNodesSnapshot {
  scope: 'LOCAL_PROCESS' | 'CLUSTER_MEMBERSHIP'
  totalNodes?: number
  onlineDevices?: number | null
  complete?: boolean
  sampledAt: string
  nodes: AccessNode[]
}
export const accessNodesApi = {
  snapshot: (signal?: AbortSignal, includeHistory = false) => request<AccessNodesSnapshot>(
    `/access/nodes${includeHistory ? '?includeHistory=true' : ''}`, { signal }),
}
