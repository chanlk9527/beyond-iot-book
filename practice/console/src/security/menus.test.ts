import { describe, expect, it } from 'vitest'
import { firstCatalogSection, menuPath, visibleMenus } from './menus'

describe('configured menu navigation',()=>{
  const menus=[
    {code:'page:devices',name:'设备台账',group:'运营',path:'/catalog/:scope/devices',sortOrder:1},
    {code:'page:products',name:'产品库',group:'业务',path:'/catalog/:scope/products',sortOrder:20},
  ]
  it('uses persisted names and order and filters by page grants',()=>{
    const account={id:'u',username:'u',tenantId:'t',permissions:['page:products','page:devices'],menus:[...menus].reverse()}
    expect(visibleMenus(account)).toEqual(menus)
    expect(firstCatalogSection(account)).toBe('devices')
    expect(visibleMenus({...account,permissions:['page:products']})).toEqual([menus[1]])
    expect(visibleMenus({...account,menus:[]})).toEqual([])
  })
  it('resolves registered scope placeholders while preserving the tenant context',()=>{
    const account={id:'u',username:'u',tenantId:'tenant a'}
    expect(menuPath(menus[0]!,account)).toBe('/catalog/tenants/tenant%20a/devices')
    expect(menuPath(menus[0]!,{...account,platformAdmin:true})).toBe('/catalog/platform/devices')
    expect(menuPath(menus[0]!,{...account,platformAdmin:true},'other')).toBe('/catalog/tenants/other/devices')
  })
})
