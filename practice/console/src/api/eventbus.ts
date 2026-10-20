import { request } from './management'
export interface Backlog { pending:number; received:number; retrying:number; oldest:string|null }
export interface RuntimeMetrics {
  nodeId:string; role:string; sampledAt:number; startedAt:number; healthy:boolean; revision:number
  syncAgeMillis:number; subscriptions:number; published:number; matched:number; routed:number
  rpcSent:number; rpcReceived:number; completed:number; failed:number; handlerNanos:number
  activeHandlers:number; persistenceQueued:number; persistenceCapacity:number
}
export interface EventNode {
  directory:{id:string; endpoint:string; state:string; leaseUntil:string; revision:number; alive:boolean}
  observation:string; metrics:RuntimeMetrics|null; lastSuccessAt:number|null
}
export interface EventSnapshot {
  mode:string; sampledAt:string; nodes:EventNode[]; collected:number; complete:boolean
  directory:null|{namespace:string; revision:number; totalNodes:number; activeNodes:number; subscriptions:number; sampledAt:string; backlog:Backlog}
}
export interface EventSubscription {
  id:string; name:string; mode:string; sourceScope:'LOCAL'|'REMOTE'|'ALL'; generation:number; patterns:string[]
  tenantId:string|null; productId:string|null; deviceCount:number; deviceSample:string[]; members:string[]
}
export interface SubscriptionPage { total:number; page:number; items:EventSubscription[] }
export const eventbusApi = {
  snapshot:(signal?:AbortSignal)=>request<EventSnapshot>('/eventbus',{signal}),
  subscriptions:(page:number,signal?:AbortSignal)=>request<SubscriptionPage>(`/eventbus/subscriptions?page=${page}`,{signal}),
  backlog:(id:string,signal?:AbortSignal)=>request<Backlog>(`/eventbus/subscriptions/${encodeURIComponent(id)}/backlog`,{signal}),
}
