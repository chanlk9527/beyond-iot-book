import {describe,it,expect} from 'vitest'
import {defaultProductConfiguration,type Product} from '../api/catalog'
import {productChanges,productTabPayload} from './productTabs'
function fixture():Product{return {id:'p1',tenantId:null,code:'warmer',name:'Warmer',description:'',revision:4,enabled:true,latestVersion:'v1',draft:{properties:{},events:{},operations:{}},configuration:defaultProductConfiguration()}}
describe('independent product tab saves',()=>{
 it('saves basic information without access or model edits',()=>{
  const saved=fixture(),editing=fixture();editing.name='New';editing.configuration.category='喂养电器 / 暖奶器';editing.configuration.networkWay='WIFI'
  const payload=productTabPayload(saved,editing,'invalid JSON','basic')
  expect(payload.name).toBe('New');expect(payload.configuration.category).toBe('喂养电器 / 暖奶器');expect(payload.configuration.networkWay).toBe('OTHER');expect(payload.draft).toEqual(saved.draft);expect(payload.revision).toBe(4)
 })
 it('saves access settings without basic information or invalid model edits',()=>{
  const saved=fixture(),editing=fixture();editing.name='Unsaved';editing.configuration.manufacturer='Unsaved';editing.configuration.deviceType='GATEWAY'
  const payload=productTabPayload(saved,editing,'invalid','access')
  expect(payload.name).toBe('Warmer');expect(payload.configuration.manufacturer).toBe('');expect(payload.configuration.deviceType).toBe('GATEWAY')
  expect(saved.configuration.deviceType).toBe('DIRECT')
 })
 it('saves the model with the latest server revision while retaining other server fields',()=>{
  const saved=fixture(),editing=fixture();saved.revision=8;editing.name='Unsaved';editing.configuration.networkWay='WIFI'
  const model={properties:{power:{id:'power'}},events:{},operations:{}}
  const payload=productTabPayload(saved,editing,JSON.stringify(model),'model')
  expect(payload.revision).toBe(8);expect(payload.name).toBe('Warmer');expect(payload.configuration.networkWay).toBe('OTHER');expect(payload.draft).toEqual(model)
 })
 it('tracks dirty tabs independently and ignores JSON whitespace',()=>{
  const saved=fixture(),editing=fixture();const json=JSON.stringify(saved.draft,null,4)
  expect(productChanges(saved,editing,json)).toEqual({basic:false,access:false,model:false})
  editing.configuration.category='喂养电器 / 暖奶器';editing.configuration.networkWay='WIFI'
  expect(productChanges(saved,editing,'{')).toEqual({basic:true,access:true,model:true})
 })
})
