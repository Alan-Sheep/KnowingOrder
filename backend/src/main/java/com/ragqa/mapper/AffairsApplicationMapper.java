package com.ragqa.mapper;

import lombok.Data;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 补贴申请、版本快照与参与确认固定SQL。
 *
 * <p>企业范围始终作为条件；金额占用与释放使用条件更新，禁止先查余额再加，
 * 以避免并发申请造成超额。查询结果中的 LocalDateTime 由 Jackson 统一格式化。</p>
 */
@Mapper
public interface AffairsApplicationMapper {

    /** 申请主记录中服务层需要的当前状态字段。 */
    record Application(String id, long enterpriseId, long applicantMemberId, long ruleId, int ruleVersion,
            String platform, long amountCents, String status, int currentVersion, String quotaPeriod,
            long reservedCents, long revision) { }

    /** 我的申请列表行；ruleName 来自受理时的规则逻辑关联。金额用 Long 才会被序列化为字符串。 */
    record Summary(String id, String ruleName, String platform, LocalDateTime occurredAt, Long amountCents,
            String status, int currentVersion, long createdMs) { }

    /** 申请详情头；requiresManager 用整数承接 tinyint，由服务层转换为布尔。 */
    record Detail(String id, Long ruleId, int ruleVersion, String ruleName, String platform, String externalOrderNo,
            LocalDateTime occurredAt, Long amountCents, String status, int currentVersion, String quotaPeriod,
            Long reservedCents, int requiresManager, Long applicantMemberId, Long payerMemberId, Long payeeMemberId,
            long createdMs) { }

    record Participant(Long id, Long memberId, String username, Long shareCents, String status,
            Long confirmedMs, String note) { }

    record Pending(Long participantId, String applicationId, String ruleName, String platform,
            LocalDateTime occurredAt, Long amountCents, int version, Long shareCents, long createdMs) { }

    @Data
    class NewApplication {
        private String id;
        private long enterpriseId;
        private long applicantMemberId;
        private long ruleId;
        private int ruleVersion;
        private String platform;
        private String externalOrderNo;
        private LocalDateTime occurredAt;
        private long amountCents;
        private long payerMemberId;
        private String status;
        private int currentVersion;
        private String quotaPeriod;
        private long reservedCents;
        private long createdMs;
        private long updatedMs;
    }

    @Data
    class NewVersion {
        private String applicationId;
        private int version;
        private long amountCents;
        private String participantsJson;
        private String snapshotJson;
        private String changeReason;
        private long createdBy;
        private long createdMs;
    }

    @Select("SELECT id,enterprise_id AS enterpriseId,applicant_member_id AS applicantMemberId,rule_id AS ruleId,"
            + "rule_version AS ruleVersion,platform,amount_cents AS amountCents,status,"
            + "current_version AS currentVersion,quota_period AS quotaPeriod,reserved_cents AS reservedCents,revision "
            + "FROM rag_affair_application WHERE id=#{id} FOR UPDATE")
    Application applicationForUpdate(@Param("id") String id);

    @Insert("""
            INSERT INTO rag_affair_application(id,enterprise_id,applicant_member_id,rule_id,rule_version,platform,
                external_order_no,occurred_at,amount_cents,payer_member_id,status,current_version,quota_period,
                reserved_cents,created_ms,updated_ms)
            VALUES(#{id},#{enterpriseId},#{applicantMemberId},#{ruleId},#{ruleVersion},#{platform},
                #{externalOrderNo,jdbcType=VARCHAR},#{occurredAt},#{amountCents},#{payerMemberId},#{status},
                #{currentVersion},#{quotaPeriod},#{reservedCents},#{createdMs},#{updatedMs})
            """)
    int insertApplication(NewApplication application);

    @Insert("""
            INSERT INTO rag_affair_application_version(application_id,version,amount_cents,participants_json,
                snapshot_json,change_reason,created_by,created_ms)
            VALUES(#{applicationId},#{version},#{amountCents},#{participantsJson},#{snapshotJson},
                #{changeReason},#{createdBy},#{createdMs})
            """)
    int insertVersion(NewVersion version);

    @Insert("""
            INSERT INTO rag_affair_participant(application_id,version,member_id,share_cents,status)
            VALUES(#{applicationId},#{version},#{memberId},#{share},'PENDING')
            """)
    int insertParticipant(@Param("applicationId") String applicationId, @Param("version") int version,
            @Param("memberId") long memberId, @Param("share") long share);

