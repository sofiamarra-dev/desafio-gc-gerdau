package com.example.demo.api;
import com.example.demo.service.PythonAiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
 properties="spring.datasource.url=jdbc:h2:mem:dictionaryhttp;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class AbbreviationHttpTests {
 @LocalServerPort int port;
 @Autowired ObjectMapper json;
 @MockitoBean PythonAiClient python;
 final HttpClient client=HttpClient.newHttpClient();
 HttpResponse<String> call(String method,String path,String body) throws Exception {
  return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
   .header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
 }
 @Test void editConflictPreviewAndStaticPage() throws Exception {
  assertEquals(7,json.readTree(call("GET","/api/abbreviations","").body()).path("entries").size());
  var created=call("POST","/api/abbreviations","{\"code\":\"TESTE\",\"meaning\":\"qualificador de teste\",\"variants\":[\"variante de teste\"],\"active\":true}");
  assertEquals(201,created.statusCode(),created.body());
  assertTrue(json.readTree(created.body()).path("index_pending").asBoolean());
  var edit=call("PUT","/api/abbreviations/TESTE","{\"variants\":[\"segunda variante\"],\"active\":false,\"version\":1}");
  assertEquals(200,edit.statusCode(),edit.body());
  assertEquals(409,call("PUT","/api/abbreviations/TESTE","{\"variants\":[],\"active\":true,\"version\":1}").statusCode());
  assertEquals(400,call("PUT","/api/abbreviations/TESTE","{\"meaning\":\"outro\",\"variants\":[],\"active\":true,\"version\":2}").statusCode());
  assertEquals(409,call("POST","/api/abbreviations","{\"code\":\"OUTRO\",\"meaning\":\"outro teste\",\"variants\":[\"SEGUNDA VARIANTE\"]}").statusCode());
  when(python.previewDictionary(any(),eq("pintor HE"))).thenReturn(json.readTree("{\"activity\":\"pintor\",\"qualifiers\":[\"HE\"]}"));
  assertEquals("pintor",json.readTree(call("POST","/api/abbreviations/preview","{\"text\":\"pintor HE\"}").body()).path("activity").asString());
  var html=call("GET","/siglas.html","");
  assertEquals(200,html.statusCode());assertTrue(html.body().contains("Dicionário de siglas"));
  assertTrue(call("GET","/menu.html","").body().contains("siglas.html"));
 }
}
