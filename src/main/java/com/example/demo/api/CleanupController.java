package com.example.demo.api;

import com.example.demo.service.CatalogCleanup;
import com.example.demo.service.CatalogSearch;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import java.util.*;

@RestController
@RequestMapping("/api/cleanups")
public class CleanupController {
    private final CatalogCleanup cleanup;
    private final CatalogSearch search;
    public CleanupController(CatalogCleanup cleanup,CatalogSearch search) { this.cleanup=cleanup; this.search=search; }
    public record Create(Boolean skip_verified) {}
    public record Decision(String current_id,String action,String candidate_id) {}
    public record Apply(boolean confirmed) {}
    @GetMapping public List<Map<String,Object>> list() { return cleanup.list(); }
    @PostMapping public Map<String,Object> create(@RequestBody Create request) { return cleanup.create(!Boolean.FALSE.equals(request.skip_verified())); }
    @GetMapping("/{id}") public Map<String,Object> get(@PathVariable String id) { return cleanup.get(id); }
    @PostMapping("/{id}/next") public Map<String,Object> next(@PathVariable String id) { return cleanup.next(id); }
    @PostMapping("/{id}/decision") public Map<String,Object> decide(@PathVariable String id,@RequestBody Decision request) {
        return cleanup.decide(id,request.current_id(),request.action(),request.candidate_id());
    }
    @PostMapping("/{id}/apply") public Map<String,Object> apply(@PathVariable String id,@RequestBody Apply request) {
        var result=cleanup.apply(id,request.confirmed());
        try { search.synchronizeAfterMigration(); result.put("index_pending",false); }
        catch (RestClientException e) {
            result.put("index_pending",true);
            result.put("warning","A limpeza foi aplicada. O índice de busca será atualizado na próxima pesquisa; verifique se o Python está ativo.");
        }
        return result;
    }
}
