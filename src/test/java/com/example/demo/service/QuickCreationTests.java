package com.example.demo.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:quick;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class QuickCreationTests {
    @Autowired CatalogStore store;
    @Autowired BulkSemanticReview review;
    @Autowired AbbreviationStore dictionary;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @MockitoBean PythonAiClient python;
    @TempDir Path directory;
    CatalogCleanup cleanup;
    String batch,line;
    @BeforeEach void setup() {
        db.update("DELETE FROM review_item");db.update("DELETE FROM review_batch");
        db.update("DELETE FROM catalog_cleanup_review");db.update("DELETE FROM catalog_cleanup_run");db.update("DELETE FROM catalog_service");
        reset(python);
        when(python.standardizeItems(anyList(),any())).thenAnswer(invocation->invocation.getArgument(0));
cleanup=new CatalogCleanup(store,dictionary,python,json,directory.toString());
    }
    Map<String,String> add(String text) { return store.create(Map.of("descricao_item",text),UUID.randomUUID().toString()); }
    void batch(String mode) {
        batch=store.createBatch("teste",new LeitorDePlanilha.ParsedRequests("Pedidos",List.of(
                new LeitorDePlanilha.RequestRow(1,Map.of("descricao_item","Pintura industrial")))),mode);
        line=store.batchItems(batch,0,1).getFirst().get("id").toString();
    }
    void compared(double score,boolean contains,boolean requiresReview) {
        var candidates=store.all();
        var rows=candidates.stream().limit(20).map(row -> Map.of("catalog_id",row.get("catalog_id"),
                "similaridade_semantica",score,"correspondencia_palavras",contains,"identical_record",false)).toList();
        when(python.compare(anyMap(),anyList(),any())).thenReturn(json.valueToTree(Map.of("results",rows,
                "quick_creation_check",Map.of("version","quick-creation-1","candidate_count",candidates.size(),"requires_review",requiresReview))));
    }
    @SuppressWarnings("unchecked") Map<String,String> service(Map<String,Object> result) { return (Map<String,String>)result.get("service"); }
    String run() { return cleanup.create(true).get("id").toString(); }
    @Test void ninetyExactlyIsAutomaticallyCreatedButAboveNinetyAndContainsRequireReview() {
        add("Motor");
        for (var condition:List.of(Map.of("score",.9,"contains",false,"review",false),
                Map.of("score",.900001,"contains",false,"review",true),Map.of("score",.1,"contains",true,"review",true))) {
            batch("QUICK");compared((double)condition.get("score"),(boolean)condition.get("contains"),(boolean)condition.get("review"));
            var result=review.match(batch,line);
            assertEquals(!(boolean)condition.get("review"),result.get("resolved"));
            if (Boolean.TRUE.equals(result.get("resolved"))) {
                assertEquals(true,result.get("created_quick"));assertEquals("2",service(result).get("required_cleanup_passes"));
                assertEquals("0",service(result).get("cleanup_passes"));assertEquals("QUICK",service(result).get("creation_kind"));
            }
        }
    }
    @Test void quickCreationWorksWithEmptyCatalogAndRetryDoesNotDuplicateOrUpgrade() {
        batch("QUICK");compared(0,false,false);var first=review.match(batch,line);
        assertEquals(1,store.all().size());assertEquals(service(first),service(review.match(batch,line)));
        assertEquals("PENDING",service(first).get("verification_status"));
        assertEquals("QUICK",store.batches().getFirst().get("creation_mode"));
    }
    @Test void wholeCatalogSummaryBlocksCreationEvenIfDisplayedTopTwentyHaveNoMatch() {
        add("Motor");batch("QUICK");compared(.2,false,true);
        assertEquals(false,review.match(batch,line).get("resolved"));assertEquals(1,store.all().size());
    }
    @Test void missingOrInconsistentPythonAnalysisFailsClosed() {
        add("Motor");batch("QUICK");
        when(python.compare(anyMap(),anyList(),any())).thenReturn(json.readTree("{\"results\":[]}"));
        assertThrows(ResponseStatusException.class,()->review.match(batch,line));
        compared(.95,false,false);assertThrows(ResponseStatusException.class,()->review.match(batch,line));
        when(python.compare(anyMap(),anyList(),any())).thenThrow(new IllegalStateException("offline"));
        assertThrows(IllegalStateException.class,()->review.match(batch,line));
        assertEquals("PENDING",store.batchItem(batch,line).get("status"));assertEquals(1,store.all().size());
    }
    @Test void normalModeStaysManualAndManualDecisionInQuickModeGetsNormalVerification() {
        add("Motor");batch("NORMAL");compared(.1,false,false);
        assertEquals(false,review.match(batch,line).get("resolved"));
        batch("QUICK");compared(.95,false,true);assertEquals(false,review.match(batch,line).get("resolved"));
        var manual=store.resolve(batch,line,"CREATE",null);
        assertEquals("STANDARD",manual.get("creation_kind"));assertEquals("1",manual.get("required_cleanup_passes"));
    }
    @Test void identicalRecordIsReusedWithoutChangingItsVerification() {
        var existing=add("Pintura industrial");batch("QUICK");
        when(python.compare(anyMap(),anyList(),any())).thenReturn(json.valueToTree(Map.of("results",List.of(
                Map.of("catalog_id",existing.get("catalog_id"),"identical_record",true)))));
        assertEquals(existing,service(review.match(batch,line)));assertEquals(1,store.all().size());
    }
    @Test void catalogRevisionChangePreventsAutomaticCreation() {
        batch("QUICK");compared(0,false,false);
        var response=python.compare(Map.of(),List.of(),dictionary.snapshot());
        when(python.compare(anyMap(),anyList(),any())).thenAnswer(invocation->{add("Concorrente");return response;});
        assertEquals(409,assertThrows(ResponseStatusException.class,()->review.match(batch,line)).getStatusCode().value());
        assertEquals("PENDING",store.batchItem(batch,line).get("status"));assertEquals(1,store.all().size());
    }
    @Test void twoDifferentAppliedCleanupsAreRequiredAndDraftOrRetryNeverAddsAPass() {
        batch("QUICK");compared(0,false,false);String id=service(review.match(batch,line)).get("catalog_id");
        String first=run();cleanup.next(first);
        assertEquals("0",store.get(id).get("cleanup_passes"));
        assertThrows(IllegalArgumentException.class,()->cleanup.apply(first,false));
        cleanup.apply(first,true);var partial=store.get(id);
        assertEquals("1",partial.get("cleanup_passes"));assertEquals("PARTIAL",partial.get("verification_status"));assertTrue(partial.get("cleaned_at").isBlank());
        cleanup.apply(first,true);assertEquals("1",store.get(id).get("cleanup_passes"));
        String second=run();assertEquals(1,cleanup.get(second).get("pending"));cleanup.next(second);cleanup.apply(second,true);
        assertEquals("2",store.get(id).get("cleanup_passes"));assertEquals("VERIFIED",store.get(id).get("verification_status"));
        assertFalse(store.get(id).get("cleaned_at").isBlank());assertEquals(0,cleanup.create(true).get("pending"));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM catalog_cleanup_review WHERE service_id=?",Integer.class,id));
    }
    @Test void skippedVerifiedItemsDoNotReceiveAnotherReviewReceipt() {
        var old=add("Antigo");String first=run();cleanup.next(first);cleanup.apply(first,true);
        var newer=add("Novo");String second=run();
        when(python.compare(anyMap(),anyList(),any())).thenReturn(json.valueToTree(Map.of("results",List.of())));
        cleanup.next(second);cleanup.decide(second,newer.get("catalog_id"),"DISTINCT",null);cleanup.apply(second,true);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM catalog_cleanup_review WHERE service_id=?",Integer.class,old.get("catalog_id")));
        assertEquals(first,store.get(old.get("catalog_id")).get("cleanup_id"));
    }

    @Test void bothQuickAndManualBulkCreationPersistCanonicalDescriptionButKeepUploadOriginal() {
        when(python.standardizeItems(anyList(),any())).thenAnswer(invocation->{
            List<Map<String,String>> items=invocation.getArgument(0);
            return items.stream().map(item->{var row=new LinkedHashMap<>(item);row.put("descricao_item","SV pintor HE");return (Map<String,String>)row;}).toList();
        });
        for(String mode:List.of("NORMAL","QUICK")) {
            batch=store.createBatch("teste",new LeitorDePlanilha.ParsedRequests("Pedidos",List.of(
                    new LeitorDePlanilha.RequestRow(1,Map.of("descricao_item","sv-pintor  hora extra")))),mode);
            line=store.batchItems(batch,0,1).getFirst().get("id").toString();
            compared(0,false,false);
            var created="QUICK".equals(mode)?service(review.match(batch,line)):review.create(batch,line);
            assertEquals("SV pintor HE",created.get("descricao_item"));
            assertEquals("SV pintor HE",store.get(created.get("catalog_id")).get("descricao_item"));
            assertEquals("0",created.get("cleanup_passes"));
            @SuppressWarnings("unchecked")var original=(Map<String,String>)store.batchItem(batch,line).get("fields");
            assertEquals("sv-pintor  hora extra",original.get("descricao_item"));
        }
    }
    @Test void manualBulkCreationRejectsDictionaryChangedDuringStandardization() {
        batch("NORMAL");
        when(python.standardizeItems(anyList(),any())).thenAnswer(invocation->{
            db.update("UPDATE abbreviation_state SET revision=revision+1 WHERE id=1");
            return invocation.getArgument(0);
        });
        assertEquals(409,assertThrows(ResponseStatusException.class,()->review.create(batch,line)).getStatusCode().value());
        assertEquals("PENDING",store.batchItem(batch,line).get("status"));assertTrue(store.all().isEmpty());
    }

}
