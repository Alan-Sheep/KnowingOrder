package com.ragqa.mapper;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** 仅验证首建/增量一致和H2可执行，真实MySQL迁移需另验。 */
class KnowledgeContextMigrationTest {
    @Test void incrementalDefinitionMatchesFreshSchema() throws Exception {
        String schema = Files.readString(Path.of("schema.sql"), StandardCharsets.UTF_8);
        String migration = Files.readString(Path.of("sql/migrations/20261019_02_knowledge_context.sql"), StandardCharsets.UTF_8);
        var pattern = Pattern.compile("(?s)CREATE TABLE rag_knowledge_context \\(.*?\\) ENGINE=InnoDB[^;]*;");
        var fresh = pattern.matcher(schema); var increment = pattern.matcher(migration);
        assertThat(fresh.find()).isTrue(); assertThat(increment.find()).isTrue();
        assertThat(fresh.group()).isEqualTo(increment.group());
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:context_migration;MODE=MySQL", "sa", "")) {
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(
                    migration.replace("COLLATE=utf8mb4_unicode_ci", "").getBytes(StandardCharsets.UTF_8)));
            assertThat(connection.getMetaData().getImportedKeys(null, null, "RAG_KNOWLEDGE_CONTEXT").next()).isFalse();
        }
    }
}
