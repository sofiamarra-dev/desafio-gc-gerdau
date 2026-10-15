package com.example.demo.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.Normalizer;
import java.util.*;

import static java.text.Normalizer.normalize;

public class LeitorDePlanilha {
    FileInputStream arquivo = new FileInputStream("produtos.xlsx");
    Workbook workbook = new XSSFWorkbook(arquivo);

    private final DataFormatter formatter = new DataFormatter(new Locale("pt", "BR"));

    public LeitorDePlanilha() throws IOException {
    }

    public record ParsedSheet(List<Map<String, String>> items, String sheet, int skippedRows) {}

    public ParsedSheet parse(MultipartFile file) throws IOException {

        Map<String, String> units = readUnidadesDeMedida(workbook);

        for (Sheet planilha: workbook){

            Header header = findHeader(planilha);
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
            return new ParsedSheet(items, planilha.getSheetName(), linhasPuladas);
        }
        return null;
    }

    private record Header(int row, Map<String, Integer> columns) {}

    private Header findHeader(Sheet sheet) {

        int end = Math.min(16, sheet.getLastRowNum());

        for (int i = sheet.getFirstRowNum(); i <= end; i++) {

            Row row = sheet.getRow(i);
            if (row == null) continue;
            Map<String, Integer> found = new HashMap<>();

            for (Cell cell : row) {
                String header = text(cell);
                switch (header) {
                    case "Grupo de Serviço" -> found.put("grupo_servico", cell.getColumnIndex());
                    case "Código do Fornecedor", "codigo de fornecedor" -> found.put("codigo_fornecedor", cell.getColumnIndex());
                    case "Código do Serviço", "codigo de servico" -> found.put("codigo_servico", cell.getColumnIndex());
                    case "Descrição do Item", "nome do servico", "descricao do servico" -> found.put("descricao_item", cell.getColumnIndex());
                    case "Unidade medida", "unidade medida" -> found.put("unidade_medida", cell.getColumnIndex());
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

            if (sheet.getSheetName().contains("Unidades de Medida")) continue;
            for (Row row : sheet) {
                String code = text(row.getCell(0));
                String description = text(row.getCell(1));
                if (code.length() <= 8 && !code.isBlank() && !description.isBlank()){
                    unidades.put(code.toUpperCase(Locale.ROOT), description);
                }
            }
        }
        return unidades;
    }



}
