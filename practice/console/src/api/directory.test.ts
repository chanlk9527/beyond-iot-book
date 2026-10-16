import {describe,it,expect,vi} from 'vitest'
import {categoryOptions,categoryLabel,directoryApi} from './directory'
import {request,managementMutation} from './management'
vi.mock('./management',()=>({request:vi.fn(),managementMutation:vi.fn()}))
describe('server-managed product directory',()=>{
 it('uses the same shared endpoint for platform and tenant callers',async()=>{
  const signal=new AbortController().signal
  for(const scope of ['/catalog/platform','/catalog/tenants/a','/catalog/tenants/b']){
   await directoryApi.get(scope,signal)
   expect(request).toHaveBeenLastCalledWith('/catalog/shared/categories',{signal})
  }
  const value={revision:1,items:[]}
  await directoryApi.save('/catalog/platform',value,signal)
  expect(managementMutation).toHaveBeenLastCalledWith('/catalog/shared/categories','PUT',value,signal)
 })
 it('uses the server tree to construct full category paths',()=>{
  expect(categoryOptions([{id:'feed',label:'喂养电器',children:[{id:'feed-warm',label:'暖奶器',children:[]}]}])).toEqual([{label:'喂养电器',value:'feed',children:[{label:'暖奶器',value:'feed-warm'}]}])
  expect(categoryOptions([])).toEqual([])
  expect(categoryLabel([{id:'feed',label:'喂养',children:[{id:'feed-warm',label:'新名称',children:[]}]}],'feed-warm')).toBe('喂养 / 新名称')
 })
})
