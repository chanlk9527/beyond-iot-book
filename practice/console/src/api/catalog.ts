import { request, managementMutation } from './management'
export interface ModelSpec { properties: Record<string,unknown>; events: Record<string,unknown>; operations: Record<string,unknown> }
export interface ProductConfiguration { category:string; manufacturer:string; modelNumber:string; deviceType:'DIRECT'|'GATEWAY'|'CHILD'; networkWay:'ETHERNET'|'WIFI'|'CELLULAR'|'NB_IOT'|'LORA'|'OTHER'; transportProtocol:'MQTT'; messageProtocol:'nebula-mqtt-v1'; dataFormat:'JSON'; firmwareVersion:string; supportUrl:string; installationNotes:string }
export function defaultProductConfiguration():ProductConfiguration { return {category:'',manufacturer:'',modelNumber:'',deviceType:'DIRECT',networkWay:'OTHER',transportProtocol:'MQTT',messageProtocol:'nebula-mqtt-v1',dataFormat:'JSON',firmwareVersion:'',supportUrl:'',installationNotes:''} }
export interface Product { configuration:ProductConfiguration; id:string; tenantId:string|null; code:string; name:string; description:string; enabled:boolean; latestVersion:string|null; revision:number; draft:ModelSpec }
export interface ModelVersion { version:string; model:ModelSpec; publishedAt:string; deviceCount:number }
export interface Device { id:string; tenantId:string|null; productId:string; productName:string; code:string; name:string; modelVersion:string; lifecycle:string; credentialVersion:number; revision:number }
export interface DeviceDetail { device:Device; product:Product; model:ModelVersion }
export interface PropertySnapshot { id:string; type:string; access:string; value:string; updatedAt:string; available:boolean }
export interface EventSnapshot { id:string; status:string; receivedAt:string; payload:string }
export interface DeviceRuntimeSnapshot { properties:PropertySnapshot[]; events:EventSnapshot[] }
export type ScalarValue = string | number | boolean
export type ShadowScalar = ScalarValue | ScalarValue[]
export interface DownlinkStatus { messageId:string; commandId:string|null; kind:string; status:string; code:string|null; message:string|null; createdAt:string; expiresAt:string }
export interface DesiredDispatch { shadow:DeviceShadow; downlink:DownlinkStatus|null; dispatchError:string|null }
export interface DeviceShadow { version:number; modelVersion:string; reported:Record<string,{value:ShadowScalar;observedAt:string;receivedAt:string;messageId:string}>; desired:Record<string,{value:ShadowScalar;updatedAt:string}>; delta:Record<string,ShadowScalar>; updatedAt:string|null }
export interface Credential { device:Device; clientId:string; username:string; secret:string }
export interface Page<T> { items:T[]; total:number; page:number; size:number }
export function catalogScope(platform:boolean,tenantId:unknown):string {
  if(platform && tenantId===undefined) return '/catalog/platform'
  if(!platform && typeof tenantId==='string' && tenantId.length>0) return `/catalog/tenants/${encodeURIComponent(tenantId)}`
  throw new Error('请明确选择平台级或租户范围。')
}
export function resourceScope(scope:string,resource:{tenantId:string|null}):string { return scope==='/catalog/platform' && resource.tenantId ? '/catalog/tenants/'+encodeURIComponent(resource.tenantId) : scope }
export interface GatewayTopology { gateway:Device|null; children:Page<Device> }
export const catalogApi = {
 topology:(scope:string,id:string,page:number,signal:AbortSignal)=>request<GatewayTopology>(`${scope}/devices/${encodeURIComponent(id)}/topology?${new URLSearchParams({page:String(page),size:'20'})}`,{signal}),
 bindGateway:(scope:string,d:Device,gatewayId:string,signal:AbortSignal)=>managementMutation<GatewayTopology>(`${resourceScope(scope,d)}/devices/${encodeURIComponent(d.id)}/gateway`,'PUT',{gatewayId,revision:d.revision},signal),
 unbindGateway:(scope:string,d:Device,gatewayId:string,signal:AbortSignal)=>managementMutation<GatewayTopology>(`${resourceScope(scope,d)}/devices/${encodeURIComponent(d.id)}/gateway?${new URLSearchParams({gatewayId,revision:String(d.revision)})}`,'DELETE',undefined,signal),
 saveAndDispatch:(scope:string,id:string,version:number,desired:Record<string,ShadowScalar|null>,signal:AbortSignal)=>managementMutation<DesiredDispatch>(`${scope}/devices/${encodeURIComponent(id)}/shadow/desired-and-dispatch`,'PUT',{version,desired},signal),
 invoke:(scope:string,id:string,operationId:string,inputs:Record<string,ShadowScalar>,signal:AbortSignal)=>managementMutation<DownlinkStatus>(`${scope}/devices/${encodeURIComponent(id)}/downlinks/operations`,'POST',{operationId,inputs},signal),
 writeProperties:(scope:string,id:string,properties:Record<string,ShadowScalar>,signal:AbortSignal)=>managementMutation<DownlinkStatus>(`${scope}/devices/${encodeURIComponent(id)}/downlinks/properties`,'POST',{properties},signal),
 downlink:(scope:string,id:string,messageId:string,signal:AbortSignal)=>request<DownlinkStatus>(`${scope}/devices/${encodeURIComponent(id)}/downlinks/${encodeURIComponent(messageId)}`,{signal}),
 shadow:(scope:string,id:string,signal:AbortSignal)=>request<DeviceShadow>(`${scope}/devices/${encodeURIComponent(id)}/shadow`,{signal}),
 desired:(scope:string,id:string,version:number,desired:Record<string,ShadowScalar|null>,signal:AbortSignal)=>managementMutation<DeviceShadow>(`${scope}/devices/${encodeURIComponent(id)}/shadow/desired`,'PUT',{version,desired},signal),
 products:(scope:string,query:string,page:number,signal:AbortSignal,category='')=>request<Page<Product>>(`${scope}/products?${new URLSearchParams({query,page:String(page),size:'20',category})}`,{signal}),
 product:(scope:string,id:string,signal:AbortSignal)=>request<Product>(`${scope}/products/${encodeURIComponent(id)}`,{signal}),
 create:(scope:string,value:{code:string;name:string;description:string;configuration?:ProductConfiguration},signal:AbortSignal)=>managementMutation<Product>(`${scope}/products`,'POST',value,signal),
 save:(scope:string,p:Product,model:ModelSpec,signal:AbortSignal)=>managementMutation<Product>(`${resourceScope(scope,p)}/products/${encodeURIComponent(p.id)}/draft`,'PUT',{name:p.name,description:p.description,configuration:p.configuration,model,revision:p.revision},signal),
 publish:(scope:string,p:Product,confirmEmpty:boolean,signal:AbortSignal)=>managementMutation<Product>(`${resourceScope(scope,p)}/products/${encodeURIComponent(p.id)}/publish`,'POST',{revision:p.revision,confirmEmpty},signal),
 status:(scope:string,p:Product,signal:AbortSignal)=>managementMutation<Product>(`${resourceScope(scope,p)}/products/${encodeURIComponent(p.id)}/status`,'PUT',{enabled:!p.enabled,revision:p.revision},signal),
 remove:(scope:string,p:Product,signal:AbortSignal)=>managementMutation<void>(`${resourceScope(scope,p)}/products/${encodeURIComponent(p.id)}?${new URLSearchParams({revision:String(p.revision)})}`,'DELETE',undefined,signal),
 models:(scope:string,id:string,signal:AbortSignal)=>request<ModelVersion[]>(`${scope}/products/${encodeURIComponent(id)}/models`,{signal}),
 devices:(scope:string,query:string,page:number,signal:AbortSignal,productId='')=>request<Page<Device>>(`${scope}/devices?${new URLSearchParams({query,page:String(page),size:'20',productId})}`,{signal}),
 detail:(scope:string,id:string,signal:AbortSignal)=>request<DeviceDetail>(`${scope}/devices/${encodeURIComponent(id)}/detail`,{signal}),
 runtime:(scope:string,id:string,signal:AbortSignal)=>request<DeviceRuntimeSnapshot>(`${scope}/devices/${encodeURIComponent(id)}/runtime`,{signal}),
 register:(scope:string,value:{productId:string;code:string;name:string;modelVersion:string},signal:AbortSignal)=>managementMutation<Credential>(`${scope}/devices`,'POST',value,signal),
 updateDevice:(scope:string,d:Device,signal:AbortSignal)=>managementMutation<Device>(`${resourceScope(scope,d)}/devices/${encodeURIComponent(d.id)}`,'PUT',{name:d.name,lifecycle:d.lifecycle,revision:d.revision},signal),
 removeDevice:(scope:string,d:Device,signal:AbortSignal)=>managementMutation<void>(`${resourceScope(scope,d)}/devices/${encodeURIComponent(d.id)}?${new URLSearchParams({revision:String(d.revision)})}`,'DELETE',undefined,signal),
 credential:(scope:string,d:Device,signal:AbortSignal)=>request<Credential>(`${resourceScope(scope,d)}/devices/${encodeURIComponent(d.id)}/credential`,{signal}),
 rotate:(scope:string,d:Device,signal:AbortSignal)=>managementMutation<Credential>(`${resourceScope(scope,d)}/devices/${encodeURIComponent(d.id)}/credential`,'POST',{revision:d.revision},signal),
}
export const warmerModel:ModelSpec={
 properties:{power:{id:'power',name:'电源开关',description:'控制设备电源',schema:{type:'BOOLEAN',minimum:null,maximum:null,maxLength:null},access:'READ_WRITE'},targetTemperature:{id:'targetTemperature',name:'目标温度',description:'设定保温温度',schema:{type:'DECIMAL',unit:'℃',minimum:35,maximum:55,maxLength:null},access:'READ_WRITE'},currentTemperature:{id:'currentTemperature',name:'当前温度',description:'传感器测量温度',schema:{type:'DECIMAL',unit:'℃',minimum:0,maximum:100,maxLength:null},access:'READ_ONLY'}},
 events:{},operations:{setTemperature:{id:'setTemperature',name:'设置温度',description:'调整设备目标温度',inputs:{temperature:{id:'temperature',name:'目标温度',description:'需要设置的温度',schema:{type:'DECIMAL',unit:'℃',minimum:35,maximum:55,maxLength:null},required:true}}}},
}
