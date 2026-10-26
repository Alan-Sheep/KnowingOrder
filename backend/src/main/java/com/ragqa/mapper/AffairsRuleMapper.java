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
 * 员工事务规则与额度账本固定SQL。
 *
 * <p>企业范围始终作为查询条件，不接受仅按主键跨企业读取；写操作由服务层先锁定并复核管理员。</p>
 */
@Mapper
public interface AffairsRuleMapper {

    /**
     * 规则只增不改：激活后旧版本置为 RETIRED，历史申请保留受理时的版本号。
     *
     * <p>金额一律用包装类型 Long：JacksonConfig 只对 Long 注册 ToStringSerializer，
     * 原始类型 long 会被序列化成 JSON 数字，前端按十进制字符串校验金额时会直接判为非法。</p>
     */
    record Rule(Long id, String name, int version, String status, String period, Long memberLimitCents,
            Long applicationLimitCents, int requiresManager, LocalDateTime effectiveFrom, LocalDateTime effectiveTo) { }

    /** 额度账本行：limitCents 为 NULL 表示该周期不限额度。 */
    record Quota(Long id, Long memberId, String period, Long limitCents, long usedCents, long reservedCents,
            long revision) { }

    /** 参与人下拉所需的最小目录：只含编号与显示名，不含联系方式或私有资源。编号用 Long 才会序列化成字符串。 */
    record MemberOption(Long memberId, String username) { }

    @Data
    class NewRule {
        private Long id;
        private long enterpriseId;
        private String name;
        private int version;
        private String period;
        private Long memberLimitCents;
        private long applicationLimitCents;
        private int requiresManager;
        private LocalDateTime effectiveFrom;
        private LocalDateTime effectiveTo;
        private long createdBy;
        private long createdMs;
        private long updatedMs;
    }

    String RULE_COLUMNS = "SELECT id,name,version,status,period,member_limit_cents AS memberLimitCents,"
            + "application_limit_cents AS applicationLimitCents,requires_manager AS requiresManager,"
            + "effective_from AS effectiveFrom,effective_to AS effectiveTo FROM rag_affair_rule ";

    @Select("SELECT COUNT(*) FROM rag_affair_rule WHERE enterprise_id=#{enterprise} "
            + "AND (#{status,jdbcType=VARCHAR} IS NULL OR status=#{status,jdbcType=VARCHAR})")
    long ruleCount(@Param("enterprise") long enterprise, @Param("status") String status);

    @Select(RULE_COLUMNS + "WHERE enterprise_id=#{enterprise} "
            + "AND (#{status,jdbcType=VARCHAR} IS NULL OR status=#{status,jdbcType=VARCHAR}) "
            + "ORDER BY id DESC LIMIT #{size} OFFSET #{offset}")
    List<Rule> rules(@Param("enterprise") long enterprise, @Param("status") String status,
            @Param("size") long size, @Param("offset") long offset);

    @Select(RULE_COLUMNS + "WHERE enterprise_id=#{enterprise} AND id=#{id}")
    Rule rule(@Param("enterprise") long enterprise, @Param("id") long id);

    /** 激活前锁定该版本，避免并发激活同一名称产生两条 ACTIVE。 */
    @Select(RULE_COLUMNS + "WHERE enterprise_id=#{enterprise} AND id=#{id} FOR UPDATE")
    Rule ruleForUpdate(@Param("enterprise") long enterprise, @Param("id") long id);

    @Select("SELECT COALESCE(MAX(version),0) FROM rag_affair_rule WHERE enterprise_id=#{enterprise} AND name=#{name}")
    int maxVersion(@Param("enterprise") long enterprise, @Param("name") String name);

    @Insert("""
            INSERT INTO rag_affair_rule(enterprise_id,name,version,status,period,member_limit_cents,
                application_limit_cents,requires_manager,effective_from,effective_to,created_by,created_ms,updated_ms)
            VALUES(#{enterpriseId},#{name},#{version},'DRAFT',#{period},#{memberLimitCents},
                #{applicationLimitCents},#{requiresManager},#{effectiveFrom},#{effectiveTo},#{createdBy},#{createdMs},#{updatedMs})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertRule(NewRule rule);

    /** 同企业同名只保留一个 ACTIVE 版本；旧版本停用不追溯已受理申请。 */
    @Update("UPDATE rag_affair_rule SET status='RETIRED',updated_ms=#{ms} "
            + "WHERE enterprise_id=#{enterprise} AND name=#{name} AND status='ACTIVE' AND id != #{id}")
    int retireActive(@Param("enterprise") long enterprise, @Param("name") String name,
            @Param("id") long id, @Param("ms") long ms);

    @Update("UPDATE rag_affair_rule SET status='ACTIVE',updated_ms=#{ms} "
            + "WHERE enterprise_id=#{enterprise} AND id=#{id} AND status != 'ACTIVE'")
    int activate(@Param("enterprise") long enterprise, @Param("id") long id, @Param("ms") long ms);

    /** 只读额度快照；预占与结算使用条件更新，见申请与结算服务。 */
    @Select("SELECT id,member_id AS memberId,period,limit_cents AS limitCents,used_cents AS usedCents,"
            + "reserved_cents AS reservedCents,revision FROM rag_affair_quota "
            + "WHERE enterprise_id=#{enterprise} AND member_id=#{member} AND period=#{period}")
    Quota quota(@Param("enterprise") long enterprise, @Param("member") long member, @Param("period") String period);

    /** 企业内有效成员目录，仅用于参与人下拉；通讯录可见不代表可读其私有资料。 */
    @Select("SELECT m.id AS memberId,u.username FROM sys_enterprise_member m "
            + "JOIN sys_user u ON u.id=m.user_id AND u.status=0 AND u.deleted=0 "
            + "WHERE m.enterprise_id=#{enterprise} AND m.status=0 "
            + "AND m.role IN ('EMPLOYEE','ADMIN','FINANCE') ORDER BY u.username LIMIT 500")
    List<MemberOption> memberOptions(@Param("enterprise") long enterprise);
}
