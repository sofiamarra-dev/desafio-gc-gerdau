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

    public JsonNode compare(Map<String, String> item, List<Map<String, String>> candidates, AbbreviationStore.Snapshot dictionary) {
        return rest.postForObject(baseUrl + "/compare", new HttpEntity<>(asJson(Map.of(
                "item", item, "candidates", candidates, "dictionary", Map.of("version",dictionary.revision(),"entries",dictionary.entries()))), jsonHeaders()), JsonNode.class);
    }

    public JsonNode catalog() {
        return rest.getForObject(baseUrl + "/catalog", JsonNode.class);
    }

    public JsonNode identicalRecords(List<Map<String,String>> items,AbbreviationStore.Snapshot dictionary) {
        return rest.postForObject(baseUrl+"/identical-records",new HttpEntity<>(asJson(Map.of("items",items,
                "dictionary",Map.of("version",dictionary.revision(),"entries",dictionary.entries()))),jsonHeaders()),JsonNode.class);
    }

    public List<Map<String,String>> standardizeItems(List<Map<String,String>> items, AbbreviationStore.Snapshot dictionary) {
        if(items.isEmpty()) return List.of();
        var response=rest.postForObject(baseUrl+"/dictionary/standardize",new HttpEntity<>(asJson(Map.of(
                "descriptions",items.stream().map(item->item.get("descricao_item")).toList(),
                "dictionary",Map.of("version",dictionary.revision(),"entries",dictionary.entries()))),jsonHeaders()),JsonNode.class);
        if(response==null || !dictionary.revision().equals(response.path("dictionary_version").asString())
                || !response.path("descriptions").isArray() || response.path("descriptions").size()!=items.size())
            throw new IllegalStateException("O Python não retornou a padronização completa. Nenhum dado foi salvo.");
        var result=new java.util.ArrayList<Map<String,String>>();
        for(int position=0;position<items.size();position++) {
            var description=response.path("descriptions").get(position);
            if(!description.isString() || description.asString().isBlank() || description.asString().length()>300)
                throw new IllegalStateException("Descrição padronizada inválida. Nenhum dado foi salvo.");
            var row=new java.util.LinkedHashMap<>(items.get(position));
            row.put("descricao_item",description.asString());result.add(row);
        }
        return result;
    }

    public JsonNode reviewStart(List<Map<String,String>> items, AbbreviationStore.Snapshot dictionary) {
        return rest.postForObject(baseUrl+"/review-index",new HttpEntity<>(asJson(Map.of("items",items,
                "dictionary",Map.of("version",dictionary.revision(),"entries",dictionary.entries()))),jsonHeaders()),JsonNode.class);
    }

    public JsonNode reviewStatus(String key, List<String> active, String selected) {
        var data=new java.util.LinkedHashMap<String,Object>();
        if(active!=null) data.put("active_ids",active);
        if(selected!=null) data.put("selected",selected);
        return rest.postForObject(baseUrl+"/review-index/"+key,new HttpEntity<>(asJson(data),jsonHeaders()),JsonNode.class);
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
