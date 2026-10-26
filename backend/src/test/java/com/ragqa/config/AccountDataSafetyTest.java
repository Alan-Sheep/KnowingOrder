package com.ragqa.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.common.aspect.NoPayloadLog;
import com.ragqa.controller.AccountController;
import com.ragqa.controller.EnterpriseAccountController;
import com.ragqa.controller.EnterpriseOrganizationController;
import com.ragqa.controller.EnterpriseResourceController;
import com.ragqa.controller.KnowledgeDocumentController;
import com.ragqa.controller.KnowledgeIngestJobController;
import com.ragqa.controller.KnowledgeManagementController;
import com.ragqa.controller.KnowledgeWorkspaceController;
import com.ragqa.domain.dto.NewEmployeeRequest;
import com.ragqa.domain.entity.SysUser;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AccountDataSafetyTest {
    @Test
    void passwordAndHashNeverAppearInDtoJsonOrLogStringAndCredentialsCanBeErased() throws Exception {
        var json = new ObjectMapper();
        var request = new NewEmployeeRequest("test_name", "synthetic-not-a-real-secret", null);
        assertThat(json.writeValueAsString(request)).doesNotContain(request.password(), "password");
        assertThat(request.toString()).doesNotContain(request.password());
        var user = new SysUser();
        user.setId(1L);
        user.setAuthVersion(1L);
        user.setUsername("test_name");
        user.setPasswordHash("synthetic-hash-only-for-tests");
        assertThat(json.writeValueAsString(user)).doesNotContain(user.getPasswordHash(), "passwordHash");
        assertThat(user.toString()).doesNotContain(user.getPasswordHash());
        var details = new AccountDetails(user);
        assertThat(details.toString()).isEqualTo("AccountDetails(userId=1)");
        details.eraseCredentials();
        assertThat(details.getPassword()).isNull();
    }

    @Test
    void everyNewSensitiveControllerEntrySkipsPayloadLogging() {
        for (Class<?> controller : List.of(AccountController.class, EnterpriseAccountController.class, EnterpriseOrganizationController.class, EnterpriseResourceController.class,
                KnowledgeDocumentController.class, KnowledgeIngestJobController.class, KnowledgeManagementController.class, KnowledgeWorkspaceController.class)) {
            for (var method : controller.getDeclaredMethods()) {
                if (method.isAnnotationPresent(GetMapping.class) || method.isAnnotationPresent(PostMapping.class)) {
                    assertThat(method.isAnnotationPresent(NoPayloadLog.class)).as(method.toString()).isTrue();
                }
            }
        }
    }

    @Test
    void nonWebBootstrapCanCreatePasswordEncoderWithoutRequiringServletSecurityChain() {
        new ApplicationContextRunner().withUserConfiguration(SecurityConfig.class).run(context -> {
            assertThat(context).hasSingleBean(org.springframework.security.crypto.password.PasswordEncoder.class);
            assertThat(context).doesNotHaveBean(SecurityFilterChain.class);
        });
    }

    @Test
    void baselineAndIncrementalEnterpriseTablesStayIdenticalAndHaveNoPhysicalForeignKeys() throws Exception {
        String baseline = Files.readString(Path.of("schema.sql"), StandardCharsets.UTF_8);
        String migration = Files.readString(Path.of("sql/migrations/20261017_03_enterprise_membership.sql"), StandardCharsets.UTF_8);
        // 逐表严格比较，避免将后续新增的版本表误算进历史企业迁移。
        for (String table : List.of("sys_enterprise", "sys_department", "sys_enterprise_member", "rag_knowledge_space",
                "rag_knowledge_grant", "rag_task_reference")) assertThat(definition(baseline, table)).isEqualTo(definition(migration, table));
        assertThat(migration).doesNotContain("FOREIGN KEY", "USE rag_qa", "UPDATE rag_document", "UPDATE rag_conversation");
        String ingest = Files.readString(Path.of("sql/migrations/20261018_01_knowledge_ingest.sql"), StandardCharsets.UTF_8);
        assertThat(definition(baseline, "rag_document_version")).isEqualTo(definition(ingest, "rag_document_version"));
        assertThat(baseline).doesNotContain("FOREIGN KEY");
        assertThat(ingest).doesNotContain("FOREIGN KEY", "USE rag_qa", "UPDATE rag_document", "UPDATE rag_chunk");
    }

    private String definition(String sql, String table) {
        var matcher = java.util.regex.Pattern.compile("(?s)CREATE TABLE " + table + " \\(.*?\\) ENGINE=InnoDB[^;]*;").matcher(sql);
        assertThat(matcher.find()).as("必须存在完整建表定义：%s", table).isTrue();
        return matcher.group();
    }
}
