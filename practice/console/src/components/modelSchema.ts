export const modelTypes:Record<string,string>={BOOLEAN:'布尔值',INTEGER:'整数',DECIMAL:'小数',TEXT:'文本',ENUM:'枚举',ARRAY:'数组'}
export interface ValueSchema {type:string;minimum?:number|null;maximum?:number|null;maxLength?:number|null;enumValues?:Record<string,string>;unit?:string;items?:ValueSchema|null;minItems?:number|null;maxItems?:number|null}
export function initialValue(s:ValueSchema):import('../api/catalog').ShadowScalar {
 return s.type==='BOOLEAN'?false:s.type==='ENUM'?Object.keys(s.enumValues||{})[0]||'':s.type==='ARRAY'?[]:s.type==='TEXT'?'':s.minimum??0
}
export function validateSchema(s:ValueSchema){
 if(!s||!modelTypes[s.type])throw Error('请选择支持的数据类型')
 if(s.type==='ARRAY'){
  if(!s.items||s.items.type==='ARRAY')throw Error('请选择数组元素类型（不支持嵌套数组）')
  validateSchema(s.items)
  if([s.minItems,s.maxItems].some(v=>v!=null&&(!Number.isInteger(v)||v<0||v>1000)))throw Error('数组长度必须为 0～1000 的整数')
  if(s.minItems!=null&&s.maxItems!=null&&s.minItems>s.maxItems)throw Error('最少项数不能大于最多项数')
 }else if(s.items!=null||s.minItems!=null||s.maxItems!=null)throw Error('仅数组类型支持元素定义和项数约束')
 const numeric=['INTEGER','DECIMAL'].includes(s.type)
 if(!numeric&&(s.minimum!=null||s.maximum!=null||s.unit))throw Error('仅数值类型支持范围和单位')
 if([s.minimum,s.maximum].some(v=>v!=null&&!Number.isFinite(v)))throw Error('范围必须为有限数值')
 if(s.minimum!=null&&s.maximum!=null&&s.minimum>s.maximum)throw Error('最小值不能大于最大值')
 if(s.type==='INTEGER'&&[s.minimum,s.maximum].some(v=>v!=null&&!Number.isInteger(v)))throw Error('整数类型的范围必须为整数')
 if(s.maxLength!=null&&(s.type!=='TEXT'||!Number.isInteger(s.maxLength)||s.maxLength<0))throw Error('仅文本类型支持非负整数长度')
 const options=Object.entries(s.enumValues||{})
 if(s.type==='ENUM'){
  if(!options.length||options.length>100)throw Error('枚举需要 1～100 个选项')
  if(options.some(([code,label])=>!code.trim()||[...code].length>64||typeof label!=='string'||!label.trim()||[...label].length>100))throw Error('枚举值和名称不能为空，最长分别为 64 和 100 字符')
 }else if(options.length)throw Error('仅枚举类型支持枚举选项')
 if(s.unit&&[...s.unit].length>32)throw Error('单位最长 32 字符')
}
export function schemaSummary(s:ValueSchema):string {
 if(s.type==='ARRAY')return '数组 · '+(s.items?schemaSummary(s.items):'未定义元素')+' · '+(s.minItems??0)+'～'+(s.maxItems??1000)+' 项'
 return (modelTypes[s.type]||s.type)+(s.unit?' · '+s.unit:'')+(s.minimum!=null||s.maximum!=null?' · '+(s.minimum??'不限')+' ～ '+(s.maximum??'不限'):'')+(s.maxLength!=null?' · 最长 '+s.maxLength+' 字符':'')+(s.type==='ENUM'?' · '+Object.entries(s.enumValues||{}).map(([code,label])=>code+' = '+label).join('；'):'')
}