    @Select("SELECT COUNT(*) FROM rag_affair_participant "
            + "WHERE application_id=#{id} AND version=#{version} AND status='PENDING'")
    long pendingCount(@Param("id") String id, @Param("version") int version);

    @Select("SELECT COUNT(*) FROM rag_affair_participant "
            + "WHERE application_id=#{id} AND version=#{version} AND status='REJECTED'")
    long rejectedCount(@Param("id") String id, @Param("version") int version);

    @Select("SELECT id,member_id AS memberId,share_cents AS shareCents,status,confirmed_ms AS confirmedMs,note "
            + "FROM rag_affair_participant WHERE application_id=#{id} AND version=#{version} "
            + "AND member_id=#{member} FOR UPDATE")
    Participant participantForUpdate(@Param("id") String id, @Param("version") int version,
            @Param("member") long member);

    @Update("UPDATE rag_affair_participant SET status=#{status},confirmed_ms=#{ms},note=#{note,jdbcType=VARCHAR} "
            + "WHERE id=#{id} AND status='PENDING'")
    int decideParticipant(@Param("id") long id, @Param("status") String status, @Param("ms") long ms,
            @Param("note") String note);

    @Select("SELECT id FROM rag_affair_participant WHERE application_id=#{id} AND member_id=#{member} LIMIT 1")
    Long hasParticipant(@Param("id") String id, @Param("member") long member);

    @Select("SELECT id FROM sys_enterprise_member WHERE enterprise_id=#{enterprise} AND id=#{member} "
            + "AND status=0 AND role IN ('EMPLOYEE','ADMIN','FINANCE') LIMIT 1")
    Long memberExists(@Param("enterprise") long enterprise, @Param("member") long member);

    @Select("INSERT INTO rag_affair_quota(enterprise_id,member_id,period,limit_cents,used_cents,reserved_cents,"
            + "revision,updated_ms) VALUES(#{enterprise},#{member},#{period},#{limit,jdbcType=BIGINT},0,0,1,#{ms}) "
            + "ON DUPLICATE KEY UPDATE id=id")
    int insertQuota(@Param("enterprise") long enterprise, @Param("member") long member,
            @Param("period") String period, @Param("limit") Long limit, @Param("ms") long ms);

    /** 条件预占：0 行表示超出额度或账本行不存在，由调用方转 409。 */
    @Update("UPDATE rag_affair_quota SET reserved_cents=reserved_cents+#{amount},revision=revision+1,updated_ms=#{ms} "
            + "WHERE enterprise_id=#{enterprise} AND member_id=#{member} AND period=#{period} "
            + "AND (limit_cents IS NULL OR used_cents+reserved_cents+#{amount}<=limit_cents)")
    int reserveQuota(@Param("enterprise") long enterprise, @Param("member") long member,
            @Param("period") String period, @Param("amount") long amount, @Param("ms") long ms);

    /** 释放不超出现有预占，避免账本出现负数。 */
    @Update("UPDATE rag_affair_quota SET reserved_cents=reserved_cents-LEAST(reserved_cents,#{release}),"
            + "revision=revision+1,updated_ms=#{ms} "
            + "WHERE enterprise_id=#{enterprise} AND member_id=#{member} AND period=#{period}")
    int releaseQuota(@Param("enterprise") long enterprise, @Param("member") long member,
            @Param("period") String period, @Param("release") long release, @Param("ms") long ms);

    @Select("SELECT COUNT(*) FROM rag_affair_application a WHERE a.enterprise_id=#{enterprise} "
            + "AND a.applicant_member_id=#{member} "
            + "AND (#{status,jdbcType=VARCHAR} IS NULL OR a.status=#{status,jdbcType=VARCHAR})")
    long myCount(@Param("enterprise") long enterprise, @Param("member") long member,
            @Param("status") String status);

    @Select("SELECT a.id,r.name AS ruleName,a.platform,a.occurred_at AS occurredAt,a.amount_cents AS amountCents,"
            + "a.status,a.current_version AS currentVersion,a.created_ms AS createdMs "
            + "FROM rag_affair_application a JOIN rag_affair_rule r ON r.id=a.rule_id "
            + "WHERE a.enterprise_id=#{enterprise} AND a.applicant_member_id=#{member} "
            + "AND (#{status,jdbcType=VARCHAR} IS NULL OR a.status=#{status,jdbcType=VARCHAR}) "
            + "ORDER BY a.created_ms DESC,a.id LIMIT #{size} OFFSET #{offset}")
    List<Summary> myApplications(@Param("enterprise") long enterprise, @Param("member") long member,
            @Param("status") String status, @Param("size") long size, @Param("offset") long offset);

