package com.example.demo.service;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

@Service
public class RankedCleanup {
    public record Draft(String id,String sourceRevision,String createdAt,boolean skipVerified,
            List<Map<String,String>> items,AbbreviationStore.Snapshot dictionary,String index,
            Map<String,String> representatives,Set<String> reviewed,int version){}
    private final CatalogStore store;private final AbbreviationStore dictionary;private final SimilarityAnalysis similarity;
    private final ObjectMapper json;private final Path directory;
    private final Map<String,Draft> cache=new LinkedHashMap<>();
    public RankedCleanup(CatalogStore store,AbbreviationStore dictionary,SimilarityAnalysis similarity,ObjectMapper json,
            @Value("${cleanup.directory:./data/cleanups}")String directory){
        this.store=store;this.dictionary=dictionary;this.similarity=similarity;this.json=json;this.directory=Path.of(directory).resolve("ranked");
    }
    private Path path(String id){return directory.resolve(UUID.fromString(id)+".json");}
    private void save(Draft draft){
        try{Files.createDirectories(directory);Path temporary=Files.createTempFile(directory,"ranked-",".tmp");
            Files.writeString(temporary,json.writeValueAsString(draft));
            try{Files.move(temporary,path(draft.id()),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(temporary,path(draft.id()),StandardCopyOption.REPLACE_EXISTING);}
            if(cache.size()>=3)cache.remove(cache.keySet().iterator().next());cache.put(draft.id(),draft);
        }catch(IOException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Não foi possível salvar a cópia da limpeza.");}
    }
    private Draft read(String id){
        if(cache.containsKey(id))return cache.get(id);
        if(!Files.exists(path(id)))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Cópia não encontrada.");
        try{var draft=json.readValue(Files.readString(path(id)),Draft.class);if(cache.size()>=3)cache.remove(cache.keySet().iterator().next());cache.put(id,draft);return draft;}
        catch(IOException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Não foi possível recuperar a cópia.");}
    }
    private void requireOpen(Draft draft){
        if(store.cleanupApplied(draft.id()))throw new ResponseStatusException(HttpStatus.CONFLICT,"Esta cópia já foi aplicada.");
        if(!store.currentRevision().equals(draft.sourceRevision()) || !dictionary.snapshot().revision().equals(draft.dictionary().revision()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"A base ou o dicionário mudou. Inicie outra cópia; as decisões anteriores continuam salvas.");
    }
    private List<Map<String,String>> active(Draft d){return d.items().stream().filter(row->row.get("catalog_id").equals(d.representatives().get(row.get("catalog_id")))).toList();}
    private List<String> activeIds(Draft d){return active(d).stream().map(row->row.get("catalog_id")).toList();}
    private boolean eligible(Draft d,Map<String,String> item){return !d.skipVerified() || item.getOrDefault("cleaned_at","").isBlank();}
    private Map<String,Object> summary(Draft d){
        var result=new LinkedHashMap<String,Object>();result.put("id",d.id());result.put("version",d.version());result.put("created_at",d.createdAt());
        result.put("status",store.cleanupApplied(d.id())?"APPLIED":"DRAFT");result.put("total",d.items().size());result.put("active",active(d).size());
        result.put("reviewed",d.reviewed().size());result.put("removed",d.items().size()-active(d).size());
        result.put("pending",active(d).stream().filter(row->eligible(d,row) && !d.reviewed().contains(row.get("catalog_id"))).count());return result;
    }
    public synchronized Map<String,Object> create(boolean skip){
        var source=store.snapshot();if(source.items().isEmpty())throw new IllegalArgumentException("O catálogo está vazio.");
        var dict=dictionary.snapshot();String index=similarity.start(source.items(),dict);
        var representatives=new LinkedHashMap<String,String>();source.items().forEach(row->representatives.put(row.get("catalog_id"),row.get("catalog_id")));
        var draft=new Draft(UUID.randomUUID().toString(),source.revision(),Instant.now().toString(),skip,source.items(),dict,index,representatives,new LinkedHashSet<>(),0);
        save(draft);return summary(draft);
    }
    public synchronized List<Map<String,Object>> list(){
        if(!Files.exists(directory))return List.of();
        try(var files=Files.list(directory)){return files.filter(file->file.getFileName().toString().matches("[a-f0-9-]{36}\\.json"))
            .map(file->summary(read(file.getFileName().toString().replace(".json",""))))
            .sorted(Comparator.comparing(row->row.get("created_at").toString(),Comparator.reverseOrder())).toList();}
        catch(IOException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Não foi possível listar as cópias.");}
    }
    public synchronized Map<String,Object> page(String id,Map<String,String> params){
        var draft=read(id);var result=summary(draft);
        if(store.cleanupApplied(id)){result.put("items",List.of());return result;}
        requireOpen(draft);
        var status=similarity.status(draft.index(),draft.items(),draft.dictionary(),activeIds(draft),null);
        result.put("analysis_status",status);
        if(!"READY".equals(status.path("status").asString())){result.put("items",List.of());return result;}
        var rows=similarity.rows(active(draft),status);
        rows.forEach(row->row.put("reviewed",draft.reviewed().contains(row.get("catalog_id")) || draft.skipVerified() && !Objects.toString(row.get("cleaned_at"),"").isBlank()));
        var page=CatalogPages.page(rows,params);result.put("catalog_total",page.remove("total"));result.putAll(page);
        // Não devolve o índice completo ao navegador: somente a página solicitada.
        result.put("analysis_status",Map.of("status","READY"));return result;
    }
    public synchronized Map<String,Object> details(String id,String selected){
        var d=read(id);requireOpen(d);var rows=active(d);var byId=new HashMap<String,Map<String,String>>();rows.forEach(row->byId.put(row.get("catalog_id"),row));
        if(!byId.containsKey(selected))throw new ResponseStatusException(HttpStatus.CONFLICT,"Este código já foi retirado da cópia.");
        var compared=similarity.status(d.index(),d.items(),d.dictionary(),activeIds(d),selected);
        if(compared==null || !compared.path("results").isArray())throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Comparação indisponível.");
        var candidates=new ArrayList<Map<String,Object>>();
        for(var pair:compared.path("results")){
            String candidate=pair.path("catalog_id").asString();
            if(!byId.containsKey(candidate) || selected.equals(candidate))throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Comparação inválida.");
            var row=new LinkedHashMap<String,Object>(byId.get(candidate));row.put("contains",pair.path("correspondencia_palavras").asBoolean());row.put("similarity",pair.path("similaridade_semantica").asDouble());candidates.add(row);
        }
        return Map.of("id",id,"version",d.version(),"selected",byId.get(selected),"results",candidates);
    }
    public synchronized Map<String,Object> decide(String id,String selected,String candidate,String action,int expectedVersion){
        var d=read(id);requireOpen(d);if(d.version()!=expectedVersion)throw new ResponseStatusException(HttpStatus.CONFLICT,"A revisão mudou. Atualize a comparação.");
        if(!Set.of("KEEP","KEEP_SELECTED","KEEP_SIMILAR").contains(Objects.toString(action,"")))throw new IllegalArgumentException("Decisão inválida.");
        var detail=details(id,selected);
        if(!"KEEP".equals(action)){
            @SuppressWarnings("unchecked")var results=(List<Map<String,Object>>)detail.get("results");
            if(results.stream().noneMatch(row->Objects.equals(candidate,row.get("catalog_id"))))throw new IllegalArgumentException("Selecione um dos 20 semelhantes apresentados.");
        }
        var reps=new LinkedHashMap<>(d.representatives());var reviewed=new LinkedHashSet<>(d.reviewed());reviewed.add(selected);
        if(!"KEEP".equals(action)){
            String removed="KEEP_SELECTED".equals(action)?candidate:selected;
            String kept="KEEP_SELECTED".equals(action)?selected:candidate;
            reps.replaceAll((key,value)->value.equals(removed)?kept:value);reviewed.add(candidate);
        }
        var next=new Draft(d.id(),d.sourceRevision(),d.createdAt(),d.skipVerified(),d.items(),d.dictionary(),d.index(),reps,reviewed,d.version()+1);
        save(next);return summary(next);
    }
    public synchronized Map<String,Object> apply(String id,boolean confirmed){
        var d=read(id);if(!confirmed)throw new IllegalArgumentException("Confirme a aplicação das decisões na base permanente.");
        if(!store.cleanupApplied(id)){
            requireOpen(d);if(d.reviewed().isEmpty())throw new IllegalArgumentException("Revise pelo menos um código antes de aplicar.");
            var reviewed=new HashSet<String>();d.items().forEach(row->{if(d.reviewed().contains(row.get("catalog_id")) && eligible(d,row))reviewed.add(row.get("catalog_id"));});
            store.applyCleanup(d.id(),d.sourceRevision(),d.dictionary().revision(),d.representatives(),reviewed);
        }
        return summary(d);
    }
}
