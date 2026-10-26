package com.ragqa.rag.ingest;

import com.ragqa.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

class Utf8TextParserTest {
    private final Utf8TextParser parser = new Utf8TextParser();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\r\n", "\u0000文本", "\u001b文本"})
    void rejectsBlankAndBinaryText(String text) {
        assertThatThrownBy(() -> parser.parse(text == null ? null : text.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void refusesMalformedUtf8OversizeAndExcessiveDecodedText() {
        for (byte[] bytes : new byte[][] { {(byte) 0xff}, {(byte) 0xc3, 0x28},
                new byte[Utf8TextParser.MAX_BYTES + 1], "a".repeat(500_001).getBytes(StandardCharsets.UTF_8) })
            assertThatThrownBy(() -> parser.parse(bytes)).isInstanceOf(BusinessException.class);
    }

    @Test
    void exactOriginalPositionsCoverEveryCharacterAndKeepSupplementaryUnicode() {
        String text = "第一章\r\n" + "审批😀要求。".repeat(400) + "\n\n下一章\n结束";
        var parts = parser.parse(text.getBytes(StandardCharsets.UTF_8));
        assertThat(parts).hasSizeGreaterThan(2);
        int coveredUntil = 0;
        for (var part : parts) {
            assertThat(part.start()).isLessThanOrEqualTo(coveredUntil);
            assertThat(part.content()).isEqualTo(text.substring(part.start(), part.end()));
            assertThat(part.content().length()).isLessThanOrEqualTo(Utf8TextParser.CHUNK_SIZE);
            assertThat(Character.isLowSurrogate(part.content().charAt(0))).isFalse();
            assertThat(Character.isHighSurrogate(part.content().charAt(part.content().length() - 1))).isFalse();
            assertThat(part.startLine()).isEqualTo(1 + (int) text.substring(0, part.start()).chars().filter(c -> c == '\n').count());
            coveredUntil = part.end();
        }
        assertThat(coveredUntil).isEqualTo(text.length());
    }

    @Test
    void shortDocumentProducesOneDeterministicTraceableChunk() {
        byte[] bytes = "报销须知\n请保留原始票据。".getBytes(StandardCharsets.UTF_8);
        var first = parser.parse(bytes);
        assertThat(first).isEqualTo(parser.parse(bytes)).hasSize(1);
        assertThat(first.get(0).index()).isZero();
        assertThat(first.get(0).startLine()).isEqualTo(1);
        assertThat(first.get(0).endLine()).isEqualTo(2);
    }
}
