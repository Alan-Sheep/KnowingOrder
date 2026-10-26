package com.ragqa.domain.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

/** 只接收空间名称；保留JSON类型，避免数字、布尔值被强制转换为合法名称。 */
public record CreateKnowledgeSpaceRequest(@NotNull(message = "空间名称不能为空") JsonNode name) {

    /** 名称内容和规范化后的长度由服务层校验，覆盖非HTTP调用。 */
    @AssertTrue(message = "空间名称必须为字符串")
    public boolean isTextName() {
        return name == null || name.isTextual();
    }
}
