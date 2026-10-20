import {describe,it,expect} from 'vitest'
import {initialValue,validateSchema,schemaSummary} from './modelSchema'
describe('model schema',()=>{
 it('validates enums and chooses a wire code',()=>{
  const schema={type:'ENUM',enumValues:{heat:'加热',warm:'保温'}}
  expect(()=>validateSchema(schema)).not.toThrow()
  expect(initialValue(schema)).toBe('heat')
  expect(schemaSummary(schema)).toContain('heat = 加热')
  expect(()=>validateSchema({type:'ENUM',enumValues:{}})).toThrow()
  expect(()=>validateSchema({type:'ENUM',enumValues:{heat:''}})).toThrow()
 })
 it('rejects incompatible constraints and preserves scalar defaults',()=>{
  expect(()=>validateSchema({type:'DATETIME',minimum:1})).toThrow()
  expect(()=>validateSchema({type:'INTEGER',minimum:0.5})).toThrow()
  expect(()=>validateSchema({type:'BOOLEAN',maxLength:1})).toThrow()
  expect(initialValue({type:'BOOLEAN'})).toBe(false)
  expect(initialValue({type:'ARRAY',items:{type:'TEXT'}})).toEqual([])
  expect(()=>validateSchema({type:'ARRAY',items:{type:'INTEGER'},minItems:3,maxItems:2})).toThrow()
  expect(()=>validateSchema({type:'ARRAY',items:{type:'ARRAY'}})).toThrow()
  expect(()=>validateSchema({type:'ARRAY',items:{type:'ENUM',enumValues:{heat:'加热'}},maxItems:10})).not.toThrow()
  expect(schemaSummary({type:'DECIMAL',minimum:35,maximum:55,unit:'℃'})).toContain('℃')
 })
})