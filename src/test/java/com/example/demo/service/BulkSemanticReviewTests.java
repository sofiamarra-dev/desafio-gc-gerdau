package com.example.demo.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BulkSemanticReviewTests {
    final CatalogStore store = mock(CatalogStore.class);
    final PythonAiClient python = mock(PythonAiClient.class);
    final AbbreviationStore dictionary = mock(AbbreviationStore.class);
    final ObjectMapper json = new ObjectMapper();
    final Map<String,String> fields = Map.of("descricao_item","Pintura");
    final Map<String,String> existing = Map.of("catalog_id","existing", "descricao_item","Pintor");
    final AbbreviationStore.Snapshot version = new AbbreviationStore.Snapshot("dictionary:1", List.of());

    BulkSemanticReview prepare(String response) {
        when(store.batchItem("batch", "item")).thenReturn(Map.of("status","PENDING", "fields",fields));
        when(store.snapshot()).thenReturn(new CatalogStore.Snapshot("catalog:1", List.of(existing)));
        when(dictionary.snapshot()).thenReturn(version);
        when(python.compare(fields, List.of(existing), version)).thenReturn(json.readTree(response));
        when(store.resolveExistingIfRevision("batch","item","existing","catalog:1","dictionary:1")).thenReturn(existing);
        return new BulkSemanticReview(store, python, dictionary);
    }

    @Test void reusesSemantic100AndDoesNotCreateAnyService() {
        var review = prepare("{\"results\":[{\"catalog_id\":\"existing\",\"semantic_exact\":true,\"similaridade_semantica\":1.0}]}");
        var result = review.match("batch","item");
        assertEquals(true,result.get("automatic"));
        assertEquals(existing,result.get("service"));
        verify(store).resolveExistingIfRevision("batch","item","existing","catalog:1","dictionary:1");
        verify(store,never()).create(anyMap(),anyString());
        verify(store,never()).resolve(anyString(),anyString(),anyString(),any());
    }

    @Test void textContainsAndRoundedOrForced100NeverTriggerReuse() {
        for (String response : List.of(
                "{\"results\":[{\"catalog_id\":\"existing\",\"correspondencia_palavras\":true,\"similaridade\":1,\"similaridade_semantica\":0.85}]}",
                "{\"results\":[{\"catalog_id\":\"existing\",\"correspondencia_exata\":true,\"similaridade\":1,\"similaridade_semantica\":0.99996,\"semantic_exact\":false}]}",
                "{\"results\":[{\"catalog_id\":\"existing\",\"semantic_exact\":true,\"similaridade_semantica\":0.99996}]}")) {
            assertEquals(false,prepare(response).match("batch","item").get("resolved"));
        }
        verify(store,never()).resolveExistingIfRevision(anyString(),anyString(),anyString(),anyString(),anyString());
    }

    @Test void retryOfResolvedLineDoesNotCompareOrCreateAgain() {
        when(store.batchItem("batch","item")).thenReturn(Map.of("status","EXISTING","service_id","existing"));
        when(store.get("existing")).thenReturn(existing);
        var result = new BulkSemanticReview(store,python,dictionary).match("batch","item");
        assertEquals(true,result.get("resolved"));
        assertEquals(false,result.get("automatic"));
        verifyNoInteractions(python,dictionary);
    }

    @Test void pythonFailureLeavesPendingRowUntouched() {
        var review = prepare("{\"results\":[]}");
        when(python.compare(fields,List.of(existing),version)).thenThrow(new IllegalStateException("offline"));
        assertThrows(IllegalStateException.class, () -> review.match("batch","item"));
        verify(store,never()).resolveExistingIfRevision(anyString(),anyString(),anyString(),anyString(),anyString());
    }

    @Test void resultOutsideComparedSnapshotIsRejected() {
        var review = prepare("{\"results\":[{\"catalog_id\":\"other\",\"semantic_exact\":true,\"similaridade_semantica\":1.0}]}");
        assertThrows(IllegalStateException.class, () -> review.match("batch","item"));
        verify(store,never()).resolveExistingIfRevision(anyString(),anyString(),anyString(),anyString(),anyString());
    }
}
