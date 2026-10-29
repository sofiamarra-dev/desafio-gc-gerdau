package com.example.demo.service;

import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class TextoDeSolicitacoes {
    private static final String[] FIELDS = {"descricao_item", "grupo_servico", "codigo_fornecedor", "codigo_servico", "unidade_medida", "unidade_descricao"};

    public LeitorDePlanilha.ParsedRequests parse(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Cole ao menos uma solicitação.");
        if (text.length() > 2_000_000) throw new IllegalArgumentException("O texto deve ter até 2 milhões de caracteres.");
        var rows = new ArrayList<LeitorDePlanilha.RequestRow>();
        String[] lines = text.replaceFirst("^\\uFEFF", "").split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].isBlank()) continue;
            List<String> cells = columns(lines[i], i + 1);
            if (cells.stream().allMatch(String::isBlank)) continue;
            var fields = new LinkedHashMap<String, String>();
            for (int c = 0; c < FIELDS.length; c++) fields.put(FIELDS[c], c < cells.size() ? cells.get(c) : "");
            rows.add(new LeitorDePlanilha.RequestRow(i + 1, fields));
            if (rows.size() > 50_000) throw new IllegalArgumentException("Máximo de 50.000 solicitações por lote.");
        }
        if (rows.isEmpty()) throw new IllegalArgumentException("O texto não contém solicitações.");
        return new LeitorDePlanilha.ParsedRequests("Texto", rows);
    }

    private List<String> columns(String line, int number) {
        var result = new ArrayList<String>();
        var value = new StringBuilder();
        boolean quoted = false, closed = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { value.append('"'); i++; }
                    else { quoted = false; closed = true; }
                } else value.append(ch);
            } else if (ch == ';') {
                result.add(value.toString().strip()); value.setLength(0); closed = false;
            } else if (ch == '"') {
                if (closed || !value.toString().isBlank()) throw invalid(number);
                value.setLength(0); quoted = true;
            } else {
                if (closed && !Character.isWhitespace(ch)) throw invalid(number);
                if (!closed) value.append(ch);
            }
        }
        if (quoted) throw invalid(number);
        result.add(value.toString().strip());
        if (result.size() > FIELDS.length) throw new IllegalArgumentException("Linha " + number + ": use até seis campos. Para incluir ; no texto, coloque o campo entre aspas duplas.");
        return result;
    }

    private IllegalArgumentException invalid(int number) {
        return new IllegalArgumentException("Linha " + number + ": confira as aspas. Cada solicitação deve ocupar uma única linha.");
    }
}
