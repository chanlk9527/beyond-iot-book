import { afterEach, expect, it, vi } from 'vitest'
import { managementUpload } from './management'

afterEach(()=>vi.unstubAllGlobals())
it('sends multipart bytes with fresh CSRF token and lets the browser set its boundary',async()=>{
 const fetch=vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({headerName:'X-CSRF-TOKEN',token:'fresh-token'}))).mockResolvedValueOnce(new Response(JSON.stringify({fileId:'file-one'})))
 vi.stubGlobal('fetch',fetch)
 const form=new FormData();form.append('file',new Blob([new Uint8Array([1,2,3])]),'firmware.bin');form.append('keyId','factory-key')
 await expect(managementUpload('/catalog/platform/products/p/firmware/channels/0/files',form)).resolves.toEqual({fileId:'file-one'})
 expect(fetch.mock.calls[0][0]).toBe('/api/management/auth/csrf')
 const [path,options]=fetch.mock.calls[1]
 expect(path).toBe('/api/management/catalog/platform/products/p/firmware/channels/0/files');expect(options.body).toBe(form)
 expect(options.headers['X-CSRF-TOKEN']).toBe('fresh-token');expect(options.headers['Content-Type']).toBeUndefined();expect(options.credentials).toBe('same-origin')
})
