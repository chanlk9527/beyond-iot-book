<script setup lang="ts">
import {computed,onScopeDispose,ref} from 'vue'
import {useRoute} from 'vue-router'
import {ElMessage,ElMessageBox} from 'element-plus'
import {catalogScope} from '../api/catalog'
import {directoryApi,type Directory,type CategoryNode} from '../api/directory'
import {errorMessage,isCancelled} from '../api/management'
import {useSessionStore} from '../stores/session'
import CatalogNavigation from '../components/CatalogNavigation.vue'
const route=useRoute(),session=useSessionStore(),scope=catalogScope(route.meta.catalogPlatform===true,route.params.tenantId)
const writable=computed(()=>session.can('product:write')&&!!session.account?.platformAdmin)
const data=ref<Directory>({revision:0,items:[]}),loading=ref(true),busy=ref(false),error=ref(''),show=ref(false),label=ref(''),parent=ref(''),oldPath=ref(''),code=ref('')
const life=new AbortController()
const rows=computed(()=>data.value.items)
async function load(){loading.value=true;error.value='';try{data.value=await directoryApi.get(scope,life.signal)}catch(e){if(!isCancelled(e))error.value=errorMessage(e)}finally{loading.value=false}}
function open(id='',parentId=''){oldPath.value=id;parent.value=parentId;const node=data.value.items.flatMap(p=>[p,...p.children]).find(n=>n.id===id);label.value=node?.label||'';code.value=node?.id.split('-').at(-1)||'';show.value=true;error.value=''}
async function persist(items:CategoryNode[]){if(busy.value)return;busy.value=true;error.value='';try{data.value=await directoryApi.save(scope,{revision:data.value.revision,items},life.signal);show.value=false;ElMessage.success('产品目录已保存')}catch(e){if(!isCancelled(e))error.value=errorMessage(e)}finally{busy.value=false}}
function save(){
 if(busy.value)return;const name=label.value.trim();if(!name||name.includes('/')){error.value='目录名称不能为空或包含斜杠';return}
 if(!/^[a-z]{3,4}$/.test(code.value)){error.value='英文码须为3至4个小写英文字母';return}
 const items:CategoryNode[]=JSON.parse(JSON.stringify(data.value.items))
 if(oldPath.value){items.flatMap(p=>[p,...p.children]).find(n=>n.id===oldPath.value)!.label=name}
 else {const id=parent.value?`${parent.value}-${code.value}`:code.value;if(items.flatMap(p=>[p,...p.children]).some(n=>n.id===id)){error.value='品类 ID 已存在';return}const node={id,label:name,children:[]};if(parent.value)items.find(p=>p.id===parent.value)!.children.push(node);else items.push(node)}
 void persist(items)
}
async function remove(id:string){try{await ElMessageBox.confirm('删除此目录及其子目录？已被产品使用的目录无法删除。','删除目录',{type:'warning',confirmButtonText:'删除',cancelButtonText:'取消'})}catch{return}let items:CategoryNode[]=JSON.parse(JSON.stringify(data.value.items));items=items.filter(p=>p.id!==id).map(p=>({...p,children:p.children.filter(c=>c.id!==id)}));await persist(items)}onScopeDispose(()=>life.abort());void load()
</script>
<template><CatalogNavigation/><div class="page-heading"><div><p class="eyebrow">PRODUCT DIRECTORY</p><h1>产品目录</h1><p>平台共享的两级产品目录，所有租户共用，由平台超级管理员统一维护。</p></div><el-button v-if="writable" type="primary" :disabled="loading||busy" @click="open()">新增一级目录</el-button></div><section class="panel"><el-alert v-if="error" :title="error" type="error" :closable="false"/><div class="section-heading"><h2>目录结构</h2><el-button :disabled="busy" @click="load">刷新</el-button></div><el-table v-loading="loading" :data="rows" row-key="id" default-expand-all empty-text="暂无目录，请新增一级目录"><el-table-column prop="id" label="品类 ID" min-width="150"/><el-table-column prop="label" label="目录名称" min-width="240"/><el-table-column label="层级" width="130"><template #default="{row}"><el-tag effect="plain">{{row.id.includes('-')?'二级品类':'一级目录'}}</el-tag></template></el-table-column><el-table-column v-if="writable" label="操作" width="250"><template #default="{row}"><el-button v-if="!row.id.includes('-')" link type="primary" :disabled="busy" @click="open('',row.id)">新增子目录</el-button><el-button link type="primary" :disabled="busy" @click="open(row.id)">重命名</el-button><el-button link type="danger" :disabled="busy" @click="remove(row.id)">删除</el-button></template></el-table-column></el-table><p class="muted small">最多两级。同级名称不能重复；已被产品使用的品类及其父目录可直接改名；删除前需先调整产品归类。</p></section><el-dialog v-model="show" :title="oldPath?'重命名目录':parent?'新增二级品类':'新增一级目录'" width="min(480px,94vw)" :close-on-click-modal="!busy" :show-close="!busy" :close-on-press-escape="!busy"><form class="tenant-form" @submit.prevent="save"><p v-if="parent">上级目录：{{parent}}</p><label>英文码<el-input v-model="code" maxlength="4" :disabled="busy||!!oldPath" placeholder="3–4个小写英文字母"/></label><p class="muted small">ID：{{oldPath||(parent?`${parent}-${code}`:code)}}。创建后不可更改，名称可以修改。</p><label>目录名称<el-input v-model="label" maxlength="40" :disabled="busy"/></label><el-alert v-if="error" :title="error" type="error" :closable="false"/><div class="tenant-form-actions"><el-button :disabled="busy" @click="show=false">取消</el-button><el-button type="primary" native-type="submit" :loading="busy" :disabled="!label.trim()">保存</el-button></div></form></el-dialog></template>
