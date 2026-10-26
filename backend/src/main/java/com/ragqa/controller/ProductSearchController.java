package com.ragqa.controller;

import com.ragqa.common.Result;
import com.ragqa.common.aspect.NoPayloadLog;
import com.ragqa.domain.dto.ProductSearchResult;
import com.ragqa.service.ProductSearchService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 本机开发商品查询入口；生产入口须在企业认证接入后独立验收。 */
@RestController
@Profile("dev")
@RequestMapping("/api/products")
public class ProductSearchController {

    private final ProductSearchService service;

    public ProductSearchController(ProductSearchService service) {
        this.service = service;
    }

    @NoPayloadLog
    @PostMapping("/search")
    public Result<ProductSearchResult> search(@Valid @RequestBody ProductSearchResult.Request request) {
        return Result.ok(service.search(request.keyword()));
    }
}
