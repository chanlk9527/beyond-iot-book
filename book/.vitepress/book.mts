import { readFileSync, existsSync } from 'node:fs'
import { dirname, resolve, relative, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

export const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')

export function readText(path: string): string {
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(readFileSync(path))
  } catch (cause) {
    throw new Error(`无法读取 UTF-8 文本：${path}。请确认构建进程能读取公司安全工具解密后的文件视图。`, { cause })
  }
}

export const pageAliases: Record<string, string> = {}

export function toPageLink(source: string): string {
  source = pageAliases[source] || source
  return '/' + source.replace(/^manuscript\//, '').replace(/\.md$/, '').replace(/\/_index$/, '/')
}

export type BookItem = { text: string; link: string; items?: BookItem[]; collapsed?: boolean }

export function flattenItems(items: BookItem[]): BookItem[] {
  return items.flatMap(({ text, link, items: children }) => [
    { text, link }, ...flattenItems(children || [])
  ])
}

export function readBook() {
  const parts: { text: string; link: string; items: BookItem[] }[] = []
  let introduction: { text: string; link: string } | undefined

  for (const line of readText(resolve(projectRoot, 'SUMMARY.md')).split(/\r?\n/)) {
    if (line.startsWith('## ')) {
      parts.push({ text: line.slice(3), link: '', items: [] })
      continue
    }
    const match = line.match(/^(  )?- \[([^\]]+)\]\(([^)]+\.md)\)$/)
    if (!match) continue
    const [, indent, label, source] = match
    const fullPath = resolve(projectRoot, source)
    const withinRoot = relative(projectRoot, fullPath)
    if (withinRoot.startsWith('..' + sep) || !source.startsWith('manuscript/') || !existsSync(fullPath)) {
      throw new Error(`SUMMARY.md 中的书稿路径无效：${source}`)
    }
    const item = { text: label.replace(/`/g, ''), link: toPageLink(source) }
    if (indent) {
      const chapter = parts.at(-1)?.items.at(-1)
      if (!chapter) throw new Error(`小节缺少所属章节：${line}`)
      ;(chapter.items ||= []).push(item)
      chapter.collapsed = true
    }
    else if (!parts.length) introduction = item
    else if (source.endsWith('/_index.md')) parts.at(-1)!.link = item.link
    else parts.at(-1)!.items.push(item)
  }
  if (!introduction && parts[0]?.items[0]) introduction = parts[0].items[0]
  if (!introduction || !parts.length || parts.some(part => !part.link || !part.items.length)) {
    throw new Error('SUMMARY.md 需要包含分部标题、分部导读与章节链接。')
  }
  return { introduction, parts }
}
