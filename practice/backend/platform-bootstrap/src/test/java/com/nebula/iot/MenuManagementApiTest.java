package com.nebula.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nebula.iot.identity.api.Menus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:menu-management;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","DATABASE_PASSWORD=test"})
@ActiveProfiles("management") @AutoConfigureMockMvc @Transactional
class MenuManagementApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @BeforeEach void seed() {
        jdbc.update("INSERT INTO tenant(id,name) VALUES ('mt','Menu tenant')");
        jdbc.update("INSERT INTO identity_account(id,username,password_hash,platform_admin,tenant_id) VALUES ('mr','menu-root','unused',TRUE,NULL),('mu','menu-user','unused',FALSE,'mt')");
    }
    MockHttpServletRequestBuilder root(MockHttpServletRequestBuilder request){return request.with(user("menu-root")).with(csrf());}
    MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request,Object value)throws Exception{return request.contentType("application/json").content(json.writeValueAsString(value));}
    Menus.Definition menu(String code)throws Exception {
        var response=mvc.perform(root(get("/api/management/iam/menus"))).andExpect(status().isOk()).andReturn().getResponse();
        for(var value:json.readValue(response.getContentAsString(),Menus.Definition[].class))if(value.code().equals(code))return value;
        throw new AssertionError("Menu missing: "+code);
    }
    void role(String id,List<String> permissions) {
        jdbc.update("INSERT INTO identity_role(id,code,name) VALUES (?,?,?)",id,id,id);
        for(String permission:permissions)jdbc.update("INSERT INTO identity_role_permission(role_id,permission) VALUES (?,?)",id,permission);
        jdbc.update("INSERT INTO identity_account_role(account_id,role_id) VALUES ('mu',?)",id);
    }
    @Test void metadataChangesAppearInNavigationWithoutExpandingLegacyRole()throws Exception {
        role("legacy",List.of("page:devices","device:read"));
        var old=menu("page:devices");
        var changed=new Menus.Definition(old.code(),"设备台账","运营管理",old.path(),old.permissions(),"设备台账功能",1,old.revision());
        mvc.perform(body(root(put("/api/management/iam/menus/page:devices")),changed)).andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(1));
        mvc.perform(get("/api/management/me").with(user("menu-user"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.menus[0].name").value("设备台账")).andExpect(jsonPath("$.menus[0].group").value("运营管理"))
            .andExpect(jsonPath("$.menus[0].sortOrder").value(1)).andExpect(jsonPath("$.permissions.length()").value(2));
        mvc.perform(body(root(put("/api/management/iam/menus/page:devices")),changed)).andExpect(status().isConflict());
        assertEquals(0L,jdbc.queryForObject("SELECT revision FROM identity_role WHERE id='legacy'",Long.class));
    }
    @Test void changedMappingSynchronizesRolesAndPreservesSharedCapabilities()throws Exception {
        role("shared",List.of("page:devices","page:products","device:read","device:write","device:credential","product:read","product:write","product:publish"));
        var old=menu("page:devices");
        var changed=new Menus.Definition(old.code(),old.name(),old.group(),old.path(),List.of("device:read","tenant:read"),old.description(),old.sortOrder(),old.revision());
        mvc.perform(body(root(put("/api/management/iam/menus/page:devices")),changed)).andExpect(status().isOk());
        var permissions=jdbc.queryForList("SELECT permission FROM identity_role_permission WHERE role_id='shared'",String.class);
        assertTrue(permissions.containsAll(List.of("device:read","product:read","tenant:read","page:devices","page:products")));
        assertFalse(permissions.contains("device:write"));assertFalse(permissions.contains("device:credential"));
        assertEquals(1L,jdbc.queryForObject("SELECT revision FROM identity_role WHERE id='shared'",Long.class));
        var created=mvc.perform(body(root(post("/api/management/iam/roles")),Map.of("code","configured","name","Configured","menus",List.of("page:devices"))))
            .andExpect(status().isCreated()).andReturn().getResponse();
        var node=json.readTree(created.getContentAsString());
        assertEquals(3,node.get("permissions").size());
        assertTrue(node.get("permissions").toString().contains("tenant:read"));
    }
    @Test void unusedMenuCanBeDeletedAndRecreatedOnlyForRegisteredRoutes()throws Exception {
        var old=menu("page:categories");
        mvc.perform(body(root(delete("/api/management/iam/menus/page:categories")),Map.of("revision",99))).andExpect(status().isConflict());
        assertFalse(menu("page:categories").permissions().isEmpty());
        mvc.perform(body(root(delete("/api/management/iam/menus/page:categories")),Map.of("revision",old.revision()))).andExpect(status().isNoContent());
        mvc.perform(body(root(post("/api/management/iam/roles")),Map.of("code","removed","name","Removed","menus",List.of("page:categories")))).andExpect(status().isBadRequest());
        mvc.perform(body(root(post("/api/management/iam/menus")),old)).andExpect(status().isCreated());
        mvc.perform(body(root(post("/api/management/iam/menus")),old)).andExpect(status().isConflict());
        var invalid=new Menus.Definition(old.code(),old.name(),old.group(),"https://example.com",old.permissions(),old.description(),0,0);
        mvc.perform(body(root(put("/api/management/iam/menus/page:categories")),invalid)).andExpect(status().isBadRequest());
        var unknown=new Menus.Definition("page:unknown","Unknown","Group","/unknown",List.of("product:read"),"",0,0);
        mvc.perform(body(root(post("/api/management/iam/menus")),unknown)).andExpect(status().isBadRequest());
    }
    @Test void assignedMenusAndMaintenanceEntryAreProtectedAndOnlySuperAdminCanMaintain()throws Exception {
        role("administrator",List.of("page:menus","page:products","iam:role:read","iam:role:write"));
        var products=menu("page:products");
        mvc.perform(body(root(delete("/api/management/iam/menus/page:products")),Map.of("revision",0))).andExpect(status().isConflict());
        mvc.perform(body(root(delete("/api/management/iam/menus/page:menus")),Map.of("revision",0))).andExpect(status().isConflict());
        mvc.perform(body(put("/api/management/iam/menus/page:products").with(user("menu-user")).with(csrf()),products)).andExpect(status().isForbidden());
        mvc.perform(body(post("/api/management/iam/menus").with(user("menu-user")).with(csrf()),products)).andExpect(status().isForbidden());
        mvc.perform(body(delete("/api/management/iam/menus/page:products").with(user("menu-user")).with(csrf()),Map.of("revision",0))).andExpect(status().isForbidden());
        mvc.perform(get("/api/management/iam/menu-pages").with(user("menu-user"))).andExpect(status().isForbidden());
        mvc.perform(body(put("/api/management/iam/menus/page:products").with(user("menu-root")),products)).andExpect(status().isForbidden());
        var badPermission=new Menus.Definition(products.code(),products.name(),products.group(),products.path(),List.of("page:users"),products.description(),0,0);
        mvc.perform(body(root(put("/api/management/iam/menus/page:products")),badPermission)).andExpect(status().isBadRequest());
    }
}
