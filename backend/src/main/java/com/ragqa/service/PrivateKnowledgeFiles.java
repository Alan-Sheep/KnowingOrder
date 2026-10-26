package com.ragqa.service;

import com.ragqa.exception.BusinessException;
import com.ragqa.common.ResultCode;
import com.ragqa.rag.ingest.Utf8TextParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
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

/** 私有原件存储，不注册静态资源映射；只接受服务器生成的UUID键，不接受用户路径。 */
@Component
public class PrivateKnowledgeFiles {
    private final Path configuredRoot;

    public PrivateKnowledgeFiles(@Value("${knowingorder.knowledge.storage-root:E:/Codex/data/knowingorder/knowledge}") String root) {
        configuredRoot = Path.of(root).toAbsolutePath().normalize();
    }

    public String save(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > Utf8TextParser.MAX_BYTES)
            throw new BusinessException(ResultCode.FILE_TOO_LARGE, "原件大小超过允许范围");
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
            // 仅清除本次CREATE_NEW成功创建的文件，不能删除碰撞或其他请求的原件。
            if (created) {
                try { Files.deleteIfExists(file); }
                catch (IOException cleanupFailure) {
                    throw new BusinessException(ResultCode.SERVICE_BUSY, "原件写入失败且残片清理未完成，请管理员核对存储");
                }
            }
            throw new BusinessException(ResultCode.SERVICE_BUSY, "原件存储暂不可用，请稍后重试");
        }
    }

    public byte[] read(String key, long expectedSize, String expectedHash) {
        try {
            Path file = resolve(key, false);
            try (var channel = Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                    var input = java.nio.channels.Channels.newInputStream(channel)) {
                byte[] bytes = input.readNBytes(Utf8TextParser.MAX_BYTES + 1);
                if (bytes.length > Utf8TextParser.MAX_BYTES || bytes.length != expectedSize
                        || !sha256(bytes).equals(expectedHash)) throw new BusinessException(ResultCode.SERVICE_BUSY, "原件完整性校验失败，请联系知识负责人");
                return bytes;
            }
        } catch (IOException ex) {
            throw new BusinessException(ResultCode.SERVICE_BUSY, "原件暂不可读取，请稍后重试或联系知识负责人");
        }
    }

    /** 仅在确认数据库事务已回滚后清除本次新建原件；未知提交状态保留供核对。 */
    public void discard(String key) {
        try { Files.deleteIfExists(resolve(key, false)); }
        catch (IOException ex) { throw new BusinessException(ResultCode.SERVICE_BUSY, "回滚后原件清理未完成，需管理员核对存储"); }
    }

    private Path resolve(String key, boolean createRoot) throws IOException {
        if (key == null || !key.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
            throw new BusinessException(ResultCode.SERVICE_BUSY, "原件存储标识异常，请联系管理员");
        if (createRoot) Files.createDirectories(configuredRoot);
        // 拒绝存储根路径及祖先被重定向到其他目录；部署时由管理员设置目录ACL。
        if (!configuredRoot.toRealPath().equals(configuredRoot)) throw new IOException("私有存储根目录被重定向");
        return configuredRoot.resolve(key);
    }

    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("运行环境缺少SHA-256", ex); }
    }
}
