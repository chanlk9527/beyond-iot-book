<script setup lang="ts">
const schema=defineModel<any>({required:true})
defineProps<{disabled?:boolean}>()
const types={BOOLEAN:'布尔值',INTEGER:'整数',DECIMAL:'小数',TEXT:'文本'}
function reset(){schema.value.minimum=null;schema.value.maximum=null;schema.value.maxLength=null}
</script>
<template>
<div class="schema-fields">
<label>数据类型<el-select v-model="schema.type" :disabled="disabled" @change="reset"><el-option v-for="(name,key) in types" :key="key" :label="name" :value="key"/></el-select></label>
<template v-if="schema.type==='INTEGER'||schema.type==='DECIMAL'">
<label>最小值<el-input-number v-model="schema.minimum" :disabled="disabled" :precision="schema.type==='INTEGER'?0:undefined" placeholder="不限制" :controls="false"/></label>
<label>最大值<el-input-number v-model="schema.maximum" :disabled="disabled" :precision="schema.type==='INTEGER'?0:undefined" placeholder="不限制" :controls="false"/></label>
</template>
<label v-if="schema.type==='TEXT'">最大长度<el-input-number v-model="schema.maxLength" :disabled="disabled" :min="0" :precision="0" placeholder="不限制" :controls="false"/></label>
</div>
</template>
<style scoped>.schema-fields{display:flex;gap:16px;flex-wrap:wrap}.schema-fields label{display:grid;gap:8px;flex:1;min-width:140px}.el-select,.el-input-number{width:100%}</style>
