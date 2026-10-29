package com.example.demo.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

@Service
public class CatalogStore {
    private static final String[] FIELDS = {"grupo_servico", "codigo_fornecedor", "codigo_servico", "descricao_item", "unidade_medida", "unidade_descricao"};
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public CatalogStore(JdbcTemplate db, ObjectMapper json, PlatformTransactionManager manager) {
        this.db = db;
        this.json = json;
        this.tx = new TransactionTemplate(manager);
    }

    // Serializa as alteraÃ§Ãµes do catÃ¡logo tambÃ©m entre duas instÃ¢ncias do Java.
    private void lockCatalog() {
        db.queryForObject("SELECT revision FROM catalog_state WHERE id=1 FOR UPDATE", Long.class);
        if ("initial".equals(db.queryForObject("SELECT dataset_key FROM catalog_state WHERE id=1", String.class))) {
            db.update("UPDATE catalog_state SET dataset_key=? WHERE id=1", UUID.randomUUID().toString());
        }
    }

    public Map<String, String> validate(Map<String, String> input, boolean requireCode) {
        if (input == null) throw new IllegalArgumentException("Informe os dados do serviÃ§o.");
        var clean = new LinkedHashMap<String, String>();
        int[] lengths = {160, 128, 128, 300, 32, 160};
        for (int i = 0; i < FIELDS.length; i++) {
            String value = Objects.toString(input.get(FIELDS[i]), "").strip();
            if (value.length() > lengths[i]) throw new IllegalArgumentException(FIELDS[i] + ": mÃ¡ximo de " + lengths[i] + " caracteres.");
            clean.put(FIELDS[i], value);
        }
        if (clean.get("descricao_item").isBlank()) throw new IllegalArgumentException("Preencha a descriÃ§Ã£o do serviÃ§o.");
        if (requireCode && clean.get("codigo_servico").isBlank()) throw new IllegalArgumentException("Preencha o cÃ³digo do serviÃ§o.");
        clean.put("unidade_medida", clean.get("unidade_medida").toUpperCase(Locale.ROOT));
        return clean;
    }

    private Map<String, String> row(java.sql.ResultSet rs) throws java.sql.SQLException {
        var result = new LinkedHashMap<String, String>();
        result.put("catalog_id", rs.getString("id"));
        for (String field : FIELDS) result.put(field, rs.getString(field));
        result.put("cleaned_at", Objects.toString(rs.getObject("cleaned_at"), ""));
        result.put("cleanup_id", Objects.toString(rs.getString("cleanup_id"), ""));
        result.put("replaced_by", Objects.toString(rs.getString("replaced_by"), ""));
        return result;
    }

    public List<Map<String, String>> all() {
        return db.query("SELECT * FROM catalog_service WHERE replaced_by IS NULL ORDER BY created_at, id", (rs, n) -> row(rs));
    }

