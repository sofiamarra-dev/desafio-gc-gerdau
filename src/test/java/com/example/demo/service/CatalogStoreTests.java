package com.example.demo.service;

import com.example.demo.api.CatalogController;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:workflow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class CatalogStoreTests {
    @Autowired CatalogStore store;
    @Autowired CatalogController controller;
    @Autowired JdbcTemplate db;

    @BeforeEach void clean() {
        db.update("DELETE FROM catalog_import_history");
        db.update("DELETE FROM review_item");
        db.update("DELETE FROM review_batch");
        db.update("DELETE FROM catalog_service");
    }

    private Map<String, String> entry(String code, String description) {
        return Map.of("codigo_servico", code, "descricao_item", description, "unidade_medida", "H");
    }

    @Test void creationIsIdempotentAndCodeSupplierIsUnique() {
        String key = UUID.randomUUID().toString();
        var first = store.create(entry("", "Manutenção de motor"), key);
        assertTrue(first.get("codigo_servico").startsWith("LOCAL-"));
        assertEquals(first, store.create(entry("", "Manutenção de motor"), key));
        assertEquals(1, store.all().size());
        assertThrows(ResponseStatusException.class, () -> store.create(entry("", "Outros dados"), key));
        assertThrows(ResponseStatusException.class, () -> store.create(entry(first.get("codigo_servico"), "Duplicado"), UUID.randomUUID().toString()));
    }

    @Test void batchPersistsPendingInvalidAndDecisionsWithoutReplacingCatalog() {
        var existing = store.create(entry("EX-1", "Serviço existente"), UUID.randomUUID().toString());
        var parsed = new LeitorDePlanilha.ParsedRequests("Pedidos", List.of(
                new LeitorDePlanilha.RequestRow(2, entry("", "Novo serviço")),
                new LeitorDePlanilha.RequestRow(3, entry("", "Outro pedido")),
                new LeitorDePlanilha.RequestRow(4, entry("X", ""))));
        String batch = store.createBatch("solicitacoes.xlsx", parsed);
        assertEquals(1, store.all().size());
        var rows = store.batchItems(batch, 0, 50);
        assertEquals("PENDING", rows.get(0).get("status"));
        assertEquals("INVALID", rows.get(2).get("status"));
        String firstId = rows.get(0).get("id").toString();
        var created = store.resolve(batch, firstId, "CREATE", null);
        assertEquals(created, store.resolve(batch, firstId, "CREATE", null));
        assertEquals(2, store.all().size());
        var selected = store.resolve(batch, rows.get(1).get("id").toString(), "EXISTING", existing.get("catalog_id"));
        assertEquals(existing, selected);
        assertEquals(2, store.all().size());
        assertEquals("CREATED", store.batchItems(batch, 0, 50).get(0).get("status"));
        assertThrows(ResponseStatusException.class, () -> store.resolve(batch, firstId, "EXISTING", existing.get("catalog_id")));
        assertEquals(2, store.batchItems(batch, 2, 50).size());
    }

    @Test void catalogImportIsAdditiveAndExportPreservesText() throws Exception {
        store.importCatalog(List.of(entry("001", "Maçã Fuji"), entry("002", "Reparo")));
        var result = store.importCatalog(List.of(entry("001", "Não sobrescrever"), entry("003", "Limpeza")));
        assertEquals(1, result.get("imported"));
        assertEquals(1, result.get("existing"));
        assertEquals(3, store.all().size());
        assertTrue(store.all().stream().anyMatch(row -> row.get("descricao_item").equals("Maçã Fuji")));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM catalog_import_history", Integer.class));
        assertTrue(db.queryForList("SELECT original_items FROM catalog_import_history", String.class).stream()
                .anyMatch(original -> original.contains("Não sobrescrever")));
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(controller.exportCatalog().getBody()))) {
            var sheet = workbook.getSheetAt(0);
            assertEquals(3, sheet.getLastRowNum());
            boolean preserved = false;
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                if ("001".equals(sheet.getRow(i).getCell(2).getStringCellValue())) {
                    assertEquals("Maçã Fuji", sheet.getRow(i).getCell(3).getStringCellValue());
                    preserved = true;
                }
            }
            assertTrue(preserved);
        }
    }

    @Test void repeatedConcurrentDecisionCreatesOnlyOneService() throws Exception {
        String batch = store.createBatch("pedidos.xlsx", new LeitorDePlanilha.ParsedRequests("Pedidos", List.of(new LeitorDePlanilha.RequestRow(2, entry("", "Concorrente")))));
        String item = store.batchItems(batch, 0, 50).getFirst().get("id").toString();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> store.resolve(batch, item, "CREATE", null));
            var second = executor.submit(() -> store.resolve(batch, item, "CREATE", null));
            assertEquals(first.get(), second.get());
        }
        assertEquals(1, store.all().size());
    }
}
