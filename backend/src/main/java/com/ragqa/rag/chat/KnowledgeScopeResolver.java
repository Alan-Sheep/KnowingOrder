package com.ragqa.rag.chat;

import com.ragqa.domain.dto.EnterpriseMemberContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** 学校试点范围识别；调用方须先鉴权并核对问题外发许可，默认不为任何组织启用。 */
@Component
public class KnowledgeScopeResolver {
    private static final KnowledgeScopeContract.Profile SCHOOL = new KnowledgeScopeContract.Profile("school", 1,
            "本组织是学校。范围包括本校招生、课程、考试、毕业、奖助学金、收费、报销、宿舍、图书馆、校内办事流程及通知。"
            + "普通个人生活、宠物保险购买等与本校事务无关的问题不在范围内；但询问本校开设宠物保险课程属于校内课程事务。"
            + "没有资料不代表范围外。无法确定所属业务时返回UNKNOWN，不猜测本校是否存在某项规定。");
    private final ChatModel model;
    private final Set<Long> enterprises;

    public KnowledgeScopeResolver(ChatModel model,
            @Value("${knowingorder.knowledge.school-enterprises:}") String configured) {
        this.model = model;
        enterprises = Arrays.stream(configured.split(",", -1)).map(String::strip).filter(value -> !value.isEmpty())
                .map(value -> {
                    if (!value.matches("[1-9][0-9]{0,18}"))
                        throw new IllegalArgumentException("学校试点须配置正整数组织编号");
                    try { return Long.parseLong(value); }
                    catch (NumberFormatException error) { throw new IllegalArgumentException("学校试点组织编号超出范围"); }
                }).collect(Collectors.toUnmodifiableSet());
    }

    public boolean applies(long enterpriseId) { return enterprises.contains(enterpriseId); }

    /** 一次调用、阶段最多5秒且不延长父预算；仅传范围定义、问题及可信话题，不传成员编号或文档。 */
    public KnowledgeScopeContract.Proposal resolve(EnterpriseMemberContext member, long spaceId, String question,
            String antecedent, TaskDeadline deadline) {
        var input = new KnowledgeScopeContract.Input(UUID.randomUUID().toString(), member.enterpriseId(),
                member.memberId(), spaceId, SCHOOL, question, antecedent);
        var payload = new HashMap<String, Object>();
        payload.put("snapshotId", KnowledgeScopeContract.snapshotId(input));
        payload.put("profile", SCHOOL);
        payload.put("question", question);
        if (antecedent != null) payload.put("antecedentQuestion", antecedent);
        TaskDeadline stage = deadline.limitedTo(Duration.ofSeconds(5));
        var output = model.call("knowledge-scope", payload, 2000, stage);
        FollowUpResolver.active(stage);
        return KnowledgeScopeContract.proposal(input, output);
    }
}
