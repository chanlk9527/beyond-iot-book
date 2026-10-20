<script setup lang="ts">
import {schemaSummary,type ValueSchema} from './modelSchema'
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import type { DeviceDetail } from '../api/catalog'
import { resourceScope } from '../api/catalog'
import { useSessionStore } from '../stores/session'
import {stableModel} from './modelVersions'
const props=defineProps<{detail:DeviceDetail;scope:string}>()
const router=useRouter(),session=useSessionStore()
type Schema=ValueSchema
type Field={id:string;name?:string;schema:Schema;required:boolean}
type Property={id:string;name?:string;schema:Schema;access:string}
type Operation={id:string;inputs:Record<string,Field>}
type Event={id:string;fields:Record<string,Field>}
const model=computed(()=>props.detail.model.model)
const properties=computed(()=>Object.values(model.value.properties) as Property[])
const operations=computed(()=>Object.values(model.value.operations) as Operation[])
const events=computed(()=>Object.values(model.value.events) as Event[])
const empty=computed(()=>!properties.value.length&&!operations.value.length&&!events.value.length)
const draftChanged=computed(()=>stableModel(props.detail.product.draft)!==stableModel(model.value))
const newerPublished=computed(()=>props.detail.product.latestVersion&&props.detail.product.latestVersion!==props.detail.model.version)
const hasDraft=computed(()=>Object.values(props.detail.product.draft).some(definitions=>Object.keys(definitions).length>0))
const canOpenProduct=computed(()=>session.can('page:products')&&session.can('product:read'))
const access:Record<string,string>={READ_ONLY:'只读',WRITE_ONLY:'只写',READ_WRITE:'读写'}
const schemaText=schemaSummary
function fieldsText(fields:Record<string,Field>){return Object.values(fields).map(f=>(f.name?f.name+' ['+f.id+']':f.id)+'（'+schemaText(f.schema)+(f.required?'，必填':'，选填')+'）').join('；')||'无参数'}
function openProduct(tab:string){void router.push({path:resourceScope(props.scope,props.detail.product)+'/products/'+encodeURIComponent(props.detail.product.id),query:{tab}})}
</script>
<template>
 <div class="capabilities">
  <div class="section-heading"><div><h2>能力模型</h2><p>继承自产品 {{detail.product.name}} · 已发布版本 {{detail.model.version}}（当前绑定） · 产品最新发布 {{detail.product.latestVersion||'尚无'}}</p></div><el-button v-if="canOpenProduct" @click="openProduct('versions')">查看产品版本</el-button></div>
  <el-alert v-if="newerPublished" :title="'当前绑定 '+detail.model.version+'，产品最新发布 '+detail.product.latestVersion" type="info" :closable="false"><p>新版本已发布，当前设备继续使用原绑定版本。设备模型迁移与固件升级功能后续提供。</p></el-alert>
  <el-alert v-if="draftChanged" :title="empty&&hasDraft?'绑定版本为空，产品草稿中已有能力定义':'产品草稿与设备绑定版本存在差异'" type="warning" :closable="false">
   <p>此处展示设备绑定的 {{detail.model.version}}，草稿差异不代表尚未发布。请到产品物模型页查看草稿发布状态；发布新版本不会自动改变已有设备的绑定。</p>
   <el-button v-if="canOpenProduct" link type="primary" @click="openProduct('model')">查看产品物模型草稿</el-button>
  </el-alert>
  <div class="capability-counts"><span>{{properties.length}} 项属性</span><span>{{operations.length}} 项操作</span><span>{{events.length}} 项事件</span></div>
  <div v-if="empty" class="empty-model"><h3>当前绑定版本未定义任何能力</h3><p>产品 {{detail.product.name}} 的 {{detail.model.version}} 发布时为空模型。此处显示已发布版本，不会自动使用产品草稿。</p></div>
  <template v-else>
   <h3>属性</h3><el-table :data="properties" empty-text="该版本未定义属性"><el-table-column prop="name" label="名称" min-width="140"/><el-table-column prop="description" label="说明" min-width="180" show-overflow-tooltip/><el-table-column prop="id" label="标识符" min-width="160"/><el-table-column label="类型与约束" min-width="240"><template #default="{row}">{{schemaText(row.schema)}}</template></el-table-column><el-table-column label="访问方式" width="120"><template #default="{row}">{{access[row.access]||row.access}}</template></el-table-column></el-table>
   <h3>操作</h3><el-table :data="operations" empty-text="该版本未定义操作"><el-table-column prop="name" label="名称" min-width="140"/><el-table-column prop="description" label="说明" min-width="180" show-overflow-tooltip/><el-table-column prop="id" label="标识符" min-width="160"/><el-table-column label="输入参数" min-width="300"><template #default="{row}">{{fieldsText(row.inputs)}}</template></el-table-column></el-table>
   <h3>事件</h3><el-table :data="events" empty-text="该版本未定义事件"><el-table-column prop="name" label="名称" min-width="140"/><el-table-column prop="description" label="说明" min-width="180" show-overflow-tooltip/><el-table-column prop="id" label="标识符" min-width="160"/><el-table-column label="事件字段" min-width="300"><template #default="{row}">{{fieldsText(row.fields)}}</template></el-table-column></el-table>
  </template>
 </div>
</template>
<style scoped>
.capabilities h2{font-size:16px}.capabilities h3{font-size:14px;margin:24px 0 14px}.capabilities p{line-height:1.8}.capability-counts{display:flex;flex-wrap:wrap;gap:24px;margin:22px 0;color:#53766a}.empty-model{padding:20px;background:#f6faf8;border:1px solid #e3eee8;border-radius:8px}.empty-model h3{margin-top:0}.empty-model p{font-size:13px;color:#789188}.section-heading{margin-bottom:20px}
</style>

