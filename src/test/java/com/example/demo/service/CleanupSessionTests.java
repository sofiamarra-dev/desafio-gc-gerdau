package com.example.demo.service;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:cleanupsession;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class CleanupSessionTests {
    @Autowired CatalogStore store;
    @Autowired AbbreviationStore dictionary;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @MockitoBean PythonAiClient python;
    CleanupSession cleanup;
    MockHttpSession session;
    String selected,candidate;
    final String key="c".repeat(64);

    @BeforeEach void setup(){
        db.update("DELETE FROM review_item");db.update("DELETE FROM review_batch");
        db.update("DELETE FROM catalog_cleanup_review");db.update("DELETE FROM catalog_cleanup_run");db.update("DELETE FROM catalog_service");
        reset(python);session=new MockHttpSession();
        cleanup=new CleanupSession(store,dictionary,new SimilarityAnalysis(python),python);
        selected=add("PINTOR");candidate=add("MOTOR");
        when(python.standardizeItems(anyList(),any())).thenAnswer(invocation->invocation.getArgument(0));
        when(python.identicalRecords(anyList(),any())).thenReturn(json.valueToTree(Map.of("groups",List.of(),"total_removed",0)));
        when(python.reviewStart(anyList(),any())).thenReturn(json.valueToTree(Map.of("id",key,"status","READY")));
        when(python.reviewStatus(eq(key),anyList(),nullable(String.class))).thenAnswer(invocation->{
            @SuppressWarnings("unchecked") var ids=(List<String>)invocation.getArgument(1);
            String current=invocation.getArgument(2);
            if(current!=null){
                var rows=ids.stream().filter(id->!id.equals(current)).limit(20).map(id->Map.of("catalog_id",id,"similaridade_semantica",1.,"correspondencia_palavras",true)).toList();
                return json.valueToTree(Map.of("results",rows));
            }
            var rows=new ArrayList<Map<String,Object>>();
            for(String id:ids){
                Object pair=ids.stream().filter(other->!other.equals(id)).findFirst().<Object>map(other->Map.of("catalog_id",other,"similaridade_semantica",1.,"correspondencia_palavras",true)).orElse(null);
                var row=new LinkedHashMap<String,Object>();row.put("catalog_id",id);row.put("pair",pair);rows.add(row);
            }
            return json.valueToTree(Map.of("status","READY","rows",rows));
        });
    }
    String add(String description){return store.create(Map.of("descricao_item",description),UUID.randomUUID().toString()).get("catalog_id");}
    int version(Map<String,Object> state){return ((Number)state.get("version")).intValue();}
    int version(){return version(cleanup.status(session));}
    int count(Map<String,Object> state,String field){return ((Number)state.get(field)).intValue();}
    Map<String,Object> openAndAcknowledge(){
        var state=cleanup.open(session);
        if(!Boolean.TRUE.equals(state.get("acknowledged")))state=cleanup.acknowledge(session,version(state));
        return state;
    }
    @SuppressWarnings("unchecked") Map<String,Object> projected(String id){
        return ((List<Map<String,Object>>)cleanup.page(session,Map.of("size","100")).get("items")).stream().filter(row->id.equals(row.get("catalog_id"))).findFirst().orElseThrow();
    }
    @Test void openingIsIdempotentPerSessionAndDoesNotTreatSemanticHundredOrContainsAsIdentity(){
        var before=store.snapshot();var first=cleanup.open(session);var second=cleanup.open(session);
        assertEquals(first.get("id"),second.get("id"));assertEquals(0,count(first,"automatic_removed"));
        assertFalse((Boolean)first.get("dirty"));assertEquals(before,store.snapshot());
        verify(python,times(1)).identicalRecords(anyList(),any());
        cleanup.acknowledge(session,version(first));
        assertEquals(2,((List<?>)cleanup.page(session,Map.of()).get("items")).size());
        assertEquals(2,store.all().size(),"Semelhança 100% e contém não removem registros distintos");
    }
    @Test void duplicateRemovalIsStagedForAllGroupsAndOnlySaveChangesSql(){
        var duplicates=new ArrayList<String>();for(int i=0;i<25;i++)duplicates.add(add("PINTOR"));
        when(python.identicalRecords(anyList(),any())).thenReturn(json.valueToTree(Map.of("groups",List.of(Map.of("kept_id",selected,"duplicate_ids",duplicates)),"total_removed",25)));
        var before=store.snapshot();var state=cleanup.open(session);
        assertEquals(25,count(state,"automatic_removed"));assertEquals(2,count(state,"active"));assertTrue((Boolean)state.get("dirty"));assertFalse((Boolean)state.get("acknowledged"));
        assertEquals(before,store.snapshot());
        cleanup.acknowledge(session,version(state));
        var page=cleanup.page(session,Map.of("size","100","pending_only","false"));
        assertEquals(2,page.get("catalog_total"));
        @SuppressWarnings("unchecked")var visible=(List<Map<String,Object>>)page.get("items");
        assertTrue(visible.stream().noneMatch(row->duplicates.contains(row.get("catalog_id"))),"Duplicados devem sumir da lista antes de salvar");
        @SuppressWarnings("unchecked")var neighbors=(List<Map<String,Object>>)cleanup.details(session,selected).get("results");
        assertTrue(neighbors.stream().noneMatch(row->duplicates.contains(row.get("catalog_id"))),"Duplicados não podem aparecer nos 20 semelhantes");
        assertEquals(before,store.snapshot(),"Ocultar duplicados não pode alterar o banco antes de salvar");
        int expected=version();cleanup.save(session,expected);
        assertEquals(2,store.all().size());assertEquals(27,db.queryForObject("SELECT COUNT(*) FROM catalog_service",Integer.class));
        for(String duplicate:duplicates)assertEquals(selected,store.get(duplicate).get("catalog_id"));
        assertEquals("0",store.get(selected).get("cleanup_passes"),"A remoção automática não equivale a uma revisão humana");
        var saved=store.snapshot();cleanup.save(session,expected);assertEquals(saved,store.snapshot(),"Repetir o salvamento não deve aplicar nada novamente");
    }
    @Test void noticeMustBeAcknowledgedBeforeManualReviewEvenWhenNoDuplicates(){
        var state=cleanup.open(session);assertFalse((Boolean)state.get("acknowledged"));
        assertThrows(ResponseStatusException.class,()->cleanup.decide(session,selected,null,"KEEP",version(state)));
        cleanup.acknowledge(session,version(state));cleanup.decide(session,selected,null,"KEEP",version());
        assertEquals("0",store.get(selected).get("cleanup_passes"));
    }
    @Test void keepingWithoutRemovingProjectsVerificationAndPersistsOnlyOnSave(){
        openAndAcknowledge();int initial=version();cleanup.decide(session,selected,null,"KEEP",initial);
        assertEquals("1",projected(selected).get("cleanup_passes"));
        assertEquals("VERIFIED",projected(selected).get("verification_status"));
        assertEquals("0",store.get(selected).get("cleanup_passes"));assertEquals(2,store.all().size());
        cleanup.save(session,version());assertEquals("1",store.get(selected).get("cleanup_passes"));
        assertEquals("0",store.get(candidate).get("cleanup_passes"));assertFalse((Boolean)cleanup.status(session).get("dirty"));
    }
    @Test void keepingInitialIncreasesItsVerificationButRetirementRemainsPendingUntilSave(){
        openAndAcknowledge();cleanup.decide(session,selected,candidate,"KEEP_SELECTED",version());
        assertEquals("1",projected(selected).get("cleanup_passes"));assertEquals(2,store.all().size());
        cleanup.save(session,version());assertEquals(1,store.all().size());assertEquals(selected,store.get(candidate).get("catalog_id"));
        assertEquals("1",store.get(selected).get("cleanup_passes"));
    }
    @Test void keepingSimilarIncreasesTheKeptCandidatesVerification(){
        openAndAcknowledge();cleanup.decide(session,selected,candidate,"KEEP_SIMILAR",version());
        assertEquals("1",projected(candidate).get("cleanup_passes"));assertEquals(2,store.all().size());
        cleanup.save(session,version());assertEquals(candidate,store.get(selected).get("catalog_id"));assertEquals("1",store.get(candidate).get("cleanup_passes"));
    }
    @Test void repeatedReviewIsCappedPerPendingSaveAndQuickCodeNeedsTwoExplicitSaves(){
        db.update("UPDATE catalog_service SET creation_kind='QUICK',required_cleanup_passes=2 WHERE id=?",selected);
        openAndAcknowledge();cleanup.decide(session,selected,null,"KEEP",version());cleanup.decide(session,selected,null,"KEEP",version());
        assertEquals("1",projected(selected).get("cleanup_passes"));int firstVersion=version();cleanup.save(session,firstVersion);
        assertEquals("PARTIAL",store.get(selected).get("verification_status"));cleanup.save(session,firstVersion);assertEquals("1",store.get(selected).get("cleanup_passes"));
        openAndAcknowledge();cleanup.decide(session,selected,null,"KEEP",version());cleanup.save(session,version());
        assertEquals("2",store.get(selected).get("cleanup_passes"));assertEquals("VERIFIED",store.get(selected).get("verification_status"));
    }
    @Test void sessionsDoNotSharePendingChoicesAndDiscardDoesNotChangeDatabase(){
        openAndAcknowledge();cleanup.decide(session,selected,candidate,"KEEP_SELECTED",version());
        var other=new MockHttpSession();var independent=cleanup.open(other);assertNotEquals(cleanup.status(session).get("id"),independent.get("id"));assertEquals(2,count(independent,"active"));
        cleanup.discard(session);assertEquals(2,store.all().size());assertEquals("0",store.get(selected).get("cleanup_passes"));
        assertEquals(2,count(cleanup.open(session),"active"));
    }
    @Test void staleTabForeignCandidateOrChangedCatalogCannotPublishData(){
        openAndAcknowledge();int initial=version();
        assertThrows(IllegalArgumentException.class,()->cleanup.decide(session,selected,"foreign","KEEP_SELECTED",initial));
        cleanup.decide(session,selected,null,"KEEP",initial);
        assertThrows(ResponseStatusException.class,()->cleanup.decide(session,selected,null,"KEEP",initial));
        add("NOVO");assertEquals(409,assertThrows(ResponseStatusException.class,()->cleanup.save(session,version())).getStatusCode().value());
        assertEquals("0",store.get(selected).get("cleanup_passes"));assertEquals(3,store.all().size());
    }
    @Test void changedDictionaryCannotPublishPreviouslyReviewedChoices(){
        openAndAcknowledge();cleanup.decide(session,selected,null,"KEEP",version());
        db.update("UPDATE abbreviation_state SET revision=revision+1 WHERE id=1");
        assertEquals(409,assertThrows(ResponseStatusException.class,()->cleanup.save(session,version())).getStatusCode().value());
        assertEquals("0",store.get(selected).get("cleanup_passes"));
    }
    @Test void invalidIdentityResponseDoesNotCreatePendingChangesOrMutateSql(){
        when(python.identicalRecords(anyList(),any())).thenReturn(json.valueToTree(Map.of("groups",List.of(Map.of("kept_id",selected,"duplicate_ids",List.of("foreign"))),"total_removed",1)));
        assertThrows(RuntimeException.class,()->cleanup.open(session));assertEquals(2,store.all().size());assertEquals("0",store.get(selected).get("cleanup_passes"));
    }
    @Test void retryRecoversCommitWhenRefreshingSessionFailsWithoutCountingReviewTwice(){
        var flakyStore=spy(store);cleanup=new CleanupSession(flakyStore,dictionary,new SimilarityAnalysis(python),python);
        openAndAcknowledge();cleanup.decide(session,selected,null,"KEEP",version());int savedVersion=version();
        var snapshots=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation->{
            if(snapshots.incrementAndGet()==2)throw new IllegalStateException("Falha simulada após commit");
            return invocation.callRealMethod();
        }).when(flakyStore).snapshot();
        assertThrows(IllegalStateException.class,()->cleanup.save(session,savedVersion));
        assertEquals("1",store.get(selected).get("cleanup_passes"),"O commit já ocorreu apesar da resposta interrompida");
        var recovered=cleanup.save(session,savedVersion);
        assertFalse((Boolean)recovered.get("dirty"));assertEquals("1",store.get(selected).get("cleanup_passes"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM catalog_cleanup_run",Integer.class));
    }
    @SuppressWarnings("unchecked")
    @Test void alreadyVerifiedCodesAreExcludedFromPendingFilterWithoutBecomingPendingChanges(){
        db.update("UPDATE catalog_service SET cleanup_passes=1,cleaned_at=CURRENT_TIMESTAMP WHERE id=?",selected);
        var opened=openAndAcknowledge();assertEquals(1,count(opened,"pending"));
        var pending=cleanup.page(session,Map.of("pending_only","true","size","100"));
        var pendingRows=(List<Map<String,Object>>)pending.get("items");
        assertEquals(1,pendingRows.size());assertEquals(candidate,pendingRows.getFirst().get("catalog_id"));
        assertEquals(1,count(pending,"pending"));
        var all=cleanup.page(session,Map.of("pending_only","false","size","100"));
        var rows=(List<Map<String,Object>>)all.get("items");assertEquals(2,rows.size());
        var verified=rows.stream().filter(row->selected.equals(row.get("catalog_id"))).findFirst().orElseThrow();
        assertEquals("VERIFIED",verified.get("verification_status"));assertEquals(false,verified.get("verification_pending"));
        assertEquals(false,cleanup.status(session).get("dirty"));assertEquals(0,count(cleanup.status(session),"reviewed"));
    }

    @Test void standardizationIsVisibleBeforeSaveAndDoesNotVerifyTheRecord() {
        db.update("UPDATE catalog_service SET descricao_item='sv-pintor  hora extra' WHERE id=?",selected);
        when(python.standardizeItems(anyList(),any())).thenAnswer(invocation->{
            List<Map<String,String>> items=invocation.getArgument(0);
            return items.stream().map(item->{
                var row=new LinkedHashMap<>(item);
                if(selected.equals(row.get("catalog_id"))) row.put("descricao_item","SV pintor HE");
                return (Map<String,String>)row;
            }).toList();
        });
        var before=store.snapshot();var state=openAndAcknowledge();
        assertTrue((Boolean)state.get("dirty"));assertEquals(1,state.get("standardized"));
        assertEquals("SV pintor HE",projected(selected).get("descricao_item"));
        assertEquals(before,store.snapshot());
        cleanup.save(session,version());
        assertEquals("SV pintor HE",store.get(selected).get("descricao_item"));
        assertEquals("0",store.get(selected).get("cleanup_passes"));
        assertFalse((Boolean)cleanup.status(session).get("dirty"));
        assertEquals("SV pintor HE",projected(selected).get("descricao_item"));
    }
    @Test void discardDoesNotPersistStandardization() {
        when(python.standardizeItems(anyList(),any())).thenAnswer(invocation->{
            List<Map<String,String>> items=invocation.getArgument(0);
            return items.stream().map(item->{var row=new LinkedHashMap<>(item);row.put("descricao_item","SV pintor");return (Map<String,String>)row;}).toList();
        });
        var before=store.snapshot();openAndAcknowledge();
        assertTrue((Boolean)cleanup.status(session).get("dirty"));
        cleanup.discard(session);assertEquals(before,store.snapshot());
    }
    @Test void standardizationFailureDoesNotOpenOrMutateCleanup() {
        when(python.standardizeItems(anyList(),any())).thenThrow(new IllegalStateException("offline"));
        var before=store.snapshot();assertThrows(IllegalStateException.class,()->cleanup.open(session));
        assertEquals(before,store.snapshot());assertEquals(false,cleanup.status(session).get("has_session"));
    }

}
