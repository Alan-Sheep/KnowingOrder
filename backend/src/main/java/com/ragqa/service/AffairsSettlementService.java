package com.ragqa.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ragqa.common.PageResult;
import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.AffairsApplicationMapper;
import com.ragqa.mapper.AffairsSettlementMapper;
import com.ragqa.mapper.EnterpriseDirectoryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Set;

/**
 * 主管批准、财务结算与退款调整。
 *
 * <p>结算只表示系统内核算状态，不等于工资已发放。所有写操作按「先申请、后结算」的固定顺序加锁，
 * 已结算后的退款只追加调整记录并回退已用额度，不修改原结算金额。</p>
 */
@Service
public class AffairsSettlementService {

    public record RefundResult(String applicationId, String applicationStatus, String kind, Long amountCents,
            Long settlementId, String settlementStatus) { }

    private static final Set<String> APPLICATION_STATUSES = Set.of("SUBMITTED", "CONFIRMING", "VERIFIED",
            "PENDING_SETTLEMENT", "SETTLED", "REJECTED", "CANCELLED");
    private static final Set<String> SETTLEMENT_STATUSES = Set.of("PENDING", "APPROVED", "SETTLED", "RETURNED", "VOID");
    private static final long MAX_AMOUNT_CENTS = 1_000_000_000L;
    private static final String UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    private final EnterpriseMemberService members;
    private final EnterpriseDirectoryMapper directory;
    private final AffairsApplicationMapper applications;
    private final AffairsSettlementMapper data;
    private final TransactionTemplate tx;

    public AffairsSettlementService(EnterpriseMemberService members, EnterpriseDirectoryMapper directory,
            AffairsApplicationMapper applications, AffairsSettlementMapper data, PlatformTransactionManager manager) {
        this.members = members;
        this.directory = directory;
        this.applications = applications;
        this.data = data;
        tx = new TransactionTemplate(manager);
        tx.setTimeout(5);
    }

    /** 主管批准：VERIFIED → PENDING_SETTLEMENT，后续由财务明确收款人后开结算单。 */
    public AffairsApplicationMapper.Detail approve(AuthenticatedUserPrincipal user, long enterprise, String id) {
        String key = uuid(id);
        return tx.execute(s -> {
            var scope = admin(user, enterprise);
            var app = applications.applicationForUpdate(key);
            if (app == null || app.enterpriseId() != enterprise) throw missing();
            if (!"VERIFIED".equals(app.status()))
                throw new BusinessException(ResultCode.CONFLICT, "当前状态不能由主管批准");
            long now = System.currentTimeMillis();
            if (data.approveApplication(enterprise, key, scope.memberId(), app.revision(), now) != 1)
                throw conflict();
            var detail = applications.detail(enterprise, key);
            if (detail == null) throw missing();
            return detail;
        });
    }

    /** 财务清单：默认看全部状态，可按申请状态筛选；已开结算单的附带单号与状态。 */
    public PageResult<AffairsSettlementMapper.FinanceApplication> financeApplications(AuthenticatedUserPrincipal user,
            long enterprise, String status, long pageNum, long pageSize) {
        long offset = offset(pageNum, pageSize);
        var scope = finance(user, enterprise);
        String filter = applicationStatus(status);
        var result = new Page<AffairsSettlementMapper.FinanceApplication>(pageNum, pageSize,
                data.financeCount(enterprise, filter));
        result.setRecords(data.financeApplications(enterprise, filter, pageSize, offset));
        return PageResult.of(result);
    }

    public PageResult<AffairsSettlementMapper.Queue> settlements(AuthenticatedUserPrincipal user, long enterprise,
            String status, long pageNum, long pageSize) {
        long offset = offset(pageNum, pageSize);
        var scope = finance(user, enterprise);
        String filter = settlementStatus(status);
        var result = new Page<AffairsSettlementMapper.Queue>(pageNum, pageSize, data.queueCount(enterprise, filter));
        result.setRecords(data.queue(enterprise, filter, pageSize, offset));
        return PageResult.of(result);
    }

