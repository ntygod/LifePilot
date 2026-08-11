package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.model.ApiResponse;
import com.lifepilot.interaction.web.model.NotificationDto;
import com.lifepilot.interaction.web.model.PageResult;
import com.lifepilot.notification.NotificationRecord;
import com.lifepilot.notification.NotificationRepository;
import com.lifepilot.notification.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * 通知管理 REST Controller — 提供通知历史查询、已读标记等端点。
 *
 * <p>原先还提供主动提醒反馈端点（信任升级 + 偏好学习回流）；
 * 旧提醒决策栈移除后该闭环一并下线，主动性改由 InitiativeEngine 以对话形式表达，
 * 不再走通知反馈打分。</p>
 *
 * @author zsg
 * @since 2026-03-13
 */
@RestController
@RequestMapping("/api/notifications")
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
public class NotificationController {

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    private final NotificationRepository notificationRepository;
    private final NotificationProperties notificationProperties;

    public NotificationController(NotificationRepository notificationRepository,
                                  NotificationProperties notificationProperties) {
        this.notificationRepository = notificationRepository;
        this.notificationProperties = notificationProperties;
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
        var dtos = records.stream()
                .map(NotificationDto::from)
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
        return ApiResponse.ok(NotificationDto.from(updated));
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
}
