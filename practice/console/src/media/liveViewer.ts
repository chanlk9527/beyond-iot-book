import { videoApi } from '../api/video'
import { errorMessage } from '../api/management'

/** Native WebRTC only. The client has no provider SDK, resource identifier or signing key. */
export function liveViewer(options: {
  tenantId: string | null; deviceId: string; channelId: string
  video: HTMLVideoElement; status: (message: string) => void; failed: (message: string) => void
}) {
  const life = new AbortController()
  let peer: RTCPeerConnection | undefined, sessionId: string | undefined
  let timer: ReturnType<typeof setTimeout> | undefined, expiry: ReturnType<typeof setTimeout> | undefined
  let timeout: ReturnType<typeof setTimeout> | undefined
  let sending = Promise.resolve(), offered = false
  const localIce: RTCIceCandidateInit[] = [], remoteIce: RTCIceCandidateInit[] = []
  function stop() {
    life.abort(); clearTimeout(timer); clearTimeout(expiry); clearTimeout(timeout)
    peer?.close(); options.video.srcObject = null
    if (sessionId) { const id = sessionId; sessionId = undefined; void videoApi.close(id).catch(() => {}) }
  }
  function fail(error: unknown) {
    if (life.signal.aborted) return
    options.failed(errorMessage(error)); stop()
  }
  function sendIce(candidate: RTCIceCandidateInit) {
    sending = sending.then(async () => {
      if (!life.signal.aborted && sessionId) await videoApi.send(sessionId, { type: 'ICE', payload: JSON.stringify(candidate) }, life.signal)
    }).catch(fail)
  }
  async function poll() {
    if (life.signal.aborted || !peer || !sessionId) return
    try {
      for (const message of await videoApi.receive(sessionId, life.signal)) {
        if (life.signal.aborted) return
        if (message.payload.length > 16000) throw new Error('直播信令过大，请重新连接。')
        if (message.type === 'ANSWER') {
          await peer.setRemoteDescription(JSON.parse(message.payload) as RTCSessionDescriptionInit)
          for (const candidate of remoteIce.splice(0)) await peer.addIceCandidate(candidate)
        } else if (message.type === 'ICE') {
          const candidate = JSON.parse(message.payload) as RTCIceCandidateInit
          if (peer.remoteDescription) await peer.addIceCandidate(candidate)
          else {
            if (remoteIce.length >= 256) throw new Error('直播信令过多，请重新连接。')
            remoteIce.push(candidate)
          }
        }
      }
      if (!life.signal.aborted) timer = setTimeout(() => { void poll() }, 700)
    } catch (error) { fail(error) }
  }
  async function start() {
    try {
      options.status('正在连接')
      const session = await videoApi.create(options.tenantId, options.deviceId, options.channelId, life.signal)
      sessionId = session.sessionId
      if (life.signal.aborted) { const id = sessionId; sessionId = undefined; void videoApi.close(id).catch(() => {}); return }
      if (session.protocol !== 'webrtc') throw new Error('当前直播协议暂不支持。')
      const remaining = Date.parse(session.expiresAt) - Date.now()
      if (!Number.isFinite(remaining) || remaining <= 0) throw new Error('直播会话已过期，请重新连接。')
      expiry = setTimeout(() => fail(new Error('本次观看已到期，请重新连接。')), remaining)
      timeout = setTimeout(() => fail(new Error('设备未响应，请确认设备正在直播后重试。')), 25_000)
      peer = new RTCPeerConnection({ iceServers: session.iceServers })
      const stream = new MediaStream()
      options.video.srcObject = stream
      peer.ontrack = event => { stream.addTrack(event.track) }
      peer.onconnectionstatechange = () => {
        if (life.signal.aborted) return
        if (peer?.connectionState === 'connected') { clearTimeout(timeout); options.status('正在观看') }
        if (peer?.connectionState === 'failed' || peer?.connectionState === 'closed') fail(new Error('直播连接已断开，请重试。'))
      }
      peer.onicecandidate = event => {
        if (!event.candidate || life.signal.aborted) return
        if (offered) sendIce(event.candidate.toJSON())
        else localIce.push(event.candidate.toJSON())
      }
      peer.addTransceiver('video', { direction: 'recvonly' })
      peer.addTransceiver('audio', { direction: 'recvonly' })
      const offer = await peer.createOffer()
      await peer.setLocalDescription(offer)
      if (life.signal.aborted) return
      await videoApi.send(sessionId, { type: 'OFFER', payload: JSON.stringify({ type: 'offer', sdp: offer.sdp }) }, life.signal)
      offered = true
      for (const candidate of localIce.splice(0)) sendIce(candidate)
      void poll()
    } catch (error) { fail(error) }
  }
  void start()
  return { stop }
}
