package com.example.demo.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:cleanups;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class CatalogCleanupTests {
    @Autowired CatalogStore store;
    @Autowired AbbreviationStore abbreviations;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @MockitoBean PythonAiClient python;
    @TempDir Path directory;
    CatalogCleanup cleanup;
    @BeforeEach void setup() {
        db.update("DELETE FROM review_item"); db.update("DELETE FROM review_batch");
        db.update("DELETE FROM catalog_service"); db.update("DELETE FROM catalog_cleanup_run");
        reset(python);
        cleanup=new CatalogCleanup(store,abbreviations,python,json,directory.toString());
    }
    Map<String,String> add(String code) {
        return store.create(Map.of("codigo_servico",code,"descricao_item","Serviço "+code),UUID.randomUUID().toString());
    }
    String id(Map<String,?> row) { return row.get("id").toString(); }
    @SuppressWarnings("unchecked") Map<String,String> current(Map<String,Object> row) { return (Map<String,String>)row.get("current"); }
    String seed(String id) { return ((Map<?,?>)((Map<?,?>)cleanup.next(id).get("automatic")).get("kept")).get("catalog_id").toString(); }
    void comparison(String candidate, double score, boolean exact) {
        when(python.compare(anyMap(),anyList(),any())).thenReturn(json.readTree("{\"results\":[{\"catalog_id\":\""+candidate+"\",\"similaridade_semantica\":"+score+",\"semantic_exact\":"+exact+",\"correspondencia_palavras\":true,\"similaridade\":1}]}"));
    }
    @Test void copyIsResumableAndOnlyVerifiedItemsAreComparedThenApplyRequiresConfirmation() {
        add("A");add("B");add("C");
        var before=store.snapshot(); var draft=cleanup.create(true); String run=id(draft);
        String first=seed(run); var second=current(cleanup.get(run));
        comparison(first,.99996,false);
        var analyzed=cleanup.next(run);
        verify(python).compare(argThat(item -> item.get("catalog_id").equals(second.get("catalog_id"))),
                argThat(candidates -> candidates.size()==1 && first.equals(candidates.getFirst().get("catalog_id"))),any());
        assertNull(analyzed.get("automatic")); assertEquals(true,analyzed.get("analysis_ready"));
        assertEquals(before,store.snapshot(),"A revisão não pode modificar o SQL");
        cleanup=new CatalogCleanup(store,abbreviations,python,json,directory.toString());
        assertEquals(true,cleanup.get(run).get("analysis_ready"),"Retoma a análise salva após reinício");
        String secondId=second.get("catalog_id");
        cleanup.decide(run,secondId,"KEEP_CURRENT",first);
        assertThrows(ResponseStatusException.class,()->cleanup.decide(run,secondId,"DISTINCT",null));
        comparison(secondId,.8,false);var third=cleanup.next(run);
        cleanup.decide(run,current(third).get("catalog_id"),"KEEP_VERIFIED",secondId);
        assertEquals(3,store.all().size());
        assertThrows(IllegalArgumentException.class,()->cleanup.apply(run,false));
        assertEquals(3,store.all().size());
        assertEquals("APPLIED",cleanup.apply(run,true).get("status"));
        assertEquals(1,store.all().size());assertEquals(secondId,store.all().getFirst().get("catalog_id"));
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM catalog_service",Integer.class),"Preserva os originais");
        assertEquals(secondId,store.get(first).get("catalog_id"));
        assertFalse(store.all().getFirst().get("cleaned_at").isBlank());
        var after=store.snapshot();cleanup.apply(run,true);assertEquals(after,store.snapshot(),"Aplicação idempotente");
    }
    @Test void onlyUnroundedSemanticExactAutomaticallyReusesVerified() {
        add("A");add("B");String run=id(cleanup.create(true));String first=seed(run);
        comparison(first,1,false);assertNull(cleanup.next(run).get("automatic"));
        // Nova cópia para testar um resultado quase perfeito: nem a barra nem o arredondamento aprovam.
        String near=id(cleanup.create(true));seed(near);comparison(first,.99996,true);
        assertNull(cleanup.next(near).get("automatic"));
        String exact=id(cleanup.create(true));seed(exact);comparison(first,1,true);
        assertNotNull(cleanup.next(exact).get("automatic"));assertEquals(0,cleanup.get(exact).get("pending"));
        assertEquals(2,store.all().size()); // Ainda não aplicado.
    }
    @Test void priorVerifiedAreOptionalAndNewDataOnlyComparesToThem() {
        add("A");String initial=id(cleanup.create(true));seed(initial);cleanup.apply(initial,true);
        var newer=add("B");var skip=cleanup.create(true);
        assertEquals(1,skip.get("pending"));assertEquals(newer.get("catalog_id"),current(skip).get("catalog_id"));
        var recheck=cleanup.create(false);assertEquals(2,recheck.get("pending"));assertEquals(0L,recheck.get("verified"));
    }
    @Test void changedBasePreventsApplicationAndDoesNotLoseConcurrentNewService() {
        add("A");String run=id(cleanup.create(true));seed(run);add("B");
        var error=assertThrows(ResponseStatusException.class,()->cleanup.apply(run,true));
        assertEquals(409,error.getStatusCode().value());assertEquals(2,store.all().size());
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM catalog_service WHERE cleaned_at IS NOT NULL",Integer.class));
    }
    @Test void incompleteOrForeignCandidatesCannotBeAppliedOrChosen() {
        add("A");add("B");String run=id(cleanup.create(true));String first=seed(run);
        assertThrows(ResponseStatusException.class,()->cleanup.apply(run,true));
        comparison(first,.5,false);var analyzed=cleanup.next(run);
        assertThrows(IllegalArgumentException.class,()->cleanup.decide(run,current(analyzed).get("catalog_id"),"KEEP_VERIFIED","foreign"));
        assertEquals(1,cleanup.get(run).get("pending"));
    }
    @Test void priorSubstitutionAndBatchLinksFollowTheNewRepresentative() {
        var a=add("A");var b=add("B");String aId=a.get("catalog_id"),bId=b.get("catalog_id");
        var snapshot=store.snapshot();store.applyCleanup(UUID.randomUUID().toString(),snapshot.revision(),abbreviations.snapshot().revision(),Map.of(aId,bId,bId,bId));
        var batch=store.createBatch("teste",new LeitorDePlanilha.ParsedRequests("Pedidos",List.of(new LeitorDePlanilha.RequestRow(1,Map.of("descricao_item","B")))));
        var line=store.batchItems(batch,0,1).getFirst();store.resolve(batch,line.get("id").toString(),"EXISTING",bId);
        var c=add("C");String run=id(cleanup.create(true));comparison(bId,.7,false);var analyzed=cleanup.next(run);
        cleanup.decide(run,current(analyzed).get("catalog_id"),"KEEP_CURRENT",bId);cleanup.apply(run,true);
        assertEquals(c.get("catalog_id"),store.get(aId).get("catalog_id"));
        assertEquals(c.get("catalog_id"),store.batchItems(batch,0,1).getFirst().get("service_id"));
    }
    @Test void databaseReceiptRecoversInterruptedFileConfirmation() throws Exception {
        add("A");String run=id(cleanup.create(true));seed(run);
        var draft=json.readValue(Files.readString(directory.resolve(run+".json")),CatalogCleanup.Draft.class);
        store.applyCleanup(run,draft.sourceRevision(),draft.dictionary().revision(),draft.representatives());
        assertEquals("APPLIED",cleanup.get(run).get("status"));
        cleanup.apply(run,true);assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM catalog_cleanup_run",Integer.class));
    }
}
