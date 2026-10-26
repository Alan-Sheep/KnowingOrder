package com.ragqa.rag.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ragqa.exception.BusinessException;
import com.ragqa.rag.chat.TaskDeadline;
import com.ragqa.rag.retrieval.QdrantVectorClient.Index;
import com.ragqa.rag.retrieval.QdrantVectorClient.Point;
import com.ragqa.rag.retrieval.QdrantVectorClient.Scope;
import com.ragqa.rag.retrieval.TextEmbeddings.Vector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/**
 * 显式选择才运行的本机Qdrant契约验收：只写新建ko_contract_随机集合，最后仅清理这个集合。
 * 使用预先给定的二维向量，不下载/调用Embedding、不读MySQL、不发送任何企业原文。
 */
@EnabledIfEnvironmentVariable(named = "QDRANT_CONTRACT_IT", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QdrantVectorClientIT {
    private final QdrantVectorClient client = new QdrantVectorClient(new ObjectMapper(), true, "http://127.0.0.1:6333");
    private final Index index = new Index("ko_contract_" + UUID.randomUUID().toString().replace("-", ""),
            "synthetic-contract:v1", "a".repeat(64), 2);
    private final String generation = UUID.randomUUID().toString();
    private final Scope baseline = new Scope(900001, 900002, 900003, 900004, generation);
    private boolean creationAttempted;

    @BeforeAll void createOwnedCollection() {
        assertThat(index.collection()).startsWith("ko_contract_");
        assertThat(client.inspect(index, deadline())).isFalse();
        creationAttempted = true;
        client.create(index, deadline());
        assertThat(client.inspect(index, deadline())).isTrue();
        client.upsert(index, baseline, List.of(point(10, "b", 1, 0), point(20, "c", 0.8, 0.6), point(30, "d", 0, 1)), deadline());
    }

    @AfterAll void removeOnlyOwnedCollection() {
        if (creationAttempted && client.inspect(index, deadline())) {
            client.deleteCollection(index, deadline());
            assertThat(client.inspect(index, deadline())).isFalse();
        }
    }

    @Test void knownCosineOrderingAndRrfAgreeWithOfflineOracle() {
        var hits = client.query(index, baseline, vector(1, 0), -1, 3, deadline());
        assertThat(hits).extracting(QdrantVectorClient.Hit::chunkId).containsExactly(10L, 20L, 30L);
        assertThat(hits.get(0).score()).isCloseTo(1.0, within(1e-6));
        assertThat(hits.get(1).score()).isCloseTo(0.8, within(1e-6));
        assertThat(client.query(index, baseline, vector(1, 0), 0.5, 3, deadline()))
                .extracting(QdrantVectorClient.Hit::chunkId).containsExactly(10L, 20L);
        assertThat(HybridRetrievalRanking.fuse(List.of(30L, 20L), List.of(10L, 20L), 60, 3))
                .extracting(HybridRetrievalRanking.Fused::chunkId).containsExactly(20L, 10L, 30L);
    }

    @Test void manualIdempotentUpsertCanBeReconciledByScopedIdAndDigest() {
        Scope scope = scope(10);
        Point point = point(7, "b", 3, 4);
        client.upsert(index, scope, List.of(point), deadline());
        var first = client.read(index, scope, List.of(7L), deadline());
        client.upsert(index, scope, List.of(point), deadline());
        assertThat(client.read(index, scope, List.of(7L), deadline())).isEqualTo(first);
        assertThat(first).hasSize(1);
        assertThat(first.get(0).vectorSha256()).isEqualTo(QdrantVectorClient.vectorSha256(point.vector()));
        assertThat(first.get(0).contentSha256()).isEqualTo(point.contentSha256());
    }

    @Test void filtersEnterpriseSpaceDocumentVersionAndGenerationBeforeReturningCandidates() {
        Scope base = scope(20);
        var scopes = List.of(base,
                new Scope(base.enterpriseId() + 1, base.spaceId(), base.documentId(), base.versionId(), generation),
                new Scope(base.enterpriseId(), base.spaceId() + 1, base.documentId(), base.versionId(), generation),
                new Scope(base.enterpriseId(), base.spaceId(), base.documentId() + 1, base.versionId(), generation),
                new Scope(base.enterpriseId(), base.spaceId(), base.documentId(), base.versionId() + 1, generation),
                new Scope(base.enterpriseId(), base.spaceId(), base.documentId(), base.versionId(), UUID.randomUUID().toString()));
        for (int i = 0; i < scopes.size(); i++) client.upsert(index, scopes.get(i), List.of(point(7, Integer.toString(i), 1, 0)), deadline());
        for (int i = 0; i < scopes.size(); i++) {
            var hits = client.query(index, scopes.get(i), vector(1, 0), 0, 24, deadline());
            assertThat(hits).hasSize(1);
            assertThat(hits.get(0).contentSha256()).isEqualTo(Integer.toString(i).repeat(64));
        }
    }

    @Test void incompatibleModelRevisionAndDimensionCannotTakeOverCollection() {
        for (Index incompatible : List.of(new Index(index.collection(), "another-model:v1", index.revision(), 2),
                new Index(index.collection(), index.model(), "b".repeat(64), 2),
                new Index(index.collection(), index.model(), index.revision(), 3))) {
            assertThatThrownBy(() -> client.inspect(incompatible, deadline())).isInstanceOfSatisfying(BusinessException.class,
                    ex -> assertThat(ex.getCode()).isEqualTo(409));
        }
        assertThat(client.inspect(index, deadline())).isTrue();
    }

    @Test void missingScopedRecordsAndEmptyResultsStayEmpty() {
        assertThat(client.query(index, scope(99), vector(1, 0), 0, 24, deadline())).isEmpty();
        assertThat(client.read(index, baseline, List.of(999999L), deadline())).isEmpty();
    }

    @Test void concurrentScopesDoNotOverwriteSameChunkId() throws Exception {
        var pool = Executors.newFixedThreadPool(4);
        try {
            var work = new ArrayList<Callable<Void>>();
            for (int i = 0; i < 4; i++) {
                int number = i;
                work.add(() -> {
                    Scope scope = scope(100 + number);
                    client.upsert(index, scope, List.of(point(7, Integer.toString(number), 1, 0)), deadline());
                    var hits = client.query(index, scope, vector(1, 0), 0, 24, deadline());
                    assertThat(hits).hasSize(1);
                    assertThat(hits.get(0).contentSha256()).isEqualTo(Integer.toString(number).repeat(64));
                    return null;
                });
            }
            for (var result : pool.invokeAll(work, 15, TimeUnit.SECONDS)) result.get();
        } finally { pool.shutdownNow(); }
    }

    @Test void duplicateCreationDoesNotReplaceExistingVectors() {
        assertThatThrownBy(() -> client.create(index, deadline())).isInstanceOf(BusinessException.class);
        assertThat(client.inspect(index, deadline())).isTrue();
        assertThat(client.query(index, baseline, vector(1, 0), 0.5, 3, deadline()))
                .extracting(QdrantVectorClient.Hit::chunkId).containsExactly(10L, 20L);
    }

    @Test void extremeDoubleVectorsRetainDirectionInFloat32Store() {
        Scope scope = scope(200);
        client.upsert(index, scope, List.of(point(11, "b", Double.MAX_VALUE, Double.MAX_VALUE),
                point(12, "c", Double.MIN_VALUE, Double.MIN_VALUE)), deadline());
        var hits = client.query(index, scope, vector(1, 1), 0.99, 2, deadline());
        assertThat(hits).extracting(QdrantVectorClient.Hit::chunkId).containsExactly(11L, 12L);
        assertThat(hits).allSatisfy(hit -> assertThat(hit.score()).isCloseTo(1, within(1e-6)));
    }

    private Scope scope(long suffix) { return new Scope(800001, 800002, 800000 + suffix, 700000 + suffix, generation); }
    private Point point(long id, String hash, double x, double y) { return new Point(id, hash.repeat(64), vector(x, y)); }
    private Vector vector(double x, double y) { return new Vector(index.model(), List.of(x, y)); }
    private TaskDeadline deadline() { return TaskDeadline.start(Duration.ofSeconds(10)); }
}
