// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createApp, nextTick } from 'vue'
import ProductFirmware from './ProductFirmware.vue'
import { firmwareApi, type FirmwareVersion } from '../api/firmware'
vi.mock('../api/firmware',()=>({firmwareApi:{channels:vi.fn(),versions:vi.fn(),createChannel:vi.fn(),createVersion:vi.fn(),status:vi.fn(),capabilities:vi.fn(),upload:vi.fn(),download:vi.fn()}}))
const channel={channel:0,component:'main',name:'主模块固件',versionRule:'TUYA' as const,createdAt:1}
const version:FirmwareVersion={id:'v1',channel:0,version:'1.10.0',description:'修复温控',sourceVersions:['1.9.0'],hardwareVersions:['HW-A'],artifact:{url:'https://example.com/a.bin',size:1024,sha256:'a'.repeat(64),keyId:'factory-key',signature:'s'},enabled:false,revision:2,createdAt:1}
let dispose:(()=>void)|undefined
async function settle(){for(let i=0;i<20;i++)await nextTick()}
function mount(writable=true,enabled=true,publishable=true){
 const root=document.createElement('div');document.body.appendChild(root);const dirty=vi.fn()
 const app=createApp(ProductFirmware,{scope:'/catalog/tenants/ta',productId:'p',enabled,writable,publishable,onDirty:dirty});app.mount(root)
 dispose=()=>{app.unmount();root.remove()};return {root,dirty}
}
function button(root:ParentNode,label:string){return [...root.querySelectorAll('button')].find(b=>b.textContent?.trim()===label)}
async function input(root:ParentNode,label:string,value:string){const control=root.querySelector(`[aria-label="${label}"]`) as HTMLInputElement;control.value=value;control.dispatchEvent(new Event('input'));await settle()}
beforeEach(()=>{
 vi.clearAllMocks();vi.mocked(firmwareApi.channels).mockResolvedValue([channel]);vi.mocked(firmwareApi.versions).mockResolvedValue({items:[structuredClone(version)],total:1,page:1,size:20})
 vi.mocked(firmwareApi.capabilities).mockResolvedValue({enabled:false,maximumUploadBytes:67108864,signingKeyIds:[]})
 vi.mocked(firmwareApi.status).mockResolvedValue({...version,enabled:true,revision:3})
})
afterEach(()=>{dispose?.();dispose=undefined})
it('loads real channel inventory and uses the version revision for enabling',async()=>{
 const {root}=mount();await settle();expect(root.textContent).toContain('1.10.0');expect(root.textContent).toContain('HW-A');expect(root.textContent).toContain('文件内容未验证')
 button(root,'启用版本')!.click();await settle()
 expect(firmwareApi.status).toHaveBeenCalledWith('/catalog/tenants/ta','p',expect.objectContaining({revision:2,enabled:false}),expect.any(AbortSignal));expect(root.textContent).toContain('可用')
})
it('retains registration inputs after a failed request and does not report device success',async()=>{
 vi.mocked(firmwareApi.createVersion).mockRejectedValue(new Error('版本冲突'))
 const {root,dirty}=mount();await settle();button(root,'登记固件版本')!.click();await settle()
 await input(root,'固件版本','1.11.0');await input(root,'允许的源版本','1.9.0，1.10.0');await input(root,'适用硬件版本','HW-A\nHW-B');await input(root,'HTTPS 下载地址','https://example.com/update.bin');await input(root,'SHA-256','a'.repeat(64));await input(root,'签名密钥标识','factory-key');await input(root,'Ed25519 签名','signature')
 root.querySelector('form')!.dispatchEvent(new Event('submit',{cancelable:true}));await settle()
 expect(firmwareApi.createVersion).toHaveBeenCalledWith('/catalog/tenants/ta','p',0,expect.objectContaining({version:'1.11.0',sourceVersions:['1.9.0','1.10.0'],hardwareVersions:['HW-A','HW-B']}),expect.any(AbortSignal))
 expect(root.textContent).toContain('版本冲突');expect((root.querySelector('[aria-label="固件版本"]') as HTMLInputElement).value).toBe('1.11.0');expect(dirty).toHaveBeenLastCalledWith(true)
})
it('registers a version as disabled and clears the form only after successful persistence',async()=>{
 vi.mocked(firmwareApi.createVersion).mockResolvedValue({...version,version:'1.11.0'})
 const {root,dirty}=mount();await settle();button(root,'登记固件版本')!.click();await settle()
 root.querySelector('form')!.dispatchEvent(new Event('submit',{cancelable:true}));await settle()
 expect(firmwareApi.createVersion).toHaveBeenCalledTimes(1);expect(root.querySelector('form')).toBeNull();expect(dirty).toHaveBeenLastCalledWith(false)
})
it('does not offer mutations without permissions and permits disabling after product deactivation',async()=>{
 let mounted=mount(false,true,false);await settle();expect(button(mounted.root,'新增通道')).toBeUndefined();expect(button(mounted.root,'启用版本')).toBeUndefined();dispose?.()
 vi.mocked(firmwareApi.versions).mockResolvedValue({items:[{...version,enabled:true}],total:1,page:1,size:20})
 mounted=mount(true,false,true);await settle();expect(button(mounted.root,'新增通道')!.disabled).toBe(true);expect(button(mounted.root,'停用版本')!.disabled).toBe(false)
})
it('shows loading failures and recovers without displaying invented channels',async()=>{
 vi.mocked(firmwareApi.channels).mockRejectedValueOnce(new Error('网络中断'))
 const {root}=mount();await settle();expect(root.textContent).toContain('固件加载失败');expect(root.querySelector('select')).toBeNull()
 button(root,'刷新固件列表')!.click();await settle();expect(root.textContent).toContain('主模块固件')
})
it('ignores a stale version response after selecting another channel',async()=>{
 vi.mocked(firmwareApi.channels).mockResolvedValue([channel,{...channel,channel:9,component:'mcu',name:'MCU 固件'}])
 let resolveOld!:(value:any)=>void
 vi.mocked(firmwareApi.versions).mockImplementationOnce(()=>new Promise(resolve=>{resolveOld=resolve}))
 const {root}=mount();await settle()
 // Initial channel loading finishes only after its first version request.
 resolveOld({items:[version],total:1,page:1,size:20});await settle()
 vi.mocked(firmwareApi.versions).mockImplementationOnce(()=>new Promise(resolve=>{resolveOld=resolve})).mockResolvedValueOnce({items:[{...version,id:'mcu',channel:9,version:'2.0.0'}],total:1,page:1,size:20})
 button(root,'重试版本列表')?.click()
 const select=root.querySelector('[aria-label="升级通道"]') as HTMLSelectElement
 select.value='9';select.dispatchEvent(new Event('change'));await settle();select.value='0';select.dispatchEvent(new Event('change'));await settle()
 resolveOld({items:[{...version,id:'stale',version:'9.9.9'}],total:1,page:1,size:20});await settle()
 expect(root.textContent).not.toContain('9.9.9')
})
function enableUploads(maximumUploadBytes=64){
 vi.mocked(firmwareApi.capabilities).mockResolvedValue({enabled:true,maximumUploadBytes,signingKeyIds:['factory-key']})
 vi.mocked(firmwareApi.upload).mockResolvedValue({fileId:'file-one',size:3,sha256:'b'.repeat(64),keyId:'factory-key',signatureVerified:true})
}
async function prepareUpload(root:HTMLElement,bytes=[1,2,3]){
 button(root,'登记固件版本')!.click();await settle()
 await input(root,'固件版本','1.11.0');await input(root,'允许的源版本','1.10.0');await input(root,'适用硬件版本','HW-A');await input(root,'Ed25519 签名','signed-content')
 const key=root.querySelector('[aria-label="签名密钥标识"]') as HTMLSelectElement;key.value='factory-key';key.dispatchEvent(new Event('change'))
 const control=root.querySelector('[aria-label="固件文件"]') as HTMLInputElement;const file=new File([new Uint8Array(bytes)],'firmware.bin',{type:'application/octet-stream'})
 Object.defineProperty(control,'files',{value:[file],configurable:true});control.dispatchEvent(new Event('change'));await settle();return file
}
it('uploads real selected file and retries metadata registration without uploading a second copy',async()=>{
 enableUploads();vi.mocked(firmwareApi.createVersion).mockRejectedValueOnce(new Error('版本冲突')).mockResolvedValueOnce(version)
 const {root}=mount();await settle();const file=await prepareUpload(root)
 root.querySelector('form')!.dispatchEvent(new Event('submit',{cancelable:true}));await settle()
 expect(firmwareApi.upload).toHaveBeenCalledWith('/catalog/tenants/ta','p',0,file,'factory-key','signed-content',expect.any(AbortSignal))
 expect(root.textContent).toContain('文件已上传并通过签名校验');expect(root.textContent).toContain('版本冲突')
 expect(firmwareApi.createVersion).toHaveBeenCalledWith('/catalog/tenants/ta','p',0,expect.objectContaining({artifact:expect.objectContaining({fileId:'file-one',size:3,sha256:'b'.repeat(64),url:null})}),expect.any(AbortSignal))
 await input(root,'固件版本','1.12.0');root.querySelector('form')!.dispatchEvent(new Event('submit',{cancelable:true}));await settle()
 expect(firmwareApi.upload).toHaveBeenCalledTimes(1);expect(firmwareApi.createVersion).toHaveBeenCalledTimes(2);expect(root.querySelector('form')).toBeNull()
})
it('does not persist a version after failed signature validation and retains the selected file for retry',async()=>{
 enableUploads();vi.mocked(firmwareApi.upload).mockRejectedValueOnce(new Error('签名校验失败'))
 const {root}=mount();await settle();await prepareUpload(root)
 root.querySelector('form')!.dispatchEvent(new Event('submit',{cancelable:true}));await settle()
 expect(firmwareApi.createVersion).not.toHaveBeenCalled();expect(root.textContent).toContain('签名校验失败');expect(root.textContent).toContain('已选 3 字节')
})
it('rejects files above the configured limit before sending the upload',async()=>{
 enableUploads(2);const {root}=mount();await settle();await prepareUpload(root)
 root.querySelector('form')!.dispatchEvent(new Event('submit',{cancelable:true}));await settle()
 expect(firmwareApi.upload).not.toHaveBeenCalled();expect(root.textContent).toContain('固件文件超过允许上传大小')
})
it('generates temporary links only on request for managed files',async()=>{
 vi.mocked(firmwareApi.versions).mockResolvedValue({items:[{...version,artifact:{...version.artifact,url:null,fileId:'file-one'}}],total:1,page:1,size:20})
 vi.mocked(firmwareApi.download).mockResolvedValue({url:'https://files.example.com/signed',expiresAt:'2026-10-21T12:00:00Z'})
 const {root}=mount();await settle();expect(firmwareApi.download).not.toHaveBeenCalled();expect(root.textContent).toContain('上传时签名已验证')
 button(root,'生成临时下载链接')!.click();await settle();expect(firmwareApi.download).toHaveBeenCalledTimes(1)
 expect(root.querySelector('a')?.href).toBe('https://files.example.com/signed')
})
