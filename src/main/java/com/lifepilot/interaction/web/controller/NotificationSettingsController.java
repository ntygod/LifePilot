package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.model.NotificationSettingDto;
import com.lifepilot.interaction.web.model.UpdateNotificationSettingRequest;
import com.lifepilot.notification.NotificationRepository;
import com.lifepilot.notification.NotificationSettingRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 通知设置管理 REST Controller — 提供通知偏好查询与更新端点。
 *
 * @author zsg
 * @since 2026-03-13
 */
@RestController
@RequestMapping("/api/notification-settings")
public class NotificationSettingsController {

    private static final Logger log = LoggerFactory.getLogger(NotificationSettingsController.class);

    private final NotificationRepository notificationRepository;

    public NotificationSettingsController(NotificationRepository notificationRepository) {
        this.notificationRepository = notificationRepository;
    }

    /**
     * 查询用户所有通知设置。
     *
     * @param userId 用户 ID
     * @return 通知设置列表
     */
    @GetMapping
    public ResponseEntity<List<NotificationSettingDto>> getSettings(@RequestParam String userId) {
        var settings = notificationRepository.findSettingsByUserId(userId);
        var dtos = settings.stream().map(NotificationSettingDto::from).toList();
        log.debug("查询通知设置: userId={}, count={}", userId, dtos.size());
        return ResponseEntity.ok(dtos);
    }

    /**
     * 更新指定通知类型的设置（UPSERT 行为）。
     *
     * @param typeId  通知类型标识
     * @param request 更新请求体
     * @return 更新后的通知设置
     */
    @PutMapping("/{typeId}")
    public ResponseEntity<NotificationSettingDto> updateSetting(
            @PathVariable String typeId,
            @RequestBody UpdateNotificationSettingRequest request) {

        var now = Instant.now();

        // 查询已有设置，决定使用已有 ID 还是生成新 ID
        var existing = notificationRepository.findSettingByUserIdAndTypeId(request.userId(), typeId);
        String id = existing.map(NotificationSettingRecord::id).orElseGet(() -> UUID.randomUUID().toString());
        var createdAt = existing.map(NotificationSettingRecord::createdAt).orElse(now);

        var record = new NotificationSettingRecord(
                id,
                request.userId(),
                typeId,
                request.enabled(),
                request.channels(),
                request.minUrgency(),
                createdAt,
                now
        );

        notificationRepository.saveSetting(record);
        log.info("更新通知设置: userId={}, typeId={}, enabled={}", request.userId(), typeId, request.enabled());

        return ResponseEntity.ok(NotificationSettingDto.from(record));
    }
}
