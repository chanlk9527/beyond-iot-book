// @vitest-environment happy-dom
import {createApp} from 'vue'
import {expect,it} from 'vitest'
import FirmwareSummary from './FirmwareSummary.vue'
it('distinguishes unreported data, failures, old reports and multiple component versions',()=>{
 const root=document.createElement('div')
 let app=createApp(FirmwareSummary,{});app.mount(root);expect(root.textContent).toContain('尚未上报');app.unmount()
 app=createApp(FirmwareSummary,{failed:true});app.mount(root);expect(root.textContent).toContain('固件读取失败');expect(root.textContent).not.toContain('尚未上报');app.unmount()
 app=createApp(FirmwareSummary,{inventory:{revision:1,information:null,checkedAt:'2026-10-23T00:00:00Z',maximumReportAgeSeconds:86400,components:[{component:'main',version:'1.9.0',source:'ota.version.report',occurredAt:'2026-10-23T00:00:00Z',receivedAt:'2026-10-23T00:00:00Z',stale:true,beforeCurrentBoot:false},{component:'mcu',version:'2.0',source:'ota.version.report',occurredAt:'2026-10-23T00:00:00Z',receivedAt:'2026-10-23T00:00:00Z',stale:false,beforeCurrentBoot:true}]}})
 app.mount(root);expect(root.textContent).toContain('main 1.9.0');expect(root.textContent).toContain('mcu 2.0');expect(root.textContent).toContain('已过期');expect(root.textContent).toContain('需重新上报');app.unmount()
})
