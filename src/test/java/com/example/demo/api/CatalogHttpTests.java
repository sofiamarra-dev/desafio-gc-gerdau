package com.example.demo.api;

import com.example.demo.service.PythonAiClient;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="spring.datasource.url=jdbc:h2:mem:httpflow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class CatalogHttpTests {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @MockitoBean PythonAiClient python;
    final HttpClient client = HttpClient.newHttpClient();

    HttpResponse<String> call(String method, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                .header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void uploadReviewResumeAndExportThroughHttp() throws Exception {
        String key = UUID.randomUUID().toString();
        String create = "{\"codigo_servico\":\"HTTP01\",\"descricao_item\":\"Reparo de motor\",\"request_id\":\""+key+"\"}";
        var saved = call("POST", "/api/catalog/services", create);
        assertEquals(201, saved.statusCode(), saved.body());
        String serviceId = json.readTree(saved.body()).get("catalog_id").asString();
        assertEquals(serviceId, json.readTree(call("POST", "/api/catalog/services", create).body()).get("catalog_id").asString());
        when(python.status()).thenReturn(json.readTree("{\"size\":0,\"revision\":null}"));
        when(python.search("motor")).thenReturn(json.readTree("{\"total\":1,\"results\":[{\"catalog_id\":\""+serviceId+"\"}]}"));
        assertEquals(200, call("POST", "/api/search", "{\"query\":\"motor\"}").statusCode());
        verify(python).sync(anyList(), anyString());

        var template = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/catalog/batches/template")).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, template.statusCode());
        assertTrue(template.headers().firstValue("Content-Disposition").orElse("").contains("modelo-solicitacoes.xlsx"));
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", template.headers().firstValue("Content-Type").orElse(""));
        byte[] xlsx;
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(template.body())); var out = new ByteArrayOutputStream()) {
            assertEquals(1, workbook.getNumberOfSheets());
            var sheet = workbook.getSheetAt(0);
            assertEquals(0, sheet.getLastRowNum(), "O modelo deve vir sem solicitações de exemplo");
            var expected = java.util.List.of("Descrição do Item", "Grupo de Serviço", "Código do Fornecedor", "Código do Serviço", "Unidade de medida", "Descrição da Unidade");
            assertEquals(expected.size(), sheet.getRow(0).getLastCellNum());
            for (int c = 0; c < expected.size(); c++) assertEquals(expected.get(c), sheet.getRow(0).getCell(c).getStringCellValue());
            assertTrue(sheet.getRow(0).getCell(0).getCellComment().getString().getString().contains("Obrigatório"));
            assertEquals("@", sheet.getColumnStyle(2).getDataFormatString(), "Códigos devem manter zeros à esquerda");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("Conserto de motor");
            row.createCell(2).setCellValue("0012");
            row.createCell(4).setCellValue("H");
            workbook.write(out); xlsx=out.toByteArray();
        }
        String boundary = "ReviewTestBoundary";
        var body = new ByteArrayOutputStream();
        body.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"pedidos.xlsx\"\r\nContent-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(xlsx); body.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        var uploaded = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/catalog/batches"))
                .header("Content-Type", "multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(201, uploaded.statusCode(), uploaded.body());
        String batch = json.readTree(uploaded.body()).get("id").asString();
        var rows = json.readTree(call("GET", "/api/catalog/batches/"+batch+"/items", "").body());
        String item = rows.get(0).get("id").asString();
        assertEquals("PENDING", rows.get(0).get("status").asString());
        assertEquals("Conserto de motor", rows.get(0).path("fields").path("descricao_item").asString());
        assertEquals("0012", rows.get(0).path("fields").path("codigo_fornecedor").asString());
        assertEquals("H", rows.get(0).path("fields").path("unidade_medida").asString());
        var decision = call("POST", "/api/catalog/batches/"+batch+"/items/"+item+"/decision", "{\"action\":\"EXISTING\",\"service_id\":\""+serviceId+"\"}");
        assertEquals(200, decision.statusCode(), decision.body());
        var batches = json.readTree(call("GET", "/api/catalog/batches", "").body());
        assertEquals(1, batches.get(0).get("resolved").asInt());
        var exported = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/catalog/batches/"+batch+"/export")).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, exported.statusCode());
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(exported.body()))) {
            assertEquals("EXISTING", workbook.getSheetAt(0).getRow(1).getCell(1).getStringCellValue());
            assertEquals("HTTP01", workbook.getSheetAt(0).getRow(1).getCell(8).getStringCellValue());
        }
        assertEquals(200, call("GET", "/menu.html", "").statusCode());
        var page = call("GET", "/massa.html", "");
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("href=\"/api/catalog/batches/template\""));
        assertTrue(page.body().contains("Baixar planilha modelo"));
    }
}
