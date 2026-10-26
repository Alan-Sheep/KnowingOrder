package com.ragqa.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ragqa.common.PageResult;
import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.AffairsRuleMapper;
import com.ragqa.mapper.EnterpriseDirectoryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;

/**
 * 补贴规则与资格预览。
 *
 * <p>资格、周期与可用额度全部由服务端按规则和额度账本计算，模型不参与裁定；
 * 预览只读，不占用额度，真正预占在创建申请时通过条件更新完成。</p>
 */
@Service
public class AffairsRuleService {

    /** 资格预览结果：remainingCents 为 NULL 表示该周期不限额度。金额用 Long，才能被序列化成十进制字符串。 */
    public record Eligibility(String period, Long limitCents, Long usedCents, Long reservedCents,
            Long remainingCents, Long applicationLimitCents, Long maxApplicableCents, Long applicableCents,
            boolean requiresManager, long ruleId, int ruleVersion, String ruleName) { }

    private static final Set<String> PERIODS = Set.of("MONTH", "QUARTER", "YEAR", "NONE");
    private static final Set<String> STATUSES = Set.of("DRAFT", "ACTIVE", "RETIRED");
    private static final long MAX_AMOUNT_CENTS = 1_000_000_000L;
    private static final ZoneId ZONE = ZoneId.of("GMT+8");

    private final EnterpriseMemberService members;
    private final EnterpriseDirectoryMapper directory;
    private final AffairsRuleMapper data;
    private final TransactionTemplate tx;

    public AffairsRuleService(EnterpriseMemberService members, EnterpriseDirectoryMapper directory,
            AffairsRuleMapper data, PlatformTransactionManager manager) {
        this.members = members;
        this.directory = directory;
        this.data = data;
        tx = new TransactionTemplate(manager);
        tx.setTimeout(5);
    }

    /** 员工只能看到生效规则；管理员与财务可按状态筛选，用于草稿与历史版本核对。 */
    public PageResult<AffairsRuleMapper.Rule> rules(AuthenticatedUserPrincipal user, long enterprise, String status,
            long page, long size) {
        long offset = offset(page, size);
        var scope = members.requireMember(user, enterprise);
        String filter = "ADMIN".equals(scope.role()) || "FINANCE".equals(scope.role()) ? status(status) : "ACTIVE";
        var result = new Page<AffairsRuleMapper.Rule>(page, size, data.ruleCount(enterprise, filter));
        result.setRecords(data.rules(enterprise, filter, size, offset));
        return PageResult.of(result);
    }

    /** 新规则总是以草稿创建，版本按同名记录自增；激活后才可用于新申请。 */
    public AffairsRuleMapper.Rule create(AuthenticatedUserPrincipal user, long enterprise, String name, String period,
            Long memberLimit, long applicationLimit, boolean requiresManager) {
        String clean = name(name);
        if (!PERIODS.contains(period)) throw invalid();
        amount(applicationLimit);
        if (memberLimit != null && (memberLimit <= 0 || memberLimit > MAX_AMOUNT_CENTS || memberLimit < applicationLimit))
            throw invalid();
        return tx.execute(s -> {
            var scope = admin(user, enterprise);
            var row = new AffairsRuleMapper.NewRule();
            row.setEnterpriseId(enterprise);
            row.setName(clean);
            row.setVersion(data.maxVersion(enterprise, clean) + 1);
            row.setPeriod(period);
            row.setMemberLimitCents(memberLimit);
            row.setApplicationLimitCents(applicationLimit);
            row.setRequiresManager(requiresManager ? 1 : 0);
            row.setEffectiveFrom(LocalDateTime.now(ZONE));
            long now = System.currentTimeMillis();
            row.setCreatedBy(scope.memberId());
            row.setCreatedMs(now);
            row.setUpdatedMs(now);
            if (data.insertRule(row) != 1) throw conflict();
            return data.rule(enterprise, row.getId());
        });
    }

