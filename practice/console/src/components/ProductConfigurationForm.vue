<script setup lang="ts">
import type {ProductConfiguration} from '../api/catalog'
import {computed,onScopeDispose} from 'vue'
import {useRoute} from 'vue-router'
import {catalogScope} from '../api/catalog'
import {useProductDirectory} from '../api/directory'
const route=useRoute(),life=new AbortController()
const {options:productCategories,error:directoryError,loading:directoryLoading}=useProductDirectory(catalogScope(route.meta.catalogPlatform===true,route.params.tenantId),life.signal)
const validCategory=computed(()=>productCategories.value.some(p=>p.children.some(c=>c.value===config.value.category)))
onScopeDispose(()=>life.abort())
const config=defineModel<ProductConfiguration>({required:true})
withDefaults(defineProps<{disabled?:boolean;section?:'all'|'basic'|'access'}>(),{section:'all'})
const deviceTypes={DIRECT:'直连设备',GATEWAY:'网关设备',CHILD:'网关子设备'}
const networks={ETHERNET:'以太网',WIFI:'Wi-Fi',CELLULAR:'蜂窝网络（4G / 5G）',NB_IOT:'NB-IoT',LORA:'LoRa',OTHER:'其他 / 未指定'}
</script>
<template>
<div class="product-configuration">
<template v-if="section!=='access'"><h3>产品档案</h3><p class="muted">统一记录产品品类、厂商与硬件信息，供设备选型和运维查询。以下档案信息均为选填。</p>
<div class="configuration-grid">
<el-alert v-if="directoryError" :title="directoryError" type="error" :closable="false"/><label><span>产品品类（选填）</span><el-cascader v-model="config.category" :options="productCategories" :props="{emitPath:false}" clearable filterable :placeholder="directoryLoading ? '正在加载产品品类…' : '请选择产品品类'" aria-label="产品品类（选填）" :disabled="disabled||directoryLoading||!!directoryError" @change="config.category ||= ''"/><span v-if="!directoryLoading&&!directoryError&&!productCategories.length" class="muted small">暂无可选品类，请先在产品目录中维护二级品类。</span><span v-if="config.category&&!validCategory" class="muted small">原品类：{{config.category}}，请选择新的母婴电器目录。</span></label>
<label>生产厂商（选填）<el-input v-model="config.manufacturer" maxlength="100" placeholder="输入厂商名称" :disabled="disabled"/></label>
<label>产品型号（选填）<el-input v-model="config.modelNumber" maxlength="100" placeholder="输入硬件型号" :disabled="disabled"/></label>
<label>出厂固件版本（选填）<el-input v-model="config.firmwareVersion" maxlength="100" placeholder="例如：1.0.0" :disabled="disabled"/><span class="muted small">产品档案信息，设备实际固件版本由设备上报。</span></label>
</div>
<el-divider/>
</template><template v-if="section!=='basic'"><h3>设备接入</h3><p class="muted">描述产品的连接方案。当前平台实现 MQTT 与官方 JSON 消息格式。</p>
<div class="configuration-grid">
<label><span>设备类型（必选，已默认）</span><el-select v-model="config.deviceType" :disabled="disabled"><el-option v-for="(name,key) in deviceTypes" :key="key" :label="name" :value="key"/></el-select></label>
<label><span>入网方式（必选，已默认）</span><el-select v-model="config.networkWay" :disabled="disabled"><el-option v-for="(name,key) in networks" :key="key" :label="name" :value="key"/></el-select></label>
<label><span>传输协议（必选，已默认）</span><el-select v-model="config.transportProtocol" :disabled="disabled"><el-option label="MQTT" value="MQTT"/></el-select></label>
<label><span>消息协议（必选，已默认）</span><el-select v-model="config.messageProtocol" :disabled="disabled"><el-option label="Nebula 官方 MQTT v1" value="nebula-mqtt-v1"/></el-select></label>
<label><span>数据格式（必选，已默认）</span><el-select v-model="config.dataFormat" :disabled="disabled"><el-option label="JSON" value="JSON"/></el-select></label>
<label>认证方式（系统生成）<el-input model-value="设备独立凭证" disabled/><span class="muted small">登记设备时生成，凭证在设备管理中轮换。</span></label>
</div>
<el-alert v-if="config.deviceType!=='DIRECT'" title="网关和子设备类型目前仅记录接入规划；拓扑绑定、代理认证和消息转发尚未接入。" type="warning" :closable="false" show-icon/>
<p class="muted small">入网方式为产品档案信息。监听地址、端口和 TLS 由平台部署配置管理；此处保存不会启动或变更网络服务。</p>
<el-divider/>
</template><template v-if="section!=='access'"><h3>运维资料</h3><div class="configuration-grid"><label class="full">技术文档地址（选填）<el-input v-model="config.supportUrl" maxlength="1000" placeholder="https://example.com/device-guide" :disabled="disabled"/></label><label class="full">安装与维护说明（选填）<el-input v-model="config.installationNotes" type="textarea" :rows="3" maxlength="1000" show-word-limit placeholder="安装环境、供电方式、维护注意事项等" :disabled="disabled"/></label></div>
</template>
</div>
</template>
<style scoped>.configuration-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:20px;margin:20px 0}.configuration-grid label{display:flex;flex-direction:column;gap:8px}.configuration-grid .full{grid-column:1/-1}.configuration-grid .el-select,.configuration-grid .el-cascader{width:100%}.configuration-grid>.el-alert{grid-column:1/-1}.product-configuration h3{margin-bottom:8px}@media(max-width:680px){.configuration-grid{grid-template-columns:1fr}}</style>