    /** 开结算单必须显式指定收款人；金额与版本取受理时事实，不允许财务改写。 */
    public AffairsSettlementMapper.SettlementView create(AuthenticatedUserPrincipal user, long enterprise,
            String applicationId, long payeeMemberId, String note) {
        String key = uuid(applicationId);
        if (payeeMemberId <= 0 || payeeMemberId == Long.MAX_VALUE) throw invalid();
        String clean = note == null || note.isBlank() ? null : text(note, 500, "核算说明过长");
        return tx.execute(s -> {
            var scope = finance(user, enterprise);
            var app = applications.applicationForUpdate(key);
            if (app == null || app.enterpriseId() != enterprise) throw missing();
            if (!"PENDING_SETTLEMENT".equals(app.status()))
                throw new BusinessException(ResultCode.CONFLICT, "该申请尚未进入待结算");
            if (applications.memberExists(enterprise, payeeMemberId) == null)
                throw new BusinessException(ResultCode.PARAM_ERROR, "收款成员不存在或已停用");
            if (data.settlementOfApplicationForUpdate(enterprise, key) != null)
                throw new BusinessException(ResultCode.CONFLICT, "该申请已开结算单");
            long now = System.currentTimeMillis();
            var row = new AffairsSettlementMapper.NewSettlement();
            row.setEnterpriseId(enterprise);
            row.setApplicationId(key);
            row.setApplicationVersion(app.currentVersion());
            row.setRuleId(app.ruleId());
            row.setAmountCents(app.amountCents());
            row.setPayeeMemberId(payeeMemberId);
            row.setStatus("PENDING");
            row.setNote(clean);
            row.setCreatedMs(now);
            row.setUpdatedMs(now);
            if (data.insertSettlement(row) != 1) throw conflict();
            return data.settlementView(enterprise, row.getId());
        });
    }

    public AffairsSettlementMapper.SettlementView approveSettlement(AuthenticatedUserPrincipal user, long enterprise,
            long settlementId) {
        positive(settlementId);
        return tx.execute(s -> {
            var scope = finance(user, enterprise);
            var settlement = data.settlementForUpdate(enterprise, settlementId);
            if (settlement == null) throw missing();
            if (!"PENDING".equals(settlement.status()))
                throw new BusinessException(ResultCode.CONFLICT, "当前结算单不能批准");
            long now = System.currentTimeMillis();
            if (data.approveSettlement(enterprise, settlementId, scope.memberId(), settlement.revision(), now) != 1)
                throw conflict();
            return data.settlementView(enterprise, settlementId);
        });
    }

    /** 标记结算：预占额度转为已用，申请状态从待结算变为已结算。 */
    public AffairsSettlementMapper.SettlementView settleSettlement(AuthenticatedUserPrincipal user, long enterprise,
            long settlementId) {
        positive(settlementId);
        return tx.execute(s -> {
            var scope = finance(user, enterprise);
            var head = data.settlement(enterprise, settlementId);
            if (head == null) throw missing();
            var app = applications.applicationForUpdate(head.applicationId());
            if (app == null || app.enterpriseId() != enterprise) throw missing();
            var settlement = data.settlementForUpdate(enterprise, settlementId);
            if (settlement == null) throw missing();
            if (!"APPROVED".equals(settlement.status()))
                throw new BusinessException(ResultCode.CONFLICT, "结算单须先批准再标记结算");
            if (!"PENDING_SETTLEMENT".equals(app.status()))
                throw new BusinessException(ResultCode.CONFLICT, "申请状态已变化，请刷新后核对");
            if (app.reservedCents() != settlement.amountCents())
                throw new BusinessException(ResultCode.CONFLICT, "申请占用额度与结算金额不一致，请核对");
            long now = System.currentTimeMillis();
            if (data.settleSettlement(enterprise, settlementId, scope.memberId(), settlement.revision(), now) != 1)
                throw conflict();
            if (applications.updateStatus(enterprise, app.id(), "SETTLED", 0, app.revision(), now) != 1)
                throw conflict();
            data.settleQuota(enterprise, app.applicantMemberId(), app.quotaPeriod(), settlement.amountCents(), now);
            return data.settlementView(enterprise, settlementId);
        });
    }

