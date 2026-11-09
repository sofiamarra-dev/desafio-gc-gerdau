package com.example.demo.service;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SimilarityAnalysisTests {
    final SimilarityAnalysis service=new SimilarityAnalysis(mock(PythonAiClient.class));
    final ObjectMapper json=new ObjectMapper();
    final Map<String,String> first=Map.of("catalog_id","a","codigo_servico","123","descricao_item","PINTOR","codigo_fornecedor","F1");
    final Map<String,String> second=Map.of("catalog_id","b","codigo_servico","123","descricao_item","PINTOR INDUSTRIAL","codigo_fornecedor","F2");
    @Test void selfReferenceNeverProducesContainsOrSimilarity(){
        var rows=service.rows(List.of(first,second),json.readTree("{\"rows\":[{\"catalog_id\":\"a\",\"pair\":{\"catalog_id\":\"a\",\"similaridade_semantica\":1,\"correspondencia_palavras\":true}}]}"));
        assertEquals(false,rows.getFirst().get("contains"));assertNull(rows.getFirst().get("similarity"));assertNull(rows.getFirst().get("pair_id"));
    }
    @Test void distinctRecordWithSameServiceCodeRemainsValidAndIdentifiesSupplier(){
        var rows=service.rows(List.of(first,second),json.readTree("{\"rows\":[{\"catalog_id\":\"a\",\"pair\":{\"catalog_id\":\"b\",\"similaridade_semantica\":0.8,\"correspondencia_palavras\":true}}]}"));
        assertEquals(true,rows.getFirst().get("contains"));assertEquals("b",rows.getFirst().get("pair_id"));assertEquals("F2",rows.getFirst().get("pair_supplier"));assertEquals("PINTOR INDUSTRIAL",rows.getFirst().get("pair_description"));
    }
}
