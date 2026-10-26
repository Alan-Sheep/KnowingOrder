package com.ragqa.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.dto.KnowledgeAnswers.Evidence;
import com.ragqa.exception.BusinessException;
import com.ragqa.rag.chat.TaskDeadline;
import com.ragqa.rag.retrieval.KeywordKnowledgeRetriever;
import com.ragqa.service.impl.CurrentUserServiceImpl;
import com.ragqa.service.impl.EnterpriseMemberServiceImpl;
import com.ragqa.service.impl.EnterpriseResourceServiceImpl;
import com.ragqa.support.EnterpriseDatabase;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 固定虚构语料执行实际授权服务与检索器；H2召回基线不代表MySQL、模型或生产性能验收。 */
class KnowledgeGoldenSetTest {
    private final ObjectMapper json = new ObjectMapper();

    record Corpus(String version, String description, List<Document> documents, List<Sample> cases) { }
    record Document(long id, long spaceId, String state, String title, String content) { }
    record ExpectedPart(String question, String status, List<Long> evidenceIds,
            List<String> mustNotConclude, String gap) { }
    record Sample(String id, String category, String question, long spaceId, String member,
            String change, int expectedError, List<ExpectedPart> parts) { }

    @Test
    void measureAuthorizedRecallWithoutInventingModelQuality() throws Exception {
        byte[] snapshot;
        try (var input = getClass().getResourceAsStream("/eval/knowledge-v1.json")) {
            snapshot = java.util.Objects.requireNonNull(input).readAllBytes();
        }
        Corpus corpus = json.readValue(snapshot, Corpus.class);
        validate(corpus);
        var rows = new ArrayList<Map<String, Object>>();
        for (Sample sample : corpus.cases()) {
            rows.add(evaluate(corpus.documents(), sample));
        }
        var metrics = summarize(rows);
        // 先保存全部题和失败原因，再判安全门槛；不通过删除坏题或只统计成功题提高分数。
        Instant evaluatedAt = Instant.now();
        Path report = Path.of(System.getProperty("knowledge.eval.report",
                "E:/Codex/test-artifacts/knowledge-golden-v1/retrieval-"
                        + evaluatedAt.toString().replace(':', '-') + ".json"));
        Files.createDirectories(report.toAbsolutePath().getParent());
        String content = json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "datasetVersion", corpus.version(),
                "datasetSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(snapshot)),
                "sourceSha256", sourceHashes(),
                "evaluatedAt", evaluatedAt.toString(), "engine", "H2 + KeywordKnowledgeRetriever",
                "modelCalls", 0, "answerQuality", "NOT_RUN", "metrics", metrics, "cases", rows,
                "limitations", "只评估授权检索。预期状态未由模型验证；H2耗时不作生产P95，质量阈值另行确认。"));
        Files.writeString(report, content.replace("\r\n", "\n") + "\n", StandardCharsets.UTF_8);
        System.out.println("企业合成检索基线：" + json.writeValueAsString(metrics));
        System.out.println("评估报告：" + report.toAbsolutePath());
        assertThat(rows).as("安全检查失败，逐题证据见评估报告")
                .allSatisfy(row -> assertThat(row.get("safetyErrors")).isEqualTo(List.of()));
    }

    private Map<String, Object> evaluate(List<Document> documents, Sample sample) throws Exception {
        var row = new LinkedHashMap<String, Object>();
        var safetyErrors = new ArrayList<String>();
        List<Long> actual = List.of();
        int errorCode = 0;
        String failure = "";
        long started = System.nanoTime();
        try (var db = new EnterpriseDatabase()) {
            db.seed();
            db.factory.getConfiguration().addMapper(KnowledgeRetrievalMapper.class);
            for (Document document : documents) seed(db, document);
            if (sample.change().equals("REVOKE")) {
                db.jdbc.update("UPDATE rag_knowledge_grant SET status=1 WHERE knowledge_space_id=401");
            }
            var members = new EnterpriseMemberServiceImpl(new CurrentUserServiceImpl(
                    db.session.getMapper(SysUserMapper.class)), db.session.getMapper(EnterpriseDirectoryMapper.class));
            var resources = new EnterpriseResourceServiceImpl(members, db.session.getMapper(EnterpriseResourceMapper.class));
            var retriever = new KeywordKnowledgeRetriever(db.session.getMapper(KnowledgeRetrievalMapper.class), resources);
            var member = sample.member().equals("OWNER")
                    ? new EnterpriseMemberContext(1, 101, 1, 11L, "EMPLOYEE", 1, 1, 1)
                    : new EnterpriseMemberContext(1, 102, 2, null, "EMPLOYEE", 1, 1, 1);
            try {
                List<Evidence> evidence = retriever.retrieve(member, sample.spaceId(), sample.question(),
                        TaskDeadline.start(Duration.ofSeconds(10)));
                actual = evidence.stream().map(item -> item.chunkId() - 3000).toList();
                if (actual.size() > 6 || actual.stream().distinct().count() != actual.size()) {
                    safetyErrors.add("片段数量或重复约束异常");
                }
                for (Evidence item : evidence) {
                    Document fixture = documents.stream().filter(doc -> doc.id() == item.chunkId() - 3000)
                            .findFirst().orElseThrow();
                    if (!fixture.state().equals("LIVE") || fixture.spaceId() != sample.spaceId()
                            || !fixture.content().equals(item.content()) || item.versionId() != fixture.id() + 2000
                            || item.documentId() != fixture.id() + 1000) {
                        safetyErrors.add("检索到禁止版本、越界空间或错误内容：" + fixture.id());
                    }
                    if (!retriever.requireEvidence(member, sample.spaceId(), item.chunkId()).equals(item)) {
                        safetyErrors.add("引用二次核权不一致：" + fixture.id());
                    }
                }
            } catch (BusinessException exception) {
                errorCode = exception.getCode();
            }
        } catch (Exception exception) {
            // 故障保留在分母；只写类型，不保存可能包含连接地址或响应正文的异常消息。
            errorCode = 500;
            failure = exception.getClass().getSimpleName();
        }
        if (errorCode != sample.expectedError()) safetyErrors.add("错误码不符：" + errorCode);
        if (sample.expectedError() != 0 && !actual.isEmpty()) safetyErrors.add("拒绝访问时仍返回片段");
        List<Long> expected = sample.parts().stream().flatMap(part -> part.evidenceIds().stream()).distinct().toList();
        List<Long> found = actual;
        List<Long> missing = expected.stream().filter(id -> !found.contains(id)).toList();
        row.put("id", sample.id());
        row.put("category", sample.category());
        row.put("question", sample.question());
        row.put("queryTerms", KeywordKnowledgeRetriever.terms(sample.question()));
        row.put("spaceId", sample.spaceId());
        row.put("member", sample.member());
        row.put("change", sample.change());
        row.put("expectedError", sample.expectedError());
        row.put("actualError", errorCode);
        row.put("expectedParts", sample.parts());
        row.put("expectedEvidenceIds", expected);
        row.put("retrievedEvidenceIds", actual);
        row.put("missingEvidenceIds", missing);
        row.put("relevantFound", expected.size() - missing.size());
        row.put("retrievalComplete", errorCode == 0 && missing.isEmpty());
        row.put("safetyErrors", safetyErrors);
        row.put("failureType", failure);
        row.put("elapsedMsIncludingFixture", Duration.ofNanos(System.nanoTime() - started).toMillis());
        row.put("answerQuality", "NOT_RUN");
        return row;
    }

    private Map<String, Object> summarize(List<Map<String, Object>> rows) {
        int relevant = 0, found = 0, positive = 0, full = 0, any = 0, denied = 0, deniedCorrect = 0;
        int noEvidence = 0, withCandidates = 0, errors = 0;
        for (Map<String, Object> row : rows) {
            int size = ((List<?>) row.get("expectedEvidenceIds")).size();
            int hits = (int) row.get("relevantFound");
            if ((int) row.get("expectedError") != 0) {
                denied++;
                if (row.get("safetyErrors").equals(List.of())) deniedCorrect++;
            } else if (size > 0) {
                positive++;
                relevant += size;
                found += hits;
                if (hits > 0) any++;
                if (row.get("retrievalComplete").equals(true)) full++;
            } else {
                noEvidence++;
                if (!row.get("retrievedEvidenceIds").equals(List.of())) withCandidates++;
            }
            if (!row.get("safetyErrors").equals(List.of())) errors++;
        }
        return Map.of("totalCases", rows.size(), "positiveCases", positive,
                "relevantEvidenceRecallAt6", counts(found, relevant), "anyEvidenceHit", counts(any, positive),
                "allEvidenceHit", counts(full, positive), "accessDenied", counts(deniedCorrect, denied),
                // 无依据但有候选不是错误：需要后续真实生成判不足，不能把候选非空当作可回答。
                "noEvidenceCasesWithCandidates", counts(withCandidates, noEvidence), "safetyFailureCases", errors);
    }

    private Map<String, Object> counts(int numerator, int denominator) {
        return Map.of("numerator", numerator, "denominator", denominator,
                "ratio", denominator == 0 ? 0 : (double) numerator / denominator);
    }

    private Map<String, String> sourceHashes() throws Exception {
        var hashes = new LinkedHashMap<String, String>();
        for (String file : List.of("schema.sql", "src/main/java/com/ragqa/mapper/KnowledgeRetrievalMapper.java",
                "src/main/java/com/ragqa/mapper/EnterpriseResourceMapper.java",
                "src/main/java/com/ragqa/rag/retrieval/KeywordKnowledgeRetriever.java",
                "src/test/java/com/ragqa/mapper/KnowledgeGoldenSetTest.java")) {
            hashes.put(file, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(file)))));
        }
        return hashes;
    }

    private void validate(Corpus corpus) {
        assertThat(corpus.version()).isEqualTo("knowledge-synthetic-v1");
        assertThat(corpus.documents()).hasSize(20).extracting(Document::id).doesNotHaveDuplicates();
        assertThat(corpus.cases()).hasSize(24).extracting(Sample::id).doesNotHaveDuplicates();
        for (Document document : corpus.documents()) {
            assertThat(document.state()).isIn("LIVE", "EXPIRED", "WITHDRAWN", "FUTURE", "DRAFT", "FAILED", "SUPERSEDED", "DELETED");
            assertThat(document.content()).isNotBlank().hasSizeLessThanOrEqualTo(1000);
        }
        for (Sample sample : corpus.cases()) {
            assertThat(sample.question()).isNotBlank();
            assertThat(sample.member()).isIn("OWNER", "PEER");
            assertThat(sample.change()).isIn("NONE", "REVOKE");
            assertThat(sample.expectedError()).isIn(0, 404);
            if (sample.expectedError() != 0) {
                assertThat(sample.parts()).isEmpty();
                continue;
            }
            assertThat(sample.parts()).hasSizeBetween(1, 3);
            assertThat(sample.parts().stream().map(ExpectedPart::question).reduce("", String::concat))
                    .isEqualTo(sample.question());
            for (ExpectedPart part : sample.parts()) {
                assertThat(part.status()).isIn("ANSWERED", "PARTIAL", "NO_EVIDENCE", "CONFLICT");
                assertThat(part.mustNotConclude()).isNotEmpty();
                if (part.status().equals("NO_EVIDENCE")) assertThat(part.evidenceIds()).isEmpty();
                else assertThat(part.evidenceIds()).isNotEmpty();
                if (part.status().equals("CONFLICT")) assertThat(part.evidenceIds()).hasSizeGreaterThanOrEqualTo(2);
                if (part.status().equals("ANSWERED")) assertThat(part.gap()).isEmpty();
                else assertThat(part.gap()).isNotBlank();
                for (long id : part.evidenceIds()) {
                    assertThat(corpus.documents()).anySatisfy(document -> {
                        assertThat(document.id()).isEqualTo(id);
                        assertThat(document.spaceId()).isEqualTo(sample.spaceId());
                        assertThat(document.state()).isEqualTo("LIVE");
                    });
                }
            }
        }
    }

    /** 同包离线对照复用完全相同的语料落库方式，避免另造一套版本映射。 */
    static void seed(EnterpriseDatabase db, Document document) {
        long enterprise = document.spaceId() == 403 ? 2 : 1;
        long owner = enterprise == 2 ? 201 : 101;
        // 旧90元版与当前30元版属于同一逻辑文档，发布指针只指向当前版。
        long documentId = document.state().equals("SUPERSEDED") ? 1001 : 1000 + document.id();
        long versionId = 2000 + document.id();
        String publication = Set.of("DRAFT", "WITHDRAWN").contains(document.state()) ? document.state() : "PUBLISHED";
        if (!document.state().equals("SUPERSEDED")) db.jdbc.update("""
                INSERT INTO rag_document(id,enterprise_id,knowledge_space_id,title,source_type,
                    owner_member_id,published_version_id,deleted) VALUES(?,?,?,?,'UPLOAD',?,?,?)
                """, documentId, enterprise, document.spaceId(), document.title(), owner,
                versionId, document.state().equals("DELETED") ? 1 : 0);
        db.jdbc.update("""
                INSERT INTO rag_document_version(id,enterprise_id,knowledge_space_id,document_id,version_no,
                    upload_request_id,original_name,storage_key,content_sha256,request_sha256,file_size,
                    created_by_member,parse_status,publication_status,chunk_count,valid_from_ms,valid_until_ms)
                VALUES(?,?,?,?,?,?,'虚构制度.md',?,?,?,100,?,?,?,1,?,?)
                """, versionId, enterprise, document.spaceId(), documentId, document.id() == 1 ? 2 : 1, UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), "a".repeat(64), "b".repeat(64), owner,
                document.state().equals("FAILED") ? "FAILED" : "READY", publication,
                document.state().equals("FUTURE") ? Long.MAX_VALUE - 1 : null,
                document.state().equals("EXPIRED") ? 1L : null);
        db.jdbc.update("""
                INSERT INTO rag_chunk(id,document_id,version_id,chunk_index,content,start_offset,end_offset,start_line,end_line)
                VALUES(?,?,?,0,?,0,?,1,1)
                """, 3000 + document.id(), documentId, versionId, document.content(), document.content().length());
    }
}
