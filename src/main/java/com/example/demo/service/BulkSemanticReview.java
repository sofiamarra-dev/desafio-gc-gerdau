package com.example.demo.service;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.Map;

/** Reutilização automática exige identidade do registro; embeddings apenas sugerem semelhantes. */
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
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta linha não está pendente.");
        var snapshot = store.snapshot();
        boolean quick="QUICK".equals(store.batchMode(batch));
        if (snapshot.items().isEmpty() && !quick) return Map.of("automatic", false, "resolved", false);
        var dictionary = abbreviations.snapshot();
        @SuppressWarnings("unchecked") var fields = (Map<String, String>) item.get("fields");
        var compared = python.compare(fields, snapshot.items(), dictionary);
        if (compared == null || !compared.path("results").isArray())
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "O Python não retornou uma comparação válida. Tente novamente.");
        for (var result : compared.path("results")) {
            // A identidade é determinística; nota semântica, igualdade parcial e "contem" não aprovam.
            if (!result.path("identical_record").asBoolean(false)) continue;
            String candidate = result.path("catalog_id").asString();
            if (snapshot.items().stream().noneMatch(row -> row.get("catalog_id").equals(candidate)))
                throw new IllegalStateException("O comparador retornou um serviço fora do catálogo consultado.");
            if (!dictionary.revision().equals(abbreviations.snapshot().revision()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "O dicionário mudou durante a análise. Tente novamente.");
            var service = store.resolveExistingIfRevision(batch, itemId, candidate, snapshot.revision(), dictionary.revision());
            return Map.of("automatic", true, "resolved", true, "service", service);
        }
        if (quick) {
            var check=compared.path("quick_creation_check");
            if (!"quick-creation-1".equals(check.path("version").asString())
                    || !check.path("requires_review").isBoolean()
                    || check.path("candidate_count").asInt(-1)!=snapshot.items().size()
                    || compared.path("results").size()!=Math.min(20,snapshot.items().size()))
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Atualize o Python para analisar a criação rápida. A linha continua pendente.");
            // O resumo cobre TODOS os candidatos, inclusive os que ficaram fora dos 20 exibidos.
            if (check.path("requires_review").asBoolean()) return Map.of("automatic",false,"resolved",false);
            for (var result:compared.path("results")) {
                if (snapshot.items().stream().noneMatch(row -> row.get("catalog_id").equals(result.path("catalog_id").asString()))
                        || !result.path("similaridade_semantica").isNumber()
                        || !Double.isFinite(result.path("similaridade_semantica").asDouble())
                        || result.path("correspondencia_palavras").asBoolean(false)
                        || result.path("similaridade_semantica").asDouble()>.90)
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"A análise rápida está inconsistente. Tente novamente.");
            }
            var service=store.resolveQuickIfRevision(batch,itemId,snapshot.revision(),dictionary.revision());
            return Map.of("automatic",true,"resolved",true,"created_quick",true,"service",service);
        }
        return Map.of("automatic", false, "resolved", false);
    }
}
