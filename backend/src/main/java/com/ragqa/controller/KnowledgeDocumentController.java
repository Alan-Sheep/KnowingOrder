package com.ragqa.controller;

import com.ragqa.common.PageResult;
import com.ragqa.common.Result;
import com.ragqa.common.aspect.NoPayloadLog;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.KnowledgeDocuments;
import com.ragqa.service.KnowledgeDocumentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/** 文档私有管理API；原件强制附件下载，其余JSON统一使用Result，不内联执行Markdown/HTML。 */
@RestController
@RequestMapping("/api/enterprises/{enterpriseId}/knowledge-spaces/{spaceId}/documents")
public class KnowledgeDocumentController {
    private final KnowledgeDocumentService service;
    public KnowledgeDocumentController(KnowledgeDocumentService service) { this.service = service; }

    @NoPayloadLog
    @GetMapping
    public Result<PageResult<KnowledgeDocuments.Document>> list(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable Long enterpriseId, @PathVariable Long spaceId,
            @RequestParam(defaultValue = "1") long pageNum, @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok(service.list(user, enterpriseId, spaceId, pageNum, pageSize));
    }

    @NoPayloadLog
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<KnowledgeDocuments.Version> upload(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable Long enterpriseId, @PathVariable Long spaceId,
            @RequestPart("metadata") KnowledgeDocumentService.Upload metadata, @RequestPart("file") MultipartFile file) {
        return Result.ok(service.upload(user, enterpriseId, spaceId, metadata, file));
    }

    @NoPayloadLog
    @GetMapping("/{documentId}")
    public Result<KnowledgeDocuments.Document> document(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable Long enterpriseId, @PathVariable Long spaceId, @PathVariable Long documentId) {
        return Result.ok(service.document(user, enterpriseId, spaceId, documentId));
    }

    @NoPayloadLog
    @GetMapping("/{documentId}/versions")
    public Result<PageResult<KnowledgeDocuments.Version>> versions(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable Long enterpriseId, @PathVariable Long spaceId, @PathVariable Long documentId,
            @RequestParam(defaultValue = "1") long pageNum, @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok(service.versions(user, enterpriseId, spaceId, documentId, pageNum, pageSize));
    }

    @NoPayloadLog
    @GetMapping("/{documentId}/versions/{versionId}")
    public Result<KnowledgeDocuments.Version> version(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable Long enterpriseId, @PathVariable Long spaceId, @PathVariable Long documentId,
            @PathVariable Long versionId) {
        return Result.ok(service.version(user, enterpriseId, spaceId, documentId, versionId));
    }

    @NoPayloadLog
    @GetMapping("/{documentId}/versions/{versionId}/chunks")
    public Result<PageResult<KnowledgeDocuments.Chunk>> chunks(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable Long enterpriseId, @PathVariable Long spaceId, @PathVariable Long documentId,
            @PathVariable Long versionId, @RequestParam(defaultValue = "1") long pageNum,
            @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok(service.chunks(user, enterpriseId, spaceId, documentId, versionId, pageNum, pageSize));
    }

    @NoPayloadLog
    @GetMapping("/{documentId}/versions/{versionId}/original")
    public ResponseEntity<byte[]> original(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable Long enterpriseId, @PathVariable Long spaceId, @PathVariable Long documentId,
            @PathVariable Long versionId) {
        var original = service.original(user, enterpriseId, spaceId, documentId, versionId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(original.name(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff").header("Cache-Control", "no-store")
                .body(original.bytes());
    }

    @NoPayloadLog
    @PostMapping("/{documentId}/versions/{versionId}/parse")
    public Result<KnowledgeDocuments.Version> parse(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable Long enterpriseId, @PathVariable Long spaceId, @PathVariable Long documentId,
            @PathVariable Long versionId, @Valid @RequestBody Revision request) {
        return Result.ok(service.parse(user, enterpriseId, spaceId, documentId, versionId, request.expectedRevision()));
    }

    @NoPayloadLog
    @PostMapping("/{documentId}/lifecycle")
    public Result<KnowledgeDocuments.Document> lifecycle(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable Long enterpriseId, @PathVariable Long spaceId, @PathVariable Long documentId,
            @Valid @RequestBody Lifecycle request) {
        return Result.ok(service.lifecycle(user, enterpriseId, spaceId, documentId, request.versionId(),
                request.expectedRevision(), request.action()));
    }

    @NoPayloadLog
    @PostMapping("/{documentId}/owner")
    public Result<KnowledgeDocuments.Document> owner(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable Long enterpriseId, @PathVariable Long spaceId, @PathVariable Long documentId,
            @Valid @RequestBody Owner request) {
        return Result.ok(service.assignOwner(user, enterpriseId, spaceId, documentId,
                request.expectedRevision(), request.ownerMemberId()));
    }

    public record Owner(@NotNull Long expectedRevision, @NotNull Long ownerMemberId) { }
    public record Revision(@NotNull Long expectedRevision) { }
    public record Lifecycle(@NotNull Long expectedRevision, Long versionId, String action) { }
}
