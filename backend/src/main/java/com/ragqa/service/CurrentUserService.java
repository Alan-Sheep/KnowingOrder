package com.ragqa.service;

import java.security.Principal;

/** 将服务端认证主体映射为当前有效的本地账号编号。 */
public interface CurrentUserService {

    /**
     * 按认证主体中的稳定用户主键，复核本地账号仍正常、未删除。
     *
     * <p>只接受服务端认证成功后创建的 AuthenticatedUserPrincipal；其他 Principal
     * 类型一律拒绝，不根据名称猜测账号。禁止从请求正文、参数或自定义用户头构造主体。
     * 本方法不校验密码、令牌或会话，后续认证适配层须先验证身份，再传入本项目的主体。</p>
     *
     * @param principal 服务端已验证的本项目认证主体
     * @return 当前有效账号编号，不返回密码散列或账号实体
     * @throws com.ragqa.exception.BusinessException 主体缺失、类型不匹配或账号不可用，业务码为401
     */
    Long requireUserId(Principal principal);

    /** 当前账号的最小展示信息：用户名与展示名（昵称为空时回退用户名），不含口令散列等敏感字段。 */
    record AccountIdentity(String username, String displayName) { }

    /**
     * 按已复核的账号编号读取展示信息。
     *
     * @param userId 已通过 {@link #requireUserId(Principal)} 复核的账号编号
     * @return 用户名与展示名；账号不可用时抛业务码 401
     */
    AccountIdentity identity(Long userId);
}
