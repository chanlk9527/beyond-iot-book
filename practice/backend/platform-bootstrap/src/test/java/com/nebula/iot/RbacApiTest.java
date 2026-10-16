package com.nebula.iot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:rbac;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","DATABASE_PASSWORD=test"})
@ActiveProfiles("management") @AutoConfigureMockMvc
class RbacApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;
    @BeforeEach void seed(){
        jdbc.update("DELETE FROM identity_audit");jdbc.update("DELETE FROM identity_account_role");jdbc.update("DELETE FROM identity_role_permission");jdbc.update("DELETE FROM identity_role");
        jdbc.update("DELETE FROM tenant_audit");jdbc.update("DELETE FROM tenant_membership");
        jdbc.update("DELETE FROM identity_account");jdbc.update("DELETE FROM tenant");
        jdbc.update("INSERT INTO tenant(id,name) VALUES ('ta','A'),('tb','B')");
        jdbc.update("INSERT INTO identity_account(id,username,password_hash,platform_admin,tenant_id) VALUES ('root','root','unused',TRUE,NULL),('a','alice','unused',FALSE,'ta'),('b','bob','unused',FALSE,'ta')");
    }
    MockHttpServletRequestBuilder actor(MockHttpServletRequestBuilder r,String name){return r.with(user(name)).with(csrf());}
    MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder r,Object value)throws Exception{return r.contentType("application/json").content(json.writeValueAsString(value));}
    String createRole(String code,List<String> permissions)throws Exception{
        var response=mvc.perform(body(actor(post("/api/management/iam/roles"),"root"),Map.of("code",code,"name",code,"permissions",permissions)))
            .andExpect(status().isCreated()).andReturn().getResponse();
        return json.readTree(response.getContentAsString()).get("id").asText();
    }
    void assign(String account,String role){jdbc.update("INSERT INTO identity_account_role(account_id,role_id) VALUES(?,?)",account,role);}
    @Test void menuCatalogAndPageRoleGrantsAreRegisteredAndRevocable() throws Exception {
        mvc.perform(actor(get("/api/management/iam/menus"),"alice")).andExpect(status().isForbidden());
        mvc.perform(actor(get("/api/management/iam/menus"),"root"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(7));
        var created=mvc.perform(body(actor(post("/api/management/iam/roles"),"root"),
            Map.of("code","device-page","name","Device page","menus",List.of("page:devices"))))
            .andExpect(status().isCreated()).andReturn().getResponse();
        var role=json.readTree(created.getContentAsString());
        var permissions=json.convertValue(role.get("permissions"),List.class);
        assertTrue(permissions.containsAll(List.of("page:devices","device:read","device:write","device:credential","product:read")));
        assertFalse(permissions.contains("page:products"));
        assertFalse(permissions.contains("page:categories"));
        String id=role.get("id").asText();assign("a",id);
        var me=mvc.perform(actor(get("/api/management/me"),"alice")).andExpect(status().isOk()).andReturn().getResponse();
        assertTrue(json.convertValue(json.readTree(me.getContentAsString()).get("permissions"),List.class).contains("page:devices"));
        mvc.perform(body(actor(put("/api/management/iam/roles/"+id),"root"),
            Map.of("name","Device page","enabled",true,"revision",0,"menus",List.of())))
            .andExpect(status().isOk()).andExpect(jsonPath("$.permissions.length()").value(0));
        mvc.perform(actor(get("/api/management/me"),"alice")).andExpect(jsonPath("$.permissions.length()").value(0));
    }
    @Test void menuInputRejectsUnknownDuplicatesMixedPayloadsAndDelegationEscalation() throws Exception {
        for (var menus:List.of(List.of("page:missing"),List.of("*"),List.of("page:products","page:products"))) {
            mvc.perform(body(actor(post("/api/management/iam/roles"),"root"),Map.of("code","bad-menu","name","Bad","menus",menus)))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(body(actor(post("/api/management/iam/roles"),"root"),
            Map.of("code","mixed","name","Mixed","menus",List.of(),"permissions",List.of())))
            .andExpect(status().isBadRequest());
        var delegated=createRole("menu-delegated",List.of("iam:role:write","page:devices","device:read"));assign("a",delegated);
        mvc.perform(body(actor(post("/api/management/iam/roles"),"alice"),
            Map.of("code","escalate-menu","name","Bad","menus",List.of("page:devices"))))
            .andExpect(status().isForbidden());
    }
    @Test void editingMigratedReadOnlyMenusDoesNotGrantWritesAndSharedCapabilitiesSurviveRemoval() throws Exception {
        String reader=createRole("legacy-page-reader",List.of("page:products","product:read"));
        mvc.perform(body(actor(put("/api/management/iam/roles/"+reader),"root"),
            Map.of("name","Renamed","enabled",false,"revision",0,"menus",List.of("page:products"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.permissions.length()").value(2));
        assertFalse(jdbc.queryForList("SELECT permission FROM identity_role_permission WHERE role_id=?",String.class,reader).contains("product:write"));
        var response=mvc.perform(body(actor(post("/api/management/iam/roles"),"root"),
            Map.of("code","shared-pages","name","Shared","menus",List.of("page:products","page:devices"))))
            .andExpect(status().isCreated()).andReturn().getResponse();
        String id=json.readTree(response.getContentAsString()).get("id").asText();
        mvc.perform(body(actor(put("/api/management/iam/roles/"+id),"root"),
            Map.of("name","Shared","enabled",true,"revision",0,"menus",List.of("page:devices"))))
            .andExpect(status().isOk());
        var permissions=jdbc.queryForList("SELECT permission FROM identity_role_permission WHERE role_id=?",String.class,id);
        assertTrue(permissions.containsAll(List.of("page:devices","product:read","device:write")));
        assertFalse(permissions.contains("page:products"));assertFalse(permissions.contains("product:write"));
        mvc.perform(body(actor(put("/api/management/iam/roles/"+id),"root"),
            Map.of("name","Stale","enabled",true,"revision",0,"menus",List.of())))
            .andExpect(status().isConflict());
    }
    @Test void noRoleMeansNoPlatformAccessAndNoSuperAdminByName()throws Exception{
        mvc.perform(actor(get("/api/management/iam/roles"),"alice")).andExpect(status().isForbidden());
        mvc.perform(actor(get("/api/management/iam/users"),"alice")).andExpect(status().isForbidden());
        mvc.perform(actor(get("/api/management/platform/tenants"),"alice")).andExpect(status().isForbidden());
        mvc.perform(actor(get("/api/management/me"),"alice")).andExpect(jsonPath("$.permissions.length()").value(0));
    }
    @Test void tenantReadRoleAllowsReadButNotWrites()throws Exception{
        String role=createRole("tenant-reader",List.of("tenant:read"));assign("a",role);
        mvc.perform(actor(get("/api/management/platform/tenants"),"alice")).andExpect(status().isOk());
        mvc.perform(body(actor(post("/api/management/platform/tenants"),"alice"),Map.of("name","No write"))).andExpect(status().isForbidden());
        mvc.perform(actor(get("/api/management/me"),"alice")).andExpect(jsonPath("$.permissions[0]").value("tenant:read"));
    }
    @Test void rolesCombineAndDisabledRolesStopAuthorizing()throws Exception{
        String read=createRole("read-role",List.of("tenant:read"));String write=createRole("write-role",List.of("tenant:write"));assign("a",read);assign("a",write);
        mvc.perform(body(actor(post("/api/management/platform/tenants"),"alice"),Map.of("name","From role"))).andExpect(status().isCreated());
        mvc.perform(body(actor(put("/api/management/iam/roles/"+write),"root"),Map.of("name","write-role","enabled",false,"revision",0,"permissions",List.of("tenant:write"))))
            .andExpect(status().isOk());
        mvc.perform(body(actor(post("/api/management/platform/tenants"),"alice"),Map.of("name","Denied"))).andExpect(status().isForbidden());
        mvc.perform(actor(get("/api/management/platform/tenants"),"alice")).andExpect(status().isOk());
    }
    @Test void revokeRolePermissionsAndAssignmentsTakeEffectWithoutRelogin()throws Exception{
        String role=createRole("reader",List.of("tenant:read"));assign("a",role);
        mvc.perform(actor(get("/api/management/platform/tenants"),"alice")).andExpect(status().isOk());
        mvc.perform(body(actor(put("/api/management/iam/roles/"+role),"root"),Map.of("name","reader","enabled",true,"revision",0,"permissions",List.of())))
            .andExpect(status().isOk());
        mvc.perform(actor(get("/api/management/platform/tenants"),"alice")).andExpect(status().isForbidden());
        mvc.perform(body(actor(put("/api/management/iam/users/a/roles"),"root"),Map.of("roleIds",List.of(),"revision",0)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.roleIds.length()").value(0));
    }
    @Test void delegatedAdministratorCannotEscalateThroughRoleCreationEditingOrAssignment()throws Exception{
        String delegated=createRole("delegated",List.of("iam:role:read","iam:role:write","iam:grant","iam:user:read"));assign("a",delegated);
        String powerful=createRole("powerful",List.of("tenant:write"));
        mvc.perform(body(actor(post("/api/management/iam/roles"),"alice"),Map.of("code","escalation","name","Bad","permissions",List.of("tenant:write")))).andExpect(status().isForbidden());
        mvc.perform(body(actor(put("/api/management/iam/roles/"+powerful),"alice"),Map.of("name","Bad","enabled",true,"revision",0,"permissions",List.of())))
            .andExpect(status().isForbidden());
        mvc.perform(body(actor(put("/api/management/iam/users/b/roles"),"alice"),Map.of("roleIds",List.of(powerful),"revision",0)))
            .andExpect(status().isForbidden());
        mvc.perform(body(actor(put("/api/management/iam/users/a/roles"),"alice"),Map.of("roleIds",List.of(delegated),"revision",0)))
            .andExpect(status().isForbidden());
    }
    @Test void unknownPermissionsWildcardsAndDuplicateCodesAreRejected()throws Exception{
        for(var permissions:List.of(List.of("*"),List.of("platform:superadmin"),List.of("tenant:read","tenant:read"))){
            mvc.perform(body(actor(post("/api/management/iam/roles"),"root"),Map.of("code","bad-role","name","Bad","permissions",permissions))).andExpect(status().isBadRequest());
        }
        createRole("duplicate",List.of());
        mvc.perform(body(actor(post("/api/management/iam/roles"),"root"),Map.of("code","duplicate","name","Other","permissions",List.of())))
            .andExpect(status().isConflict());
    }
    @Test void createUserStoresHashAndReturnsSafeProjection()throws Exception{
        var response=mvc.perform(body(actor(post("/api/management/iam/users"),"root"),Map.of("username","new-user","password","short","tenantId","ta")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.platformAdmin").value(false)).andExpect(jsonPath("$.roleIds.length()").value(0))
            .andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.passwordHash").doesNotExist()).andReturn();
        JsonNode created=json.readTree(response.getResponse().getContentAsString());
        String hash=jdbc.queryForObject("SELECT password_hash FROM identity_account WHERE id=?",String.class,created.get("id").asText());assertTrue(encoder.matches("short",hash));
        mvc.perform(body(actor(post("/api/management/iam/users"),"root"),Map.of("username","new-user","password","another","tenantId","ta"))).andExpect(status().isConflict());
    }
    @Test void superAdminAndOwnAccountAreProtectedAndStaleChangesFail()throws Exception{
        mvc.perform(body(actor(put("/api/management/iam/users/root"),"root"),Map.of("enabled",false,"revision",0))).andExpect(status().isForbidden());
        mvc.perform(body(actor(put("/api/management/iam/users/root/roles"),"root"),Map.of("roleIds",List.of(),"revision",0))).andExpect(status().isForbidden());
        mvc.perform(body(actor(put("/api/management/iam/users/a"),"root"),Map.of("enabled",false,"revision",9))).andExpect(status().isConflict());
        mvc.perform(body(actor(put("/api/management/iam/users/a"),"root"),Map.of("enabled",false,"revision",0))).andExpect(status().isOk());
        mvc.perform(actor(get("/api/management/me"),"alice")).andExpect(status().isUnauthorized());
    }
    @Test void assignmentRejectsDisabledRoleAndStaleRevision()throws Exception{
        String role=createRole("inactive",List.of("tenant:read"));jdbc.update("UPDATE identity_role SET enabled=FALSE WHERE id=?",role);
        mvc.perform(body(actor(put("/api/management/iam/users/a/roles"),"root"),Map.of("roleIds",List.of(role),"revision",0))).andExpect(status().isBadRequest());
        mvc.perform(body(actor(put("/api/management/iam/users/a/roles"),"root"),Map.of("roleIds",List.of(),"revision",5))).andExpect(status().isConflict());
    }
    @Test void csrfAndAuditAreEnforced()throws Exception{
        mvc.perform(body(post("/api/management/iam/roles").with(user("root")),Map.of("code","unsafe","name","Unsafe","permissions",List.of())))
            .andExpect(status().isForbidden());
        String role=createRole("audit-role",List.of("tenant:read"));
        mvc.perform(body(actor(put("/api/management/iam/users/b/roles"),"root"),Map.of("roleIds",List.of(role),"revision",0))).andExpect(status().isOk());
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM identity_audit",Integer.class));
    }
    @Test void tenantUserAdministrationCannotListOrModifyAnotherTenantAccount() throws Exception {
        jdbc.update("INSERT INTO identity_account(id,username,password_hash,tenant_id) VALUES ('c','other-tenant','unused','tb')");
        var role=createRole("tenant-user-admin",List.of("iam:user:read","iam:user:write","iam:grant"));assign("a",role);
        mvc.perform(actor(get("/api/management/iam/users"),"alice")).andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(2));
        mvc.perform(body(actor(put("/api/management/iam/users/c"),"alice"),Map.of("enabled",false,"revision",0))).andExpect(status().isForbidden());
        mvc.perform(body(actor(put("/api/management/iam/users/c/roles"),"alice"),Map.of("roleIds",List.of(),"revision",0))).andExpect(status().isForbidden());
    }
}
