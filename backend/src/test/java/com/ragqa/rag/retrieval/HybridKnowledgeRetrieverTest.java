package com.ragqa.rag.retrieval;

import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.dto.KnowledgeAnswers.Evidence;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.*;
import com.ragqa.mapper.KnowledgeVectorJobMapper.Stored;
import com.ragqa.rag.chat.TaskDeadline;
import com.ragqa.rag.retrieval.QdrantVectorClient.Hit;
import com.ragqa.rag.retrieval.QdrantVectorClient.Index;
import com.ragqa.rag.retrieval.QdrantVectorClient.Point;
import com.ragqa.rag.retrieval.QdrantVectorClient.Scope;
import com.ragqa.rag.retrieval.TextEmbeddings.Vector;
import com.ragqa.service.EnterpriseResourceService;
import com.ragqa.service.impl.CurrentUserServiceImpl;
import com.ragqa.service.impl.EnterpriseMemberServiceImpl;
import com.ragqa.service.impl.EnterpriseResourceServiceImpl;
import com.ragqa.support.EnterpriseDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实授权SQL、发布清单和H2；仅本机Embedding/Qdrant方法替身，不证明真实语义效果。 */
class HybridKnowledgeRetrieverTest {
    private static final String SOURCE = "b".repeat(64);
    private static final LocalEmbeddingClient.ModelSnapshot MODEL = new LocalEmbeddingClient.ModelSnapshot("synthetic:1", "a".repeat(64), 2);
    private static final Vector VECTOR = new Vector("synthetic:1", List.of(3.0, 4.0));
    private static final List<String> TEXT = List.of("虚构制度：餐补三十元。", "虚构制度：需主管审批。");
    private final EnterpriseMemberContext member = new EnterpriseMemberContext(1, 101, 1, 11L, "EMPLOYEE", 1, 1, 1);
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now.get()); }
    };
    private EnterpriseDatabase db;
    private KnowledgeRetrievalMapper evidence;
    private KnowledgeVectorJobMapper jobs;
    private KnowledgeVectorManifestMapper manifests;
    private KnowledgeVectorPublicationMapper publications;
    private EnterpriseResourceService resources;
    private TransactionTemplate tx;
    private LocalEmbeddingClient embedding;
    private QdrantVectorClient vectors;
    private HybridKnowledgeRetriever retriever;
    private final Map<Long, List<Hit>> results = new ConcurrentHashMap<>();
    private final Map<Long, VectorIndexManifest> indexes = new HashMap<>();
    private Consumer<Scope> onQuery = scope -> { };
    private Runnable onEmbed = () -> { };
    private Consumer<Integer> onSnapshot = count -> { };
    private final AtomicInteger snapshots = new AtomicInteger();

    @BeforeEach void setup() throws Exception {
        db = new EnterpriseDatabase(); db.seed();
        try (var connection = db.dataSource.getConnection()) {
            for (String file : List.of("20261019_04_knowledge_vector_jobs.sql", "20261020_01_knowledge_vector_manifests.sql",
                    "20261020_02_knowledge_vector_publication.sql")) {
                String sql = Files.readString(Path.of("sql/migrations", file), StandardCharsets.UTF_8)
                        .replaceAll("(?i)COLLATE\\s*=?\\s*(utf8mb4_unicode_ci|ascii_bin)", "")
                        .replaceAll("(?i)CHARACTER SET ascii", "");
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
            }
        }
        for (var type : List.of(KnowledgeRetrievalMapper.class, KnowledgeVectorJobMapper.class,
                KnowledgeVectorManifestMapper.class, KnowledgeVectorPublicationMapper.class)) db.factory.getConfiguration().addMapper(type);
        var sql = new SqlSessionTemplate(db.factory);
        evidence = sql.getMapper(KnowledgeRetrievalMapper.class);
        jobs = sql.getMapper(KnowledgeVectorJobMapper.class);
        manifests = sql.getMapper(KnowledgeVectorManifestMapper.class);
        publications = sql.getMapper(KnowledgeVectorPublicationMapper.class);
        var members = new EnterpriseMemberServiceImpl(new CurrentUserServiceImpl(sql.getMapper(SysUserMapper.class)),
                sql.getMapper(EnterpriseDirectoryMapper.class));
        resources = new EnterpriseResourceServiceImpl(members, sql.getMapper(EnterpriseResourceMapper.class));
        tx = new TransactionTemplate(new DataSourceTransactionManager(db.dataSource));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); tx.setTimeout(5);
        seed(501, 701, 1001, TEXT);
        seed(507, 707, 2001, List.of("虚构政策：晚餐福利另行安排。"));
        results.put(501L, List.of(hit(1002, TEXT.get(1), .8)));
        results.put(507L, List.of(hit(2001, "虚构政策：晚餐福利另行安排。", .95)));
        embedding = mock(LocalEmbeddingClient.class); vectors = mock(QdrantVectorClient.class);
        when(embedding.snapshot(any())).thenAnswer(call -> {
            outside(); onSnapshot.accept(snapshots.incrementAndGet()); return MODEL;
        });
        when(embedding.embed(anyList(), any())).thenAnswer(call -> {
            outside(); assertThat(call.getArgument(0, List.class)).containsExactly("餐补审批"); onEmbed.run(); return List.of(VECTOR);
        });
        when(vectors.query(any(), any(), any(), anyDouble(), eq(24), any())).thenAnswer(call -> {
            outside(); Scope scope = call.getArgument(1);
            var expected = indexes.get(scope.documentId());
            assertThat(scope).isEqualTo(expected.scope());
            assertThat(call.getArgument(0, Index.class)).isEqualTo(expected.index());
            assertThat(call.getArgument(2, Vector.class)).isEqualTo(VECTOR);
            assertThat(call.getArgument(3, Double.class)).isEqualTo(.5);
            onQuery.accept(scope); return results.get(scope.documentId());
        });
        retriever = fresh(.5, 60);
    }

    @AfterEach void close() throws Exception { if (db != null) db.close(); }

    private void seed(long document, long version, long chunk, List<String> text) {
        if (document != 501) db.jdbc.update("INSERT INTO rag_document(id,title,source_type,enterprise_id,knowledge_space_id) VALUES(?,'虚构制度','UPLOAD',1,401)", document);
        db.jdbc.update("UPDATE rag_document SET owner_member_id=103,revision=7,published_version_id=? WHERE id=?", version, document);
        db.jdbc.update("""
                INSERT INTO rag_document_version(id,enterprise_id,knowledge_space_id,document_id,version_no,
                  upload_request_id,original_name,storage_key,content_sha256,request_sha256,file_size,
                  parse_status,publication_status,chunk_count,created_by_member)
                VALUES(?,1,401,?,1,?,'虚构制度.md',?,?,?,100,'READY','PUBLISHED',?,103)
                """, version, document, UUID.randomUUID().toString(), UUID.randomUUID().toString(), SOURCE, "c".repeat(64), text.size());
        var points = new ArrayList<Point>();
        for (int n = 0; n < text.size(); n++) {
            db.jdbc.update("""
                    INSERT INTO rag_chunk(id,document_id,version_id,chunk_index,content,start_offset,end_offset,start_line,end_line)
                    VALUES(?,?,?,?,?,?,?,1,1)
                    """, chunk + n, document, version, n, text.get(n), n * 1200, n * 1200 + text.get(n).length());
            points.add(new Point(chunk + n, hash(text.get(n)), VECTOR));
        }
        String id = UUID.randomUUID().toString(), generation = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
        jobs.insert(new Stored(id, generation, 1, 401, document, version, 103, 3, 1, 7, "c".repeat(64), SOURCE,
                "ko_hybrid_test", MODEL.model(), MODEL.revision(), 2, text.size(), "QUEUED", null, null, null, 0,
                now.get() - 50_000, now.get() + 70_000, null, null));
        assertThat(jobs.claim(id, token, now.get() - 40_000, now.get() + 60_000)).isEqualTo(1);
        var manifest = VectorIndexManifest.capture(new Index("ko_hybrid_test", MODEL.model(), MODEL.revision(), 2),
                new Scope(1, 401, document, version, generation), 7, SOURCE, text.size(), points);
        tx.execute(status -> manifests.persist(id, token, manifest, Clock.fixed(Instant.ofEpochMilli(now.get() - 2000), ZoneOffset.UTC)));
        assertThat(jobs.verified(id, token, manifest.sha256(), text.size(), now.get() - 1000)).isEqualTo(1);
        Stored job = jobs.find(id);
        tx.execute(status -> publications.publish(job, manifest.expected(), 0, clock));
        indexes.put(document, manifest);
        results.put(document, List.of());
    }

    private HybridKnowledgeRetriever fresh(double threshold, int smoothing) {
        return new HybridKnowledgeRetriever(evidence, publications, resources, embedding, vectors, threshold, smoothing, clock);
    }
    private List<Evidence> retrieve() { return retriever.retrieve(member, 401, "餐补审批", TaskDeadline.start(Duration.ofSeconds(30))); }
    private static Hit hit(long id, String text, double score) { return new Hit(id, hash(text), score); }
    private static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    private static void outside() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
    private void conflict() { assertThatThrownBy(this::retrieve).isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(409)); }

    @Test void fusesGlobalVectorRankWithFullKeywordCandidatesAndKeepsProvenance() {
        var output = retrieve();
        assertThat(output).extracting(Evidence::chunkId).containsExactly(1002L, 1001L, 2001L);
        assertThat(output).allSatisfy(row -> {
            assertThat(row.versionNo()).isEqualTo(1);
            assertThat(row.endOffset() - row.startOffset()).isEqualTo(row.content().length());
        });
        assertThatThrownBy(() -> output.add(output.get(0))).isInstanceOf(UnsupportedOperationException.class);
        verify(embedding, times(2)).snapshot(any()); verify(embedding).embed(eq(List.of("餐补审批")), any());
        verify(vectors, times(2)).query(any(), any(), any(), eq(.5), eq(24), any());
        assertThat(db.jdbc.queryForObject("SELECT SUM(revision) FROM rag_vector_publication", Long.class)).isEqualTo(2);
        assertThat(db.jdbc.queryForObject("SELECT COUNT(*) FROM rag_vector_job WHERE status='VERIFIED'", Integer.class)).isEqualTo(2);
        verify(vectors, never()).upsert(any(), any(), anyList(), any());
    }

    @Test void emptyVectorResultsAreSuccessfulSearchesAndPreserveLexicalEvidence() {
        results.replaceAll((document, hits) -> List.of());
        assertThat(retrieve()).extracting(Evidence::chunkId).containsExactly(1001L, 1002L);
        verify(vectors, times(2)).query(any(), any(), any(), anyDouble(), eq(24), any());
    }

    @Test void bothBranchesEmptyReturnNoEvidenceRatherThanFabricatingAnswers() {
        results.replaceAll((document, hits) -> List.of());
        doReturn(List.of(VECTOR)).when(embedding).embed(anyList(), any());
        assertThat(retriever.retrieve(member, 401, "完全无关xyz", TaskDeadline.start(Duration.ofSeconds(30)))).isEmpty();
    }

    @Test void vectorBranchStillWorksWhenKeywordTokenizerHasNoTerms() {
        doReturn(List.of(VECTOR)).when(embedding).embed(anyList(), any());
        assertThat(retriever.retrieve(member, 401, "餐", TaskDeadline.start(Duration.ofSeconds(30))))
                .extracting(Evidence::chunkId).containsExactly(2001L, 1002L);
    }

    @Test void referenceReadUsesCurrentBusinessAuthorizationWithoutRequiringPublishedVectorPointer() {
        db.jdbc.update("UPDATE rag_vector_publication SET job_id=NULL,revision=revision+1");
        assertThat(retriever.requireEvidence(member, 401, 1001).content()).isEqualTo(TEXT.get(0));
        assertThatThrownBy(() -> retriever.requireEvidence(member, 403, 1001)).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(404));
        verifyNoInteractions(embedding, vectors);
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "\u0000", "\u200B", "\uD800"})
    void invalidQuestionsFailBeforeAnyNetwork(String question) {
        assertThatThrownBy(() -> retriever.retrieve(member, 401, question, TaskDeadline.start(Duration.ofSeconds(30))))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(400));
        verifyNoInteractions(embedding, vectors);
    }

    @Test void invalidIdentitySpaceDeadlineAndOversizedQuestionsAreRejected() {
        var deadline = TaskDeadline.start(Duration.ofSeconds(30));
        for (Runnable call : List.<Runnable>of(() -> retriever.retrieve(null, 401, "问题", deadline),
                () -> retriever.retrieve(member, 0, "问题", deadline), () -> retriever.retrieve(member, 401, "问题", null),
                () -> retriever.retrieve(member, 401, "问".repeat(1201), deadline), () -> retriever.requireEvidence(member, 401, 0)))
            assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(400));
        verifyNoInteractions(embedding, vectors);
    }

    @ParameterizedTest @ValueSource(longs = {402, 403, Long.MAX_VALUE})
    void unauthorizedAndCrossEnterpriseSpacesNeverReachEmbedding(long space) {
        assertThatThrownBy(() -> retriever.retrieve(member, space, "餐补审批", TaskDeadline.start(Duration.ofSeconds(30))))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(404));
        verifyNoInteractions(embedding, vectors);
    }

    @Test void noPublishedIndexIsExplicitlyUnavailableAndDoesNotFallBack() {
        db.jdbc.update("UPDATE rag_vector_publication SET job_id=NULL,revision=revision+1");
        assertThatThrownBy(this::retrieve).isInstanceOfSatisfying(BusinessException.class, ex -> {
            assertThat(ex.getCode()).isEqualTo(503); assertThat(ex.getMessage()).contains("尚无可用向量索引");
        });
        verifyNoInteractions(embedding, vectors);
    }

    @Test void manifestCorruptionWithUnchangedRowCountFailsBeforeNetwork() {
        db.jdbc.update("UPDATE rag_vector_manifest_entry SET content_sha256=? WHERE chunk_id=1001", "d".repeat(64));
        conflict(); verifyNoInteractions(embedding, vectors);
    }

    @ParameterizedTest @ValueSource(strings = {
        "UPDATE rag_knowledge_grant SET status=1 WHERE knowledge_space_id=401",
        "UPDATE sys_user SET auth_version=2 WHERE id=1",
        "UPDATE sys_enterprise_member SET permission_version=2 WHERE id=101",
        "UPDATE sys_enterprise SET status=1 WHERE id=1",
        "UPDATE rag_document_version SET publication_status='WITHDRAWN' WHERE id=701",
        "UPDATE rag_document_version SET valid_until_ms=1800000000000 WHERE id=701",
        "UPDATE rag_document SET published_version_id=NULL WHERE id=501",
        "UPDATE rag_document SET revision=8 WHERE id=501",
        "UPDATE rag_vector_publication SET job_id=NULL,revision=revision+1 WHERE document_id=501"
    })
    void revocationOrGenerationChangeDuringQueryDoesNotReturnPartialSuccess(String sql) {
        onQuery = scope -> db.jdbc.update(sql);
        conflict(); verify(vectors, times(1)).query(any(), any(), any(), anyDouble(), eq(24), any());
    }

    @Test void contentModifiedWithoutChangingVersionOrManifestCannotPassSourceHashReview() {
        onQuery = scope -> db.jdbc.update("UPDATE rag_chunk SET content='虚构制度：餐补九十元。' WHERE id=1001");
        results.put(501L, List.of(hit(1001, TEXT.get(0), .9)));
        conflict();
    }

    @Test void finalModelMetadataCallCannotHideLateEvidenceModification() {
        onSnapshot = count -> { if (count == 2) db.jdbc.update("UPDATE rag_chunk SET content='虚构制度：需组长审批。' WHERE id=1002"); };
        conflict();
    }

    @Test void manifestModificationAfterQueryIsDetectedByFinalSealedDigestCheck() {
        onQuery = scope -> db.jdbc.update("UPDATE rag_vector_manifest_entry SET vector_sha256=? WHERE chunk_id=1001", "f".repeat(64));
        conflict();
    }

    @Test void hitFromAnotherDocumentIsRejectedEvenWithAValidExistingChunkId() {
        results.put(501L, List.of(hit(2001, "虚构政策：晚餐福利另行安排。", .9)));
        conflict();
    }

    @Test void hitHashMustMatchSealedManifestAndLiveSqlContent() {
        results.put(501L, List.of(new Hit(1001, "f".repeat(64), .9)));
        conflict();
    }

    @ParameterizedTest @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, -.2, 1.1})
    void invalidSemanticScoresFailWithoutFusing(double score) {
        results.put(501L, List.of(hit(1001, TEXT.get(0), score))); conflict();
    }

    @Test void duplicateAndUnknownVectorHitsAreRejected() {
        results.put(501L, List.of(hit(1001, TEXT.get(0), .9), hit(1001, TEXT.get(0), .8))); conflict();
        results.put(501L, List.of(hit(Long.MAX_VALUE, TEXT.get(0), .9))); conflict();
    }

    @Test void oversizedOrNullVectorResponsesCannotBeSilentlyTruncated() {
        results.put(501L, Collections.nCopies(25, hit(1001, TEXT.get(0), .9))); conflict();
        results.remove(501L); conflict();
    }

    @Test void wrongModelRevisionBeforeEmbeddingStopsAllQueries() {
        doReturn(new LocalEmbeddingClient.ModelSnapshot(MODEL.model(), "f".repeat(64), 2)).when(embedding).snapshot(any());
        conflict(); verify(embedding, never()).embed(anyList(), any()); verifyNoInteractions(vectors);
    }

    @Test void wrongModelRevisionAfterQueriesDiscardsEntireResult() {
        doReturn(MODEL, new LocalEmbeddingClient.ModelSnapshot(MODEL.model(), "f".repeat(64), 2)).when(embedding).snapshot(any());
        conflict(); verify(vectors, times(2)).query(any(), any(), any(), anyDouble(), eq(24), any());
    }

    @Test void differentModelGenerationsAcrossDocumentsCannotShareOneQueryVector() {
        db.jdbc.update("UPDATE rag_vector_job SET model_revision=? WHERE document_id=507", "f".repeat(64));
        conflict(); verifyNoInteractions(embedding, vectors);
    }

    @Test void nullEmptyWrongModelDimensionAndZeroEmbeddingAreRejectedBeforeQuery() {
        for (var encoded : List.of(List.<Vector>of(), List.of(new Vector("other:1", List.of(3.0, 4.0))),
                List.of(new Vector(MODEL.model(), List.of(1.0))), List.of(new Vector(MODEL.model(), List.of(0.0, 0.0))),
                List.of(new Vector(MODEL.model(), List.of(Double.NaN, 1.0))))) {
            doReturn(encoded).when(embedding).embed(anyList(), any()); conflict();
        }
        doReturn(null).when(embedding).embed(anyList(), any()); conflict(); verifyNoInteractions(vectors);
    }

    @Test void unexpectedProviderFailureIsSanitizedNotRetriedAndReleasesSlot() {
        when(vectors.query(any(), any(), any(), anyDouble(), eq(24), any())).thenThrow(new IllegalStateException("RAW_PRIVATE_UPSTREAM_BODY"));
        assertThatThrownBy(this::retrieve).isInstanceOfSatisfying(BusinessException.class, ex -> {
            assertThat(ex.getCode()).isEqualTo(503); assertThat(ex.getMessage()).doesNotContain("RAW_PRIVATE"); assertThat(ex.getCause()).isNull();
        });
        verify(vectors, times(1)).query(any(), any(), any(), anyDouble(), eq(24), any());
        when(vectors.query(any(), any(), any(), anyDouble(), eq(24), any())).thenReturn(List.of());
        assertThat(retrieve()).hasSize(2);
    }

    @Test void sharedStageBudgetExhaustedDuringFirstQueryPreventsNextQueryAndSuccess() {
        TaskDeadline parent = mock(TaskDeadline.class), stage = mock(TaskDeadline.class);
        var exhausted = new AtomicBoolean();
        when(parent.limitedTo(Duration.ofSeconds(15))).thenReturn(stage);
        when(stage.timeoutFor(any())).thenAnswer(call -> {
            if (exhausted.get()) throw new BusinessException(ResultCode.SERVICE_BUSY, "测试预算已耗尽");
            return Duration.ofMillis(500);
        });
        onQuery = scope -> exhausted.set(true);
        assertThatThrownBy(() -> retriever.retrieve(member, 401, "餐补审批", parent))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(503));
        verify(parent, times(1)).limitedTo(Duration.ofSeconds(15));
        verify(vectors, times(1)).query(any(), any(), any(), anyDouble(), eq(24), same(stage));
        verify(embedding).embed(anyList(), same(stage));
    }

    @Test void interruptedThreadFailsBeforeNetworkAndPreservesInterruption() {
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(this::retrieve).isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(503));
            assertThat(Thread.currentThread().isInterrupted()).isTrue(); verifyNoInteractions(embedding, vectors);
        } finally { Thread.interrupted(); }
    }

    @Test void clockRollbackDuringEmbeddingStopsBeforeQuery() {
        onEmbed = () -> now.decrementAndGet();
        assertThatThrownBy(this::retrieve).isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(503));
        verifyNoInteractions(vectors);
    }

    @Test void outerTransactionIsRejectedEvenAtReadCommitted() {
        tx.execute(status -> {
            assertThatThrownBy(this::retrieve).isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(503));
            return null;
        });
        verifyNoInteractions(embedding, vectors);
    }

    @Test void twoBusyRequestsRejectThirdWithoutQueueAndSlotsRecover() throws Exception {
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        onSnapshot = count -> {
            if (count <= 2) {
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("测试释放超时"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
            }
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(this::retrieve); var second = pool.submit(this::retrieve);
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(this::retrieve).isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.getCode()).isEqualTo(503));
            assertThat(snapshots).hasValue(2);
            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).hasSize(3); assertThat(second.get(10, TimeUnit.SECONDS)).hasSize(3);
            assertThat(retrieve()).hasSize(3);
        } finally { release.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }

    @Test void overlapSuppressionAndSixEvidenceLimitDoNotTruncateText() {
        var text = new ArrayList<String>();
        for (int n = 0; n < 8; n++) text.add("餐补审批" + n + "甲".repeat(200));
        seed(508, 708, 3001, text);
        db.jdbc.update("UPDATE rag_chunk SET start_offset=0,end_offset=CHAR_LENGTH(content) WHERE id IN(3001,3002)");
        results.replaceAll((document, hits) -> List.of());
        var output = retrieve();
        assertThat(output).hasSize(6).extracting(Evidence::chunkId).contains(3001L).doesNotContain(3002L);
        assertThat(output).allSatisfy(row -> assertThat(row.content()).isIn(text));
    }

    @Test void equalCosineScoresAcrossDocumentsHaveStableGlobalIdOrder() {
        results.put(501L, List.of(hit(1002, TEXT.get(1), .9)));
        results.put(507L, List.of(hit(2001, "虚构政策：晚餐福利另行安排。", .9)));
        assertThat(retrieve()).extracting(Evidence::chunkId).containsExactly(1002L, 1001L, 2001L);
    }

    @Test void fortyEightSemanticCandidatesBecomeGlobalTopTwentyFourBeforeRrf() {
        var low = new ArrayList<String>(); var high = new ArrayList<String>();
        for (int n = 0; n < 24; n++) { low.add("虚构低相似资料" + n); high.add("虚构高相似资料" + n); }
        seed(508, 708, 3001, low); seed(509, 709, 4001, high);
        results.replaceAll((document, hits) -> List.of());
        var lowHits = new ArrayList<Hit>(); var highHits = new ArrayList<Hit>();
        for (int n = 0; n < 24; n++) {
            lowHits.add(hit(3001 + n, low.get(n), .6)); highHits.add(hit(4001 + n, high.get(n), .9));
        }
        results.put(508L, lowHits); results.put(509L, highHits);
        assertThat(retrieve()).extracting(Evidence::chunkId).containsExactly(1001L, 4001L, 1002L, 4002L, 4003L, 4004L);
    }

    @Test void twentyFourDocumentsAllowedAndTwentyFifthFailsBeforeEmbedding() {
        for (int n = 0; n < 22; n++) seed(10_000 + n, 20_000 + n, 30_000 + n, List.of("虚构容量制度" + n));
        assertThat(retrieve()).hasSize(3);
        clearInvocations(embedding, vectors);
        seed(10_022, 20_022, 30_022, List.of("虚构容量制度22"));
        assertThatThrownBy(this::retrieve).isInstanceOfSatisfying(BusinessException.class, ex -> {
            assertThat(ex.getCode()).isEqualTo(503); assertThat(ex.getMessage()).contains("超过24");
        });
        verifyNoInteractions(embedding, vectors);
    }

    @Test void explicitRankingParametersAreValidatedRatherThanSilentlyClamped() {
        for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -1.1, 1.1})
            assertThatThrownBy(() -> fresh(value, 60)).isInstanceOf(IllegalArgumentException.class);
        for (int value : new int[] {0, 1001}) assertThatThrownBy(() -> fresh(.5, value)).isInstanceOf(IllegalArgumentException.class);
        assertThat(fresh(-1, 1)).isNotNull(); assertThat(fresh(1, 1000)).isNotNull();
    }
}
