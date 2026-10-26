package com.ragqa.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 访客临时身份，对应 rag_guest_identity；全部时间以 UTC 毫秒保存。
 * 表中没有公共审计或软删除字段，因此不继承 BaseEntity。
 * 本类只负责持久化映射，认证、跨日权限和额度判断由后续服务负责。
 */
@Getter
@Setter
@ToString(onlyExplicitlyIncluded = true)
@TableName("rag_guest_identity")
public class RagGuestIdentity {

    /** 内部主键，不是访问凭据。 */
    @TableId(value = "id", type = IdType.AUTO)
    @ToString.Include
    private Long id;

    /** 凭据的 SHA-256 摘要；默认查询不取出，不进入 JSON 或日志字符串。 */
    @JsonIgnore
    @TableField(value = "credential_hash", select = false)
    private byte[] credentialHash;

    /** 服务端签发时刻。 */
    @TableField("issued_at_epoch_ms")
    private Long issuedAtEpochMs;

    /** 新问题受理截止；北京时间次日零点，等于该时刻即停止接单。 */
    @TableField("accept_until_epoch_ms")
    private Long acceptUntilEpochMs;

    /** 已受理任务的执行硬上限，不延长各任务原有截止时间。 */
    @TableField("drain_until_epoch_ms")
    private Long drainUntilEpochMs;

    /** 身份最终失效硬上限，不代表在此前可读取全部历史。 */
    @TableField("expires_at_epoch_ms")
    private Long expiresAtEpochMs;

    /** 生命周期：0 正常、1 撤销、2 收尾、3 到期；具体迁移由服务校验。 */
    @TableField("status")
    private Integer status;

    /** 签发时固定的结果阅读窗口；旧记录可为 0，不在实体中补发默认值。 */
    @TableField("result_read_window_ms")
    private Long resultReadWindowMs;

    /** 返回副本，避免调用方通过数组引用修改实体中的摘要。 */
    public byte[] getCredentialHash() {
        return credentialHash == null ? null : credentialHash.clone();
    }

    /** 保存副本；空值保持为空，是否允许写入由后续服务和数据库约束决定。 */
    public void setCredentialHash(byte[] credentialHash) {
        this.credentialHash = credentialHash == null ? null : credentialHash.clone();
    }
}
