package com.lifepilot.interaction.web.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.task.proactive.DeliveryLevel;
import com.lifepilot.agent.task.proactive.DeliveryResult;
import com.lifepilot.agent.task.proactive.ProactiveAction;
import com.lifepilot.agent.task.proactive.ProactiveCandidate;
import com.lifepilot.agent.task.proactive.TrustUpgradeService;
import com.lifepilot.agent.task.proactive.preference.PreferenceLearner;
import com.lifepilot.agent.task.reminder.ReminderFeedbackRecord;
import com.lifepilot.agent.task.reminder.ReminderFeedbackRepository;
import com.lifepilot.agent.task.reminder.ReminderFeedbackType;
import com.lifepilot.agent.task.reminder.ReminderNotificationFeedbackView;
import com.lifepilot.agent.task.reminder.ReminderTopicPreferenceRecord;
import com.lifepilot.interaction.web.model.ApiResponse;
import com.lifepilot.interaction.web.model.NotificationDto;
import com.lifepilot.interaction.web.model.PageResult;
import com.lifepilot.interaction.web.model.ReminderFeedbackRequest;
import com.lifepilot.notification.NotificationRecord;
import com.lifepilot.notification.NotificationRepository;
import com.lifepilot.notification.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 通知管理 REST Controller — 提供通知历史查询、已读标记等端点。
 *
 * @author zsg
 * @since 2026-03-13
 */
@RestController
@RequestMapping("/api/notifications")
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
public class NotificationController {

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REMINDER_TYPE = "proactive_reminder";

    private final NotificationRepository notificationRepository;
    private final NotificationProperties notificationProperties;
    @Nullable
    private final ReminderFeedbackRepository reminderFeedbackRepository;
    @Nullable
    private final TrustUpgradeService trustUpgradeService;
    @Nullable
    private final PreferenceLearner preferenceLearner;

    public NotificationController(NotificationRepository notificationRepository,
                                  NotificationProperties notificationProperties) {
        this(notificationRepository, notificationProperties, null, null, null);
    }

    @Autowired
    public NotificationController(NotificationRepository notificationRepository,
                                  NotificationProperties notificationProperties,
                                  @Nullable ReminderFeedbackRepository reminderFeedbackRepository,
                                  @Nullable TrustUpgradeService trustUpgradeService,
                                  @Nullable PreferenceLearner preferenceLearner) {
        this.notificationRepository = notificationRepository;
        this.notificationProperties = notificationProperties;
        this.reminderFeedbackRepository = reminderFeedbackRepository;
        this.trustUpgradeService = trustUpgradeService;
        this.preferenceLearner = preferenceLearner;
    }

    /**
     * 分页查询通知历史。
     *
     * @param userId  用户 ID（必填）
     * @param page    页码（从 0 开始，默认 0）
     * @param size    每页大小（默认取配置 historyPageSize，上限 maxHistoryPageSize）
     * @return 分页通知列表
     */
    @GetMapping
    public ApiResponse<PageResult<NotificationDto>> listNotifications(
            @RequestParam String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {

        int effectiveSize = size != null ? size : notificationProperties.getHistoryPageSize();
        effectiveSize = Math.clamp(effectiveSize, 1, notificationProperties.getMaxHistoryPageSize());

        List<NotificationRecord> records = notificationRepository.findByUserId(userId, page, effectiveSize);
        long total = notificationRepository.countByUserId(userId);
        Map<String, ReminderNotificationFeedbackView> feedbackViews = loadFeedbackViews(records);
        var dtos = records.stream()
                .map(record -> NotificationDto.from(record, feedbackViews.get(record.id())))
                .toList();
        log.debug("查询通知历史: userId={}, page={}, size={}, total={}",
                userId, page, effectiveSize, total);

        return ApiResponse.ok(new PageResult<>(dtos, page, effectiveSize, total));
    }

    /**
     * 标记单条通知为已读。
     *
     * @param id 通知 ID
     * @return 更新后的通知记录
     */
    @PutMapping("/{id}/read")
    public ApiResponse<NotificationDto> markAsRead(@PathVariable String id) {
        var record = notificationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "通知不存在: id=" + id));

        notificationRepository.markAsRead(id);
        log.info("标记通知已读: id={}", id);

