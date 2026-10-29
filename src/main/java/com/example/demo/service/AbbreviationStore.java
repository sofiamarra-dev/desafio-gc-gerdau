package com.example.demo.service;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class AbbreviationStore {
    public record Entry(String code, String meaning, List<String> variants, boolean active, long version) {}
    public record Snapshot(String revision, List<Entry> entries) {}
    private final tools.jackson.databind.ObjectMapper json;
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}_]+|[^\\p{L}\\p{N}_\\s]", Pattern.UNICODE_CHARACTER_CLASS);

    public AbbreviationStore(JdbcTemplate db, PlatformTransactionManager manager, tools.jackson.databind.ObjectMapper json) {
        this.db = db; this.json = json;
        tx = new TransactionTemplate(manager);
    }

    // Compatível com as chaves do separador Python: acentos/caixa/espaços não criam variantes distintas.
    public static String normalize(String text) {
        String clean = Normalizer.normalize(text, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replace("ß", "ss").replace('ς', 'σ');
        var matcher = TOKEN.matcher(clean);
        var tokens = new ArrayList<String>();
        while (matcher.find()) tokens.add(matcher.group());
        return String.join(" ", tokens);
    }

    private static String text(String value, String label, int limit) {
        if (value == null || value.isBlank() || value.strip().length() > limit)
            throw new IllegalArgumentException(label + ": preencha entre 1 e " + limit + " caracteres.");
        String result = value.strip();
        if (result.codePoints().noneMatch(Character::isLetterOrDigit) || result.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(label + ": use uma expressão com letras ou números, sem quebras de linha.");
        return result;
    }

    private void lock() {
        db.queryForObject("SELECT revision FROM abbreviation_state WHERE id=1 FOR UPDATE", Long.class);
        if ("initial".equals(db.queryForObject("SELECT dataset_key FROM abbreviation_state WHERE id=1", String.class)))
            db.update("UPDATE abbreviation_state SET dataset_key=? WHERE id=1", UUID.randomUUID().toString());
    }

    private Entry entry(String code) {
        var found = db.query("SELECT * FROM abbreviation WHERE code=?", (rs, n) ->
                new Entry(rs.getString("code"), rs.getString("meaning"), List.of(), rs.getBoolean("active"), rs.getLong("version")), code);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sigla não encontrada.");
        var item = found.getFirst();
        var variants = db.query("SELECT expression FROM abbreviation_alias WHERE code=? AND kind='VARIANT' ORDER BY normalized",
                (rs, n) -> rs.getString(1), code);
        return new Entry(code, item.meaning(), variants, item.active(), item.version());
    }

    private Snapshot snapshotLocked() {
        String revision = db.queryForObject("SELECT dataset_key FROM abbreviation_state WHERE id=1", String.class)
                + ":" + db.queryForObject("SELECT revision FROM abbreviation_state WHERE id=1", Long.class);
        var entries = db.query("SELECT code FROM abbreviation ORDER BY code", (rs,n) -> rs.getString(1))
                .stream().map(this::entry).toList();
        return new Snapshot(revision, entries);
    }

    public Snapshot snapshot() {
        return tx.execute(status -> { lock(); return snapshotLocked(); });
    }

    private record Alias(String expression, String kind) {}
    private Map<String, Alias> aliases(String code, String meaning, List<String> variants) {
        if (variants == null || variants.size() > 100)
            throw new IllegalArgumentException("Informe até 100 variantes, uma por linha.");
        for (String phrase : java.util.stream.Stream.concat(java.util.stream.Stream.of(code, meaning), variants.stream()).toList()) {
            String compact = normalize(Objects.toString(phrase, "")).replaceAll("[^\\p{L}\\p{N}]", "");
            if (compact.equalsIgnoreCase("ESP") || compact.equalsIgnoreCase("ADM"))
                throw new IllegalArgumentException("ESP e ADM isolados são ambíguos. Preserve-os na atividade; cadastre apenas expressões completas e confirmadas de qualificadores.");
        }
        var result = new LinkedHashMap<String, Alias>();
        result.put(normalize(code), new Alias(code, "CODE"));
        result.putIfAbsent(normalize(meaning), new Alias(meaning, "MEANING"));
        for (String value : variants) {
            String variant = text(value, "Variante", 80);
            result.putIfAbsent(normalize(variant), new Alias(variant, "VARIANT"));
        }
        for (var alias : result.entrySet()) {
            var owners = db.query("SELECT code FROM abbreviation_alias WHERE normalized=?",
                    (rs,n) -> rs.getString(1), alias.getKey());
            if (!owners.isEmpty() && !owners.getFirst().equals(code))
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "A expressão ‘" + alias.getValue().expression() + "’ já pertence à sigla " + owners.getFirst() + ".");
        }
        return result;
    }

    private void saveAliases(String code, Map<String, Alias> aliases) {
        db.update("DELETE FROM abbreviation_alias WHERE code=?", code);
        for (var item : aliases.entrySet())
            db.update("INSERT INTO abbreviation_alias(normalized,code,expression,kind) VALUES (?,?,?,?)",
                    item.getKey(), code, item.getValue().expression(), item.getValue().kind());
    }

    public Snapshot create(String code, String meaning, List<String> variants, boolean active) {
        String canonical = text(code, "Sigla", 16).toUpperCase(Locale.ROOT);
        if (!canonical.matches("[A-Z0-9]{1,16}")) throw new IllegalArgumentException("Use apenas letras e números na sigla.");
        String official = text(meaning, "Significado", 80);
        return tx.execute(status -> {
            lock();
            if (db.queryForObject("SELECT COUNT(*) FROM abbreviation WHERE code=?", Integer.class, canonical) > 0)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A sigla " + canonical + " já existe. Edite suas variantes.");
            if (db.queryForObject("SELECT COUNT(*) FROM abbreviation", Integer.class) >= 1000)
                throw new IllegalArgumentException("Limite de 1.000 siglas atingido.");
            var aliases = aliases(canonical, official, variants);
            db.update("INSERT INTO abbreviation(code,meaning,active,version) VALUES (?,?,?,1)", canonical, official, active);
            saveAliases(canonical, aliases);
            db.update("UPDATE abbreviation_state SET revision=revision+1 WHERE id=1");
            return snapshotLocked();
        });
    }

    public Snapshot update(String code, List<String> variants, boolean active, Long version) {
        return tx.execute(status -> {
            lock();
            var current = entry(code);
            if (version == null || version != current.version())
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta sigla foi alterada em outra tela. Recarregue a lista antes de salvar.");
            var aliases = aliases(code, current.meaning(), variants);
            saveAliases(code, aliases);
            db.update("UPDATE abbreviation SET active=?,version=version+1 WHERE code=?", active, code);
            db.update("UPDATE abbreviation_state SET revision=revision+1 WHERE id=1");
            return snapshotLocked();
        });
    }
    public Snapshot correct(String code, String meaning, List<String> variants, String reason, Long version) {
        String official = text(meaning, "Significado", 80);
        String explanation = text(reason, "Motivo", 500);
        if (explanation.length() < 10) throw new IllegalArgumentException("Explique a correção em pelo menos 10 caracteres.");
        return tx.execute(status -> {
            lock();
            var before = entry(code);
            if (version == null || version != before.version())
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta sigla foi alterada em outra tela. Recarregue a lista antes de corrigir.");
            var proposed = aliases(code, official, variants);
            if (official.equals(before.meaning()) && variants.equals(before.variants()))
                throw new IllegalArgumentException("Altere o significado ou as variantes para registrar uma correção.");
            saveAliases(code, proposed);
            db.update("UPDATE abbreviation SET meaning=?,version=version+1 WHERE code=?", official, code);
            var after = entry(code);
            db.update("INSERT INTO abbreviation_correction(id,code,before_entry,after_entry,reason) VALUES (?,?,?,?,?)",
                    UUID.randomUUID().toString(), code, json.writeValueAsString(before), json.writeValueAsString(after), explanation);
            db.update("UPDATE abbreviation_state SET revision=revision+1 WHERE id=1");
            return snapshotLocked();
        });
    }

    public List<Map<String, Object>> corrections(String code) {
        return tx.execute(status -> {
            lock(); entry(code);
            return db.query("SELECT * FROM abbreviation_correction WHERE code=? ORDER BY created_at DESC,id", (rs,n) -> {
                Map<String,Object> item = new LinkedHashMap<>();
                item.put("before", json.readTree(rs.getString("before_entry")));
                item.put("after", json.readTree(rs.getString("after_entry")));
                item.put("reason", rs.getString("reason"));
                item.put("created_at", rs.getTimestamp("created_at").toInstant().toString());
                return item;
            }, code);
        });
    }

}
