import { request, managementMutation, managementUpload } from './management'
import type { Page } from './catalog'

export interface FirmwareChannel { channel:number; component:string; name:string; versionRule:'TUYA'|'OPAQUE'; createdAt:number }
export interface FirmwareArtifact { url:string|null; size:number; sha256:string; keyId:string; signature:string; fileId?:string|null }
export interface FirmwareFile { fileId:string; size:number; sha256:string; keyId:string; signatureVerified:boolean }
export interface FirmwareFileCapabilities { enabled:boolean; maximumUploadBytes:number; signingKeyIds:string[] }
export interface FirmwareVersion { id:string; channel:number; version:string; description:string; sourceVersions:string[]; hardwareVersions:string[]; artifact:FirmwareArtifact; enabled:boolean; revision:number; createdAt:number }
export type NewFirmwareVersion = Pick<FirmwareVersion,'version'|'description'|'sourceVersions'|'hardwareVersions'|'artifact'>
function base(scope:string,productId:string) { return `${scope}/products/${encodeURIComponent(productId)}/firmware/channels` }
export const firmwareApi = {
  capabilities:(scope:string,id:string,signal:AbortSignal)=>request<FirmwareFileCapabilities>(`${scope}/products/${encodeURIComponent(id)}/firmware/files/capabilities`,{signal}),
  upload:(scope:string,id:string,channel:number,file:File,keyId:string,signature:string,signal:AbortSignal)=>{
    const body=new FormData();body.append('file',file);body.append('keyId',keyId);body.append('signature',signature)
    return managementUpload<FirmwareFile>(`${base(scope,id)}/${channel}/files`,body,signal)
  },
  download:(scope:string,id:string,v:FirmwareVersion,signal:AbortSignal)=>request<{url:string;expiresAt:string}>(`${base(scope,id)}/${v.channel}/versions/${encodeURIComponent(v.id)}/download`,{signal}),
  channels:(scope:string,id:string,signal:AbortSignal)=>request<FirmwareChannel[]>(base(scope,id),{signal}),
  createChannel:(scope:string,id:string,value:Omit<FirmwareChannel,'createdAt'>,signal:AbortSignal)=>managementMutation<FirmwareChannel>(base(scope,id),'POST',value,signal),
  versions:(scope:string,id:string,channel:number,page:number,signal:AbortSignal)=>request<Page<FirmwareVersion>>(`${base(scope,id)}/${channel}/versions?${new URLSearchParams({page:String(page),size:'20'})}`,{signal}),
  createVersion:(scope:string,id:string,channel:number,value:NewFirmwareVersion,signal:AbortSignal)=>managementMutation<FirmwareVersion>(`${base(scope,id)}/${channel}/versions`,'POST',value,signal),
  status:(scope:string,id:string,v:FirmwareVersion,signal:AbortSignal)=>managementMutation<FirmwareVersion>(`${base(scope,id)}/${v.channel}/versions/${encodeURIComponent(v.id)}/status`,'PUT',{enabled:!v.enabled,revision:v.revision},signal),
}
