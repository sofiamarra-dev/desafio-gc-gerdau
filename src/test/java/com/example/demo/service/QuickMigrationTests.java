package com.example.demo.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class QuickMigrationTests {
    @Test void upgradingExistingDatabasePreservesVerifiedAndPendingCatalogAndOldBatchMode() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:quickmigration;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        Flyway.configure().dataSource(source).target("6").load().migrate();
        var db=new JdbcTemplate(source);
        db.update("INSERT INTO catalog_service(id,codigo_servico,descricao_item,cleaned_at) VALUES ('old','A','Verificado',CURRENT_TIMESTAMP)");
        db.update("INSERT INTO catalog_service(id,codigo_servico,descricao_item) VALUES ('pending','B','Pendente')");
        db.update("INSERT INTO review_batch(id,filename,sheet_name) VALUES ('batch','Anterior','Pedidos')");
        Flyway.configure().dataSource(source).load().migrate();
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM catalog_service",Integer.class));
        assertEquals(1,db.queryForObject("SELECT cleanup_passes FROM catalog_service WHERE id='old'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT cleanup_passes FROM catalog_service WHERE id='pending'",Integer.class));
        assertEquals(1,db.queryForObject("SELECT required_cleanup_passes FROM catalog_service WHERE id='old'",Integer.class));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM catalog_service WHERE cleaned_at IS NOT NULL",Integer.class));
        assertEquals("NORMAL",db.queryForObject("SELECT creation_mode FROM review_batch WHERE id='batch'",String.class));
    }
}
