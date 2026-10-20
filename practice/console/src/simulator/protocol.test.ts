import {describe,expect,it} from 'vitest'
import {SimulatedDevice,validateValue,type Envelope} from './protocol'
import type {ModelSpec} from '../api/catalog'

const model:ModelSpec={properties:{temperature:{id:'temperature',access:'READ_WRITE',schema:{type:'DECIMAL',minimum:0,maximum:100}},secret:{id:'secret',access:'WRITE_ONLY',schema:{type:'TEXT'}}},events:{},operations:{heat:{id:'heat',inputs:{target:{id:'target',required:true,schema:{type:'INTEGER',minimum:1,maximum:100}}}}}}
function request(method:string,data:Record<string,unknown>,extra:Partial<Envelope>={}):Envelope{return {version:'1.0',messageId:'down-1',method,timestamp:Date.now(),modelVersion:'v1',expiresAt:Date.now()+30000,data,...extra}}
describe('simulated device protocol',()=>{
 it('previews editable replies without executing or caching a command',()=>{
  const device=new SimulatedDevice(model,'v1'),down=request('thing.property.write',{properties:{temperature:40}})
  expect(device.preview(down).data).toEqual({properties:{temperature:40}});expect(device.values).toEqual({})
  expect(device.respondCustom(down,{result:{code:'BUSY',message:'正在加热'},data:{}}).result).toEqual({code:'BUSY',message:'正在加热'});expect(device.values).toEqual({})
 })
 it('sends edited property results with fixed correlation and updates the simulated actual value',()=>{
  const device=new SimulatedDevice(model,'v1'),down=request('thing.property.write',{properties:{temperature:40}})
  const reply=device.respondCustom(down,{result:{code:'SUCCESS',message:'已调整'},data:{properties:{temperature:39}}})
  expect(reply.replyTo).toBe(down.messageId);expect(reply.method).toBe('thing.property.write.reply');expect(device.values.temperature).toBe(39)
  expect(()=>device.respondCustom(down,{result:{code:'SUCCESS'},data:{properties:{temperature:38}}})).toThrow('原应答内容')
 })
 it('rejects malformed, unrelated and out-of-model custom replies without changing state',()=>{
  const device=new SimulatedDevice(model,'v1'),down=request('thing.property.write',{properties:{temperature:40}})
  for(const body of [null,{result:{code:'SUCCESS'},data:{properties:{temperature:101}}},{result:{code:'SUCCESS'},data:{properties:{other:40}}},{result:{code:'BUSY'},data:{properties:{temperature:40}}},{replyTo:'other',result:{code:'SUCCESS'},data:{properties:{temperature:40}}}])expect(()=>device.respondCustom(down,body)).toThrow()
  expect(device.values).toEqual({})
 })
 it('requires matching command IDs and consistent execution states in edited replies',()=>{
  const device=new SimulatedDevice(model,'v1'),down=request('thing.command.invoke',{commandId:'c1',operationId:'heat',inputs:{target:40}})
  expect(()=>device.respondCustom(down,{result:{code:'BUSY'},data:{commandId:'other',status:'FAILED'}})).toThrow('commandId')
  expect(()=>device.respondCustom(down,{result:{code:'SUCCESS'},data:{commandId:'c1',status:'FAILED'}})).toThrow('SUCCEEDED')
  expect(device.respondCustom(down,{result:{code:'BUSY',message:'忙'},data:{commandId:'c1',status:'FAILED'}}).data.status).toBe('FAILED')
 })
 it('applies a valid property group atomically and refuses an invalid group',()=>{
  const device=new SimulatedDevice(model,'v1')
  const reply=device.respond(request('thing.property.write',{properties:{temperature:40}}))
  expect(reply.method).toBe('thing.property.write.reply');expect(reply.replyTo).toBe('down-1');expect(reply.result?.code).toBe('SUCCESS');expect(device.values.temperature).toBe(40)
  const rejected=device.respond(request('thing.property.write',{properties:{temperature:50,unknown:1}},{messageId:'bad'}))
  expect(rejected.result?.code).toBe('INVALID_ARGUMENT');expect(device.values.temperature).toBe(40)
 })
 it('does not execute expired, mismatched, or intentionally failed requests',()=>{
  for(const [extra,success,code] of [[{expiresAt:Date.now()-1},true,'EXPIRED'],[{modelVersion:'v2'},true,'MODEL_VERSION_MISMATCH'],[{},false,'BUSY']] as const){
   const device=new SimulatedDevice(model,'v1'),reply=device.respond(request('thing.property.write',{properties:{temperature:60}},extra),success)
   expect(reply.result?.code).toBe(code);expect(device.values).toEqual({});expect(reply.data).toEqual({})
  }
 })
 it('correlates command results, checks inputs, and preserves duplicate outcomes',()=>{
  const device=new SimulatedDevice(model,'v1')
  const down=request('thing.command.invoke',{commandId:'c1',operationId:'heat',inputs:{target:40}})
  const reply=device.respond(down)
  expect(reply.method).toBe('thing.command.reply');expect(reply.data).toEqual({commandId:'c1',status:'SUCCEEDED'})
  expect(device.respond(down,false)).toBe(reply)
  expect(()=>device.respond({...down,data:{...down.data,inputs:{target:70}}})).toThrow('相同消息 ID')
  const duplicate=device.respond({...down,messageId:'down-2'},false)
  expect(duplicate.data.status).toBe('SUCCEEDED')
  const bad=device.respond(request('thing.command.invoke',{commandId:'c2',operationId:'heat',inputs:{}},{messageId:'bad'}))
  expect(bad.data.status).toBe('FAILED');expect(bad.result?.code).toBe('INVALID_ARGUMENT')
 })
 it('does not expose write-only properties and does not invent readings',()=>{
  const device=new SimulatedDevice(model,'v1');device.values.secret='hidden'
  expect(device.respond(request('thing.property.read',{properties:['secret']})).result?.code).toBe('NOT_FOUND')
  expect(device.respond(request('thing.property.read',{properties:['temperature']},{messageId:'missing'})).result?.code).toBe('NOT_FOUND')
 })
 it('checks enum, scalar, array and safe integer constraints before reporting',()=>{
  expect(validateValue({type:'ARRAY',items:{type:'ENUM',enumValues:{ok:'正常'}},minItems:1,maxItems:2},['ok'])).toBe(true)
  expect(validateValue({type:'ARRAY',items:{type:'BOOLEAN'},minItems:1},[])).toBe(false)
  expect(validateValue({type:'INTEGER'},Number.MAX_SAFE_INTEGER+1)).toBe(false)
  expect(validateValue({type:'DECIMAL',maximum:10},11)).toBe(false)
  expect(validateValue({type:'TEXT',maxLength:1},'温度')).toBe(false)
 })
})
