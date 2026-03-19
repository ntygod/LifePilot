package com.lifepilot.meta.infra.interaction;

import com.lifepilot.interaction.model.ResponseContent;
import com.lifepilot.notification.NotificationRequest;
import com.lifepilot.notification.NotificationService;
import com.lifepilot.notification.Urgency;
import com.lifepilot.notification.config.NotificationProperties;
import com.lifepilot.tool.model.ToolInput;
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

            var request = new NotificationRequest(
                    notificationProperties.getDefaultUserId(),
                    new ResponseContent.TextContent(message),
                    urgency,
                    null,
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
}
