package com.example.demo.service;

import com.example.demo.api.AbbreviationController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:dictionarycorrections;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class AbbreviationCorrectionTests {
    @Autowired AbbreviationStore store;
    @Autowired AbbreviationController controller;
    String code() { return "T"+UUID.randomUUID().toString().replace("-", "").substring(0,10).toUpperCase(); }
    AbbreviationStore.Entry get(String code) { return store.snapshot().entries().stream().filter(e->e.code().equals(code)).findFirst().orElseThrow(); }
    @Test void reservedAmbiguousTokensRemainUnclaimedButContextCanBeRegistered() {
        for (String token: List.of("ESP", "ADM", "A.D.M.", "ÉSP")) {
            assertThrows(IllegalArgumentException.class, ()->store.create(code(), "sentido confirmado", List.of(token), true));
        }
        assertThrows(IllegalArgumentException.class, ()->store.create("ADM", "administrativo", List.of(), true));
        String code=code();store.create(code,"qualificador contextual confirmado "+code,List.of("expressão completa "+code),true);
        assertTrue(get(code).active());
    }
    @Test void correctionIsAtomicVersionedAndReleasesOnlyExplicitlyRemovedAliases() {
        String code=code(),other=code(),old="significado errado "+code,newMeaning="significado correto "+code;
        store.create(code,old,List.of("variante errada "+code),false);
        var before=get(code);
        assertThrows(IllegalArgumentException.class,()->controller.correct(code,new AbbreviationController.Correction(newMeaning,List.of(),"Correção revisada pelo responsável",before.version(),false)));
        store.correct(code,newMeaning,List.of("variante correta "+code),"Correção do significado e da variante",before.version());
        var after=get(code);
        assertFalse(after.active());assertEquals(before.version()+1,after.version());assertEquals(newMeaning,after.meaning());
        assertEquals(1,store.corrections(code).size());
        assertTrue(store.corrections(code).getFirst().get("before").toString().contains(old));
        store.create(other,old,List.of("variante errada "+code),true);
        var revision=store.snapshot().revision();
        assertThrows(ResponseStatusException.class,()->store.correct(code,old,List.of(),"Colisão deve rejeitar toda a correção",after.version()));
        assertEquals(revision,store.snapshot().revision());assertEquals(1,store.corrections(code).size());assertEquals(newMeaning,get(code).meaning());
        assertThrows(ResponseStatusException.class,()->store.correct(code,"outro",List.of(),"Versão antiga deve ser recusada",before.version()));
        assertThrows(IllegalArgumentException.class,()->store.correct(code,"outro",List.of(),"curto",after.version()));
        store.correct(code,"significado restaurado "+code,List.of(),"Nova correção conserva os dois registros",after.version());
        assertEquals(2,store.corrections(code).size());
    }
}
