package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.model.ApiResponse;
import com.lifepilot.interaction.web.model.ErrorResponse;
import com.lifepilot.interaction.web.model.PageResult;
import com.lifepilot.interaction.web.model.TraceDetailDto;
import com.lifepilot.interaction.web.model.TraceItemDto;
import com.lifepilot.interaction.web.model.TraceStepDto;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.observability.config.ObservabilityProperties;
import com.lifepilot.observability.trace.OverviewStats;
import com.lifepilot.observability.trace.ToolUsageStats;
import com.lifepilot.observability.trace.TokenConsumptionStats;
import com.lifepilot.observability.trace.TraceNotFoundException;
import com.lifepilot.observability.trace.TraceQuery;
import com.lifepilot.observability.trace.TraceQueryParams;
import com.lifepilot.observability.trace.TraceRecorder;
import com.lifepilot.observability.trace.TraceStep;
import com.lifepilot.observability.trace.TraceStepSerializer;
import com.lifepilot.observability.trace.LlmCallStep;
import com.lifepilot.observability.trace.ToolCallStep;
import com.lifepilot.observability.trace.StateTransitionStep;
import com.lifepilot.observability.trace.GuardrailStep;
import com.lifepilot.observability.trace.EvaluationStep;
import com.lifepilot.observability.evaluation.EvaluationResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

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
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
public class TraceController {

    private static final Logger log = LoggerFactory.getLogger(TraceController.class);

    private final TraceQuery traceQuery;
    private final TraceRecorder traceRecorder;
    private final TraceStepSerializer stepSerializer;
    private final ObservabilityProperties properties;

    public TraceController(TraceQuery traceQuery,
                           TraceRecorder traceRecorder,
                           TraceStepSerializer stepSerializer,
                           ObservabilityProperties properties) {
        this.traceQuery = traceQuery;
        this.traceRecorder = traceRecorder;
        this.stepSerializer = stepSerializer;
        this.properties = properties;
    }

    /**
     * 分页查询轨迹列表，按创建时间倒序。
     *
     * @param page 页码（从 0 开始，默认 0）
     * @param size 每页大小（默认 20）
     * @return 轨迹摘要列表
     */
    @GetMapping
    public ApiResponse<PageResult<TraceItemDto>> listTraces(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.debug("查询轨迹列表: page={}, size={}", page, size);
        var params = TraceQueryParams.builder()
                .limit(size)
                .offset(page * size)
                .build();
        var summaries = traceQuery.query(params);
        var items = summaries.stream()
                .map(s -> new TraceItemDto(
                        s.traceId(),
                        s.sessionId(),
                        s.goal(),
                        s.success(),
                        s.totalSteps(),
                        s.totalTokens(),
                        s.totalDurationMs(),
                        s.startTime().toString()
                ))
                .toList();
        long total = traceQuery.countAll();
        return ApiResponse.ok(new PageResult<>(items, page, size, total));
    }

    /**
     * 查询单条轨迹详情（含完整步骤列表）。
     *
     * @param id 轨迹 ID
     * @return 轨迹详情，不存在返回 404
     */
    @GetMapping("/{id}")
    public ApiResponse<TraceDetailDto> getTrace(@PathVariable String id) {
        var detail = traceQuery.getDetail(id);
        String modelId = resolveModelId(detail.steps());
        var dto = new TraceDetailDto(
                detail.traceId(),
                detail.sessionId(),
                detail.goal(),
                detail.success(),
                detail.totalSteps(),
                detail.totalTokens(),
                detail.totalDurationMs(),
                detail.startTime().toString(),
                detail.finalOutput(),
                detail.errorMessage(),
                detail.terminationReason(),
                modelId
        );
        return ApiResponse.ok(dto);
    }

    /**
     * 回放轨迹步骤，按 stepIndex 升序。
     *
     * @param id 轨迹 ID
     * @return 回放步骤列表
     */
    @GetMapping("/{id}/steps")
    public ApiResponse<List<TraceStepDto>> getTraceSteps(@PathVariable String id) {
        boolean includeContent = properties.getTrace().isRecordPrompts();
        List<TraceStep> steps = traceQuery.getSteps(id);
        var dtos = steps.stream()
                .map(step -> toStepDto(id, step, includeContent))
                .toList();
        return ApiResponse.ok(dtos);
    }

