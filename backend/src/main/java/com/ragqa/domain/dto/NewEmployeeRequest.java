package com.ragqa.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 不生成包含口令的日志字符串；具体校验由服务层执行，以覆盖非HTTP开户调用。 */
public record NewEmployeeRequest(String username, @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password) {
    @Override
    public String toString() {
        return "NewEmployeeRequest(<内容不记录>)";
    }
}
