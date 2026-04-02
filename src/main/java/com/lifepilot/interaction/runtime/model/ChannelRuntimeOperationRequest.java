package com.lifepilot.interaction.runtime.model;

import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * 渠道连接器通用操作请求。
 *
 * <p>用于消息更新、撤回、文件上传/下载、群管理等非发送类操作。</p>
 *
 * @author zsg
 * @since 2026-04-02
 */
public record ChannelRuntimeOperationRequest(
        String instanceId,
        String operationId,
        String operationType,
        Map<String, Object> parameters,
        @Nullable Map<String, Object> metadata
) {

    public ChannelRuntimeOperationRequest {
        if (instanceId == null || instanceId.isBlank()) {
            throw new IllegalArgumentException("instanceId 不能为空");
        }
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("operationId 不能为空");
        }
        if (operationType == null || operationType.isBlank()) {
            throw new IllegalArgumentException("operationType 不能为空");
        }
        parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
        metadata = metadata != null ? Map.copyOf(metadata) : null;
    }
}
