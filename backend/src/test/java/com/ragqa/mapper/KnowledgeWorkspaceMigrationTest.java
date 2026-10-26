package com.ragqa.mapper;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.*;

/** 比较首建与增量DDL，并在隔离H2执行增量；目标MySQL兼容仍需实库验收。 */
class KnowledgeWorkspaceMigrationTest {
    @Test void freshSchemaAndIncrementalDefinitionsAgree() throws Exception {
        String schema = Files.readString(Path.of("schema.sql"), StandardCharsets.UTF_8);
        String migration = Files.readString(Path.of("sql/migrations/20261019_01_knowledge_workspace.sql"), StandardCharsets.UTF_8);
        for (String table : List.of("rag_knowledge_chat", "rag_knowledge_job", "rag_knowledge_feedback")) {
            var pattern = Pattern.compile("(?s)CREATE TABLE " + table + " \\(.*?\\) ENGINE=InnoDB[^;]*;");
            var original = pattern.matcher(schema); var incremental = pattern.matcher(migration);
            assertThat(original.find()).isTrue(); assertThat(incremental.find()).isTrue();
            assertThat(original.group()).isEqualTo(incremental.group());
        }
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:migration_" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE", "sa", "")) {
            String h2 = migration.replaceAll("(?i)COLLATE\\s*=?\\s*utf8mb4_unicode_ci", "");
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(h2.getBytes(StandardCharsets.UTF_8)));
            var tables = connection.getMetaData().getTables(null, null, "rag_knowledge_%", new String[]{"TABLE"});
            int count = 0; while (tables.next()) count++; assertThat(count).isEqualTo(3);
        }
    }
}
