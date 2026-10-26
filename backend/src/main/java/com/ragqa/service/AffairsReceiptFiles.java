package com.ragqa.service;

import com.ragqa.common.ResultCode;
import com.ragqa.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/**
 * 补贴凭证私有原件存储。
 *
 * <p>与知识原件分离目录：凭证属于员工事务，不参与知识检索，也不注册静态资源映射。
 * 只接受服务端生成的 UUID 键，不接受用户路径；单文件上限 1MiB。</p>
 */
@Component
public class AffairsReceiptFiles {

    /** 单文件上限 1MiB，与 application.yml 的 multipart 限制一致。 */
    public static final long MAX_BYTES = 1024L * 1024;

    private final Path configuredRoot;

    public AffairsReceiptFiles(
            @Value("${knowingorder.affairs.storage-root:E:/Codex/data/knowingorder/affairs}") String root) {
        configuredRoot = Path.of(root).toAbsolutePath().normalize();
    }

    public String save(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES)
            throw new BusinessException(ResultCode.FILE_TOO_LARGE, "凭证原件大小超过允许范围");
        String key = UUID.randomUUID().toString();
        Path file = null;
        boolean created = false;
        try {
            file = resolve(key, true);
            try (SeekableByteChannel channel = Files.newByteChannel(file,
                    Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS))) {
                created = true;
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
            }
            return key;
        } catch (IOException ex) {
            // 仅清理本次 CREATE_NEW 成功创建的文件，不能删除碰撞或其他请求的原件。
            if (created) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException cleanupFailure) {
                    throw new BusinessException(ResultCode.SERVICE_BUSY, "凭证写入失败且残片清理未完成，请管理员核对存储");
                }
            }
            throw new BusinessException(ResultCode.SERVICE_BUSY, "凭证存储暂不可用，请稍后重试");
        }
    }

    public byte[] read(String key, long expectedSize, String expectedHash) {
        try {
            Path file = resolve(key, false);
            try (var channel = Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                    var input = Channels.newInputStream(channel)) {
                byte[] bytes = input.readNBytes((int) MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES || bytes.length != expectedSize
                        || !sha256(bytes).equals(expectedHash))
                    throw new BusinessException(ResultCode.SERVICE_BUSY, "凭证完整性校验失败，请重新上传");
                return bytes;
            }
        } catch (IOException ex) {
            throw new BusinessException(ResultCode.SERVICE_BUSY, "凭证暂不可读取，请稍后重试");
        }
    }

    /** 仅在确认事务回滚后清除本次新建原件；未知提交状态保留供核对。 */
    public void discard(String key) {
        try {
            Files.deleteIfExists(resolve(key, false));
        } catch (IOException ex) {
            throw new BusinessException(ResultCode.SERVICE_BUSY, "回滚后凭证清理未完成，需管理员核对存储");
        }
    }

    private Path resolve(String key, boolean createRoot) throws IOException {
        if (key == null || !key.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
            throw new BusinessException(ResultCode.SERVICE_BUSY, "凭证存储标识异常，请联系管理员");
        if (createRoot) Files.createDirectories(configuredRoot);
        // 拒绝存储根路径被重定向到其他目录；部署时由管理员设置目录 ACL。
        if (!configuredRoot.toRealPath().equals(configuredRoot)) throw new IOException("凭证存储根目录被重定向");
        return configuredRoot.resolve(key);
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("运行环境缺少SHA-256", ex);
        }
    }
}
