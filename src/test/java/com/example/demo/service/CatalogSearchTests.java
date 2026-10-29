package com.example.demo.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatalogSearchTests {
    private AbbreviationStore dictionary() {
        var store = mock(AbbreviationStore.class);
        when(store.snapshot()).thenReturn(new AbbreviationStore.Snapshot("dictionary:1", List.of()));
        return store;
    }
    @Test void rebuildsSameRevisionWhenPythonIndexIsOutdated() {
        var json = new ObjectMapper();
        var store = mock(CatalogStore.class);
        var python = mock(PythonAiClient.class);
        var rows = List.of(Map.of("codigo_servico", "A", "descricao_item", "PINTOR HE 50%"));
        when(store.snapshot()).thenReturn(new CatalogStore.Snapshot("test:1", rows));
        when(python.status()).thenReturn(json.readTree("{\"size\":1,\"revision\":\"test:1\",\"requires_reindex\":true}"));
        new CatalogSearch(store, python, dictionary()).search("pintor");
        var ordered = inOrder(python);
        ordered.verify(python).sync(rows, "test:1");
        ordered.verify(python).search("pintor");
    }

    @Test void onlySynchronizesChangedRevisionAndProtectsLegacyCatalog() {
        var json = new ObjectMapper();
        var store = mock(CatalogStore.class);
        var python = mock(PythonAiClient.class);
        var rows = List.of(Map.of("codigo_servico", "A", "descricao_item", "Motor"));
        when(store.snapshot()).thenReturn(new CatalogStore.Snapshot("test:1", rows));
        when(python.status()).thenReturn(json.readTree("{\"size\":0,\"revision\":null}"), json.readTree("{\"size\":1,\"revision\":\"test:1\",\"dictionary_version\":\"dictionary:1\"}"));
        when(python.search("motor")).thenReturn(json.readTree("{\"total\":1}"));
        var search = new CatalogSearch(store, python, dictionary());
        assertEquals(1, search.search("motor").get("total").asInt());
        search.search("motor");
        verify(python, times(1)).sync(rows, "test:1");
        when(store.snapshot()).thenReturn(new CatalogStore.Snapshot("test:0", List.of()));
        when(python.status()).thenReturn(json.readTree("{\"size\":100}"));
        assertThrows(ResponseStatusException.class, () -> search.search("motor"));
    }
}
