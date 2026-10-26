package com.ragqa.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ragqa.common.PageResult;
import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.AffairsApplicationMapper;
import com.ragqa.mapper.AffairsRuleMapper;
import com.ragqa.utils.JsonUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 补贴申请：提交、修改、撤回与参与确认。
 *
 * <p>额度按申请人整笔预占，参与分摊只用于他人确认；所有金额以分为单位。
 * 申请版本只增不改，旧版本确认不会被新内容复用。</p>
 */
@Service
public class AffairsApplicationService {

    /** 参与分摊；申请人份额可以为 0，其他人必须为正。 */
    public record Share(long memberId, long shareCents) { }

    public record View(String id, Long ruleId, int ruleVersion, String ruleName, String platform,
            String externalOrderNo, LocalDateTime occurredAt, Long amountCents, String status, int currentVersion,
            String quotaPeriod, Long reservedCents, boolean requiresManager, Long applicantMemberId,
            Long payerMemberId, Long payeeMemberId, long createdMs,
            List<AffairsApplicationMapper.Participant> participants) { }

    private static final Set<String> STATUSES = Set.of("SUBMITTED", "CONFIRMING", "VERIFIED",
            "PENDING_SETTLEMENT", "SETTLED", "REJECTED", "CANCELLED");
    private static final Set<String> CANCELLABLE = Set.of("SUBMITTED", "CONFIRMING", "VERIFIED");
    private static final long MAX_AMOUNT_CENTS = 1_000_000_000L;
    private static final ZoneId ZONE = ZoneId.of("GMT+8");
    private static final String UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    /**
     * 费用类型标识：既接受前端预置的英文 key（travel-hotel），也接受员工在「其他」里自己填的中文名。
     * 该字段只用于分类展示与人工核对，不参与额度计算，因此不做枚举白名单。
     */
    private static final String PLATFORM = "[\\p{IsHan}A-Za-z0-9_\\- ]{1,32}";

    private final EnterpriseMemberService members;
    private final AffairsRuleMapper rules;
    private final AffairsApplicationMapper data;
    private final JsonUtils json;
    private final TransactionTemplate tx;

    public AffairsApplicationService(EnterpriseMemberService members, AffairsRuleMapper rules,
            AffairsApplicationMapper data, JsonUtils json, PlatformTransactionManager manager) {
        this.members = members;
        this.rules = rules;
        this.data = data;
        this.json = json;
        tx = new TransactionTemplate(manager);
        tx.setTimeout(5);
    }

    /**
     * 提交申请：id 为客户端 UUID，重复提交返回已有记录；同一外部订单号只受理一次。
     * 先用条件更新预占额度，再写申请与版本，任何一步失败整体回滚。
     */
    public View create(AuthenticatedUserPrincipal user, long enterprise, String id, long ruleId, String platform,
            String externalOrderNo, LocalDateTime occurredAt, long amountCents, List<Share> shares) {
        String key = uuid(id);
        String channel = platform(platform);
        String order = order(externalOrderNo);
        amount(amountCents);
        shareList(shares);
        return tx.execute(s -> {
            var scope = members.requireMember(user, enterprise);
            var existing = data.applicationForUpdate(key);
            if (existing != null) {
                if (existing.enterpriseId() != enterprise || existing.applicantMemberId() != scope.memberId())
                    throw missing();
                return view(enterprise, key);
            }
            LocalDateTime at = occurredAt == null ? LocalDateTime.now(ZONE) : occurredAt;
            var rule = rules.ruleForUpdate(enterprise, ruleId);
            if (rule == null || !"ACTIVE".equals(rule.status())) throw missing();
            if (rule.effectiveFrom().isAfter(at) || (rule.effectiveTo() != null && !rule.effectiveTo().isAfter(at)))
                throw missing();
            if (amountCents > rule.applicationLimitCents())
                throw new BusinessException(ResultCode.PARAM_ERROR, "超过该规则的单笔补贴上限");
            shares(shares, amountCents, scope.memberId(), enterprise);
            String period = AffairsRuleService.periodKey(rule.period(), at);
            long now = System.currentTimeMillis();
            data.insertQuota(enterprise, scope.memberId(), period, rule.memberLimitCents(), now);
            if (data.reserveQuota(enterprise, scope.memberId(), period, amountCents, now) != 1)
                throw new BusinessException(ResultCode.CONFLICT, "本期补贴额度不足，请核对后再提交");
            var row = new AffairsApplicationMapper.NewApplication();
            row.setId(key);
            row.setEnterpriseId(enterprise);
            row.setApplicantMemberId(scope.memberId());
            row.setRuleId(rule.id());
            row.setRuleVersion(rule.version());
            row.setPlatform(channel);
            row.setExternalOrderNo(order);
            row.setOccurredAt(at);
            row.setAmountCents(amountCents);
            row.setPayerMemberId(scope.memberId());
            row.setStatus("SUBMITTED");
            row.setCurrentVersion(1);
            row.setQuotaPeriod(period);
            row.setReservedCents(amountCents);
            row.setCreatedMs(now);
            row.setUpdatedMs(now);
            try {
                if (data.insertApplication(row) != 1) throw conflict();
            } catch (org.springframework.dao.DuplicateKeyException ex) {
                throw new BusinessException(ResultCode.CONFLICT, "该订单号已提交过申请，请刷新我的申请核对");
            }
            snapshot(key, 1, amountCents, shares, "创建申请", scope.memberId(), rule, channel, order, at);
            confirmRows(key, 1, shares, scope.memberId());
            long pending = data.pendingCount(key, 1);
            String status = pending == 0 ? (rule.requiresManager() == 1 ? "VERIFIED" : "PENDING_SETTLEMENT")
                    : "SUBMITTED";
            if (pending == 0 && data.updateStatus(enterprise, key, status, amountCents, 1, now) != 1)
                throw conflict();
            return view(enterprise, key);
        });
    }

