package com.ragqa.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ragqa.common.PageResult;
import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.AuthenticatedUserPrincipal;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.AffairsApplicationMapper;
import com.ragqa.mapper.AffairsReceiptMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.Set;

/**
 * 补贴凭证：上传、列表与下载。
 *
 * <p>不做 OCR，只保存原件与 SHA-256 摘要；类型由文件头判定而不是信任扩展名，
 * 下载一律以附件返回并禁止缓存，存储键不出现在接口响应中。</p>
 */
@Slf4j
@Service
public class AffairsReceiptService {

    /** 下载结果，由控制器组装响应头。 */
    public record Downloaded(String name, String contentType, byte[] bytes) { }

    private static final Set<String> TYPES = Set.of("image/jpeg", "image/png", "application/pdf");
    private static final Set<String> FROZEN = Set.of("SETTLED", "REJECTED", "CANCELLED");
    private static final String UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    private final EnterpriseMemberService members;
    private final AffairsApplicationMapper applications;
    private final AffairsReceiptMapper data;
    private final AffairsReceiptFiles files;
    private final TransactionTemplate tx;

    public AffairsReceiptService(EnterpriseMemberService members, AffairsApplicationMapper applications,
            AffairsReceiptMapper data, AffairsReceiptFiles files, PlatformTransactionManager manager) {
        this.members = members;
        this.applications = applications;
        this.data = data;
        this.files = files;
        tx = new TransactionTemplate(manager);
        tx.setTimeout(5);
    }

    public PageResult<AffairsReceiptMapper.Receipt> list(AuthenticatedUserPrincipal user, long enterprise,
            String applicationId, long pageNum, long pageSize) {
        String key = uuid(applicationId);
        long offset = offset(pageNum, pageSize);
        var scope = members.requireMember(user, enterprise);
        var app = applications.detail(enterprise, key);
        if (app == null || !canRead(scope, app, key)) throw missing();
        var result = new Page<AffairsReceiptMapper.Receipt>(pageNum, pageSize, data.count(enterprise, key));
        result.setRecords(data.receipts(enterprise, key, pageSize, offset));
        return PageResult.of(result);
    }

