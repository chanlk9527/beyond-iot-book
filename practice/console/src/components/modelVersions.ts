import type {ModelSpec} from '../api/catalog'

export function stableModel(value:unknown):string {
 if(Array.isArray(value))return '['+value.map(stableModel).join(',')+']'
 if(value&&typeof value==='object')return '{'+Object.entries(value).sort(([a],[b])=>a.localeCompare(b)).map(([k,v])=>JSON.stringify(k)+':'+stableModel(v)).join(',')+'}'
 return JSON.stringify(value)
}
export function modelCounts(model:ModelSpec){
 return {properties:Object.keys(model.properties).length,operations:Object.keys(model.operations).length,events:Object.keys(model.events).length}
}
export function emptyModel(model:ModelSpec){return Object.values(modelCounts(model)).every(count=>count===0)}
export function modelDiff(previous:ModelSpec|undefined,next:ModelSpec){
 return (['properties','operations','events'] as const).map(group=>{
  const before=previous?.[group]||{},after=next[group]
  const owns=(value:Record<string,unknown>,id:string)=>Object.prototype.hasOwnProperty.call(value,id)
  return {group,added:Object.keys(after).filter(id=>!owns(before,id)),removed:Object.keys(before).filter(id=>!owns(after,id)),changed:Object.keys(after).filter(id=>owns(before,id)&&stableModel(before[id])!==stableModel(after[id]))}
 })
}
