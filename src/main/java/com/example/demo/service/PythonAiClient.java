package com.example.demo.service;

import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Service
public class PythonAiClient {
    private static final MediaType JSON_UTF8 = new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8);
    private final String baseUrl;
    private final RestTemplate rest;
    private final ObjectMapper objectMapper;

    private record IndexRequest(List<Map<String, String>> items) {}
    private record SearchRequest(String query, int limit) {}
    private record SyncRequest(List<Map<String, String>> items, String revision) {}

    public PythonAiClient(@Value("${python.ai.url}") String url, ObjectMapper objectMapper) {
        baseUrl = url.replaceAll("/+$", "");
        rest = new RestTemplate();
        this.objectMapper = objectMapper;
    }

    public JsonNode index(List<Map<String, String>> items) {
        return rest.postForObject(baseUrl + "/index",
                new HttpEntity<>(asJson(new IndexRequest(items)), jsonHeaders()), JsonNode.class);
    }

    public JsonNode search(String query) {
        return rest.postForObject(baseUrl + "/search",
                new HttpEntity<>(asJson(new SearchRequest(query, 20)), jsonHeaders()), JsonNode.class);
    }

    public JsonNode sync(List<Map<String, String>> items, String revision) {
        return rest.postForObject(baseUrl + "/sync",
                new HttpEntity<>(asJson(new SyncRequest(items, revision)), jsonHeaders()), JsonNode.class);
    }

    public JsonNode catalog() {
        return rest.getForObject(baseUrl + "/catalog", JsonNode.class);
    }

    public JsonNode status() {
        return rest.getForObject(baseUrl + "/status", JsonNode.class);
    }

    public JsonNode dictionary(AbbreviationStore.Snapshot snapshot) {
        return rest.exchange(baseUrl + "/dictionary", org.springframework.http.HttpMethod.PUT,
                new HttpEntity<>(asJson(Map.of("version", snapshot.revision(), "entries", snapshot.entries())), jsonHeaders()),
                JsonNode.class).getBody();
    }

    public JsonNode previewDictionary(AbbreviationStore.Snapshot snapshot, String text) {
        return rest.postForObject(baseUrl + "/dictionary/preview", new HttpEntity<>(asJson(
                Map.of("version", snapshot.revision(), "entries", snapshot.entries(), "text", text)), jsonHeaders()), JsonNode.class);
    }

    private String asJson(Object request) {
        try {
            return objectMapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new IllegalStateException("Não foi possível preparar a requisição para o serviço Python.", e);
        }
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(JSON_UTF8);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        return headers;
    }


}
