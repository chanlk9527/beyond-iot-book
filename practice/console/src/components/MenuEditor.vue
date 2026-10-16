<script setup lang="ts">
import { computed, onScopeDispose, ref } from 'vue'
import { accessApi, type AccessRole, type Menu, type Permission } from '../api/access'
import { errorMessage, isCancelled } from '../api/management'

const props=defineProps<{ menu: Menu | null; existing: Menu[]; roles: AccessRole[] }>()
const emit=defineEmits<{ close: []; saved: [] }>()
const draft=ref<Menu>(props.menu ? {...props.menu,permissions:[...props.menu.permissions]} : {code:'',name:'',group:'',path:'',description:'',permissions:[],sortOrder:100,revision:0})
const pages=ref<Menu[]>([]),permissions=ref<Permission[]>([])
const loading=ref(true),busy=ref(false),error=ref(''),query=ref('')
const life=new AbortController()
const groups=computed(()=>[...new Set(permissions.value.map(permission=>permission.group))])
const affectedRoles=computed(()=>props.roles.filter(role=>role.permissions.includes(draft.value.code)))
const added=computed(()=>draft.value.permissions.filter(permission=>!props.menu?.permissions.includes(permission)))
const removed=computed(()=>props.menu?.permissions.filter(permission=>!draft.value.permissions.includes(permission)) || [])
const availablePages=computed(()=>pages.value.filter(page=>!props.existing.some(menu=>menu.code===page.code)))
function choosePage(code: string) {
  const page=pages.value.find(page=>page.code===code)
  if(page)draft.value={...page,permissions:[...page.permissions],sortOrder:draft.value.sortOrder,revision:0}
}
function recommended() {
  const page=pages.value.find(page=>page.code===draft.value.code)
  if(page)draft.value.permissions=[...page.permissions]
}
function matches(permission: Permission) { return `${permission.name} ${permission.code}`.toLowerCase().includes(query.value.trim().toLowerCase()) }
async function load() {
  loading.value=true;error.value=''
  try { [pages.value,permissions.value]=await Promise.all([accessApi.menuPages(life.signal),accessApi.catalog(life.signal)]) }
  catch(failure){if(!isCancelled(failure))error.value=errorMessage(failure)}
  finally{loading.value=false}
}
async function save() {
  if(busy.value)return
  busy.value=true;error.value=''
  try {
    if(props.menu)await accessApi.updateMenu(draft.value,life.signal)
    else await accessApi.createMenu(draft.value,life.signal)
    emit('saved')
  }catch(failure){if(!isCancelled(failure))error.value=errorMessage(failure)}
  finally{busy.value=false}
}
onScopeDispose(()=>life.abort())
void load()
</script>
<template>
  <el-dialog :model-value="true" :title="menu ? '编辑菜单' : '新增菜单'" width="min(780px,94vw)" :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy" @update:model-value="(open:boolean)=>{if(!open)emit('close')}">
    <el-skeleton v-if="loading" :rows="6" animated/>
    <form v-else class="tenant-form" @submit.prevent="save">
      <p class="muted small">平台超管维护页面与接口的关联；管理员在角色管理中勾选页面即可完成授权。</p>
      <h3>1. 菜单信息</h3>
      <label>关联页面</label>
      <el-select v-model="draft.code" :disabled="!!menu || busy" placeholder="选择已开发并注册的页面" aria-label="关联页面" @change="choosePage">
        <el-option v-for="page in menu ? pages : availablePages" :key="page.code" :value="page.code" :label="`${page.name} · ${page.path}`"/>
      </el-select>
      <p v-if="!menu && !availablePages.length" class="muted small">所有已注册页面均已建立菜单，可关闭此窗口后编辑已有菜单。新增业务页面需由开发者先注册。</p>
      <label>页面路由</label><el-input :model-value="draft.path" readonly aria-label="页面路由"/>
      <p class="muted small">路由随关联页面自动带出；详情页沿用该页面授权。</p>
      <div class="menu-fields"><div><label>菜单名称</label><el-input v-model="draft.name" :disabled="busy" maxlength="100" placeholder="例如：设备管理" aria-label="菜单名称"/></div><div><label>菜单分组</label><el-input v-model="draft.group" :disabled="busy" maxlength="100" placeholder="例如：产品与设备" aria-label="菜单分组"/></div><div><label>显示顺序</label><el-input-number v-model="draft.sortOrder" :disabled="busy" :min="0" :max="10000" :precision="0" aria-label="显示顺序"/></div></div>
      <label>功能说明</label><el-input v-model="draft.description" :disabled="busy" type="textarea" maxlength="500" show-word-limit placeholder="告诉管理员勾选此页面后可以做什么" aria-label="功能说明"/>
      <h3>2. 关联接口权限 · 已选 {{ draft.permissions.length }} 项</h3>
      <p class="muted small">选择页面正常使用所需的全部接口权限，包括下拉选项等辅助查询。关联查询权限不会自动开放其他页面。</p>
      <div class="menu-permission-toolbar"><el-input v-model="query" clearable placeholder="搜索权限名称或标识" aria-label="搜索接口权限"/><el-button :disabled="busy || !draft.code" @click="recommended">使用页面建议权限</el-button></div>
      <fieldset v-for="group in groups.filter(group=>permissions.some(permission=>permission.group===group && matches(permission)))" :key="group" class="permission-group"><legend>{{ group }}</legend><label v-for="permission in permissions.filter(permission=>permission.group===group && matches(permission))" :key="permission.code" class="permission-choice"><input v-model="draft.permissions" type="checkbox" :value="permission.code" :disabled="busy"/><span>{{ permission.name }}<small>{{ permission.code }}</small></span></label></fieldset>
      <div v-if="menu" class="menu-impact"><strong>保存影响</strong><p>已授权角色：{{ affectedRoles.map(role=>role.name+(role.enabled?'':'（已停用）')).join('、') || '暂无' }}。</p><p>新增 {{ added.length }} 项、移除 {{ removed.length }} 项接口关联。保存后同步到上述角色，其他页面仍需使用的共享权限保留。仅改名称、分组或顺序不会扩大角色操作权限。</p></div>
      <el-alert v-if="error" :title="error" type="error" :closable="false"/>
      <div class="tenant-form-actions"><el-button :disabled="busy" @click="emit('close')">取消</el-button><el-button v-if="!pages.length" @click="load">重试加载</el-button><el-button type="primary" native-type="submit" :loading="busy" :disabled="!draft.code || !draft.name.trim() || !draft.group.trim() || !draft.permissions.length || !permissions.length">{{ menu ? '保存菜单并同步授权' : '创建菜单' }}</el-button></div>
    </form>
  </el-dialog>
</template>
<style scoped>
.menu-fields{display:flex;gap:12px;flex-wrap:wrap}.menu-fields>div{flex:1;min-width:160px}.menu-fields label{display:block;margin-bottom:8px}.menu-permission-toolbar{display:flex;gap:12px}.menu-impact{padding:12px 16px;background:var(--el-fill-color-light);border-radius:8px}.menu-impact p{font-size:13px;line-height:1.6;margin:8px 0 0}h3{margin:16px 0 0}
</style>
