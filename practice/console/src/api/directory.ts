import {ref,onMounted} from 'vue'
import {request,managementMutation,errorMessage} from './management'
export interface CategoryNode {id:string;label:string;children:CategoryNode[]}
export interface Directory {revision:number;items:CategoryNode[]}
export const directoryApi={get:(_scope:string,signal:AbortSignal)=>request<Directory>('/catalog/shared/categories',{signal}),save:(_scope:string,value:Directory,signal:AbortSignal)=>managementMutation<Directory>('/catalog/shared/categories','PUT',value,signal)}
export function categoryOptions(items:CategoryNode[]){return items.map(p=>({label:p.label,value:p.id,children:p.children.map(c=>({label:c.label,value:c.id}))}))}
export function categoryLabel(items:CategoryNode[],id:string){
 for(const p of items){if(p.id===id)return p.label;const child=p.children.find(c=>c.id===id);if(child)return `${p.label} / ${child.label}`}
 return id||'未分类'
}
export function useProductDirectory(scope:string,signal:AbortSignal){
 const items=ref<CategoryNode[]>([]),options=ref<ReturnType<typeof categoryOptions>>([]),error=ref(''),loading=ref(true)
 async function load(){loading.value=true;error.value='';try{items.value=(await directoryApi.get(scope,signal)).items;options.value=categoryOptions(items.value)}catch(e){if(!signal.aborted)error.value=errorMessage(e)}finally{loading.value=false}}
 onMounted(load)
 return {options,items,error,loading,load}
}
