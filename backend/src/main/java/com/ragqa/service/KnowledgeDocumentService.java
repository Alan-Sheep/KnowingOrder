package com.ragqa.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ragqa.common.PageResult;
import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.dto.KnowledgeDocuments;
import com.ragqa.domain.entity.RagDocument;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.KnowledgeDocumentMapper;
import com.ragqa.mapper.KnowledgeManagementMapper;
import com.ragqa.rag.ingest.Utf8TextParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/** 本地文字入库用例；解析在数据库事务外进行，发布指针与版本状态在同一事务切换。 */
@Service
public class KnowledgeDocumentService {
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeDocumentService.class);
    private final EnterpriseMemberService members;
    private final EnterpriseResourceService resources;
    private final KnowledgeManagementMapper spaces;
    private final KnowledgeDocumentMapper documents;
    private final PrivateKnowledgeFiles files;
    private final Utf8TextParser parser;
    private final TransactionTemplate transaction;
    private final Semaphore workSlots = new Semaphore(4);
    private final Clock clock;

    @Autowired
    public KnowledgeDocumentService(EnterpriseMemberService members, EnterpriseResourceService resources,
            KnowledgeManagementMapper spaces, KnowledgeDocumentMapper documents, PrivateKnowledgeFiles files,
            Utf8TextParser parser, PlatformTransactionManager manager) {
        this(members, resources, spaces, documents, files, parser, manager, Clock.systemUTC());
    }

    /** 测试可注入固定时钟，不依赖用户电脑日期构造有效期断言。 */
    public KnowledgeDocumentService(EnterpriseMemberService members, EnterpriseResourceService resources,
            KnowledgeManagementMapper spaces, KnowledgeDocumentMapper documents, PrivateKnowledgeFiles files,
            Utf8TextParser parser, PlatformTransactionManager manager, Clock clock) {
        this.members = members;
        this.resources = resources;
        this.spaces = spaces;
        this.documents = documents;
        this.files = files;
        this.parser = parser;
        this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(10);
    }

    public PageResult<KnowledgeDocuments.Document> list(AuthenticatedUserPrincipal user, Long enterpriseId,
            Long spaceId, long pageNum, long pageSize) {
        var scope = readable(user, enterpriseId, spaceId);
        return PageResult.of(documents.documents(page(pageNum, pageSize), scope, spaceId, clock.millis()));
    }

    public PageResult<KnowledgeDocuments.Version> versions(AuthenticatedUserPrincipal user, Long enterpriseId,
            Long spaceId, Long documentId, long pageNum, long pageSize) {
        var scope = readable(user, enterpriseId, spaceId);
        positive(documentId);
        return PageResult.of(documents.versions(page(pageNum, pageSize), scope, spaceId, documentId, clock.millis()),
                KnowledgeDocuments.StoredVersion::view);
    }

    public KnowledgeDocuments.Document document(AuthenticatedUserPrincipal user, Long enterpriseId,
            Long spaceId, Long documentId) {
        var scope = readable(user, enterpriseId, spaceId);
        positive(documentId);
        var document = documents.document(scope, spaceId, documentId, clock.millis());
        if (document == null) throw unavailable();
        return document;
    }

    public KnowledgeDocuments.Version version(AuthenticatedUserPrincipal user, Long enterpriseId,
            Long spaceId, Long documentId, Long versionId) {
        return stored(readable(user, enterpriseId, spaceId), spaceId, documentId, versionId).view();
    }

    public PageResult<KnowledgeDocuments.Chunk> chunks(AuthenticatedUserPrincipal user, Long enterpriseId,
            Long spaceId, Long documentId, Long versionId, long pageNum, long pageSize) {
        var scope = readable(user, enterpriseId, spaceId);
        stored(scope, spaceId, documentId, versionId);
        return PageResult.of(documents.chunks(page(pageNum, pageSize), scope, spaceId, documentId, versionId, clock.millis()));
    }

    public Original original(AuthenticatedUserPrincipal user, Long enterpriseId, Long spaceId,
            Long documentId, Long versionId) {
        return bounded(() -> {
            var scope = readable(user, enterpriseId, spaceId);
            var version = stored(scope, spaceId, documentId, versionId);
            byte[] bytes = files.read(version.getStorageKey(), version.getFileSize(), version.getContentSha256());
            // 原件读取可能耗时；返回前重新核验身份、空间及版本，撤回与撤权不沿用旧快照。
            stored(members.requireCurrent(scope), spaceId, documentId, versionId);
            return new Original(version.getOriginalName(), bytes);
        });
    }

    public KnowledgeDocuments.Version upload(AuthenticatedUserPrincipal user, Long enterpriseId, Long spaceId,
            Upload request, MultipartFile file) {
        return bounded(() -> {
            var scope = readable(user, enterpriseId, spaceId);
            // 在读取文件字节前先校验维护资格，事务提交前仍须加锁复核。
            transaction.execute(status -> { maintain(scope, spaceId); return null; });
            if (request == null || file == null) throw invalid("缺少上传参数或原件");
            String filename = safeName(file.getOriginalFilename());
            String title = Objects.toString(request.title(), filename).strip();
            if (title.isEmpty() || title.length() > 255) throw invalid("文档标题须为1至255个字符");
            String requestId = uuid(request.requestId());
            if (request.documentId() != null) positive(request.documentId());
            dates(request.validFromMs(), request.validUntilMs());
            byte[] bytes;
            try (var input = file.getInputStream()) { bytes = input.readNBytes(Utf8TextParser.MAX_BYTES + 1); }
            catch (IOException ex) { throw new BusinessException(ResultCode.SERVICE_BUSY, "上传连接中断，请使用原请求编号查询或重试"); }
            if (bytes.length == 0) throw invalid("不能上传空文件");
            if (bytes.length > Utf8TextParser.MAX_BYTES) throw new BusinessException(ResultCode.FILE_TOO_LARGE, "首批文字原件最大1MiB");
            String hash = PrivateKnowledgeFiles.sha256(bytes);
            String fingerprint = fingerprint(request, filename, title, hash);
            return transaction.execute(status -> {
                maintain(scope, spaceId);
                var previous = documents.findRequest(scope, spaceId, requestId);
                if (previous != null) {
                    if (previous.getCreatedByMember() != scope.memberId()
                            || !fingerprint.equals(previous.getRequestSha256())) throw conflict("上传请求编号已使用，请勿换参复用");
                    // 幂等键不能成为下载或恢复已删除记录的旁路。
                    return stored(scope, spaceId, previous.getDocumentId(), previous.getId()).view();
                }
                long documentId;
                if (request.documentId() == null) {
                    var document = new RagDocument();
                    document.setTitle(title);
                    check(documents.insertDocument(scope, spaceId, document));
                    if (document.getId() == null || document.getId() <= 0) throw new BusinessException(ResultCode.SYSTEM_ERROR);
                    documentId = document.getId();
                } else {
                    documentId = request.documentId();
                    var document = lockedDocument(scope, spaceId, documentId, request.expectedRevision());
                    advance(scope, spaceId, document);
                }
                int last = documents.lastVersion(scope, spaceId, documentId);
                if (last == Integer.MAX_VALUE) throw conflict("文档版本号已达上限");
                String key = files.save(bytes);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int completion) {
                        if (completion == STATUS_ROLLED_BACK) {
                            try { files.discard(key); }
                            catch (BusinessException ex) { LOG.error("知识原件回滚清理失败，需管理员核对；不记录路径或内容"); }
                        }
                    }
                });
                var version = new KnowledgeDocuments.StoredVersion();
                version.setDocumentId(documentId);
                version.setVersionNo(last + 1);
                version.setUploadRequestId(requestId);
                version.setOriginalName(filename);
                version.setStorageKey(key);
                version.setContentSha256(hash);
                version.setRequestSha256(fingerprint);
                version.setFileSize((long) bytes.length);
                version.setValidFromMs(request.validFromMs());
                version.setValidUntilMs(request.validUntilMs());
                check(documents.insertVersion(scope, spaceId, version));
                return stored(scope, spaceId, documentId, version.getId()).view();
            });
        });
    }

    public KnowledgeDocuments.Version parse(AuthenticatedUserPrincipal user, Long enterpriseId, Long spaceId,
            Long documentId, Long versionId, Long expectedRevision) {
        return parseWithin(user, enterpriseId, spaceId, documentId, versionId, expectedRevision, false, () -> { }, () -> { });
    }

    /** 持久任务栅栏在每段事务内复核；成功回调与片段同事务，拒绝取消、租约丢失后的迟到提交。 */
    KnowledgeDocuments.Version parseWithin(AuthenticatedUserPrincipal user, Long enterpriseId, Long spaceId,
            Long documentId, Long versionId, Long expectedRevision, boolean rebuild, Runnable fence, Runnable complete) {
        return bounded(() -> {
            var scope = readable(user, enterpriseId, spaceId);
            var candidate = transaction.execute(status -> {
                maintain(scope, spaceId);
                fence.run();
                var version = stored(scope, spaceId, documentId, versionId);
                if (!rebuild && "READY".equals(version.getParseStatus())) {
                    if (documents.chunkCount(documentId, versionId) != version.getChunkCount()) throw conflict("片段不完整，请核对或创建新草稿");
                    complete.run();
                    return version;
                }
                lockedDocument(scope, spaceId, documentId, expectedRevision);
                if (!"DRAFT".equals(version.getPublicationStatus())) throw conflict("仅草稿版本可以解析");
                return version;
            });
            if (!rebuild && "READY".equals(candidate.getParseStatus())) return candidate.view();
            List<Utf8TextParser.Part> parts;
            try { parts = parser.parse(candidate.getOriginalName(), files.read(candidate.getStorageKey(), candidate.getFileSize(), candidate.getContentSha256())); }
            catch (BusinessException ex) {
                transaction.execute(status -> {
                    maintain(scope, spaceId);
                    fence.run();
                    var document = lockedDocument(scope, spaceId, documentId, expectedRevision);
                    // 重建失败保留既有片段与READY，不能把可用草稿改成零片段失败态。
                    if (rebuild && "READY".equals(candidate.getParseStatus())) return null;
                    check(documents.parsed(scope, spaceId, documentId, versionId, "FAILED", 0,
                            ex.getCode() == 400 ? "INVALID_TEXT" : ex.getCode() == 504 ? "PARSE_TIMEOUT" : "ORIGINAL_UNAVAILABLE"));
                    advance(scope, spaceId, document);
                    fence.run();
                    return null;
                });
                throw ex;
            }
            return transaction.execute(status -> {
                maintain(scope, spaceId);
                fence.run();
                var current = stored(scope, spaceId, documentId, versionId);
                if (!rebuild && "READY".equals(current.getParseStatus())) {
                    if (documents.chunkCount(documentId, versionId) != current.getChunkCount()) throw conflict("片段不完整，请核对或创建新草稿");
                    complete.run();
                    return current.view();
                }
                var document = lockedDocument(scope, spaceId, documentId, expectedRevision);
                if (!"DRAFT".equals(current.getPublicationStatus())) throw conflict("仅草稿版本可以解析或重建");
                documents.clearChunks(scope, spaceId, documentId, versionId);
                for (int i = 0; i < parts.size(); i += 100) {
                    var batch = parts.subList(i, Math.min(i + 100, parts.size()));
                    if (documents.insertChunks(documentId, versionId, batch) != batch.size()) throw new BusinessException(ResultCode.SYSTEM_ERROR);
                }
                check(documents.parsed(scope, spaceId, documentId, versionId, "READY", parts.size(), null));
                advance(scope, spaceId, document);
                complete.run();
                return stored(scope, spaceId, documentId, versionId).view();
            });
        });
    }

    public KnowledgeDocuments.Document lifecycle(AuthenticatedUserPrincipal user, Long enterpriseId, Long spaceId,
            Long documentId, Long versionId, Long expectedRevision, String action) {
        var scope = readable(user, enterpriseId, spaceId);
        if (!Set.of("PUBLISH", "WITHDRAW", "DELETE_VERSION", "DELETE_DOCUMENT").contains(Objects.toString(action, "")))
            throw invalid("未知生命周期操作");
        return transaction.execute(status -> {
            maintain(scope, spaceId);
            var document = lockedDocument(scope, spaceId, documentId, expectedRevision);
            if ("DELETE_DOCUMENT".equals(action)) {
                check(documents.remove(scope, spaceId, documentId, document.revision()));
                return new KnowledgeDocuments.Document(documentId, spaceId, document.title(), document.ownerMemberId(),
                        null, document.revision() + 1, document.ownerMemberName());
            }
            var version = stored(scope, spaceId, documentId, versionId);
            if ("PUBLISH".equals(action)) {
                long now = clock.millis();
                if (!"READY".equals(version.getParseStatus()) || version.getChunkCount() <= 0
                        || documents.chunkCount(documentId, versionId) != version.getChunkCount())
                    throw conflict("片段尚未就绪，请先完成解析");
                if ((version.getValidFromMs() != null && version.getValidFromMs() > now)
                        || (version.getValidUntilMs() != null && version.getValidUntilMs() <= now))
                    throw conflict("版本尚未生效或已经过期，不能发布");
                if (document.publishedVersionId() != null && !document.publishedVersionId().equals(versionId))
                    check(documents.publication(scope, spaceId, documentId, document.publishedVersionId(), "WITHDRAWN"));
                check(documents.publication(scope, spaceId, documentId, versionId, "PUBLISHED"));
                check(documents.point(scope, spaceId, documentId, versionId, document.revision(), 2, version.getChunkCount()));
            } else {
                boolean current = Objects.equals(document.publishedVersionId(), versionId);
                if ("DELETE_VERSION".equals(action) && current) throw conflict("请先撤回当前发布版本，再删除该版本");
                check(documents.publication(scope, spaceId, documentId, versionId,
                        "WITHDRAW".equals(action) ? "WITHDRAWN" : "DELETED"));
                if (current) check(documents.point(scope, spaceId, documentId, null, document.revision(), 0, 0));
                else advance(scope, spaceId, document);
            }
            return documents.lockDocument(scope, spaceId, documentId);
        });
    }

    public KnowledgeDocuments.Document assignOwner(AuthenticatedUserPrincipal user, Long enterpriseId, Long spaceId,
            Long documentId, Long expectedRevision, Long ownerMemberId) {
        var scope = readable(user, enterpriseId, spaceId);
        positive(ownerMemberId);
        return transaction.execute(status -> {
            maintain(scope, spaceId);
            var document = lockedDocument(scope, spaceId, documentId, expectedRevision);
            if (spaces.lockMember(scope, ownerMemberId) == null || spaces.maintainerMember(scope, spaceId, ownerMemberId) == null)
                throw invalid("负责人须为同企业有效且拥有该空间维护权限的成员");
            check(documents.owner(scope, spaceId, documentId, document.revision(), ownerMemberId));
            // 重新读取以返回新负责人的姓名，前端无需再单独查询成员。
            return documents.document(scope, spaceId, documentId, clock.millis());
        });
    }

    private EnterpriseMemberContext readable(AuthenticatedUserPrincipal user, Long enterpriseId, Long spaceId) {
        positive(spaceId);
        var scope = members.requireMember(user, enterpriseId);
        resources.requireSpace(scope, spaceId);
        return scope;
    }

    private void maintain(EnterpriseMemberContext scope, long spaceId) {
        if (spaces.lockMaintain(scope, spaceId) == null) throw unavailable();
    }

    private KnowledgeDocuments.Document lockedDocument(EnterpriseMemberContext scope, long spaceId, Long documentId, Long revision) {
        positive(documentId);
        positive(revision);
        var document = documents.lockDocument(scope, spaceId, documentId);
        if (document == null || document.ownerMemberId() == null) throw unavailable();
        if (document.revision() != revision || document.revision() == Long.MAX_VALUE) throw conflict("文档已变化，请刷新后再操作");
        return document;
    }

    private KnowledgeDocuments.StoredVersion stored(EnterpriseMemberContext scope, long spaceId, Long documentId, Long versionId) {
        positive(documentId);
        positive(versionId);
        var version = documents.readVersion(scope, spaceId, documentId, versionId, clock.millis());
        if (version == null) throw unavailable();
        return version;
    }

    private void advance(EnterpriseMemberContext scope, long spaceId, KnowledgeDocuments.Document document) {
        check(documents.advance(scope, spaceId, document.id(), document.revision()));
    }

    private <T> Page<T> page(long number, long size) {
        if (number < 1 || size < 1 || size > 500 || number - 1 > Long.MAX_VALUE / size) throw invalid("分页参数不合法");
        return new Page<>(number, size);
    }

    private static void positive(Long number) {
        if (number == null || number <= 0) throw invalid("编号或版本必须为正整数");
    }

    private static String safeName(String name) {
        if (name == null || name.isBlank() || name.length() > 255 || name.contains("/") || name.contains("\\")
                || name.chars().anyMatch(c -> Character.isISOControl(c) || c == ':' || c == '"')) throw invalid("原件名称不合法");
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".txt") && !lower.endsWith(".md") && !lower.endsWith(".pdf") && !lower.endsWith(".docx"))
            throw invalid("仅支持UTF-8 TXT/Markdown、文字层PDF及DOCX；不支持DOC、扫描识别或图像OCR");
        return name;
    }

    private static String uuid(String value) {
        try {
            if (value == null || !UUID.fromString(value).toString().equals(value)) throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException ex) { throw invalid("上传请求编号须为标准小写UUID"); }
    }

    private static void dates(Long from, Long until) {
        if ((from != null && from < 0) || (until != null && until < 0) || (from != null && until != null && from >= until))
            throw invalid("文档有效期不合法：结束时间必须晚于开始时间");
    }

    private static String fingerprint(Upload upload, String name, String title, String contentHash) {
        var value = new StringBuilder();
        for (Object item : new Object[] { upload.documentId(), upload.expectedRevision(), name, title,
                upload.validFromMs(), upload.validUntilMs(), contentHash }) {
            String field = Objects.toString(item, "<空>");
            value.append(field.length()).append(':').append(field);
        }
        return PrivateKnowledgeFiles.sha256(value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private <T> T bounded(Supplier<T> work) {
        if (!workSlots.tryAcquire()) throw new BusinessException(ResultCode.SERVICE_BUSY, "知识入库或原件读取繁忙，请稍后手动重试");
        try { return work.get(); }
        finally { workSlots.release(); }
    }

    private void check(int count) { if (count != 1) throw conflict("记录已变化，请刷新后再操作"); }
    private static BusinessException invalid(String message) { return new BusinessException(ResultCode.PARAM_ERROR, message); }
    private static BusinessException conflict(String message) { return new BusinessException(ResultCode.CONFLICT, message); }
    private static BusinessException unavailable() { return new BusinessException(ResultCode.NOT_FOUND, "文档不存在或不可访问"); }

    public record Upload(String requestId, Long documentId, Long expectedRevision, String title, Long validFromMs, Long validUntilMs) { }
    public record Original(String name, byte[] bytes) { }
}
