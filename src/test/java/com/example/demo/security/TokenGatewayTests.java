package com.example.demo.security;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class TokenGatewayTests {
    HttpServer server;
    TokenGateway gateway;
    ObjectMapper json=new ObjectMapper();
    int status=200;
    String response="{\"ok\":true}";
    String receivedPath,receivedBody,receivedType;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            receivedPath=exchange.getRequestURI().getPath();
            receivedBody=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            receivedType=exchange.getRequestHeaders().getFirst("Content-Type");
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json;charset=UTF-8");
            exchange.sendResponseHeaders(status,bytes.length);
            exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();gateway=new TokenGateway("http://127.0.0.1:"+server.getAddress().getPort(),json);
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void forwardsSendAndVerificationJson() {
        assertTrue(gateway.send("comum@gmail.com").body().path("ok").asBoolean());
        assertEquals("/enviar",receivedPath);assertEquals("comum@gmail.com",json.readTree(receivedBody).path("email").asString());
        assertTrue(receivedType.startsWith("application/json"));
        gateway.verify("comum@gmail.com","0123456789");
        assertEquals("/verificar",receivedPath);assertEquals("0123456789",json.readTree(receivedBody).path("codigo").asString());
    }
    @Test void preservesOriginalRateLimitAndUtf8Message() {
        status=429;response="{\"ok\":false,\"msg\":\"Aguarde a próxima tentativa.\"}";
        var result=gateway.send("comum@gmail.com");assertEquals(429,result.status());
        assertEquals("Aguarde a próxima tentativa.",result.body().path("msg").asString());
    }
    @Test void invalidSuccessBodyCannotEstablishAuthentication() {
        response="<html>Erro</html>";assertEquals(502,gateway.verify("comum@gmail.com","1234567890").status());
        response="null";assertFalse(gateway.send("comum@gmail.com").body().path("ok").asBoolean());
    }
    @Test void unavailableServiceIsReportedAsBadGateway() {
        server.stop(0);assertEquals(502,gateway.send("comum@gmail.com").status());
    }
}
