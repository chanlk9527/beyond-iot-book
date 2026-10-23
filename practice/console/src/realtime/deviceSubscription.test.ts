// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { effectScope, ref, nextTick } from 'vue'
import { ApiError, managementMutation } from '../api/management'
import { closeDeviceSubscriptions, useDeviceSubscription } from './deviceSubscription'
vi.mock('../api/management',async original=>({...await original<typeof import('../api/management')>(),managementMutation:vi.fn()}))
class Socket {
 static instances:Socket[]=[]
 onmessage:((event:{data:string})=>void)|undefined
 onclose:(()=>void)|undefined
 onerror:(()=>void)|undefined
 send=vi.fn()
 close=vi.fn(()=>this.onclose?.())
 constructor(public url:string){Socket.instances.push(this)}
 message(data:object){this.onmessage?.({data:JSON.stringify({protocol:'nebula.subscription.v1',...data})})}
}
const scopes:ReturnType<typeof effectScope>[]=[]
const tick=async()=>{await nextTick();await Promise.resolve();await Promise.resolve()}
function subscribe(receive=vi.fn()){
 const resources=ref([{tenantId:'ta',deviceId:'d1'}]);const scope=effectScope();scopes.push(scope)
 scope.run(()=>useDeviceSubscription(()=>resources.value,receive));return {resources,receive}
}
beforeEach(()=>{vi.useFakeTimers();vi.stubGlobal('WebSocket',Socket);Socket.instances=[];vi.mocked(managementMutation).mockReset();vi.mocked(managementMutation).mockResolvedValue({ticket:'one-time'})})
afterEach(()=>{scopes.splice(0).forEach(s=>s.stop());closeDeviceSubscriptions();vi.useRealTimers();vi.unstubAllGlobals()})
it('shares the socket, resyncs after ready and routes only matching resource notices',async()=>{
 const a=subscribe(),b=subscribe();await tick()
 expect(managementMutation).toHaveBeenCalledTimes(1);expect(Socket.instances).toHaveLength(1)
 const ws=Socket.instances[0]!;expect(ws.url).toContain('ticket=one-time')
 ws.message({type:'ready'});expect(a.receive).toHaveBeenLastCalledWith({type:'resync'});expect(b.receive).toHaveBeenCalledTimes(1)
 ws.message({type:'invalidate',kind:'shadow',tenantId:'tb',deviceId:'d1'});expect(a.receive).toHaveBeenCalledTimes(1)
 ws.message({type:'invalidate',kind:'shadow',tenantId:'ta',deviceId:'d1'});expect(a.receive).toHaveBeenCalledTimes(2)
 ws.message({type:'ping'});expect(ws.send).toHaveBeenCalledWith('pong')
})
it('reconnects with a fresh ticket and requests a new snapshot',async()=>{
 const {receive}=subscribe();await tick();Socket.instances[0]!.close()
 expect(receive).toHaveBeenLastCalledWith({type:'disconnected'})
 vi.mocked(managementMutation).mockResolvedValue({ticket:'replacement'})
 await vi.advanceTimersByTimeAsync(1500);await tick()
 expect(Socket.instances).toHaveLength(2);expect(Socket.instances[1]!.url).toContain('replacement')
 Socket.instances[1]!.message({type:'ready'});expect(receive).toHaveBeenLastCalledWith({type:'resync'})
})
it('replaces resources on navigation and cancels a pending ticket on disposal',async()=>{
 const {resources}=subscribe();await tick();resources.value=[{tenantId:'tb',deviceId:'d2'}];await tick()
 expect(Socket.instances[0]!.close).toHaveBeenCalled()
 expect(managementMutation).toHaveBeenLastCalledWith('/realtime/tickets','POST',{devices:[{tenantId:'tb',deviceId:'d2'}]},expect.any(AbortSignal))
 closeDeviceSubscriptions();await tick();expect(Socket.instances[1]!.close).toHaveBeenCalled()
})
it('stops automatic retry when subscription authorization is denied',async()=>{
 vi.mocked(managementMutation).mockRejectedValue(new ApiError(403,'denied'));const {receive}=subscribe();await tick()
 expect(receive).toHaveBeenLastCalledWith({type:'denied'})
 await vi.advanceTimersByTimeAsync(60000);expect(managementMutation).toHaveBeenCalledTimes(1);expect(Socket.instances).toHaveLength(0)
})
it('can subscribe again after login clears the previous session connections',async()=>{
 const {resources,receive}=subscribe();await tick();closeDeviceSubscriptions()
 resources.value=[];await tick();resources.value=[{tenantId:'ta',deviceId:'d2'}];await tick()
 expect(managementMutation).toHaveBeenLastCalledWith('/realtime/tickets','POST',{devices:[{tenantId:'ta',deviceId:'d2'}]},expect.any(AbortSignal))
 Socket.instances.at(-1)!.message({type:'ready'});expect(receive).toHaveBeenLastCalledWith({type:'resync'})
})
