package com.example.demo.service;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;
import java.util.*;

@Service
public class SimilarityAnalysis {
    private final PythonAiClient python;
    public SimilarityAnalysis(PythonAiClient python){this.python=python;}
    public String start(List<Map<String,String>> items,AbbreviationStore.Snapshot dictionary){
        var response=python.reviewStart(items,dictionary);
        String key=response==null?"":response.path("id").asString();
        if(key==null || !key.matches("[a-f0-9]{64}"))throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Identificador de análise inválido.");
        return key;
    }
    public JsonNode status(String key,List<Map<String,String>> items,AbbreviationStore.Snapshot dictionary,List<String> active,String selected){
        try{return python.reviewStatus(key,active,selected);}
        catch(HttpClientErrorException.NotFound e){start(items,dictionary);throw new ResponseStatusException(HttpStatus.CONFLICT,"O índice está sendo recuperado. Atualize em alguns instantes.");}
    }
    public List<Map<String,Object>> rows(List<Map<String,String>> items,JsonNode response){
        if(response==null || !response.path("rows").isArray())throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Análise incompleta.");
        var pairs=new HashMap<String,JsonNode>();response.path("rows").forEach(row->pairs.put(row.path("catalog_id").asString(),row.path("pair")));
        var result=new ArrayList<Map<String,Object>>();
        var byId=new HashMap<String,Map<String,String>>();items.forEach(item->byId.put(item.get("catalog_id"),item));
        for(var item:items){
            var row=new LinkedHashMap<String,Object>(item);var pair=pairs.get(item.get("catalog_id"));
            String candidate=pair==null || pair.isNull()?null:pair.path("catalog_id").asString();
            if(Objects.equals(candidate,item.get("catalog_id")) || !byId.containsKey(candidate))pair=null;
            row.put("contains",pair!=null && pair.path("correspondencia_palavras").asBoolean(false));
            row.put("similarity",pair==null || pair.isNull()?null:pair.path("similaridade_semantica").asDouble());
            row.put("pair_id",pair==null || pair.isNull()?null:pair.path("catalog_id").asString());result.add(row);
            var reference=pair==null || pair.isNull()?null:byId.get(pair.path("catalog_id").asString());
            if(reference!=null){row.put("pair_code",reference.get("codigo_servico"));row.put("pair_description",reference.get("descricao_item"));row.put("pair_supplier",reference.get("codigo_fornecedor"));}
        }
        return result;
    }
}
