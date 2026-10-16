import { describe,expect,it,vi } from 'vitest'
import { catalogScope,catalogApi,defaultProductConfiguration,type Product } from './catalog'
import {managementMutation,request} from './management'
vi.mock('./management',()=>({request:vi.fn(),managementMutation:vi.fn()}))
describe('product configuration',()=>{
 it('lets the server allocate the version and sends explicit empty-model confirmation',async()=>{
  const signal=new AbortController().signal
  await catalogApi.publish('/catalog/platform',{id:'p',tenantId:'ta',revision:7} as Product,true,signal)
  expect(managementMutation).toHaveBeenLastCalledWith('/catalog/tenants/ta/products/p/publish','POST',{revision:7,confirmEmpty:true},signal)
 })
 it('routes writes from global results to the actual tenant',async()=>{
  const signal=new AbortController().signal
  await catalogApi.remove('/catalog/platform',{id:'p',tenantId:'tenant/a',revision:2} as Product,signal)
  expect(managementMutation).toHaveBeenLastCalledWith('/catalog/tenants/tenant%2Fa/products/p?revision=2','DELETE',undefined,signal)
 })
 it('deletes products in their scope with revision protection',async()=>{
  const signal=new AbortController().signal
  await catalogApi.remove('/catalog/tenants/tenant-a',{id:'product/a',revision:7} as Product,signal)
  expect(managementMutation).toHaveBeenLastCalledWith('/catalog/tenants/tenant-a/products/product%2Fa?revision=7','DELETE',undefined,signal)
 })
 it('filters embedded device lists by product on the server',async()=>{
  const signal=new AbortController().signal
  await catalogApi.devices('/catalog/platform','warmer',2,signal,'product/a')
  const [url,options]=vi.mocked(request).mock.calls.at(-1)!
  const parsed=new URL(url,'http://localhost')
  expect(parsed.searchParams.get('productId')).toBe('product/a')
  expect(parsed.searchParams.get('page')).toBe('2')
  expect(options?.signal).toBe(signal)
 })
 it('creates independent defaults matching the supported protocol',()=>{
  const first=defaultProductConfiguration(),second=defaultProductConfiguration()
  first.category='喂养电器 / 暖奶器'
  expect(second.category).toBe('')
  expect(second.messageProtocol).toBe('nebula-mqtt-v1')
 })
 it('saves configuration and model together with the revision',async()=>{
  const configuration={...defaultProductConfiguration(),manufacturer:'Nebula',networkWay:'WIFI' as const}
  const draft={properties:{},events:{},operations:{}}
  const p:Product={id:'warmer',tenantId:null,code:'warmer',name:'Warmer',description:'',enabled:true,latestVersion:null,revision:7,draft,configuration}
  const signal=new AbortController().signal
  await catalogApi.save('/catalog/platform',p,draft,signal)
  expect(managementMutation).toHaveBeenLastCalledWith('/catalog/platform/products/warmer/draft','PUT',{name:'Warmer',description:'',model:draft,configuration,revision:7},signal)
 })
})
describe('explicit catalog scope',()=>{
 it('distinguishes null-tenant platform data from a tenant route',()=>{
  expect(catalogScope(true,undefined)).toBe('/catalog/platform')
  expect(catalogScope(false,'tenant-a')).toBe('/catalog/tenants/tenant-a')
 })
 it('never treats an omitted tenant as all tenants or as platform data',()=>{
  expect(()=>catalogScope(false,undefined)).toThrow()
  expect(()=>catalogScope(false,'')).toThrow()
  expect(()=>catalogScope(true,'tenant-a')).toThrow()
 })
 it('escapes tenant identifiers into one path segment',()=>{
  expect(catalogScope(false,'a/b')).toBe('/catalog/tenants/a%2Fb')
 })
})