    public PageResult<AffairsApplicationMapper.Summary> mine(AuthenticatedUserPrincipal user, long enterprise,
            String status, long pageNum, long pageSize) {
        long offset = offset(pageNum, pageSize);
        var scope = members.requireMember(user, enterprise);
        String filter = status(status);
        var result = new Page<AffairsApplicationMapper.Summary>(pageNum, pageSize,
                data.myCount(enterprise, scope.memberId(), filter));
        result.setRecords(data.myApplications(enterprise, scope.memberId(), filter, pageSize, offset));
        return PageResult.of(result);
    }

    /** 申请人、当前及历史参与成员、管理员与财务可查看详情；其他人按不存在处理。 */
    public View detail(AuthenticatedUserPrincipal user, long enterprise, String id) {
        String key = uuid(id);
        var scope = members.requireMember(user, enterprise);
        var row = data.detail(enterprise, key);
        if (row == null) throw missing();
        boolean allowed = row.applicantMemberId() == scope.memberId()
                || "ADMIN".equals(scope.role()) || "FINANCE".equals(scope.role())
                || data.hasParticipant(key, scope.memberId()) != null;
        if (!allowed) throw missing();
        return view(enterprise, key);
    }

    /** 修改生成新版本：按金额差额调整占用，旧版本确认失效，需重新确认。 */
    public View revise(AuthenticatedUserPrincipal user, long enterprise, String id, int expectedVersion,
            long amountCents, List<Share> shares, String reason) {
        String key = uuid(id);
        positive(expectedVersion);
        amount(amountCents);
        shareList(shares);
        String note = text(reason, 200, "请填写变更原因");
        return tx.execute(s -> {
            var scope = members.requireMember(user, enterprise);
            var app = data.applicationForUpdate(key);
            if (app == null || app.enterpriseId() != enterprise || app.applicantMemberId() != scope.memberId())
                throw missing();
            if (app.currentVersion() != expectedVersion) throw conflict();
            if (Set.of("SETTLED", "REJECTED", "CANCELLED").contains(app.status()))
                throw new BusinessException(ResultCode.CONFLICT, "当前状态不能修改申请");
            var rule = rules.ruleForUpdate(enterprise, app.ruleId());
            if (rule == null) throw missing();
            if (amountCents > rule.applicationLimitCents())
                throw new BusinessException(ResultCode.PARAM_ERROR, "超过该规则的单笔补贴上限");
            shares(shares, amountCents, scope.memberId(), enterprise);
            long delta = amountCents - app.amountCents();
            long now = System.currentTimeMillis();
            if (delta > 0 && data.reserveQuota(enterprise, scope.memberId(), app.quotaPeriod(), delta, now) != 1)
                throw new BusinessException(ResultCode.CONFLICT, "本期补贴额度不足，请核对后再提交");
            if (delta < 0) data.releaseQuota(enterprise, scope.memberId(), app.quotaPeriod(), -delta, now);
            int next = app.currentVersion() + 1;
            snapshot(key, next, amountCents, shares, note, scope.memberId(), rule, app.platform(), null, null);
            confirmRows(key, next, shares, scope.memberId());
            long pending = data.pendingCount(key, next);
            String status = pending == 0 ? (rule.requiresManager() == 1 ? "VERIFIED" : "PENDING_SETTLEMENT")
                    : "SUBMITTED";
            if (data.revise(enterprise, key, amountCents, next, status, delta, app.revision(),
                    expectedVersion, now) != 1) throw conflict();
            return view(enterprise, key);
        });
    }

