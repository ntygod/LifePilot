package com.lifepilot.agent.task.reminder.tracking;

import com.lifepilot.notification.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 外部信号接入 Controller — 短信转发 Webhook 和追踪查询。
 *
 * @author zsg
 * @since 2026-04-05
 */
@RestController
@RequestMapping("/api/signals")
@ConditionalOnBean(TrackingRegistry.class)
public class TrackingSignalController {

    private static final Logger log = LoggerFactory.getLogger(TrackingSignalController.class);

    private final SmsSignalExtractor smsSignalExtractor;
    private final TrackingRegistry trackingRegistry;
    private final NotificationProperties notificationProperties;

    public TrackingSignalController(SmsSignalExtractor smsSignalExtractor,
                                    TrackingRegistry trackingRegistry,
                                    NotificationProperties notificationProperties) {
        this.smsSignalExtractor = smsSignalExtractor;
        this.trackingRegistry = trackingRegistry;
        this.notificationProperties = notificationProperties;
    }

    /**
     * 短信转发 Webhook — 接收手机端 Tasker/快捷指令转发来的短信。
     */
    @PostMapping("/sms")
    public ResponseEntity<Map<String, Object>> receiveSms(@RequestBody SmsSignalRequest request) {
        String userId = notificationProperties.getDefaultUserId();
        var result = smsSignalExtractor.extract(request, userId);
        if (result.isEmpty()) {
            log.debug("短信信号未识别: sender={}", request.sender());
            return ResponseEntity.ok(Map.of("recognized", false));
        }
        var extraction = result.get();
        log.info("短信信号已接收: type={}, key={}", extraction.type(), extraction.trackingKey());
        return ResponseEntity.ok(Map.of(
                "recognized", true,
                "type", extraction.type().name(),
                "trackingKey", extraction.trackingKey(),
                "title", extraction.title(),
                "entryId", extraction.entryId()
        ));
    }

    /**
     * 查询用户的活跃追踪条目。
     */
    @GetMapping("/tracking")
    public ResponseEntity<List<TrackingEntry>> listTracking(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String type) {
        String effectiveUserId = userId != null ? userId : notificationProperties.getDefaultUserId();
        List<TrackingEntry> entries;
        if (type != null && !type.isBlank()) {
            try {
                entries = trackingRegistry.findActiveByType(TrackingType.valueOf(type.toUpperCase()));
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        } else {
            entries = trackingRegistry.findActiveByUserId(effectiveUserId);
        }
        return ResponseEntity.ok(entries);
    }
}
