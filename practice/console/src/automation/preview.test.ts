import { describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { evaluateFlow, newFlow, readField, sampleMessage, useAutomationPreview, validateFlow } from './preview'

describe('data forwarding preview', () => {
  it('forwards the full message unchanged when processing is off', () => {
    const flow = newFlow(), message = sampleMessage()
    expect(evaluateFlow(flow, message)).toMatchObject({ matched: true, output: message })
  })
  it('does not forward another product, device or message kind', () => {
    const flow = { ...newFlow(), device: 'warmer-002' }
    for (const message of [sampleMessage('nursery'), sampleMessage(), sampleMessage('warmer', 'event', 'warmer-002')]) expect(evaluateFlow(flow, message).matched).toBe(false)
  })
  it('filters temperature and creates renamed, converted output', () => {
    const flow = { ...newFlow(), processing: true, mappings: [{ source: 'meta.deviceId', target: 'device', multiplier: 1 }, { source: 'data.temperature', target: 'scaled', multiplier: 10 }] }
    expect(evaluateFlow(flow, sampleMessage()).output).toEqual({ device: 'warmer-001', scaled: 523 })
    flow.threshold = 60
    expect(evaluateFlow(flow, sampleMessage())).toMatchObject({ matched: false, output: null })
  })
  it('does not coerce missing or string temperatures into a match', () => {
    const flow = { ...newFlow(), processing: true }
    expect(evaluateFlow(flow, { meta: sampleMessage().meta, data: { temperature: '80' } }).matched).toBe(false)
    expect(evaluateFlow(flow, { meta: sampleMessage().meta, data: {} }).matched).toBe(false)
  })
  it('rejects absent output fields and invalid numeric conversions', () => {
    const flow = { ...newFlow(), processing: true, mappings: [{ source: 'data.missing', target: 'value', multiplier: 1 }] }
    expect(() => evaluateFlow(flow, sampleMessage())).toThrow('不存在')
    flow.mappings = [{ source: 'meta.deviceId', target: 'device', multiplier: 10 }]
    expect(() => evaluateFlow(flow, sampleMessage())).toThrow('不能进行倍数换算')
  })
  it('prevents duplicate output names and disabled or missing targets', () => {
    setActivePinia(createPinia())
    const store = useAutomationPreview(), flow = { ...newFlow(), name: '测试规则', processing: true }
    expect(validateFlow(flow, store.connections)).toBe('')
    flow.mappings[1]!.target = 'deviceId'
    expect(validateFlow(flow, store.connections)).toContain('不能重复')
    flow.processing = false; flow.connectionIds = ['conn-rocket']
    expect(validateFlow(flow, store.connections)).toContain('已停用')
  })
  it('does not traverse prototype fields', () => {
    expect(readField({}, '__proto__.constructor')).toBeUndefined()
    expect(readField({}, 'constructor.name')).toBeUndefined()
  })
  it('keeps demo state across pages, resets across accounts and bounds run history', () => {
    setActivePinia(createPinia())
    const store = useAutomationPreview()
    store.bindOwner('alice'); store.flows[0]!.name = '本次演示'
    store.bindOwner('alice'); expect(store.flows[0]!.name).toBe('本次演示')
    for (let i = 0; i < 105; i++) store.record({ name: '测试', type: '数据流转', status: 'success', input: {}, output: {}, detail: '模拟' })
    expect(store.runs).toHaveLength(100)
    store.bindOwner('bob'); expect(store.flows[0]!.name).toBe('暖奶器设备数据同步')
    expect(store.runs).toHaveLength(3)
  })
})
