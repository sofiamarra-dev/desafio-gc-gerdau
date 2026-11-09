package com.example.demo.api;

import com.example.demo.service.CatalogStore;
import com.example.demo.service.PythonAiClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="spring.datasource.url=jdbc:h2:mem:bulksemantic;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class BulkSemanticHttpTests {
    @LocalServerPort int port;
    @Autowired CatalogStore store;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @MockitoBean PythonAiClient python;
    final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach void clean() {
        db.update("DELETE FROM review_item"); db.update("DELETE FROM review_batch"); db.update("DELETE FROM catalog_service");
        reset(python);
        when(python.standardizeItems(anyList(),any())).thenAnswer(invocation->invocation.getArgument(0));

    }

    HttpResponse<String> post(String path, Object value) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(value))).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void explicitBulkDecisionSavesStandardizedDescription() throws Exception {
        when(python.standardizeItems(anyList(),any())).thenAnswer(invocation->{
            List<Map<String,String>> items=invocation.getArgument(0);
            return items.stream().map(item->{
                var row=new java.util.LinkedHashMap<>(item);row.put("descricao_item","SV pintor");
                return (Map<String,String>)row;
            }).toList();
        });
        String batch=json.readTree(post("/api/catalog/batches/text",Map.of("text","sv-pintor")).body()).path("id").asString();
        String item=store.batchItems(batch,0,1).getFirst().get("id").toString();
        String path="/api/catalog/batches/"+batch+"/items/"+item+"/decision";
        var response=post(path,Map.of("action","CREATE"));
        assertEquals(200,response.statusCode(),response.body());
        assertEquals("SV pintor",json.readTree(response.body()).path("descricao_item").asString());
        assertEquals("SV pintor",store.all().getFirst().get("descricao_item"));
        assertEquals(response.body(),post(path,Map.of("action","CREATE")).body());
        assertEquals(1,store.all().size());
    }

    @Test void automaticReuseAndCreateEndpointDoNotInsertDuplicates() throws Exception {
        var existing = store.create(Map.of("codigo_servico","BULK-AUTO", "descricao_item","Pintor"), UUID.randomUUID().toString());
        when(python.compare(anyMap(),anyList(),any())).thenReturn(json.readTree(json.writeValueAsString(Map.of("results",List.of(
                Map.of("catalog_id",existing.get("catalog_id"), "identical_record",true,"semantic_exact",false,"similaridade_semantica",0.2))))));
        String batch = json.readTree(post("/api/catalog/batches/text",Map.of("text","Pintor\nPintor")).body()).path("id").asString();
        var items = store.batchItems(batch,0,10);
        String path = "/api/catalog/batches/"+batch+"/items/"+items.getFirst().get("id")+"/auto-match";
        var first = post(path,Map.of());
        assertEquals(200,first.statusCode(),first.body());
        assertTrue(json.readTree(first.body()).path("automatic").asBoolean());
        assertEquals(1,store.all().size());
        assertEquals("EXISTING",store.batchItems(batch,0,10).getFirst().get("status"));
        clearInvocations(python);
        assertEquals(200,post(path,Map.of()).statusCode());
        verifyNoInteractions(python);
        var direct = post("/api/catalog/batches/"+batch+"/items/"+items.get(1).get("id")+"/decision",Map.of("action","CREATE"));
        assertEquals(200,direct.statusCode(),direct.body());
        assertEquals(existing.get("catalog_id"),json.readTree(direct.body()).path("catalog_id").asString());
        assertEquals(1,store.all().size());
        assertEquals("EXISTING",store.batchItems(batch,0,10).get(1).get("status"));
    }
    @Test void semantic100WithDifferentDescriptionStaysPendingAndAllowsExplicitCreation() throws Exception {
        var existing=store.create(Map.of("codigo_servico","PINTOR-HE", "descricao_item","Pintor HE 50%"),UUID.randomUUID().toString());
        when(python.compare(anyMap(),anyList(),any())).thenReturn(json.readTree(json.writeValueAsString(Map.of("results",List.of(
                Map.of("catalog_id",existing.get("catalog_id"),"identical_record",false,"semantic_exact",true,"similaridade_semantica",1.0))))));
        String batch=json.readTree(post("/api/catalog/batches/text",Map.of("text","Pintor")).body()).path("id").asString();
        String item=store.batchItems(batch,0,10).getFirst().get("id").toString();
        var compared=post("/api/catalog/batches/"+batch+"/items/"+item+"/auto-match",Map.of());
        assertEquals(200,compared.statusCode(),compared.body());
        assertFalse(json.readTree(compared.body()).path("automatic").asBoolean());
        assertFalse(json.readTree(compared.body()).path("resolved").asBoolean());
        assertEquals("PENDING",store.batchItems(batch,0,10).getFirst().get("status"));
        assertEquals(1,store.all().size());
        var confirmed=post("/api/catalog/batches/"+batch+"/items/"+item+"/decision",Map.of("action","CREATE"));
        assertEquals(200,confirmed.statusCode(),confirmed.body());
        assertEquals("Pintor",json.readTree(confirmed.body()).path("descricao_item").asString());
        assertEquals("CREATED",store.batchItems(batch,0,10).getFirst().get("status"));
        assertEquals(2,store.all().size());
    }

}
