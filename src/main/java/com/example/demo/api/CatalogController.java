package com.example.demo.api;

import com.example.demo.service.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {
    private final CatalogStore store;
    private final CatalogSearch search;
    private final PythonAiClient python;
    private final LeitorDePlanilha parser;
    private final TextoDeSolicitacoes textParser;
    private final ObjectMapper json;
    private final BulkSemanticReview bulkReview;

    public CatalogController(CatalogStore store, CatalogSearch search, PythonAiClient python, LeitorDePlanilha parser, TextoDeSolicitacoes textParser, ObjectMapper json, BulkSemanticReview bulkReview) {
        this.store = store; this.search = search; this.python = python; this.parser = parser; this.json = json; this.textParser = textParser; this.bulkReview = bulkReview;
    }

    @GetMapping("/status")
    public Map<String, Object> status() { return search.databaseStatus(); }

    @PostMapping("/services")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> create(@RequestBody Map<String, String> fields) {
        return store.create(fields, fields.get("request_id"));
    }

    @PostMapping("/migrate-python")
    public Map<String, Object> migrate() {
        List<Map<String, String>> items = new ArrayList<>();
        for (var node : python.catalog().path("items")) {
            @SuppressWarnings("unchecked")
            Map<String, String> row = json.readValue(node.toString(), Map.class);
            items.add(row);
        }
        var result = new LinkedHashMap<String, Object>(store.importCatalog(items, "PYTHON_MIGRATION"));
        try {
            search.synchronizeAfterMigration();
            result.put("index_pending", false);
        } catch (org.springframework.web.client.RestClientException e) {
            result.put("index_pending", true);
            result.put("warning", "Os registros foram salvos no SQL, mas a sincronização falhou. Repita Recuperar catálogo anterior para tentar novamente sem duplicar os cadastros.");
        }
        return result;
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportCatalog() throws IOException {
        return workbook("catalogo.xlsx", List.of("Grupo de Serviço", "Código do Fornecedor", "Código do Serviço", "Descrição do Item", "Unidade de medida", "Descrição da Unidade"),
                store.all().stream().map(CatalogController::cells).toList());
    }

    @GetMapping("/batches/template")
    public ResponseEntity<byte[]> batchTemplate() throws IOException {
        return workbook("modelo-solicitacoes.xlsx", List.of("Descrição do Item", "Grupo de Serviço", "Código do Fornecedor", "Código do Serviço", "Unidade de medida", "Descrição da Unidade"), List.of(), true);
    }

    @PostMapping(value="/batches", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> upload(@RequestParam("file") MultipartFile file) throws IOException {
        String filename = Objects.toString(file.getOriginalFilename(), "solicitacoes.xlsx");
        if (filename.length() > 255) throw new IllegalArgumentException("Nome de arquivo muito longo.");
        return Map.of("id", store.createBatch(filename, parser.parseRequests(file)));
    }

    public record TextBatch(String text) {}

    @PostMapping("/batches/text")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> paste(@RequestBody TextBatch input) {
        return Map.of("id", store.createBatch("Solicitações por texto", textParser.parse(input.text())));
    }

    @GetMapping("/batches")
    public List<Map<String, Object>> batches() { return store.batches(); }

    @GetMapping("/batches/{id}/items")
    public List<Map<String, Object>> items(@PathVariable String id, @RequestParam(defaultValue="0") int after,
                                           @RequestParam(defaultValue="50") int limit) {
        return store.batchItems(id, Math.max(0, after), Math.clamp(limit, 1, 200));
    }

    public record Decision(String action, String service_id) {}

    @PostMapping("/batches/{batch}/items/{item}/auto-match")
    public Map<String, Object> autoMatch(@PathVariable String batch, @PathVariable String item) {
        return bulkReview.match(batch, item);
    }

    @PostMapping("/batches/{batch}/items/{item}/decision")
    public Map<String, String> decide(@PathVariable String batch, @PathVariable String item, @RequestBody Decision decision) {
        if ("CREATE".equals(decision.action())) {
            var match = bulkReview.match(batch, item);
            if (Boolean.TRUE.equals(match.get("resolved"))) {
                @SuppressWarnings("unchecked") var service = (Map<String, String>) match.get("service");
                return service;
            }
        }
        return store.resolve(batch, item, decision.action(), decision.service_id());
    }

    @GetMapping("/batches/{batch}/export")
    public ResponseEntity<byte[]> exportBatch(@PathVariable String batch) throws IOException {
        var rows = new ArrayList<List<String>>();
        for (var item : store.batchItems(batch, 0, 50_000)) {
            @SuppressWarnings("unchecked") var fields = (Map<String, String>) item.get("fields");
            var row = new ArrayList<String>();
            row.add(item.get("row_number").toString());
            row.add(item.get("status").toString());
            row.addAll(cells(fields));
            String serviceId = (String) item.get("service_id");
            row.add(serviceId == null ? "" : store.get(serviceId).get("codigo_servico"));
            row.add(Objects.toString(item.get("error"), ""));
            rows.add(row);
        }
        return workbook("resultado-lote.xlsx", List.of("Linha original", "Situação", "Grupo de Serviço", "Código do Fornecedor", "Código solicitado", "Descrição do Item", "Unidade de medida", "Descrição da Unidade", "Código final", "Erro"), rows);
    }

    private static List<String> cells(Map<String, String> item) {
        return List.of(item.getOrDefault("grupo_servico", ""), item.getOrDefault("codigo_fornecedor", ""), item.getOrDefault("codigo_servico", ""),
                item.getOrDefault("descricao_item", ""), item.getOrDefault("unidade_medida", ""), item.getOrDefault("unidade_descricao", ""));
    }

    private static ResponseEntity<byte[]> workbook(String filename, List<String> headers, List<List<String>> rows) throws IOException {
        return workbook(filename, headers, rows, false);
    }

    private static ResponseEntity<byte[]> workbook(String filename, List<String> headers, List<List<String>> rows, boolean template) throws IOException {
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Serviços");
            var header = sheet.createRow(0);
            for (int c=0; c<headers.size(); c++) { header.createCell(c).setCellValue(headers.get(c)); sheet.setColumnWidth(c, 24 * 256); }
            if (template) {
                workbook.setSheetName(0, "Solicitações");
                var textStyle = workbook.createCellStyle();
                textStyle.setDataFormat(workbook.createDataFormat().getFormat("@"));
                var headerStyle = workbook.createCellStyle();
                headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
                headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                headerStyle.setWrapText(true);
                var font = workbook.createFont();
                font.setBold(true);
                font.setColor(IndexedColors.WHITE.getIndex());
                headerStyle.setFont(font);
                header.setHeightInPoints(32);
                for (int c = 0; c < headers.size(); c++) {
                    sheet.setDefaultColumnStyle(c, textStyle);
                    sheet.setColumnWidth(c, (c == 0 ? 60 : 28) * 256);
                    header.getCell(c).setCellStyle(headerStyle);
                }
                var anchor = workbook.getCreationHelper().createClientAnchor();
                anchor.setCol1(0); anchor.setCol2(4); anchor.setRow1(0); anchor.setRow2(4);
                var comment = sheet.createDrawingPatriarch().createCellComment(anchor);
                comment.setString(workbook.getCreationHelper().createRichTextString("Obrigatório. Preencha uma descrição por linha, a partir da linha 2. Mantenha os cabeçalhos. Os demais campos são opcionais."));
                comment.setAuthor("Catálogo de serviços");
                header.getCell(0).setCellComment(comment);
            }
            int n = 1;
            for (var values : rows) {
                var row = sheet.createRow(n++);
                for (int c=0; c<values.size(); c++) row.createCell(c).setCellValue(values.get(c));
            }
            sheet.createFreezePane(0, 1);
            workbook.write(out);
            return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                    .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")).body(out.toByteArray());
        }
    }
}
