package com.example.demo.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.junit.jupiter.api.AfterEach;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class HeAliasMigrationTests {
    final List<SingleConnectionDataSource> sources = new ArrayList<>();
    @AfterEach void closeConnections() { sources.forEach(SingleConnectionDataSource::destroy); }
    String url() { return "jdbc:h2:mem:he"+UUID.randomUUID().toString().replace("-", "")+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1"; }
    JdbcTemplate setup(String url) {
        var source = new SingleConnectionDataSource(url,"sa","",true);
        sources.add(source);
        Flyway.configure().dataSource(source).target("4").load().migrate();
        return new JdbcTemplate(source);
    }
    @Test void addsDottedAliasesAndInvalidatesRevisionOnlyOnce() {
        String url=url();var db=setup(url);
        Flyway.configure().dataSource(db.getDataSource()).load().migrate();
        assertEquals("HE",db.queryForObject("SELECT code FROM abbreviation_alias WHERE normalized='h . extra'",String.class));
        assertEquals("HE",db.queryForObject("SELECT code FROM abbreviation_alias WHERE normalized='h . extras'",String.class));
        assertEquals(2,db.queryForObject("SELECT version FROM abbreviation WHERE code='HE'",Integer.class));
        assertEquals(1,db.queryForObject("SELECT revision FROM abbreviation_state WHERE id=1",Integer.class));
        Flyway.configure().dataSource(db.getDataSource()).load().migrate();
        assertEquals(1,db.queryForObject("SELECT revision FROM abbreviation_state WHERE id=1",Integer.class));
    }
    @Test void preservesInactiveOrCorrectedHeAndOtherOwners() {
        for (String change : new String[]{"UPDATE abbreviation SET active=FALSE WHERE code='HE'", "UPDATE abbreviation SET meaning='outro significado' WHERE code='HE'"}) {
            String url=url();var db=setup(url);db.update(change);
            Flyway.configure().dataSource(db.getDataSource()).load().migrate();
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM abbreviation_alias WHERE normalized='h . extra'",Integer.class));
            assertEquals(0,db.queryForObject("SELECT revision FROM abbreviation_state WHERE id=1",Integer.class));
        }
        String url=url();var db=setup(url);
        db.update("INSERT INTO abbreviation(code,meaning) VALUES ('XX','outro significado')");
        db.update("INSERT INTO abbreviation_alias(normalized,code,expression,kind) VALUES ('h . extra','XX','H.EXTRA','VARIANT')");
        Flyway.configure().dataSource(db.getDataSource()).load().migrate();
        assertEquals("XX",db.queryForObject("SELECT code FROM abbreviation_alias WHERE normalized='h . extra'",String.class));
        assertEquals("HE",db.queryForObject("SELECT code FROM abbreviation_alias WHERE normalized='h . extras'",String.class));
    }
}