    /**
     * SSE：实时订阅指定 traceId 的步骤事件。
     *
     * <p>用于 Web UI “实时轨迹”视图。在默认配置下不会推送 prompt/output 等敏感内容。</p>
     */
    @GetMapping(value = "/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTrace(@PathVariable String id) {
        var emitter = new SseEmitter(0L); // 不超时，由客户端断开/服务端完成
        boolean includeContent = properties.getTrace().isRecordPrompts();

        // 订阅 Trace 步骤与结束事件（可取消订阅，避免 listener 泄漏）
        AutoCloseable stepSub = traceRecorder.onStep(ev -> {
            if (!id.equals(ev.traceId())) return;
            try {
                TraceStepDto dto = toStepDto(id, ev.step(), includeContent);
                emitter.send(SseEmitter.event()
                        .name(SseEventType.TRACE_STEP)
                        .data(java.util.Objects.requireNonNull(dto)));
            } catch (Exception sendError) {
                try { emitter.completeWithError(sendError); } catch (Exception e) { log.debug("关闭SSE连接失败", e); }
            }
        });

        AutoCloseable endSub = traceRecorder.onTraceEnd(record -> {
            if (!id.equals(record.traceId())) return;
            try {
                emitter.send(SseEmitter.event().name(SseEventType.TRACE_END).data(
                        java.util.Objects.requireNonNull((Object) Map.of(
                        "traceId", record.traceId(),
                        "success", record.success(),
                        "timestamp", Instant.now().toEpochMilli()
                        ))
                ));
                emitter.complete();
            } catch (Exception sendError) {
                try { emitter.completeWithError(sendError); } catch (Exception e) { log.debug("关闭SSE连接失败", e); }
            }
        });

        Runnable cleanup = () -> closeQuietly(stepSub, endSub);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ex -> cleanup.run());

        // 立即发送 start 事件，便于前端进入“实时中”状态
        try {
            emitter.send(SseEmitter.event().name(SseEventType.TRACE_START).data(
                    java.util.Objects.requireNonNull((Object) Map.of(
                    "traceId", id,
                    "recordPrompts", includeContent,
                    "timestamp", Instant.now().toEpochMilli()
                    ))
            ));
        } catch (Exception e) {
            cleanup.run();
            emitter.completeWithError(e);
        }

