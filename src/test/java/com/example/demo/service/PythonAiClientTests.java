package com.example.demo.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PythonAiClientTests {
    @Test void standardizationUsesDictionarySnapshotAndPreservesRecordMetadata() throws Exception {
        var mapper=new ObjectMapper();
        var request=new AtomicReference<tools.jackson.databind.JsonNode>();
        var reply=new AtomicReference<>("{\"dictionary_version\":\"dict:1\",\"descriptions\":[\"SV pintor\",\"SV manutenção\"]}");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/dictionary/standardize",exchange->{
            request.set(mapper.readTree(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
            byte[] bytes=reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json;charset=UTF-8");
            exchange.sendResponseHeaders(200,bytes.length);
            try(var output=exchange.getResponseBody()){output.write(bytes);}
        });
        server.start();
        try {
            var client=new PythonAiClient("http://127.0.0.1:"+server.getAddress().getPort(),mapper);
            var dictionary=new AbbreviationStore.Snapshot("dict:1",java.util.List.of());
            var items=java.util.List.of(java.util.Map.of("catalog_id","a","descricao_item","sv-pintor","codigo_fornecedor","001"),
                    java.util.Map.of("catalog_id","b","descricao_item","sv- manutenção"));
            var normalized=client.standardizeItems(items,dictionary);
            assertEquals("dict:1",request.get().path("dictionary").path("version").asString());
            assertEquals("sv-pintor",request.get().path("descriptions").get(0).asString());
            assertEquals("SV pintor",normalized.getFirst().get("descricao_item"));
            assertEquals("001",normalized.getFirst().get("codigo_fornecedor"));
            assertEquals("SV manutenção",normalized.get(1).get("descricao_item"));
            assertEquals("sv-pintor",items.getFirst().get("descricao_item"));
            for(String invalid:java.util.List.of(
                    "{\"dictionary_version\":\"other\",\"descriptions\":[\"A\",\"B\"]}",
                    "{\"dictionary_version\":\"dict:1\",\"descriptions\":[\"A\"]}",
                    "{\"dictionary_version\":\"dict:1\",\"descriptions\":[7,\"B\"]}")) {
                reply.set(invalid);
                assertThrows(IllegalStateException.class,()->client.standardizeItems(items,dictionary));
            }
        } finally {server.stop(0);}
    }

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
