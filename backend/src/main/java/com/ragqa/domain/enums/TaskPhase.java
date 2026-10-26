package com.ragqa.domain.enums;

/** 任务正在执行或最后执行的阶段；任务是否结束由 TaskStatus 决定。 */
public enum TaskPhase {

    /** 分析消息意图、补充关系与处理顺序。 */
    ANALYZING,

    /** 检索可用资料。 */
    SEARCHING,

    /** 根据资料生成答案。 */
    GENERATING
}
