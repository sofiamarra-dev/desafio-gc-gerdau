package com.example.demo.service;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.util.*;

/** Fila de alterações em memória, privada à sessão. SQL só muda em save(). */
@Service
public class CleanupSession {
    private static final String WORKSPACE=CleanupSession.class.getName()+".workspace";
    private static final String SEQUENCE=CleanupSession.class.getName()+".version";

    private static final class Workspace {
        String operation=UUID.randomUUID().toString();
        CatalogStore.Snapshot source;
        AbbreviationStore.Snapshot dictionary;
        List<Map<String,String>> indexedItems;
        String index;
        final Map<String,String> representatives=new LinkedHashMap<>();
        final Set<String> reviewed=new LinkedHashSet<>();
        final Map<String,String> descriptions=new LinkedHashMap<>();
        int automaticRemoved;
        int version;
        boolean acknowledged;
        Integer lastSaveVersion;
        Workspace(CatalogStore.Snapshot source,AbbreviationStore.Snapshot dictionary) {
            this.source=source;this.dictionary=dictionary;this.indexedItems=source.items();
            source.items().forEach(item->representatives.put(item.get("catalog_id"),item.get("catalog_id")));
        }
    }

    private final CatalogStore store;
    private final AbbreviationStore dictionary;
    private final SimilarityAnalysis similarity;
    private final PythonAiClient python;