    /** 激活即停用同企业同名旧 ACTIVE；已受理申请保留原规则版本，不追溯调整。 */
    public AffairsRuleMapper.Rule activate(AuthenticatedUserPrincipal user, long enterprise, long id) {
        positive(id);
        return tx.execute(s -> {
            admin(user, enterprise);
            var rule = data.ruleForUpdate(enterprise, id);
            if (rule == null) throw missing();
            if ("ACTIVE".equals(rule.status())) return rule;
            long now = System.currentTimeMillis();
            data.retireActive(enterprise, rule.name(), id, now);
            if (data.activate(enterprise, id, now) != 1) throw conflict();
            return data.rule(enterprise, id);
        });
    }

    /**
     * 只读资格预览：按当前时间落入规则周期，读取本人额度账本。
     * 账本行不存在时按规则上限试算，真正占用仍以创建申请时的条件更新为准。
     */
    public Eligibility eligibility(AuthenticatedUserPrincipal user, long enterprise, long ruleId, long amountCents) {
        positive(ruleId);
        amount(amountCents);
        var scope = members.requireMember(user, enterprise);
        var rule = data.rule(enterprise, ruleId);
        LocalDateTime at = LocalDateTime.now(ZONE);
        if (rule == null || !"ACTIVE".equals(rule.status()) || rule.effectiveFrom().isAfter(at)
                || (rule.effectiveTo() != null && !rule.effectiveTo().isAfter(at))) throw missing();
        String period = periodKey(rule.period(), at);
        var quota = data.quota(enterprise, scope.memberId(), period);
        Long limit = quota != null ? quota.limitCents() : rule.memberLimitCents();
        long used = quota != null ? quota.usedCents() : 0;
        long reserved = quota != null ? quota.reservedCents() : 0;
        Long remaining = limit == null ? null : Math.max(0, limit - used - reserved);
        long max = remaining == null ? rule.applicationLimitCents()
                : Math.min(rule.applicationLimitCents(), remaining);
        return new Eligibility(period, limit, used, reserved, remaining, rule.applicationLimitCents(), max,
                Math.min(amountCents, max), rule.requiresManager() == 1, rule.id(), rule.version(), rule.name());
    }

    /** 只读成员目录，用于参与人下拉；任何有效成员可读，不返回联系方式。 */
    public java.util.List<AffairsRuleMapper.MemberOption> memberOptions(AuthenticatedUserPrincipal user,
            long enterprise) {
        members.requireMember(user, enterprise);
        return data.memberOptions(enterprise);
    }

    /** 周期标识与 rag_affair_quota.period 保持一致，长度不超过 8。 */
    public static String periodKey(String period, LocalDateTime at) {
        return switch (period) {
            case "MONTH" -> String.format("%04d-%02d", at.getYear(), at.getMonthValue());
            case "QUARTER" -> at.getYear() + "-Q" + ((at.getMonthValue() - 1) / 3 + 1);
            case "YEAR" -> Integer.toString(at.getYear());
            default -> "ALL";
        };
    }

    private EnterpriseMemberContext admin(AuthenticatedUserPrincipal user, long enterprise) {
        var scope = members.requireMember(user, enterprise);
        if (!"ADMIN".equals(scope.role())
                || !scope.equals(directory.findActiveMemberForUpdate(scope.userId(), enterprise, scope.userAuthVersion())))
            throw missing();
        return scope;
    }

    private String status(String value) {
        if (value == null || value.isBlank()) return null;
        String clean = value.strip();
        if (!STATUSES.contains(clean)) throw invalid();
        return clean;
    }

    private String name(String value) {
        if (value == null) throw invalid();
        String clean = value.strip();
        if (clean.isEmpty() || clean.length() > 128
                || clean.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029))
            throw invalid();
        return clean;
    }

    private void amount(long value) {
        if (value <= 0 || value > MAX_AMOUNT_CENTS) throw invalid();
    }

    private void positive(long value) {
        if (value <= 0 || value == Long.MAX_VALUE) throw invalid();
    }

    private long offset(long page, long size) {
        if (page < 1 || page > 100000 || size < 1 || size > 100) throw invalid();
        return (page - 1) * size;
    }

    private BusinessException invalid() {
        return new BusinessException(ResultCode.PARAM_ERROR, "补贴规则参数不正确");
    }

    private BusinessException missing() {
        return new BusinessException(ResultCode.NOT_FOUND, "补贴规则不存在或不可用");
    }

    private BusinessException conflict() {
        return new BusinessException(ResultCode.CONFLICT, "补贴规则已变化，请刷新后重新确认");
    }
}
