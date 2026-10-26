package com.ragqa.utils;

import com.ragqa.RagQaApplication;
import com.ragqa.domain.dto.NewEmployeeRequest;
import com.ragqa.service.EnterpriseAccountService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/** 手动执行的非Web首建命令；不在正常应用启动时运行，不设置默认账号密码。 */
public final class BootstrapAdminCommand {
    private BootstrapAdminCommand() {
    }

    public static void main(String[] args) {
        String enterprise = System.getenv("KNOWINGORDER_BOOTSTRAP_ENTERPRISE");
        String username = System.getenv("KNOWINGORDER_BOOTSTRAP_USERNAME");
        String password = System.getenv("KNOWINGORDER_BOOTSTRAP_PASSWORD");
        if (enterprise == null || username == null || password == null) {
            throw new IllegalStateException("请通过本次进程的环境配置注入首建企业、账号和口令，不要放入命令参数或仓库");
        }
        try (var context = new SpringApplicationBuilder(RagQaApplication.class)
                .web(WebApplicationType.NONE).run(args)) {
            var created = context.getBean(EnterpriseAccountService.class)
                    .bootstrapFirstAdmin(enterprise, new NewEmployeeRequest(username, password));
            System.out.println("首建完成：企业编号=" + created.enterpriseId() + "，管理员成员编号=" + created.memberId());
        }
    }
}
