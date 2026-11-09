package com.example.demo.api;

import com.example.demo.service.PythonAiClient;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties="spring.datasource.url=jdbc:h2:mem:quickhttp;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class QuickBatchHttpTests {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @MockitoBean PythonAiClient python;
    HttpClient client=HttpClient.newHttpClient();
    URI url(String path) { return URI.create("http://127.0.0.1:"+port+path); }
    HttpResponse<String> post(String path,String body) throws Exception {
        return client.send(HttpRequest.newBuilder(url(path)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @org.junit.jupiter.api.BeforeEach void standardization() {
        when(python.standardizeItems(anyList(),any())).thenAnswer(invocation->invocation.getArgument(0));
    }
    @Test void quickTextBatchCreatesOnceAndExportsVerificationLevel() throws Exception {
        var invalid=post("/api/catalog/batches/text","{\"text\":\"Pintura\",\"mode\":\"INVALID\"}");assertEquals(400,invalid.statusCode());
        var uploaded=post("/api/catalog/batches/text","{\"text\":\"Pintura industrial;;;;;\",\"mode\":\"QUICK\"}");
        assertEquals(201,uploaded.statusCode(),uploaded.body());
        String batch=json.readTree(uploaded.body()).path("id").asString();
        var items=client.send(HttpRequest.newBuilder(url("/api/catalog/batches/"+batch+"/items")).GET().build(),HttpResponse.BodyHandlers.ofString());
        String item=json.readTree(items.body()).get(0).path("id").asString();
        when(python.compare(anyMap(),anyList(),any())).thenReturn(json.readTree("{\"results\":[],\"quick_creation_check\":{\"version\":\"quick-creation-1\",\"candidate_count\":0,\"requires_review\":false}}"));
        String path="/api/catalog/batches/"+batch+"/items/"+item+"/auto-match";
        var created=post(path,"{}");assertEquals(200,created.statusCode(),created.body());
        assertTrue(json.readTree(created.body()).path("created_quick").asBoolean());
        var repeat=post(path,"{}");assertEquals(json.readTree(created.body()).path("service"),json.readTree(repeat.body()).path("service"));
        var exported=client.send(HttpRequest.newBuilder(url("/api/catalog/batches/"+batch+"/export")).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        try(var book=new XSSFWorkbook(new ByteArrayInputStream(exported.body()))) {
            var row=book.getSheetAt(0).getRow(1);
            assertEquals("QUICK",row.getCell(10).getStringCellValue());assertEquals("0",row.getCell(11).getStringCellValue());
            assertEquals("2",row.getCell(12).getStringCellValue());assertEquals("PENDING",row.getCell(13).getStringCellValue());
        }
    }
    @Test void excelUploadPersistsQuickMode() throws Exception {
        byte[] file;
        try(var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
            var sheet=book.createSheet("Solicitações");sheet.createRow(0).createCell(0).setCellValue("Descrição do Item");
            sheet.createRow(1).createCell(0).setCellValue("Roçagem");book.write(out);file=out.toByteArray();
        }
        var out=new ByteArrayOutputStream();String boundary="QuickBatchBoundary";
        out.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"mode\"\r\n\r\nQUICK\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"rapida.xlsx\"\r\nContent-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(file);out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        var response=client.send(HttpRequest.newBuilder(url("/api/catalog/batches")).header("Content-Type","multipart/form-data; boundary="+boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(201,response.statusCode(),response.body());String id=json.readTree(response.body()).path("id").asString();
        var batches=client.send(HttpRequest.newBuilder(url("/api/catalog/batches")).GET().build(),HttpResponse.BodyHandlers.ofString());
        boolean found=false;for(var batch:json.readTree(batches.body())) if(batch.path("id").asString().equals(id)) {
            assertEquals("QUICK",batch.path("creation_mode").asString());found=true;
        }
        assertTrue(found);
    }
}
