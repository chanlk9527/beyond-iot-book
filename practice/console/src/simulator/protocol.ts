import type { ModelSpec, ShadowScalar } from '../api/catalog'
import type { ValueSchema } from '../components/modelSchema'

export interface Field { id:string; name?:string; schema:ValueSchema; access?:string; required?:boolean }
export interface ModelEvent { id:string; name?:string; fields:Record<string,Field> }
export interface Envelope {
 version:string; messageId:string; method:string; timestamp:number; data:Record<string,unknown>;
 modelVersion?:string; expiresAt?:number; replyTo?:string; result?:{code:string;message?:string}
}
export function envelope(method:string,data:Record<string,unknown>,extra:Partial<Envelope>={}):Envelope {
 return {version:'1.0',messageId:crypto.randomUUID(),method,timestamp:Date.now(),data,...extra}
}
export function validateValue(schema:ValueSchema,value:unknown):boolean {
 if(schema.type==='ARRAY')return Array.isArray(value)&&!!schema.items&&value.length>=(schema.minItems??0)&&value.length<=(schema.maxItems??1000)&&value.every(v=>validateValue(schema.items!,v))
 if(schema.type==='BOOLEAN')return typeof value==='boolean'
 if(schema.type==='TEXT')return typeof value==='string'&&[...value].length<=(schema.maxLength??4096)
 if(schema.type==='ENUM')return typeof value==='string'&&Object.hasOwn(schema.enumValues||{},value)
 if(!['INTEGER','DECIMAL'].includes(schema.type)||typeof value!=='number'||!Number.isFinite(value))return false
 return (schema.type!=='INTEGER'||Number.isSafeInteger(value))&&(schema.minimum==null||value>=schema.minimum)&&(schema.maximum==null||value<=schema.maximum)
}

