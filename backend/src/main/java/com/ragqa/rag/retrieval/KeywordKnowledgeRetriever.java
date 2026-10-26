package com.ragqa.rag.retrieval;

import com.ragqa.common.ResultCode;
import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.dto.KnowledgeAnswers.Evidence;
import com.ragqa.exception.BusinessException;
import com.ragqa.mapper.KnowledgeRetrievalMapper;
import com.ragqa.rag.chat.TaskDeadline;
import com.ragqa.service.EnterpriseResourceService;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 小语料关键词基线，不是语义向量检索；中文双字词与拉丁词都只作绑定参数。 */
@Component
public class KeywordKnowledgeRetriever implements KnowledgeRetriever {
    // Unicode字母包含汉字，非汉字分支须显式排除，避免把CAB是什么吞成同一个词。
    private static final Pattern WORD = Pattern.compile("[\\p{IsHan}]+|[\\p{L}\\p{N}&&[^\\p{IsHan}]]{2,}");
    private static final Set<String> STOP = Set.of("怎么", "如何", "什么", "可以", "是否", "需要", "请问",
            "一下", "我们", "多少", "哪些", "以及", "还有", "时候", "这个", "那个", "有什", "么时");
    private final KnowledgeRetrievalMapper mapper;
    private final EnterpriseResourceService resources;

    public KeywordKnowledgeRetriever(KnowledgeRetrievalMapper mapper, EnterpriseResourceService resources) {
        this.mapper = mapper;
        this.resources = resources;
    }

    @Override
    public List<Evidence> retrieve(EnterpriseMemberContext member, long spaceId, String question, TaskDeadline deadline) {
        resources.requireSpace(member, spaceId);
        deadline.timeoutFor(Duration.ofSeconds(2));
        List<String> terms = terms(question);
        if (terms.isEmpty()) return List.of();
        List<Evidence> candidates = mapper.search(member, spaceId, System.currentTimeMillis(), terms);
        deadline.timeoutFor(Duration.ofSeconds(2));
        // 候选最多24条，送入模型最多6条。重叠块不重复占满上下文，兼顾后面的不同主题。
        List<Evidence> selected = new ArrayList<>();
        for (Evidence candidate : candidates) {
            validate(candidate);
            boolean overlaps = selected.stream().anyMatch(previous -> previous.versionId().equals(candidate.versionId())
                    && Math.min(previous.endOffset(), candidate.endOffset())
                        - Math.max(previous.startOffset(), candidate.startOffset()) > 120);
            if (!overlaps) selected.add(candidate);
            if (selected.size() == 6) break;
        }
        return List.copyOf(selected);
    }

    @Override
    public Evidence requireEvidence(EnterpriseMemberContext member, long spaceId, long chunkId) {
        resources.requireSpace(member, spaceId);
        Evidence result = mapper.evidence(member, spaceId, System.currentTimeMillis(), chunkId);
        if (result == null) throw new BusinessException(ResultCode.NOT_FOUND, "资料不存在、已失效或不可访问");
        validate(result);
        return result;
    }

    /** 超长问题均匀取词，避免只截取开头而完全遗漏末尾的问题。 */
    public static List<String> terms(String question) {
        var unique = new LinkedHashSet<String>();
        var matcher = WORD.matcher(question.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            if (word.codePoints().allMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
                int[] chars = word.codePoints().toArray();
                for (int i = 0; i + 1 < chars.length; i++) {
                    String term = new String(chars, i, 2);
                    if (!STOP.contains(term)) unique.add(term);
                }
            } else unique.add(word.length() > 64 ? word.substring(0, 64) : word);
        }
        List<String> all = new ArrayList<>(unique);
        if (all.size() <= 48) return List.copyOf(all);
        List<String> sampled = new ArrayList<>();
        for (int i = 0; i < 48; i++) sampled.add(all.get(i * (all.size() - 1) / 47));
        return List.copyOf(sampled);
    }

    private static void validate(Evidence evidence) {
        if (evidence.content() == null || evidence.content().isBlank() || evidence.content().length() > 1000
                || evidence.startOffset() < 0 || evidence.endOffset() - evidence.startOffset() != evidence.content().length()
                || evidence.startLine() < 1 || evidence.endLine() < evidence.startLine())
            throw new BusinessException(ResultCode.CONFLICT, "片段溯源信息异常，请联系知识负责人重新解析");
    }
}
