package com.lifepilot.meta.infra.interaction;

import com.lifepilot.interaction.model.ResponseContent;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.notification.NotificationRequest;
import com.lifepilot.notification.NotificationService;
import com.lifepilot.notification.Urgency;
import com.lifepilot.notification.config.NotificationProperties;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolContextKeys;
import com.lifepilot.tool.model.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 通知工具执行器 — 通过 {@link NotificationService} 向用户推送通知，非阻塞。
 *
 * <p>LLM 只需提供 {@code message} 和可选的 {@code urgency}，
 * 不再需要 {@code sessionId}（由 NotificationService 统一路由）。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class NotifyToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(NotifyToolExecutor.class);

    private final NotificationService notificationService;
    private final NotificationProperties notificationProperties;

    public NotifyToolExecutor(NotificationService notificationService,
                              NotificationProperties notificationProperties) {
        this.notificationService = notificationService;
        this.notificationProperties = notificationProperties;
    }

    /**
     * 执行通知推送（非阻塞）。
     *
     * @param input 工具输入，必需参数 message，可选参数 urgency
     * @return 推送结果
     */
    public ToolResult execute(ToolInput input) {
        try {
            String message = input.getParam("message", String.class);
            Urgency urgency = parseUrgency(input);
            String targetUserId = resolveTargetUserId(input);
            String channel = resolveChannel(input);

            var request = new NotificationRequest(
                    targetUserId,
                    new ResponseContent.TextContent(message),
                    urgency,
                    channel,
                    "agent.notify",
                    Map.of()
            );

            List<String> ids = notificationService.send(request);
            log.debug("通知推送完成: urgency={}, notificationIds={}", urgency, ids);

            return ToolResult.success(Map.of("notified", true, "count", ids.size()));
        } catch (IllegalArgumentException e) {
            return ToolResult.error("参数错误: " + e.getMessage());
        } catch (Exception e) {
            log.error("通知工具执行失败", e);
            return ToolResult.error("通知工具执行失败: " + e.getMessage());
        }
    }

    /**
     * 解析 urgency 参数，默认 MEDIUM。
     */
    private Urgency parseUrgency(ToolInput input) {
        try {
            String raw = input.getParam("urgency", String.class);
            if (raw != null && !raw.isBlank()) {
                return Urgency.valueOf(raw.toUpperCase());
            }
        } catch (Exception ignored) {
            // 参数缺失或无效，使用默认值
        }
        return Urgency.MEDIUM;
    }

    /**
     * 优先使用工具上下文中的当前用户，缺失时回退到默认用户。
     */
    private String resolveTargetUserId(ToolInput input) {
        return input.getContextValue(ToolContextKeys.USER_ID, String.class)
                .filter(userId -> !userId.isBlank())
                .orElse(notificationProperties.getDefaultUserId());
    }

    /**
     * 优先使用显式参数，其次使用当前请求上下文中的渠道信息。
     *
     * <p>notify 工具默认应回到当前会话渠道，避免再次广播到所有适配器。</p>
     */
    private String resolveChannel(ToolInput input) {
        String explicit = input.getOptionalParam("channel", String.class)
                .filter(value -> !value.isBlank())
                .orElse(null);
        if (explicit != null) {
            return normalizeChannel(explicit);
        }
        String fromContext = input.getContextValue(ToolContextKeys.CHANNEL_TYPE, String.class)
                .filter(value -> !value.isBlank())
                .orElse(null);
        if (fromContext != null) {
            return normalizeChannel(fromContext);
        }
        String sessionId = input.getContextValue(ToolContextKeys.SESSION_ID, String.class)
                .filter(value -> !value.isBlank())
                .orElse(null);
        if (sessionId != null) {
            int index = sessionId.indexOf(':');
            String inferred = index > 0 ? sessionId.substring(0, index) : ChannelType.WEB.value();
            return normalizeChannel(inferred);
        }
        return ChannelType.WEB.name();
    }

    private String normalizeChannel(String raw) {
        try {
            return ChannelType.fromValue(raw.toLowerCase()).name();
        } catch (IllegalArgumentException ignored) {
            return raw.toUpperCase();
        }
    }
}
