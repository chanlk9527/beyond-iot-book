<script setup lang="ts">
import { computed, onScopeDispose, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { visibleMenus } from '../security/menus'
import MenuEditor from '../components/MenuEditor.vue'
import { useSessionStore } from '../stores/session'
import { accessApi, type AccessUser, type AccessRole, type Menu } from '../api/access'
import { errorMessage, isCancelled } from '../api/management'
import StatePanel from '../components/StatePanel.vue'
import { tenantsApi, type Tenant } from '../api/tenants'
const session = useSessionStore()
const route = useRoute(), router = useRouter()
const tab = computed(() => route.path.split('/').pop())
const accessPages = computed(()=>visibleMenus(session.account).filter(menu=>menu.path.startsWith('/access/')))
const menuEditorOpen=ref(false)
const editingMenu=ref<Menu|null>(null)
function editMenu(menu?: Menu){editingMenu.value=menu||null;menuEditorOpen.value=true}
async function menuSaved(){menuEditorOpen.value=false;ElMessage.success('菜单已保存');await session.ensureSession(true);await load()}
async function removeMenu(menu: Menu){
  try{await ElMessageBox.confirm('删除后该页面将从菜单和角色授权选项中移除，可从已注册页面重新添加。','删除菜单：'+menu.name,{type:'warning',confirmButtonText:'删除',cancelButtonText:'取消'})}catch{return}
  busy.value=true;error.value=''
  try{await accessApi.deleteMenu(menu,lifetime.signal);ElMessage.success('菜单已删除');await session.ensureSession(true);await load()}
  catch(failure){if(!isCancelled(failure))error.value=errorMessage(failure)}
  finally{busy.value=false}
}
const users = ref<AccessUser[]>([])
const roles = ref<AccessRole[]>([])
const catalog = ref<Menu[]>([])
const query = ref('')
const page = ref(1)
const total = ref(0)
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const saveError = ref('')
const dialog = ref<'user'|'role'|'grant'|''>('')
const username = ref('')
const password = ref('')
const userTenantId=ref('')
const userTenantRole=ref('VIEWER')
const tenantOptions=ref<Tenant[]>([])
const tenantSearchLoading=ref(false)
const roleCode = ref('')
const roleName = ref('')
const roleEnabled = ref(true)
const selectedMenus = ref<string[]>([])
const selectedRoles = ref<string[]>([])
const editingRole = ref<AccessRole | null>(null)
const editingUser = ref<AccessUser | null>(null)
const configuringMenu = ref<Menu | null>(null)
function configureMenu(menu: Menu) { configuringMenu.value = menu }
function configureRole(role?: AccessRole) {
  const code = configuringMenu.value?.code
  configuringMenu.value = null; editRole(role)
  if (!role && code && availableMenus.value.some(menu => menu.code === code)) selectedMenus.value = [code]
}
const lifetime = new AbortController()
let request = new AbortController()
let revision = 0
const availableMenus = computed(() => catalog.value.filter(menu => session.can(menu.code) && (editingRole.value?.permissions.includes(menu.code) || menu.permissions.every(p => session.can(p)))))
const groups = computed(() => [...new Set(catalog.value.map(p => p.group))])
const selectedMenuNames = computed(() => catalog.value.filter(menu => selectedMenus.value.includes(menu.code)).map(menu => menu.name))
function limitedMenu(menu: Menu) {
  return editingRole.value?.permissions.includes(menu.code) && !menu.permissions.every(permission => editingRole.value?.permissions.includes(permission))
}
function selectableMenu(menu: Menu) { return availableMenus.value.some(item => item.code === menu.code) }
const canEditRole = (r: AccessRole) => session.can('iam:role:write') && r.permissions.every(p => session.can(p))
const canSelectMenus = (r: AccessRole) => canEditRole(r)
function menuNames(role: AccessRole) { return catalog.value.filter(menu => role.permissions.includes(menu.code)).map(menu => menu.name).join('、') || '未授权页面' }
function menuRoles(code: string) { return roles.value.filter(role => role.permissions.includes(code)).map(role => role.name + (role.enabled ? '' : '（已停用）')).join('、') || '未分配' }
const grantableRoles = computed(() => roles.value.filter(r => r.enabled && r.permissions.every(p => session.can(p))))
const protectedUser = (u: AccessUser) => u.platformAdmin || u.id === session.account?.id
function roleNames(ids: string[]) { return ids.map(id => roles.value.find(r => r.id === id)?.name || id).join('、') || '未分配角色' }
async function load() {
  request.abort(); request = new AbortController(); const current = ++revision
  loading.value = true; error.value = ''; users.value = []; roles.value = []; catalog.value = []
  try {
    const [userPage, roleList, definitions] = await Promise.all([
      tab.value === 'users' && session.can('iam:user:read') ? accessApi.users(query.value,page.value,request.signal) : Promise.resolve({items: [], total: 0}),
      session.can('iam:role:read') ? accessApi.roles(request.signal) : Promise.resolve([]),
      session.can('iam:role:read') ? accessApi.menus(request.signal) : Promise.resolve([]),
    ])
    if (current !== revision) return
    users.value = userPage.items; total.value = userPage.total; roles.value = roleList; catalog.value = definitions
  } catch (failure) { if (current === revision && !isCancelled(failure)) error.value = errorMessage(failure) }
  finally { if (current === revision) loading.value = false }
}
async function searchTenants(query: string) {
  tenantSearchLoading.value=true
  try {tenantOptions.value=(await tenantsApi.list(query,1,lifetime.signal)).items.filter(t=>t.enabled)}
  catch(failure){if(!isCancelled(failure))saveError.value=errorMessage(failure)}
  finally{tenantSearchLoading.value=false}
}
function newUser() {
  username.value='';password.value='';saveError.value='';userTenantRole.value='VIEWER'
  userTenantId.value=session.account?.tenantId||'';dialog.value='user'
  if(session.account?.platformAdmin)void searchTenants('')
}
function editRole(role?: AccessRole) {
  editingRole.value = role ? {...role} : null; roleCode.value = role?.code || ''; roleName.value = role?.name || ''
  roleEnabled.value = role?.enabled ?? true; selectedMenus.value = catalog.value.filter(menu => role?.permissions.includes(menu.code)).map(menu => menu.code); saveError.value = ''; dialog.value = 'role'
}
function grant(user: AccessUser) { editingUser.value = {...user}; selectedRoles.value = [...user.roleIds]; saveError.value = ''; dialog.value = 'grant' }
async function save() {
  if (busy.value) return
  busy.value = true; saveError.value = ''
  try {
    if (dialog.value === 'user') await accessApi.createUser(username.value,password.value,userTenantId.value,userTenantRole.value,lifetime.signal)
    if (dialog.value === 'role') {
      if (editingRole.value) await accessApi.updateRole({...editingRole.value,name:roleName.value,enabled:roleEnabled.value},selectedMenus.value,lifetime.signal)
      else await accessApi.createRole(roleCode.value,roleName.value,selectedMenus.value,lifetime.signal)
    }
    if (dialog.value === 'grant' && editingUser.value) await accessApi.grant(editingUser.value,selectedRoles.value,lifetime.signal)
    dialog.value = ''; password.value = ''; ElMessage.success('已保存'); await load()
  } catch (failure) { if (!isCancelled(failure)) saveError.value = errorMessage(failure) }
  finally { busy.value = false }
}
async function toggleUser(user: AccessUser) {
  busy.value = true; error.value = ''
  try { await accessApi.updateUser(user,lifetime.signal); ElMessage.success('用户状态已更新'); await load() }
  catch (failure) { if (!isCancelled(failure)) error.value = errorMessage(failure) }
  finally { busy.value = false }
}
onScopeDispose(() => { revision++; request.abort(); lifetime.abort(); password.value = '' })
void load()
</script>
<template>
  <div class="page-heading"><div><p class="eyebrow">IDENTITY / ACCESS CONTROL</p><h1>权限管理</h1><p>为用户分配角色，按角色配置可见菜单和可访问页面。</p></div><el-button :loading="loading" @click="load">刷新</el-button></div>
  <section class="tenant-context-info"><div><strong>配置顺序：创建角色 → 勾选页面 → 为用户分配角色</strong><p>只需选择允许使用的页面，系统自动关联页面所需的接口权限。用户拥有多个角色时，可使用这些角色授权的全部页面。数据访问仍受所属租户和成员身份限制。</p></div></section>
  <div class="access-tabs"><el-button v-for="item in accessPages.filter(item => session.can(item.code))" :key="item.code" :type="route.path === item.path ? 'primary' : 'default'" @click="router.push(item.path)">{{ item.name }}</el-button></div>
  <section v-if="loading" class="panel tenant-context-loading"><el-skeleton :rows="5" animated /></section>
  <section v-else-if="error" class="panel"><StatePanel title="权限数据加载失败" :description="error"><el-button @click="load">重试</el-button></StatePanel></section>
  <section v-else class="panel tenant-section">
    <template v-if="tab==='users'"><form class="tenant-context-toolbar" @submit.prevent="page=1;load()"><div class="tenant-search"><el-input v-model="query" maxlength="100" placeholder="搜索账号" aria-label="搜索账号" clearable /><el-button native-type="submit">查询</el-button></div><el-button v-if="session.can('iam:user:write') && session.can('tenant:member:write')" type="primary" @click="newUser">新建用户</el-button></form>
      <el-table :data="users" row-key="id"><el-table-column prop="username" label="账号" min-width="150" /><el-table-column label="角色" min-width="220"><template #default="{row}"><el-tag v-if="row.platformAdmin" effect="plain">平台超级管理员</el-tag><span v-else>{{ roleNames(row.roleIds) }}</span></template></el-table-column><el-table-column label="状态" width="90"><template #default="{row}">{{ row.enabled ? '启用' : '停用' }}</template></el-table-column><el-table-column label="操作" min-width="200"><template #default="{row}"><el-button v-if="session.can('iam:grant') && session.can('iam:role:read') && !protectedUser(row)" link type="primary" :disabled="busy" @click="grant(row)">分配角色</el-button><el-button v-if="session.can('iam:user:write') && !protectedUser(row)" link :disabled="busy" @click="toggleUser(row)">{{ row.enabled ? '停用' : '启用' }}</el-button><span v-if="protectedUser(row)" class="muted small">受保护账号</span></template></el-table-column></el-table><div class="tenant-pagination"><el-pagination :current-page="page" :total="total" :page-size="20" layout="prev,pager,next" @current-change="(n:number)=>{page=n;load()}" /></div></template>
    <template v-if="tab==='roles'"><div class="section-heading"><div><h2>平台角色 · {{ roles.length }}</h2><p>用户可访问所有启用角色授权页面的并集。停用角色后不再提供授权。</p></div><el-button v-if="session.can('iam:role:write')" type="primary" @click="editRole()">新建角色</el-button></div><el-table :data="roles" class="member-table"><el-table-column prop="name" label="角色名称" min-width="140" /><el-table-column prop="code" label="角色编码" min-width="160" /><el-table-column label="可访问页面" min-width="260"><template #default="{row}">{{ menuNames(row) }}</template></el-table-column><el-table-column label="状态" width="90"><template #default="{row}">{{ row.enabled ? '启用' : '停用' }}</template></el-table-column><el-table-column label="操作" width="100"><template #default="{row}"><el-button v-if="canSelectMenus(row)" type="primary" link @click="editRole(row)">编辑授权</el-button><span v-else class="muted small">只读</span></template></el-table-column></el-table></template>
    <template v-if="tab==='menus'">
      <div class="section-heading"><div><h2>菜单管理</h2><p>{{ session.account?.platformAdmin ? '维护页面信息及接口关联，角色授权会同步使用配置结果。' : '查看页面功能与已授权角色；菜单定义由平台超管维护。' }}</p></div><el-button v-if="session.account?.platformAdmin" type="primary" :disabled="busy" @click="editMenu()">新增菜单</el-button></div>
      <el-table :data="catalog" class="member-table" row-key="code">
        <el-table-column prop="sortOrder" label="顺序" width="70"/>
        <el-table-column prop="group" label="所属分组" width="120"/>
        <el-table-column prop="name" label="菜单页面" min-width="140"/>
        <el-table-column prop="description" label="授权后可使用的功能" min-width="240"/>
        <el-table-column label="关联接口权限" width="120"><template #default="{row}">{{ row.permissions.length }} 项</template></el-table-column>
        <el-table-column label="已授权角色" min-width="180"><template #default="{row}">{{ menuRoles(row.code) }}</template></el-table-column>
        <el-table-column label="操作" :width="session.account?.platformAdmin ? 230 : 110"><template #default="{row}">
          <el-button v-if="session.account?.platformAdmin" link type="primary" :disabled="busy" @click="editMenu(row)">编辑菜单</el-button>
          <el-button v-if="session.can('iam:role:write')" link type="primary" :disabled="busy" @click="configureMenu(row)">查看授权</el-button>
          <el-tooltip v-if="session.account?.platformAdmin" :content="row.code==='page:menus' ? '菜单维护入口不可删除' : roles.some(role=>role.permissions.includes(row.code)) ? '请先在角色管理中取消此页面授权' : '删除未使用菜单'"><span><el-button link type="danger" :disabled="busy || row.code==='page:menus' || roles.some(role=>role.permissions.includes(row.code))" @click="removeMenu(row)">删除</el-button></span></el-tooltip>
        </template></el-table-column>
      </el-table>
      <p class="muted small">菜单维护配置页面与接口的关系；为用户授权请到角色管理勾选页面。控制台概览默认开放，平台超管可访问全部已配置页面。</p>
    </template>
  </section>
  <el-dialog :model-value="!!dialog" :title="dialog==='user' ? '新建用户' : dialog==='grant' ? '分配角色' : editingRole ? '编辑角色' : '新建角色'" width="min(650px,94vw)" :close-on-click-modal="!busy" :show-close="!busy" :close-on-press-escape="!busy" @update:model-value="(open:boolean)=>{if(!open){dialog='';password=''}}">
    <form class="tenant-form" @submit.prevent="save">
      <template v-if="dialog==='user'"><label for="access-username">登录账号</label><el-input id="access-username" v-model="username" maxlength="100" autocomplete="off" :disabled="busy" /><label>所属租户</label><el-select v-if="session.account?.platformAdmin" v-model="userTenantId" filterable remote :remote-method="searchTenants" :loading="tenantSearchLoading" :disabled="busy" placeholder="选择用户唯一所属的租户" aria-label="所属租户"><el-option v-for="tenant in tenantOptions" :key="tenant.id" :label="tenant.name" :value="tenant.id" /></el-select><el-input v-else :model-value="session.tenant?.name || session.account?.tenantId || ''" disabled /><label>租户角色</label><el-select v-model="userTenantRole" :disabled="busy" aria-label="租户角色"><el-option label="所有者" value="OWNER"/><el-option label="管理员" value="ADMIN"/><el-option label="观察者" value="VIEWER"/></el-select><label for="access-password">初始密码</label><el-input id="access-password" v-model="password" type="password" show-password autocomplete="new-password" :disabled="busy" /><p class="muted small">普通账号必须绑定一个租户，创建时自动建立成员关系；后台功能角色需另行分配。密码非空，最多72个UTF-8字节。</p></template>
      <template v-if="dialog==='role'"><label for="role-code">角色编码</label><el-input id="role-code" v-model="roleCode" maxlength="64" :disabled="!!editingRole||busy" placeholder="例如 tenant-reader" /><label for="role-name">角色名称</label><el-input id="role-name" v-model="roleName" maxlength="100" :disabled="busy" /><el-switch v-model="roleEnabled" v-if="editingRole" active-text="启用" inactive-text="停用" :disabled="busy" aria-label="角色状态" /><div class="menu-grant-summary"><strong>可访问页面 · 已选 {{ selectedMenus.length }} 项</strong><p>勾选页面即授权该页面的功能，所需接口由系统自动关联，无需逐个配置。</p><p>控制台概览默认可见；详情页随所属列表页一起授权。</p></div><fieldset v-for="group in groups" :key="group" class="permission-group"><legend>{{ group }}</legend><label v-for="menu in catalog.filter(menu=>menu.group===group)" :key="menu.code" class="permission-choice"><input v-model="selectedMenus" type="checkbox" :value="menu.code" :disabled="busy || !selectableMenu(menu)"/><span>{{ menu.name }}<small>{{ menu.description }}</small><small v-if="!selectableMenu(menu)" class="grant-note">你的授权范围不足，无法授予此页面。</small><small v-else-if="limitedMenu(menu)" class="grant-note">此角色保留原有操作限制，本次保存不会自动扩大操作范围。</small></span></label></fieldset><p class="muted small">保存后可访问：{{ selectedMenuNames.join('、') || '未选择页面，仅可访问控制台概览' }}。</p></template>
      <template v-if="dialog==='grant'"><p>为 <strong>{{ editingUser?.username }}</strong> 分配角色</p><el-select v-model="selectedRoles" multiple aria-label="用户角色" :disabled="busy" placeholder="请选择角色"><el-option v-for="role in grantableRoles" :key="role.id" :label="role.name" :value="role.id" /></el-select><p class="muted small">清空将撤销全部角色。仅可分配启用且不超过你自身权限的角色。</p></template>
      <el-alert v-if="saveError" :title="saveError" type="error" :closable="false" /><div class="tenant-form-actions"><el-button :disabled="busy" @click="dialog='';password=''">取消</el-button><el-button type="primary" native-type="submit" :loading="busy" :disabled="dialog==='user' ? !username.trim()||!password||!userTenantId : dialog==='role' ? !roleCode||!roleName.trim() : false">保存</el-button></div>
    </form>
  </el-dialog>
  <el-dialog :model-value="!!configuringMenu" :title="`${configuringMenu?.name || ''} · 角色授权`" width="min(650px,94vw)" @update:model-value="(open:boolean)=>{if(!open)configuringMenu=null}">
    <p class="muted small">选择角色调整页面授权，再将角色分配给用户。停用角色不会提供页面访问权限。</p>
    <el-table :data="roles" row-key="id" empty-text="尚无角色，请先新建角色">
      <el-table-column prop="name" label="角色名称" min-width="140"/>
      <el-table-column label="当前授权" width="110"><template #default="{row}">{{ row.permissions.includes(configuringMenu?.code) ? '已授权' : '未授权' }}</template></el-table-column>
      <el-table-column label="状态" width="90"><template #default="{row}">{{ row.enabled ? '启用' : '停用' }}</template></el-table-column>
      <el-table-column label="操作" width="140"><template #default="{row}"><el-button v-if="canSelectMenus(row)" link type="primary" @click="configureRole(row)">调整页面授权</el-button><span v-else class="muted small">只读</span></template></el-table-column>
    </el-table>
    <template #footer><el-button @click="configuringMenu=null">关闭</el-button><el-button v-if="session.can('iam:role:write')" type="primary" @click="configureRole()">新建角色</el-button></template>
  </el-dialog>
  <MenuEditor v-if="menuEditorOpen" :menu="editingMenu" :existing="catalog" :roles="roles" @close="menuEditorOpen=false" @saved="menuSaved"/>
</template>

<style scoped>
.menu-grant-summary { padding: 12px 16px; background: var(--el-fill-color-light); border-radius: 8px; }
.menu-grant-summary p { margin: 6px 0 0; font-size: 13px; color: var(--el-text-color-secondary); line-height: 1.6; }
.permission-choice small { font-family: inherit; line-height: 1.5; color: var(--el-text-color-secondary); }
.permission-choice .grant-note { color: var(--el-color-warning-dark-2); }
</style>