package com.ragqa.rag.ingest;

import com.ragqa.common.ResultCode;
import com.ragqa.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 首版只解析UTF-8纯文字，保留原始换行和位置；不调用模型、OCR或外部解析服务。 */
@Component
public class Utf8TextParser {
    public static final int MAX_BYTES = 1024 * 1024;
    public static final int MAX_CHARS = 500_000;
    public static final int CHUNK_SIZE = 1000;
    public static final int OVERLAP = 120;

    /** 所有格式统一切分提取后的规范文本；PDF/DOCX坐标不代表原始二进制字节位置。 */
    public List<Part> parse(String originalName, byte[] bytes) {
        if (originalName == null) throw invalid("缺少原件格式");
        String name = originalName.toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".txt") || name.endsWith(".md")) return parse(bytes);
        long deadline = System.nanoTime() + 5_000_000_000L;
        String text = new OfficeTextExtractor().extract(name, bytes);
        return split(text, deadline);
    }

    public List<Part> parse(byte[] bytes) {
        long deadline = System.nanoTime() + 5_000_000_000L;
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES)
            throw invalid("原件须为1字节至1MiB的UTF-8文本");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            throw invalid("文件不是有效UTF-8文本，请转换编码后重新上传");
        }
        return split(text, deadline);
    }

    private List<Part> split(String text, long deadline) {
        if (text.isBlank() || text.length() > MAX_CHARS) throw invalid("文档须含有效文字，且不超过50万UTF-16字符");
        int[] lines = new int[text.length() + 1];
        int line = 1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')
                throw invalid("文本含二进制或不支持的控制字符");
            lines[i] = line;
            if (c == '\n') line++;
        }
        lines[text.length()] = line;
        var parts = new ArrayList<Part>();
        int start = 0;
        while (start < text.length()) {
            if (System.nanoTime() >= deadline) throw new BusinessException(ResultCode.PROCESS_TIMEOUT, "文字解析超过5秒，请缩小文件后重试");
            int end = Math.min(start + CHUNK_SIZE, text.length());
            if (end < text.length()) {
                int paragraph = text.lastIndexOf("\n\n", end - 2);
                int newline = text.lastIndexOf('\n', end - 1);
                int boundary = paragraph >= start + CHUNK_SIZE / 2 ? paragraph + 2 : newline + 1;
                if (boundary > start + CHUNK_SIZE / 2) end = boundary;
                if (Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) end--;
            }
            parts.add(new Part(parts.size(), text.substring(start, end), start, end, lines[start], lines[end - 1]));
            if (parts.size() > 1000) throw invalid("片段数超过1000，请拆分原文件");
            if (end == text.length()) break;
            int next = Math.max(start + 1, end - OVERLAP);
            if (Character.isLowSurrogate(text.charAt(next)) && Character.isHighSurrogate(text.charAt(next - 1))) next--;
            start = next;
        }
        return List.copyOf(parts);
    }

    private BusinessException invalid(String message) { return new BusinessException(ResultCode.PARAM_ERROR, message); }

    /** UTF-16位置与Java String对应；不是token计数，不能拿字符数充当模型usage。 */
    public record Part(int index, String content, int start, int end, int startLine, int endLine) { }
}
