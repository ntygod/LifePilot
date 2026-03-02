package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.model.ErrorResponse;
import com.lifepilot.observability.trace.OverviewStats;
import com.lifepilot.observability.trace.ToolUsageStats;
import com.lifepilot.observability.trace.TokenConsumptionStats;
import com.lifepilot.observability.trace.TraceNotFoundException;
import com.lifepilot.observability.trace.TraceQuery;
import com.lifepilot.observability.trace.TraceQueryParams;
import com.lifepilot.observability.evaluation.EvaluationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;

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

    /**
     * 获取概览统计。
     *
     * @param window 时间窗口（24h / 7d / 30d），默认 7d
     * @return 概览统计数据
     */
    @GetMapping("/stats/overview")
    public ResponseEntity<OverviewStats> getOverviewStats(
            @RequestParam(defaultValue = "7d") String window) {
        if (!"24h".equals(window) && !"7d".equals(window) && !"30d".equals(window)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    new OverviewStats(
                            0,
                            0,
                            0,
                            0.0,
                            0.0,
                            0.0,
                            0L,
                            0.0
                    )
            );
        }

        var stats = traceQuery.getOverviewStats(window);
        return ResponseEntity.ok(stats);
    }

    /**
     * 获取工具使用统计。
     *
     * @return 工具使用统计列表
     */
    @GetMapping("/stats/tools")
    public ResponseEntity<List<ToolUsageStats>> getToolUsageStats() {
        var stats = traceQuery.getToolUsageStats();
        return ResponseEntity.ok(stats);
    }

    /**
     * 关键词搜索轨迹。
     *
     * @param keyword 搜索关键词（必需）
     * @param limit   最大返回数量，默认 20
     * @return 匹配的轨迹摘要列表或错误响应
     */
    @GetMapping("/search")
    public ResponseEntity<?> searchTraces(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "20") int limit) {
        if (keyword == null || keyword.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                    new ErrorResponse(400, "搜索关键词不能为空", Instant.now()));
        }

        var results = traceQuery.searchByKeyword(keyword, limit);
        return ResponseEntity.ok(results);
    }

    /**
     * 导出轨迹为 JSON 文件。
     *
     * @param id 轨迹 ID
     * @return JSON 字符串，包含下载响应头或错误响应
     */
    @GetMapping("/{id}/export")
    public ResponseEntity<?> exportTrace(@PathVariable String id) {
        try {
            String json = traceQuery.exportAsJson(id);
            String filename = "trace-" + id + ".json";
            return ResponseEntity.ok()
                    .header("Content-Type", "application/json")
                    .header("Content-Disposition", "attachment; filename=" + filename)
                    .body(json);
        } catch (TraceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse(404, e.getMessage(), Instant.now()));
        }
    }

    /**
     * 获取 Token 消耗统计。
     *
     * @param start 起始时间（ISO 8601），默认 7 天前
     * @param end   结束时间（ISO 8601），默认当前时间
     * @return Token 消耗统计
     */
    @GetMapping("/stats/tokens")
    public ResponseEntity<?> getTokenStats(
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        Instant now = Instant.now();
        Instant startTime = now.minus(Duration.ofDays(7));
        Instant endTime = now;

        try {
            if (start != null && !start.isBlank()) {
                startTime = Instant.parse(start);
            }
            if (end != null && !end.isBlank()) {
                endTime = Instant.parse(end);
            }
        } catch (DateTimeParseException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ErrorResponse(400, "时间格式非法，必须为 ISO 8601 格式", Instant.now()));
        }

        TokenConsumptionStats stats = traceQuery.getTokenStats(startTime, endTime);
        return ResponseEntity.ok(stats);
    }

    /**
     * 获取轨迹评估结果。
     *
     * @param id 轨迹 ID
     * @return 评估结果，不存在时返回 404
     */
    @GetMapping("/{id}/evaluation")
    public ResponseEntity<?> getEvaluation(@PathVariable String id) {
        try {
            EvaluationResult result = traceQuery.getEvaluation(id);
            return ResponseEntity.ok(result);
        } catch (TraceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse(404, e.getMessage(), Instant.now()));
        }
    }
}
