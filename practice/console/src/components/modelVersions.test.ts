import {expect,it} from 'vitest'
import {emptyModel,modelDiff,stableModel} from './modelVersions'
import type {ModelSpec} from '../api/catalog'
const empty=():ModelSpec=>({properties:{},operations:{},events:{}})
it('compares model objects independently of JSON key order',()=>{
 expect(stableModel({properties:{a:1,b:2},events:{}})).toBe(stableModel({events:{},properties:{b:2,a:1}}))
})
it('distinguishes added, removed and modified definitions in a publish review',()=>{
 const before={...empty(),properties:{power:{type:'BOOLEAN'},old:{type:'TEXT'}}}
 const after={...empty(),properties:{power:{type:'INTEGER'},temperature:{type:'DECIMAL'}}}
 expect(modelDiff(before,after)[0]).toEqual({group:'properties',added:['temperature'],removed:['old'],changed:['power']})
 expect(emptyModel(after)).toBe(false);expect(emptyModel(empty())).toBe(true)
})
