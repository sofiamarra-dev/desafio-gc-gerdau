package com.example.demo.api;

import com.example.demo.service.AbbreviationStore;
import com.example.demo.service.PythonAiClient;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/abbreviations")
public class AbbreviationController {
    private final AbbreviationStore store;
    private final PythonAiClient python;
    public AbbreviationController(AbbreviationStore store, PythonAiClient python) {
        this.store = store; this.python = python;
    }
    public record Create(String code, String meaning, List<String> variants, Boolean active) {}
    public record Edit(String code, String meaning, List<String> variants, Boolean active, Long version) {}
    public record Correction(String meaning, List<String> variants, String reason, Long version, Boolean confirmed) {}
    public record Preview(String text) {}
    @GetMapping public AbbreviationStore.Snapshot list() { return store.snapshot(); }
    private Map<String, Object> saved(AbbreviationStore.Snapshot snapshot) {
        return Map.of("revision", snapshot.revision(), "entries", snapshot.entries(), "index_pending", true);
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody Create request) {
        return saved(store.create(request.code(), request.meaning(), request.variants(), !Boolean.FALSE.equals(request.active())));
    }
    @PutMapping("/{code}")
    public Map<String, Object> update(@PathVariable String code, @RequestBody Edit request) {
        if (request.code() != null || request.meaning() != null)
            throw new IllegalArgumentException("O código e o significado oficial não podem ser alterados. Edite apenas as variantes e a situação.");
        if (request.active() == null) throw new IllegalArgumentException("Informe se a sigla está ativa.");
        return saved(store.update(code, request.variants(), request.active(), request.version()));
    }
    @PostMapping("/{code}/corrections")
    public Map<String, Object> correct(@PathVariable String code, @RequestBody Correction request) {
        if (!Boolean.TRUE.equals(request.confirmed())) throw new IllegalArgumentException("Confirme que as variantes têm o significado informado.");
        return saved(store.correct(code, request.meaning(), request.variants(), request.reason(), request.version()));
    }
    @GetMapping("/{code}/corrections")
    public List<Map<String, Object>> history(@PathVariable String code) { return store.corrections(code); }
    @PostMapping("/preview")
    public JsonNode preview(@RequestBody Preview request) {
        String text = Objects.toString(request.text(), "").strip();
        if (text.isEmpty() || text.length() > 300) throw new IllegalArgumentException("Digite uma descrição de até 300 caracteres.");
        return python.previewDictionary(store.snapshot(), text);
    }
}