    public CleanupSession(CatalogStore store,AbbreviationStore dictionary,SimilarityAnalysis similarity,PythonAiClient python) {
        this.store=store;this.dictionary=dictionary;this.similarity=similarity;this.python=python;
    }
    private ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
    private ResponseStatusException invalidComparison(){return new ResponseStatusException(HttpStatus.BAD_GATEWAY,"A análise de registros idênticos retornou dados inválidos. Nenhuma alteração foi salva.");}
    private int advance(HttpSession session) {
        int version=session.getAttribute(SEQUENCE) instanceof Integer previous?previous+1:0;
        session.setAttribute(SEQUENCE,version);return version;
    }
    private Workspace workspace(HttpSession session) {
        if(!(session.getAttribute(WORKSPACE) instanceof Workspace state)) throw conflict("Abra a limpeza para iniciar uma revisão nesta sessão.");
        return state;
    }
    private void requireCurrent(Workspace state) {
        if(!state.source.revision().equals(store.currentRevision()) || !state.dictionary.revision().equals(dictionary.snapshot().revision()))
            throw conflict("O catálogo ou o dicionário mudou. Suas alterações ainda não foram salvas. Descarte a revisão e abra a limpeza novamente para usar os dados atuais.");
    }
    private void requireAcknowledged(Workspace state) {
        if(!state.acknowledged) throw conflict("Confirme o aviso de registros idênticos antes de revisar os códigos.");
    }
    private boolean dirty(Workspace state) {
        return !state.descriptions.isEmpty() || !state.reviewed.isEmpty() || state.representatives.entrySet().stream().anyMatch(entry->!entry.getKey().equals(entry.getValue()));
    }
    private List<Map<String,String>> active(Workspace state) {
        return state.source.items().stream().filter(item->item.get("catalog_id").equals(state.representatives.get(item.get("catalog_id"))))
                .map(item->{
                    var result=new LinkedHashMap<>(item);
                    String text=state.descriptions.get(item.get("catalog_id"));
                    if(text!=null)result.put("descricao_item",text);
                    return (Map<String,String>)result;
                }).toList();
    }
    private Map<String,String> project(Workspace state,Map<String,String> item) {
        var result=new LinkedHashMap<>(item);
        if(state.reviewed.contains(item.get("catalog_id"))) {
            int required=Integer.parseInt(item.getOrDefault("required_cleanup_passes","1"));
            int passes=Math.min(required,Integer.parseInt(item.getOrDefault("cleanup_passes","0"))+1);
            result.put("cleanup_passes",Integer.toString(passes));
            result.put("verification_status",passes>=required?"VERIFIED":passes>0?"PARTIAL":"PENDING");
        }
        return result;
    }
    private Map<String,Object> summary(Workspace state) {
        var result=new LinkedHashMap<String,Object>();
        result.put("id",state.operation);result.put("version",state.version);result.put("dirty",dirty(state));
        result.put("standardized",state.descriptions.size());result.put("automatic_removed",state.automaticRemoved);result.put("acknowledged",state.acknowledged);
        result.put("total",state.source.items().size());result.put("active",active(state).size());
        result.put("reviewed",state.reviewed.size());result.put("removed",state.source.items().size()-active(state).size());
        result.put("pending",active(state).stream().filter(row->!state.reviewed.contains(row.get("catalog_id")) && !"VERIFIED".equals(row.get("verification_status"))).count());
        result.put("source_revision",state.source.revision());result.put("status","OPEN");return result;
    }
    public Map<String,Object> open(HttpSession session) {
        synchronized(session) {
            if(session.getAttribute(WORKSPACE) instanceof Workspace existing) {
                requireCurrent(existing);return summary(existing);
            }
            var source=store.snapshot();var dict=dictionary.snapshot();
            var state=new Workspace(source,dict);
            if(!source.items().isEmpty()) {
                state.indexedItems=python.standardizeItems(source.items(),dict);
                if(state.indexedItems==null || state.indexedItems.size()!=source.items().size()) throw invalidComparison();
                for(int position=0;position<source.items().size();position++) {
                    var original=source.items().get(position);var normalized=state.indexedItems.get(position);
                    if(!Objects.equals(original.get("catalog_id"),normalized.get("catalog_id"))) throw invalidComparison();
                    if(!Objects.equals(original.get("descricao_item"),normalized.get("descricao_item")))
                        state.descriptions.put(original.get("catalog_id"),normalized.get("descricao_item"));
                }
                var identity=python.identicalRecords(state.indexedItems,dict);
                if(identity==null || !identity.path("groups").isArray() || !identity.path("total_removed").isNumber()) throw invalidComparison();
                var grouped=new HashSet<String>();
                for(var group:identity.path("groups")) {
                    String kept=group.path("kept_id").asString();
                    if(!state.representatives.containsKey(kept) || !grouped.add(kept)
                            || !group.path("duplicate_ids").isArray() || group.path("duplicate_ids").isEmpty()) throw invalidComparison();
                    for(var duplicate:group.path("duplicate_ids")) {
                        String removed=duplicate.asString();
                        if(!state.representatives.containsKey(removed) || !grouped.add(removed)) throw invalidComparison();
                        state.representatives.put(removed,kept);state.automaticRemoved++;
                    }
                }
                if(identity.path("total_removed").asInt(-1)!=state.automaticRemoved) throw invalidComparison();
            }
            requireCurrent(state);
            state.version=advance(session);session.setAttribute(WORKSPACE,state);
            return summary(state);
        }
    }
    public Map<String,Object> status(HttpSession session) {
        synchronized(session) {
            return session.getAttribute(WORKSPACE) instanceof Workspace state?summary(state):Map.of("dirty",false,"version",-1,"acknowledged",false,"has_session",false);
        }
    }
    public Map<String,Object> acknowledge(HttpSession session,int version) {
        synchronized(session) {
            var state=workspace(session);requireCurrent(state);
            if(version!=state.version) throw conflict("A revisão mudou. Atualize o aviso.");
            if(!state.acknowledged){state.acknowledged=true;state.version=advance(session);}
            return summary(state);
        }
    }
    private JsonNode analysis(Workspace state,String selected) {
        if(state.index==null)state.index=similarity.start(state.indexedItems,state.dictionary);
        return similarity.status(state.index,state.indexedItems,state.dictionary,
                active(state).stream().map(row->row.get("catalog_id")).toList(),selected);
    }
    public Map<String,Object> page(HttpSession session,Map<String,String> params) {
        synchronized(session) {
            var state=workspace(session);requireCurrent(state);var result=summary(state);
            if(!state.acknowledged) {
                result.put("items",List.of());result.put("catalog_total",0);result.put("page",1);result.put("pages",1);return result;
            }
            var items=active(state);
            if(items.isEmpty()) {
                result.putAll(CatalogPages.page(List.of(),params));result.put("catalog_total",0);result.put("analysis_status",Map.of("status","READY"));return result;
            }
            var status=analysis(state,null);
            if(status==null || status.path("status").asString()==null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Análise de semelhantes indisponível.");
            if(!"READY".equals(status.path("status").asString())) {
                result.put("items",List.of());result.put("analysis_status",status);return result;
            }
            var rows=similarity.rows(items.stream().map(item->project(state,item)).toList(),status);
            rows.forEach(row->{
                row.put("reviewed",state.reviewed.contains(row.get("catalog_id")) || "VERIFIED".equals(row.get("verification_status")));
                row.put("verification_pending",state.reviewed.contains(row.get("catalog_id")));
            });
            var page=CatalogPages.page(rows,params);result.put("catalog_total",page.remove("total"));result.putAll(page);
            result.put("analysis_status",Map.of("status","READY"));return result;
        }
    }
    public Map<String,Object> details(HttpSession session,String selected) {
        synchronized(session) {
            var state=workspace(session);requireCurrent(state);requireAcknowledged(state);
            var byId=new LinkedHashMap<String,Map<String,String>>();active(state).forEach(item->byId.put(item.get("catalog_id"),item));
            if(!byId.containsKey(selected)) throw conflict("Este código já foi retirado da revisão.");
            var compared=analysis(state,selected);
            if(compared==null || !compared.path("results").isArray() || compared.path("results").size()>20)
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Comparação indisponível.");
            var seen=new HashSet<String>();var rows=new ArrayList<Map<String,Object>>();
            for(var pair:compared.path("results")) {
                String id=pair.path("catalog_id").asString();
                if(selected.equals(id) || !byId.containsKey(id) || !seen.add(id)) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"A comparação retornou um código inválido.");
                var row=new LinkedHashMap<String,Object>(project(state,byId.get(id)));
                row.put("verification_pending",state.reviewed.contains(id));row.put("contains",pair.path("correspondencia_palavras").asBoolean());
                row.put("similarity",pair.path("similaridade_semantica").asDouble());rows.add(row);
            }
            var result=summary(state);var current=new LinkedHashMap<String,Object>(project(state,byId.get(selected)));
            current.put("verification_pending",state.reviewed.contains(selected));result.put("selected",current);result.put("results",rows);return result;
        }
    }
    public Map<String,Object> decide(HttpSession session,String selected,String candidate,String action,int version) {
        synchronized(session) {
            var state=workspace(session);requireCurrent(state);requireAcknowledged(state);
            if(version!=state.version) throw conflict("A revisão mudou em outra aba. Atualize a comparação.");
            if(!Set.of("KEEP","KEEP_SELECTED","KEEP_SIMILAR").contains(Objects.toString(action,""))) throw new IllegalArgumentException("Decisão inválida.");
            if(selected==null || !selected.equals(state.representatives.get(selected))) throw conflict("Este código já foi retirado da revisão ou não foi selecionado.");
            if(!"KEEP".equals(action)) {
                @SuppressWarnings("unchecked")var rows=(List<Map<String,Object>>)details(session,selected).get("results");
                if(rows.stream().noneMatch(row->Objects.equals(candidate,row.get("catalog_id"))))throw new IllegalArgumentException("Selecione um dos semelhantes apresentados.");
                String kept="KEEP_SELECTED".equals(action)?selected:candidate;
                String removed="KEEP_SELECTED".equals(action)?candidate:selected;
                state.representatives.replaceAll((key,value)->removed.equals(value)?kept:value);
                state.reviewed.add(kept);
            } else state.reviewed.add(selected);
            state.version=advance(session);return summary(state);
        }
    }
    public Map<String,Object> save(HttpSession session,int version) {
        synchronized(session) {
            var state=workspace(session);
            if(!dirty(state) && Objects.equals(state.lastSaveVersion,version)) {
                var result=summary(state);result.put("saved",true);return result;
            }
            boolean committed=store.cleanupApplied(state.operation);
            if(!committed)requireCurrent(state);
            requireAcknowledged(state);
            if(version!=state.version) throw conflict("A revisão mudou. Atualize a página antes de salvar.");
            if(!dirty(state)){var result=summary(state);result.put("saved",true);return result;}
            if(!committed)store.applyCleanup(state.operation,state.source.revision(),state.dictionary.revision(),state.representatives,state.reviewed,state.descriptions);
            // Atualiza a sessão após o commit; permite continuar sem criar outra cópia.
            var current=store.snapshot();var dict=dictionary.snapshot();var next=new Workspace(current,dict);
            var indexedIds=new HashSet<String>();state.indexedItems.forEach(item->indexedIds.add(item.get("catalog_id")));
            if(dict.revision().equals(state.dictionary.revision()) && current.items().stream().allMatch(item->indexedIds.contains(item.get("catalog_id")))) {
                next.index=state.index;next.indexedItems=state.indexedItems;
            }
            next.acknowledged=true;next.version=advance(session);next.lastSaveVersion=version;session.setAttribute(WORKSPACE,next);
            var result=summary(next);result.put("saved",true);return result;
        }
    }
    public Map<String,Object> discard(HttpSession session) {
        synchronized(session){session.removeAttribute(WORKSPACE);return Map.of("discarded",true,"dirty",false,"version",-1);}
    }
}
