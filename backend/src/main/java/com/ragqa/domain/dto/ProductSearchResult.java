package com.ragqa.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品查询的渠道无关契约；模拟标识必须随结果传递，不得作为订单或财务事实。
 *
 * @param provider 数据提供者，不代表商品所属平台已授权本系统
 * @param simulated 是否为模拟数据
 * @param notice 数据来源、价格与跳转能力说明
 * @param items 匹配商品摘要，未匹配时为空列表
 */
public record ProductSearchResult(String provider, boolean simulated, String notice, List<Item> items) {

    public ProductSearchResult {
        items = List.copyOf(items);
    }

    /** 查询条件不接受身份、金额、任意跳转地址或其他执行指令。 */
    public record Request(@NotBlank(message = "请输入商品关键词")
                          @Size(max = 80, message = "商品关键词最多80个字符") String keyword) {
    }

    /**
     * 展示摘要；示例价格不是实际售价，跳转目的是平台搜索页而不是商品详情页。
     *
     * @param referencePrice 展示参考价，使用定点金额而非浮点数
     * @param linkType 跳转目的类型；当前Mock为 PLATFORM_SEARCH（淘宝搜索页，已带关键词）
     */
    public record Item(String id, String title, String merchant, BigDecimal referencePrice,
                       String currency, String linkType, String jumpUrl) {
    }
}
