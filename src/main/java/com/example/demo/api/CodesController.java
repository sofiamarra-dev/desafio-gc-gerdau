package com.example.demo.api;

import com.example.demo.service.LeitorDePlanilha;
import com.example.demo.service.PythonAiClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class CodesController {
    private final LeitorDePlanilha leitorDePlanilha;
    private final PythonAiClient pythonAiClient;

    public CodesController(LeitorDePlanilha spreadsheetParser, PythonAiClient pythonAiClient) {
        this.leitorDePlanilha = spreadsheetParser;
        this.pythonAiClient = pythonAiClient;
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)

    public Map<String, Object> importXlsx(@RequestParam("file") MultipartFile file) throws IOException {

        if (file.isEmpty()) throw new IllegalArgumentException("selecione uma planilha xlsx válida.");

        var parsed = leitorDePlanilha.parse(file);

        JsonNode indexed = pythonAiClient.index(parsed.items());

        Map<String, Object> response = new LinkedHashMap<>();

        response.put("sheet", parsed.sheet());
        response.put("imported", indexed.get("imported").asInt());
        response.put("skipped", parsed.skippedRows());
        response.put("mode", indexed.get("mode").asString());
        response.put("model", indexed.get("model").asString());

        return response;
    }

    public record SearchInput(String query) {}

    @PostMapping("/search")
    public JsonNode search(@RequestBody SearchInput input) {
        if (input == null || input.query() == null || input.query().isBlank()) {
            throw new IllegalArgumentException("Digite um código ou uma descrição para pesquisar.");
        }
        if (input.query().length() > 300) {
            throw new IllegalArgumentException("A pesquisa deve ter até 300 caracteres.");
        }
        return pythonAiClient.search(input.query().strip());
    }

    @GetMapping("/status")
    public JsonNode status() {
        return pythonAiClient.status();
    }

}
