package com.ragqa.domain.dto;

import com.ragqa.domain.enums.TaskStatus;

/**
 * 消息提交回执，作为统一响应 Result 的业务数据，不携带最终答案。
 *
 * <p>服务层完成可靠记录与调度接纳后才能返回回执。初次接收可返回 QUEUED，
 * 去重重放可反映原任务的当前状态；最新答案及合并目标通过 taskId 查询任务快照。
 * 本类型仅约束数据，不能替代持久化、去重与调度实现。</p>
 *
 * @param conversationId 会话编号，使用项目现有 Long 字符串序列化规则避免前端丢失精度
 * @param messageId 原始消息编号，重复提交应关联同一条消息
 * @param taskId 本次受理任务编号，重连和取消时使用
 * @param version 任务快照版本，从 1 开始；不是问题修订编号，也不表示完成比例
 * @param status 生成本回执时的任务状态；已接收不等于已完成回答
 * @param message 服务端提供的安全提示，不能传入第三方原始响应或异常堆栈
 */
public record MessageReceipt(Long conversationId, Long messageId, Long taskId,
                             long version, TaskStatus status, String message) {

    /** 创建时检查必填值，仅在异常中输出字段名，不回显数据内容。 */
    public MessageReceipt {
        requireId(conversationId, "conversationId");
        requireId(messageId, "messageId");
        requireId(taskId, "taskId");
        if (version < 1) {
            throw new IllegalArgumentException("version 必须为正整数");
        }
        if (status == null) {
            throw new IllegalArgumentException("status 不能为空");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message 不能为空白");
        }
    }

    private static void requireId(Long value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " 必须为正整数");
        }
    }
}
