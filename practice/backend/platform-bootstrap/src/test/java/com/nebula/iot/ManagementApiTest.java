package com.nebula.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:management;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "DATABASE_PASSWORD=test",
        "nebula.management.initial-account.username=seed",
        "nebula.management.initial-account.password=short",
        "nebula.management.initial-account.platform-admin=true"
})
@ActiveProfiles("management")
@AutoConfigureMockMvc
class ManagementApiTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired @Qualifier("initialAccount") ApplicationRunner initialAccount;
    @Autowired ApplicationArguments arguments;

    @BeforeEach void fixtures() {
        jdbc.update("DELETE FROM tenant_membership");
        jdbc.update("DELETE FROM identity_account");
        jdbc.update("DELETE FROM tenant");
        jdbc.update("INSERT INTO tenant(id, name) VALUES ('tenant-a', 'Tenant A'), ('tenant-b', 'Tenant B')");
        jdbc.update("INSERT INTO identity_account(id, username, password_hash, tenant_id) VALUES (?, ?, ?, ?)",
                "a", "alice", passwords.encode("alice-password"), "tenant-a");
        jdbc.update("INSERT INTO identity_account(id, username, password_hash, tenant_id) VALUES (?, ?, ?, ?)",
                "b", "bob", passwords.encode("bob-password"), "tenant-b");
        jdbc.update("INSERT INTO tenant_membership(tenant_id, account_id, role_code) VALUES ('tenant-a', 'a', 'OWNER'), ('tenant-b', 'b', 'VIEWER')");
    }

    private MvcResult csrf(MockHttpSession session) throws Exception {
        var request = get("/api/management/auth/csrf");
        if (session != null) request.session(session);
        return mvc.perform(request).andExpect(status().isOk()).andReturn();
    }
    private MockHttpSession login(String username, String password) throws Exception {
        var token = csrf(null);
        var body = json.readTree(token.getResponse().getContentAsString());
        var session = (MockHttpSession) token.getRequest().getSession(false);
        String oldId = session.getId();
        var result = mvc.perform(post("/api/management/auth/login").session(session)
                        .header(body.get("headerName").asText(), body.get("token").asText())
                        .param("username", username).param("password", password))
                .andExpect(status().isNoContent()).andReturn();
        var authenticated = (MockHttpSession) result.getRequest().getSession(false);
        assertNotEquals(oldId, authenticated.getId(), "login must rotate session id");
        return authenticated;
    }

    @Test void anonymousGetsJson401AndHealthRemainsPublic() throws Exception {
        mvc.perform(get("/api/management/me")).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(401));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
    @Test void loginRequiresCsrf() throws Exception {
        mvc.perform(post("/api/management/auth/login").param("username", "alice").param("password", "alice-password"))
                .andExpect(status().isForbidden());
    }
    @Test void badCredentialsAreJson401() throws Exception {
        var token = csrf(null);
        var body = json.readTree(token.getResponse().getContentAsString());
        mvc.perform(post("/api/management/auth/login")
                        .session((MockHttpSession) token.getRequest().getSession(false))
                        .header(body.get("headerName").asText(), body.get("token").asText())
                        .param("username", "alice").param("password", "wrong"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
    }
    @Test void currentUserDoesNotExposeCredentials() throws Exception {
        mvc.perform(get("/api/management/me").session(login("alice", "alice-password")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value("a"))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }
    @Test void twoTenantsStayIsolatedAndForgedHeadersDoNotGrantAccess() throws Exception {
        var alice = login("alice", "alice-password");
        var bob = login("bob", "bob-password");
        mvc.perform(get("/api/management/tenants").session(alice).header("X-Tenant-Id", "tenant-b"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("tenant-a"));
        mvc.perform(get("/api/management/tenants/tenant-b").session(alice))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        mvc.perform(get("/api/management/tenants/tenant-b").session(bob))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("VIEWER"));
        mvc.perform(get("/api/management/tenants/tenant-a").session(bob)).andExpect(status().isForbidden());
    }
    @Test void membershipRevocationTakesEffectForExistingSession() throws Exception {
        var alice = login("alice", "alice-password");
        jdbc.update("UPDATE tenant_membership SET enabled = FALSE WHERE account_id = 'a'");
        mvc.perform(get("/api/management/tenants/tenant-a").session(alice)).andExpect(status().isForbidden());
        mvc.perform(get("/api/management/tenants").session(alice))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }
    @Test void disabledTenantIsInaccessible() throws Exception {
        var alice = login("alice", "alice-password");
        jdbc.update("UPDATE tenant SET enabled = FALSE WHERE id = 'tenant-a'");
        mvc.perform(get("/api/management/tenants/tenant-a").session(alice)).andExpect(status().isForbidden());
    }
    @Test void disabledAccountInvalidatesExistingSession() throws Exception {
        var alice = login("alice", "alice-password");
        jdbc.update("UPDATE identity_account SET enabled = FALSE WHERE id = 'a'");
        mvc.perform(get("/api/management/me").session(alice)).andExpect(status().isUnauthorized());
        assertTrue(alice.isInvalid());
    }
    @Test void logoutRequiresCsrfAndInvalidatesSession() throws Exception {
        var alice = login("alice", "alice-password");
        mvc.perform(post("/api/management/auth/logout").session(alice)).andExpect(status().isForbidden());
        var body = json.readTree(csrf(alice).getResponse().getContentAsString());
        mvc.perform(post("/api/management/auth/logout").session(alice)
                        .header(body.get("headerName").asText(), body.get("token").asText()))
                .andExpect(status().isNoContent());
        assertTrue(alice.isInvalid());
        mvc.perform(get("/api/management/me")).andExpect(status().isUnauthorized());
    }
    @Test void authenticatedUsersCannotAccessOtherRoutes() throws Exception {
        var alice = login("alice", "alice-password");
        mvc.perform(get("/api/devices").session(alice)).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/env").session(alice)).andExpect(status().isForbidden());
    }
    @Test void shortInitialPasswordSupportsLoginWithoutResettingAccountOrGrantingTenantMembership() throws Exception {
        initialAccount.run(arguments);
        var original = jdbc.queryForObject("SELECT password_hash FROM identity_account WHERE username = 'seed'", String.class);
        assertTrue(passwords.matches("short", original));
        initialAccount.run(arguments);
        assertEquals(original, jdbc.queryForObject("SELECT password_hash FROM identity_account WHERE username = 'seed'", String.class));
        mvc.perform(get("/api/management/tenants").session(login("seed", "short")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }
}
