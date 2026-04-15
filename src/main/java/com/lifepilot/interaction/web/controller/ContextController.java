package com.lifepilot.interaction.web.controller;

import com.lifepilot.agent.task.proactive.behavior.ClipboardIntentBuffer;
import com.lifepilot.agent.task.reminder.ReminderClipboardIntent;
import com.lifepilot.agent.task.reminder.ReminderFocusState;
import com.lifepilot.agent.task.reminder.ReminderFocusStateHolder;
import com.lifepilot.interaction.model.ResponseContent;
import com.lifepilot.notification.NotificationRequest;
import com.lifepilot.notification.NotificationService;
import com.lifepilot.notification.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 上下文感知 REST Controller — 接收 Tauri 桌面端上报的环境上下文信息。
 *
 * <p>包括焦点应用状态和剪贴板意图识别。
 * 剪贴板高置信意图（快递/航班/车次）会通过通知系统推送到用户。</p>
 *
 * @author zsg
 * @since 2026-04-05
 */
@RestController
@RequestMapping("/api/context")
@ConditionalOnBean(ReminderFocusStateHolder.class)
public class ContextController {

    private static final Logger log = LoggerFactory.getLogger(ContextController.class);

    private final ReminderFocusStateHolder focusStateHolder;
    @Nullable
    private final NotificationService notificationService;
    @Nullable
    private final NotificationProperties notificationProperties;
    @Nullable
    private final ClipboardIntentBuffer clipboardIntentBuffer;

    public ContextController(ReminderFocusStateHolder focusStateHolder,
                             @Nullable NotificationService notificationService,
                             @Nullable NotificationProperties notificationProperties,
                             @Nullable ClipboardIntentBuffer clipboardIntentBuffer) {
        this.focusStateHolder = focusStateHolder;
        this.notificationService = notificationService;
        this.notificationProperties = notificationProperties;
        this.clipboardIntentBuffer = clipboardIntentBuffer;
    }

    /**
     * 接收焦点应用状态上报。
     *
     * @param focusState 焦点状态
     * @return 204 No Content
     */
    @PostMapping("/focus")
    public ResponseEntity<Void> reportFocusState(@RequestBody ReminderFocusState focusState) {
        focusStateHolder.update(focusState);
        return ResponseEntity.noContent().build();
    }

    /**
     * 接收剪贴板意图识别结果，通过通知系统推送给用户。
     *
     * @param intent 剪贴板意图
     * @return 204 No Content
     */
    @PostMapping("/clipboard-intent")
    public ResponseEntity<Void> reportClipboardIntent(@RequestBody ReminderClipboardIntent intent) {
        log.info("收到剪贴板意图: type={}, value={}", intent.intentType(), intent.value());

        // 优先送入主动引擎缓冲区，由 ClipboardBehavior 在心跳中处理
        if (clipboardIntentBuffer != null) {
            clipboardIntentBuffer.offer(intent);
            return ResponseEntity.noContent().build();
        }

        // 回退：直接通知（引擎不可用时）
        if (notificationService == null || notificationProperties == null) {
            log.debug("通知服务不可用，跳过剪贴板意图推送");
            return ResponseEntity.noContent().build();
        }

        String suggestion = generateSuggestion(intent);
        String userId = notificationProperties.getDefaultUserId();
        var content = new ResponseContent.TextContent(suggestion);
        var metadata = Map.of(
                "intentType", intent.intentType().name(),
                "value", intent.value()
        );
        var request = new NotificationRequest(userId, content, null, "clipboard_intent", metadata);
        notificationService.send(request);

        return ResponseEntity.noContent().build();
    }

    /**
     * 根据剪贴板意图生成操作建议。
     */
    private String generateSuggestion(ReminderClipboardIntent intent) {
        return switch (intent.intentType()) {
            case TRACKING_NUMBER -> "要帮你查询快递 %s 的物流状态吗？".formatted(intent.value());
            case FLIGHT_NUMBER -> "要帮你查询航班 %s 的动态吗？".formatted(intent.value());
            case TRAIN_NUMBER -> "要帮你查询车次 %s 的运行状态吗？".formatted(intent.value());
            case URL -> "要帮你总结链接 %s 的内容吗？".formatted(intent.value());
            case PHONE -> "要帮你查询号码 %s 的归属地吗？".formatted(intent.value());
        };
    }
}
