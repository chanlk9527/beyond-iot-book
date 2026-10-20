<script setup lang="ts">
import {computed,ref,onBeforeUpdate} from 'vue'
import {ElMessageBox,ElRadioGroup,ElRadioButton} from 'element-plus'
import type {ModelSpec} from '../api/catalog'
import {validateSchema,schemaSummary} from './modelSchema'
import ModelSchemaFields from './ModelSchemaFields.vue'
const text=defineModel<string>({required:true})
const props=defineProps<{disabled?:boolean}>()
type Kind='properties'|'operations'|'events'
const labels:Record<Kind,string>={properties:'属性',operations:'功能（指令）',events:'事件'}
const hints:Record<Kind,string>={properties:'描述设备状态和数据，配置读写权限与取值范围。',operations:'定义平台可下发的指令，以及调用时需要的输入参数。',events:'定义设备主动上报的事件，以及事件携带的数据字段。'}
const kind=ref<Kind>('properties'),mode=ref('visual'),query=ref(''),dialog=ref(false),original=ref(''),failure=ref('')
const draft=ref<any>({}),params=ref<any[]>([])
const parsed=computed(()=>{try{const m=JSON.parse(text.value);if(!m||!['properties','operations','events'].every(k=>m[k]&&typeof m[k]==='object'&&!Array.isArray(m[k])))return null;return m as ModelSpec}catch{return null}})
const rows=computed(()=>Object.entries(parsed.value?.[kind.value]||{}).filter(([id,value])=>(id+' '+(value as any)?.name).toLowerCase().includes(query.value.toLowerCase())).map(([id,value])=>({key:id,...value as object})))
const schemaEditors=ref<InstanceType<typeof ModelSchemaFields>[]>([])
onBeforeUpdate(()=>{schemaEditors.value=[]})
const access:Record<string,string>={READ_ONLY:'只读',WRITE_ONLY:'只写',READ_WRITE:'读写'}
function schema(){return {type:'TEXT',minimum:null,maximum:null,maxLength:null}}
function open(key?:string){schemaEditors.value=[];failure.value='';original.value=key||'';draft.value=key?JSON.parse(JSON.stringify(parsed.value![kind.value][key])):{id:'',name:'',description:'',...(kind.value==='properties'?{schema:schema(),access:'READ_ONLY'}:{})};params.value=Object.values(draft.value[kind.value==='events'?'fields':'inputs']||{});dialog.value=true}
const validate=validateSchema
function metadata(v:any){if(!/^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(v.id))throw Error('标识符须以英文字母开头，仅含字母、数字、下划线，最长 64 字符');if([...v.name||''].length>100||[...v.description||''].length>1000)throw Error('名称最长 100 字符，说明最长 1000 字符')}
function apply(){if(props.disabled||!parsed.value)return;try{for(const editor of schemaEditors.value)editor.validate();const value=JSON.parse(JSON.stringify(draft.value));value.id=value.id.trim();if(!value.id)throw Error('请填写标识符');metadata(value);const m=JSON.parse(JSON.stringify(parsed.value));if(value.id!==original.value&&Object.hasOwn(m[kind.value],value.id))throw Error('标识符已存在');if(kind.value==='properties')validate(value.schema);else{const fields:Record<string,unknown>=Object.create(null);for(const p of params.value){p.id=p.id.trim();if(!p.id)throw Error('请填写所有参数标识符');metadata(p);if(Object.hasOwn(fields,p.id))throw Error('参数标识符不能重复');validate(p.schema);fields[p.id]=p}value[kind.value==='events'?'fields':'inputs']=fields}if(original.value)delete m[kind.value][original.value];Object.defineProperty(m[kind.value],value.id,{value,enumerable:true,configurable:true,writable:true});text.value=JSON.stringify(m,null,2);dialog.value=false}catch(e){failure.value=(e as Error).message}}
async function remove(key:string){try{await ElMessageBox.confirm(`删除“${key}”？保存草稿后生效。`,'删除模型定义',{type:'warning',confirmButtonText:'删除',cancelButtonText:'取消'});if(props.disabled||!parsed.value)return;const m=JSON.parse(JSON.stringify(parsed.value));delete m[kind.value][key];text.value=JSON.stringify(m,null,2)}catch{/* cancelled */}}
function summary(row:any){return row.schema?schemaSummary(row.schema):Object.keys(row.fields||row.inputs||{}).length+' 个参数'}
</script>
<template>
<div class="thing-model">
<div class="model-mode"><el-radio-group v-model="mode" aria-label="物模型编辑模式"><el-radio-button value="visual">可视化编辑</el-radio-button><el-radio-button value="json">JSON 定义</el-radio-button></el-radio-group><el-tag effect="plain" type="warning">草稿</el-tag></div>
<template v-if="mode==='visual'">
<el-alert v-if="!parsed" title="模型结构无法解析，请切换 JSON 定义修正 properties、operations、events 对象。" type="error" :closable="false"/>
<template v-else>
<el-tabs v-model="kind"><el-tab-pane v-for="(label,key) in labels" :key="key" :name="key" :label="`${label} · ${Object.keys(parsed[key]).length}`"/></el-tabs>
<p class="muted">{{hints[kind]}}</p>
<div class="model-toolbar"><el-input v-model="query" clearable placeholder="搜索标识符或名称" aria-label="搜索模型定义"/><el-button v-if="!disabled" type="primary" @click="open()">新增{{labels[kind]}}</el-button></div>
<el-table :data="rows" empty-text="暂无匹配的定义，可新增能力或调整搜索条件。"><el-table-column prop="name" label="名称" min-width="140"/><el-table-column prop="description" label="说明" min-width="180" show-overflow-tooltip/><el-table-column prop="key" label="标识符" min-width="160"/><el-table-column label="数据定义" min-width="220"><template #default="{row}">{{summary(row)}}</template></el-table-column><el-table-column v-if="kind==='properties'" label="读写权限" width="110"><template #default="{row}"><el-tag effect="plain">{{access[row.access]||row.access}}</el-tag></template></el-table-column><el-table-column v-if="(kind==='properties'&&$slots['data-policy'])||(kind==='events'&&$slots['event-policy'])" label="数据记录" min-width="250"><template #default="{row}"><slot :name="kind==='events'?'event-policy':'data-policy'" :property-id="row.key" :event-id="row.key"/></template></el-table-column><el-table-column label="操作" width="145"><template #default="{row}"><el-button link type="primary" @click="open(row.key)">{{disabled?'查看':'编辑'}}</el-button><el-button v-if="!disabled" link type="danger" @click="remove(row.key)">删除</el-button></template></el-table-column></el-table>
</template></template>
<template v-else><p class="muted">使用本平台模型格式；JSON 修改与可视化编辑同步，保存草稿后生效。</p><el-input v-model="text" type="textarea" :rows="18" class="model-editor" aria-label="物模型 JSON" :disabled="disabled"/></template>
<el-dialog v-model="dialog" :title="`${disabled?'查看':original?'编辑':'新增'}${labels[kind]}`" width="min(760px,94vw)" :close-on-click-modal="false">
<form class="tenant-form" @submit.prevent="apply"><label>名称<el-input v-model="draft.name" :disabled="disabled" maxlength="100" placeholder="例如 当前温度"/></label><label>说明<el-input v-model="draft.description" type="textarea" :disabled="disabled" maxlength="1000"/></label><label>标识符<el-input v-model="draft.id" :disabled="disabled" placeholder="例如 temperature、setTemperature"/></label>
<template v-if="kind==='properties'"><ModelSchemaFields :ref="(el:any)=>{if(el&&!schemaEditors.includes(el))schemaEditors.push(el)}" v-model="draft.schema" :disabled="disabled"/><label>读写权限<el-select v-model="draft.access" :disabled="disabled"><el-option v-for="(name,key) in access" :key="key" :label="name" :value="key"/></el-select></label></template>
<template v-else><div class="section-heading"><h3>{{kind==='events'?'事件字段':'输入参数'}}</h3><el-button v-if="!disabled" @click="params.push({id:'',name:'',description:'',schema:schema(),required:true})">添加参数</el-button></div><p v-if="!params.length" class="muted">暂无参数，此能力允许空参数。</p><div v-for="(p,i) in params" :key="i" class="model-param"><div class="model-toolbar"><el-input v-model="p.id" :disabled="disabled" placeholder="参数标识符" :aria-label="`参数 ${i+1} 标识符`"/><el-checkbox v-model="p.required" :disabled="disabled">必填</el-checkbox><el-button v-if="!disabled" type="danger" link @click="params.splice(i,1)">移除</el-button></div><el-input v-model="p.name" :disabled="disabled" maxlength="100" placeholder="参数名称"/><el-input v-model="p.description" :disabled="disabled" maxlength="1000" placeholder="参数说明"/><ModelSchemaFields :ref="(el:any)=>{if(el&&!schemaEditors.includes(el))schemaEditors.push(el)}" v-model="p.schema" :disabled="disabled"/></div></template>
<el-alert v-if="failure" :title="failure" type="error" :closable="false"/><div class="tenant-form-actions"><el-button @click="dialog=false">{{disabled?'关闭':'取消'}}</el-button><el-button v-if="!disabled" type="primary" native-type="submit">应用到草稿</el-button></div></form>
</el-dialog>
</div>
</template>
<style scoped>.model-mode,.model-toolbar{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-bottom:18px}.model-toolbar>.el-input{max-width:320px}.model-param{padding:18px;background:var(--el-fill-color-light);border:1px solid var(--el-border-color-light);border-radius:8px}.model-mode{flex-wrap:wrap}.thing-model :deep(.el-tabs__item){font-size:15px}.thing-model :deep(.el-table){margin-bottom:20px}</style>
