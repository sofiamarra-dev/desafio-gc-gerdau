package com.example.demo.service;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.Map;

/** Apenas o resultado semÃ¢ntico sem arredondamento pode resolver uma linha automaticamente. */
@Service
public class BulkSemanticReview {
    private final CatalogStore store;
    private final PythonAiClient python;
    private final AbbreviationStore abbreviations;

    public BulkSemanticReview(CatalogStore store, PythonAiClient python, AbbreviationStore abbreviations) {
        this.store = store;
        this.python = python;
        this.abbreviations = abbreviations;
    }

    public Map<String, Object> match(String batch, String itemId) {
        var item = store.batchItem(batch, itemId);
        String status = item.get("status").toString();
        if (status.equals("EXISTING") || status.equals("CREATED"))
            return Map.of("automatic", false, "resolved", true,
                    "service", store.get(item.get("service_id").toString()));
        if (!status.equals("PENDING"))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta linha nÃ£o estÃ¡ pendente.");
        var snapshot = store.snapshot();
        if (snapshot.items().isEmpty()) return Map.of("automatic", false, "resolved", false);
        var dictionary = abbreviations.snapshot();
        @SuppressWarnings("unchecked") var fields = (Map<String, String>) item.get("fields");
        var compared = python.compare(fields, snapshot.items(), dictionary);
        if (compared == null || !compared.path("results").isArray())
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "O Python nÃ£o retornou uma comparaÃ§Ã£o vÃ¡lida. Tente novamente.");
        for (var result : compared.path("results")) {
            // A flag vem do comparador de embeddings. NÃ£o usar 'similaridade',
            // igualdade textual, 'contem' nem o percentual exibido na tela.
            if (!result.path("semantic_exact").asBoolean(false)) continue;
            double score = result.path("similaridade_semantica").asDouble(Double.NaN);
            if (!Double.isFinite(score) || Math.abs(1.0 - score) > 1e-7) continue;
            String candidate = result.path("catalog_id").asString();
            if (snapshot.items().stream().noneMatch(row -> row.get("catalog_id").equals(candidate)))
                throw new IllegalStateException("O comparador retornou um serviÃ§o fora do catÃ¡logo consultado.");
            if (!dictionary.revision().equals(abbreviations.snapshot().revision()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "O dicionÃ¡rio mudou durante a anÃ¡lise. Tente novamente.");
            var service = store.resolveExistingIfRevision(batch, itemId, candidate, snapshot.revision(), dictionary.revision());
            return Map.of("automatic", true, "resolved", true, "service", service);
        }
        return Map.of("automatic", false, "resolved", false);
    }
}