    /** 撤回只允许在主管批准前；结算环节的退回由财务流程处理。 */
    public View cancel(AuthenticatedUserPrincipal user, long enterprise, String id) {
        String key = uuid(id);
        return tx.execute(s -> {
            var scope = members.requireMember(user, enterprise);
            var app = data.applicationForUpdate(key);
            if (app == null || app.enterpriseId() != enterprise || app.applicantMemberId() != scope.memberId())
                throw missing();
            if (!CANCELLABLE.contains(app.status()))
                throw new BusinessException(ResultCode.CONFLICT, "当前状态不能撤回申请");
            long now = System.currentTimeMillis();
            data.releaseQuota(enterprise, scope.memberId(), app.quotaPeriod(), app.reservedCents(), now);
            if (data.updateStatus(enterprise, key, "CANCELLED", 0, app.revision(), now) != 1) throw conflict();
            return view(enterprise, key);
        });
    }

    /**
     * 参与确认：只对当前版本有效；任一人拒绝即整单退回并释放额度。
     * 全部确认后进入 VERIFIED；规则不要求主管时直接进入 PENDING_SETTLEMENT。
     */
    public View confirm(AuthenticatedUserPrincipal user, long enterprise, String id, int version, boolean accept,
            String note) {
        String key = uuid(id);
        positive(version);
        String clean = note == null || note.isBlank() ? null : text(note, 200, "说明过长");
        return tx.execute(s -> {
            var scope = members.requireMember(user, enterprise);
            var app = data.applicationForUpdate(key);
            if (app == null || app.enterpriseId() != enterprise) throw missing();
            if (app.currentVersion() != version) throw conflict();
            var row = data.participantForUpdate(key, version, scope.memberId());
            if (row == null) throw missing();
            if (!"PENDING".equals(row.status()))
                throw new BusinessException(ResultCode.CONFLICT, "该参与确认已处理");
            long now = System.currentTimeMillis();
            if (data.decideParticipant(row.id(), accept ? "CONFIRMED" : "REJECTED", now, clean) != 1)
                throw conflict();
            var rule = rules.rule(enterprise, app.ruleId());
            String status;
            long reserved = app.reservedCents();
            if (!accept || data.rejectedCount(key, version) > 0) {
                status = "REJECTED";
                reserved = 0;
                data.releaseQuota(enterprise, app.applicantMemberId(), app.quotaPeriod(),
                        app.reservedCents(), now);
            } else if (data.pendingCount(key, version) == 0) {
                status = rule != null && rule.requiresManager() == 1 ? "VERIFIED" : "PENDING_SETTLEMENT";
            } else {
                status = "CONFIRMING";
            }
            if (data.updateStatus(enterprise, key, status, reserved, app.revision(), now) != 1) throw conflict();
            return view(enterprise, key);
        });
    }

    /** 待我确认：只列当前版本、仍待处理且申请未撤回的条目。 */
    public PageResult<AffairsApplicationMapper.Pending> pendingForMe(AuthenticatedUserPrincipal user,
            long enterprise, long pageNum, long pageSize) {
        long offset = offset(pageNum, pageSize);
        var scope = members.requireMember(user, enterprise);
        var result = new Page<AffairsApplicationMapper.Pending>(pageNum, pageSize,
                data.pendingForMeCount(enterprise, scope.memberId()));
        result.setRecords(data.pendingForMe(enterprise, scope.memberId(), pageSize, offset));
        return PageResult.of(result);
    }

