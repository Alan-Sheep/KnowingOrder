package com.ragqa.common;

import com.baomidou.mybatisplus.core.metadata.IPage;
import lombok.Getter;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 分页查询出参，配合 {@link Result} 使用：{@code Result<PageResult<XxxVO>>}。
 *
 * <p>与 {@link Result} 一致，字段全部 final、构造器私有，只能通过静态工厂创建。</p>
 *
 * @param <T> 列表元素类型，既可以是实体，也可以是 VO
 */
@Getter
public final class PageResult<T> {

    /** 当前页数据 */
    private final List<T> records;

    /** 满足条件的总记录数 */
    private final long total;

    /** 当前页码 */
    private final long pageNum;

    /** 每页条数 */
    private final long pageSize;

    /** 总页数 */
    private final long pages;

    private PageResult(List<T> records, long total, long pageNum, long pageSize, long pages) {
        this.records = records;
        this.total = total;
        this.pageNum = pageNum;
        this.pageSize = pageSize;
        this.pages = pages;
    }

    /** 直接由 MyBatis-Plus 分页对象转换 */
    public static <T> PageResult<T> of(IPage<T> page) {
        return new PageResult<>(page.getRecords(), page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    /**
     * 由分页对象转换，并把每条记录映射为另一种类型（实体转 VO）。
     *
     * <pre>
     * PageResult&lt;DocumentVO&gt; vo = PageResult.of(page, DocumentVO::from);
     * </pre>
     */
    public static <S, T> PageResult<T> of(IPage<S> page, Function<? super S, ? extends T> converter) {
        List<T> converted = page.getRecords().stream()
                .map(converter)
                .collect(Collectors.toList());
        return new PageResult<>(converted, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }
}
