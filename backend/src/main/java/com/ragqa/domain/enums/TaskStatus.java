package com.ragqa.domain.enums;

/**
 * 问答任务状态。对外使用枚举名称，搜索、分析和生成细分阶段由后续任务快照表达。
 *
 * <p>终态表示本任务不再执行，不等于已经生成答案；合并任务还需查询目标任务，
 * 待澄清任务则等待用户另发消息。本枚举不负责状态迁移、并发控制或资源释放。</p>
 */
public enum TaskStatus {

    /** 已接收，等待调度；不能据此声称已分析或已合并。 */
    QUEUED(false),

    /** 正在分析、检索或生成，具体阶段由任务快照提供。 */
    RUNNING(false),

    /** 已完成回答。 */
    SUCCEEDED(true),

    /** 部分问题已完成，保留可用答案和来源。 */
    PARTIAL(true),

    /** 正常完成检索，但没有足够资料。 */
    NO_SOURCES(true),

    /** 处理失败，向用户提供安全提示。 */
    FAILED(true),

    /** 排队或处理时间超过预算。 */
    TIMED_OUT(true),

    /** 指定任务已被用户显式取消。 */
    CANCELLED(true),

    /** 本任务已并入另一任务，最新快照须提供目标编号。 */
    MERGED(true),

    /** 本次分析已结束，需要用户另发澄清消息，不占用线程等待输入。 */
    NEEDS_CLARIFICATION(true);

    private final boolean terminal;

    TaskStatus(boolean terminal) {
        this.terminal = terminal;
    }

    /** 是否已结束本任务的执行；不能据此判断是否成功或是否已有答案。 */
    public boolean isTerminal() {
        return terminal;
    }
}
