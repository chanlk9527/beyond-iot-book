import type { Account, NavigationMenu } from '../api/management'
import { hasPermission } from './permissions'

export const accessPages = [
  { code: 'page:users', name: '用户管理', path: '/access/users' },
  { code: 'page:roles', name: '角色管理', path: '/access/roles' },
  { code: 'page:menus', name: '菜单管理', path: '/access/menus' },
] as const
export const catalogPages = [
  { code: 'page:categories', name: '产品目录', section: 'categories' },
  { code: 'page:products', name: '产品管理', section: 'products' },
  { code: 'page:devices', name: '设备管理', section: 'devices' },
  { code: 'page:production', name: '生产与交付', section: 'production' },
] as const
export function canVisitPage(account: Account | null, code: string): boolean {
  return hasPermission(account, code)
}
export function firstCatalogSection(account: Account | null): string | undefined {
  return catalogPages.find(page => page.code === visibleMenus(account).find(menu => menu.path.startsWith('/catalog/'))?.code)?.section
}
export function visibleMenus(account: Account | null): NavigationMenu[] {
  const fallback = [
    { code: 'page:tenants', name: '租户管理', group: '平台管理', path: '/platform/tenants', sortOrder: 0 },
    ...accessPages.map((page,index) => ({ ...page, group: '权限管理', sortOrder: index + 1 })),
    ...catalogPages.map((page,index) => ({ ...page, path: `/catalog/:scope/${page.section}`, group: '产品与设备', sortOrder: index + 4 })),
  ]
  return [...(account?.menus ?? fallback)].filter(menu => canVisitPage(account,menu.code)).sort((a,b)=>a.sortOrder-b.sortOrder || a.code.localeCompare(b.code))
}
export function menuPath(menu: NavigationMenu, account: Account | null, tenantId?: string): string {
  const scope = tenantId ? `tenants/${encodeURIComponent(tenantId)}` : account?.platformAdmin ? 'platform' : `tenants/${encodeURIComponent(account?.tenantId || '')}`
  return menu.path.replace(':scope',scope)
}
