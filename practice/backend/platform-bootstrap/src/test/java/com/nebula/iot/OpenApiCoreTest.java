package com.nebula.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:openapi;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver","DATABASE_PASSWORD=test","nebula.mqtt.enabled=false"})
@ActiveProfiles("management") @AutoConfigureMockMvc
class OpenApiCoreTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.nebula.iot.identity.application.OpenApiService service;
    String register(String id) throws Exception {
        String result=mvc.perform(post("/api/management/openapi/apps").with(user("open-admin")).with(csrf())
            .contentType("application/json").content(json.writeValueAsString(Map.of("appId",id,"name","Test","scopes",List.of("device:read")))))
            .andExpect(status().isCreated()).andExpect(header().string("Cache-Control","no-store"))
            .andReturn().getResponse().getContentAsString();
        return json.readTree(result).path("clientSecret").asText();
    }
    String issue(String id,String secret) throws Exception {
        String result=mvc.perform(post("/api/open/v1/oauth/token").contentType("application/json")
            .content(json.writeValueAsString(Map.of("appId",id,"clientSecret",secret,"scopes",List.of("device:read")))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(result).path("accessToken").asText();
    }
    @org.junit.jupiter.api.BeforeEach void admin(){
        if(jdbc.queryForObject("SELECT COUNT(*) FROM identity_account WHERE username='open-admin'",Integer.class)==0)
            jdbc.update("INSERT INTO identity_account(id,username,password_hash,platform_admin) VALUES('open-admin','open-admin','unused',TRUE)");
    }
    @Test void bearerLifecycleIsIsolatedFromManagement() throws Exception {
        String id=UUID.randomUUID().toString(), secret=register(id), token=issue(id,secret);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.authenticate(token,"device:control"));
        mvc.perform(get("/api/open/v1/me").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.appId").value(id));
        mvc.perform(get("/api/open/v1/me").with(user("open-admin"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/management/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/open/v1/oauth/revoke").header("Authorization","Bearer "+token)).andExpect(status().isNoContent());
        mvc.perform(get("/api/open/v1/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM openapi_access_token WHERE token_hash=?",Integer.class,token));
    }
    @Test void disablingAndRotationInvalidateExistingTokens() throws Exception {
        String id=UUID.randomUUID().toString(), secret=register(id), token=issue(id,secret);
        mvc.perform(put("/api/management/openapi/apps/"+id+"/status").with(user("open-admin")).with(csrf())
            .contentType("application/json").content("{\"enabled\":false,\"revision\":0}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/open/v1/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/management/openapi/apps/"+id+"/status").with(user("open-admin")).with(csrf())
            .contentType("application/json").content("{\"enabled\":true,\"revision\":1}")).andExpect(status().isNoContent());
        mvc.perform(get("/api/open/v1/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        String next=issue(id,secret);
        mvc.perform(post("/api/management/openapi/apps/"+id+"/secret").with(user("open-admin")).with(csrf())
            .contentType("application/json").content("{\"revision\":2}")).andExpect(status().isOk());
        mvc.perform(get("/api/open/v1/me").header("Authorization","Bearer "+next)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/open/v1/oauth/token").contentType("application/json")
            .content(json.writeValueAsString(Map.of("appId",id,"clientSecret",secret,"scopes",List.of("device:read")))))
            .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/management/openapi/apps/"+id+"/status").with(user("open-admin")).with(csrf())
            .contentType("application/json").content("{\"enabled\":true,\"revision\":0}")).andExpect(status().isConflict());
    }
    @Test void rejectsScopeEscalationDelegationAndInvalidCredentials() throws Exception {
        String id=UUID.randomUUID().toString(), secret=register(id);
        for(String scope:List.of("device:control","device:read")) {
            var input=new HashMap<String,Object>(Map.of("appId",id,"clientSecret",secret,"scopes",List.of(scope)));
            if(scope.equals("device:read"))input.put("subject","forged-user");
            mvc.perform(post("/api/open/v1/oauth/token").contentType("application/json").content(json.writeValueAsString(input)))
                .andExpect(status().is(scope.equals("device:control")?403:400));
        }
        mvc.perform(post("/api/open/v1/oauth/token").contentType("application/json")
            .content(json.writeValueAsString(Map.of("appId",id,"clientSecret","wrong","scopes",List.of("device:read")))))
            .andExpect(status().isUnauthorized());
        String token=issue(id,secret);
        jdbc.update("UPDATE openapi_access_token SET expires_at=TIMESTAMP '2000-01-01 00:00:00' WHERE app_id=?",id);
        mvc.perform(get("/api/open/v1/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/open/v1/me").header("Authorization","Bearer invalid")).andExpect(status().isUnauthorized());
    }
    @Test void appRegistrationRequiresManagementLoginAndCsrf() throws Exception {
        mvc.perform(post("/api/management/openapi/apps").with(user("open-admin"))
            .contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/management/openapi/apps").with(csrf())
            .contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
    }
    @Test void tenantDeveloperApplicationRequiresReviewAndStaysIsolated() throws Exception {
        String tenant=UUID.randomUUID().toString(),other=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO tenant(id,name) VALUES (?, 'Developer tenant'), (?, 'Other tenant')",tenant,other);
        for(String role:List.of("ADMIN","VIEWER")){
            String account=UUID.randomUUID().toString(),username="dev-"+role;
            jdbc.update("INSERT INTO identity_account(id,username,password_hash,tenant_id) VALUES(?,?, 'unused',?)",account,username,tenant);
            jdbc.update("INSERT INTO tenant_membership(tenant_id,account_id,role_code) VALUES(?,?,?)",tenant,account,role);
        }
        String path="/api/management/developer/tenants/"+tenant+"/apps";
        mvc.perform(get(path).with(user("dev-VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/management/developer/tenants/"+other+"/apps").with(user("dev-ADMIN"))).andExpect(status().isForbidden());
        var result=mvc.perform(post(path).with(user("dev-ADMIN")).with(csrf()).contentType("application/json")
            .content("{\"name\":\"Home App\",\"scopes\":[\"device:read\",\"video:watch\"]}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.reviewStatus").value("PENDING"))
            .andExpect(jsonPath("$.enabled").value(false)).andReturn().getResponse().getContentAsString();
        var app=json.readTree(result);String appId=app.path("appId").asText();long revision=app.path("revision").asLong();
        mvc.perform(post(path+"/"+appId+"/secret").with(user("dev-ADMIN")).with(csrf()).contentType("application/json")
            .content("{\"revision\":"+revision+"}")).andExpect(status().isConflict());
        String review="/api/management/developer/reviews/"+appId;
        String approval="{\"revision\":"+revision+",\"approved\":true,\"scopes\":[\"device:read\"],\"note\":\"OK\"}";
        mvc.perform(post(review).with(user("dev-ADMIN")).with(csrf()).contentType("application/json").content(approval)).andExpect(status().isForbidden());
        mvc.perform(post(review).with(user("open-admin")).with(csrf()).contentType("application/json")
            .content(approval.replace("device:read","device:control"))).andExpect(status().isBadRequest());
        mvc.perform(post(review).with(user("open-admin")).with(csrf()).contentType("application/json").content(approval))
            .andExpect(status().isOk()).andExpect(jsonPath("$.scopes").value("device:read"));
        mvc.perform(post(review).with(user("open-admin")).with(csrf()).contentType("application/json").content(approval)).andExpect(status().isConflict());
        String secretResult=mvc.perform(post(path+"/"+appId+"/secret").with(user("dev-ADMIN")).with(csrf()).contentType("application/json")
            .content("{\"revision\":"+(revision+1)+"}"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString();
        String secret=json.readTree(secretResult).path("clientSecret").asText(), token=issue(appId,secret);
        mvc.perform(get("/api/open/v1/me").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andExpect(jsonPath("$.tenantId").value(tenant));
        String list=mvc.perform(get(path).with(user("dev-ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(list.contains(secret));assertFalse(list.contains("secretHash"));
        mvc.perform(get(path+"/"+appId+"/audit").with(user("dev-ADMIN"))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        mvc.perform(get(review+"/audit").with(user("open-admin"))).andExpect(status().isOk());
        mvc.perform(post(path+"/"+appId+"/submit").with(user("dev-ADMIN")).with(csrf()).contentType("application/json")
            .content("{\"revision\":"+(revision+2)+",\"scopes\":[\"video:watch\"]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reviewStatus").value("PENDING"));
        mvc.perform(get("/api/open/v1/me").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        String rejection="{\"revision\":"+(revision+3)+",\"approved\":false,\"scopes\":[],\"note\":\"请补充用途\"}";
        mvc.perform(post(review).with(user("open-admin")).with(csrf()).contentType("application/json").content(rejection))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reviewStatus").value("REJECTED"));
        mvc.perform(post(path+"/"+appId+"/submit").with(user("dev-ADMIN")).with(csrf()).contentType("application/json")
            .content("{\"revision\":"+(revision+4)+",\"scopes\":[\"device:read\"]}"))
            .andExpect(status().isOk());
        mvc.perform(post(review).with(user("open-admin")).with(csrf()).contentType("application/json")
            .content("{\"revision\":"+(revision+5)+",\"approved\":true,\"scopes\":[\"device:read\"],\"note\":\"OK\"}"))
            .andExpect(status().isOk());
        String fresh=issue(appId,secret);
        jdbc.update("UPDATE tenant SET enabled=FALSE WHERE id=?",tenant);
        mvc.perform(get("/api/open/v1/me").header("Authorization","Bearer "+fresh)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).with(user("dev-ADMIN"))).andExpect(status().isForbidden());
    }
}
