package com.ragqa.mapper;

import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.rag.retrieval.KeywordKnowledgeRetriever;
import com.ragqa.support.EnterpriseDatabase;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** 隔离H2执行真实授权SQL；不替代MySQL执行计划、驱动时限和生产容量验收。 */
class KnowledgeRetrievalTest {
    private EnterpriseDatabase db;
    private KnowledgeRetrievalMapper mapper;
    private final EnterpriseMemberContext member = new EnterpriseMemberContext(1, 101, 1, 11L, "EMPLOYEE", 1, 1, 1);
    private static final long NOW = 1_800_000_000_000L;

    @BeforeEach
    void setup() throws Exception {
        db = new EnterpriseDatabase();
        db.seed();
        db.factory.getConfiguration().addMapper(KnowledgeRetrievalMapper.class);
        mapper = db.session.getMapper(KnowledgeRetrievalMapper.class);
        publish(501, 401, 1, 701, "加班餐补每人每天三十元，需主管审批。");
        publish(502, 402, 1, 702, "加班餐补未授权资料。");
        publish(503, 403, 2, 703, "加班餐补另一企业资料。");
        publish(505, 404, 1, 705, "加班餐补部门资料。");
    }

    @AfterEach
    void close() throws Exception { db.close(); }

    @Test
    void onlyPublishedAndAuthorizedEvidenceIsReturnedWithCoordinates() {
        var evidence = mapper.search(member, 401, NOW, List.of("餐补"));
        assertThat(evidence).hasSize(1);
        assertThat(evidence.get(0).versionId()).isEqualTo(701);
        assertThat(evidence.get(0).endOffset()).isEqualTo(evidence.get(0).content().length());
        assertThat(mapper.search(member, 402, NOW, List.of("餐补"))).isEmpty();
        assertThat(mapper.search(member, 403, NOW, List.of("餐补"))).isEmpty();
        assertThat(mapper.search(member, 404, NOW, List.of("餐补"))).hasSize(1);
        assertThat(mapper.evidence(member, 401, NOW, 702)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "UPDATE rag_document_version SET publication_status='DRAFT' WHERE id=701",
        "UPDATE rag_document_version SET publication_status='WITHDRAWN' WHERE id=701",
        "UPDATE rag_document_version SET parse_status='FAILED' WHERE id=701",
        "UPDATE rag_document_version SET valid_until_ms=1800000000000 WHERE id=701",
        "UPDATE rag_document_version SET valid_from_ms=1800000000001 WHERE id=701",
        "UPDATE rag_document SET deleted=1 WHERE id=501",
        "UPDATE rag_document SET published_version_id=NULL WHERE id=501",
        "UPDATE rag_document SET owner_member_id=NULL WHERE id=501",
        "UPDATE rag_document_version SET enterprise_id=2 WHERE id=701",
        "UPDATE rag_document_version SET knowledge_space_id=403 WHERE id=701",
        "UPDATE rag_knowledge_grant SET status=1 WHERE knowledge_space_id=401",
        "UPDATE sys_enterprise_member SET permission_version=2 WHERE id=101",
        "UPDATE sys_enterprise SET status=1 WHERE id=1",
        "UPDATE sys_user SET auth_version=2 WHERE id=1",
        "UPDATE rag_chunk SET start_offset=NULL WHERE id=701"
    })
    void revocationInvalidLifecycleAndInconsistentRelationsCannotBeUsed(String sql) {
        db.jdbc.update(sql);
        assertThat(mapper.search(member, 401, NOW, List.of("餐补"))).isEmpty();
        assertThat(mapper.evidence(member, 401, NOW, 701)).isNull();
    }

    @Test
    void maintainerStillCannotUseDraftAsAnswerEvidence() {
        db.jdbc.update("UPDATE rag_knowledge_grant SET permission='MAINTAIN' WHERE knowledge_space_id=401");
        db.jdbc.update("UPDATE rag_document_version SET publication_status='DRAFT' WHERE id=701");
        assertThat(mapper.search(member, 401, NOW, List.of("餐补"))).isEmpty();
    }

    @Test
    void sqlMetacharactersAreLiteralParametersAndDoNotBroadenScope() {
        for (String term : List.of("%", "_", "' OR 1=1 --", "餐补' UNION SELECT"))
            assertThat(mapper.search(member, 401, NOW, List.of(term))).isEmpty();
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class)).isEqualTo(3);
    }

    @Test
    void bilingualTermsAreBoundedAndKeepLastTopics() {
        assertThat(KeywordKnowledgeRetriever.terms("加班餐补怎么申请，Java deployment？")).contains("加班", "餐补", "java", "deployment");
        assertThat(KeywordKnowledgeRetriever.terms("？%_' ")).isEmpty();
        String longQuestion = "一二三四五六七八九十甲乙丙丁戊己庚辛壬癸天地玄黄宇宙洪荒日月盈昃辰宿列张寒来暑往秋收冬藏闰余成岁律吕调阳云腾致雨露结为霜金生丽水玉出昆冈最后餐补";
        assertThat(KeywordKnowledgeRetriever.terms(longQuestion)).hasSizeLessThanOrEqualTo(48).contains("餐补");
    }

    @ParameterizedTest
    @CsvSource({
        "CAB是什么？,cab|是什",
        "deployment上线先做什么？,deployment|上线|线先|先做|做什",
        "中文Java17部署,中文|java17|部署",
        "版本2FA验证,版本|2fa|验证",
        "café政策,café|政策",
        "政策café,政策|café",
        "𠮷野CAB部署,𠮷野|cab|部署",
        "CAB🙂部署,cab|部署"
    })
    void hanAndOtherLettersRemainSeparateEvenWithoutWhitespace(String question, String expected) {
        assertThat(KeywordKnowledgeRetriever.terms(question)).containsExactly(expected.split("\\|"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CAB是什么？", "deployment上线先做什么？"})
    void attachedChineseDoesNotPreventEnglishEvidenceFromBeingRetrieved(String question) {
        String content = "CAB是变更评审委员会。deployment必须通过测试。";
        db.jdbc.update("UPDATE rag_chunk SET content=?,end_offset=? WHERE id=701", content, content.length());
        assertThat(mapper.search(member, 401, NOW, KeywordKnowledgeRetriever.terms(question)))
                .extracting(item -> item.chunkId()).containsExactly(701L);
    }

    private void publish(long documentId, long spaceId, long enterpriseId, long versionId, String content) {
        db.jdbc.update("UPDATE rag_document SET owner_member_id=101,published_version_id=? WHERE id=?", versionId, documentId);
        db.jdbc.update("""
                INSERT INTO rag_document_version(id,enterprise_id,knowledge_space_id,document_id,version_no,
                  upload_request_id,original_name,storage_key,content_sha256,request_sha256,file_size,created_by_member,
                  parse_status,publication_status,chunk_count)
                VALUES(?,?,?,?,1,?,'虚构制度.md',?,?,?,100,101,'READY','PUBLISHED',1)
                """, versionId, enterpriseId, spaceId, documentId, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "a".repeat(64), "b".repeat(64));
        db.jdbc.update("""
                INSERT INTO rag_chunk(id,document_id,version_id,chunk_index,content,start_offset,end_offset,start_line,end_line)
                VALUES(?,?,?,0,?,0,?,1,1)
                """, versionId, documentId, versionId, content, content.length());
    }
}
