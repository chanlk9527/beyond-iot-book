<script setup lang="ts">
import {computed,onScopeDispose,ref,watch} from 'vue'
import {onBeforeRouteLeave,onBeforeRouteUpdate,useRoute,useRouter} from 'vue-router'
import {ElMessage,ElMessageBox} from 'element-plus'
import {catalogApi,catalogScope,warmerModel,type Product,type ModelVersion} from '../api/catalog'
import {errorMessage,isCancelled} from '../api/management'
import {useSessionStore} from '../stores/session'
import CatalogNavigation from '../components/CatalogNavigation.vue'
import StatePanel from '../components/StatePanel.vue'
import ThingModelEditor from '../components/ThingModelEditor.vue'
import ProductConfigurationForm from '../components/ProductConfigurationForm.vue'
import DevicesView from './DevicesView.vue'
import {productChanges,productTabPayload,type ProductEditTab} from '../components/productTabs'
import {stableModel,modelCounts,modelDiff,emptyModel} from '../components/modelVersions'
const route=useRoute(),router=useRouter(),session=useSessionStore()
let scope=catalogScope(route.meta.catalogPlatform===true,route.params.tenantId),id=String(route.params.id)
const writable=computed(()=>!!session.account?.platformAdmin||(route.meta.catalogPlatform!==true&&['OWNER','ADMIN'].includes((session.tenant?.id===route.params.tenantId?session.tenant.role:undefined)||'')))
const editable=computed(()=>writable.value&&session.can('product:write'))
const saved=ref<Product|null>(null),product=ref<Product|null>(null),versions=ref<ModelVersion[]>([])
const publishedSnapshot=ref<{version:string;model:Product['draft']}|null>(null)
const loading=ref(true),error=ref(''),actionError=ref(''),busy=ref(false),modelText=ref(''),showModel=ref(false),publishedText=ref(''),publishedVersion=ref('')
const tabs=computed(()=>[{id:'basic',name:'基本信息'},{id:'access',name:'设备接入'},{id:'model',name:'物模型'},{id:'devices',name:'设备列表'},{id:'versions',name:'版本管理'}].filter(tab=>tab.id!=='devices'||session.can('page:devices')))
const active=computed({get:()=>tabs.value.some(t=>t.id===route.query.tab)?String(route.query.tab):'basic',set:(tab:string)=>{void router.replace({query:{...route.query,tab}})}})
const devicesVisited=ref(false)
watch(active,tab=>{if(tab==='devices')devicesVisited.value=true})
const changes=computed(()=>product.value&&saved.value?productChanges(saved.value,product.value,modelText.value):{basic:false,access:false,model:false})
const dirty=computed(()=>Object.values(changes.value).some(Boolean))
const latestModel=computed(()=>versions.value.find(v=>v.version===saved.value?.latestVersion)||(publishedSnapshot.value?.version===saved.value?.latestVersion?publishedSnapshot.value:null))
const draftPublished=computed(()=>!!latestModel.value&&stableModel(latestModel.value.model)===stableModel(saved.value?.draft))
const draftStatus=computed(()=>changes.value.model?'有未保存修改':draftPublished.value?'已保存 · 与最新发布版一致':'已保存 · 尚未发布')
const counts=computed(()=>saved.value?modelCounts(saved.value.draft):{properties:0,operations:0,events:0})
const differences=computed(()=>saved.value?modelDiff(latestModel.value?.model,saved.value.draft):[])
const canPublish=computed(()=>writable.value&&session.can('product:publish')&&!busy.value&&!changes.value.model&&saved.value?.enabled&&!draftPublished.value)
const groupLabels={properties:'属性',operations:'操作',events:'事件'}
const life=new AbortController();let loadGeneration=0
const clone=<T,>(v:T):T=>JSON.parse(JSON.stringify(v))
async function load(){const generation=++loadGeneration;scope=catalogScope(route.meta.catalogPlatform===true,route.params.tenantId);id=String(route.params.id);loading.value=true;error.value='';actionError.value='';saved.value=null;product.value=null;publishedSnapshot.value=null;devicesVisited.value=false;try{const [p,v]=await Promise.all([catalogApi.product(scope,id,life.signal),catalogApi.models(scope,id,life.signal)]);if(generation!==loadGeneration)return;saved.value=clone(p);product.value=clone(p);modelText.value=JSON.stringify(p.draft,null,2);versions.value=v;devicesVisited.value=active.value==='devices'}catch(e){if(generation===loadGeneration&&!isCancelled(e))error.value=errorMessage(e)}finally{if(generation===loadGeneration)loading.value=false}}
async function save(tab:ProductEditTab){if(!product.value||!saved.value||busy.value)return;busy.value=true;actionError.value='';try{const payload=productTabPayload(saved.value,product.value,modelText.value,tab);const result=await catalogApi.save(scope,payload,payload.draft,life.signal);saved.value=clone(result);product.value.revision=result.revision;product.value.enabled=result.enabled;product.value.latestVersion=result.latestVersion;if(tab==='basic'){product.value.name=result.name;product.value.description=result.description;for(const key of ['category','manufacturer','modelNumber','firmwareVersion','supportUrl','installationNotes'] as const)product.value.configuration[key]=result.configuration[key]}else if(tab==='access'){for(const key of ['deviceType','networkWay','transportProtocol','messageProtocol','dataFormat'] as const)(product.value.configuration as any)[key]=result.configuration[key]}else{product.value.draft=clone(result.draft);modelText.value=JSON.stringify(result.draft,null,2)}ElMessage.success('当前页修改已保存')}catch(e){if(!isCancelled(e))actionError.value=e instanceof SyntaxError?'物模型 JSON 格式错误，请检查后保存。':errorMessage(e)}finally{busy.value=false}}
async function publish(){
 if(!saved.value||!canPublish.value)return
 busy.value=true;actionError.value=''
 try{
  const isEmpty=emptyModel(saved.value.draft)
  const summary=differences.value.map(d=>`${groupLabels[d.group]}：新增 ${d.added.length}，修改 ${d.changed.length}，移除 ${d.removed.length}`).join('；')
  try{await ElMessageBox.confirm(`${isEmpty?'当前草稿为空，发布后该版本没有任何属性、操作或事件。\n':''}${summary}。\n版本号由系统自动递增分配，已发布内容不可修改，已有设备仍使用原绑定版本。`,'确认发布已保存草稿',{confirmButtonText:isEmpty?'确认发布空模型':'确认发布',cancelButtonText:'返回检查',type:isEmpty?'warning':'info'})}catch{return}
  const p=await catalogApi.publish(scope,saved.value,isEmpty,life.signal)
  saved.value=clone(p);if(product.value){product.value.revision=p.revision;product.value.latestVersion=p.latestVersion}
  publishedSnapshot.value={version:p.latestVersion!,model:clone(p.draft)}
  ElMessage.success(`物模型 ${p.latestVersion} 已发布`)
  try{versions.value=await catalogApi.models(scope,id,life.signal)}catch(e){if(!isCancelled(e))actionError.value=`版本 ${p.latestVersion} 已发布，但版本列表刷新失败：${errorMessage(e)}。请刷新版本列表。`}
 }catch(e){if(!isCancelled(e))actionError.value=errorMessage(e)}finally{busy.value=false}
}
async function refreshVersions(){if(busy.value)return;busy.value=true;actionError.value='';try{versions.value=await catalogApi.models(scope,id,life.signal)}catch(e){if(!isCancelled(e))actionError.value=errorMessage(e)}finally{busy.value=false}}
async function status(){if(!saved.value||busy.value)return;busy.value=true;actionError.value='';try{const p=await catalogApi.status(scope,saved.value,life.signal);saved.value=clone(p);if(product.value){product.value.enabled=p.enabled;product.value.revision=p.revision}ElMessage.success('产品状态已更新')}catch(e){if(!isCancelled(e))actionError.value=errorMessage(e)}finally{busy.value=false}}
async function remove(){if(!saved.value||saved.value.enabled||busy.value)return;busy.value=true;actionError.value='';try{try{await ElMessageBox.confirm(`确定删除产品“${saved.value.name}”及其全部物模型版本？删除后无法恢复，产品下有设备时无法删除。${dirty.value?'未保存的修改也将丢失。':''}`,'删除产品',{confirmButtonText:'删除产品',cancelButtonText:'取消',type:'warning'})}catch{return}await catalogApi.remove(scope,saved.value,life.signal);product.value=null;saved.value=null;busy.value=false;ElMessage.success('产品已删除');await router.push(`${scope}/products`)}catch(e){if(!isCancelled(e))actionError.value=errorMessage(e)}finally{busy.value=false}}
async function canLeave(){if(busy.value)return false;if(!dirty.value)return true;try{await ElMessageBox.confirm('还有未保存的修改，离开后将丢失。是否离开？','未保存的修改',{confirmButtonText:'离开',cancelButtonText:'继续编辑',type:'warning'});return true}catch{return false}}
onBeforeRouteLeave(canLeave)
onBeforeRouteUpdate(async(to,from)=>{if(to.path!==from.path)return canLeave();return true})
function unload(e:BeforeUnloadEvent){if(dirty.value){e.preventDefault();e.returnValue=''}}
window.addEventListener('beforeunload',unload)
watch(()=>route.path,()=>{void load()})
onScopeDispose(()=>{loadGeneration++;life.abort();window.removeEventListener('beforeunload',unload)})
void load()
</script>
<template>
<CatalogNavigation/>
<div class="page-heading"><div><p class="eyebrow">PRODUCT DETAIL</p><h1>{{saved?.name||'产品详情'}}</h1><p v-if="saved">{{saved.code}} · 最新模型版本 {{saved.latestVersion||'未发布'}} <el-tag :type="saved.enabled?'success':'info'">{{saved.enabled?'启用':'停用'}}</el-tag></p></div><div><el-button v-if="saved&&writable&&session.can('product:publish')" :disabled="busy" @click="status">{{saved.enabled?'停用产品':'启用产品'}}</el-button><el-button v-if="saved&&editable" type="danger" :disabled="busy||saved.enabled" :title="saved.enabled?'请先停用产品':'仅无设备的产品可删除'" @click="remove">删除产品</el-button><el-button @click="router.push(`${scope}/products`)">返回列表</el-button></div></div>
<section v-if="loading" class="panel tenant-context-loading"><el-skeleton :rows="6" animated/></section>
<section v-else-if="error" class="panel"><StatePanel title="产品加载失败" :description="error"><el-button @click="load">重试</el-button></StatePanel></section>
<section v-else-if="product&&saved" class="panel product-detail-tabs">
<el-alert v-if="actionError" :title="actionError" type="error" :closable="false"/>
<el-tabs v-model="active">
<el-tab-pane v-for="tab in tabs" :key="tab.id" :name="tab.id"><template #label>{{tab.name}}<span v-if="changes[tab.id as ProductEditTab]" class="unsaved-dot" aria-label="有未保存修改"> ●</span></template></el-tab-pane>
</el-tabs>
<div v-show="active==='basic'" class="tenant-form"><h2>基本信息</h2><dl class="detail-grid"><div><dt>产品编码</dt><dd>{{product.code}}</dd></div><div><dt>产品 ID</dt><dd class="mono">{{product.id}}</dd></div></dl><label for="edit-product-name">产品名称（必填）</label><el-input id="edit-product-name" v-model="product.name" aria-required="true" maxlength="100" :disabled="busy||!editable"/><label>产品描述（选填）</label><el-input v-model="product.description" type="textarea" maxlength="1000" aria-label="产品描述" :disabled="busy||!editable"/><ProductConfigurationForm v-model="product.configuration" section="basic" :disabled="busy||!editable"/><div class="tenant-form-actions"><el-button v-if="editable" type="primary" :disabled="!changes.basic||!product.name.trim()" :loading="busy" @click="save('basic')">保存基本信息</el-button></div></div>
<div v-show="active==='access'"><h2>设备接入</h2><ProductConfigurationForm v-model="product.configuration" section="access" :disabled="busy||!editable"/><el-alert title="MQTT 5 默认端口 1883；登记并启用设备后，可使用后台凭证连接。产品及所属租户必须启用。" type="info" :closable="false"/><h3>接入步骤</h3><ol><li>确认产品接入方案与消息协议。</li><li>在物模型中定义能力，保存并发布模型版本。</li><li>在设备列表登记并启用设备，使用当前设备凭证连接 MQTT 5。</li></ol><p class="muted">网络服务地址、端口及 TLS 由平台部署配置管理。</p><div class="tenant-form-actions"><el-button v-if="editable" type="primary" :disabled="!changes.access" :loading="busy" @click="save('access')">保存设备接入</el-button></div></div>
<div v-show="active==='model'">
 <div class="section-heading"><div><h2>物模型草稿 <el-tag :type="changes.model?'warning':draftPublished?'success':'info'">{{draftStatus}}</el-tag></h2><p>最新发布版：{{saved.latestVersion||'尚无'}}。保存只更新草稿，发布后才能供新设备选择。</p></div><el-button v-if="editable" :disabled="busy" @click="modelText=JSON.stringify(warmerModel,null,2)">填入暖奶器示例</el-button></div>
 <ThingModelEditor v-model="modelText" :disabled="busy||!editable"/>
 <div class="tenant-form-actions"><el-button v-if="editable" type="primary" :disabled="!changes.model" :loading="busy" @click="save('model')">保存物模型草稿</el-button><el-button v-if="writable&&session.can('product:publish')" :disabled="!canPublish" :loading="busy" @click="publish">发布已保存草稿（自动编号）</el-button></div>
 <p class="muted">{{changes.model?'请先保存当前编辑内容，再发布。':draftPublished?'当前草稿已发布，无需重复发布。':'草稿已保存，发布时将生成下一个模型版本。'}}已有设备的模型绑定保持不变。</p>
