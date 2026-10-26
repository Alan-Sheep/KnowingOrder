package com.ragqa.mapper;

import lombok.Data;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 补贴凭证固定SQL。
 *
 * <p>凭证只保存原件与完整性摘要，不做 OCR；同一申请同一摘要只允许一条，重复上传按已存在返回。</p>
 */
@Mapper
public interface AffairsReceiptMapper {

    /** 列表行：上传人显示名称来自企业成员与系统账号。 */
    record Receipt(long id, String originalName, String contentType, long byteSize, String sha256,
            long createdMs, long uploadedBy, String uploaderName) { }

    /** 下载所需的最小字段；storagePath 只在服务端使用，不对外拼接直链。 */
    record File(long id, String originalName, String storagePath, String contentType, long byteSize,
            String sha256) { }

    @Data
    class NewReceipt {
        private Long id;
        private long enterpriseId;
        private String applicationId;
        private String originalName;
        private String storagePath;
        private String contentType;
        private long byteSize;
        private String sha256;
        private long uploadedBy;
        private long createdMs;
    }

    /** 上传人展示中文姓名（昵称），昵称为空才回退登录账号。 */
    String UPLOADER_NAME = "COALESCE(NULLIF(TRIM(u.nickname),''),u.username)";

    @Select("SELECT COUNT(*) FROM rag_affair_receipt WHERE enterprise_id=#{enterprise} AND application_id=#{application}")
    long count(@Param("enterprise") long enterprise, @Param("application") String application);

    @Select("SELECT r.id,r.original_name AS originalName,r.content_type AS contentType,r.byte_size AS byteSize,"
            + "r.sha256,r.created_ms AS createdMs,r.uploaded_by AS uploadedBy," + UPLOADER_NAME + " AS uploaderName "
            + "FROM rag_affair_receipt r "
            + "JOIN sys_enterprise_member m ON m.id=r.uploaded_by AND m.enterprise_id=#{enterprise} "
            + "JOIN sys_user u ON u.id=m.user_id "
            + "WHERE r.enterprise_id=#{enterprise} AND r.application_id=#{application} "
            + "ORDER BY r.created_ms DESC,r.id LIMIT #{size} OFFSET #{offset}")
    List<Receipt> receipts(@Param("enterprise") long enterprise, @Param("application") String application,
            @Param("size") long size, @Param("offset") long offset);

    @Select("SELECT r.id,r.original_name AS originalName,r.storage_path AS storagePath,"
            + "r.content_type AS contentType,r.byte_size AS byteSize,r.sha256 "
            + "FROM rag_affair_receipt r WHERE r.enterprise_id=#{enterprise} "
            + "AND r.application_id=#{application} AND r.id=#{id}")
    File file(@Param("enterprise") long enterprise, @Param("application") String application,
            @Param("id") long id);

    @Select("SELECT r.id,r.original_name AS originalName,r.content_type AS contentType,r.byte_size AS byteSize,"
            + "r.sha256,r.created_ms AS createdMs,r.uploaded_by AS uploadedBy," + UPLOADER_NAME + " AS uploaderName "
            + "FROM rag_affair_receipt r "
            + "JOIN sys_enterprise_member m ON m.id=r.uploaded_by AND m.enterprise_id=#{enterprise} "
            + "JOIN sys_user u ON u.id=m.user_id "
            + "WHERE r.enterprise_id=#{enterprise} AND r.application_id=#{application} "
            + "AND r.sha256=#{sha256} LIMIT 1")
    Receipt byDigest(@Param("enterprise") long enterprise, @Param("application") String application,
            @Param("sha256") String sha256);

    @Insert("""
            INSERT INTO rag_affair_receipt(enterprise_id,application_id,original_name,storage_path,content_type,
                byte_size,sha256,uploaded_by,created_ms)
            VALUES(#{enterpriseId},#{applicationId},#{originalName},#{storagePath},#{contentType},
                #{byteSize},#{sha256},#{uploadedBy},#{createdMs})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(NewReceipt receipt);
}
