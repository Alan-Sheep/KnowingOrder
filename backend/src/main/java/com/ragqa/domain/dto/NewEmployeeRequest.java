package com.ragqa.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 不生成包含口令的日志字符串；具体校验由服务层执行，以覆盖非HTTP开户调用。
 * displayName 为员工真实姓名（可选），写入 sys_user.nickname 后全系统作为显示名。
 */
public record NewEmployeeRequest(String username, @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) String password,
        String displayName) {
    @Override
    public String toString() {
        return "NewEmployeeRequest(<内容不记录>)";
    }
}