        return emitter;
    }

    /**
     * 获取概览统计。
     *
     * @param window 时间窗口（24h / 7d / 30d），默认 7d
     * @return 概览统计数据
     */
    @GetMapping("/stats/overview")
    public ApiResponse<OverviewStats> getOverviewStats(
            @RequestParam(defaultValue = "7d") String window) {
        if (!"24h".equals(window) && !"7d".equals(window) && !"30d".equals(window)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的时间窗口: " + window);
        }

        var stats = traceQuery.getOverviewStats(window);
        return ApiResponse.ok(stats);
    }

    /**
     * 获取工具使用统计。
     *
     * @return 工具使用统计列表
     */
    @GetMapping("/stats/tools")
    public ApiResponse<List<ToolUsageStats>> getToolUsageStats() {
        var stats = traceQuery.getToolUsageStats();
        return ApiResponse.ok(stats);
    }

    /**
     * 关键词搜索轨迹。
     *
     * @param keyword 搜索关键词（必需）
     * @param limit   最大返回数量，默认 20
     * @return 匹配的轨迹摘要列表或错误响应
     */
    @GetMapping("/search")
    public ApiResponse<List<TraceItemDto>> searchTraces(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "20") int limit) {
        if (keyword == null || keyword.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "搜索关键词不能为空");
        }

        var results = traceQuery.searchByKeyword(keyword, limit);
        var items = results.stream()
                .map(s -> new TraceItemDto(
                        s.traceId(),
                        s.sessionId(),
                        s.goal(),
                        s.success(),
                        s.totalSteps(),
                        s.totalTokens(),
                        s.totalDurationMs(),
                        s.startTime().toString()
                ))
                .toList();
        return ApiResponse.ok(items);
    }

    /**
     * 导出轨迹为 JSON 文件。
     *
     * @param id 轨迹 ID
     * @return JSON 字符串，包含下载响应头或错误响应
     */
    @GetMapping("/{id}/export")
    public ResponseEntity<String> exportTrace(@PathVariable String id) {
        String json = traceQuery.exportAsJson(id);
        String filename = "trace-" + id + ".json";
        return ResponseEntity.ok()
                .header("Content-Type", "application/json")
                .header("Content-Disposition", "attachment; filename=" + filename)
                .body(json);
    }

    /**
     * 获取 Token 消耗统计。
     *
     * @param start 起始时间（ISO 8601），默认 7 天前
     * @param end   结束时间（ISO 8601），默认当前时间
     * @return Token 消耗统计
     */
    @GetMapping("/stats/tokens")
    public ApiResponse<TokenConsumptionStats> getTokenStats(
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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "时间格式非法，必须为 ISO 8601 格式");
        }

        TokenConsumptionStats stats = traceQuery.getTokenStats(startTime, endTime);
        return ApiResponse.ok(stats);
    }

    /**
     * 获取轨迹评估结果。
     *
     * @param id 轨迹 ID
     * @return 评估结果，不存在时返回 404
     */
    @GetMapping("/{id}/evaluation")
    public ApiResponse<EvaluationResult> getEvaluation(@PathVariable String id) {
        EvaluationResult result = traceQuery.getEvaluation(id);
        return ApiResponse.ok(result);
    }

    // ────────────────────────────────────────────────
    // DTO 映射辅助方法
    // ────────────────────────────────────────────────

    private String resolveModelId(List<TraceStep> steps) {
        if (steps == null || steps.isEmpty()) return null;
        for (int i = steps.size() - 1; i >= 0; i--) {
            var s = steps.get(i);
            if (s instanceof LlmCallStep llm) {
                return llm.modelId();
            }
        }
        return null;
    }

    private TraceStepDto toStepDto(String traceId, TraceStep step, boolean includeContent) {
        String stepType = step.typeName();
        String id = traceId + ":" + stepType + ":" + step.stepIndex();
        String createdAt = step.timestamp().toString();

        // 默认字段
        String phaseBefore = "";
        String phaseAfter = "";
        String actionType = stepType;
        String actionJson = null;
        String toolId = null;
        String toolInputJson = null;
        String toolOutput = null;
        boolean success = true;
        boolean blocked = false;
        String blockReason = null;
        int tokensUsed = 0;
        long latencyMs = step.duration().toMillis();

        switch (step) {
            case StateTransitionStep st -> {
                phaseBefore = st.phaseBefore();
                phaseAfter = st.phaseAfter();
                actionType = st.actionType();
                actionJson = includeContent && st.actionSummary() != null
                        ? st.actionSummary()
                        : null;
                success = true;
                blocked = false;
                tokensUsed = 0;
                latencyMs = st.duration().toMillis();
            }
            case LlmCallStep llm -> {
                actionType = "LLM_CALL";
                tokensUsed = llm.inputTokens() + llm.outputTokens();
                latencyMs = llm.latency().toMillis();
                // actionJson 用于 UI 展示结构化信息
                actionJson = safeSerialize(llm, includeContent);
            }
            case ToolCallStep tool -> {
                actionType = tool.toolAction() != null ? tool.toolAction() : "TOOL_CALL";
                toolId = tool.toolId();
                toolInputJson = includeContent ? tool.inputJson() : null;
                toolOutput = includeContent ? tool.outputJson() : null;
                success = tool.success();
                blocked = false;
                tokensUsed = 0;
                latencyMs = tool.duration().toMillis();
                actionJson = safeSerialize(tool, includeContent);
            }
            case GuardrailStep g -> {
                actionType = "GUARDRAIL";
                success = g.passed();
                blocked = !g.passed();
                blockReason = includeContent ? g.reason() : null;
                tokensUsed = 0;
                latencyMs = g.duration().toMillis();
                actionJson = safeSerialize(g, includeContent);
            }
            case EvaluationStep e -> {
                actionType = "EVALUATION";
                success = true;
                blocked = false;
                tokensUsed = 0;
                latencyMs = e.duration().toMillis();
                actionJson = safeSerialize(e, includeContent);
            }
            default -> {
                actionJson = safeSerialize(step, includeContent);
            }
        }

        return new TraceStepDto(
                id,
                step.stepIndex(),
                phaseBefore,
                phaseAfter,
                actionType,
                actionJson,
                toolId,
                toolInputJson,
                toolOutput,
                success,
                blocked,
                blockReason,
                tokensUsed,
                latencyMs,
                createdAt
        );
    }

    private String safeSerialize(TraceStep step, boolean includeContent) {
        if (!includeContent && (step instanceof ToolCallStep
                || step instanceof StateTransitionStep
                || step instanceof GuardrailStep)) {
            // 默认不暴露潜在敏感字段（仅返回类型与索引等结构信息）
            return "{\"type\":\"" + step.typeName() + "\",\"stepIndex\":" + step.stepIndex() + "}";
        }
        try {
            return stepSerializer.serialize(step);
        } catch (Exception e) {
            return "{\"type\":\"" + step.typeName() + "\",\"stepIndex\":" + step.stepIndex() + "}";
        }
    }

    private void closeQuietly(AutoCloseable... closables) {
        for (AutoCloseable c : closables) {
            if (c == null) continue;
            try { c.close(); } catch (Exception e) { log.debug("关闭资源失败", e); }
        }
    }
}
