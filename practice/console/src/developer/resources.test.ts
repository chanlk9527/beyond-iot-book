import { expect, it } from 'vitest'
import { documents, resolveDocLink, searchDocuments } from './resources'
import { renderDocument } from './markdown'
import { loginDestination } from '../security/loginDestination'

it('searches repository document contents as well as titles',()=>{
  expect(searchDocuments('ClientId').some(doc=>doc.id==='mqtt')).toBe(true)
  expect(searchDocuments('令牌 撤销').some(doc=>doc.id==='openapi')).toBe(true)
  expect(searchDocuments('    ')).toHaveLength(documents.length)
  expect(searchDocuments('no-such-document-1234')).toHaveLength(0)
})
it('resolves only curated documents and downloadable resources',()=>{
  expect(resolveDocLink('../openapi/README.md','developer/getting-started.md')?.url).toBe('/developer/docs/openapi')
  expect(resolveDocLink('../mqtt-protocol.md','developer/device-development.md')?.url).toBe('/developer/docs/mqtt')
  expect(resolveDocLink('openapi.json','openapi/README.md')?.url).toBeTruthy()
  expect(resolveDocLink('examples.ps1','openapi/README.md')?.download).toBe('nebula-openapi-example.ps1')
  expect(resolveDocLink('../../backend/README.md','openapi/README.md')).toBeNull()
  expect(resolveDocLink('javascript:alert(1)','openapi/README.md')).toBeNull()
})
it('escapes HTML and blocks active content and remote tracking images',()=>{
  const result=renderDocument('<script>alert(1)</script>\n\n[unsafe](javascript:alert%281%29)\n\n![tracking](https://example.test/track.png)\n\n```html\n<img onerror=alert(1)>\n```','developer/faq.md')
  expect(result.html).not.toContain('<script>')
  expect(result.html).not.toContain('href="javascript:')
  expect(result.html).not.toContain('<img')
  expect(result.html).toContain('&lt;script&gt;')
})
it('builds unique heading anchors and keeps trusted document links navigable',()=>{
  const result=renderDocument('## 接入说明\n\n## 接入说明\n\n[OpenAPI](../openapi/README.md)\n\n[internal](../../private.md)','developer/faq.md')
  expect(result.headings.map(h=>h.id)).toEqual(['接入说明','接入说明-1'])
  expect(result.html).toContain('href="/developer/docs/openapi"')
  expect(result.html).toContain('<span>internal</span>')
})
it('accepts only the known application login destination',()=>{
  expect(loginDestination('/developer/apps')).toBe('/developer/apps')
  for(const target of ['https://other.test','//other.test','/platform/openapi',['/developer/apps'],undefined])expect(loginDestination(target)).toBe('/overview')
})
