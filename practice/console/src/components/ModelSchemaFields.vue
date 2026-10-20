<script setup lang="ts">
import {computed,ref,watch} from 'vue'
import {modelTypes,validateSchema} from './modelSchema'
const schema=defineModel<any>({required:true})
const props=defineProps<{disabled?:boolean;scalarOnly?:boolean}>()
const types=computed(()=>Object.fromEntries(Object.entries(modelTypes).filter(([key])=>!props.scalarOnly||key!=='ARRAY')))
const options=ref<{key:number;code:string;label:string}[]>([]),child=ref<{validate:()=>void}>()
let nextKey=0
watch(()=>schema.value,()=>{options.value=Object.entries(schema.value.enumValues||{}).map(([code,label])=>({key:nextKey++,code,label:String(label)}))},{immediate:true})
const enumError=computed(()=>{
 if(new Set(options.value.map(o=>o.code)).size!==options.value.length)return '枚举值不能重复'
 if(options.value.some(o=>!o.code.trim()||!o.label.trim()))return '请填写每个选项的枚举值和显示名称'
 return ''
})
function sync(){schema.value.enumValues=Object.fromEntries(options.value.map(o=>[o.code,o.label]))}
function add(){options.value.push({key:nextKey++,code:'',label:''});sync()}
function remove(index:number){options.value.splice(index,1);sync()}
function reset(){
 Object.assign(schema.value,{minimum:null,maximum:null,maxLength:null,enumValues:{},unit:'',items:null,minItems:null,maxItems:null})
 options.value=[]
 if(schema.value.type==='ARRAY')schema.value.items={type:'TEXT'}
}
defineExpose({validate(){
 if(schema.value.type==='ENUM'&&enumError.value)throw Error(enumError.value)
 if(schema.value.type==='ARRAY')child.value?.validate()
 validateSchema(schema.value)
}})
</script>
<template>
<div class="schema-fields">
<label>数据类型<el-select v-model="schema.type" :disabled="disabled" @change="reset"><el-option v-for="(name,key) in types" :key="key" :label="name" :value="key"/></el-select></label>
<template v-if="schema.type==='INTEGER'||schema.type==='DECIMAL'">
<label>最小值<el-input-number v-model="schema.minimum" :disabled="disabled" :precision="schema.type==='INTEGER'?0:undefined" placeholder="不限制" :controls="false"/></label>
<label>最大值<el-input-number v-model="schema.maximum" :disabled="disabled" :precision="schema.type==='INTEGER'?0:undefined" placeholder="不限制" :controls="false"/></label>
<label>单位<el-input v-model="schema.unit" :disabled="disabled" maxlength="32" placeholder="例如 ℃、%"/></label>
</template>
<label v-if="schema.type==='TEXT'">最大长度<el-input-number v-model="schema.maxLength" :disabled="disabled" :min="0" :precision="0" placeholder="不限制" :controls="false"/></label>
<div v-if="schema.type==='ENUM'" class="enum-options">
<div class="option-heading"><strong>枚举选项 <small>{{options.length}} / 100</small></strong><el-button v-if="!disabled" :disabled="options.length>=100" @click="add">添加选项</el-button></div>
<p class="muted">分别填写设备传输的值与界面显示名称。例如：heat / 加热。枚举值按字符串传输。</p>
<div v-for="(option,index) in options" :key="option.key" class="option-row">
<label>枚举值<el-input v-model="option.code" :aria-label="`枚举值 ${index+1}`" :disabled="disabled" maxlength="64" placeholder="例如 heat" @update:model-value="sync"/></label>
<label>显示名称<el-input v-model="option.label" :aria-label="`枚举名称 ${index+1}`" :disabled="disabled" maxlength="100" placeholder="例如 加热" @update:model-value="sync"/></label>
<el-button v-if="!disabled" type="danger" link :aria-label="`删除枚举选项 ${index+1}`" @click="remove(index)">删除</el-button>
</div>
<p v-if="!options.length" class="muted">尚无选项，点击“添加选项”开始配置。</p>
<el-alert v-if="enumError" :title="enumError" type="error" :closable="false"/>
</div>
<template v-if="schema.type==='ARRAY'">
<label>最少项数<el-input-number v-model="schema.minItems" :disabled="disabled" :min="0" :max="1000" :precision="0" placeholder="默认 0" :controls="false"/></label>
<label>最多项数<el-input-number v-model="schema.maxItems" :disabled="disabled" :min="0" :max="1000" :precision="0" placeholder="默认 1000" :controls="false"/></label>
<div class="array-items"><strong>数组元素定义</strong><p class="muted">数组内所有元素使用同一种类型，逐项校验以下约束。</p><ModelSchemaFields v-if="schema.items" ref="child" v-model="schema.items" :disabled="disabled" scalar-only/><el-alert v-else title="请在 JSON 中补充 items 元素定义" type="error" :closable="false"/></div>
</template>
</div>
</template>
<style scoped>
.schema-fields{display:flex;gap:16px;flex-wrap:wrap}.schema-fields label{display:grid;gap:8px;flex:1;min-width:120px}.enum-options,.array-items{flex-basis:100%;padding:16px;border:1px solid var(--el-border-color-light);border-radius:8px}.option-heading,.option-row{display:flex;align-items:center;gap:12px;justify-content:space-between}.option-row{margin:12px 0;align-items:end}.option-row .el-button{margin-bottom:8px}.el-select,.el-input-number{width:100%}.muted,small{font-size:12px;color:var(--el-text-color-secondary)}@media(max-width:520px){.option-row{flex-wrap:wrap}}
</style>