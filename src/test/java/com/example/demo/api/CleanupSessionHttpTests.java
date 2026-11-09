package com.example.demo.api;

import com.example.demo.security.AdminAccounts;
import com.example.demo.security.TokenGateway;
import com.example.demo.service.CatalogStore;
import com.example.demo.service.PythonAiClient;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import java.net.*;
import java.net.http.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.datasource.url=jdbc:h2:mem:cleanupsessionhttp;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","app.auth.enabled=true"})
class CleanupSessionHttpTests {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired CatalogStore store;
    @Autowired JdbcTemplate db;
    @MockitoBean TokenGateway gateway;
    @MockitoBean PythonAiClient python;
    HttpClient browser;
    String selected;
    @BeforeEach void setup(){
        db.update("DELETE FROM review_item");db.update("DELETE FROM review_batch");
        db.update("DELETE FROM catalog_cleanup_review");db.update("DELETE FROM catalog_cleanup_run");db.update("DELETE FROM catalog_service");
        reset(gateway,python);newBrowser();
        selected=store.create(Map.of("descricao_item","PINTOR"),UUID.randomUUID().toString()).get("catalog_id");
        when(gateway.send(anyString())).thenReturn(new TokenGateway.Reply(200,json.valueToTree(Map.of("ok",true))));
        when(gateway.verify(anyString(),anyString())).thenReturn(new TokenGateway.Reply(200,json.valueToTree(Map.of("ok",true))));
        when(python.standardizeItems(anyList(),any())).thenAnswer(invocation->invocation.getArgument(0));
        when(python.identicalRecords(anyList(),any())).thenReturn(json.valueToTree(Map.of("groups",List.of(),"total_removed",0)));
        when(python.reviewStart(anyList(),any())).thenReturn(json.valueToTree(Map.of("id","d".repeat(64),"status","READY")));
        when(python.reviewStatus(anyString(),anyList(),nullable(String.class))).thenAnswer(invocation->json.valueToTree(
                invocation.getArgument(2)==null?Map.of("status","READY","rows",List.of(Map.of("catalog_id",selected))):Map.of("results",List.of())));
    }
    void newBrowser(){browser=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();}
    URI url(String path){return URI.create("http://localhost:"+port+path);}
    HttpResponse<String> get(String path)throws Exception{return browser.send(HttpRequest.newBuilder(url(path)).GET().build(),HttpResponse.BodyHandlers.ofString());}
    HttpResponse<String> post(String path,Map<String,?> body,boolean csrf)throws Exception{
        var builder=HttpRequest.newBuilder(url(path)).header("Content-Type","application/json");
        if(csrf){var token=json.readTree(get("/api/auth/status").body());builder.header(token.path("csrfHeader").asString(),token.path("csrfToken").asString());}
        return browser.send(builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    void login(String email)throws Exception{
        assertEquals(200,post("/enviar",Map.of("email",email),false).statusCode());
        assertEquals(200,post("/verificar",Map.of("email",email,"codigo","1234567890"),false).statusCode());
    }
    @Test void ordinaryUserCannotOpenOrSaveAdminCleanupAndAdminNeedsCsrf()throws Exception{
        assertEquals(401,get("/api/cleanups/session/status").statusCode());login("normal@gmail.com");
        assertEquals(403,post("/api/cleanups/session/open",Map.of(),true).statusCode());
        assertEquals(403,post("/api/cleanups/session/save",Map.of("version",0),true).statusCode());verifyNoInteractions(python);
        newBrowser();login(AdminAccounts.EMAILS.iterator().next());
        assertEquals(403,post("/api/cleanups/session/open",Map.of(),false).statusCode());
        var response=post("/api/cleanups/session/open",Map.of(),true);assertEquals(200,response.statusCode(),response.body());
    }
    @Test void reviewThroughHttpProjectsLevelThenSaveCommitsAndOtherBrowserHasIndependentWorkspace()throws Exception{
        login(AdminAccounts.EMAILS.iterator().next());
        var opened=post("/api/cleanups/session/open",Map.of(),true);assertEquals(200,opened.statusCode(),opened.body());
        var state=json.readTree(opened.body());String workspace=state.path("id").asString();
        var acknowledgement=post("/api/cleanups/session/acknowledge",Map.of("version",state.path("version").asInt()),true);
        assertEquals(200,acknowledgement.statusCode(),acknowledgement.body());
        state=json.readTree(acknowledgement.body());
        var decision=post("/api/cleanups/session/decision",Map.of("selected",selected,"action","KEEP","version",state.path("version").asInt()),true);
        assertEquals(200,decision.statusCode(),decision.body());assertEquals("0",store.get(selected).get("cleanup_passes"));
        var detail=get("/api/cleanups/session/items/"+selected);assertEquals(200,detail.statusCode(),detail.body());
        assertEquals("1",json.readTree(detail.body()).path("selected").path("cleanup_passes").asString());
        state=json.readTree(decision.body());var saved=post("/api/cleanups/session/save",Map.of("version",state.path("version").asInt()),true);
        assertEquals(200,saved.statusCode(),saved.body());assertEquals("1",store.get(selected).get("cleanup_passes"));
        newBrowser();login(AdminAccounts.EMAILS.iterator().next());
        var other=post("/api/cleanups/session/open",Map.of(),true);assertEquals(200,other.statusCode(),other.body());
        assertNotEquals(workspace,json.readTree(other.body()).path("id").asString());
    }
}
