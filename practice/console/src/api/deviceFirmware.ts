import { request } from './management'
export interface FirmwareInformation { hardwareVersion:string; bootId:string; sdkVersion:string|null; clockSynced:boolean; occurredAt:string; receivedAt:string; bootObservedAt:string; stale:boolean }
export interface FirmwareComponent { component:string; version:string; source:string; occurredAt:string; receivedAt:string; stale:boolean; beforeCurrentBoot:boolean }
export interface FirmwareInventory { revision:number; information:FirmwareInformation|null; components:FirmwareComponent[]; checkedAt:string; maximumReportAgeSeconds:number }
export interface FirmwareCheck { code:string; message:string }
export interface FirmwarePreflight { deviceId:string; channel:number; component:string; currentVersion:string|null; targetVersion:string; hardwareVersion:string|null; inventoryRevision:number; targetRevision:number; compatible:boolean; reasons:FirmwareCheck[]; warnings:FirmwareCheck[]; checkedAt:string }
const base=(scope:string,id:string)=>`${scope}/devices/${encodeURIComponent(id)}/firmware`
export const deviceFirmwareApi={
 inventories:(scope:string,ids:string[],signal:AbortSignal)=>request<Record<string,FirmwareInventory>>(`${scope}/devices/firmware-inventory?${new URLSearchParams({ids:ids.join(',')})}`,{signal}),
 inventory:(scope:string,id:string,signal:AbortSignal)=>request<FirmwareInventory>(base(scope,id),{signal}),
 preflight:(scope:string,id:string,channel:number,versionId:string,signal:AbortSignal)=>request<FirmwarePreflight>(`${base(scope,id)}/channels/${channel}/versions/${encodeURIComponent(versionId)}/preflight`,{signal}),
}
