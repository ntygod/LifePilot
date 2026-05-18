package com.lifepilot.interaction.model;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import lombok.Builder;
import org.springframework.lang.Nullable;

/**
 * 统一网关响应 record，中间件管道的输出。
 *
 * <p>紧凑构造器为 {@code responseId} 提供默认值（UUID），
 * 集合字段使用 {@link List#copyOf} 和 {@link Map#copyOf} 确保不可变性。
 * 提供工厂方法快速创建常见响应（成功、错误、限流、未授权）。</p>
 *
 * <p>{@code artifactRefs} 字段（2026-05-17 引入）承载本次回复关联的会话产物
 * 引用列表，让 {@code ChannelDeliveryDispatcher} 按渠道差异化适配（IM 渠道走
 * connector RPC 投递文件消息、Web 端走 SSE 事件 + REST 下载端点、Tauri 桌面端
 * 复用 Web 链路 + 本地 plugin 唤起文件）。既有工厂方法默认 {@code List.of()},
 * 不破坏既有调用点。</p>
 *
 * @param responseId   响应唯一标识，默认 UUID
 * @param channelType  通道类型
 * @param content      响应内容
 * @param attachments  附件列表（不可变）
 * @param metadata     元数据（不可变）
 * @param latency      处理延迟
 * @param tokenUsage   Token 消耗统计，可为 null
 * @param statusCode   HTTP 风格状态码
 * @param errorMessage 错误消息，可为 null
 * @param artifactRefs 会话产物引用列表（无产物时为 {@link List#of()}）
 * @author zsg
 * @since 2026-02-25
 */
@Builder(toBuilder = true)
public record GatewayResponse(
        String responseId,
        ChannelType channelType,
        ResponseContent content,
        List<GatewayMessage.Attachment> attachments,
        Map<String, Object> metadata,
        Duration latency,
        @Nullable TokenUsage tokenUsage,
        int statusCode,
        @Nullable String errorMessage,
        List<ArtifactRef> artifactRefs
) {

    /**
     * 紧凑构造器：为 responseId 提供默认值，集合字段防御性拷贝。
     */
    public GatewayResponse {
        responseId = responseId != null ? responseId : UUID.randomUUID().toString();
        attachments = attachments != null ? List.copyOf(attachments) : List.of();
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        artifactRefs = artifactRefs != null ? List.copyOf(artifactRefs) : List.of();
    }

    /**
     * 创建成功响应。
     *
     * @param channelType 通道类型
     * @param content     响应内容
     * @return 状态码 200 的成功响应
     */
    public static GatewayResponse success(ChannelType channelType, ResponseContent content) {
        return new GatewayResponse(
                null, channelType, content,
                List.of(), Map.of(), Duration.ZERO,
                null, 200, null, List.of()
        );
    }

    /**
     * 创建错误响应。
     *
     * @param channelType 通道类型
     * @param message     错误消息
     * @param code        状态码
     * @return 指定状态码的错误响应
     */
    public static GatewayResponse error(ChannelType channelType, String message, int code) {
        return new GatewayResponse(
                null, channelType, new ResponseContent.TextContent(message),
                List.of(), Map.of(), Duration.ZERO,
                null, code, message, List.of()
        );
    }

    /**
     * 创建限流响应。
     *
     * @param channelType 通道类型
     * @return 状态码 429 的限流响应
     */
    public static GatewayResponse rateLimited(ChannelType channelType) {
        return error(channelType, "请求过于频繁，请稍后再试", 429);
    }

    /**
     * 创建未授权响应。
     *
     * @param channelType 通道类型
     * @return 状态码 401 的未授权响应
     */
    public static GatewayResponse unauthorized(ChannelType channelType) {
        return error(channelType, "未授权访问", 401);
    }

    /**
     * 判断响应是否成功（状态码 200-299）。
     *
     * @return 如果状态码在 200-299 范围内则返回 true
     */
    public boolean isSuccess() {
        return statusCode >= 200 && statusCode < 300;
    }
}
