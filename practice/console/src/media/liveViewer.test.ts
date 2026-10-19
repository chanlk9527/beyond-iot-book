// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { liveViewer } from './liveViewer'
import { videoApi } from '../api/video'
vi.mock('../api/video', () => ({ videoApi: { create: vi.fn(), send: vi.fn(), receive: vi.fn(), close: vi.fn() } }))
class Peer {
  static current: Peer
  remoteDescription: unknown
  localDescription: unknown
  connectionState = 'new'
  ontrack: ((e: unknown) => void) | null = null
  onconnectionstatechange: (() => void) | null = null
  onicecandidate: ((e: unknown) => void) | null = null
  addTransceiver = vi.fn()
  createOffer = vi.fn(async () => ({ type: 'offer', sdp: 'receive-only-sdp' }))
  setLocalDescription = vi.fn(async (offer: unknown) => { this.localDescription = offer })
  setRemoteDescription = vi.fn(async (answer: unknown) => { this.remoteDescription = answer })
  addIceCandidate = vi.fn(async () => {})
  close = vi.fn()
  constructor() { Peer.current = this }
}
const status = vi.fn(), failed = vi.fn()
let viewer: ReturnType<typeof liveViewer> | undefined
const session = () => ({ sessionId: 'opaque-id', protocol: 'webrtc' as const, expiresAt: new Date(Date.now()+60_000).toISOString(), iceServers: [] })
async function settle() { for (let i=0;i<20;i++) await Promise.resolve() }
function start() {
  viewer = liveViewer({ tenantId:'ta', deviceId:'d1', channelId:'main', video: { srcObject: null } as unknown as HTMLVideoElement, status, failed })
}
beforeEach(() => {
  vi.useFakeTimers(); vi.clearAllMocks(); vi.stubGlobal('RTCPeerConnection',Peer)
  vi.stubGlobal('MediaStream',class { addTrack = vi.fn() })
  vi.mocked(videoApi.create).mockResolvedValue(session()); vi.mocked(videoApi.send).mockResolvedValue()
  vi.mocked(videoApi.receive).mockResolvedValue([]); vi.mocked(videoApi.close).mockResolvedValue()
})
afterEach(() => { viewer?.stop(); vi.unstubAllGlobals(); vi.useRealTimers() })
it('negotiates receive-only tracks and buffers remote ICE until the answer arrives',async () => {
  vi.mocked(videoApi.receive).mockResolvedValueOnce([
    { type:'ICE',payload:JSON.stringify({candidate:'remote-candidate'}) },
    { type:'ANSWER',payload:JSON.stringify({type:'answer',sdp:'remote-sdp'}) },
  ])
  start(); await settle()
  expect(Peer.current.addTransceiver.mock.calls).toEqual([['video',{direction:'recvonly'}],['audio',{direction:'recvonly'}]])
  expect(videoApi.send).toHaveBeenCalledWith('opaque-id',{type:'OFFER',payload:JSON.stringify({type:'offer',sdp:'receive-only-sdp'})},expect.any(AbortSignal))
  expect(Peer.current.addIceCandidate).toHaveBeenCalledWith({candidate:'remote-candidate'})
  viewer?.stop(); expect(Peer.current.close).toHaveBeenCalled(); expect(videoApi.close).toHaveBeenCalledWith('opaque-id')
})
it('releases a session whose create response arrives after the panel was closed',async () => {
  let resolve!: (value: ReturnType<typeof session>) => void
  vi.mocked(videoApi.create).mockImplementation(() => new Promise(r => {resolve=r}))
  start(); viewer?.stop(); resolve(session()); await settle()
  expect(videoApi.close).toHaveBeenCalledWith('opaque-id'); expect(videoApi.send).not.toHaveBeenCalled()
})
it('closes the peer when authorization is revoked during polling',async () => {
  vi.mocked(videoApi.receive).mockRejectedValueOnce(new Error('permission revoked'))
  start(); await settle()
  expect(failed).toHaveBeenCalledWith('permission revoked'); expect(Peer.current.close).toHaveBeenCalled()
})
it('expires locally and releases the server session',async () => {
  start(); await settle(); Peer.current.connectionState = 'connected'; Peer.current.onconnectionstatechange?.(); await vi.advanceTimersByTimeAsync(60_001)
  expect(failed).toHaveBeenCalledWith('本次观看已到期，请重新连接。')
  expect(Peer.current.close).toHaveBeenCalled(); expect(videoApi.close).toHaveBeenCalledWith('opaque-id')
})
