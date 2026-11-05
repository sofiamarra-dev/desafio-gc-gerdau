package com.example.demo.api;

import com.example.demo.security.TokenGateway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.datasource.url=jdbc:h2:mem:authflow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","app.auth.enabled=true"})
class AuthenticationHttpTests {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @MockitoBean TokenGateway gateway;
    HttpClient client;
    CookieManager cookies;
    @BeforeEach void setup() {
        reset(gateway);cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        client=HttpClient.newBuilder().cookieHandler(cookies).build();
        when(gateway.send(anyString())).thenReturn(new TokenGateway.Reply(200,json.readTree("{\"ok\":true,\"msg\":\"\"}")));
        when(gateway.verify(anyString(),anyString())).thenReturn(new TokenGateway.Reply(200,json.readTree("{\"ok\":true,\"msg\":\"Autenticação concluída.\"}")));
    }
    HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> post(String path,Map<String,?> data,boolean csrf) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).header("Content-Type","application/json");
        if(csrf) {var token=json.readTree(get("/api/auth/status").body());request.header(token.path("csrfHeader").asString(),token.path("csrfToken").asString());}
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(data))).build(),HttpResponse.BodyHandlers.ofString());
    }
    void login(String email) throws Exception {
        var send=post("/enviar",Map.of("email",email),false);assertEquals(200,send.statusCode(),send.body());
        var verified=post("/verificar",Map.of("email",email,"codigo","1234567890"),false);
        assertEquals(200,verified.statusCode(),verified.body());
    }
    @Test void anonymousCannotReadCatalogOrPagesButCanLoadOriginalLogin() throws Exception {
        assertEquals(401,get("/api/catalog/status").statusCode());
        var page=get("/menu.html");assertEquals(302,page.statusCode());assertEquals("/login.html",URI.create(page.headers().firstValue("Location").orElseThrow()).getPath());
        assertEquals(200,get("/login.html").statusCode());assertEquals(200,get("/api/health").statusCode());
        var original=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/token-login.html")).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        String originalHtml=Files.readString(Path.of("auth-token/index.html"));
        String presentedHtml=new String(original.body(),java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(originalHtml.substring(originalHtml.indexOf("<script>")),presentedHtml.substring(presentedHtml.indexOf("<script>")),"O JavaScript original do token deve ser preservado");
        for(String asset:List.of("/design/login.css","/design/token-theme.css","/design/logo-gerdau.png")) assertEquals(200,get(asset).statusCode(),asset);
    }
    @Test void normalUserCanUseCatalogButCannotAccessEitherAdminFeature() throws Exception {
        login("comum@gmail.com");
        var status=json.readTree(get("/api/auth/status").body());assertTrue(status.path("authenticated").asBoolean());assertFalse(status.path("admin").asBoolean());
        assertEquals(200,get("/menu.html").statusCode());assertEquals(200,get("/api/catalog/status").statusCode());
        for(String path:List.of("/api/abbreviations","/api/cleanups","/siglas.html","/limpeza.html")) assertEquals(403,get(path).statusCode(),path);
        assertEquals(403,post("/api/abbreviations",Map.of("code","X"),true).statusCode());
        assertEquals(403,post("/api/cleanups",Map.of(),true).statusCode());
    }
    @Test void configuredAdminGetsServerSideAdminRole() throws Exception {
        login("  DANIELOSTERKAMP38@GMAIL.COM  ");
        assertTrue(json.readTree(get("/api/auth/status").body()).path("admin").asBoolean());
        for(String path:List.of("/api/abbreviations","/api/cleanups","/siglas.html","/limpeza.html")) assertEquals(200,get(path).statusCode(),path);
    }
    @Test void wrongOtpOrAnotherBrowserDoesNotCreateASession() throws Exception {
        when(gateway.verify(anyString(),anyString())).thenReturn(new TokenGateway.Reply(400,json.readTree("{\"ok\":false,\"msg\":\"Código inválido\"}")));
        post("/enviar",Map.of("email","comum@gmail.com"),false);
        assertEquals(400,post("/verificar",Map.of("email","comum@gmail.com","codigo","1234567890"),false).statusCode());
        assertFalse(json.readTree(get("/api/auth/status").body()).path("authenticated").asBoolean());
        clearInvocations(gateway);cookies.getCookieStore().removeAll();
        assertEquals(400,post("/verificar",Map.of("email","comum@gmail.com","codigo","1234567890"),false).statusCode());verifyNoInteractions(gateway);
    }
    @Test void emailSwitchCannotVerifyDifferentEmailOrGrantAdmin() throws Exception {
        post("/enviar",Map.of("email","comum@gmail.com"),false);clearInvocations(gateway);
        assertEquals(400,post("/verificar",Map.of("email","danielosterkamp38@gmail.com","codigo","1234567890","admin",true),false).statusCode());
        verifyNoInteractions(gateway);assertEquals(401,get("/api/catalog/status").statusCode());
    }
    @Test void sessionIdRotatesAndLogoutReallyRevokesAccess() throws Exception {
        post("/enviar",Map.of("email","comum@gmail.com"),false);
        String before=cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("JSESSIONID")).findFirst().orElseThrow().getValue();
        post("/verificar",Map.of("email","comum@gmail.com","codigo","1234567890"),false);
        String after=cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("JSESSIONID")).findFirst().orElseThrow().getValue();assertNotEquals(before,after);
        assertEquals(403,post("/api/auth/logout",Map.of(),false).statusCode());
        assertEquals(204,post("/api/auth/logout",Map.of(),true).statusCode());assertEquals(401,get("/api/catalog/status").statusCode());
    }
    @Test void userCanCreateWithCsrfAndCannotWithoutIt() throws Exception {
        login("comum@gmail.com");var body=Map.of("descricao_item","Teste autenticação","request_id",UUID.randomUUID().toString());
        assertEquals(403,post("/api/catalog/services",body,false).statusCode());
        var allowed=post("/api/catalog/services",body,true);assertEquals(201,allowed.statusCode(),allowed.body());
    }
    @Test void crossOriginLoginIsRejected() throws Exception {
        var response=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/enviar"))
                .header("Origin","http://outro.example").header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"comum@gmail.com\"}")).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(403,response.statusCode());verifyNoInteractions(gateway);
    }
}
