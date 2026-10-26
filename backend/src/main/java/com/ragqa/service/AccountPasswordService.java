package com.ragqa.service;

import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.SysUserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;

/** 本人改密，旧口令核验与认证版本CAS；不允许企业管理员借此修改其他企业共用的账号。 */
@Service
public class AccountPasswordService {
    private final SysUserMapper users;
    private final CurrentUserService current;
    private final PasswordEncoder encoder;
    private final Semaphore slots = new Semaphore(2);
    private final java.util.concurrent.ConcurrentMap<Long, Long> nextAllowed = new java.util.concurrent.ConcurrentHashMap<>();

    public AccountPasswordService(SysUserMapper users, CurrentUserService current, PasswordEncoder encoder) {
        this.users = users; this.current = current; this.encoder = encoder;
    }

    public void change(AuthenticatedUserPrincipal identity, String previous, String replacement) {
        long id = current.requireUserId(identity);
        if (identity.authVersion() <= 0 || previous == null || previous.isBlank() || previous.length() > 256
                || replacement == null || replacement.isBlank() || replacement.length() < 12
                || replacement.getBytes(StandardCharsets.UTF_8).length > 72
                || replacement.codePoints().anyMatch(Character::isISOControl) || previous.equals(replacement))
            throw new BusinessException(ResultCode.PARAM_ERROR, "新口令须至少12字符且不超过72字节，不能与原口令相同或包含控制字符");
        if (!slots.tryAcquire()) throw new BusinessException(ResultCode.SERVICE_BUSY, "改密服务繁忙，请稍后重试");
        try {
            long now = System.currentTimeMillis();
            synchronized (nextAllowed) {
                nextAllowed.entrySet().removeIf(entry -> entry.getValue() <= now);
                if (nextAllowed.containsKey(id) || nextAllowed.size() >= 1024)
                    throw new BusinessException(ResultCode.SERVICE_BUSY, "改密尝试过于频繁，请至少30秒后再试");
                nextAllowed.put(id, now + 30_000);
            }
            String hash = users.currentPasswordHash(id, identity.authVersion());
            if (hash == null) throw new BusinessException(ResultCode.UNAUTHORIZED);
            if (!encoder.matches(previous, hash)) throw new BusinessException(ResultCode.BIZ_ERROR, "原口令不正确，未修改密码");
            // 不在事务/行锁内执行高成本BCrypt；并发改密或主动撤销会话由CAS拒绝。
            String encoded = encoder.encode(replacement);
            current.requireUserId(identity);
            if (users.changePassword(id, identity.authVersion(), encoded) != 1)
                throw new BusinessException(ResultCode.CONFLICT, "账号认证状态已变化，请重新登录后核对");
        } finally { slots.release(); }
    }
}
