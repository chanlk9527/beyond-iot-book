import MarkdownIt from 'markdown-it'
import { resolveDocLink } from './resources'

export interface Heading { id: string; text: string; level: number }
/** Only curated repository Markdown is rendered; embedded HTML and remote images are disabled. */
export function renderDocument(content: string, source: string) {
  const markdown = new MarkdownIt({html: false, linkify: false, typographer: false})
  const headings: Heading[] = [], used = new Map<string, number>(), linkTags: string[] = []
  markdown.renderer.rules.heading_open = (tokens, index, options, _env, renderer) => {
    const text = tokens[index + 1]?.content || ''
    const base = text.toLocaleLowerCase().replace(/[^\p{L}\p{N}\s-]/gu, '').trim().replace(/\s+/g, '-') || 'section'
    const count = used.get(base) || 0; used.set(base, count + 1)
    const id = count ? `${base}-${count}` : base
    tokens[index].attrSet('id', id)
    headings.push({id, text, level: Number(tokens[index].tag.slice(1))})
    return renderer.renderToken(tokens, index, options)
  }
  markdown.renderer.rules.link_open = (tokens, index) => {
    const href = String(tokens[index].attrGet('href') || '')
    let target = null
    try { target = resolveDocLink(href, source) } catch { /* Invalid links remain readable text. */ }
    linkTags.push(target ? 'a' : 'span')
    return target ? `<a href="${markdown.utils.escapeHtml(target.url)}"${target.external ? ' target="_blank" rel="noopener noreferrer"' : ''}${target.download ? ` download="${markdown.utils.escapeHtml(target.download)}"` : ''}>` : '<span>'
  }
  markdown.renderer.rules.link_close = () => `</${linkTags.pop() || 'span'}>`
  markdown.renderer.rules.image = (tokens, index) => markdown.utils.escapeHtml(tokens[index].content)
  return {html: markdown.render(content), headings}
}
