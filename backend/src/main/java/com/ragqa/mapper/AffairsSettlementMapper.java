package com.ragqa.mapper;

import lombok.Data;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 主管批准、财务结算与退款调整固定SQL。
 *
 * <p>结算记录只表示系统内核算状态，不等于工资已发放；已结算后发生退款只追加调整记录，
 * 不修改原结算金额。额度从预占转为已用、以及退款回退都用条件更新完成。</p>
 */
@Mapper
public interface AffairsSettlementMapper {

    /** 结算主记录中服务层需要的最小字段。 */
    record Settlement(long id, long enterpriseId, String applicationId, int applicationVersion, long amountCents,
            long payeeMemberId, String status, long revision) { }

    record SettlementView(Long id, String applicationId, String ruleName, Long amountCents, Long payeeMemberId,
            String payeeName, String status, String note, long createdMs, Long approvedMs, Long settledMs) { }

    /** 财务清单：已进入待结算的申请，附带是否已开出结算单。 */
    record FinanceApplication(String id, String ruleName, String applicantName, Long amountCents, String status,
            LocalDateTime occurredAt, long createdMs, Long settlementId, String settlementStatus) { }

    record Queue(Long id, String applicationId, String ruleName, String applicantName, Long amountCents,
            String payeeName, String status, long createdMs) { }

    @Data
    class NewSettlement {
        private Long id;
        private long enterpriseId;
        private String applicationId;
        private int applicationVersion;
        private long ruleId;
        private long amountCents;
        private long payeeMemberId;
        private String status;
        private String note;
        private long createdMs;
        private long updatedMs;
    }

    @Data
    class NewAdjustment {
        private long enterpriseId;
        private String applicationId;
        private Long settlementId;
        private String kind;
        private long amountCents;
        private String reason;
        private long operatorMemberId;
        private long createdMs;
    }

    /** 主管批准：只允许从 VERIFIED 进入待结算，并记录批准人与时间。 */
    @Update("UPDATE rag_affair_application SET status='PENDING_SETTLEMENT',manager_approved_by=#{member},"
            + "manager_approved_ms=#{ms},revision=revision+1,updated_ms=#{ms} "
            + "WHERE enterprise_id=#{enterprise} AND id=#{id} AND status='VERIFIED' AND revision=#{revision}")
    int approveApplication(@Param("enterprise") long enterprise, @Param("id") String id,
            @Param("member") long member, @Param("revision") long revision, @Param("ms") long ms);

    /** 先无锁读取定位申请，再按「申请→结算」固定顺序加锁，避免与退款流程交错死锁。 */
    @Select("SELECT id,enterprise_id AS enterpriseId,application_id AS applicationId,"
            + "application_version AS applicationVersion,amount_cents AS amountCents,"
            + "payee_member_id AS payeeMemberId,status,revision FROM rag_affair_settlement "
            + "WHERE enterprise_id=#{enterprise} AND id=#{id}")
    Settlement settlement(@Param("enterprise") long enterprise, @Param("id") long id);

    @Select("SELECT id,enterprise_id AS enterpriseId,application_id AS applicationId,"
            + "application_version AS applicationVersion,amount_cents AS amountCents,"
            + "payee_member_id AS payeeMemberId,status,revision FROM rag_affair_settlement "
            + "WHERE enterprise_id=#{enterprise} AND id=#{id} FOR UPDATE")
    Settlement settlementForUpdate(@Param("enterprise") long enterprise, @Param("id") long id);

    @Select("SELECT id,enterprise_id AS enterpriseId,application_id AS applicationId,"
            + "application_version AS applicationVersion,amount_cents AS amountCents,"
            + "payee_member_id AS payeeMemberId,status,revision FROM rag_affair_settlement "
            + "WHERE enterprise_id=#{enterprise} AND application_id=#{application} FOR UPDATE")
    Settlement settlementOfApplicationForUpdate(@Param("enterprise") long enterprise,
            @Param("application") String application);

