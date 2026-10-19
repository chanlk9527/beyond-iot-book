import { request, managementMutation } from './management'
import type { Page } from './catalog'
export interface ProductionBatch {
  id:string; tenantId:string|null; scopeKey:string; code:string; name:string; productId:string; productName:string;
  modelVersion:string; quantity:number; hardwareVersion:string; firmwareVersion:string;
  status:'PLANNED'|'PRODUCING'|'RELEASED'|'DELIVERED'; revision:number;
  recipient:string; deliveryRef:string; createdAt:number; deliveredAt:number|null;
}
export interface ProductionItem {
  deviceId:string; code:string; name:string; sequenceNumber:number; lifecycle:string; credentialVersion:number;
  burnedVersion:number|null; testedVersion:number|null; testResult:'PASS'|'FAIL'|null;
}
export interface ProductionEvent {
  id:string; deviceId:string|null; actor:string; action:string; station:string; result:string; note:string;
  credentialVersion:number|null; occurredAt:number;
}
export interface ProductionDetail { batch:ProductionBatch; items:ProductionItem[]; events:ProductionEvent[]; totalEvents:number }
export interface ProductionCreate { code:string; name:string; productId:string; modelVersion:string; quantity:number; hardwareVersion:string; firmwareVersion:string }
export interface ProvisioningCredential { deviceId:string; code:string; clientId:string; username:string; secret:string; credentialVersion:number }
const path=(scope:string,id?:string)=>`${scope}/production/batches${id?'/'+encodeURIComponent(id):''}`
export const productionApi={
  list:(scope:string,query:string,page:number,signal:AbortSignal)=>request<Page<ProductionBatch>>(`${path(scope)}?${new URLSearchParams({query,page:String(page)})}`,{signal}),
  detail:(scope:string,id:string,eventPage:number,signal:AbortSignal)=>request<ProductionDetail>(`${path(scope,id)}?eventPage=${eventPage}`,{signal}),
  create:(scope:string,input:ProductionCreate,signal:AbortSignal)=>managementMutation<ProductionDetail>(path(scope),'POST',input,signal),
  generate:(scope:string,id:string,revision:number,signal:AbortSignal)=>managementMutation<ProductionDetail>(`${path(scope,id)}/generate`,'POST',{revision},signal),
  credentials:(scope:string,id:string,revision:number,signal:AbortSignal)=>managementMutation<ProvisioningCredential[]>(`${path(scope,id)}/credentials`,'POST',{revision},signal),
  evidence:(scope:string,id:string,deviceId:string,kind:'burn'|'tests',input:{revision:number;credentialVersion:number;station:string;note:string;result:string},signal:AbortSignal)=>managementMutation<ProductionDetail>(`${path(scope,id)}/devices/${encodeURIComponent(deviceId)}/${kind}`,'POST',input,signal),
  release:(scope:string,id:string,revision:number,note:string,signal:AbortSignal)=>managementMutation<ProductionDetail>(`${path(scope,id)}/release`,'POST',{revision,note},signal),
  deliver:(scope:string,id:string,revision:number,input:{recipient:string;deliveryRef:string;note:string},signal:AbortSignal)=>managementMutation<ProductionDetail>(`${path(scope,id)}/deliver`,'POST',{revision,...input},signal),
}
export const productionStatus:Record<ProductionBatch['status'],string>={PLANNED:'待生产',PRODUCING:'生产中',RELEASED:'已放行',DELIVERED:'已交付'}
export function productionReady(item:ProductionItem):boolean {
  return item.lifecycle==='ACTIVE' && item.burnedVersion===item.credentialVersion && item.testedVersion===item.credentialVersion && item.testResult==='PASS'
}
