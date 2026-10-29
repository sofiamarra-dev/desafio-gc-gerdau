package com.example.demo.service;

import com.example.demo.api.AbbreviationController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:abbreviations;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class AbbreviationTests {
    @Autowired AbbreviationStore store;
    @Autowired AbbreviationController controller;
    private String code() { return "X" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(); }
    @Test void initialDictionaryAndNormalizedConflicts() {
        var initial = store.snapshot();
        assertTrue(initial.entries().stream().anyMatch(e -> e.code().equals("DOMF") && e.variants().contains("DOM.FER")));
        assertThrows(ResponseStatusException.class, () -> store.create(code(), "outro", List.of("  SERVICO  "), true));
        assertThrows(ResponseStatusException.class, () -> store.create("sv", "suco verde", List.of(), true));
        assertEquals("dom . fer", AbbreviationStore.normalize("DOM .FER"));
    }
    @Test void editIsVersionedAndInactiveExpressionsRemainReserved() {
        String code = code(), meaning = "sentido " + code, alias = "variante " + code;
        var created = store.create(code, meaning, List.of(alias, alias.toUpperCase()), true);
        var item = created.entries().stream().filter(e -> e.code().equals(code)).findFirst().orElseThrow();
        assertEquals(List.of(alias), item.variants());
        var edited = store.update(code, List.of(alias), false, item.version());
        assertNotEquals(created.revision(), edited.revision());
        assertThrows(ResponseStatusException.class, () -> store.create(code(), "outro significado", List.of(alias), true));
        assertThrows(ResponseStatusException.class, () -> store.update(code, List.of(), true, item.version()));
        var current = store.snapshot().entries().stream().filter(e -> e.code().equals(code)).findFirst().orElseThrow();
        assertFalse(current.active());
        assertEquals(meaning, current.meaning());
        assertThrows(IllegalArgumentException.class, () -> controller.update(code,
                new AbbreviationController.Edit(null, "troca indevida", List.of(), true, current.version())));
    }
    @Test void competingRequestsCannotClaimSameExpression() throws Exception {
        String alias = "comum " + code();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> claim(code(), alias));
            var b = pool.submit(() -> claim(code(), alias));
            assertEquals(1, a.get() + b.get());
        }
    }
    private int claim(String code, String alias) {
        try { store.create(code, "significado " + code, List.of(alias), true); return 1; }
        catch (ResponseStatusException conflict) { assertEquals(409, conflict.getStatusCode().value()); return 0; }
    }
}
