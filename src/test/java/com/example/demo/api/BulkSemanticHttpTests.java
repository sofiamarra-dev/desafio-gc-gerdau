package com.example.demo.api;

import com.example.demo.service.CatalogStore;
import com.example.demo.service.PythonAiClient;
import org.junit.jupiter.api.Test;
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
    @MockitoBean PythonAiClient python;
    final HttpClient client = HttpClient.newHttpClient();

    HttpResponse<String> post(String path, Object value) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(value))).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void automaticReuseAndCreateEndpointDoNotInsertDuplicates() throws Exception {
        var existing = store.create(Map.of("codigo_servico","BULK-AUTO", "descricao_item","Pintor"), UUID.randomUUID().toString());
        when(python.compare(anyMap(),anyList(),any())).thenReturn(json.readTree(json.writeValueAsString(Map.of("results",List.of(
                Map.of("catalog_id",existing.get("catalog_id"), "semantic_exact",true,"similaridade_semantica",1.0))))));
        String batch = json.readTree(post("/api/catalog/batches/text",Map.of("text","Pintura\nPintura 2")).body()).path("id").asString();
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
}
