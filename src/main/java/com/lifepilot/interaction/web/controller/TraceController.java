package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.model.ErrorResponse;
import com.lifepilot.interaction.web.service.TraceQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

/**
 * 轨迹回放 REST Controller。
 *
 * <p>提供 Agent 执行轨迹的分页查询、详情查看和步骤列表端点。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
@RestController
@RequestMapping("/api/traces")
public class TraceController {

    private static final Logger log = LoggerFactory.getLogger(TraceController.class);

    private final TraceQueryService traceQueryService;

    public TraceController(TraceQueryService traceQueryService) {
        this.traceQueryService = traceQueryService;
    }

    /**
     * 分页查询轨迹列表，按创建时间倒序。
     *
     * @param page 页码（从 0 开始，默认 0）
     * @param size 每页大小（默认 20）
     * @return 分页轨迹列表
     */
    @GetMapping
    public ResponseEntity<?> listTraces(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.debug("查询轨迹列表: page={}, size={}", page, size);
        return ResponseEntity.ok(traceQueryService.listTraces(page, size));
    }

    /**
     * 查询单条轨迹详情。
     *
     * @param id 轨迹 ID
     * @return 轨迹详情，不存在返回 404
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getTrace(@PathVariable String id) {
        return traceQueryService.getTrace(id)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        new ErrorResponse(404, "轨迹不存在: id=" + id, Instant.now())));
    }

    /**
     * 查询轨迹的所有步骤，按 stepIndex 升序。
     *
     * @param id 轨迹 ID
     * @return 步骤列表
     */
    @GetMapping("/{id}/steps")
    public ResponseEntity<?> getTraceSteps(@PathVariable String id) {
        return ResponseEntity.ok(traceQueryService.getTraceSteps(id));
    }
}
