package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.model.ErrorResponse;
import com.lifepilot.observability.trace.TraceNotFoundException;
import com.lifepilot.observability.trace.TraceQuery;
import com.lifepilot.observability.trace.TraceQueryParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 轨迹回放 REST Controller。
 *
 * <p>提供 Agent 执行轨迹的分页查询、详情查看和步骤回放端点。</p>
 *
 * <p>仅在 {@link TraceQuery} Bean 存在时注册（需要启用 trace 追踪功能）。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
@RestController
@RequestMapping("/api/traces")
@ConditionalOnBean(TraceQuery.class)
public class TraceController {

    private static final Logger log = LoggerFactory.getLogger(TraceController.class);

    private final TraceQuery traceQuery;

    public TraceController(TraceQuery traceQuery) {
        this.traceQuery = traceQuery;
    }

    /**
     * 分页查询轨迹列表，按创建时间倒序。
     *
     * @param page 页码（从 0 开始，默认 0）
     * @param size 每页大小（默认 20）
     * @return 轨迹摘要列表
     */
    @GetMapping
    public ResponseEntity<?> listTraces(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.debug("查询轨迹列表: page={}, size={}", page, size);
        var params = TraceQueryParams.builder()
                .limit(size)
                .offset(page * size)
                .build();
        return ResponseEntity.ok(traceQuery.query(params));
    }

    /**
     * 查询单条轨迹详情（含完整步骤列表）。
     *
     * @param id 轨迹 ID
     * @return 轨迹详情，不存在返回 404
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getTrace(@PathVariable String id) {
        try {
            return ResponseEntity.ok(traceQuery.getDetail(id));
        } catch (TraceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    new ErrorResponse(404, e.getMessage(), Instant.now()));
        }
    }

    /**
     * 回放轨迹步骤，按 stepIndex 升序。
     *
     * @param id 轨迹 ID
     * @return 回放步骤列表
     */
    @GetMapping("/{id}/steps")
    public ResponseEntity<?> getTraceSteps(@PathVariable String id) {
        try {
            return ResponseEntity.ok(traceQuery.replay(id));
        } catch (TraceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    new ErrorResponse(404, e.getMessage(), Instant.now()));
        }
    }
}
