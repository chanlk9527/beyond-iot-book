package com.nebula.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

@SpringBootTest(properties={
    "spring.datasource.url=jdbc:h2:mem:tenant-management;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "DATABASE_PASSWORD=test"
})
@ActiveProfiles("management")
@AutoConfigureMockMvc
class TenantManagementApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;
    static final String BASE="/api/management/platform/tenants";
    @BeforeEach void seed() {
        jdbc.update("DELETE FROM tenant_audit");
        jdbc.update("DELETE FROM tenant_membership");
        jdbc.update("DELETE FROM identity_account_role");
        jdbc.update("DELETE FROM identity_role_permission");
        jdbc.update("DELETE FROM identity_role");
        jdbc.update("DELETE FROM identity_audit");
        jdbc.update("DELETE FROM identity_account");
        jdbc.update("DELETE FROM tenant");
        jdbc.update("INSERT INTO tenant(id,name) VALUES ('ta','Tenant A'),('tb','Tenant B')");
        jdbc.update("INSERT INTO identity_account(id,username,password_hash,platform_admin,tenant_id) VALUES ('root','root','unused',TRUE,NULL),('a','alice','unused',FALSE,'ta'),('b','bob','unused',FALSE,'tb')");
        jdbc.update("INSERT INTO tenant_membership(tenant_id,account_id,role_code) VALUES ('ta','a','OWNER'),('tb','b','VIEWER')");
    }
    MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) { return request.with(user("root")).with(csrf()); }
    @Test void adminCanCreateRenameDisableAndAudit() throws Exception {
        var result=mvc.perform(admin(post(BASE)).contentType("application/json").content("{\"name\":\"Created tenant\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.memberCount").value(0)).andReturn();
        String id=json.readTree(result.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(admin(put(BASE+"/"+id)).contentType("application/json").content("{\"name\":\"Renamed\",\"enabled\":false,\"revision\":0}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.revision").value(1));
        mvc.perform(admin(get(BASE+"/"+id+"/audit"))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    }
    @Test void tenantOwnerIsNotPlatformAdministrator() throws Exception {
        for(String path:new String[]{BASE,BASE+"/ta",BASE+"/ta/members",BASE+"/ta/audit"}) {
            mvc.perform(get(path).with(user("alice"))).andExpect(status().isForbidden());
        }
        mvc.perform(post(BASE).with(user("alice")).with(csrf()).contentType("application/json").content("{\"name\":\"forbidden\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/management/me").with(user("alice"))).andExpect(jsonPath("$.platformAdmin").value(false));
    }
    @Test void platformAdminDoesNotAutomaticallyJoinAnyAccessibleTenant() throws Exception {
        mvc.perform(get("/api/management/tenants").with(user("root"))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/management/tenants/ta").with(user("root"))).andExpect(status().isForbidden());
    }
    @Test void singleTenantMemberCanBeDisabledAndRestoredButNotBoundToAnotherTenant() throws Exception {
        mvc.perform(admin(post(BASE+"/ta/members")).contentType("application/json").content("{\"username\":\"bob\",\"role\":\"ADMIN\"}"))
            .andExpect(status().isConflict());
        mvc.perform(admin(put(BASE+"/ta/members/a")).contentType("application/json").content("{\"role\":\"VIEWER\",\"enabled\":false,\"revision\":0}"))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/management/tenants/ta").with(user("alice"))).andExpect(status().isForbidden());
        mvc.perform(admin(put(BASE+"/ta/members/a")).contentType("application/json").content("{\"role\":\"ADMIN\",\"enabled\":true,\"revision\":1}"))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/management/tenants/ta").with(user("alice"))).andExpect(status().isOk());
        mvc.perform(get("/api/management/tenants/tb").with(user("alice"))).andExpect(status().isForbidden());
    }
    @Test void disabledTenantDeniesMembersButRemainsManageable() throws Exception {
        mvc.perform(admin(put(BASE+"/ta")).contentType("application/json").content("{\"name\":\"Tenant A\",\"enabled\":false,\"revision\":0}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/management/tenants/ta").with(user("alice"))).andExpect(status().isForbidden());
        mvc.perform(admin(get(BASE+"/ta"))).andExpect(status().isOk());
    }
    @Test void permissionsAreRecheckedAfterPlatformGrantRevocation() throws Exception {
        jdbc.update("UPDATE identity_account SET platform_admin=FALSE,tenant_id='ta' WHERE id='root'");
        mvc.perform(admin(get(BASE))).andExpect(status().isForbidden());
    }
    @Test void validatesInputAndRejectsStaleUpdates() throws Exception {
        mvc.perform(admin(post(BASE)).contentType("application/json").content("{\"name\":\"  \"}")).andExpect(status().isBadRequest());
        mvc.perform(admin(get(BASE+"?size=101"))).andExpect(status().isBadRequest());
        mvc.perform(admin(put(BASE+"/ta")).contentType("application/json").content("{\"name\":\"stale\",\"enabled\":true,\"revision\":7}"))
            .andExpect(status().isConflict());
        mvc.perform(admin(post(BASE+"/ta/members")).contentType("application/json").content("{\"username\":\"alice\",\"role\":\"OWNER\"}"))
            .andExpect(status().isConflict());
        mvc.perform(admin(post(BASE+"/ta/members")).contentType("application/json").content("{\"username\":\"missing\",\"role\":\"VIEWER\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(admin(post(BASE+"/ta/members")).contentType("application/json").content("{\"username\":\"bob\",\"role\":\"SUPER_ADMIN\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(admin(put(BASE+"/ta/members/b")).contentType("application/json").content("{\"role\":\"VIEWER\",\"enabled\":true,\"revision\":0}"))
            .andExpect(status().isConflict());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM tenant_audit",Integer.class));
    }
    @Test void writesRequireCsrfAndListsArePaginated() throws Exception {
        mvc.perform(post(BASE).with(user("root")).contentType("application/json").content("{\"name\":\"unsafe\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(admin(get(BASE+"?query=Tenant&page=2&size=1"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.items[0].id").value("tb"));
        mvc.perform(admin(get(BASE).param("query","Tenant A"))).andExpect(jsonPath("$.total").value(1));
    }
    MockHttpServletRequestBuilder newUser(String tenant, String username, String password, String role) throws Exception {
        return post(BASE+"/"+tenant+"/users").contentType("application/json")
            .content(json.writeValueAsString(java.util.Map.of("username",username,"password",password,"role",role)));
    }
    @Test void createsLoginAccountAndMembershipTogetherWithoutGrantingPlatformRoles() throws Exception {
        var result=mvc.perform(admin(newUser("ta","new-member","short7","ADMIN")))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.username").value("new-member"))
            .andExpect(jsonPath("$.role").value("ADMIN")).andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.password").doesNotExist()).andExpect(jsonPath("$.passwordHash").doesNotExist()).andReturn();
        String id=json.readTree(result.getResponse().getContentAsString()).get("accountId").asText();
        assertTrue(encoder.matches("short7",jdbc.queryForObject("SELECT password_hash FROM identity_account WHERE id=?",String.class,id)));
        assertEquals(false,jdbc.queryForObject("SELECT platform_admin FROM identity_account WHERE id=?",Boolean.class,id));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM identity_account_role WHERE account_id=?",Integer.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM tenant_audit WHERE subject_id=? AND action='MEMBER_USER_CREATED'",Integer.class,id));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM identity_audit WHERE subject_id=? AND action='USER_CREATED'",Integer.class,id));
        var login=mvc.perform(post("/api/management/auth/login").with(csrf()).param("username","new-member").param("password","short7"))
            .andExpect(status().isNoContent()).andReturn();
        var session=(org.springframework.mock.web.MockHttpSession)login.getRequest().getSession(false);
        assertNotNull(session);
        mvc.perform(get("/api/management/tenants").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value("ta"));
        mvc.perform(get("/api/management/me").session(session)).andExpect(jsonPath("$.permissions.length()").value(0));
        mvc.perform(get("/api/management/tenants/tb").session(session)).andExpect(status().isForbidden());
    }
    @Test void duplicateUsernameDoesNotChangeExistingAccountOrAddMembership() throws Exception {
        mvc.perform(admin(newUser("ta","bob","replacement","VIEWER"))).andExpect(status().isConflict());
        assertEquals("unused",jdbc.queryForObject("SELECT password_hash FROM identity_account WHERE id='b'",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM tenant_membership WHERE tenant_id='ta' AND account_id='b'",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM identity_audit",Integer.class));
    }
    @Test void rejectsInvalidCredentialsAndTenantRoleWithoutCreatingAnAccount() throws Exception {
        for (String[] input : new String[][]{{"bad user ","ok","VIEWER"},{"invalid"," ","VIEWER"},{"invalid","密".repeat(25),"VIEWER"},{"invalid","ok","SUPER_ADMIN"}}) {
            mvc.perform(admin(newUser("ta",input[0],input[1],input[2]))).andExpect(status().isBadRequest());
        }
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM identity_account",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM tenant_audit",Integer.class));
    }
    @Test void missingAndDisabledTenantsNeverLeaveOrphanAccounts() throws Exception {
        mvc.perform(admin(newUser("missing","orphan","ok","VIEWER"))).andExpect(status().isNotFound());
        jdbc.update("UPDATE tenant SET enabled=FALSE WHERE id='ta'");
        mvc.perform(admin(newUser("ta","orphan","ok","VIEWER"))).andExpect(status().isConflict());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM identity_account WHERE username='orphan'",Integer.class));
    }
    @Test void provisioningRequiresBothPermissionsAndTenantRoleAloneCannotAuthorizeIt() throws Exception {
        mvc.perform(newUser("ta","denied","ok","VIEWER").with(user("alice")).with(csrf())).andExpect(status().isForbidden());
        jdbc.update("INSERT INTO identity_role(id,code,name) VALUES ('provisioner','provisioner','Provisioner')");


        jdbc.update("INSERT INTO identity_account_role(account_id,role_id) VALUES ('a','provisioner')");
        for(String permission: new String[]{"iam:user:write","tenant:member:write"}) {
            jdbc.update("DELETE FROM identity_role_permission");
            jdbc.update("INSERT INTO identity_role_permission(role_id,permission) VALUES ('provisioner',?)",permission);
            mvc.perform(newUser("ta","denied","ok","VIEWER").with(user("alice")).with(csrf())).andExpect(status().isForbidden());
        }
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM identity_account WHERE username='denied'",Integer.class));
        jdbc.update("INSERT INTO identity_role_permission(role_id,permission) VALUES ('provisioner','iam:user:write')");
        mvc.perform(newUser("ta","delegated","ok","VIEWER").with(user("alice")).with(csrf())).andExpect(status().isCreated());
    }
    @Test void provisioningRequiresAuthenticationAndCsrf() throws Exception {
        mvc.perform(newUser("ta","unsafe","ok","VIEWER").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(newUser("ta","unsafe","ok","VIEWER").with(user("root"))).andExpect(status().isForbidden());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM identity_account WHERE username='unsafe'",Integer.class));
    }
    @Test void auditFailureRollsBackNewAccountMembershipAndBothAuditTrails() throws Exception {
        jdbc.execute("ALTER TABLE tenant_audit ADD CONSTRAINT reject_provision_audit CHECK (action <> 'MEMBER_USER_CREATED')");
        try {
            assertThrows(jakarta.servlet.ServletException.class,()->mvc.perform(admin(newUser("ta","rollback-user","ok","VIEWER"))));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM identity_account WHERE username='rollback-user'",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM tenant_membership WHERE tenant_id='ta'",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM identity_audit",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM tenant_audit",Integer.class));
        } finally { jdbc.execute("ALTER TABLE tenant_audit DROP CONSTRAINT reject_provision_audit"); }
    }
    @Test void databaseRequiresTenantAndRejectsSecondMembershipEvenWhenDisabled() {
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO identity_account(id,username,password_hash) VALUES ('orphan','orphan','unused')"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("INSERT INTO tenant_membership(tenant_id,account_id,role_code,enabled) VALUES ('tb','a','VIEWER',FALSE)"));
        assertEquals("ta",jdbc.queryForObject("SELECT tenant_id FROM identity_account WHERE id='a'",String.class));
    }
    @Test void tenantAdministratorCannotForgeAnotherTenantWhenCreatingUsers() throws Exception {
        jdbc.update("INSERT INTO identity_role(id,code,name) VALUES ('creator','creator','Creator')");
        jdbc.update("INSERT INTO identity_account_role(account_id,role_id) VALUES ('a','creator')");
        for(String p:java.util.List.of("iam:user:write","tenant:member:write"))jdbc.update("INSERT INTO identity_role_permission(role_id,permission) VALUES ('creator',?)",p);
        mvc.perform(newUser("tb","forged","ok","VIEWER").with(user("alice")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/management/iam/users").with(user("alice")).with(csrf()).contentType("application/json")
            .content("{\"username\":\"own-user\",\"password\":\"short\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.tenantId").value("ta"));
        mvc.perform(get("/api/management/me").with(user("alice"))).andExpect(jsonPath("$.tenantId").value("ta"));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM identity_account WHERE username='forged'",Integer.class));
    }
    @Test void platformAccountCannotCreateUnboundOrdinaryUsers() throws Exception {
        mvc.perform(admin(post("/api/management/iam/users")).contentType("application/json").content("{\"username\":\"orphan\",\"password\":\"ok\"}"))
            .andExpect(status().isBadRequest());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM identity_account WHERE username='orphan'",Integer.class));
    }
}