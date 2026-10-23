<script setup lang="ts">
import { computed,onScopeDispose,ref } from 'vue'
import { useRoute,useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { catalogApi,catalogScope,resourceScope,type Product,type Device,type Credential,type ModelVersion } from '../api/catalog'
import { snapshotRefresh } from '../realtime/snapshotRefresh'
import { useDeviceSubscription,readPresence } from '../realtime/deviceSubscription'
import { errorMessage,isCancelled } from '../api/management'
import { useSessionStore } from '../stores/session'
import CatalogNavigation from '../components/CatalogNavigation.vue'
import StatePanel from '../components/StatePanel.vue'
import FirmwareSummary from '../components/FirmwareSummary.vue'
import {deviceFirmwareApi,type FirmwareInventory} from '../api/deviceFirmware'
const props=defineProps<{product?:Product}>()
const route=useRoute(),router=useRouter(),session=useSessionStore(),scope=catalogScope(route.meta.catalogPlatform===true,route.params.tenantId)
const writable=computed(()=>!!session.account?.platformAdmin || (route.meta.catalogPlatform!==true && ['OWNER','ADMIN'].includes((session.tenant?.id===route.params.tenantId ? session.tenant.role : undefined)||'')))
const rows=ref<Device[]>([]),query=ref(''),page=ref(1),total=ref(0),loading=ref(true),error=ref(''),busy=ref(false),saveError=ref(''),createDialog=ref(false),editDialog=ref(false),credential=ref<Credential|null>(null)
const filterProductId=ref(''),filterProducts=ref<Product[]>([]),loadingFilterProducts=ref(false),filterProductsError=ref('')
const products=ref<Product[]>([]),productQuery=ref(''),versions=ref<ModelVersion[]>([]),loadingProducts=ref(false),loadingModels=ref(false),selectedProduct=ref(''),modelVersion=ref(''),code=ref(''),name=ref(''),editing=ref<Device|null>(null)
const registrationProduct=computed(()=>props.product||products.value.find(p=>p.id===selectedProduct.value))
const codeAlphabet='abcdefghjkmnpqrstuvwxyz'
function generatedCode(){let suffix='';for(let i=0;i<8;i++)suffix+=codeAlphabet[Math.floor(Math.random()*codeAlphabet.length)];return `device-${suffix}`}
const registrationBlocker=computed(()=>registrationProduct.value&&!registrationProduct.value.enabled?'产品已停用，请先启用产品后再登记设备。':selectedProduct.value&&!loadingModels.value&&!saveError.value&&!versions.value.length?'该产品尚无已发布的物模型版本，请先保存物模型草稿并发布版本。':'')
const canRegister=computed(()=>writable.value&&session.can('device:write')&&session.can('device:credential')&&session.can('product:read')&&!!registrationProduct.value&&!registrationBlocker.value&&!loadingProducts.value&&!loadingModels.value&&versions.value.some(v=>v.version===modelVersion.value)&&(!code.value.trim()||/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(code.value))&&!!name.value.trim())
function prepareProduct(){if(!registrationProduct.value)return;createDialog.value=false;void router.push({path:`${scope}/products/${encodeURIComponent(registrationProduct.value.id)}`,query:{tab:'versions'}})}
const states=[{value:'ACTIVE',label:'启用'},{value:'DISABLED',label:'停用'},{value:'RETIRED',label:'退役'}]
const life=new AbortController();let request=new AbortController(),revision=0,modelRequest=new AbortController(),modelRevision=0,productRequest=new AbortController(),productRevision=0,filterProductRequest=new AbortController(),filterProductRevision=0
const presence=ref<Record<string,string>>({})
const firmware=ref<Record<string,FirmwareInventory>>({}),firmwareLoading=ref(false),firmwareError=ref('')
let firmwareRevision=0
async function refreshFirmware(){
 const current=++firmwareRevision,ids=rows.value.map(d=>d.id);firmware.value={};firmwareError.value='';firmwareLoading.value=!!ids.length
 if(!ids.length)return
 try{const result=await deviceFirmwareApi.inventories(scope,ids,life.signal);if(current===firmwareRevision&&!life.signal.aborted)firmware.value=result}
 catch(e){if(current===firmwareRevision&&!isCancelled(e))firmwareError.value=errorMessage(e)}finally{if(current===firmwareRevision)firmwareLoading.value=false}
}
let presenceRevision=0
const presenceRefresh=snapshotRefresh(refreshPresence)
async function refreshPresence(){
 const current=++presenceRevision
 const values=await Promise.all(rows.value.map(async d=>{
  try{return [d.id,(await readPresence({tenantId:d.tenantId,deviceId:d.id},life.signal)).state] as const}
  catch{return [d.id,'UNKNOWN'] as const}
 }))
 if(current===presenceRevision&&!life.signal.aborted)presence.value=Object.fromEntries(values)
}
useDeviceSubscription(()=>rows.value.map(d=>({tenantId:d.tenantId,deviceId:d.id})),notice=>{
 if(notice.type==='disconnected'){presenceRevision++;presenceRefresh.cancel();presence.value={};return}
 if(notice.type!=='resync'&&notice.kind!=='presence')return
 presenceRefresh.schedule()
})
const presenceName=(id:string)=>presence.value[id]==='ONLINE'?'在线':presence.value[id]==='OFFLINE'?'离线':'未知'
const stateName=(value:string)=>states.find(s=>s.value===value)?.label||value
async function load(){request.abort();request=new AbortController();const current=++revision;loading.value=true;error.value='';rows.value=[];firmwareRevision++;firmware.value={};firmwareError.value='';firmwareLoading.value=false;try{const result=await catalogApi.devices(scope,query.value,page.value,request.signal,props.product?.id||filterProductId.value);if(current!==revision)return;rows.value=result.items;total.value=result.total;presenceRefresh.schedule();void refreshFirmware()}catch(e){if(current===revision&&!isCancelled(e))error.value=errorMessage(e)}finally{if(current===revision)loading.value=false}}
function filterDevices(){page.value=1;void load()}
async function findFilterProducts(query=''){
 filterProductRequest.abort();filterProductRequest=new AbortController();const current=++filterProductRevision
 loadingFilterProducts.value=true;filterProductsError.value=''
 try{
  const result=await catalogApi.products(scope,query,1,filterProductRequest.signal)
  if(current!==filterProductRevision)return
  const selected=filterProducts.value.find(p=>p.id===filterProductId.value)
  filterProducts.value=selected&&!result.items.some(p=>p.id===selected.id)?[selected,...result.items]:result.items
 }catch(e){if(current===filterProductRevision&&!isCancelled(e))filterProductsError.value=errorMessage(e)}
 finally{if(current===filterProductRevision)loadingFilterProducts.value=false}
}
async function findProducts(){productRequest.abort();productRequest=new AbortController();const current=++productRevision;loadingProducts.value=true;saveError.value='';selectedProduct.value='';versions.value=[];modelVersion.value='';modelRevision++;modelRequest.abort();loadingModels.value=false;products.value=[];try{const result=await catalogApi.products(scope,productQuery.value,1,productRequest.signal);if(current===productRevision)products.value=result.items.filter(p=>p.enabled&&p.latestVersion)}catch(e){if(current===productRevision&&!isCancelled(e))saveError.value=errorMessage(e)}finally{if(current===productRevision)loadingProducts.value=false}}
async function chooseProduct(){modelRequest.abort();modelRequest=new AbortController();const current=++modelRevision;versions.value=[];modelVersion.value='';loadingModels.value=true;saveError.value='';try{const data=await catalogApi.models(scope,selectedProduct.value,modelRequest.signal);if(current===modelRevision){versions.value=data;modelVersion.value=data.find(v=>v.version===registrationProduct.value?.latestVersion)?.version||data[0]?.version||''}}catch(e){if(current===modelRevision&&!isCancelled(e))saveError.value=errorMessage(e)}finally{if(current===modelRevision)loadingModels.value=false}}
function openRegister(){if(props.product){createDialog.value=true;saveError.value='';selectedProduct.value=props.product.id;products.value=[props.product];code.value=generatedCode();name.value='';void chooseProduct();return}createDialog.value=true;saveError.value='';selectedProduct.value='';modelVersion.value='';versions.value=[];code.value=generatedCode();name.value='';productQuery.value='';void findProducts()}
async function register(){if(busy.value||!canRegister.value||!registrationProduct.value)return;busy.value=true;saveError.value='';try{credential.value=await catalogApi.register(resourceScope(scope,registrationProduct.value),{productId:selectedProduct.value,code:code.value,name:name.value,modelVersion:modelVersion.value},life.signal);createDialog.value=false;ElMessage.success('设备已登记并启用，可使用凭证连接 MQTT');await load()}catch(e){if(!isCancelled(e))saveError.value=errorMessage(e)}finally{busy.value=false}}
async function update(){if(!editing.value||busy.value)return;busy.value=true;saveError.value='';try{await catalogApi.updateDevice(scope,editing.value,life.signal);editDialog.value=false;ElMessage.success('设备已更新');await load()}catch(e){if(!isCancelled(e))saveError.value=errorMessage(e)}finally{busy.value=false}}
async function viewCredential(device:Device){if(busy.value)return;busy.value=true;error.value='';try{credential.value=await catalogApi.credential(scope,device,life.signal)}catch(e){if(!isCancelled(e))error.value=errorMessage(e)}finally{busy.value=false}}
async function rotate(device:Device){if(busy.value)return;busy.value=true;error.value='';try{credential.value=await catalogApi.rotate(scope,device,life.signal);await load()}catch(e){if(!isCancelled(e))error.value=errorMessage(e)}finally{busy.value=false}}
onScopeDispose(()=>{firmwareRevision++;presenceRefresh.dispose();presenceRevision++;revision++;modelRevision++;productRevision++;filterProductRevision++;filterProductRequest.abort();request.abort();modelRequest.abort();productRequest.abort();life.abort();credential.value=null});void load()
</script>
<template>
<CatalogNavigation v-if="!product"/>
<div class="page-heading"><div><p class="eyebrow">DEVICE REGISTRY</p><h1>{{product?'产品设备':'设备管理'}}</h1></div><el-button v-if="writable&&session.can('device:write')&&session.can('device:credential')&&session.can('product:read')" type="primary" :disabled="busy" @click="openRegister">登记设备</el-button></div>
<section class="panel"><form class="tenant-context-toolbar" @submit.prevent="filterDevices"><h2>设备 · {{total}}</h2><div class="tenant-search device-filters"><el-select v-if="!product&&session.can('product:read')" v-model="filterProductId" class="device-product-filter" aria-label="按产品筛选" placeholder="全部产品" clearable filterable remote :remote-method="findFilterProducts" :loading="loadingFilterProducts" @visible-change="(visible:boolean)=>{if(visible)void findFilterProducts()}" @change="filterDevices"><el-option v-for="p in filterProducts" :key="p.id" :label="`${p.name} · ${p.code}`" :value="p.id"/></el-select><el-input v-model="query" maxlength="100" placeholder="搜索设备编码或名称" aria-label="搜索设备" clearable/><el-button native-type="submit">查询</el-button><el-button @click="load">刷新</el-button></div></form><el-alert v-if="filterProductsError" :title="'产品选项加载失败：'+filterProductsError" type="error" :closable="false"/><div v-if="loading" class="tenant-context-loading"><el-skeleton :rows="5" animated/></div><StatePanel v-else-if="error" title="设备加载失败" :description="error"><el-button @click="load">重试</el-button></StatePanel><StatePanel v-else-if="!rows.length" title="暂无设备" description="先发布产品物模型，再为每台设备登记独立身份。"/><div v-else class="tenant-table"><p v-if="firmwareError" role="alert">固件信息读取失败，设备列表仍可操作。<el-button link :disabled="firmwareLoading" @click="refreshFirmware">重试固件信息</el-button></p><el-table :data="rows"><el-table-column prop="name" label="设备名称" min-width="150"/><el-table-column v-if="route.meta.catalogPlatform" label="数据归属" min-width="150"><template #default="{row}">{{row.tenantId||'无租户'}}</template></el-table-column><el-table-column prop="code" label="设备编码" min-width="140"/><el-table-column prop="productName" label="所属产品" min-width="140"/><el-table-column prop="modelVersion" label="模型版本" width="100"/><el-table-column label="实际固件版本" min-width="200"><template #default="{row}"><FirmwareSummary :inventory="firmware[row.id]" :loading="firmwareLoading" :failed="!!firmwareError"/></template></el-table-column><el-table-column label="在线状态" width="100"><template #default="{row}"><el-tag :type="presence[row.id]==='ONLINE'?'success':'info'" effect="plain">{{presenceName(row.id)}}</el-tag></template></el-table-column><el-table-column label="生命周期" width="100"><template #default="{row}"><el-tag :type="row.lifecycle==='ACTIVE'?'success':'info'" effect="plain">{{stateName(row.lifecycle)}}</el-tag></template></el-table-column><el-table-column label="操作" min-width="260"><template #default="{row}"><el-button link type="primary" @click="router.push(`${resourceScope(scope,row)}/devices/${encodeURIComponent(row.id)}`)">{{writable&&session.can('device:write')?'管理设备':'设备详情'}}</el-button><el-button v-if="session.can('device:credential')" link :disabled="busy" @click="viewCredential(row)">查看凭证</el-button><el-button v-if="writable&&session.can('device:credential')&&row.lifecycle!=='RETIRED'" link :disabled="busy" @click="rotate(row)">轮换凭证</el-button></template></el-table-column></el-table></div><div class="tenant-pagination"><el-pagination :current-page="page" :total="total" :page-size="20" layout="prev,pager,next" @current-change="(p:number)=>{page=p;load()}"/></div></section>
<el-dialog v-model="createDialog" title="登记设备" width="min(570px,94vw)" :show-close="!busy" :close-on-click-modal="!busy" :close-on-press-escape="!busy"><form class="tenant-form" @submit.prevent="register"><label for="device-name">设备名称</label><el-input id="device-name" v-model="name" maxlength="100" placeholder="请输入设备名称" :disabled="busy"/><template v-if="!product"><label>查找已发布产品</label><div class="tenant-search"><el-input v-model="productQuery" maxlength="100" placeholder="输入产品名称或编码" aria-label="查找登记产品"/><el-button :loading="loadingProducts" @click="findProducts">查询</el-button></div><el-select v-model="selectedProduct" placeholder="选择启用且已发布的产品" aria-label="所属产品" :disabled="busy" @change="chooseProduct"><el-option v-for="p in products" :key="p.id" :label="`${p.name} · ${p.code}`" :value="p.id"/></el-select></template><p v-else>所属产品：{{product.name}} · {{product.code}}</p><el-alert v-if="registrationBlocker" :title="registrationBlocker" type="warning" :closable="false"/><el-button v-if="registrationBlocker&&session.can('page:products')" type="primary" plain @click="prepareProduct">前往版本管理</el-button><label>物模型版本</label><el-select v-model="modelVersion" :loading="loadingModels" :disabled="busy||!selectedProduct||loadingModels" aria-label="登记模型版本" placeholder="选择固定版本"><el-option v-for="v in versions" :key="v.version" :label="v.version" :value="v.version"/></el-select><label for="device-code">设备编码（可选）</label><div class="tenant-search"><el-input id="device-code" v-model="code" maxlength="64" placeholder="留空则自动生成" :disabled="busy"/><el-button :disabled="busy" @click="code=generatedCode()">自动生成</el-button></div><el-alert v-if="saveError" :title="saveError" type="error" :closable="false"/><div class="tenant-form-actions"><el-button :disabled="busy" @click="createDialog=false">取消</el-button><el-button type="primary" native-type="submit" :loading="busy" :disabled="!canRegister">登记并生成凭证</el-button></div></form></el-dialog>
<el-dialog v-model="editDialog" title="设备详情与生命周期" width="min(570px,94vw)" :show-close="!busy" :close-on-click-modal="!busy" :close-on-press-escape="!busy"><form v-if="editing" class="tenant-form" @submit.prevent="update"><dl class="detail-grid"><div><dt>设备 ID</dt><dd class="mono">{{editing.id}}</dd></div><div><dt>产品／模型版本</dt><dd>{{editing.productName}} / {{editing.modelVersion}}</dd></div><div><dt>设备编码</dt><dd>{{editing.code}}</dd></div><div><dt>凭证版本</dt><dd>{{editing.credentialVersion}}</dd></div></dl><label for="edit-device-name">设备名称</label><el-input id="edit-device-name" v-model="editing.name" maxlength="100" :disabled="busy||!writable||!session.can('device:write')"/><label>生命周期</label><el-select v-model="editing.lifecycle" aria-label="设备生命周期" :disabled="busy||!writable||!session.can('device:write')"><el-option v-for="s in states" :key="s.value" :label="s.label" :value="s.value"/></el-select><p class="muted small">退役后不可重新启用或轮换凭证；设备产品、归属和模型版本保持不变。</p><el-alert v-if="saveError" :title="saveError" type="error" :closable="false"/><div class="tenant-form-actions"><el-button :disabled="busy" @click="editDialog=false">关闭</el-button><el-button v-if="writable&&session.can('device:write')" type="primary" native-type="submit" :loading="busy" :disabled="!editing.name.trim()">保存设备</el-button></div></form></el-dialog>
<el-dialog :model-value="!!credential" title="设备凭证" width="min(600px,94vw)" :close-on-click-modal="false" @update:model-value="(open:boolean)=>{if(!open)credential=null}"><div v-if="credential" class="tenant-form"><el-alert title="轮换后旧凭证立即失效。" type="info" :closable="false"/><label>设备 ID / ClientId</label><el-input :model-value="credential.clientId" readonly aria-label="设备ClientId"/><label>设备用户名</label><el-input :model-value="credential.username" readonly aria-label="设备用户名"/><label>设备密钥</label><el-input :model-value="credential.secret" readonly aria-label="设备密钥"/><div class="tenant-form-actions"><el-button type="primary" @click="credential=null">关闭</el-button></div></div></el-dialog>
</template>
<style scoped>
.device-filters { max-width: 780px; flex-wrap: wrap; }
.device-product-filter { width: 240px; }
</style>
