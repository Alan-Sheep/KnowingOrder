package com.ragqa.service;

import com.ragqa.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.channels.SeekableByteChannel;
import java.nio.ByteBuffer;
import java.io.IOException;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PrivateKnowledgeFilesTest {
    @TempDir Path root;

    @Test
    void randomImmutableKeyRoundTripsBytesAndVerifiesIntegrity() throws Exception {
        var files = new PrivateKnowledgeFiles(root.toString());
        byte[] content = "原始内容\r\n第二行".getBytes(StandardCharsets.UTF_8);
        String key = files.save(content);
        assertThat(key).matches("[0-9a-f-]{36}");
        assertThat(files.read(key, content.length, PrivateKnowledgeFiles.sha256(content))).isEqualTo(content);
        Files.writeString(root.resolve(key), "被改动", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> files.read(key, content.length, PrivateKnowledgeFiles.sha256(content))).isInstanceOf(BusinessException.class);
        files.discard(key);
        assertThat(Files.exists(root.resolve(key))).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"../outside", "C:\\file", "../../1.txt", "00000000-0000-0000-0000-000000000000/../secret"})
    void rejectsNonServerKeysBeforeReading(String key) {
        var files = new PrivateKnowledgeFiles(root.toString());
        assertThatThrownBy(() -> files.read(key, 1, "hash")).isInstanceOf(BusinessException.class);
    }

    @Test
    void partialWriteFailureRemovesOnlyItsCreatedFile() throws Exception {
        Path existing = root.resolve("保留原件");
        Files.writeString(existing, "其他原件", StandardCharsets.UTF_8);
        try (var mocked = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            mocked.when(() -> Files.newByteChannel(any(Path.class), anySet())).thenAnswer(call -> {
                SeekableByteChannel channel = spy((SeekableByteChannel) call.callRealMethod());
                doThrow(new IOException("测试磁盘故障")).when(channel).write(any(ByteBuffer.class));
                return channel;
            });
            assertThatThrownBy(() -> new PrivateKnowledgeFiles(root.toString()).save(new byte[]{1, 2}))
                    .isInstanceOf(BusinessException.class).hasMessageNotContaining(root.toString());
        }
        try (var entries = Files.list(root)) { assertThat(entries.toList()).containsExactly(existing); }
        assertThat(Files.readString(existing, StandardCharsets.UTF_8)).isEqualTo("其他原件");
    }

    @Test
    void missingOriginalAndUnwritableRootReturnSafeFailure() throws Exception {
        var files = new PrivateKnowledgeFiles(root.toString());
        assertThatThrownBy(() -> files.read("00000000-0000-0000-0000-000000000000", 1, "hash"))
                .isInstanceOf(BusinessException.class).hasMessageNotContaining(root.toString());
        Path fileAsDirectory = root.resolve("not-directory");
        Files.writeString(fileAsDirectory, "占位", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new PrivateKnowledgeFiles(fileAsDirectory.toString()).save(new byte[]{1}))
                .isInstanceOf(BusinessException.class).hasMessageNotContaining(root.toString());
    }
}
