package com.ragqa.controller;

import com.ragqa.common.Result;
import com.ragqa.common.aspect.NoPayloadLog;
import com.ragqa.domain.dto.ChatAnswer;
import com.ragqa.domain.dto.ChatRequest;
import com.ragqa.service.WebChatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 公开联网问答入口（无鉴权，靠 Nginx 对 /api/chat 的限流防滥用）；独立于企业问答，不检索企业知识。 */
@RestController
@RequestMapping("/api/chat")
public class WebChatController {

    private final WebChatService service;

    public WebChatController(WebChatService service) {
        this.service = service;
    }

    @NoPayloadLog
    @PostMapping
    public Result<ChatAnswer> chat(@Valid @RequestBody ChatRequest request) {
        return Result.ok(service.answer(request));
    }
}
