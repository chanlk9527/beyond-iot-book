import {beforeEach,expect,it,vi} from 'vitest'
import {SimulatorClient,type Trace} from './client'
import {envelope} from './protocol'
import type {Credential} from '../api/catalog'
const mock=vi.hoisted(()=>({connect:vi.fn()}))
vi.mock('mqtt',()=>({default:{connect:mock.connect}}))
let handlers:Record<string,(...args:any[])=>void>,socket:any,rows:Trace[],state:ReturnType<typeof vi.fn>,receive:ReturnType<typeof vi.fn>
const credential={clientId:'device',username:'device',secret:'secret'} as Credential
beforeEach(()=>{
 handlers={};rows=[];state=vi.fn();receive=vi.fn()
 socket={options:{password:'secret'},connected:true,on:vi.fn((name,fn)=>{handlers[name]=fn}),publish:vi.fn((_topic,_body,_options,callback)=>callback(null)),end:vi.fn()}
 mock.connect.mockReturnValue(socket)
})
it('uses real MQTT 5 identity over WebSocket with no reconnect or subscription',async()=>{
 const client=new SimulatorClient(row=>rows.push(row),state,receive)
 client.start('ws://localhost/mqtt',credential);handlers.connect!()
 expect(mock.connect).toHaveBeenCalledWith('ws://localhost/mqtt',expect.objectContaining({protocolVersion:5,clientId:'device',username:'device',password:'secret',reconnectPeriod:0,resubscribe:false}))
 const message=envelope('thing.property.report',{properties:{power:true}})
 await client.publish(message)
 expect(socket.publish).toHaveBeenCalledWith('nebula/v1/up',JSON.stringify(message),{qos:1,retain:false},expect.any(Function))
 expect(rows.at(-1)?.status).toContain('PUBACK 成功');expect(rows.at(-1)?.status).toContain('不代表业务持久化')
 client.stop();expect(socket.end).toHaveBeenCalledWith(true);expect(socket.options.password).toBeUndefined()
})
it('shows failed PUBACKs as failures and accepts direct downlinks without subscriptions',async()=>{
 const client=new SimulatorClient(row=>rows.push(row),state,receive);client.start('ws://localhost/mqtt',credential)
 socket.publish.mockImplementation((_topic:any,_body:any,_options:any,callback:any)=>callback(Error('Publish error: Payload format invalid')))
 await expect(client.publish(envelope('thing.property.report',{}))).rejects.toThrow('Payload format invalid')
 expect(rows.at(-1)?.status).toContain('Payload format invalid')
 const message=envelope('thing.property.write',{properties:{power:false}})
 handlers.message!('nebula/v1/down',{toString:()=>JSON.stringify(message)})
 expect(receive).toHaveBeenCalledWith(message)
 client.stop();handlers.message!('nebula/v1/down',{toString:()=>JSON.stringify(message)})
 expect(receive).toHaveBeenCalledTimes(1)
})
it('ignores late callbacks from an old socket and cancels pending acknowledgements on stop',async()=>{
 const client=new SimulatorClient(row=>rows.push(row),state,receive);client.start('ws://localhost/mqtt',credential)
 const oldClose=handlers.close!
 client.stop();socket={...socket,options:{password:'secret'},end:vi.fn()};mock.connect.mockReturnValue(socket)
 client.start('ws://localhost/mqtt',credential)
 const stops=socket.end.mock.calls.length;oldClose();expect(socket.end).toHaveBeenCalledTimes(stops)
 socket.publish.mockImplementation(()=>{})
 const publishing=client.publish(envelope('thing.property.report',{properties:{power:true}}))
 const rejected=expect(publishing).rejects.toThrow('结果未知')
 client.stop();await rejected
})
