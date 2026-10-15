package com.example.demo.service;

import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.IOException;
import java.text.Normalizer;
import java.util.*;

@Service
public class LeitorDePlanilha {

    public record ParsedSheet(List<Map<String, String>> items, String sheet, int skippedRows) {}

    public ParsedSheet parse(MultipartFile file) throws IOException {
        String filename = Optional.ofNullable(file.getOriginalFilename()).orElse("");
        if (file.isEmpty() || !filename.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new IllegalArgumentException("Selecione uma planilha XLSX não vazia.");
        }

        try (InputStream arquivo = file.getInputStream(); Workbook workbook = WorkbookFactory.create(arquivo)) {
            Map<String, String> units = readUnidadesDeMedida(workbook);

            for (Sheet planilha: workbook){

                Header header = findHeader(planilha);
                if (header == null) continue;
                List<Map<String, String>> items = new ArrayList<>();

                int linhasPuladas = 0;

                for (int r = header.row + 1; r <= planilha.getLastRowNum(); r++) {

                    Row row = planilha.getRow(r);

                    if (row == null) continue;

                    Map<String, String> item = new LinkedHashMap<>();

                    for (Map.Entry<String, Integer> h : header.columns.entrySet()) {
                        item.put(h.getKey(), text(row.getCell(h.getValue())));
                    }

                    String code = item.getOrDefault("codigo_servico", "");
                    String desc = item.getOrDefault("descricao_item", "");
                    if (code.isBlank() || desc.isBlank()) { linhasPuladas++; continue; }
                    String unit = item.getOrDefault("unidade_medida", "").toUpperCase(Locale.ROOT);
                    item.put("unidade_descricao", units.getOrDefault(unit, unit));
                    items.add(item);
                    if (items.size() > 50000) {
                        throw new IllegalArgumentException("Amostra grande demais para o protótipo: máximo de 50.000 linhas.");
                    }

                }
                if (items.isEmpty()) {
                    throw new IllegalArgumentException("A aba de serviços não contém linhas válidas.");
                }
                return new ParsedSheet(items, planilha.getSheetName(), linhasPuladas);
            }
            throw new IllegalArgumentException("Não encontrei uma aba com as cinco colunas do catálogo de serviços.");
        }
    }

    private record Header(int row, Map<String, Integer> columns) {}

    private Header findHeader(Sheet sheet) {

        int end = Math.min(16, sheet.getLastRowNum());

        for (int i = sheet.getFirstRowNum(); i <= end; i++) {

            Row row = sheet.getRow(i);
            if (row == null) continue;
            Map<String, Integer> found = new HashMap<>();

            for (Cell cell : row) {
                String header = normalize(text(cell));
                switch (header) {
                    case "grupo de servico" -> found.put("grupo_servico", cell.getColumnIndex());
                    case "codigo do fornecedor", "codigo de fornecedor" -> found.put("codigo_fornecedor", cell.getColumnIndex());
                    case "codigo do servico", "codigo de servico" -> found.put("codigo_servico", cell.getColumnIndex());
                    case "descricao do item", "nome do servico", "descricao do servico" -> found.put("descricao_item", cell.getColumnIndex());
                    case "unidade de medida", "unidade medida" -> found.put("unidade_medida", cell.getColumnIndex());
                    default -> { }
                }
            }
            if (found.keySet().containsAll(Set.of("grupo_servico", "codigo_fornecedor", "codigo_servico", "descricao_item", "unidade_medida"))) {
                return new Header(i, found);
            }
        }
        return null;
    }

    private String text(Cell cell) { // pega o texto da planilha cuidando para não enviar uma formula ao invés do resultado
        if (cell == null) return "";
        DataFormatter formatter = new DataFormatter(Locale.forLanguageTag("pt-BR"));
        formatter.setUseCachedValuesForFormulaCells(true);
        if (cell.getCellType() == CellType.FORMULA) {
            CellType cached = cell.getCachedFormulaResultType();
            if (cached == CellType.STRING) return cell.getStringCellValue().trim();
            if (cached == CellType.NUMERIC) return formatter.formatCellValue(cell).trim();
            return "";
        }
        return formatter.formatCellValue(cell).trim();
    }

    private Map<String, String> readUnidadesDeMedida(Workbook workbook) {

        Map<String, String> unidades = new HashMap<>();

        for (Sheet sheet : workbook) {

            if (!normalize(sheet.getSheetName()).contains("unidades de medida")) continue;
            for (Row row : sheet) {
                String code = text(row.getCell(0));
                String description = text(row.getCell(1));
                if (code.length() <= 8 && !code.isBlank() && !description.isBlank()
                        && !normalize(code).contains("sigla")){
                    unidades.put(code.toUpperCase(Locale.ROOT), description);
                }
            }
        }
        return unidades;
    }

    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ").trim();
    }



}
