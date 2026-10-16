package com.ragqa.common;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.ragqa.utils.StrUtils;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PageQuery} 的单元测试：不启动 Spring，只验证参数边界与排序字段的安全处理。
 */
class PageQueryTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    @DisplayName("默认值：第1页、每页10条、不带排序")
    void defaultsAreSane() {
        PageQuery query = new PageQuery();
        assertThat(query.getPageNum()).isEqualTo(1L);
        assertThat(query.getPageSize()).isEqualTo(10L);
        assertThat(query.isSortable()).isFalse();
        assertThat(query.buildOrderItems()).isEmpty();
    }

    @Test
    @DisplayName("toPage 自动带上排序条件，调用方无需再 addOrder")
    void toPageCarriesOrder() {
        PageQuery query = new PageQuery();
        query.setSortField("createdAt");
        query.setSortOrder("desc");

        List<OrderItem> orders = query.toPage().orders();
        assertThat(orders).hasSize(1);
        assertThat(orders.get(0).getColumn()).isEqualTo("created_at");
        assertThat(orders.get(0).isAsc()).isFalse();
    }

    @Test
    @DisplayName("分页参数边界：页码 0 与超过上限的每页条数都被校验拦截")
    void invalidPagingParamsAreRejected() {
        PageQuery query = new PageQuery();
        query.setPageNum(0);
        query.setPageSize(PageQuery.MAX_PAGE_SIZE + 1);

        Set<ConstraintViolation<PageQuery>> violations = validator.validate(query);
        assertThat(violations).hasSize(2);
        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .containsExactlyInAnyOrder("pageNum", "pageSize");
    }

    @Test
    @DisplayName("每页条数取上限值时合法")
    void pageSizeAtUpperBoundIsValid() {
        PageQuery query = new PageQuery();
        query.setPageSize(PageQuery.MAX_PAGE_SIZE);
        assertThat(validator.validate(query)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "id;DROP TABLE sys_user",
            "id--",
            "1=1",
            "id OR 1=1",
            "id)",
            "(select 1)",
            "id, username",
            "id'",
            "",
            "   "
    })
    @DisplayName("非法排序字段被静默忽略：不抛异常、不产生任何 ORDER BY")
    void maliciousSortFieldIsIgnored(String sortField) {
        PageQuery query = new PageQuery();
        query.setSortField(sortField);

        assertThat(query.isSortable()).isFalse();
        assertThat(query.buildOrderItems()).isEmpty();
        assertThat(query.toPage().orders()).isEmpty();
    }

    @Test
    @DisplayName("排序方向只认 desc，其余一律回落升序")
    void onlyDescIsRecognized() {
        PageQuery query = new PageQuery();
        query.setSortField("createdAt");
        query.setSortOrder("DESC");
        assertThat(query.buildOrderItems().get(0).isAsc()).isFalse();

        query.setSortOrder("asc");
        assertThat(query.buildOrderItems().get(0).isAsc()).isTrue();

        // 试图注入的方向同样回落为升序
        query.setSortOrder("desc; DROP TABLE sys_user");
        assertThat(query.buildOrderItems().get(0).isAsc()).isTrue();
    }

    @Test
    @DisplayName("驼峰转下划线")
    void camelToSnake() {
        assertThat(StrUtils.camelToSnake("createdAt")).isEqualTo("created_at");
        assertThat(StrUtils.camelToSnake("documentId")).isEqualTo("document_id");
        assertThat(StrUtils.camelToSnake("ID")).isEqualTo("id");
        assertThat(StrUtils.camelToSnake("id")).isEqualTo("id");
        assertThat(StrUtils.camelToSnake(null)).isNull();
        assertThat(StrUtils.camelToSnake("  ")).isEqualTo("  ");
    }
}