        // 重新查询获取更新后的记录
        var updated = notificationRepository.findById(id).orElse(record);
        return ApiResponse.ok(NotificationDto.from(updated, loadFeedbackView(id)));
    }

    /**
     * 标记用户所有未读通知为已读。
     *
     * @param userId 用户 ID
     * @return 更新的记录数
     */
    @PutMapping("/read-all")
    public ApiResponse<Map<String, Integer>> markAllAsRead(@RequestParam String userId) {
        int count = notificationRepository.markAllAsRead(userId);
        log.info("批量标记通知已读: userId={}, count={}", userId, count);
        return ApiResponse.ok(Map.of("updatedCount", count));
    }

    /**
     * 提交主动提醒反馈。
     *
     * @param id      通知 ID
     * @param request 反馈请求
     * @return 更新后的通知记录
     */
    @PostMapping("/{id}/feedback")
    public ApiResponse<NotificationDto> submitReminderFeedback(@PathVariable String id,
                                                                  @RequestBody ReminderFeedbackRequest request) {
        if (reminderFeedbackRepository == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "主动提醒反馈未启用");
        }
        if (request.feedbackType() == null || request.feedbackType().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "反馈类型不能为空");
        }

        NotificationRecord record = notificationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "通知不存在: id=" + id));
        if (!REMINDER_TYPE.equals(record.typeId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "仅主动提醒支持反馈");
        }

        ReminderFeedbackType feedbackType;
        try {
            feedbackType = ReminderFeedbackType.parse(request.feedbackType());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "反馈类型无效: " + request.feedbackType(), e);
        }

        String topicKey = extractTopicKey(record.metadataJson())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "通知缺少提醒主题"));
        Instant now = Instant.now();
        reminderFeedbackRepository.saveFeedback(new ReminderFeedbackRecord(
                UUID.randomUUID().toString(),
                record.id(),
                record.userId(),
                topicKey,
                feedbackType,
                request.comment(),
                now,
                now
        ));
        if (request.muteTopic() != null) {
            reminderFeedbackRepository.upsertTopicPreference(new ReminderTopicPreferenceRecord(
                    record.userId(),
                    topicKey,
                    request.muteTopic(),
                    Boolean.TRUE.equals(request.muteTopic()) ? now : null,
                    now
            ));
        }
        notificationRepository.markAsRead(id);

        // ── 反馈闭环：通知引擎的信任升级和偏好学习 ──
        dispatchFeedbackToEngine(record, feedbackType);

        NotificationRecord updated = notificationRepository.findById(id).orElse(record);
        ReminderNotificationFeedbackView feedbackView = reminderFeedbackRepository.findFeedbackViewByNotificationId(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "主动提醒反馈保存后未能读取"));
        log.info("提交主动提醒反馈: notificationId={}, topicKey={}, feedbackType={}, muteTopic={}",
                id, topicKey, feedbackType, request.muteTopic());
        return ApiResponse.ok(NotificationDto.from(updated, feedbackView));
    }

    /**
     * 反馈闭环 — 将用户反馈回流到信任升级和偏好学习。
     *
     * <p>ACTED/SNOOZED → 正反馈 → 信任升级 + 偏好正向观察。
     * DISMISSED/NOT_RELEVANT → 负反馈 → 信任降级 + 偏好负向观察。</p>
     */
    private void dispatchFeedbackToEngine(NotificationRecord record, ReminderFeedbackType feedbackType) {
        boolean positive = feedbackType == ReminderFeedbackType.ACTED
                || feedbackType == ReminderFeedbackType.SNOOZED;
        String behaviorName = extractBehaviorName(record.metadataJson()).orElse("reminder");

        // 信任升级/降级
        if (trustUpgradeService != null) {
            try {
                if (positive) {
                    trustUpgradeService.recordPositiveFeedback(record.userId(), behaviorName);
                } else {
                    trustUpgradeService.recordNegativeFeedback(record.userId(), behaviorName);
                }
            } catch (Exception e) {
                log.debug("反馈闭环: 信任更新失败: {}", e.getMessage());
            }
        }

        // 偏好学习
        if (preferenceLearner != null) {
            try {
                var candidate = new ProactiveCandidate("feedback", behaviorName,
                        extractTopicKey(record.metadataJson()).orElse("unknown"),
                        "", 0.5f, "", null);
                var action = new ProactiveAction(candidate, "", DeliveryLevel.NOTIFY, null);
                var result = new DeliveryResult(record.id(), DeliveryLevel.NOTIFY, record.createdAt());
                preferenceLearner.learnFromDelivery(action, result, record.userId(), positive);
            } catch (Exception e) {
                log.debug("反馈闭环: 偏好学习失败: {}", e.getMessage());
            }
        }
    }

    private Optional<String> extractBehaviorName(@Nullable String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) return Optional.empty();
        try {
            Map<String, String> metadata = MAPPER.readValue(metadataJson, new TypeReference<>() {});
            return Optional.ofNullable(metadata.get("behaviorName"));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Map<String, ReminderNotificationFeedbackView> loadFeedbackViews(List<NotificationRecord> records) {
        if (reminderFeedbackRepository == null || records.isEmpty()) {
            return Map.of();
        }
        List<String> notificationIds = records.stream()
                .map(NotificationRecord::id)
                .toList();
        return reminderFeedbackRepository.findFeedbackViewsByNotificationIds(notificationIds);
    }

    @Nullable
    private ReminderNotificationFeedbackView loadFeedbackView(String notificationId) {
        if (reminderFeedbackRepository == null) {
            return null;
        }
        return reminderFeedbackRepository.findFeedbackViewByNotificationId(notificationId).orElse(null);
    }

    private Optional<String> extractTopicKey(@Nullable String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> metadata = MAPPER.readValue(metadataJson, new TypeReference<>() {});
            Object topicKey = metadata.get("topicKey");
            if (topicKey == null) {
                return Optional.empty();
            }
            String value = topicKey.toString().trim();
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        } catch (Exception e) {
            log.debug("解析通知元数据失败: notificationMetadata={}, error={}", metadataJson, e.getMessage());
            return Optional.empty();
        }
    }
}
