package com.ragqa.service.impl;

import com.ragqa.domain.dto.ProductSearchResult;
import com.ragqa.exception.BusinessException;
import com.ragqa.service.ProductSearchService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/** 固定测试目录适配器：仅在dev且选择mock时装配，无数据库和外部网络调用。 */
@Service
@Profile("dev")
@ConditionalOnProperty(name = "knowingorder.products.provider", havingValue = "mock", matchIfMissing = true)
public class MockProductSearchService implements ProductSearchService {

    private static final String NOTICE = "模拟商品与示例价格，不代表真实门店、库存或平台报价。"
            + "演示链接仅打开淘宝首页，不对应真实商品；请在平台自行查询、下单和支付，不产生订单或补贴记录。";

    private static final List<Entry> CATALOG = List.of(
            entry("mock-coffee-01", "经典美式咖啡（示例）", "楼下咖啡店（虚构）", "12.00",
                    "咖啡", "美式", "coffee"),
            entry("mock-coffee-02", "燕麦拿铁（示例）", "楼下咖啡店（虚构）", "18.00",
                    "咖啡", "拿铁", "coffee", "latte"),
            entry("mock-tea-01", "茉莉奶茶（示例）", "街角茶饮店（虚构）", "15.00",
                    "奶茶", "茶饮", "milk tea"),
            entry("mock-dinner-01", "香菇鸡肉饭（示例）", "便当小馆（虚构）", "22.00",
                    "晚餐", "晚饭", "午餐", "便当", "鸡肉", "dinner", "lunch")
    );

    @Override
    public ProductSearchResult search(String keyword) {
        if (keyword == null || keyword.isBlank() || keyword.length() > 80
                || keyword.codePoints().anyMatch(Character::isISOControl)) {
            throw new BusinessException("请输入1至80个字符的商品关键词，不能包含控制字符");
        }
        String query = keyword.strip().toLowerCase(Locale.ROOT);
        List<ProductSearchResult.Item> items = CATALOG.stream()
                .filter(entry -> entry.keywords().stream().anyMatch(query::contains))
                .map(Entry::item).toList();
        return new ProductSearchResult("MOCK", true, NOTICE, items);
    }

    private static Entry entry(String id, String title, String merchant, String price, String... keywords) {
        return new Entry(new ProductSearchResult.Item(id, title, merchant, new BigDecimal(price),
                "CNY", "PLATFORM_HOME", "https://www.taobao.com/"), List.of(keywords));
    }

    private record Entry(ProductSearchResult.Item item, List<String> keywords) {
    }
}
