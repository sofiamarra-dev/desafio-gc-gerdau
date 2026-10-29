package com.example.demo.api;

import com.example.demo.service.PythonAiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="spring.datasource.url=jdbc:h2:mem:textflow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class TextBatchHttpTests {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @MockitoBean PythonAiClient python;
    final HttpClient client = HttpClient.newHttpClient();

    HttpResponse<String> call(String method, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void textCreatesOnlyPendingRequestsUntilIndividualDecision() throws Exception {
        String text = "Manutenção de motor;Manutenção;0012;;h;hora\n\n;Grupo\nPINTOR HE 50%";
        var response = call("POST", "/api/catalog/batches/text", json.writeValueAsString(Map.of("text", text)));
        assertEquals(201, response.statusCode(), response.body());
        String id = json.readTree(response.body()).path("id").asString();
        var rows = json.readTree(call("GET", "/api/catalog/batches/"+id+"/items", "").body());
        assertEquals(3, rows.size());
        assertEquals(3, rows.get(1).path("row_number").asInt());
        assertEquals("INVALID", rows.get(1).path("status").asString());
        assertEquals("Manutenção de motor", rows.get(0).path("fields").path("descricao_item").asString());
        assertEquals("0012", rows.get(0).path("fields").path("codigo_fornecedor").asString());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM catalog_service", Integer.class));
        String item = rows.get(0).path("id").asString();
        var saved = call("POST", "/api/catalog/batches/"+id+"/items/"+item+"/decision", "{\"action\":\"CREATE\"}");
        assertEquals(200, saved.statusCode(), saved.body());
        assertTrue(json.readTree(saved.body()).path("codigo_servico").asString().startsWith("LOCAL-"));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM catalog_service", Integer.class));
        assertEquals(400, call("POST", "/api/catalog/batches/text", "{\"text\":\"a;b;c;d;e;f;g\"}").statusCode());
        assertEquals(400, call("POST", "/api/catalog/batches/text", "{}").statusCode());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM review_batch", Integer.class));
    }
}