    /**
     * 退款：未结算时整单取消并释放预占（部分退款需按净额重新提交）；
     * 已结算时只追加调整记录，全额退款作废结算单，均按退款金额回退已用额度。
     */
    public RefundResult refund(AuthenticatedUserPrincipal user, long enterprise, String applicationId,
            boolean full, long amountCents, String reason) {
        String key = uuid(applicationId);
        if (amountCents <= 0 || amountCents > MAX_AMOUNT_CENTS) throw invalid();
        String clean = text(reason, 200, "请填写退款原因");
        String kind = full ? "REFUND_FULL" : "REFUND_PARTIAL";
        return tx.execute(s -> {
            var scope = finance(user, enterprise);
            var app = applications.applicationForUpdate(key);
            if (app == null || app.enterpriseId() != enterprise) throw missing();
            var settlement = data.settlementOfApplicationForUpdate(enterprise, key);
            boolean settled = "SETTLED".equals(app.status());
            if (settled) {
                if (settlement == null || !"SETTLED".equals(settlement.status()))
                    throw new BusinessException(ResultCode.CONFLICT, "已结算申请缺少有效结算单，请核对");
                check(full, amountCents, settlement.amountCents());
            } else {
                if (Set.of("CANCELLED", "REJECTED").contains(app.status()))
                    throw new BusinessException(ResultCode.CONFLICT, "当前状态不能登记退款");
                check(full, amountCents, app.amountCents());
                if (settlement != null && "SETTLED".equals(settlement.status()))
                    throw new BusinessException(ResultCode.CONFLICT, "结算状态与申请状态不一致，请核对");
            }
            long now = System.currentTimeMillis();
            var adjustment = new AffairsSettlementMapper.NewAdjustment();
            adjustment.setEnterpriseId(enterprise);
            adjustment.setApplicationId(key);
            adjustment.setSettlementId(settlement == null ? null : settlement.id());
            adjustment.setKind(kind);
            adjustment.setAmountCents(amountCents);
            adjustment.setReason(clean);
            adjustment.setOperatorMemberId(scope.memberId());
            adjustment.setCreatedMs(now);
            if (data.insertAdjustment(adjustment) != 1) throw conflict();
            if (settled) {
                data.refundQuota(enterprise, app.applicantMemberId(), app.quotaPeriod(), amountCents, now);
                if (!full) return new RefundResult(key, app.status(), kind, amountCents, settlement.id(), "SETTLED");
                if (data.voidSettlement(enterprise, settlement.id(), clean, scope.memberId(),
                        settlement.revision(), now) != 1) throw conflict();
                if (applications.updateStatus(enterprise, key, "CANCELLED", 0, app.revision(), now) != 1)
                    throw conflict();
                return new RefundResult(key, "CANCELLED", kind, amountCents, settlement.id(), "VOID");
            }
            applications.releaseQuota(enterprise, app.applicantMemberId(), app.quotaPeriod(), app.reservedCents(), now);
            String settlementStatus = null;
            if (settlement != null && !"VOID".equals(settlement.status())) {
                if (data.voidSettlement(enterprise, settlement.id(), clean, scope.memberId(),
                        settlement.revision(), now) != 1) throw conflict();
                settlementStatus = "VOID";
            }
            if (applications.updateStatus(enterprise, key, "CANCELLED", 0, app.revision(), now) != 1)
                throw conflict();
            return new RefundResult(key, "CANCELLED", kind, amountCents,
                    settlement == null ? null : settlement.id(), settlementStatus);
        });
    }

    /** 全额退款必须等于原金额，部分退款必须小于原金额。 */
    private void check(boolean full, long amount, long total) {
        if (full && amount != total)
            throw new BusinessException(ResultCode.PARAM_ERROR, "全额退款金额必须等于原金额");
        if (!full && amount >= total)
            throw new BusinessException(ResultCode.PARAM_ERROR, "部分退款金额必须小于原金额");
    }

    private EnterpriseMemberContext admin(AuthenticatedUserPrincipal user, long enterprise) {
        var scope = members.requireMember(user, enterprise);
        if (!"ADMIN".equals(scope.role())
                || !scope.equals(directory.findActiveMemberForUpdate(scope.userId(), enterprise, scope.userAuthVersion())))
            throw missing();
        return scope;
    }

    private EnterpriseMemberContext finance(AuthenticatedUserPrincipal user, long enterprise) {
        var scope = members.requireMember(user, enterprise);
        if (!("FINANCE".equals(scope.role()) || "ADMIN".equals(scope.role())))
            throw missing();
        return scope;
    }

    private String applicationStatus(String value) {
        if (value == null || value.isBlank()) return null;
        String clean = value.strip();
        if (!APPLICATION_STATUSES.contains(clean)) throw invalid();
        return clean;
    }

    private String settlementStatus(String value) {
        if (value == null || value.isBlank()) return null;
        String clean = value.strip();
        if (!SETTLEMENT_STATUSES.contains(clean)) throw invalid();
        return clean;
    }

    private String uuid(String value) {
        if (value == null || !value.matches(UUID)) throw invalid();
        return value.toLowerCase();
    }

    private String text(String value, int max, String message) {
        if (value == null) throw new BusinessException(ResultCode.PARAM_ERROR, message);
        String clean = value.strip();
        if (clean.isEmpty() || clean.length() > max
                || clean.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0x2028 || c == 0x2029))
            throw new BusinessException(ResultCode.PARAM_ERROR, message);
        return clean;
    }

    private void positive(long value) {
        if (value <= 0 || value == Long.MAX_VALUE) throw invalid();
    }

    private long offset(long page, long size) {
        if (page < 1 || page > 100000 || size < 1 || size > 100) throw invalid();
        return (page - 1) * size;
    }

    private BusinessException invalid() {
        return new BusinessException(ResultCode.PARAM_ERROR, "结算参数不正确");
    }

    private BusinessException missing() {
        return new BusinessException(ResultCode.NOT_FOUND, "补贴申请或结算单不存在或不可访问");
    }

    private BusinessException conflict() {
        return new BusinessException(ResultCode.CONFLICT, "记录已变化，请刷新后重新确认");
    }
}
