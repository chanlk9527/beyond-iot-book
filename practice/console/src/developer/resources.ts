import gettingStarted from '../../../docs/developer/getting-started.md?raw'
import openapi from '../../../docs/openapi/README.md?raw'
import mqtt from '../../../docs/mqtt-protocol.md?raw'
import devices from '../../../docs/developer/device-development.md?raw'
import faq from '../../../docs/developer/faq.md?raw'
import changelog from '../../../docs/developer/changelog.md?raw'
import apiDefinition from '../../../docs/openapi/openapi.json?url'
import example from '../../../docs/openapi/examples.ps1?url'

export const documents = [
  { id: 'getting-started', title: '开始接入', description: '从选择接入方式到第一条应用请求。', group: '接入指南', source: 'developer/getting-started.md', content: gettingStarted },
  { id: 'openapi', title: 'OpenAPI 接口规范', description: '应用认证、能力申请、审核与错误处理。', group: '应用开发', source: 'openapi/README.md', content: openapi },
  { id: 'devices', title: '设备开发与接入', description: '产品、物模型、设备身份与联调检查。', group: '设备开发', source: 'developer/device-development.md', content: devices },
  { id: 'mqtt', title: 'MQTT 默认协议', description: 'Topic、消息信封、属性上报与命令回复。', group: '设备开发', source: 'mqtt-protocol.md', content: mqtt },
  { id: 'faq', title: '常见问题', description: '应用权限、凭证、令牌与接入问题排查。', group: '支持与更新', source: 'developer/faq.md', content: faq },
  { id: 'changelog', title: '更新记录', description: '查看已交付能力与当前开放范围。', group: '支持与更新', source: 'developer/changelog.md', content: changelog },
] as const
export const downloads = [
  { source: 'openapi/openapi.json', url: apiDefinition, filename: 'nebula-openapi.json', title: 'OpenAPI 接口定义', description: '可导入支持 OpenAPI 3.0.3 的接口工具。' },
  { source: 'openapi/examples.ps1', url: example, filename: 'nebula-openapi-example.ps1', title: 'PowerShell 接入示例', description: '从环境读取凭证，换取令牌并验证应用身份。' },
]
export function searchDocuments(query: string) {
  const terms = query.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean)
  return documents.filter(doc => terms.every(term => `${doc.title} ${doc.description} ${doc.content}`.toLocaleLowerCase().includes(term)))
}
export function resolveDocLink(href: string, source: string): {url: string; external?: boolean; download?: string} | null {
  if (/^https?:\/\//i.test(href)) return {url: href, external: true}
  if (href.startsWith('#')) return {url: href}
  const base = 'https://docs.invalid/'
  const target = new URL(href, base + source)
  if (target.origin !== 'https://docs.invalid') return null
  const file = decodeURIComponent(target.pathname.slice(1))
  const doc = documents.find(doc => doc.source === file)
  if (doc) return {url: `/developer/docs/${doc.id}${target.hash}`}
  const download = downloads.find(item => item.source === file)
  return download ? {url: download.url, download: download.filename} : null
}
