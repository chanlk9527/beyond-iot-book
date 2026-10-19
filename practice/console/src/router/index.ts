import { createRouter, createWebHistory } from 'vue-router'
import { useSessionStore } from '../stores/session'
import { accessPages, firstCatalogSection, visibleMenus } from '../security/menus'
import { loginDestination } from '../security/loginDestination'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { title: '登录' } },
    { path: '/developer', component: () => import('../layouts/DeveloperLayout.vue'), children: [
      { path: '', name: 'developer-home', component: () => import('../views/DeveloperHomeView.vue'), meta: {title:'开发者中心',public:true} },
      { path: 'docs/:slug?', name: 'developer-docs', component: () => import('../views/DeveloperDocsView.vue'), meta: {title:'开发文档',public:true} },
      { path: 'examples', name: 'developer-examples', component: () => import('../views/DeveloperExamplesView.vue'), meta: {title:'示例与工具',public:true} },
      { path: 'apps', name: 'developer-apps', component: () => import('../views/DeveloperAppsView.vue'), meta: {title:'我的应用'} },
    ] },
    { path: '/', component: () => import('../layouts/ConsoleLayout.vue'), children: [
      { path: '', redirect: '/overview' },
      { path: 'overview', name: 'overview', component: () => import('../views/OverviewView.vue'), meta: { title: '控制台概览' } },
      { path: 'platform/openapi', name: 'openapi-management', component: () => import('../views/DeveloperAppsView.vue'), meta: { title: '开放平台管理' } },
      { path: 'catalog', name: 'catalog', component: () => import('../views/CatalogHomeView.vue'), meta: { title: '产品与设备', anyPermissions: ['page:categories','page:products','page:devices','page:production'] } },
      { path: 'catalog/platform/categories', name:'platform-categories', component:()=>import('../views/ProductCategoriesView.vue'), meta:{title:'产品目录',permission:'page:categories',catalogPlatform:true} },
      { path: 'catalog/tenants/:tenantId/categories', name:'tenant-categories', component:()=>import('../views/ProductCategoriesView.vue'), meta:{title:'产品目录',permission:'page:categories',catalogPlatform:false} },
      { path: 'catalog/platform/products', name: 'platform-products', component: () => import('../views/ProductsView.vue'), meta: { title: '产品管理', permission: 'page:products', catalogPlatform: true } },
      { path: 'catalog/platform/products/:id', name: 'platform-product-detail', component: () => import('../views/ProductDetailView.vue'), meta: { title: '产品详情', permission: 'page:products', catalogPlatform: true } },
      { path: 'catalog/platform/devices', name: 'platform-devices', component: () => import('../views/DevicesView.vue'), meta: { title: '设备管理', permission: 'page:devices', catalogPlatform: true } },
      { path: 'catalog/platform/devices/:id', name: 'platform-device-detail', component: () => import('../views/DeviceDetailView.vue'), meta: { title: '设备详情', permission: 'page:devices', catalogPlatform: true } },
      { path: 'catalog/tenants/:tenantId/products', name: 'tenant-products', component: () => import('../views/ProductsView.vue'), meta: { title: '产品管理', permission: 'page:products', catalogPlatform: false } },
      { path: 'catalog/tenants/:tenantId/products/:id', name: 'tenant-product-detail', component: () => import('../views/ProductDetailView.vue'), meta: { title: '产品详情', permission: 'page:products', catalogPlatform: false } },
      { path: 'catalog/tenants/:tenantId/devices', name: 'tenant-devices', component: () => import('../views/DevicesView.vue'), meta: { title: '设备管理', permission: 'page:devices', catalogPlatform: false } },
      { path: 'catalog/tenants/:tenantId/devices/:id', name: 'tenant-device-detail', component: () => import('../views/DeviceDetailView.vue'), meta: { title: '设备详情', permission: 'page:devices', catalogPlatform: false } },
      { path: 'catalog/platform/production', name: 'platform-production', component: () => import('../views/ProductionView.vue'), meta: { title: '生产与交付', permission: 'page:production', catalogPlatform: true } },
      { path: 'catalog/tenants/:tenantId/production', name: 'tenant-production', component: () => import('../views/ProductionView.vue'), meta: { title: '生产与交付', permission: 'page:production', catalogPlatform: false } },
      { path: 'access', name: 'access', component: () => import('../views/AccessManagementView.vue'), meta: { title: '权限管理' } },
      ...accessPages.map(page => ({ path: page.path.slice(1), name: page.code, component: () => import('../views/AccessManagementView.vue'), meta: { title: page.name, permission: page.code } })),
      { path: 'platform/access-nodes', name: 'access-nodes', component: () => import('../views/AccessNodesView.vue'), meta: { title: '设备接入', platformAdminOnly: true } },
      { path: 'platform/tenants', name: 'tenants', component: () => import('../views/TenantsView.vue'), meta: { title: '租户管理', permission: 'page:tenants' } },
      { path: 'platform/tenants/:tenantId', name: 'tenant-management', component: () => import('../views/TenantManagementView.vue'), meta: { title: '租户详情', permission: 'page:tenants' } },
      { path: 'tenants', redirect: '/catalog' },
      { path: 'tenants/:tenantId', redirect: '/catalog' },
      // Preserve bookmarks while the public navigation uses tenant URLs.
      { path: 'workspaces', redirect: '/catalog' },
      { path: 'workspaces/:tenantId', redirect: '/catalog' },
      { path: ':pathMatch(.*)*', component: () => import('../views/NotFoundView.vue'), meta: { title: '页面不存在' } },
    ] },
  ],
  scrollBehavior: to => to.hash ? { el: to.hash, top: 24 } : { top: 0 },
})
router.beforeEach(async to => {
  if (to.meta.public) return true
  const session = useSessionStore()
  await session.ensureSession(session.authenticated)
  if (session.status === 'error') return to.name === 'overview' || to.name === 'login' ? true : { name: 'overview' }
  if (!session.authenticated && to.name !== 'login') return to.name === 'developer-apps' ? {name:'login',query:{redirect:'/developer/apps'}} : { name: 'login' }
  if (session.authenticated && to.meta.platformAdminOnly && !session.account?.platformAdmin) return {name:'overview'}
  if (session.authenticated && to.name === 'openapi-management' && !session.account?.platformAdmin) return {name:'overview'}
  if (session.authenticated && to.name === 'developer-apps') {
    if(session.account?.platformAdmin)return {name:'openapi-management'}
    await session.refreshTenant()
    if(!['OWNER','ADMIN'].includes(session.tenant?.role || ''))return {name:'overview'}
  }
  if (session.authenticated && typeof to.meta.permission === 'string' && !session.can(to.meta.permission)) return { name: 'overview' }
  if (session.authenticated && Array.isArray(to.meta.anyPermissions) && !to.meta.anyPermissions.some(p => session.can(String(p)))) return { name: 'overview' }
  if (session.authenticated && to.name === 'access') return visibleMenus(session.account).find(page => page.path.startsWith('/access/'))?.path || '/overview'
  if (session.authenticated && to.name === 'catalog') {
    const section=firstCatalogSection(session.account)
    if(!section)return {name:'overview'}
    if(session.account?.platformAdmin)return `/catalog/platform/${section}`
    if(session.account?.tenantId)return `/catalog/tenants/${encodeURIComponent(session.account.tenantId)}/${section}`
    return {name:'overview'}
  }
  if (session.authenticated && to.meta.catalogPlatform===false && !session.account?.platformAdmin && to.params.tenantId!==session.account?.tenantId) return {name:'catalog'}
  if (session.authenticated && to.meta.catalogPlatform===true && !session.account?.platformAdmin) return {name:'catalog'}
  if (session.authenticated && to.name === 'login') return loginDestination(to.query.redirect)
})
router.afterEach(to => { document.title = `${String(to.meta.title || '控制台')} · 星云 IoT` })
export default router