/** A session-local device model. Operations acknowledge simulated execution without inventing physical effects. */
export class SimulatedDevice {
 readonly values:Record<string,ShadowScalar>={}
 private replies=new Map<string,{request:string;reply:Envelope}>()
 private commands=new Map<string,{request:string;code:string}>()
 constructor(readonly model:ModelSpec,readonly modelVersion:string){}
 preview(request:Envelope,success=true):Envelope {
  const copy=new SimulatedDevice(this.model,this.modelVersion)
  Object.assign(copy.values,this.values)
  return copy.respond(request,success)
 }
 respondCustom(request:Envelope,body:unknown):Envelope {
  const key=request.messageId+'|'+request.method,fingerprint=JSON.stringify(request)
  if(!body||typeof body!=='object'||Array.isArray(body))throw Error('应答必须是 JSON 对象')
  const value=body as {result?:{code?:unknown;message?:unknown};data?:Record<string,unknown>}
  if(Object.keys(body).some(k=>!['result','data'].includes(k)))throw Error('仅可编辑 result 和 data，关联标识由系统生成')
  const result=value.result,data=value.data
  if(!result||typeof result!=='object'||Array.isArray(result)||Object.keys(result).some(k=>!['code','message'].includes(k))||typeof result.code!=='string'||!/^SUCCESS$|^[A-Z][A-Z0-9_]{0,63}$/.test(result.code))throw Error('result.code 须为大写业务码，例如 SUCCESS 或 BUSY')
  if(result.message!==undefined&&(typeof result.message!=='string'||result.message.length>1000))throw Error('result.message 须为最多 1000 字符的说明')
  if(!data||typeof data!=='object'||Array.isArray(data))throw Error('data 必须是 JSON 对象')
  const success=result.code==='SUCCESS',operation=request.method==='thing.command.invoke'
  const trial=new SimulatedDevice(this.model,this.modelVersion)
  Object.assign(trial.values,this.values)
  if(success){
   if(operation){
    if(Object.keys(data).some(k=>!['commandId','status'].includes(k))||data.commandId!==request.data.commandId||data.status!=='SUCCEEDED')throw Error('操作成功应答须保留 commandId，status 为 SUCCEEDED')
   }else{
    const properties=data.properties
    if(Object.keys(data).some(k=>k!=='properties')||!properties||typeof properties!=='object'||Array.isArray(properties))throw Error('属性成功应答需要 data.properties 对象')
    const ids=request.method==='thing.property.write'?Object.keys(request.data.properties as object):request.data.properties as string[]
    const entries=Object.entries(properties),fields=this.model.properties as Record<string,Field>
    if(!Array.isArray(ids)||entries.length!==ids.length||entries.some(([id,v])=>!ids.includes(id)||!fields[id]||!validateValue(fields[id]!.schema,v)))throw Error('返回属性须与请求一致，且值符合物模型约束')
    Object.assign(trial.values,properties)
   }
   const checked=trial.respond(request,true)
   if(checked.result?.code!=='SUCCESS')throw Error('当前请求不能成功应答：'+checked.result?.code)
  }else if(operation){
   if(Object.keys(data).some(k=>!['commandId','status'].includes(k))||data.commandId!==request.data.commandId||data.status!=='FAILED')throw Error('操作失败应答须保留 commandId，status 为 FAILED')
  }else if(Object.keys(data).length)throw Error('属性失败应答的 data 必须为空对象')
  const cached=this.replies.get(key)
  if(cached){
   if(cached.request!==fingerprint)throw Error('相同消息 ID 的内容发生变化')
   if(JSON.stringify(cached.reply.data)!==JSON.stringify(data)||JSON.stringify(cached.reply.result)!==JSON.stringify(result))throw Error('此请求已有应答，重试须保持原应答内容')
   return cached.reply
  }
  if(operation){
   const previous=this.commands.get(String(request.data.commandId))
   const commandRequest=JSON.stringify([request.data.operationId,request.data.inputs])
   if(previous&&(previous.request!==commandRequest||previous.code!==result.code))throw Error('此命令已有不同结果，不能覆盖')
   this.remember(this.commands,String(request.data.commandId),{request:commandRequest,code:result.code})
  }else if(success&&request.method==='thing.property.write')Object.assign(this.values,data.properties)
  const reply=envelope(operation?'thing.command.reply':request.method+'.reply',data,{replyTo:request.messageId,result:{code:result.code,...(result.message!==undefined?{message:result.message as string}:{})}})
  this.remember(this.replies,key,{request:fingerprint,reply})
  return reply
 }
 respond(request:Envelope,success=true):Envelope {
  const key=request.messageId+'|'+request.method, fingerprint=JSON.stringify(request)
  const cached=this.replies.get(key)
  if(cached){if(cached.request!==fingerprint)throw Error('相同消息 ID 的内容发生变化');return cached.reply}
  const method=request.method==='thing.command.invoke'?'thing.command.reply':request.method+'.reply'
  let code='SUCCESS',data:Record<string,unknown>={}
  if(request.version!=='1.0'||!request.messageId||!Number.isFinite(request.expiresAt))code='INVALID_ARGUMENT'
  else if(request.modelVersion!==this.modelVersion)code='MODEL_VERSION_MISMATCH'
  else if(request.expiresAt!<=Date.now())code='EXPIRED'
  else if(!success)code='BUSY'
  else if(request.method==='thing.property.write'){
   const values=request.data.properties
   if(!values||typeof values!=='object'||Array.isArray(values)||!Object.keys(values).length)code='INVALID_ARGUMENT'
   else {
    const fields=this.model.properties as Record<string,Field>
    const entries=Object.entries(values)
    if(entries.some(([id,v])=>!fields[id]||!['READ_WRITE','WRITE_ONLY'].includes(fields[id]!.access||'')||!validateValue(fields[id]!.schema,v)))code='INVALID_ARGUMENT'
    else {Object.assign(this.values,values);data={properties:values}}
   }
  }else if(request.method==='thing.property.read'){
   const ids=request.data.properties,fields=this.model.properties as Record<string,Field>
   if(!Array.isArray(ids)||!ids.length||ids.some(id=>typeof id!=='string'||!fields[id]||fields[id]!.access==='WRITE_ONLY'||!Object.hasOwn(this.values,id)))code='NOT_FOUND'
   else data={properties:Object.fromEntries(ids.map(id=>[id,this.values[id]]))}
  }else if(request.method==='thing.command.invoke'){
   const operationId=request.data.operationId,inputs=request.data.inputs
   const operation=typeof operationId==='string'?this.model.operations[operationId] as {inputs:Record<string,Field>}|undefined:undefined
   if(!operation)code='UNSUPPORTED_METHOD'
   else if(!inputs||typeof inputs!=='object'||Array.isArray(inputs))code='INVALID_ARGUMENT'
   else if(Object.entries(operation.inputs).some(([id,f])=>f.required&&!Object.hasOwn(inputs,id))||Object.entries(inputs).some(([id,v])=>!operation.inputs[id]||!validateValue(operation.inputs[id]!.schema,v)))code='INVALID_ARGUMENT'
  }else code='UNSUPPORTED_METHOD'
  if(request.method==='thing.command.invoke'){
   if(typeof request.data.commandId!=='string'||!request.data.commandId)throw Error('下行缺少 commandId')
   const commandKey=request.data.commandId,commandRequest=JSON.stringify([request.data.operationId,request.data.inputs])
   const previous=this.commands.get(commandKey)
   if(previous){if(previous.request!==commandRequest)code='INVALID_ARGUMENT';else code=previous.code}
   else this.remember(this.commands,commandKey,{request:commandRequest,code})
   data={commandId:commandKey,status:code==='SUCCESS'?'SUCCEEDED':'FAILED'}
  }
  const reply=envelope(method,data,{replyTo:request.messageId,result:{code}})
  this.remember(this.replies,key,{request:fingerprint,reply})
  return reply
 }
 private remember<T>(map:Map<string,T>,key:string,value:T){map.set(key,value);if(map.size>256)map.delete(map.keys().next().value!)}
}
