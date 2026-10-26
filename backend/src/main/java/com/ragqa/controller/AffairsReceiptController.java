package com.ragqa.controller;

import com.ragqa.common.PageResult;
import com.ragqa.common.Result;
import com.ragqa.common.aspect.NoPayloadLog;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.mapper.AffairsReceiptMapper;
import com.ragqa.service.AffairsReceiptService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/** 补贴凭证入口：上传只收图片或PDF原件；下载一律附件、禁缓存、禁止内容嗅探。 */
@RestController
@RequestMapping("/api/enterprises/{enterprise}/affairs")
public class AffairsReceiptController {

    private final AffairsReceiptService service;

    public AffairsReceiptController(AffairsReceiptService service) {
        this.service = service;
    }

    @NoPayloadLog
    @GetMapping("/applications/{id}/receipts")
    public Result<PageResult<AffairsReceiptMapper.Receipt>> list(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise, @PathVariable String id,
            @RequestParam(defaultValue = "1") long pageNum, @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok(service.list(user, enterprise, id, pageNum, pageSize));
    }

    @NoPayloadLog
    @PostMapping(value = "/applications/{id}/receipts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<AffairsReceiptMapper.Receipt> upload(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise, @PathVariable String id, @RequestPart("file") MultipartFile file) {
        return Result.ok(service.upload(user, enterprise, id, file));
    }

    @NoPayloadLog
    @GetMapping("/applications/{id}/receipts/{receiptId}/download")
    public ResponseEntity<byte[]> download(
            @AuthenticationPrincipal(expression = "identity") AuthenticatedUserPrincipal user,
            @PathVariable long enterprise, @PathVariable String id, @PathVariable long receiptId) {
        var attachment = service.download(user, enterprise, id, receiptId);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(attachment.name(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff").header("Cache-Control", "no-store")
                .body(attachment.bytes());
    }
}
