package com.lifepilot.interaction.web.controller;

import com.lifepilot.agent.task.proactive.QueuedActionRepository;
import com.lifepilot.interaction.web.model.ApiResponse;
import com.lifepilot.interaction.web.model.QueuedActionDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 主动引擎队列 REST Controller — 提供排队动作查询、标记已展示、删除等端点。
 *
 * @author zsg
 * @since 2026-04-15
 */
@RestController
@RequestMapping("/api/proactive")
public class ProactiveController {

    private static final Logger log = LoggerFactory.getLogger(ProactiveController.class);

    private final QueuedActionRepository queuedActionRepository;

    public ProactiveController(QueuedActionRepository queuedActionRepository) {
        this.queuedActionRepository = queuedActionRepository;
    }

    /**
     * 查询用户未展示的排队动作列表。
     *
     * @param userId 用户 ID（默认 "default"）
     * @param limit  返回上限（默认 20，最大 50）
     * @return 排队动作列表
     */
    @GetMapping("/queue")
    public ApiResponse<List<QueuedActionDto>> listQueue(
            @RequestParam(defaultValue = "default") String userId,
            @RequestParam(defaultValue = "20") int limit) {

        int effectiveLimit = Math.clamp(limit, 1, 50);
        var records = queuedActionRepository.findPendingByUserId(userId, effectiveLimit);
        var dtos = records.stream().map(QueuedActionDto::from).toList();
        log.debug("查询排队动作: userId={}, limit={}, count={}", userId, effectiveLimit, dtos.size());
        return ApiResponse.ok(dtos);
    }

    /**
     * 标记排队动作为已展示。
     *
     * @param id 排队动作 ID
     * @return 空响应
     */
    @PutMapping("/queue/{id}/shown")
    public ApiResponse<Void> markShown(@PathVariable String id) {
        queuedActionRepository.markShown(id);
        log.info("标记排队动作已展示: id={}", id);
        return ApiResponse.ok();
    }

    /**
     * 删除排队动作。
     *
     * @param id 排队动作 ID
     * @return 空响应
     */
    @DeleteMapping("/queue/{id}")
    public ApiResponse<Void> deleteAction(@PathVariable String id) {
        int deleted = queuedActionRepository.deleteById(id);
        log.info("删除排队动作: id={}, affected={}", id, deleted);
        return ApiResponse.ok();
    }
}
