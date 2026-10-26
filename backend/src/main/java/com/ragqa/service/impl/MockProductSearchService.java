package com.ragqa.service.impl;

import com.ragqa.domain.dto.ProductSearchResult;
import com.ragqa.exception.BusinessException;
import com.ragqa.service.ProductSearchService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 固定示例目录适配器：无数据库调用、无外部网络请求、不产生订单或补贴记录，
 * 因此不限制运行环境；只有配置 {@code knowingorder.products.provider} 为其它值时才换用真实实现。
 * <p>
 * 跳转目的地是<b>淘宝搜索页</b>（关键词已填好），不是首页、也不是商品详情页：
 * 示例商品本身是虚构的，不存在对应的真实详情页，把关键词带到搜索页是当前唯一诚实且可用的做法。
 * 搜索结果、报价、库存全部由平台决定，与这里的示例价无关。
 */
@Service
@ConditionalOnProperty(name = "knowingorder.products.provider", havingValue = "mock", matchIfMissing = true)
public class MockProductSearchService implements ProductSearchService {

    private static final String NOTICE = "模拟商品与示例价格，不代表真实门店、库存或平台报价。"
            + "演示链接会打开淘宝搜索页并已填入商品名，搜索结果与价格由平台决定，不对应本系统的示例商品；"
            + "下单与支付请在平台自行完成，本系统不产生订单或补贴记录。";

    /** 跳转只允许淘宝搜索域，且查询串只带一个 q 参数；接入真实渠道时重新评审白名单。 */
    private static final String SEARCH_URL_PREFIX = "https://s.taobao.com/search?q=";

    private static final List<Entry> CATALOG = List.of(
            entry("mock-coffee-01", "经典美式咖啡（示例）", "楼下咖啡店（虚构）", "12.00",
                    "美式咖啡", "咖啡", "美式", "coffee"),
            entry("mock-coffee-02", "燕麦拿铁（示例）", "楼下咖啡店（虚构）", "18.00",
                    "燕麦拿铁", "咖啡", "拿铁", "coffee", "latte"),
            entry("mock-tea-01", "茉莉奶茶（示例）", "街角茶饮店（虚构）", "15.00",
                    "茉莉奶茶", "奶茶", "茶饮", "milk tea"),
            entry("mock-dinner-01", "香菇鸡肉饭（示例）", "便当小馆（虚构）", "22.00",
                    "香菇鸡肉饭", "晚餐", "晚饭", "午餐", "便当", "鸡肉", "dinner", "lunch")
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

    private static Entry entry(String id, String title, String merchant, String price,
                               String searchTerm, String... keywords) {
        // 空格编码成 %20 而不是 +，避免个别平台把 + 当字面量。
        String encoded = URLEncoder.encode(searchTerm, StandardCharsets.UTF_8).replace("+", "%20");
        return new Entry(new ProductSearchResult.Item(id, title, merchant, new BigDecimal(price),
                "CNY", "PLATFORM_SEARCH", SEARCH_URL_PREFIX + encoded), List.of(keywords));
    }

    private record Entry(ProductSearchResult.Item item, List<String> keywords) {
    }
}
