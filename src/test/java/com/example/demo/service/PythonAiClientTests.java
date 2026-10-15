package com.example.demo.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PythonAiClientTests {
    @Test
    void enviaJsonUtf8ComLimiteVinte() throws Exception {
        var body = new AtomicReference<String>();
        var contentType = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] response = "{\"query\":\"manutenção de motor\",\"total\":0,\"results\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            var mapper = new ObjectMapper();
            var client = new PythonAiClient("http://127.0.0.1:" + server.getAddress().getPort(), mapper);
            var response = client.search("manutenção de motor");
            var request = mapper.readTree(body.get());
            assertEquals(20, request.get("limit").asInt());
            assertEquals("manutenção de motor", request.get("query").asString());
            assertTrue(contentType.get().toLowerCase().contains("charset=utf-8"));
            assertEquals("manutenção de motor", response.get("query").asString());
        } finally {
            server.stop(0);
        }
    }
}
