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

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:ranked;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class RankedCleanupTests {
    @Autowired CatalogStore store;@Autowired AbbreviationStore dictionary;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;
    @MockitoBean PythonAiClient python;@TempDir Path directory;RankedCleanup cleanup;
    String selected,candidate;final String key="a".repeat(64);
    @BeforeEach void setup(){
        db.update("DELETE FROM review_item");db.update("DELETE FROM review_batch");db.update("DELETE FROM catalog_cleanup_review");db.update("DELETE FROM catalog_cleanup_run");db.update("DELETE FROM catalog_service");
        reset(python);cleanup=new RankedCleanup(store,dictionary,new SimilarityAnalysis(python),json,directory.toString());
        selected=store.create(Map.of("descricao_item","Pintor"),UUID.randomUUID().toString()).get("catalog_id");
        candidate=store.create(Map.of("descricao_item","Pintor industrial"),UUID.randomUUID().toString()).get("catalog_id");
        when(python.reviewStart(anyList(),any())).thenReturn(json.valueToTree(Map.of("id",key,"status","RUNNING")));
        when(python.reviewStatus(eq(key),anyList(),isNull())).thenReturn(json.valueToTree(Map.of("status","READY","rows",List.of(
                Map.of("catalog_id",selected,"pair",Map.of("catalog_id",candidate,"similaridade_semantica",.2,"correspondencia_palavras",true)),
                Map.of("catalog_id",candidate,"pair",Map.of("catalog_id",selected,"similaridade_semantica",.99,"correspondencia_palavras",false))))));
        when(python.reviewStatus(eq(key),anyList(),eq(selected))).thenReturn(json.valueToTree(Map.of("results",List.of(Map.of("catalog_id",candidate,"similaridade_semantica",.2,"correspondencia_palavras",true)))));
    }
    String create(){return cleanup.create(true).get("id").toString();}
    @Test void containsSortsBeforeHigherSemanticScoreAndDetailsUseOnlySurvivors(){
        String run=create();var page=cleanup.page(run,Map.of("size","1","sort","similarity"));
        assertEquals(2,page.get("pages"));assertEquals(2,page.get("catalog_total"));
        assertEquals(selected,((Map<?,?>)((List<?>)page.get("items")).getFirst()).get("catalog_id"));
        assertEquals(1,((List<?>)cleanup.details(run,selected).get("results")).size());
    }
    @Test void retireSimilarChangesOnlyCopyAndApplyPreservesOriginalAndIsIdempotent(){
        String run=create();cleanup.decide(run,selected,candidate,"KEEP_SELECTED",0);
        assertEquals(2,store.all().size());assertEquals(1,cleanup.page(run,Map.of()).get("removed"));
        assertThrows(IllegalArgumentException.class,()->cleanup.apply(run,false));
        cleanup=new RankedCleanup(store,dictionary,new SimilarityAnalysis(python),json,directory.toString());
        cleanup.apply(run,true);assertEquals(1,store.all().size());assertEquals(selected,store.get(candidate).get("catalog_id"));
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM catalog_service",Integer.class));
        var after=store.snapshot();cleanup.apply(run,true);assertEquals(after,store.snapshot());
    }
    @Test void keepSimilarReplacesInitialAndForeignOrStaleChoiceCannotChangeCopy(){
        String run=create();assertThrows(IllegalArgumentException.class,()->cleanup.decide(run,selected,"foreign","KEEP_SIMILAR",0));
        cleanup.decide(run,selected,candidate,"KEEP_SIMILAR",0);
        assertThrows(ResponseStatusException.class,()->cleanup.decide(run,selected,candidate,"KEEP_SELECTED",0));
        cleanup.apply(run,true);assertEquals(candidate,store.get(selected).get("catalog_id"));
    }
    @Test void keepWithoutMergeLeavesOthersPendingAndPartialApplyDoesNotVerifyUnreviewed(){
        String run=create();cleanup.decide(run,selected,null,"KEEP",0);cleanup.apply(run,true);
        assertEquals(2,store.all().size());assertEquals("1",store.get(selected).get("cleanup_passes"));assertEquals("0",store.get(candidate).get("cleanup_passes"));
    }
    @Test void sourceChangeBlocksApplyingOldDecisions(){
        String run=create();cleanup.decide(run,selected,null,"KEEP",0);store.create(Map.of("descricao_item","Novo"),UUID.randomUUID().toString());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->cleanup.apply(run,true)).getStatusCode().value());
        assertEquals("0",store.get(selected).get("cleanup_passes"));
    }
    @Test void dashboardPaginationUsesDatabaseFiltersAndRejectsSqlSortInjection(){
        var page=store.page(Map.of("size","1","page","2","sort","descricao_item"));assertEquals(2L,page.get("total"));assertEquals(2,page.get("pages"));assertEquals(1,((List<?>)page.get("items")).size());
        assertEquals(1L,store.page(Map.of("query","industrial")).get("total"));
        assertThrows(IllegalArgumentException.class,()->store.page(Map.of("sort","id; DELETE FROM catalog_service")));
    }
    @Test void quickRecordStillNeedsTwoDifferentAppliedCopies(){
        db.update("UPDATE catalog_service SET creation_kind='QUICK',required_cleanup_passes=2 WHERE id=?",selected);
        String first=create();cleanup.decide(first,selected,null,"KEEP",0);cleanup.apply(first,true);
        assertEquals("1",store.get(selected).get("cleanup_passes"));assertEquals("PARTIAL",store.get(selected).get("verification_status"));
        cleanup.apply(first,true);assertEquals("1",store.get(selected).get("cleanup_passes"));
        String second=create();cleanup.decide(second,selected,null,"KEEP",0);cleanup.apply(second,true);
        assertEquals("2",store.get(selected).get("cleanup_passes"));assertEquals("VERIFIED",store.get(selected).get("verification_status"));
    }
}