</div>
<div v-if="devicesVisited" v-show="active==='devices'"><DevicesView v-if="session.can('page:devices')" :key="saved.id" :product="saved"/><StatePanel v-else title="暂无设备查看权限" description="请联系管理员授予设备读取权限。"/></div>
<div v-show="active==='versions'">
 <div class="section-heading"><div><h2>已发布物模型版本</h2><p>最新发布版：{{saved.latestVersion||'尚无'}} · 草稿状态：{{draftStatus}}</p></div><el-button :disabled="busy" @click="active='model'">编辑物模型草稿</el-button></div>
 <p class="muted">版本号由系统按 v1、v2、v3 自动递增；版本内容不可覆盖。已有设备继续使用原绑定版本，固件升级与模型迁移后续提供。</p>
 <el-alert v-if="changes.model" title="物模型存在未保存修改，请先到物模型页保存草稿。" type="warning" :closable="false"/>
 <el-alert v-else-if="draftPublished" title="草稿与最新发布版一致，无需重复发布。" type="success" :closable="false"/>
 <div v-else class="publish-summary"><h3>待发布的已保存草稿</h3><p>{{counts.properties}} 项属性 · {{counts.operations}} 项操作 · {{counts.events}} 项事件</p><p v-for="diff in differences" :key="diff.group">{{groupLabels[diff.group]}}：新增 {{diff.added.length}}，修改 {{diff.changed.length}}，移除 {{diff.removed.length}}<span v-if="diff.added.length"> · 新增 {{diff.added.join('、')}}</span><span v-if="diff.changed.length"> · 修改 {{diff.changed.join('、')}}</span><span v-if="diff.removed.length"> · 移除 {{diff.removed.join('、')}}</span></p><el-alert v-if="emptyModel(saved.draft)" title="当前是空物模型，发布前需要再次确认。" type="warning" :closable="false"/></div>
 <el-button v-if="writable&&session.can('product:publish')" type="primary" :disabled="!canPublish" :loading="busy" @click="publish">发布已保存草稿（自动编号）</el-button>
 <p v-if="!saved.enabled" class="muted">产品已停用，启用后才能发布。</p>
 <el-button :disabled="busy" @click="refreshVersions">刷新版本列表</el-button><el-table :data="versions" empty-text="尚未发布物模型版本"><el-table-column label="模型版本" width="180"><template #default="{row}">{{row.version}} <el-tag v-if="row.version===saved.latestVersion" size="small">最新发布</el-tag></template></el-table-column><el-table-column label="能力数量" min-width="220"><template #default="{row}">{{modelCounts(row.model).properties}} 属性 / {{modelCounts(row.model).operations}} 操作 / {{modelCounts(row.model).events}} 事件</template></el-table-column><el-table-column prop="deviceCount" label="绑定设备数" width="120"/><el-table-column label="发布时间" min-width="180"><template #default="{row}">{{new Date(row.publishedAt).toLocaleString('zh-CN')}}</template></el-table-column><el-table-column label="操作" width="120"><template #default="{row}"><el-button link type="primary" @click="publishedVersion=row.version;publishedText=JSON.stringify(row.model,null,2);showModel=true">查看模型</el-button></template></el-table-column></el-table>
</div>
</section>
<el-dialog v-model="showModel" :title="'已发布物模型 '+publishedVersion+'（只读）'" width="min(760px,94vw)"><pre class="published-model">{{publishedText}}</pre></el-dialog>
</template>
<style scoped>.product-detail-tabs{padding:24px}.unsaved-dot{color:var(--el-color-warning)}.product-detail-tabs h2{margin:16px 0}.product-detail-tabs :deep(.page-heading h1){font-size:22px}.product-detail-tabs ol{line-height:2}.product-detail-tabs :deep(.tenant-context-info){margin-top:12px}</style>
