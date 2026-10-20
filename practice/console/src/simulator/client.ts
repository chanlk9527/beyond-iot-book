import mqtt, { type MqttClient } from 'mqtt'
import type { Credential } from '../api/catalog'
import type { Envelope } from './protocol'

export interface Trace { time:number; direction:'上行'|'下行'|'连接'; topic:string; body:string; status:string }
export class SimulatorClient {
 private client?:MqttClient
 private stopped=false
 private pending=new Set<(error:Error)=>void>()
 constructor(private trace:(row:Trace)=>void,private state:(state:'connecting'|'connected'|'disconnected')=>void,private receive:(message:Envelope)=>void){}
 start(url:string,credential:Credential){
  this.stopped=false;this.state('connecting')
  const client=mqtt.connect(url,{protocolVersion:5,clientId:credential.clientId,username:credential.username,password:credential.secret,
   clean:true,reconnectPeriod:0,connectTimeout:8000,keepalive:30,resubscribe:false,queueQoSZero:false,
   properties:{sessionExpiryInterval:0,receiveMaximum:16,maximumPacketSize:65536,topicAliasMaximum:0}})
  this.client=client
  const current=()=>!this.stopped&&this.client===client
  client.on('connect',()=>{if(!current())return;client.options.password=undefined;this.state('connected');this.log('连接','','','已连接 MQTT 5')})
  client.on('error',error=>{if(current()){this.log('连接','','',error.message);this.stop()}})
  client.on('disconnect',packet=>{if(current())this.log('连接','','',`服务端断开，原因码 ${packet.reasonCode}`)})
  client.on('close',()=>{if(current()){this.state('disconnected');this.log('连接','','','连接已关闭');this.stop()}})
  client.on('message',(topic,payload)=>{
   if(!current())return
   const body=payload.toString();this.log('下行',topic,body,'已接收（传输确认不代表执行）')
   if(topic!=='nebula/v1/down')return
   try{const message=JSON.parse(body) as Envelope;if(message&&typeof message.method==='string'&&typeof message.messageId==='string'&&message.data&&typeof message.data==='object')this.receive(message);else throw Error('报文格式无效')}
   catch{this.log('下行',topic,'','无法处理下行报文')}
  })
 }
 publish(message:Envelope):Promise<void>{
  const client=this.client
  if(!client?.connected||this.stopped)return Promise.reject(Error('请先连接模拟设备'))
  const body=JSON.stringify(message)
  if(new TextEncoder().encode(body).length>64000)return Promise.reject(Error('报文超过 64 KB 限制'))
  this.log('上行','nebula/v1/up',body,'等待 PUBACK')
  return new Promise((resolve,reject)=>{
   let done=false
   const finish=(error?:Error|null)=>{if(done)return;done=true;clearTimeout(timer);this.pending.delete(finish);if(!this.stopped)this.log('上行','nebula/v1/up',body,error?error.message:'PUBACK 成功（不代表业务持久化）');if(error)reject(error);else resolve()}
   const timer=setTimeout(()=>finish(Error('PUBACK 超时，结果未知')),10000)
   this.pending.add(finish)
   client.publish('nebula/v1/up',body,{qos:1,retain:false},error=>finish(error))
  })
 }
 stop(){
  if(this.stopped)return
  this.stopped=true
  for(const finish of this.pending)finish(Error('连接已断开，上报结果未知'))
  const client=this.client;this.client=undefined
  if(client){client.options.password=undefined;client.end(true)}
  this.state('disconnected')
 }
 private log(direction:Trace['direction'],topic:string,body:string,status:string){this.trace({time:Date.now(),direction,topic,body,status})}
}
