package com.lifepilot.sync.model;

import java.util.List;

/**
 * 推送结果 record，描述一次推送操作的成功/失败统计及错误详情。
 *
 * @param successCount 成功推送的实体数量
 * @param failureCount 推送失败的实体数量
 * @param errors       每个失败项的错误详情
 * @author zsg
 * @since 2026-02-26
 */
public record PushResult(
        int successCount,
        int failureCount,
        List<PushError> errors
) {

    /**
     * 创建 PushResult 实例，对 errors 列表进行防御性拷贝。
     */
    public PushResult {
        errors = List.copyOf(errors);
    }

    /**
     * 单个推送失败项的错误详情。
     *
     * @param localEntityId 推送失败的本地实体 ID
     * @param errorMessage  错误消息
     */
    public record PushError(String localEntityId, String errorMessage) {
    }
}
