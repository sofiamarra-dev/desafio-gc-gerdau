package com.example.demo.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TextoDeSolicitacoesTests {
    final TextoDeSolicitacoes parser = new TextoDeSolicitacoes();

    @Test void preservesAccentsCodesEmptyColumnsAndOriginalLineNumbers() {
        var result = parser.parse("\uFEFFManutenção de motor;Grupo;0012;;h\r\n\r\nPINTOR HX 50%\r\n;Grupo;0001;;;\n");
        assertEquals(3, result.rows().size());
        assertEquals("Manutenção de motor", result.rows().get(0).fields().get("descricao_item"));
        assertEquals("0012", result.rows().get(0).fields().get("codigo_fornecedor"));
        assertEquals("", result.rows().get(0).fields().get("codigo_servico"));
        assertEquals(3, result.rows().get(1).rowNumber());
        assertEquals("PINTOR HX 50%", result.rows().get(1).fields().get("descricao_item"));
        assertEquals("", result.rows().get(2).fields().get("descricao_item"));
    }

    @Test void handlesQuotedSeparatorsAndEscapedQuotes() {
        var rows = parser.parse("\"Reparo; motor \"\"A\"\"\";Grupo;;;H;hora").rows();
        assertEquals("Reparo; motor \"A\"", rows.getFirst().fields().get("descricao_item"));
        assertEquals("hora", rows.getFirst().fields().get("unidade_descricao"));
    }

    @Test void rejectsMalformedInputAndLimits() {
        for (String text : new String[]{"", " ; ; ", "a;b;c;d;e;f;g", "\"aberto", "abc\"texto\"", "\"texto\"fim", "a".repeat(2_000_001)})
            assertThrows(IllegalArgumentException.class, () -> parser.parse(text));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("item\n".repeat(50_001)));
    }
}