    public Map<String, String> get(String id) {
        var seen = new HashSet<String>();
        while (seen.add(id)) {
            var found = db.query("SELECT * FROM catalog_service WHERE id=?", (rs, n) -> row(rs), id);
            if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "ServiÃ§o nÃ£o encontrado.");
            var item = found.getFirst();
            if (item.get("replaced_by").isBlank()) return item;
            id = item.get("replaced_by");
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT, "VÃ­nculo circular no catÃ¡logo.");
    }

    public Map<String, Object> batchItem(String batch, String itemId) {
        var found = db.queryForList("SELECT row_number FROM review_item WHERE batch_id=? AND id=?", batch, itemId);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Linha nÃ£o encontrada.");
        int row = ((Number) found.getFirst().get("row_number")).intValue();
        return batchItems(batch, row - 1, 1).getFirst();
    }

    public Map<String, String> resolveExistingIfRevision(String batch, String item, String candidate, String expectedRevision, String expectedDictionaryRevision) {
        return tx.execute(status -> {
            lockCatalog();
            if (!snapshot().revision().equals(expectedRevision))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "O catÃ¡logo mudou. Compare esta linha novamente.");
            db.queryForObject("SELECT revision FROM abbreviation_state WHERE id=1 FOR UPDATE", Long.class);
            String dictionaryRevision=db.queryForObject("SELECT dataset_key FROM abbreviation_state WHERE id=1",String.class)
                    + ":" + db.queryForObject("SELECT revision FROM abbreviation_state WHERE id=1",Long.class);
            if (!dictionaryRevision.equals(expectedDictionaryRevision))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"O dicionário mudou. Compare esta linha novamente.");
            return resolve(batch, item, "EXISTING", candidate);
        });
    }

    public boolean cleanupApplied(String id) {
        return db.queryForObject("SELECT COUNT(*) FROM catalog_cleanup_run WHERE id=?", Integer.class, id) > 0;
    }

    public void applyCleanup(String id, String expectedRevision, String expectedDictionaryRevision, Map<String, String> representatives) {
        tx.executeWithoutResult(status -> {
            lockCatalog();
            if (cleanupApplied(id)) return; // ConfirmaÃ§Ã£o repetida ou recuperaÃ§Ã£o apÃ³s falha na gravaÃ§Ã£o do arquivo.
            var current = snapshot();
            if (!current.revision().equals(expectedRevision))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A base mudou durante a limpeza. Inicie uma nova cÃ³pia para incluir as alteraÃ§Ãµes.");
            db.queryForObject("SELECT revision FROM abbreviation_state WHERE id=1 FOR UPDATE", Long.class);
            String dictionaryRevision = db.queryForObject("SELECT dataset_key FROM abbreviation_state WHERE id=1", String.class)
                    + ":" + db.queryForObject("SELECT revision FROM abbreviation_state WHERE id=1", Long.class);
            if (!dictionaryRevision.equals(expectedDictionaryRevision))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "O dicionÃ¡rio mudou durante a limpeza. Inicie uma nova cÃ³pia.");
            var ids = new HashSet<String>();
            current.items().forEach(item -> ids.add(item.get("catalog_id")));
            if (!ids.equals(representatives.keySet()) || representatives.values().stream()
                    .anyMatch(target -> !ids.contains(target) || !target.equals(representatives.get(target))))
                throw new IllegalArgumentException("A revisÃ£o estÃ¡ incompleta ou possui vÃ­nculos invÃ¡lidos.");
            for (var entry : representatives.entrySet()) {
                String target = entry.getKey().equals(entry.getValue()) ? null : entry.getValue();
                db.update("UPDATE catalog_service SET cleaned_at=CURRENT_TIMESTAMP,cleanup_id=?,replaced_by=? WHERE id=?", id,target,entry.getKey());
            }
            // Preserva os registros originais e mantÃ©m decisÃµes antigas apontando ao representante atual.
            for (var entry : representatives.entrySet()) {
                if (!entry.getKey().equals(entry.getValue())) {
                    db.update("UPDATE review_item SET service_id=? WHERE service_id=?",entry.getValue(),entry.getKey());
                    db.update("UPDATE catalog_service SET replaced_by=? WHERE replaced_by=?",entry.getValue(),entry.getKey());
                }
            }
            db.update("INSERT INTO catalog_cleanup_run(id,source_revision) VALUES (?,?)",id,expectedRevision);
            db.update("UPDATE catalog_state SET revision=revision+1 WHERE id=1");
        });
    }

    public record Snapshot(String revision, List<Map<String, String>> items) {}

    public Snapshot snapshot() {
        return tx.execute(status -> {
            lockCatalog();
            String revision = db.queryForObject("SELECT dataset_key FROM catalog_state WHERE id=1", String.class)
                    + ":" + db.queryForObject("SELECT revision FROM catalog_state WHERE id=1", Long.class);
            return new Snapshot(revision, all());
        });
    }

    private List<Map<String, String>> sameCode(Map<String, String> fields) {
        return db.query("SELECT * FROM catalog_service WHERE codigo_servico=? AND codigo_fornecedor=?",
                (rs, n) -> row(rs), fields.get("codigo_servico"), fields.get("codigo_fornecedor"));
    }

    private Map<String, String> insert(Map<String, String> fields, String requestKey) {
        if (db.queryForObject("SELECT COUNT(*) FROM catalog_service", Long.class) >= 50_000)
            throw new IllegalArgumentException("O Ã­ndice atual suporta atÃ© 50.000 serviÃ§os no catÃ¡logo.");
        if (fields.get("codigo_servico").isBlank()) {
            // Identificador local do protÃ³tipo; nÃ£o Ã© um cÃ³digo emitido pelo SAP.
            do {
                long number = db.queryForObject("SELECT nextval('local_service_code')", Long.class);
                fields.put("codigo_servico", "LOCAL-%08d".formatted(number));
            } while (!sameCode(fields).isEmpty());
        }
        if (!sameCode(fields).isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "JÃ¡ existe um serviÃ§o com esse cÃ³digo e fornecedor. Selecione o existente.");
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO catalog_service (id, grupo_servico, codigo_fornecedor, codigo_servico, descricao_item, unidade_medida, unidade_descricao, request_key) VALUES (?,?,?,?,?,?,?,?)",
                id, fields.get("grupo_servico"), fields.get("codigo_fornecedor"), fields.get("codigo_servico"),
                fields.get("descricao_item"), fields.get("unidade_medida"), fields.get("unidade_descricao"), requestKey);
        db.update("UPDATE catalog_state SET revision=revision+1 WHERE id=1");
        return get(id);
    }

    public Map<String, String> create(Map<String, String> input, String requestKey) {
        var fields = validate(input, false);
        if (requestKey == null) throw new IllegalArgumentException("Informe request_id para evitar cadastros duplicados em uma repetiÃ§Ã£o.");
        UUID.fromString(requestKey);
        return tx.execute(status -> {
            lockCatalog();
            var previous = db.query("SELECT * FROM catalog_service WHERE request_key=?", (rs, n) -> row(rs), requestKey);
            if (!previous.isEmpty()) {
                var saved = previous.getFirst();
                for (String field : FIELDS) {
                    if (field.equals("codigo_servico") && fields.get(field).isBlank()) continue;
                    if (!fields.get(field).equals(saved.get(field))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Esse request_id jÃ¡ foi usado com outros dados.");
                }
                return get(saved.get("catalog_id"));
            }
            return insert(fields, requestKey);
        });
    }

    // Carga de referÃªncia: acrescenta serviÃ§os, nunca substitui nem apaga o catÃ¡logo SQL.
    public Map<String, Object> importCatalog(List<Map<String, String>> incoming) {
        return importCatalog(incoming, "REFERENCE_XLSX");
    }

    public Map<String, Object> importCatalog(List<Map<String, String>> incoming, String source) {
        if (incoming.isEmpty() || incoming.size() > 50_000) throw new IllegalArgumentException("Envie entre 1 e 50.000 registros.");
        var clean = incoming.stream().map(item -> validate(item, true)).toList();
        return tx.execute(status -> {
            lockCatalog();
            int added = 0;
            for (var item : clean) {
                if (!sameCode(item).isEmpty()) continue;
                insert(item, null);
                added++;
            }
            db.update("INSERT INTO catalog_import_history(id,source,original_items,imported_count,existing_count) VALUES (?,?,?,?,?)",
                    UUID.randomUUID().toString(), source, json.writeValueAsString(incoming), added, incoming.size() - added);
            return Map.of("imported", added, "existing", incoming.size() - added);
        });
    }

    public String createBatch(String filename, LeitorDePlanilha.ParsedRequests parsed) {
        return tx.execute(status -> {
            String batch = UUID.randomUUID().toString();
            db.update("INSERT INTO review_batch(id,filename,sheet_name) VALUES (?,?,?)", batch, filename, parsed.sheet());
            for (var row : parsed.rows()) {
                String error = null;
                try { validate(row.fields(), false); } catch (IllegalArgumentException e) { error = e.getMessage(); }
                db.update("INSERT INTO review_item(id,batch_id,row_number,payload,status,error_message) VALUES (?,?,?,?,?,?)",
                        UUID.randomUUID().toString(), batch, row.rowNumber(), json.writeValueAsString(row.fields()),
                        error == null ? "PENDING" : "INVALID", error);
            }
            return batch;
        });
    }

    public List<Map<String, Object>> batches() {
        return db.query("SELECT b.id,b.filename,b.sheet_name,b.created_at,COUNT(i.id) AS total, SUM(CASE WHEN i.status='PENDING' THEN 1 ELSE 0 END) AS pending, SUM(CASE WHEN i.status='INVALID' THEN 1 ELSE 0 END) AS invalid, SUM(CASE WHEN i.status IN ('EXISTING','CREATED') THEN 1 ELSE 0 END) AS resolved FROM review_batch b LEFT JOIN review_item i ON i.batch_id=b.id GROUP BY b.id,b.filename,b.sheet_name,b.created_at ORDER BY b.created_at DESC", (rs, n) -> {
            var result = new LinkedHashMap<String, Object>();
            result.put("id", rs.getString("id"));
            result.put("filename", rs.getString("filename"));
            result.put("sheet_name", rs.getString("sheet_name"));
            result.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
            for (String field : List.of("total", "pending", "invalid", "resolved")) result.put(field, rs.getLong(field));
            return result;
        });
    }

    public List<Map<String, Object>> batchItems(String batch, int after, int limit) {
        if (db.queryForObject("SELECT COUNT(*) FROM review_batch WHERE id=?", Integer.class, batch) == 0)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Lote nÃ£o encontrado.");
        return db.query("SELECT * FROM review_item WHERE batch_id=? AND row_number>? ORDER BY row_number LIMIT ?", (rs, n) -> {
            var item = new LinkedHashMap<String, Object>();
            item.put("id", rs.getString("id"));
            item.put("row_number", rs.getInt("row_number"));
            item.put("status", rs.getString("status"));
            item.put("error", rs.getString("error_message"));
            item.put("service_id", rs.getString("service_id"));
            item.put("fields", json.readValue(rs.getString("payload"), Map.class));
            return item;
        }, batch, after, limit);
    }

    public Map<String, String> resolve(String batch, String itemId, String action, String existingId) {
        if (!Set.of("EXISTING", "CREATE").contains(Objects.toString(action, ""))) throw new IllegalArgumentException("AÃ§Ã£o deve ser EXISTING ou CREATE.");
        return tx.execute(status -> {
            lockCatalog();
            var found = db.queryForList("SELECT * FROM review_item WHERE id=? AND batch_id=? FOR UPDATE", itemId, batch);
            if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Linha nÃ£o encontrada.");
            var item = found.getFirst();
            String targetStatus = action.equals("CREATE") ? "CREATED" : "EXISTING";
            if (!"PENDING".equals(item.get("status"))) {
                if (targetStatus.equals(item.get("status")) && (action.equals("CREATE") || Objects.equals(existingId, item.get("service_id"))))
                    return get(item.get("service_id").toString());
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta linha nÃ£o estÃ¡ pendente ou jÃ¡ recebeu outra decisÃ£o.");
            }
            Map<String, String> selected;
            if (action.equals("EXISTING")) selected = get(Objects.toString(existingId, ""));
            else {
                @SuppressWarnings("unchecked")
                Map<String, String> fields = json.readValue(item.get("payload").toString(), Map.class);
                selected = insert(validate(fields, false), itemId);
            }
            db.update("UPDATE review_item SET status=?,service_id=?,decided_at=CURRENT_TIMESTAMP WHERE id=?", targetStatus, selected.get("catalog_id"), itemId);
            return selected;
        });
    }
}
