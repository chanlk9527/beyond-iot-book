import type {Product} from '../api/catalog'
import {stableModel} from './modelVersions'
export type ProductEditTab='basic'|'access'|'model'
export const basicKeys=['category','manufacturer','modelNumber','firmwareVersion','supportUrl','installationNotes'] as const
export const accessKeys=['deviceType','networkWay','transportProtocol','messageProtocol','dataFormat'] as const
export function productChanges(saved:Product,editing:Product,modelText:string){
 let model=true
 try{model=stableModel(JSON.parse(modelText))!==stableModel(saved.draft)}catch{/* Invalid JSON is unsaved. */}
 return {basic:saved.name!==editing.name||saved.description!==editing.description||basicKeys.some(k=>saved.configuration[k]!==editing.configuration[k]),access:accessKeys.some(k=>saved.configuration[k]!==editing.configuration[k]),model}
}
/** Use the last server snapshot for every other tab so a save cannot publish another tab's edits. */
export function productTabPayload(saved:Product,editing:Product,modelText:string,tab:ProductEditTab):Product{
 const value:Product=JSON.parse(JSON.stringify(saved))
 if(tab==='basic'){
  value.name=editing.name;value.description=editing.description
  for(const k of basicKeys)value.configuration[k]=editing.configuration[k]
 }else if(tab==='access'){
  value.configuration={...value.configuration,...Object.fromEntries(accessKeys.map(k=>[k,editing.configuration[k]]))}
 }else value.draft=JSON.parse(modelText)
 return value
}
