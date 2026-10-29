package com.example.demo.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** A revisão grava somente arquivos locais; SQL só muda na confirmação final. */
@Service
public class CatalogCleanup {
    public record Draft(String id, String status, String sourceRevision, String createdAt, boolean skipVerified,
                        AbbreviationStore.Snapshot dictionary, List<Map<String,String>> items,
                        Map<String,String> representatives, List<Map<String,Object>> decisions,
                        List<Map<String,Object>> results, String analyzedId) {}
    private final CatalogStore store;
    private final AbbreviationStore abbreviations;
    private final PythonAiClient python;
    private final ObjectMapper json;
    private final Path directory;

    public CatalogCleanup(CatalogStore store, AbbreviationStore abbreviations, PythonAiClient python, ObjectMapper json,
                          @Value("${cleanup.directory:./data/cleanups}") String directory) {
        this.store=store; this.abbreviations=abbreviations; this.python=python; this.json=json;
        this.directory=Path.of(directory).toAbsolutePath().normalize();
    }

    private Path path(String id) {
        try { return directory.resolve(UUID.fromString(id).toString()+".json"); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Identificação de limpeza inválida."); }
    }
    private ResponseStatusException storageError() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Não foi possível ler ou salvar a cópia local da limpeza. Verifique o diretório e tente novamente.");
    }
    private void save(Draft draft) {
        Path temporary=null;
        try {
            Files.createDirectories(directory);
            temporary=Files.createTempFile(directory,"cleanup-",".tmp");
            Files.writeString(temporary,json.writeValueAsString(draft),StandardCharsets.UTF_8);
            try { Files.move(temporary,path(draft.id()),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary,path(draft.id()),StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { throw storageError(); }
        finally { if (temporary!=null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {} }
    }
    private Draft read(String id) {
        Path file=path(id);
        if (!Files.exists(file)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Limpeza não encontrada.");
        try { return json.readValue(Files.readString(file,StandardCharsets.UTF_8),Draft.class); }
        catch (IOException e) { throw storageError(); }
    }
    private String id(Map<String,String> item) { return item.get("catalog_id"); }
    private Map<String,String> current(Draft draft) {
        return draft.items().stream().filter(item -> !draft.representatives().containsKey(id(item))).findFirst().orElse(null);
    }
    private boolean applied(Draft draft) { return "APPLIED".equals(draft.status()) || store.cleanupApplied(draft.id()); }
    private void requireOpen(Draft draft) {
        if (applied(draft)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Esta limpeza já foi aplicada.");
        if (!abbreviations.snapshot().revision().equals(draft.dictionary().revision()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"O dicionário mudou. Inicie uma nova cópia para revisar com as regras atuais.");
    }
    private Map<String,Object> view(Draft draft) {
        var result=new LinkedHashMap<String,Object>();
        result.put("id",draft.id()); result.put("status",applied(draft)?"APPLIED":"DRAFT");
        result.put("source_revision",draft.sourceRevision()); result.put("created_at",draft.createdAt());
        result.put("skip_verified",draft.skipVerified()); result.put("total",draft.items().size());
        result.put("pending",draft.items().size()-draft.representatives().size());
        long verified=draft.representatives().entrySet().stream().filter(e -> e.getKey().equals(e.getValue())).count();
        result.put("verified",verified); result.put("replaced",draft.representatives().size()-verified);
        var item=current(draft); result.put("current",item);
        boolean ready=item!=null && id(item).equals(draft.analyzedId());
        result.put("analysis_ready",ready); result.put("results",ready?draft.results():List.of());
        result.put("automatic",null);
        return result;
    }
    public synchronized List<Map<String,Object>> list() {
        if (!Files.exists(directory)) return List.of();
        try (var files=Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.reverseOrder()).map(p -> {
                        var result=view(read(p.getFileName().toString().replace(".json","")));
                        result.remove("results"); result.remove("current"); return result;
                    }).sorted(Comparator.comparing(row -> row.get("created_at").toString(),Comparator.reverseOrder())).toList();
        } catch (IOException e) { throw storageError(); }
    }
    public synchronized Map<String,Object> create(boolean skipVerified) {
        var snapshot=store.snapshot();
        if (snapshot.items().isEmpty()) throw new IllegalArgumentException("O catálogo está vazio. Adicione dados antes de iniciar a limpeza.");
        var representatives=new LinkedHashMap<String,String>();
        if (skipVerified) for (var item:snapshot.items()) {
            if (!item.getOrDefault("cleaned_at","").isBlank()) representatives.put(id(item),id(item));
        }
        var draft=new Draft(UUID.randomUUID().toString(),"DRAFT",snapshot.revision(),Instant.now().toString(),skipVerified,
                abbreviations.snapshot(),snapshot.items(),representatives,new ArrayList<>(),List.of(),null);
        save(draft); return view(draft);
    }
    public synchronized Map<String,Object> get(String id) { return view(read(id)); }

    private Draft resolved(Draft draft, Map<String,String> item, String kept, String action, boolean automatic) {
        var representatives=new LinkedHashMap<>(draft.representatives());
        String itemId=id(item);
        if ("KEEP_CURRENT".equals(action)) {
            String previous=kept; kept=itemId;
            representatives.replaceAll((key,value) -> value.equals(previous)?itemId:value);
        }
        representatives.put(itemId,kept);
        var decisions=new ArrayList<>(draft.decisions());
        decisions.add(Map.of("item_id",itemId,"kept_id",kept,"action",action,"automatic",automatic,"at",Instant.now().toString()));
        var updated=new Draft(draft.id(),draft.status(),draft.sourceRevision(),draft.createdAt(),draft.skipVerified(),
                draft.dictionary(),draft.items(),representatives,decisions,List.of(),null);
        save(updated); return updated;
    }
    public synchronized Map<String,Object> next(String id) {
        var draft=read(id); requireOpen(draft);
        var item=current(draft);
        if (item==null || id(item).equals(draft.analyzedId())) return view(draft);
        var verified=draft.items().stream().filter(row -> id(row).equals(draft.representatives().get(id(row)))).toList();
        if (verified.isEmpty()) {
            var updated=resolved(draft,item,id(item),"DISTINCT",true);
            var result=view(updated); result.put("automatic",Map.of("item",item,"kept",item)); return result;
        }
        var comparison=python.compare(item,verified,draft.dictionary());
        if (comparison==null || !comparison.path("results").isArray())
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"O Python não retornou uma comparação válida.");
        var candidates=new HashMap<String,Map<String,String>>(); verified.forEach(row -> candidates.put(id(row),row));
        var results=new ArrayList<Map<String,Object>>();
        for (var node:comparison.path("results")) {
            String candidate=node.path("catalog_id").asString();
            if (!candidates.containsKey(candidate)) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"A comparação retornou um registro fora dos verificados.");
            @SuppressWarnings("unchecked") var row=(Map<String,Object>)json.readValue(node.toString(),Map.class);
            results.add(row);
            double score=node.path("similaridade_semantica").asDouble(-1);
            if (node.path("semantic_exact").asBoolean(false) && Double.isFinite(score) && score>=1.0-1e-7 && score<=1.0) {
                var updated=resolved(draft,item,candidate,"KEEP_VERIFIED",true);
                var result=view(updated); result.put("automatic",Map.of("item",item,"kept",candidates.get(candidate))); return result;
            }
        }
        var updated=new Draft(draft.id(),draft.status(),draft.sourceRevision(),draft.createdAt(),draft.skipVerified(),
                draft.dictionary(),draft.items(),draft.representatives(),draft.decisions(),results,id(item));
        save(updated); return view(updated);
    }
    public synchronized Map<String,Object> decide(String id,String currentId,String action,String candidate) {
        var draft=read(id); requireOpen(draft); var item=current(draft);
        if (item==null || !id(item).equals(currentId) || !currentId.equals(draft.analyzedId()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Esta linha mudou ou ainda não foi comparada. Atualize a revisão.");
        if ("DISTINCT".equals(action)) return view(resolved(draft,item,id(item),action,false));
        if (!Set.of("KEEP_CURRENT","KEEP_VERIFIED").contains(Objects.toString(action,"")))
            throw new IllegalArgumentException("Selecione qual registro deseja manter.");
        if (!Objects.equals(candidate,draft.representatives().get(candidate)) || candidate==null || draft.results().stream()
                .noneMatch(row -> candidate.equals(row.get("catalog_id")))) throw new IllegalArgumentException("Escolha um dos registros verificados apresentados.");
        return view(resolved(draft,item,candidate,action,false));
    }
    public synchronized Map<String,Object> apply(String id,boolean confirmed) {
        if (!confirmed) throw new IllegalArgumentException("Confirme explicitamente o uso desta cópia como base permanente.");
        var draft=read(id);
        if (!applied(draft)) {
            requireOpen(draft);
            if (current(draft)!=null) throw new ResponseStatusException(HttpStatus.CONFLICT,"Conclua todas as linhas antes de aplicar a limpeza.");
            store.applyCleanup(draft.id(),draft.sourceRevision(),draft.dictionary().revision(),draft.representatives());
        }
        // O comprovante SQL permite retomar mesmo se o processo cair antes de salvar este arquivo.
        var updated=new Draft(draft.id(),"APPLIED",draft.sourceRevision(),draft.createdAt(),draft.skipVerified(),draft.dictionary(),
                draft.items(),draft.representatives(),draft.decisions(),draft.results(),draft.analyzedId());
        save(updated); return view(updated);
    }
}
