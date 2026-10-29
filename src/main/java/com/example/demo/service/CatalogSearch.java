package com.example.demo.service;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.Map;

@Service
public class CatalogSearch {
    private final CatalogStore store;
    private final PythonAiClient python;
    private final AbbreviationStore abbreviations;

    public CatalogSearch(CatalogStore store, PythonAiClient python, AbbreviationStore abbreviations) {
        this.store = store;
        this.python = python;
        this.abbreviations = abbreviations;
    }

    // Banco é a fonte principal. O índice Python pode ser reconstruído após qualquer falha.
    // Uma revisão persistida evita recalcular embeddings em cada pesquisa.
    public synchronized JsonNode search(String query) {
        var snapshot = store.snapshot();
        var state = python.status();
        String remoteRevision = state.path("revision").asString();
        String databaseKey = snapshot.revision().substring(0, snapshot.revision().indexOf(':')) + ":";
        if (state.path("size").asInt() > 0 && (remoteRevision == null || !remoteRevision.startsWith(databaseKey)))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "Há um catálogo Python de outra origem. Use Recuperar catálogo anterior antes de pesquisar; a base anterior será preservada no SQL.");
        var dictionary = abbreviations.snapshot();
        boolean dictionaryChanged = !dictionary.revision().equals(state.path("dictionary_version").asString());
        if (dictionaryChanged) python.dictionary(dictionary);
        if (dictionaryChanged || state.path("requires_reindex").asBoolean(false)
                || !snapshot.revision().equals(state.path("revision").asString())) {
            python.sync(snapshot.items(), snapshot.revision());
        }
        return python.search(query);
    }

    public synchronized void synchronizeAfterMigration() {
        var snapshot = store.snapshot();
        python.dictionary(abbreviations.snapshot());
        python.sync(snapshot.items(), snapshot.revision());
    }

    public Map<String, Object> databaseStatus() {
        var snapshot = store.snapshot();
        return Map.of("size", snapshot.items().size(), "revision", snapshot.revision());
    }
}