    @Insert("""
            INSERT INTO rag_affair_settlement(enterprise_id,application_id,application_version,rule_id,
                amount_cents,payee_member_id,status,note,created_ms,updated_ms)
            VALUES(#{enterpriseId},#{applicationId},#{applicationVersion},#{ruleId},#{amountCents},
                #{payeeMemberId},#{status},#{note,jdbcType=VARCHAR},#{createdMs},#{updatedMs})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertSettlement(NewSettlement settlement);

    @Update("UPDATE rag_affair_settlement SET status='APPROVED',finance_member_id=#{member},approved_ms=#{ms},"
            + "revision=revision+1,updated_ms=#{ms} "
            + "WHERE enterprise_id=#{enterprise} AND id=#{id} AND status='PENDING' AND revision=#{revision}")
    int approveSettlement(@Param("enterprise") long enterprise, @Param("id") long id,
            @Param("member") long member, @Param("revision") long revision, @Param("ms") long ms);

    @Update("UPDATE rag_affair_settlement SET status='SETTLED',finance_member_id=#{member},settled_ms=#{ms},"
            + "revision=revision+1,updated_ms=#{ms} "
            + "WHERE enterprise_id=#{enterprise} AND id=#{id} AND status='APPROVED' AND revision=#{revision}")
    int settleSettlement(@Param("enterprise") long enterprise, @Param("id") long id,
            @Param("member") long member, @Param("revision") long revision, @Param("ms") long ms);

    /** 作废只改变状态留痕，不删除已发生的结算事实。 */
    @Update("UPDATE rag_affair_settlement SET status='VOID',note=#{note,jdbcType=VARCHAR},"
            + "finance_member_id=#{member},revision=revision+1,updated_ms=#{ms} "
            + "WHERE enterprise_id=#{enterprise} AND id=#{id} AND revision=#{revision} "
            + "AND status IN ('PENDING','APPROVED','SETTLED')")
    int voidSettlement(@Param("enterprise") long enterprise, @Param("id") long id, @Param("note") String note,
            @Param("member") long member, @Param("revision") long revision, @Param("ms") long ms);

    @Insert("""
            INSERT INTO rag_affair_adjustment(enterprise_id,application_id,settlement_id,kind,amount_cents,
                reason,operator_member_id,created_ms)
            VALUES(#{enterpriseId},#{applicationId},#{settlementId,jdbcType=BIGINT},#{kind},#{amountCents},
                #{reason},#{operatorMemberId},#{createdMs})
            """)
    int insertAdjustment(NewAdjustment adjustment);

    /** 结算时预占转为已用：预占最多扣到 0，已用按结算金额增加。 */
    @Update("UPDATE rag_affair_quota SET reserved_cents=reserved_cents-LEAST(reserved_cents,#{amount}),"
            + "used_cents=used_cents+#{amount},revision=revision+1,updated_ms=#{ms} "
            + "WHERE enterprise_id=#{enterprise} AND member_id=#{member} AND period=#{period}")
    int settleQuota(@Param("enterprise") long enterprise, @Param("member") long member,
            @Param("period") String period, @Param("amount") long amount, @Param("ms") long ms);

    /** 退款只回退已用额度，且不超过当前已用值。 */
    @Update("UPDATE rag_affair_quota SET used_cents=used_cents-LEAST(used_cents,#{amount}),"
            + "revision=revision+1,updated_ms=#{ms} "
            + "WHERE enterprise_id=#{enterprise} AND member_id=#{member} AND period=#{period}")
    int refundQuota(@Param("enterprise") long enterprise, @Param("member") long member,
            @Param("period") String period, @Param("amount") long amount, @Param("ms") long ms);

    @Select("SELECT s.id,s.application_id AS applicationId,r.name AS ruleName,s.amount_cents AS amountCents,"
            + "s.payee_member_id AS payeeMemberId,pu.username AS payeeName,s.status,s.note,"
            + "s.created_ms AS createdMs,s.approved_ms AS approvedMs,s.settled_ms AS settledMs "
            + "FROM rag_affair_settlement s "
            + "JOIN rag_affair_rule r ON r.id=s.rule_id "
            + "JOIN sys_enterprise_member pm ON pm.id=s.payee_member_id AND pm.enterprise_id=s.enterprise_id "
            + "JOIN sys_user pu ON pu.id=pm.user_id "
            + "WHERE s.enterprise_id=#{enterprise} AND s.id=#{id}")
    SettlementView settlementView(@Param("enterprise") long enterprise, @Param("id") long id);

    @Select("SELECT COUNT(*) FROM rag_affair_settlement s WHERE s.enterprise_id=#{enterprise} "
            + "AND (#{status,jdbcType=VARCHAR} IS NULL OR s.status=#{status,jdbcType=VARCHAR})")
    long queueCount(@Param("enterprise") long enterprise, @Param("status") String status);

    @Select("SELECT s.id,s.application_id AS applicationId,r.name AS ruleName,au.username AS applicantName,"
            + "s.amount_cents AS amountCents,pu.username AS payeeName,s.status,s.created_ms AS createdMs "
            + "FROM rag_affair_settlement s "
            + "JOIN rag_affair_application a ON a.id=s.application_id AND a.enterprise_id=#{enterprise} "
            + "JOIN rag_affair_rule r ON r.id=s.rule_id "
            + "JOIN sys_enterprise_member am ON am.id=a.applicant_member_id "
            + "JOIN sys_user au ON au.id=am.user_id "
            + "JOIN sys_enterprise_member pm ON pm.id=s.payee_member_id "
            + "JOIN sys_user pu ON pu.id=pm.user_id "
            + "WHERE s.enterprise_id=#{enterprise} "
            + "AND (#{status,jdbcType=VARCHAR} IS NULL OR s.status=#{status,jdbcType=VARCHAR}) "
            + "ORDER BY s.created_ms DESC,s.id LIMIT #{size} OFFSET #{offset}")
    List<Queue> queue(@Param("enterprise") long enterprise, @Param("status") String status,
            @Param("size") long size, @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM rag_affair_application a WHERE a.enterprise_id=#{enterprise} "
            + "AND (#{status,jdbcType=VARCHAR} IS NULL OR a.status=#{status,jdbcType=VARCHAR})")
    long financeCount(@Param("enterprise") long enterprise, @Param("status") String status);

    @Select("SELECT a.id,r.name AS ruleName,au.username AS applicantName,a.amount_cents AS amountCents,"
            + "a.status,a.occurred_at AS occurredAt,a.created_ms AS createdMs,s.id AS settlementId,"
            + "s.status AS settlementStatus "
            + "FROM rag_affair_application a "
            + "JOIN rag_affair_rule r ON r.id=a.rule_id "
            + "JOIN sys_enterprise_member am ON am.id=a.applicant_member_id "
            + "JOIN sys_user au ON au.id=am.user_id "
            + "LEFT JOIN rag_affair_settlement s ON s.application_id=a.id AND s.enterprise_id=a.enterprise_id "
            + "WHERE a.enterprise_id=#{enterprise} "
            + "AND (#{status,jdbcType=VARCHAR} IS NULL OR a.status=#{status,jdbcType=VARCHAR}) "
            + "ORDER BY a.created_ms DESC,a.id LIMIT #{size} OFFSET #{offset}")
    List<FinanceApplication> financeApplications(@Param("enterprise") long enterprise,
            @Param("status") String status, @Param("size") long size, @Param("offset") long offset);
}
