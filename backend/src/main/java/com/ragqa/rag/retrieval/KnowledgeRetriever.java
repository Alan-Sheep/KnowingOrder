package com.ragqa.rag.retrieval;

import com.ragqa.domain.dto.EnterpriseMemberContext;
import com.ragqa.domain.dto.KnowledgeAnswers.Evidence;
import com.ragqa.rag.chat.TaskDeadline;

import java.util.List;

/** 授权检索替换入口；后续适配向量召回仍须保留当前发布版本和最终权限复核。 */
public interface KnowledgeRetriever {
    List<Evidence> retrieve(EnterpriseMemberContext member, long spaceId, String question, TaskDeadline deadline);
    Evidence requireEvidence(EnterpriseMemberContext member, long spaceId, long chunkId);
}
