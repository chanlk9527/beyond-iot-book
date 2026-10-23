import { defineStore } from 'pinia'

export type RuleStatus = 'running' | 'paused' | 'draft'
export type MessageKind = 'property' | 'event' | 'connection'
export type ConnectionKind = 'HTTPS' | 'Kafka' | 'RocketMQ'
export interface Connection { id: string; name: string; kind: ConnectionKind; address: string; target: string; enabled: boolean; tested: boolean }
export interface Mapping { source: string; target: string; multiplier: number }
export interface Flow {
  id: string; name: string; description: string; status: RuleStatus; version: number;
  product: string; device: string; kind: MessageKind; processing: boolean;
  field: string; operator: '>' | '>=' | '<' | '='; threshold: number;
  mappings: Mapping[]; connectionIds: string[];
}
export interface SceneAction { kind: 'device' | 'notification' | 'connection' | 'delay'; target: string; value: string }
export interface Scene { id: string; name: string; description: string; status: RuleStatus; version: number; manual: boolean; device: string; threshold: number; cooldown: number; start: string; end: string; actions: SceneAction[] }
export interface Channel { id: string; name: string; kind: '邮件' | '短信' | 'App 推送'; recipient: string; enabled: boolean }
export interface NoticeTemplate { id: string; name: string; channelId: string; content: string }
export interface Run { id: string; name: string; type: '数据流转' | '场景自动化'; status: 'success' | 'failed' | 'filtered'; time: string; detail: string; input: unknown; output: unknown; attempts: number }

