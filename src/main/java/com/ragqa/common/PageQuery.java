package com.ragqa.common;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ragqa.utils.StrUtils;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 分页查询入参基类。业务查询 DTO 继承本类即可获得统一的分页与排序参数。
 *
 * <pre>
 * GET /api/documents?pageNum=1&amp;pageSize=20&amp;sortField=createdAt&amp;sortOrder=desc
 * </pre>
 *
 * <p><b>排序字段的安全处理</b>：{@code sortField} 来自外部输入，若直接拼进 ORDER BY 即为
 * SQL 注入点。这里只放行「字母开头 + 字母/数字/下划线」的字段名，非法输入不会报错，
 * 而是忽略排序（宁可不排序，也不中断业务）。字段名按驼峰传入，转成数据库列名后使用。</p>
 */
@Getter
@Setter
public class PageQuery {

    /** 单页最大条数，与 MybatisPlusConfig 的分页插件上限保持一致 */
    public static final long MAX_PAGE_SIZE = 500L;

    private static final long DEFAULT_PAGE_NUM = 1L;
    private static final long DEFAULT_PAGE_SIZE = 10L;

    /** 只允许字母开头，后跟 0~63 位字母/数字/下划线；排除空格、引号、分号、括号等注入常用字符 */
    private static final Pattern SORT_FIELD_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");

    private static final String ORDER_DESC = "desc";

    /** 页码，从 1 开始 */
    @Min(value = 1, message = "页码必须大于0")
    private long pageNum = DEFAULT_PAGE_NUM;

    /** 每页条数，上限见 {@link #MAX_PAGE_SIZE} */
    @Min(value = 1, message = "每页条数必须大于0")
    @Max(value = MAX_PAGE_SIZE, message = "每页条数不能超过" + MAX_PAGE_SIZE)
    private long pageSize = DEFAULT_PAGE_SIZE;

    /** 排序字段（驼峰），如 createdAt；为空或非法时忽略排序 */
    private String sortField;

    /** 排序方向 asc / desc，默认 asc；无法识别时同样按 asc 处理 */
    private String sortOrder;

    /** 构造 MyBatis-Plus 分页对象，并自动带上排序条件 */
    public <T> Page<T> toPage() {
        Page<T> page = new Page<>(pageNum, pageSize);
        page.addOrder(buildOrderItems());
        return page;
    }

    /**
     * 构造排序条件，无可排序字段时返回空列表。
     *
     * @return 最多一个 {@link OrderItem}，可直接 {@code page.addOrder(...)}
     */
    public List<OrderItem> buildOrderItems() {
        if (!isSortable()) {
            return Collections.emptyList();
        }
        String column = StrUtils.camelToSnake(sortField.trim());
        // 方向只认 desc，其余（含 "asc"、乱填、注入尝试）一律回落为升序
        boolean desc = ORDER_DESC.equalsIgnoreCase(StrUtils.isBlank(sortOrder) ? "" : sortOrder.trim());
        return Collections.singletonList(desc ? OrderItem.desc(column) : OrderItem.asc(column));
    }

    /** 排序字段是否合法：非空白且通过字符白名单 */
    public boolean isSortable() {
        return StrUtils.isNotBlank(sortField) && SORT_FIELD_PATTERN.matcher(sortField.trim()).matches();
    }
}
