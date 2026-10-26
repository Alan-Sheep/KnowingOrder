package com.ragqa.rag.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.ragqa.common.ResultCode;
import com.ragqa.exception.BusinessException;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 改写结果只提供一条查找用备选句，不携带权限、工具或证据。
 * 调用方先使用ChatModel严格解析JSON；本类不调用模型，也不授权检索。
 */
public final class QueryRewriteContract {
    private static final Pattern NUMBERS = Pattern.compile("[-+]?\\p{Nd}+(?:[.,]\\p{Nd}+)*(?:[%％])?");
    private static final Pattern CONSTRAINTS = Pattern.compile(
            "不允许|不超过|不少于|不得|不能|不可|不要|禁止|至少|至多|最多|最低|最高|不|未|无"
                    + "|\\b(?:at least|at most|not|no|never|without)\\b");

    private QueryRewriteContract() { }

    /**
     * 原问题保留给调用方展示和生成，仅返回通过校验的alternativeQuery。
     * 数字与有限约束词必须原样保留；这只是保守词法检查，不能证明语义等价。
     */
    public static String alternativeQuery(String originalQuestion, JsonNode output) {
        require(originalQuestion != null && originalQuestion.length() <= 1200
                && hasText(originalQuestion) && safeCharacters(originalQuestion, true));
        require(output != null && output.isObject() && output.size() == 1
                && output.path("alternativeQuery").isTextual());
        String alternative = output.path("alternativeQuery").textValue();
        // 500码点仅为单条备选句的初始契约上限，不代表检索质量或模型Token预算。
        require(alternative.length() <= 1000 && alternative.codePointCount(0, alternative.length()) <= 500
                && hasText(alternative) && safeCharacters(alternative, false));
        require(tokens(NUMBERS, originalQuestion).equals(tokens(NUMBERS, alternative)));
        require(tokens(CONSTRAINTS, originalQuestion).equals(tokens(CONSTRAINTS, alternative)));
        return alternative.strip();
    }

    private static Map<String, Integer> tokens(Pattern pattern, String text) {
        Map<String, Integer> counts = new HashMap<>();
        var matcher = pattern.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) counts.merge(matcher.group(), 1, Integer::sum);
        return counts;
    }

    private static boolean hasText(String text) {
        return text.codePoints().anyMatch(cp -> !Character.isWhitespace(cp) && !Character.isSpaceChar(cp));
    }

    private static boolean safeCharacters(String text, boolean original) {
        return text.codePoints().allMatch(cp -> {
            if (original && (cp == '\n' || cp == '\r' || cp == '\t')) return true;
            int type = Character.getType(cp);
            return type != Character.CONTROL && type != Character.FORMAT && type != Character.SURROGATE
                    && type != Character.LINE_SEPARATOR && type != Character.PARAGRAPH_SEPARATOR;
        });
    }

    private static void require(boolean valid) {
        if (!valid) throw new BusinessException(ResultCode.BIZ_ERROR, "检索扩展数据校验未通过");
    }
}
