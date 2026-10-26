package com.ragqa.controller;

import com.ragqa.common.Result;
import com.ragqa.common.aspect.NoPayloadLog;
import com.ragqa.domain.dto.ProductSearchResult;
import com.ragqa.service.ProductSearchService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品查询入口。
 * <p>返回的是固定示例目录（{@code MockProductSearchService}），不查询真实门店、不产生订单，
 * 因此开发与生产环境都可装配；接入真实渠道时用 {@code knowingorder.products.provider} 切换实现。
 */
@RestController
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
