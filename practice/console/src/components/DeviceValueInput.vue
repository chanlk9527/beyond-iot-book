<script setup lang="ts">
import {computed} from 'vue'
import {initialValue,type ValueSchema} from './modelSchema'
import type {ShadowScalar,ScalarValue} from '../api/catalog'
const props=defineProps<{modelValue:ShadowScalar|undefined;schema:ValueSchema;disabled:boolean;label:string}>()
const emit=defineEmits<{'update:modelValue':[value:ShadowScalar]}>()
const scalar=computed(()=>Array.isArray(props.modelValue)?undefined:props.modelValue)
const elements=computed(()=>Array.isArray(props.modelValue)?props.modelValue:[])
function update(index:number,value:ShadowScalar){if(Array.isArray(value))return;const next=[...elements.value];next[index]=value;emit('update:modelValue',next)}
function add(){if(!props.schema.items)return;emit('update:modelValue',[...elements.value,initialValue(props.schema.items) as ScalarValue])}
function remove(index:number){emit('update:modelValue',elements.value.filter((_,i)=>i!==index))}
</script>
<template>
 <div v-if="schema.type==='ARRAY'" class="array-value">
  <div v-for="(element,index) in elements" :key="index" class="array-row"><span>{{index+1}}</span><DeviceValueInput v-if="schema.items" :model-value="element" :schema="schema.items" :disabled="disabled" :label="label+' 第 '+(index+1)+' 项'" @update:model-value="v=>update(index,v)"/><el-button :disabled="disabled" :aria-label="label+' 删除第 '+(index+1)+' 项'" @click="remove(index)">删除</el-button></div>
  <el-button :disabled="disabled||elements.length>=(schema.maxItems??1000)" @click="add">添加元素</el-button><span> {{elements.length}} 项（{{schema.minItems??0}}～{{schema.maxItems??1000}}）</span>
 </div>
 <el-select v-else-if="schema.type==='BOOLEAN'" :model-value="scalar" @update:model-value="(v:boolean)=>emit('update:modelValue',v)" :disabled="disabled" :aria-label="label"><el-option label="true" :value="true"/><el-option label="false" :value="false"/></el-select>
 <el-select v-else-if="schema.type==='ENUM'" :model-value="scalar" @update:model-value="(v:string)=>emit('update:modelValue',v)" :disabled="disabled" :aria-label="label"><el-option v-for="(name,code) in schema.enumValues" :key="code" :label="name+' ('+code+')'" :value="code"/></el-select>
 <el-input v-else-if="schema.type==='TEXT'" :model-value="scalar" @update:model-value="(v:string)=>emit('update:modelValue',v)" :maxlength="schema.maxLength??4096" :disabled="disabled" :aria-label="label"/>
 <el-input-number v-else :model-value="typeof scalar==='number'?scalar:undefined" @update:model-value="(v:number|undefined|null)=>{if(v!=null)emit('update:modelValue',v)}" :min="schema.minimum??undefined" :max="schema.maximum??undefined" :precision="schema.type==='INTEGER'?0:undefined" :disabled="disabled" :aria-label="label"/>
</template>
<style scoped>.array-row{display:flex;gap:8px;align-items:center;margin-bottom:8px}.array-value>span{font-size:12px;color:var(--el-text-color-secondary)}</style>