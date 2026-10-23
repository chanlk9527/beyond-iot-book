// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, h, nextTick, reactive } from 'vue'
import DeviceFirmwarePanel from './DeviceFirmwarePanel.vue'
import { deviceFirmwareApi, type FirmwareInventory, type FirmwarePreflight } from '../api/deviceFirmware'
import { firmwareApi, type FirmwareVersion } from '../api/firmware'
import type { Device } from '../api/catalog'
vi.mock('../api/deviceFirmware',()=>({deviceFirmwareApi:{inventory:vi.fn(),preflight:vi.fn()}}))
vi.mock('../api/firmware',()=>({firmwareApi:{channels:vi.fn(),versions:vi.fn()}}))
const device:Device={id:'d',tenantId:'ta',productId:'p',productName:'产品',code:'device',name:'设备',modelVersion:'v1',lifecycle:'ACTIVE',credentialVersion:1,revision:0}
const at='2026-10-21T10:00:00Z'
const snapshot=():FirmwareInventory=>({revision:2,information:{hardwareVersion:'HW-A',bootId:'boot-1',sdkVersion:'sdk1',clockSynced:false,occurredAt:at,receivedAt:at,bootObservedAt:at,stale:false},components:[{component:'main',version:'1.9.0',source:'ota.version.report',occurredAt:at,receivedAt:at,stale:false,beforeCurrentBoot:false}],checkedAt:at,maximumReportAgeSeconds:86400})
const version:FirmwareVersion={id:'v',channel:0,version:'1.10.0',description:'升级',sourceVersions:['1.9.0'],hardwareVersions:['HW-A'],artifact:{url:'https://example.com/firmware.bin',size:1,sha256:'a'.repeat(64),keyId:'k',signature:'s'},enabled:true,revision:1,createdAt:1}
const preflight=():FirmwarePreflight=>({deviceId:'d',channel:0,component:'main',currentVersion:'1.9.0',targetVersion:'1.10.0',hardwareVersion:'HW-A',inventoryRevision:2,targetRevision:1,compatible:true,reasons:[],warnings:[{code:'DISPATCH_CHECKS_REQUIRED',message:'仅检查静态兼容性，在线状态和安装条件仍需确认'}],checkedAt:at})
let dispose:(()=>void)|undefined
async function settle(){for(let i=0;i<20;i++)await nextTick()}
function mount(canReadProduct=true){
 const props=reactive({scope:'/catalog/platform',device:{...device},canReadProduct})
 const root=document.createElement('div');document.body.appendChild(root)
 // Render through a reactive parent to exercise changing device identities.
 const parent=createApp({setup:()=>()=>h(DeviceFirmwarePanel,props)});parent.mount(root)
 dispose=()=>{parent.unmount();root.remove()};return {root,props}
}
const button=(root:ParentNode,label:string)=>[...root.querySelectorAll('button')].find(b=>b.textContent?.trim()===label)!
async function selectTarget(root:HTMLElement,id='v'){const control=root.querySelector('[aria-label="预检目标版本"]') as HTMLSelectElement;control.value=id;control.dispatchEvent(new Event('change'));await settle()}
beforeEach(()=>{
 vi.resetAllMocks();vi.mocked(deviceFirmwareApi.inventory).mockResolvedValue(snapshot());vi.mocked(deviceFirmwareApi.preflight).mockResolvedValue(preflight())
 vi.mocked(firmwareApi.channels).mockResolvedValue([{channel:0,component:'main',name:'主模块',versionRule:'TUYA',createdAt:1}])
 vi.mocked(firmwareApi.versions).mockResolvedValue({items:[version],total:1,page:1,size:20})
})
afterEach(()=>{dispose?.();dispose=undefined})
it('shows actual reports and checks an explicitly selected target using the device scope',async()=>{
 const {root}=mount();await settle();expect(root.textContent).toContain('HW-A');expect(root.textContent).toContain('1.9.0');expect(root.textContent).toContain('boot-1')
 expect(deviceFirmwareApi.preflight).not.toHaveBeenCalled();expect(firmwareApi.channels).toHaveBeenCalledWith('/catalog/tenants/ta','p',expect.any(AbortSignal))
 await selectTarget(root);button(root,'检查升级兼容性').click();await settle()
 expect(deviceFirmwareApi.preflight).toHaveBeenCalledWith('/catalog/platform','d',0,'v',expect.any(AbortSignal))
 expect(root.textContent).toContain('静态兼容检查通过');expect(root.textContent).toContain('在线状态和安装条件仍需确认');expect(root.textContent).not.toContain('升级成功')
})
it('shows every blocking reason and stale or previous-boot reports',async()=>{
 const state=snapshot();state.information!.stale=true;state.components[0].beforeCurrentBoot=true
 vi.mocked(deviceFirmwareApi.inventory).mockResolvedValue(state)
 vi.mocked(deviceFirmwareApi.preflight).mockResolvedValue({...preflight(),compatible:false,reasons:[{code:'SOURCE_NOT_ALLOWED',message:'源版本不在允许清单中'},{code:'COMPONENT_BEFORE_BOOT',message:'请重新上报组件版本'}]})
 const {root}=mount();await settle();await selectTarget(root);button(root,'检查升级兼容性').click();await settle()
 expect(root.textContent).toContain('已过期，请重新上报');expect(root.textContent).toContain('早于本次启动');expect(root.textContent).toContain('暂不满足升级条件');expect(root.textContent).toContain('源版本不在允许清单中');expect(root.textContent).toContain('请重新上报组件版本')
})
it('does not invent actual firmware or fetch product data without product permission',async()=>{
 vi.mocked(deviceFirmwareApi.inventory).mockResolvedValue({revision:0,information:null,components:[],checkedAt:at,maximumReportAgeSeconds:86400})
 const {root}=mount(false);await settle();expect(root.textContent).toContain('设备尚未上报固件信息');expect(firmwareApi.channels).not.toHaveBeenCalled();expect(root.querySelector('select')).toBeNull()
})
it('shows failed reads and supports refreshing without making a failed snapshot actionable',async()=>{
 vi.mocked(deviceFirmwareApi.inventory).mockRejectedValueOnce(new Error('状态读取失败'))
 const {root}=mount();await settle();await selectTarget(root);expect(root.textContent).toContain('固件状态加载失败');expect(button(root,'检查升级兼容性').disabled).toBe(true)
 button(root,'刷新固件状态').click();await settle();expect(root.textContent).toContain('HW-A');expect(root.textContent).not.toContain('固件状态加载失败')
})
it('discards an old preflight when the parent switches to another device',async()=>{
 let resolve!:(value:FirmwarePreflight)=>void
 vi.mocked(deviceFirmwareApi.preflight).mockImplementationOnce(()=>new Promise(done=>{resolve=done}))
 const {root,props}=mount();await settle();await selectTarget(root);button(root,'检查升级兼容性').click();await settle()
 props.device={...device,id:'another-device'};await settle();resolve(preflight());await settle()
 expect(root.textContent).not.toContain('静态兼容检查通过');expect(deviceFirmwareApi.inventory).toHaveBeenLastCalledWith('/catalog/platform','another-device',expect.any(AbortSignal))
})
