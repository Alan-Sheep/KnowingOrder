package com.ragqa.controller;

import com.ragqa.common.Result;
import com.ragqa.common.aspect.NoPayloadLog;
import com.ragqa.domain.dto.ChatAnswer;
import com.ragqa.domain.dto.ChatRequest;
import com.ragqa.service.WebChatService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 开发环境单次问答入口；上线前由独立的鉴权、消息与后台任务接口替换接入。 */
@RestController
@Profile("dev")
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
