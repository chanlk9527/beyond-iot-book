<script setup lang="ts">
import { computed, onMounted, onScopeDispose, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import CatalogNavigation from '../components/CatalogNavigation.vue'
import { useSessionStore } from '../stores/session'
import { catalogApi,catalogScope,resourceScope,type Product,type ModelVersion } from '../api/catalog'
import { errorMessage,isCancelled } from '../api/management'
import { productionApi,productionReady,productionStatus,type ProductionBatch,type ProductionDetail,type ProductionItem } from '../api/production'

const route=useRoute(), session=useSessionStore()
const scope=catalogScope(route.meta.catalogPlatform===true,route.params.tenantId)
const writable=computed(()=>session.can('production:write') && (!!session.account?.platformAdmin || ['OWNER','ADMIN'].includes(session.tenant?.role||'')))
const exportable=computed(()=>session.can('production:credential')&&session.can('device:credential')&&(!!session.account?.platformAdmin||['OWNER','ADMIN'].includes(session.tenant?.role||'')))
const rows=ref<ProductionBatch[]>([]),total=ref(0),page=ref(1),query=ref(''),loading=ref(false),error=ref(''),busy=ref(false),saveError=ref('')
const selected=ref<ProductionDetail|null>(null),detailLoading=ref(false),detailError=ref(''),eventPage=ref(1)
const createOpen=ref(false),products=ref<Product[]>([]),models=ref<ModelVersion[]>([]),productQuery=ref(''),productLoading=ref(false),modelLoading=ref(false)
const form=ref({code:'',name:'',productId:'',modelVersion:'',quantity:1,hardwareVersion:'',firmwareVersion:''})
const dialog=ref<''|'generate'|'export'|'burn'|'tests'|'release'|'deliver'>(''),target=ref<ProductionItem|null>(null)
const station=ref(''),note=ref(''),result=ref('PASS'),recipient=ref(''),deliveryRef=ref(''),exportConfirmed=ref(false)
const life=new AbortController();let listRequest=new AbortController(),detailRequest=new AbortController(),productRequest=new AbortController(),modelRequest=new AbortController()
let listSeq=0,detailSeq=0,productSeq=0,modelSeq=0
onScopeDispose(()=>{life.abort();listRequest.abort();detailRequest.abort();productRequest.abort();modelRequest.abort()})
const readyCount=computed(()=>selected.value?.items.filter(productionReady).length||0)
const allReady=computed(()=>!!selected.value&&selected.value.items.length===selected.value.batch.quantity&&readyCount.value===selected.value.batch.quantity)
const evidenceTitle=computed(()=>({generate:'生成批次设备',export:'导出烧录凭证',burn:'确认凭证烧录',tests:'登记出厂测试',release:'批次放行',deliver:'登记交付'}[dialog.value as Exclude<typeof dialog.value,''>]||''))
const canCreate=computed(()=>!!form.value.code.match(/^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$/)&&!!form.value.name.trim()&&!!form.value.hardwareVersion.trim()&&!!form.value.firmwareVersion.trim()&&models.value.some(m=>m.version===form.value.modelVersion)&&form.value.quantity>=1&&form.value.quantity<=50&&!modelLoading.value)
const canSubmit=computed(()=>dialog.value==='generate'||dialog.value==='export'&&exportConfirmed.value||dialog.value==='burn'&&!!station.value.trim()||dialog.value==='tests'&&!!station.value.trim()&&!!note.value.trim()||dialog.value==='release'&&!!note.value.trim()||dialog.value==='deliver'&&!!recipient.value.trim()&&!!deliveryRef.value.trim())
function date(value:number|null){return value===null?'—':new Date(value).toLocaleString()}
function batchScope(){return resourceScope(scope,selected.value!.batch)}
async function load(){
  listRequest.abort();listRequest=new AbortController();const seq=++listSeq;loading.value=true;error.value=''
  try{const data=await productionApi.list(scope,query.value,page.value,listRequest.signal);if(seq===listSeq){rows.value=data.items;total.value=data.total}}
  catch(e){if(!isCancelled(e)&&seq===listSeq)error.value=errorMessage(e)}finally{if(seq===listSeq)loading.value=false}
}
async function openBatch(batch:ProductionBatch,reset=true){
  if(busy.value)return
  if(reset){selected.value=null;eventPage.value=1}
  detailRequest.abort();detailRequest=new AbortController();const seq=++detailSeq;detailLoading.value=true;detailError.value=''
  try{const data=await productionApi.detail(resourceScope(scope,batch),batch.id,eventPage.value,detailRequest.signal);if(seq===detailSeq)selected.value=data}
  catch(e){if(!isCancelled(e)&&seq===detailSeq)detailError.value=errorMessage(e)}finally{if(seq===detailSeq)detailLoading.value=false}
}
async function findProducts(){
  productRequest.abort();productRequest=new AbortController();const seq=++productSeq;productLoading.value=true;saveError.value=''
  try{const data=await catalogApi.products(scope,productQuery.value,1,productRequest.signal);if(seq===productSeq)products.value=data.items.filter(p=>p.enabled&&p.latestVersion)}
  catch(e){if(!isCancelled(e)&&seq===productSeq)saveError.value=errorMessage(e)}finally{if(seq===productSeq)productLoading.value=false}
}
async function chooseProduct(){
  modelRequest.abort();modelRequest=new AbortController();const seq=++modelSeq;models.value=[];form.value.modelVersion='';modelLoading.value=true;saveError.value=''
  const product=products.value.find(p=>p.id===form.value.productId)
  try{if(product){const data=await catalogApi.models(resourceScope(scope,product),product.id,modelRequest.signal);if(seq===modelSeq){models.value=data;form.value.modelVersion=data[0]?.version||''}}}
  catch(e){if(!isCancelled(e)&&seq===modelSeq)saveError.value=errorMessage(e)}finally{if(seq===modelSeq)modelLoading.value=false}
}
function startCreate(){form.value={code:'',name:'',productId:'',modelVersion:'',quantity:1,hardwareVersion:'',firmwareVersion:''};models.value=[];createOpen.value=true;saveError.value='';void findProducts()}
async function create(){
  if(!canCreate.value||busy.value)return
  detailRequest.abort();++detailSeq;detailLoading.value=false;busy.value=true;saveError.value=''
  try{selected.value=await productionApi.create(scope,form.value,life.signal);eventPage.value=1;createOpen.value=false;await load();ElMessage.success('批次已创建，产品和模型版本已固定')}
  catch(e){if(!isCancelled(e))saveError.value=errorMessage(e)}finally{busy.value=false}
}
function prepare(kind:typeof dialog.value,item?:ProductionItem){dialog.value=kind;target.value=item||null;station.value='';note.value='';recipient.value='';deliveryRef.value='';result.value='PASS';exportConfirmed.value=false;saveError.value=''}
async function submit(){
  if(!selected.value||!canSubmit.value||busy.value)return
  detailRequest.abort();++detailSeq;detailLoading.value=false
  const b=selected.value.batch,kind=dialog.value;busy.value=true;saveError.value=''
  try{
    if(kind==='export'){
      const credentials=await productionApi.credentials(batchScope(),b.id,b.revision,life.signal)
      life.signal.throwIfAborted()
      const url=URL.createObjectURL(new Blob([JSON.stringify({batchCode:b.code,productId:b.productId,modelVersion:b.modelVersion,hardwareVersion:b.hardwareVersion,firmwareVersion:b.firmwareVersion,devices:credentials},null,2)],{type:'application/json'}))
      const a=document.createElement('a');a.href=url;a.download=`${b.code}-provisioning.json`;a.click();URL.revokeObjectURL(url)
      selected.value=await productionApi.detail(batchScope(),b.id,eventPage.value,life.signal)
    }else{
      let data:ProductionDetail
      if(kind==='generate')data=await productionApi.generate(batchScope(),b.id,b.revision,life.signal)
      else if(kind==='burn'||kind==='tests')data=await productionApi.evidence(batchScope(),b.id,target.value!.deviceId,kind,{revision:b.revision,credentialVersion:target.value!.credentialVersion,station:station.value,note:note.value,result:result.value},life.signal)
      else if(kind==='release')data=await productionApi.release(batchScope(),b.id,b.revision,note.value,life.signal)
      else if(kind==='deliver')data=await productionApi.deliver(batchScope(),b.id,b.revision,{recipient:recipient.value,deliveryRef:deliveryRef.value,note:note.value},life.signal)
      else return
      selected.value=data;eventPage.value=1;await load()
    }
    dialog.value='';ElMessage.success(kind==='export'?'凭证文件已下载':'操作已记录')
  }catch(e){if(!isCancelled(e))saveError.value=errorMessage(e)}finally{busy.value=false}
}
const actionNames:Record<string,string>={BATCH_CREATED:'创建批次',DEVICES_GENERATED:'生成设备',CREDENTIALS_EXPORTED:'导出凭证',BURN_CONFIRMED:'确认烧录',FACTORY_TEST:'出厂测试',BATCH_RELEASED:'批次放行',BATCH_DELIVERED:'登记交付'}
onMounted(()=>{void load()})
</script>

<template>
  <CatalogNavigation/>
  <section class="page-heading"><div><span class="eyebrow">PRODUCTION & DELIVERY</span><h1>生产与交付</h1><p>从设备生产到交付，保留每一步的版本、结果和操作记录。</p></div><el-button v-if="writable&&session.can('product:read')" type="primary" :disabled="busy" @click="startCreate">新建生产批次</el-button></section>
  <section class="panel production-panel">
    <div class="tenant-toolbar"><el-input v-model="query" placeholder="搜索批次编码或名称" aria-label="搜索生产批次" maxlength="100" :disabled="busy" @keyup.enter="page=1;load()"/><el-button :loading="loading" :disabled="busy" @click="page=1;load()">查询</el-button></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false"/>
    <el-table v-loading="loading" :data="rows" empty-text="暂无生产批次" row-key="id">
      <el-table-column prop="code" label="批次编码" min-width="150"/><el-table-column prop="name" label="批次名称" min-width="150"/>
      <el-table-column prop="productName" label="产品" min-width="140"/><el-table-column prop="modelVersion" label="模型" width="80"/>
      <el-table-column prop="quantity" label="数量" width="70"/><el-table-column label="状态" width="110"><template #default="{row}"><el-tag :type="row.status==='DELIVERED'?'success':'info'">{{productionStatus[row.status as ProductionBatch['status']]}}</el-tag></template></el-table-column>
      <el-table-column label="操作" width="100"><template #default="{row}"><el-button link type="primary" :disabled="busy" @click="openBatch(row)">查看批次</el-button></template></el-table-column>
    </el-table>
    <el-pagination v-if="total" v-model:current-page="page" :total="total" :page-size="20" layout="total, prev, pager, next" :disabled="busy" @current-change="load"/>
  </section>
  <el-alert v-if="detailError" :title="detailError" type="error" :closable="false"/>
  <section v-if="selected" v-loading="detailLoading" class="panel production-panel">
    <div class="detail-heading"><div><h2>{{selected.batch.name}}</h2><p class="muted">{{selected.batch.code}} · {{productionStatus[selected.batch.status]}}</p></div><el-button :disabled="busy" @click="openBatch(selected.batch,false)">刷新批次</el-button></div>
    <dl class="detail-grid"><div><dt>产品 / 模型</dt><dd>{{selected.batch.productName}} / {{selected.batch.modelVersion}}</dd></div><div><dt>硬件 / 固件</dt><dd>{{selected.batch.hardwareVersion}} / {{selected.batch.firmwareVersion}}</dd></div><div><dt>当前合格 / 计划数量</dt><dd>{{readyCount}} / {{selected.batch.quantity}}</dd></div><div><dt>创建时间</dt><dd>{{date(selected.batch.createdAt)}}</dd></div></dl>
    <el-alert title="烧录和测试为人工确认记录。生产状态不代表设备在线；交付不改变租户归属或 App 绑定关系。" type="info" :closable="false"/>
    <div class="production-actions">
      <el-button v-if="writable&&session.can('device:write')&&session.can('device:credential')&&selected.batch.status==='PLANNED'" type="primary" :disabled="busy" @click="prepare('generate')">生成设备与凭证</el-button>
      <el-button v-if="exportable&&['PRODUCING','RELEASED'].includes(selected.batch.status)" :disabled="busy" @click="prepare('export')">导出烧录凭证</el-button>
      <el-button v-if="writable&&selected.batch.status==='PRODUCING'" type="primary" :disabled="busy||!allReady" @click="prepare('release')">整批放行</el-button>
      <el-button v-if="writable&&selected.batch.status==='RELEASED'" type="primary" :disabled="busy||!allReady" @click="prepare('deliver')">登记交付</el-button>
      <span v-if="selected.batch.status==='PRODUCING'&&!allReady" class="muted small">全部设备使用当前凭证完成烧录并通过测试后可放行。</span>
    </div>
    <div v-if="selected.batch.status==='DELIVERED'" class="delivery-summary"><strong>交付对象：{{selected.batch.recipient}}</strong><span>交付单号：{{selected.batch.deliveryRef}}</span><span>交付时间：{{date(selected.batch.deliveredAt)}}</span></div>
    <el-table :data="selected.items" empty-text="生成设备后显示生产明细">
      <el-table-column prop="code" label="设备编码" min-width="160"/><el-table-column prop="deviceId" label="设备 ID" min-width="180"/>
      <el-table-column label="凭证 / 烧录版本" width="150"><template #default="{row}">v{{row.credentialVersion}} / {{row.burnedVersion===null?'未确认':'v'+row.burnedVersion}}</template></el-table-column>
      <el-table-column label="出厂测试" width="130"><template #default="{row}"><el-tag :type="productionReady(row)?'success':row.testResult==='FAIL'?'danger':'info'">{{row.testedVersion!==row.credentialVersion&&row.testResult?'需重新测试':row.testResult==='PASS'?'合格':row.testResult==='FAIL'?'不合格':'待测试'}}</el-tag></template></el-table-column>
      <el-table-column v-if="writable&&['PRODUCING','RELEASED'].includes(selected.batch.status)" label="记录" width="190"><template #default="{row}"><el-button link type="primary" :disabled="busy||row.lifecycle!=='ACTIVE'" @click="prepare('burn',row)">确认烧录</el-button><el-button link type="primary" :disabled="busy||row.lifecycle!=='ACTIVE'||row.burnedVersion!==row.credentialVersion" @click="prepare('tests',row)">出厂测试</el-button></template></el-table-column>
    </el-table>
    <h3>操作与测试记录</h3><p class="muted small">保留历次记录，每页 100 条；重新烧录或登记测试会撤回原批次放行状态。</p>
    <el-table :data="selected.events" empty-text="暂无操作记录"><el-table-column label="时间" min-width="180"><template #default="{row}">{{date(row.occurredAt)}}</template></el-table-column><el-table-column label="操作" min-width="120"><template #default="{row}">{{actionNames[row.action]||row.action}}</template></el-table-column><el-table-column prop="actor" label="操作人" width="100"/><el-table-column prop="deviceId" label="设备 ID" min-width="170"/><el-table-column prop="station" label="工位" min-width="100"/><el-table-column prop="result" label="结果" width="110"/><el-table-column prop="note" label="记录说明" min-width="200"/></el-table>
    <el-pagination v-if="selected.totalEvents>100" v-model:current-page="eventPage" :total="selected.totalEvents" :page-size="100" layout="total, prev, pager, next" :disabled="busy" @current-change="openBatch(selected!.batch,false)"/>
  </section>
  <div v-else-if="detailLoading" class="panel production-panel" v-loading="true" style="min-height:120px"></div>

  <el-dialog v-model="createOpen" title="新建生产批次" width="min(600px,94vw)" :show-close="!busy" :close-on-click-modal="!busy" :close-on-press-escape="!busy">
    <form class="tenant-form" @submit.prevent="create"><label>批次编码</label><el-input v-model="form.code" maxlength="40" placeholder="如 WAR-202610-A" :disabled="busy"/><label>批次名称</label><el-input v-model="form.name" maxlength="100" :disabled="busy"/>
      <label>查找产品</label><div class="tenant-search"><el-input v-model="productQuery" maxlength="100" placeholder="产品名称或编码" :disabled="busy"/><el-button :loading="productLoading" :disabled="busy" @click="findProducts">查找</el-button></div><el-select v-model="form.productId" placeholder="选择启用且已发布的产品" :disabled="busy||productLoading" aria-label="生产产品" @change="chooseProduct"><el-option v-for="p in products" :key="p.id" :label="p.name+' · '+p.code" :value="p.id"/></el-select>
      <label>固定模型版本</label><el-select v-model="form.modelVersion" :loading="modelLoading" :disabled="busy||modelLoading" aria-label="生产模型版本"><el-option v-for="m in models" :key="m.version" :label="m.version" :value="m.version"/></el-select>
      <label>计划数量（1～50）</label><el-input-number v-model="form.quantity" :min="1" :max="50" :precision="0" :disabled="busy"/><label>硬件版本</label><el-input v-model="form.hardwareVersion" maxlength="100" :disabled="busy"/><label>目标固件版本</label><el-input v-model="form.firmwareVersion" maxlength="100" :disabled="busy"/>
      <p class="muted small">批次创建后固定产品、模型与数量。设备编码按“批次编码-序号”生成，设备继承产品归属。</p><el-alert v-if="saveError" :title="saveError" type="error" :closable="false"/><div class="tenant-form-actions"><el-button :disabled="busy" @click="createOpen=false">取消</el-button><el-button type="primary" native-type="submit" :loading="busy" :disabled="!canCreate">创建批次</el-button></div>
    </form>
  </el-dialog>
  <el-dialog :model-value="!!dialog" :title="evidenceTitle" width="min(600px,94vw)" :show-close="!busy" :close-on-click-modal="!busy" :close-on-press-escape="!busy" @update:model-value="(open:boolean)=>{if(!open)dialog=''}">
    <form class="tenant-form" @submit.prevent="submit">
      <p v-if="dialog==='generate'">将为此批次一次生成 {{selected?.batch.quantity}} 台启用设备和独立凭证，设备可用于联网测试。设备生成后保留生产追溯记录。</p>
      <template v-if="dialog==='export'"><el-alert title="下载文件包含整批设备密钥，请仅交给授权烧录人员并妥善保管。导出操作会留痕。" type="warning" :closable="false"/><el-checkbox v-model="exportConfirmed" :disabled="busy">确认下载包含设备密钥的 JSON 文件</el-checkbox></template>
      <template v-if="dialog==='burn'||dialog==='tests'"><p>设备：{{target?.code}} · 当前凭证 v{{target?.credentialVersion}}</p><el-alert v-if="selected?.batch.status==='RELEASED'" title="新增烧录或测试记录后，批次需重新放行。" type="warning" :closable="false"/><label>工位 / 测试台</label><el-input v-model="station" maxlength="100" :disabled="busy"/><template v-if="dialog==='tests'"><label>测试结果</label><el-select v-model="result" aria-label="出厂测试结果" :disabled="busy"><el-option label="合格" value="PASS"/><el-option label="不合格" value="FAIL"/></el-select></template></template>
      <template v-if="dialog==='deliver'"><label>交付对象</label><el-input v-model="recipient" maxlength="200" :disabled="busy"/><label>交付单号</label><el-input v-model="deliveryRef" maxlength="100" :disabled="busy"/><p class="muted small">登记整批交付；不转移设备租户，不改变 App 绑定。</p></template>
      <template v-if="dialog!=='generate'&&dialog!=='export'"><label>{{dialog==='tests'?'测试项目与结果说明（必填）':dialog==='release'?'放行依据（必填）':'记录说明'}}</label><el-input v-model="note" type="textarea" :rows="3" maxlength="1000" :disabled="busy"/></template>
      <el-alert v-if="saveError" :title="saveError+' 如版本已变化，请关闭对话框并刷新批次。'" type="error" :closable="false"/><div class="tenant-form-actions"><el-button :disabled="busy" @click="dialog=''">取消</el-button><el-button type="primary" native-type="submit" :loading="busy" :disabled="!canSubmit">{{dialog==='export'?'下载凭证文件':'确认'}}</el-button></div>
    </form>
  </el-dialog>
</template>
<style scoped>
.production-panel{padding:24px;margin-bottom:24px}.production-panel .el-pagination{margin-top:20px}.tenant-toolbar{display:flex;gap:12px;margin-bottom:20px}.tenant-toolbar .el-input{max-width:360px}.detail-heading{display:flex;justify-content:space-between;align-items:center;gap:16px}.detail-heading h2{margin:0}.production-actions{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin:20px 0}.production-actions .el-button+.el-button{margin-left:0}.delivery-summary{display:flex;flex-wrap:wrap;gap:20px;padding:18px;background:var(--el-fill-color-light);margin-bottom:16px}.detail-grid{margin:20px 0}.production-panel h3{margin-top:28px}
</style>
