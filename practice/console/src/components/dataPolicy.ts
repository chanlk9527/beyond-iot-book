export type HistoryMode = 'none' | 'time' | 'count'
export interface DataRule {
  latest: boolean
  history: HistoryMode
  days: number
  count: number
  write: 'every' | 'change' | 'interval'
  intervalSeconds: number
  aggregate: boolean
  aggregateDays: number
}
export interface DataPolicy { defaults: DataRule; overrides: Record<string, DataRule>; eventDefaults: DataRule; eventOverrides: Record<string, DataRule> }
export const defaultDataRule = (): DataRule => ({ latest: true, history: 'none', days: 30, count: 100, write: 'every', intervalSeconds: 60, aggregate: false, aggregateDays: 365 })
export const defaultEventRule = (): DataRule => ({ ...defaultDataRule(), latest: false, history: 'time', days: 90 })
export const defaultDataPolicy = (): DataPolicy => ({ defaults: defaultDataRule(), overrides: {}, eventDefaults: defaultEventRule(), eventOverrides: {} })
export const validateEventRule = (rule: DataRule): boolean => validateRule(rule) && rule.latest === false && rule.aggregate === false && rule.write === 'every'
export const eventRuleSummary = (rule: DataRule): string => rule.history === 'none' ? '不保存事件历史' : rule.history === 'time' ? `完整事件保留 ${rule.days} 天` : `最近 ${rule.count} 次 / 设备事件类型`
export const copyPolicy = <T>(value: T): T => JSON.parse(JSON.stringify(value))
export function ruleSummary(rule: DataRule): string {
  if (rule.history === 'none') return rule.latest ? '不保留原始历史' : rule.aggregate ? '仅保留小时统计' : '不保存数据'
  const retention = rule.history === 'time' ? `保留 ${rule.days} 天` : `最近 ${rule.count} 条 / 设备属性`
  const write = rule.write === 'change' ? '值变化时记录' : rule.write === 'interval' ? `每 ${rule.intervalSeconds} 秒取最后一个观测值` : '每次上报记录'
  return `${retention} · ${write}`
}
export function validateRule(rule: DataRule): boolean {
  const positive = (value: number, max: number) => Number.isInteger(value) && value >= 1 && value <= max
  return typeof rule.latest === 'boolean' && ['none', 'time', 'count'].includes(rule.history)
    && (rule.history === 'none' || (['every', 'change', 'interval'].includes(rule.write)
      && (rule.history === 'time' ? positive(rule.days, 36500) : positive(rule.count, 1000000))
      && (rule.write !== 'interval' || positive(rule.intervalSeconds, 86400))))
    && typeof rule.aggregate === 'boolean'
    && (!rule.aggregate || positive(rule.aggregateDays, 36500))
}
export function parsePolicy(text: string): DataPolicy {
  const value = JSON.parse(text)
  if (!value || !value.defaults || !value.overrides || Array.isArray(value.overrides)
      || typeof value.overrides !== 'object' || !validateRule(value.defaults)
      || !Object.values(value.overrides).every(rule => rule && validateRule(rule as DataRule))) {
    throw new Error('Invalid data policy')
  }
  value.eventDefaults ??= defaultEventRule()
  value.eventOverrides ??= {}
  if (!validateEventRule(value.eventDefaults) || typeof value.eventOverrides !== 'object'
      || Array.isArray(value.eventOverrides) || !Object.values(value.eventOverrides).every(rule => rule && validateEventRule(rule as DataRule))) throw new Error('Invalid event policy')
  return value
}
