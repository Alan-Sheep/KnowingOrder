package com.ragqa.rag.retrieval;

import com.ragqa.common.ResultCode;
import com.ragqa.exception.BusinessException;
import com.ragqa.rag.chat.TaskDeadline;
import com.ragqa.rag.retrieval.QdrantVectorClient.Index;
import com.ragqa.rag.retrieval.QdrantVectorClient.Point;
import com.ragqa.rag.retrieval.QdrantVectorClient.Scope;
import com.ragqa.rag.retrieval.QdrantVectorClient.Stored;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

/**
 * 单文档版本、单代次的不可变预期清单；仅保留定位和摘要，不保留原文或向量。
 * 核对全部预期点不等于授权、原子快照或READY发布，发布方仍须事务内复核清单及租约。
 */
public final class VectorIndexManifest {
    // 与当前文字解析器的1000片段上限一致；不截断超限清单，不代表生产容量。
    public static final int MAX_CHUNKS = 1000;
    private static final Duration CHECK_LIMIT = Duration.ofSeconds(5);
    private final Index index;
    private final Scope scope;
    private final long documentRevision;
    private final String sourceSha256;
    private final List<Stored> expected;
    private final String sha256;

    /** 只表示一次有界读取的核对结果，不作为可直接发布索引的凭据。 */
    public record Verification(String manifestSha256, int checkedChunks, int checkedBatches) { }

    private VectorIndexManifest(Index index, Scope scope, long revision, String sourceSha256, List<Stored> expected) {
        this.index = index;
        this.scope = scope;
        this.documentRevision = revision;
        this.sourceSha256 = sourceSha256;
        this.expected = List.copyOf(expected);
        MessageDigest digest = digest();
        for (String field : List.of("knowingorder-manifest-v1", index.collection(), index.model(), index.revision(),
                Integer.toString(index.dimensions()), Long.toString(scope.enterpriseId()), Long.toString(scope.spaceId()),
                Long.toString(scope.documentId()), Long.toString(scope.versionId()), scope.generation(),
                Long.toString(revision), sourceSha256, Integer.toString(expected.size()))) update(digest, field);
        for (Stored row : expected) {
            update(digest, Long.toString(row.chunkId()));
            update(digest, row.contentSha256());
            update(digest, row.vectorSha256());
        }
        sha256 = HexFormat.of().formatHex(digest.digest());
    }

    /**
     * 调用方须从授权的固定数据库快照取得原件摘要、修订及完整片段数，再用实际向量构造清单。
     * expectedCount必须独立于已成功写入数量；向量模型真实修订的解析由后续编排负责。
     */
    public static VectorIndexManifest capture(Index index, Scope scope, long revision, String sourceSha256,
            int expectedCount, List<Point> points) {
        if (index == null || scope == null || revision <= 0 || !hash(sourceSha256)
                || expectedCount < 1 || expectedCount > MAX_CHUNKS || points == null
                || points.size() != expectedCount) throw invalid();
        var input = new ArrayList<>(points);
        if (input.size() != expectedCount) throw invalid();
        var seen = new HashSet<Long>();
        var rows = new ArrayList<Stored>();
        for (Point point : input) {
            if (point == null || point.chunkId() <= 0 || !seen.add(point.chunkId()) || !hash(point.contentSha256())
                    || point.vector() == null || !index.model().equals(point.vector().model())
                    || point.vector().values().size() != index.dimensions()) throw invalid();
            rows.add(new Stored(point.chunkId(), point.contentSha256(), QdrantVectorClient.vectorSha256(point.vector())));
        }
        // 清单绑定集合而非批次顺序，同一快照分批方式变化不应改变清单摘要。
        rows.sort(Comparator.comparingLong(Stored::chunkId));
        return new VectorIndexManifest(index, scope, revision, sourceSha256, rows);
    }

    /**
     * 从已授权读取的完整持久摘要恢复预期清单，重算并核对原封存摘要；不推理、不读取原件。
     * 仍不产生授权或READY凭据；调用方须重新核模型、向量服务与当前来源，禁止使用客户端清单。
     */
    public static VectorIndexManifest restore(Index index, Scope scope, long revision, String sourceSha256,
            int expectedCount, List<Stored> entries, String sealedSha256) {
        if (index == null || scope == null || revision <= 0 || !hash(sourceSha256) || !hash(sealedSha256)
                || expectedCount < 1 || expectedCount > MAX_CHUNKS || entries == null
                || entries.size() != expectedCount) throw invalid();
        var rows = new ArrayList<>(entries);
        if (rows.size() != expectedCount) throw invalid();
        var seen = new HashSet<Long>();
        for (Stored row : rows) {
            if (row == null || row.chunkId() <= 0 || !seen.add(row.chunkId())
                    || !hash(row.contentSha256()) || !hash(row.vectorSha256())) throw invalid();
        }
        rows.sort(Comparator.comparingLong(Stored::chunkId));
        var restored = new VectorIndexManifest(index, scope, revision, sourceSha256, rows);
        if (!sealedSha256.equals(restored.sha256())) throw new BusinessException(ResultCode.CONFLICT,
                "持久索引清单与封存摘要不一致，禁止发布，请核对原任务");
        return restored;
    }

    /**
     * 每批至多16点，所有批次复用父预算；guard由上层提供，用于批次前后检查取消、租约和权限。
     * 任何缺项、摘要不符或回调失败都不返回成功结果；不补写、不重试、不修改任何业务状态。
     * 这里只比较payload摘要，不读取实际向量数值，也不证明该代次没有额外点或并发写入。
     */
    public Verification verify(QdrantVectorClient client, TaskDeadline deadline, Runnable guard) {
        if (client == null || deadline == null || guard == null) throw invalid();
        checkpoint(deadline, guard);
        int batches = 0;
        for (int start = 0; start < expected.size(); start += 16) {
            List<Stored> batch = expected.subList(start, Math.min(start + 16, expected.size()));
            List<Stored> actual = client.read(index, scope, batch.stream().map(Stored::chunkId).toList(), deadline);
            checkpoint(deadline, guard);
            // read已验证范围、确定性编号并排序；逐字段比较，数量相同也可能是错误内容或向量。
            if (!batch.equals(actual)) throw new BusinessException(ResultCode.CONFLICT,
                    "索引片段缺失或摘要不一致，未通过完整核对；禁止发布，请先核对原任务");
            batches++;
        }
        return new Verification(sha256, expected.size(), batches);
    }

    public Index index() { return index; }
    public Scope scope() { return scope; }
    public long documentRevision() { return documentRevision; }
    public String sourceSha256() { return sourceSha256; }
    public List<Stored> expected() { return expected; }
    public String sha256() { return sha256; }

    @Override public String toString() { return "VectorIndexManifest[chunks=" + expected.size() + "]"; }

    private static void checkpoint(TaskDeadline deadline, Runnable guard) {
        if (Thread.currentThread().isInterrupted()) throw new BusinessException(ResultCode.SERVICE_BUSY, "索引核对已中断，未确认索引就绪");
        deadline.timeoutFor(CHECK_LIMIT);
        guard.run();
        deadline.timeoutFor(CHECK_LIMIT);
        if (Thread.currentThread().isInterrupted()) throw new BusinessException(ResultCode.SERVICE_BUSY, "索引核对已中断，未确认索引就绪");
    }

    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static BusinessException invalid() { return new BusinessException(ResultCode.PARAM_ERROR, "索引清单范围、修订、摘要、完整片段数或向量不合法"); }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("当前JDK不支持SHA-256"); }
    }
    private static void update(MessageDigest digest, String field) {
        byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