    /**
     * 上传原件：申请人或管理员可传；已结算、已拒绝、已撤回不可再补件。
     * 同一申请的同一摘要重复上传直接返回已有记录，不重复落盘。
     */
    public AffairsReceiptMapper.Receipt upload(AuthenticatedUserPrincipal user, long enterprise,
            String applicationId, MultipartFile file) {
        String key = uuid(applicationId);
        if (file == null) throw invalid();
        var scope = members.requireMember(user, enterprise);
        var app = applications.detail(enterprise, key);
        if (app == null || !canWrite(scope, app)) throw missing();
        if (FROZEN.contains(app.status()))
            throw new BusinessException(ResultCode.CONFLICT, "当前状态不能再上传凭证");
        byte[] bytes;
        try (var input = file.getInputStream()) {
            bytes = input.readNBytes((int) AffairsReceiptFiles.MAX_BYTES + 1);
        } catch (IOException ex) {
            throw new BusinessException(ResultCode.SERVICE_BUSY, "上传连接中断，请稍后重试");
        }
        if (bytes.length == 0) throw invalid();
        if (bytes.length > AffairsReceiptFiles.MAX_BYTES)
            throw new BusinessException(ResultCode.FILE_TOO_LARGE, "凭证原件最大1MiB");
        String actual = detect(bytes);
        if (actual == null)
            throw new BusinessException(ResultCode.PARAM_ERROR, "仅支持 JPG、PNG 图片或 PDF 原件");
        String declared = declared(file.getContentType());
        if (declared != null && !declared.equals(actual))
            throw new BusinessException(ResultCode.PARAM_ERROR, "文件内容与声明的类型不一致");
        String name = safeName(file.getOriginalFilename(), actual);
        String hash = AffairsReceiptFiles.sha256(bytes);
        var existing = data.byDigest(enterprise, key, hash);
        if (existing != null) return existing;
        return tx.execute(s -> {
            var locked = applications.applicationForUpdate(key);
            if (locked == null || locked.enterpriseId() != enterprise) throw missing();
            if (!canWrite(scope, locked)) throw missing();
            if (FROZEN.contains(locked.status()))
                throw new BusinessException(ResultCode.CONFLICT, "当前状态不能再上传凭证");
            var again = data.byDigest(enterprise, key, hash);
            if (again != null) return again;
            String storage = files.save(bytes);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        try {
                            files.discard(storage);
                        } catch (BusinessException ex) {
                            log.error("凭证回滚清理失败，需管理员核对；不记录路径或内容");
                        }
                    }
                }
            });
            var receipt = new AffairsReceiptMapper.NewReceipt();
            receipt.setEnterpriseId(enterprise);
            receipt.setApplicationId(key);
            receipt.setOriginalName(name);
            receipt.setStoragePath(storage);
            receipt.setContentType(actual);
            receipt.setByteSize(bytes.length);
            receipt.setSha256(hash);
            receipt.setUploadedBy(scope.memberId());
            receipt.setCreatedMs(System.currentTimeMillis());
            if (data.insert(receipt) != 1) throw conflict();
            var saved = data.byDigest(enterprise, key, hash);
            if (saved == null) throw conflict();
            return saved;
        });
    }

    /** 申请人、参与成员、管理员与财务可下载；读取后复核身份与权限，撤权立即失效。 */
    public Downloaded download(AuthenticatedUserPrincipal user, long enterprise, String applicationId, long id) {
        String key = uuid(applicationId);
        if (id <= 0) throw invalid();
        var scope = members.requireMember(user, enterprise);
        var app = applications.detail(enterprise, key);
        if (app == null || !canRead(scope, app, key)) throw missing();
        var file = data.file(enterprise, key, id);
        if (file == null) throw missing();
        byte[] bytes = files.read(file.storagePath(), file.byteSize(), file.sha256());
        var current = members.requireCurrent(scope);
        var again = applications.detail(enterprise, key);
        if (again == null || !canRead(current, again, key)) throw missing();
        return new Downloaded(file.originalName(), file.contentType(), bytes);
    }

    private boolean canRead(EnterpriseMemberContext scope, AffairsApplicationMapper.Detail app, String id) {
        if (app.applicantMemberId() == scope.memberId()) return true;
        if ("ADMIN".equals(scope.role()) || "FINANCE".equals(scope.role())) return true;
        return applications.hasParticipant(id, scope.memberId()) != null;
    }

    /** 补件只允许申请人或管理员，参与人只有查看权。 */
    private boolean canWrite(EnterpriseMemberContext scope, AffairsApplicationMapper.Detail app) {
        return app.applicantMemberId() == scope.memberId() || "ADMIN".equals(scope.role());
    }

    private boolean canWrite(EnterpriseMemberContext scope, AffairsApplicationMapper.Application app) {
        return app.applicantMemberId() == scope.memberId() || "ADMIN".equals(scope.role());
    }

    private String declared(String value) {
        if (value == null || value.isBlank()) return null;
        String clean = value.strip().toLowerCase(Locale.ROOT);
        if ("image/jpg".equals(clean)) clean = "image/jpeg";
        if (!TYPES.contains(clean))
            throw new BusinessException(ResultCode.PARAM_ERROR, "仅支持 JPG、PNG 图片或 PDF 原件");
        return clean;
    }

    /** 以文件头判定真实类型：JPG FFD8FF、PNG 89504E47、PDF %PDF。 */
    private String detect(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N'
                && bytes[3] == 'G' && (bytes[4] & 0xFF) == 0x0D && (bytes[5] & 0xFF) == 0x0A
                && (bytes[6] & 0xFF) == 0x1A && (bytes[7] & 0xFF) == 0x0A) return "image/png";
        if (bytes.length >= 4 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F')
            return "application/pdf";
        return null;
    }

    /** 展示名只保留文件名本身，拒绝路径、控制字符与长度超限。 */
    private String safeName(String value, String actual) {
        if (value == null) throw invalid();
        String clean = value.strip();
        int slash = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        if (slash >= 0) clean = clean.substring(slash + 1);
        if (clean.isEmpty() || clean.length() > 128
                || clean.codePoints().anyMatch(c -> Character.isISOControl(c) || c == '"' || c == ':')) throw invalid();
        String lower = clean.toLowerCase(Locale.ROOT);
        boolean match = switch (actual) {
            case "image/jpeg" -> lower.endsWith(".jpg") || lower.endsWith(".jpeg");
            case "image/png" -> lower.endsWith(".png");
            default -> lower.endsWith(".pdf");
        };
        if (!match) throw new BusinessException(ResultCode.PARAM_ERROR, "文件扩展名与内容类型不一致");
        return clean;
    }

    private String uuid(String value) {
        if (value == null || !value.matches(UUID)) throw invalid();
        return value.toLowerCase();
    }

    private long offset(long page, long size) {
        if (page < 1 || page > 100000 || size < 1 || size > 100) throw invalid();
        return (page - 1) * size;
    }

    private BusinessException invalid() {
        return new BusinessException(ResultCode.PARAM_ERROR, "凭证参数不正确");
    }

    private BusinessException missing() {
        return new BusinessException(ResultCode.NOT_FOUND, "补贴申请或凭证不存在或不可访问");
    }

    private BusinessException conflict() {
        return new BusinessException(ResultCode.CONFLICT, "凭证已变化，请刷新后重试");
    }
}
