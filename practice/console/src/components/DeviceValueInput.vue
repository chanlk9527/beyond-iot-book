<script setup lang="ts">
import type { ShadowScalar } from '../api/catalog'
defineProps<{modelValue:ShadowScalar|undefined;schema:{type:string;minimum?:number|null;maximum?:number|null;maxLength?:number|null};disabled:boolean;label:string}>()
const emit=defineEmits<{'update:modelValue':[value:ShadowScalar]}>()
</script>
<template>
 <el-select v-if="schema.type==='BOOLEAN'" :model-value="modelValue" @update:model-value="(v:boolean)=>emit('update:modelValue',v)" :disabled="disabled" :aria-label="label"><el-option label="true" :value="true"/><el-option label="false" :value="false"/></el-select>
 <el-input v-else-if="schema.type==='TEXT'" :model-value="modelValue" @update:model-value="(v:string)=>emit('update:modelValue',v)" :maxlength="schema.maxLength??4096" :disabled="disabled" :aria-label="label"/>
 <el-input-number v-else :model-value="typeof modelValue==='number'?modelValue:undefined" @update:model-value="(v:number|undefined|null)=>{if(v!=null)emit('update:modelValue',v)}" :min="schema.minimum??undefined" :max="schema.maximum??undefined" :precision="schema.type==='INTEGER'?0:undefined" :disabled="disabled" :aria-label="label"/>
</template>