    @Select("SELECT a.id,a.rule_id AS ruleId,a.rule_version AS ruleVersion,r.name AS ruleName,a.platform,"
            + "a.external_order_no AS externalOrderNo,a.occurred_at AS occurredAt,a.amount_cents AS amountCents,"
            + "a.status,a.current_version AS currentVersion,a.quota_period AS quotaPeriod,"
            + "a.reserved_cents AS reservedCents,r.requires_manager AS requiresManager,"
            + "a.applicant_member_id AS applicantMemberId,a.payer_member_id AS payerMemberId,"
            + "a.payee_member_id AS payeeMemberId,a.created_ms AS createdMs "
            + "FROM rag_affair_application a JOIN rag_affair_rule r ON r.id=a.rule_id "
            + "WHERE a.enterprise_id=#{enterprise} AND a.id=#{id}")
    Detail detail(@Param("enterprise") long enterprise, @Param("id") String id);

    @Select("SELECT p.id,p.member_id AS memberId,u.username,p.share_cents AS shareCents,p.status,"
            + "p.confirmed_ms AS confirmedMs,p.note "
            + "FROM rag_affair_participant p "
            + "JOIN sys_enterprise_member m ON m.id=p.member_id AND m.enterprise_id=#{enterprise} "
            + "JOIN sys_user u ON u.id=m.user_id "
            + "WHERE p.application_id=#{id} AND p.version=#{version} ORDER BY p.id")
    List<Participant> participants(@Param("enterprise") long enterprise, @Param("id") String id,
            @Param("version") int version);

    @Update("UPDATE rag_affair_application SET status=#{status},reserved_cents=#{reserved},"
            + "revision=revision+1,updated_ms=#{ms} "
            + "WHERE enterprise_id=#{enterprise} AND id=#{id} AND revision=#{revision}")
    int updateStatus(@Param("enterprise") long enterprise, @Param("id") String id, @Param("status") String status,
            @Param("reserved") long reserved, @Param("revision") long revision, @Param("ms") long ms);

    /** 修改即新版本：金额、版本指针与占用额度一次条件更新，旧确认因版本不同自然失效。 */
    @Update("UPDATE rag_affair_application SET amount_cents=#{amount},current_version=#{version},status=#{status},"
            + "reserved_cents=GREATEST(0,reserved_cents+#{delta}),revision=revision+1,updated_ms=#{ms} "
            + "WHERE enterprise_id=#{enterprise} AND id=#{id} AND revision=#{revision} "
            + "AND current_version=#{expectedVersion}")
    int revise(@Param("enterprise") long enterprise, @Param("id") String id, @Param("amount") long amount,
            @Param("version") int version, @Param("status") String status, @Param("delta") long delta,
            @Param("revision") long revision, @Param("expectedVersion") int expectedVersion, @Param("ms") long ms);

    @Select("SELECT COUNT(*) FROM rag_affair_participant p "
            + "JOIN rag_affair_application a ON a.id=p.application_id AND a.enterprise_id=#{enterprise} "
            + "WHERE p.member_id=#{member} AND p.version=a.current_version AND p.status='PENDING' "
            + "AND a.status IN ('SUBMITTED','CONFIRMING')")
    long pendingForMeCount(@Param("enterprise") long enterprise, @Param("member") long member);

    @Select("SELECT p.id AS participantId,a.id AS applicationId,r.name AS ruleName,a.platform,"
            + "a.occurred_at AS occurredAt,a.amount_cents AS amountCents,a.current_version AS version,"
            + "p.share_cents AS shareCents,a.created_ms AS createdMs "
            + "FROM rag_affair_participant p "
            + "JOIN rag_affair_application a ON a.id=p.application_id AND a.enterprise_id=#{enterprise} "
            + "JOIN rag_affair_rule r ON r.id=a.rule_id "
            + "WHERE p.member_id=#{member} AND p.version=a.current_version AND p.status='PENDING' "
            + "AND a.status IN ('SUBMITTED','CONFIRMING') "
            + "ORDER BY a.created_ms DESC,a.id LIMIT #{size} OFFSET #{offset}")
    List<Pending> pendingForMe(@Param("enterprise") long enterprise, @Param("member") long member,
            @Param("size") long size, @Param("offset") long offset);
}
