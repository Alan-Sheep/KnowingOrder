package com.ragqa.mapper;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.*;

/** 首建/增量逐字比较并执行隔离H2；不代表真实MySQL迁移验收。 */
class KnowledgeIngestJobMigrationTest {
    @Test void definitionsAndGateAgreeAndMigrationEnforcesBounds() throws Exception {
        String schema=Files.readString(Path.of("schema.sql"),StandardCharsets.UTF_8);
        String migration=Files.readString(Path.of("sql/migrations/20261019_03_knowledge_ingest_jobs.sql"),StandardCharsets.UTF_8);
        for (String table:List.of("rag_ingest_gate","rag_ingest_job")) {
            Pattern p=Pattern.compile("(?s)CREATE TABLE "+table+" \\(.*?\\) ENGINE=InnoDB[^;]*;");
            var a=p.matcher(schema); var b=p.matcher(migration); assertThat(a.find()).isTrue(); assertThat(b.find()).isTrue(); assertThat(a.group()).isEqualTo(b.group());
        }
        assertThat(migration).doesNotContain("FOREIGN KEY","ALTER TABLE","DROP TABLE");
        try(var connection=DriverManager.getConnection("jdbc:h2:mem:ingest_migration_"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE","sa","")) {
            ScriptUtils.executeSqlScript(connection,new ByteArrayResource(migration.replaceAll("(?i)COLLATE\\s*=?\\s*utf8mb4_unicode_ci","").getBytes(StandardCharsets.UTF_8)));
            try(var stmt=connection.createStatement()) {
                assertThatThrownBy(() -> stmt.executeUpdate("INSERT INTO rag_ingest_gate VALUES(2)")).isInstanceOf(java.sql.SQLException.class);
                var result=stmt.executeQuery("SELECT COUNT(*) FROM rag_ingest_gate WHERE id=1"); result.next(); assertThat(result.getInt(1)).isEqualTo(1);
            }
        }
    }
}
