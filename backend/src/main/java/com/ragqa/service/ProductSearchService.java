package com.ragqa.service;

import com.ragqa.domain.dto.ProductSearchResult;

/**
 * 商品查询端口：上层只依赖此契约，渠道鉴权、响应适配及错误处理由实现承担。
 * 仅查询和提供跳转信息，不创建订单、不代支付、不决定补贴资格。
 */
public interface ProductSearchService {

    /** 无匹配返回空结果；非法输入抛业务异常，供应商故障不能伪装成无匹配。 */
    ProductSearchResult search(String keyword);
}
