package com.lifepilot.interaction.web.controller;

import com.lifepilot.agent.task.reminder.ReminderClipboardIntent;
import com.lifepilot.agent.task.reminder.ReminderClipboardIntentType;
import com.lifepilot.agent.task.reminder.ReminderFocusState;
import com.lifepilot.agent.task.reminder.ReminderFocusStateHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 上下文感知 REST Controller — 接收 Tauri 桌面端上报的环境上下文信息。
 *
 * <p>包括焦点应用状态和剪贴板意图识别。</p>
 *
 * @author zsg
 * @since 2026-04-05
 */
@RestController
@RequestMapping("/api/context")
public class ContextController {

    private static final Logger log = LoggerFactory.getLogger(ContextController.class);

    private final ReminderFocusStateHolder focusStateHolder;

    public ContextController(ReminderFocusStateHolder focusStateHolder) {
        this.focusStateHolder = focusStateHolder;
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
     * 接收剪贴板意图识别结果。
     *
     * @param intent 剪贴板意图
     * @return 建议操作（如有），否则 204 No Content
     */
    @PostMapping("/clipboard-intent")
    public ResponseEntity<?> reportClipboardIntent(@RequestBody ReminderClipboardIntent intent) {
        log.info("收到剪贴板意图: type={}, value={}", intent.intentType(), intent.value());

        String suggestion = generateSuggestion(intent);
        if (suggestion != null) {
            return ResponseEntity.ok(Map.of("suggestion", suggestion));
        }
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