    private void snapshot(String applicationId, int version, long amount, List<Share> shares, String reason,
            long by, AffairsRuleMapper.Rule rule, String platform, String order, LocalDateTime at) {
        var row = new AffairsApplicationMapper.NewVersion();
        row.setApplicationId(applicationId);
        row.setVersion(version);
        row.setAmountCents(amount);
        row.setParticipantsJson(json.toJson(shares));
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("ruleId", rule.id());
        facts.put("ruleName", rule.name());
        facts.put("ruleVersion", rule.version());
        facts.put("period", rule.period());
        facts.put("memberLimitCents", rule.memberLimitCents());
        facts.put("applicationLimitCents", rule.applicationLimitCents());
        facts.put("requiresManager", rule.requiresManager() == 1);
        facts.put("platform", platform);
        facts.put("externalOrderNo", order);
        if (at != null) facts.put("occurredAt", at);
        facts.put("amountCents", amount);
        row.setSnapshotJson(json.toJson(facts));
        row.setChangeReason(reason);
        row.setCreatedBy(by);
        row.setCreatedMs(System.currentTimeMillis());
        if (data.insertVersion(row) != 1) throw conflict();
    }

    /** 申请人视为已确认，只为其他参与成员创建待确认行。 */
    private void confirmRows(String applicationId, int version, List<Share> shares, long applicant) {
        for (var share : shares) {
            if (share.memberId() == applicant) continue;
            if (data.insertParticipant(applicationId, version, share.memberId(), share.shareCents()) != 1)
                throw conflict();
        }
    }

    private View view(long enterprise, String id) {
        var row = data.detail(enterprise, id);
        if (row == null) throw missing();
        return new View(row.id(), row.ruleId(), row.ruleVersion(), row.ruleName(), row.platform(),
                row.externalOrderNo(), row.occurredAt(), row.amountCents(), row.status(), row.currentVersion(),
                row.quotaPeriod(), row.reservedCents(), row.requiresManager() == 1, row.applicantMemberId(),
                row.payerMemberId(), row.payeeMemberId(), row.createdMs(),
                data.participants(enterprise, id, row.currentVersion()));
    }

    /** 分摊必须去重、合计等于申请金额，且申请人本人必须在列表中。 */
    private void shares(List<Share> shares, long amount, long applicant, long enterprise) {
        long sum = 0;
        boolean applicantIncluded = false;
        Set<Long> seen = new HashSet<>();
        for (var share : shares) {
            positive(share.memberId());
            if (!seen.add(share.memberId())) throw invalid();
            if (share.memberId() == applicant) {
                applicantIncluded = true;
            } else {
                if (share.shareCents() <= 0) throw invalid();
                if (data.memberExists(enterprise, share.memberId()) == null)
                    throw new BusinessException(ResultCode.PARAM_ERROR, "参与成员不存在或已停用");
            }
            sum += share.shareCents();
        }
        if (!applicantIncluded || sum != amount)
            throw new BusinessException(ResultCode.PARAM_ERROR, "参与分摊合计必须等于申请金额");
    }

    private void shareList(List<Share> shares) {
        if (shares == null || shares.isEmpty() || shares.size() > 20) throw invalid();
        for (var share : shares) {
            if (share == null || share.shareCents() < 0 || share.shareCents() > MAX_AMOUNT_CENTS) throw invalid();
        }
    }

    private String uuid(String value) {
        if (value == null || !value.matches(UUID)) throw invalid();
        return value.toLowerCase();
    }

    private String platform(String value) {
        if (value == null || !value.matches(PLATFORM)) throw invalid();
        return value;
    }

    private String order(String value) {
        if (value == null || value.isBlank()) return null;
        String clean = value.strip();
        if (clean.length() > 64 || clean.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return clean;
    }

    private String text(String value, int max, String message) {
        if (value == null) throw new BusinessException(ResultCode.PARAM_ERROR, message);
        String clean = value.strip();
        if (clean.isEmpty() || clean.length() > max
                || clean.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029))
            throw new BusinessException(ResultCode.PARAM_ERROR, message);
        return clean;
    }

    private String status(String value) {
        if (value == null || value.isBlank()) return null;
        String clean = value.strip();
        if (!STATUSES.contains(clean)) throw invalid();
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
        return new BusinessException(ResultCode.PARAM_ERROR, "补贴申请参数不正确");
    }

    private BusinessException missing() {
        return new BusinessException(ResultCode.NOT_FOUND, "补贴申请不存在或不可访问");
    }

    private BusinessException conflict() {
        return new BusinessException(ResultCode.CONFLICT, "补贴申请已变化，请刷新后重新确认");
    }
}