export const products = [{ id: 'warmer', name: '恒温暖奶器', model: 'NW-01' }, { id: 'nursery', name: '婴儿房环境传感器', model: 'NS-02' }]
export const devices = [{ id: 'warmer-001', product: 'warmer', name: '体验间 · 暖奶器' }, { id: 'warmer-002', product: 'warmer', name: '测试台 · 暖奶器' }, { id: 'sensor-001', product: 'nursery', name: '婴儿房 · 温湿度传感器' }]
export const kindNames: Record<MessageKind, string> = { property: '属性上报', event: '设备事件', connection: '上下线变化' }
export const statusNames: Record<RuleStatus, string> = { running: '运行中', paused: '已停用', draft: '草稿' }
export const fieldOptions = ['meta.deviceId', 'meta.productId', 'meta.occurredAt', 'data.temperature', 'data.power', 'data.humidity']
export function sampleMessage(product = 'warmer', kind: MessageKind = 'property', device = '') {
  return { meta: { productId: product, deviceId: device || (product === 'warmer' ? 'warmer-001' : 'sensor-001'), kind, occurredAt: '2026-10-23T08:30:00Z' }, data: kind === 'property' ? { temperature: 52.3, power: true, humidity: 48 } : kind === 'event' ? { eventId: 'heating_finished', temperature: 40 } : { online: false } }
}
export function newFlow(): Flow { return { id: '', name: '', description: '', status: 'draft', version: 0, product: 'warmer', device: '', kind: 'property', processing: false, field: 'data.temperature', operator: '>', threshold: 50, mappings: [{ source: 'meta.deviceId', target: 'deviceId', multiplier: 1 }, { source: 'data.temperature', target: 'temperature', multiplier: 1 }], connectionIds: ['conn-http'] } }
export function newScene(): Scene { return { id: '', name: '', description: '', status: 'draft', version: 0, manual: false, device: 'sensor-001', threshold: 28, cooldown: 10, start: '00:00', end: '23:59', actions: [{ kind: 'device', target: 'warmer-001', value: '关闭加热' }] } }
export function readField(input: unknown, path: string): unknown {
  return path.split('.').reduce<unknown>((current, key) => {
    if (['__proto__', 'prototype', 'constructor'].includes(key) || !current || typeof current !== 'object' || !Object.hasOwn(current, key)) return undefined
    return (current as Record<string, unknown>)[key]
  }, input)
}
export function validateFlow(flow: Flow, connections: Connection[]): string {
  if (!flow.name.trim()) return '请填写规则名称'
  if (!products.some(p => p.id === flow.product)) return '请选择消息源产品'
  if (flow.device && !devices.some(d => d.id === flow.device && d.product === flow.product)) return '所选设备不属于消息源产品'
  if (!flow.connectionIds.length) return '请至少选择一个转发目标'
  if (flow.connectionIds.some(id => !connections.some(c => c.id === id && c.enabled))) return '转发目标不存在或已停用，请重新选择'
  if (flow.processing) {
    if (!Number.isFinite(flow.threshold)) return '筛选阈值必须是有效数字'
    if (!flow.mappings.length) return '请至少保留一个输出字段'
    const names = new Set<string>()
    for (const m of flow.mappings) {
      if (!fieldOptions.includes(m.source)) return '请选择有效的来源字段'
      if (!/^[a-zA-Z][a-zA-Z0-9_]{0,39}$/.test(m.target) || ['constructor', 'prototype'].includes(m.target)) return '输出字段需以字母开头，只包含字母、数字或下划线'
      if (names.has(m.target)) return '输出字段名称不能重复'
      if (!Number.isFinite(m.multiplier)) return '换算倍数必须是有效数字'
      names.add(m.target)
    }
  }
  return ''
}
export function evaluateFlow(flow: Flow, input: unknown): { matched: boolean; reason: string; output: unknown } {
  if (!input || typeof input !== 'object' || !readField(input, 'meta') || !readField(input, 'data')) throw new Error('样例必须包含 meta 和 data 对象')
  if (readField(input, 'meta.productId') !== flow.product || readField(input, 'meta.kind') !== flow.kind || flow.device && readField(input, 'meta.deviceId') !== flow.device) return { matched: false, reason: '消息不属于当前订阅范围', output: null }
  if (!flow.processing) return { matched: true, reason: '命中消息源，保持标准消息原样输出', output: input }
  const value = readField(input, flow.field)
  if (typeof value !== 'number' || !Number.isFinite(value)) return { matched: false, reason: `筛选字段 ${flow.field} 缺失或不是数字`, output: null }
  const matched = flow.operator === '>' ? value > flow.threshold : flow.operator === '>=' ? value >= flow.threshold : flow.operator === '<' ? value < flow.threshold : value === flow.threshold
  if (!matched) return { matched: false, reason: '消息未满足筛选条件，不创建转发任务', output: null }
  const output: Record<string, unknown> = {}
  for (const mapping of flow.mappings) {
    const value = readField(input, mapping.source)
    if (value === undefined) throw new Error(`输出来源字段 ${mapping.source} 不存在`)
    if (mapping.multiplier !== 1 && typeof value !== 'number') throw new Error(`${mapping.source} 不是数字，不能进行倍数换算`)
    const mapped = typeof value === 'number' ? value * mapping.multiplier : value
    if (typeof mapped === 'number' && !Number.isFinite(mapped)) throw new Error('换算结果超出有效数字范围')
    output[mapping.target] = mapped
  }
  return { matched: true, reason: '筛选命中，已按字段映射生成输出', output }
}
// A readable representation of the form, not an executable SQL parser.
export function flowSql(flow: Flow): string {
  const source = flow.device ? `device('${flow.device}').${flow.kind}` : `product('${flow.product}').${flow.kind}`
  const select = flow.processing ? flow.mappings.map(m => `  ${m.source}${m.multiplier !== 1 ? ` * ${m.multiplier}` : ''} AS ${m.target}`).join(',\n') : '  *'
  return `SELECT\n${select}\nFROM ${source}${flow.processing ? `\nWHERE ${flow.field} ${flow.operator} ${flow.threshold}` : ''}`
}
function seed() {
  const flows: Flow[] = [
    { ...newFlow(), id: 'flow-1', name: '暖奶器设备数据同步', description: '将标准设备消息同步到品牌商业务服务', status: 'running', version: 2 },
    { ...newFlow(), id: 'flow-2', name: '异常温度数据分流', description: '筛选高温数据，提取关键字段供售后分析', status: 'running', version: 1, processing: true, connectionIds: ['conn-kafka'] },
    { ...newFlow(), id: 'flow-3', name: '环境传感器数据归档', description: '婴儿房环境数据接入分析链路', product: 'nursery', status: 'draft', connectionIds: ['conn-rocket'] },
  ]
  const connections: Connection[] = [
    { id: 'conn-http', name: '品牌商业务服务', kind: 'HTTPS', address: 'https://api.example.com/iot/events', target: '', enabled: true, tested: true },
    { id: 'conn-kafka', name: '设备分析 Kafka', kind: 'Kafka', address: 'kafka.example.com:9093', target: 'device.telemetry', enabled: true, tested: true },
    { id: 'conn-rocket', name: '售后事件队列', kind: 'RocketMQ', address: 'mq.example.com:9876', target: 'device-events', enabled: false, tested: false },
  ]
  const scenes: Scene[] = [
    { ...newScene(), id: 'scene-1', name: '婴儿房高温提醒', description: '环境温度升高时，通知值班人员关注', status: 'running', version: 1, actions: [{ kind: 'notification', target: 'template-1', value: '' }] },
    { ...newScene(), id: 'scene-2', name: '一键结束暖奶', description: '关闭体验间设备加热，结束本次演示', manual: true, status: 'running', version: 1 },
  ]
  const channels: Channel[] = [{ id: 'channel-mail', name: '设备运营邮箱', kind: '邮件', recipient: 'operations@example.com', enabled: true }, { id: 'channel-sms', name: '值班手机', kind: '短信', recipient: '138****8000', enabled: false }]
  const templates: NoticeTemplate[] = [{ id: 'template-1', name: '环境温度提醒', channelId: 'channel-mail', content: '设备 {{deviceName}} 当前温度为 {{temperature}}℃，请及时关注。' }]
  const runs: Run[] = [
    { id: 'DEMO-1024', name: '暖奶器设备数据同步', type: '数据流转', status: 'success', time: new Date().toISOString(), detail: '示例消息已模拟送达品牌商业务服务', input: sampleMessage(), output: sampleMessage(), attempts: 1 },
    { id: 'DEMO-1023', name: '异常温度数据分流', type: '数据流转', status: 'failed', time: new Date(Date.now() - 120000).toISOString(), detail: '模拟目标响应超时，等待人工重试', input: sampleMessage(), output: { deviceId: 'warmer-001', temperature: 52.3 }, attempts: 1 },
    { id: 'DEMO-1022', name: '婴儿房高温提醒', type: '场景自动化', status: 'success', time: new Date(Date.now() - 300000).toISOString(), detail: '示例通知已模拟完成', input: sampleMessage('nursery'), output: { template: '环境温度提醒', channel: '设备运营邮箱' }, attempts: 1 },
  ]
  return { flows, connections, scenes, channels, templates, runs }
}
export const useAutomationPreview = defineStore('automation-preview', {
  state: () => ({ ...seed(), owner: '' }),
  actions: {
    bindOwner(owner: string) { if (this.owner !== owner) { this.$reset(); this.owner = owner } },
    record(run: Omit<Run, 'id' | 'time' | 'attempts'>) { this.runs.unshift({ ...run, id: `DEMO-${crypto.randomUUID().slice(0, 8)}`, time: new Date().toISOString(), attempts: 1 }); this.runs.splice(100) },
  },
})
