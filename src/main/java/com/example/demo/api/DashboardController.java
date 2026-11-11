package com.example.demo.api;

import com.example.demo.service.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.*;

@RestController
@RequestMapping("/api/catalog/dashboard")
public class DashboardController {

    private record Analysis(CatalogStore.Snapshot source,AbbreviationStore.Snapshot dictionary){}

    private final CatalogStore store;
    private final AbbreviationStore dictionary;
    private final SimilarityAnalysis similarity;
    private final Map<String,Analysis> analyses=new LinkedHashMap<>();

    public DashboardController(CatalogStore store,AbbreviationStore dictionary,SimilarityAnalysis similarity){
        this.store=store;
        this.dictionary=dictionary;
        this.similarity=similarity;
    }

    @PostMapping("/analysis") public synchronized Map<String,String> start(){
        var source=store.snapshot();var dict=dictionary.snapshot();
        String key=similarity.start(source.items(),dict);
        if(analyses.size()>=2 && !analyses.containsKey(key))
            analyses.remove(analyses.keySet().iterator().next());

        analyses.put(key,new Analysis(source,dict));return Map.of("id",key);
    }
    @GetMapping public synchronized Map<String,Object> page(@RequestParam Map<String,String> params){
        String key=params.get("analysis");

        if(key==null || key.isBlank())
            return store.page(params);

        var analysis=analyses.get(key);

        if(analysis==null)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Solicite a análise novamente.");

        if(!store.currentRevision().equals(analysis.source().revision()) || !dictionary.snapshot().revision().equals(analysis.dictionary().revision()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"O catálogo ou dicionário mudou. Atualize a análise.");

        var status=similarity.status(key,analysis.source().items(),analysis.dictionary(),null,null);

        if(!"READY".equals(status.path("status").asString()))
            return Map.of("analysis_status",status);

        var result=CatalogPages.page(similarity.rows(analysis.source().items(),status),params);
        result.put("analysis_status",Map.of("status","READY"));
        return result;
    }
    @GetMapping("/{key}/items/{selected}") public synchronized Map<String,Object> details(@PathVariable String key,@PathVariable String selected){
        var a=analyses.get(key);
        if(a==null || !store.currentRevision().equals(a.source().revision()) || !dictionary.snapshot().revision().equals(a.dictionary().revision()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"A análise mudou. Volte ao dashboard e gere outra análise.");

        var state=similarity.status(key,a.source().items(),a.dictionary(),null,null);

        if(!"READY".equals(state.path("status").asString()))
            return Map.of("analysis_status",state);

        var byId=new HashMap<String,Map<String,String>>();a.source().items().forEach(row->byId.put(row.get("catalog_id"),row));

        if(!byId.containsKey(selected))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Código não encontrado nesta análise.");

        var compared=similarity.status(key,a.source().items(),a.dictionary(),null,selected);
        var rows=new ArrayList<Map<String,Object>>();

        for(var pair:compared.path("results")){
            var row=new LinkedHashMap<String,Object>(byId.get(pair.path("catalog_id").asString()));
            row.put("contains",pair.path("correspondencia_palavras").asBoolean());
            row.put("similarity",pair.path("similaridade_semantica").asDouble());
            rows.add(row);
        }

        return Map.of("selected",byId.get(selected),"results",rows);
    }
}
