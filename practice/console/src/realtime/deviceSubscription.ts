import { onScopeDispose, watch, type WatchSource } from 'vue'
import { managementMutation, request, ApiError } from '../api/management'

export interface DeviceResource { tenantId:string|null; deviceId:string }
export interface DeviceNotice { type:'invalidate'|'resync'|'disconnected'; kind?:'shadow'|'presence'; deviceId?:string; tenantId?:string }
interface Listener { resources:DeviceResource[]; receive:(notice:DeviceNotice)=>void }
const listeners = new Set<Listener>()
let socket:WebSocket|undefined, retry:ReturnType<typeof setTimeout>|undefined, watchdog:ReturnType<typeof setTimeout>|undefined
let generation=0, attempt=0, scheduled=false, controller:AbortController|undefined
const key=(r:DeviceResource)=>JSON.stringify([r.tenantId,r.deviceId])
function stop() {
  generation++; controller?.abort(); controller=undefined
  if(retry)clearTimeout(retry)
  if(watchdog)clearTimeout(watchdog)
  retry=undefined;watchdog=undefined
  const old=socket;socket=undefined;old?.close()
}
function schedule() {
  if(scheduled)return
  scheduled=true
  queueMicrotask(()=>{scheduled=false;stop();attempt=0;void connect()})
}
async function connect() {
  const devices=[...new Map([...listeners].flatMap(l=>l.resources).map(r=>[key(r),r])).values()]
  if(!devices.length||typeof WebSocket==='undefined')return
  const current=generation
  controller=new AbortController()
  const signal=controller.signal
  const reconnect=()=>{
    if(current!==generation||retry)return
    if(watchdog)clearTimeout(watchdog)
    socket=undefined
    for(const listener of listeners)listener.receive({type:'disconnected'})
    retry=setTimeout(()=>{retry=undefined;void connect()},Math.min(30000,1000*2**Math.min(attempt++,5))*(0.8+Math.random()*0.4))
  }
  const alive=()=>{
    if(watchdog)clearTimeout(watchdog)
    watchdog=setTimeout(()=>ws.close(),45000)
  }
  let ws:WebSocket
  try {
    const grant=await managementMutation<{ticket:string}>('/realtime/tickets','POST',{devices},signal)
    if(current!==generation)return
    const url=new URL('/api/management/realtime/socket',window.location.href)
    url.protocol=url.protocol==='https:'?'wss:':'ws:';url.searchParams.set('ticket',grant.ticket)
    ws=new WebSocket(url.toString());socket=ws;alive()
    ws.onmessage=event=>{
      if(current!==generation)return
      try {
        const message=JSON.parse(String(event.data))
        if(message.protocol!=='nebula.subscription.v1')return
        alive()
        if(message.type==='ping'){ws.send('pong');return}
        if(message.type==='ready'){
          attempt=0
          for(const listener of listeners)listener.receive({type:'resync'})
        } else if(message.type==='invalidate'){
          for(const listener of listeners)if(listener.resources.some(r=>r.deviceId===message.deviceId&&(r.tenantId??'platform')===message.tenantId))listener.receive(message)
        }
      } catch { ws.close() }
    }
    ws.onclose=reconnect
    ws.onerror=()=>ws.close()
  } catch(error) {
    if(signal.aborted)return
    // A denied subscription needs a permission/session change, not an endless retry loop.
    if(error instanceof ApiError&&[400,401,403,404].includes(error.status))return
    reconnect()
  }
}
/** One shared socket per browser context; resources are rebuilt on navigation and pagination. */
export function useDeviceSubscription(resources:WatchSource<DeviceResource[]>, receive:(notice:DeviceNotice)=>void) {
  const listener:Listener={resources:[],receive}
  listeners.add(listener)
  watch(resources,value=>{listener.resources=value;schedule()},{immediate:true,deep:true})
  onScopeDispose(()=>{listeners.delete(listener);schedule()})
}
export function closeDeviceSubscriptions() { listeners.clear();stop() }
export const readPresence=(resource:DeviceResource,signal:AbortSignal)=>request<{state:'ONLINE'|'OFFLINE'|'UNKNOWN'}>(
  `/realtime/presence/${encodeURIComponent(resource.deviceId)}${resource.tenantId?'?'+new URLSearchParams({tenantId:resource.tenantId}):''}`,{signal})
