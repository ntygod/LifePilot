package com.lifepilot.observability.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lifepilot.observability.config.ObservabilityProperties;
import com.lifepilot.observability.evaluation.EvaluationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 轨迹查询服务 — 提供多维度查询、FTS5 全文搜索、回放、导出和统计功能。
 *
 * @author zsg
 * @since 2026-02-27
 */
public class TraceQuery {

    private static final Logger log = LoggerFactory.getLogger(TraceQuery.class);

    private final JdbcTemplate jdbcTemplate;
    private final TraceStepSerializer serializer;
    private final ObservabilityProperties properties;
    private final ObjectMapper exportMapper;

    public TraceQuery(JdbcTemplate jdbcTemplate,
                      TraceStepSerializer serializer,
                      ObservabilityProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.serializer = serializer;
        this.properties = properties;
        this.exportMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * 多维度查询轨迹摘要列表。
     *
     * @param params 查询参数
     * @return 轨迹摘要列表
     */
    public List<TraceSummary> query(TraceQueryParams params) {
        var sql = new StringBuilder("""
                SELECT trace_id, session_id, goal, start_time, end_time,
                       total_duration_ms, total_steps, total_tokens, input_tokens, output_tokens,
                       success, termination_reason, error_message
                FROM traces WHERE 1=1
                """);
        var args = new ArrayList<>();

        if (params.startTime() != null) {
            sql.append(" AND start_time >= ?");
            args.add(params.startTime().toString());
        }
        if (params.endTime() != null) {
            sql.append(" AND start_time <= ?");
            args.add(params.endTime().toString());
        }
        if (params.sessionId() != null) {
            sql.append(" AND session_id = ?");
            args.add(params.sessionId());
        }
        if (params.successOnly() != null) {
            sql.append(" AND success = ?");
            args.add(params.successOnly() ? 1 : 0);
        }
        if (params.minSteps() > 0) {
            sql.append(" AND total_steps >= ?");
            args.add(params.minSteps());
        }
        if (params.minTokens() > 0) {
            sql.append(" AND total_tokens >= ?");
            args.add(params.minTokens());
        }

        sql.append(" ORDER BY start_time DESC LIMIT ? OFFSET ?");
        args.add(params.limit() > 0 ? params.limit() : 20);
        args.add(params.offset());

        return jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new TraceSummary(
                rs.getString("trace_id"),
                rs.getString("session_id"),
                rs.getString("goal"),
                Instant.parse(rs.getString("start_time")),
                rs.getString("end_time") != null ? Instant.parse(rs.getString("end_time")) : null,
                rs.getLong("total_duration_ms"),
                rs.getInt("total_steps"),
                rs.getInt("total_tokens"),
                rs.getInt("input_tokens"),
                rs.getInt("output_tokens"),
                rs.getInt("success") == 1,
                rs.getString("termination_reason"),
                rs.getString("error_message")
        ), args.toArray());
    }

    /**
     * 使用 FTS5 全文搜索轨迹。
     *
     * @param keyword 搜索关键词
     * @param limit   最大返回数量
     * @return 匹配的轨迹摘要列表
     */
    public List<TraceSummary> searchByKeyword(String keyword, int limit) {
        return jdbcTemplate.query("""
                SELECT t.trace_id, t.session_id, t.goal, t.start_time, t.end_time,
                       t.total_duration_ms, t.total_steps, t.total_tokens, t.input_tokens, t.output_tokens,
                       t.success, t.termination_reason, t.error_message
                FROM traces t
                INNER JOIN traces_fts fts ON t.trace_id = fts.trace_id
                WHERE traces_fts MATCH ?
                ORDER BY rank
                LIMIT ?
                """, (rs, rowNum) -> new TraceSummary(
                rs.getString("trace_id"),
                rs.getString("session_id"),
                rs.getString("goal"),
                Instant.parse(rs.getString("start_time")),
                rs.getString("end_time") != null ? Instant.parse(rs.getString("end_time")) : null,
                rs.getLong("total_duration_ms"),
                rs.getInt("total_steps"),
                rs.getInt("total_tokens"),
                rs.getInt("input_tokens"),
                rs.getInt("output_tokens"),
                rs.getInt("success") == 1,
                rs.getString("termination_reason"),
                rs.getString("error_message")
        ), keyword, limit > 0 ? limit : 20);
    }

    /**
     * 获取轨迹详情（含完整步骤列表）。
     *
     * @param traceId 追踪 ID
     * @return 轨迹详情
     * @throws TraceNotFoundException 轨迹不存在时抛出
     */
    public TraceDetail getDetail(String traceId) {
        var traces = jdbcTemplate.query("""
                SELECT trace_id, session_id, goal, start_time, end_time,
                       total_duration_ms, total_steps, total_tokens, input_tokens, output_tokens,
                       success, termination_reason, final_output, error_message, metadata_json
                FROM traces WHERE trace_id = ?
                """, (rs, rowNum) -> new Object[]{
                rs.getString("trace_id"),
                rs.getString("session_id"),
                rs.getString("goal"),
                rs.getString("start_time"),
                rs.getString("end_time"),
                rs.getLong("total_duration_ms"),
                rs.getInt("total_steps"),
                rs.getInt("total_tokens"),
                rs.getInt("input_tokens"),
                rs.getInt("output_tokens"),
                rs.getInt("success"),
                rs.getString("termination_reason"),
                rs.getString("final_output"),
                rs.getString("error_message"),
                rs.getString("metadata_json")
        }, traceId);

        if (traces.isEmpty()) {
            throw new TraceNotFoundException("轨迹不存在: traceId=" + traceId);
        }

        var row = traces.getFirst();
        List<TraceStep> steps = loadSteps(traceId);

        return new TraceDetail(
                (String) row[0],   // traceId
                (String) row[1],   // sessionId
                (String) row[2],   // goal
                Instant.parse((String) row[3]),  // startTime
                row[4] != null ? Instant.parse((String) row[4]) : null,  // endTime
                (long) row[5],     // totalDurationMs
                (int) row[6],      // totalSteps
                (int) row[7],      // totalTokens
                (int) row[8],      // inputTokens
                (int) row[9],      // outputTokens
                (int) row[10] == 1, // success
                (String) row[11],  // terminationReason
                (String) row[12],  // finalOutput
                (String) row[13],  // errorMessage
                (String) row[14],  // metadataJson
                steps
        );
    }

    /**
     * 回放轨迹 — 将每个步骤转换为包含人类可读摘要的 ReplayStep。
     *
     * @param traceId 追踪 ID
     * @return 回放步骤列表
     * @throws TraceNotFoundException 轨迹不存在时抛出
     */
    public List<ReplayStep> replay(String traceId) {
        // 验证轨迹存在
        var count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM traces WHERE trace_id = ?", Integer.class, traceId);
        if (count == null || count == 0) {
            throw new TraceNotFoundException("轨迹不存在: traceId=" + traceId);
        }

        List<TraceStep> steps = loadSteps(traceId);
        return steps.stream()
                .map(step -> new ReplayStep(
                        step.stepIndex(),
                        step.typeName(),
                        step.timestamp(),
                        step.duration(),
                        generateSummary(step),
                        step
                ))
                .toList();
    }

    /**
     * 导出轨迹为 JSON 字符串。
     *
     * @param traceId 追踪 ID
     * @return JSON 字符串
     * @throws TraceNotFoundException 轨迹不存在时抛出
     * @throws TraceExportException   导出失败时抛出
     */
    public String exportAsJson(String traceId) {
        TraceDetail detail = getDetail(traceId);
        try {
            return exportMapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            throw new TraceExportException("轨迹导出失败: traceId=" + traceId, e);
        }
    }

    /**
     * 获取时间范围内的 Token 消耗统计。
     *
     * @param start 起始时间
     * @param end   结束时间
     * @return Token 消耗统计
     */
    public TokenConsumptionStats getTokenStats(Instant start, Instant end) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) AS trace_count,
                       COALESCE(SUM(total_tokens), 0) AS total_tokens,
                       COALESCE(SUM(input_tokens), 0) AS total_input_tokens,
                       COALESCE(SUM(output_tokens), 0) AS total_output_tokens,
                       COALESCE(AVG(total_tokens), 0) AS avg_tokens,
                       COALESCE(MAX(total_tokens), 0) AS max_tokens,
                       COALESCE(SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END), 0) AS success_count,
                       COALESCE(AVG(total_duration_ms), 0) AS avg_duration_ms
                FROM traces
                WHERE start_time >= ? AND start_time <= ?
                """, (rs, rowNum) -> new TokenConsumptionStats(
                rs.getInt("trace_count"),
                rs.getLong("total_tokens"),
                rs.getLong("total_input_tokens"),
                rs.getLong("total_output_tokens"),
                rs.getDouble("avg_tokens"),
                rs.getInt("max_tokens"),
                rs.getInt("success_count"),
                rs.getDouble("avg_duration_ms")
        ), start.toString(), end.toString());
    }

    /**
     * 获取指定时间窗口内的概览统计。
     *
     * @param window 时间窗口（24h / 7d / 30d），默认使用 7d
     * @return 概览统计数据
     */
    public OverviewStats getOverviewStats(String window) {
        Instant now = Instant.now();
        Instant start = switch (window) {
            case "24h" -> now.minus(Duration.ofHours(24));
            case "30d" -> now.minus(Duration.ofDays(30));
            default -> now.minus(Duration.ofDays(7));
        };

        return jdbcTemplate.queryForObject("""
                SELECT
                    COUNT(*)                                                   AS total_traces,
                    COALESCE(SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END), 0)  AS success_count,
                    COALESCE(SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END), 0)  AS failure_count,
                    CASE
                        WHEN COUNT(*) > 0 THEN
                            CAST(SUM(CASE WHEN success = 1 THEN 1 ELSE 0 END) AS REAL) / COUNT(*)
                        ELSE 0.0
                    END                                                        AS success_rate,
                    COALESCE(AVG(total_steps), 0.0)                            AS avg_steps,
                    COALESCE(AVG(total_duration_ms), 0.0)                      AS avg_duration_ms,
                    COALESCE(SUM(total_tokens), 0)                             AS total_tokens,
                    COALESCE(AVG(total_tokens), 0.0)                           AS avg_tokens
                FROM traces
                WHERE start_time >= ? AND start_time <= ?
                """, (rs, rowNum) -> new OverviewStats(
                rs.getInt("total_traces"),
                rs.getInt("success_count"),
                rs.getInt("failure_count"),
                rs.getDouble("success_rate"),
                rs.getDouble("avg_steps"),
                rs.getDouble("avg_duration_ms"),
                rs.getLong("total_tokens"),
                rs.getDouble("avg_tokens")
        ), start.toString(), now.toString());
    }

    /**
     * 获取所有工具的使用统计。
     *
     * @return 工具使用统计列表
     */
    public List<ToolUsageStats> getToolUsageStats() {
        // 仅统计工具调用步骤，聚合逻辑在内存中完成以简化 SQL。
        var steps = jdbcTemplate.query("""
                SELECT detail_json
                FROM trace_steps
                WHERE step_type = 'tool_call'
                """, (rs, rowNum) -> rs.getString("detail_json"));

        record Accumulator(long callCount, long successCount, long totalDurationMs) {
        }

        var statsByTool = steps.stream()
                .map(json -> {
                    try {
                        TraceStep step = serializer.deserialize(json, "tool_call");
                        return (step instanceof ToolCallStep tool) ? tool : null;
                    } catch (Exception e) {
                        log.warn("工具步骤反序列化失败，跳过统计: error={}", e.getMessage());
                        return null;
                    }
                })
                .filter(tool -> tool != null && tool.toolId() != null && !tool.toolId().isBlank())
                .collect(java.util.stream.Collectors.toMap(
                        ToolCallStep::toolId,
                        tool -> new Accumulator(1, tool.success() ? 1 : 0, tool.duration().toMillis()),
                        (a, b) -> new Accumulator(
                                a.callCount + b.callCount,
                                a.successCount + b.successCount,
                                a.totalDurationMs + b.totalDurationMs
                        )
                ));

        return statsByTool.entrySet().stream()
                .map(entry -> {
                    String toolId = entry.getKey();
                    Accumulator acc = entry.getValue();
                    int callCount = (int) acc.callCount;
                    int successCount = (int) acc.successCount;
                    int failureCount = callCount - successCount;
                    double successRate = callCount > 0 ? (double) successCount / callCount : 0.0;
                    double avgDurationMs = callCount > 0
                            ? (double) acc.totalDurationMs / callCount
                            : 0.0;
                    return new ToolUsageStats(
                            toolId,
                            callCount,
                            successCount,
                            failureCount,
                            successRate,
                            avgDurationMs
                    );
                })
                .sorted(java.util.Comparator.comparing(ToolUsageStats::toolId))
                .toList();
    }

    /**
     * 根据轨迹 ID 获取评估结果。
     *
     * @param traceId 轨迹 ID
     * @return 评估结果
     * @throws TraceNotFoundException 当轨迹无评估结果时抛出
     */
    public EvaluationResult getEvaluation(String traceId) {
        var results = jdbcTemplate.query("""
                SELECT trace_id, evaluated_at,
                       tool_selection_score, parameter_validity_score, step_efficiency_score,
                       policy_compliance_score, token_efficiency_score, overall_score,
                       actual_steps, actual_tokens, violations_json, suggestions_json
                FROM evaluation_results
                WHERE trace_id = ?
                """, (rs, rowNum) -> {
            String violationsJson = rs.getString("violations_json");
            String suggestionsJson = rs.getString("suggestions_json");

            var violations = (violationsJson == null || violationsJson.isBlank())
                    ? List.<String>of()
                    : Arrays.stream(violationsJson.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();

            var suggestions = (suggestionsJson == null || suggestionsJson.isBlank())
                    ? List.<String>of()
                    : Arrays.stream(suggestionsJson.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();

            return new EvaluationResult(
                    rs.getString("trace_id"),
                    Instant.parse(rs.getString("evaluated_at")),
                    rs.getDouble("tool_selection_score"),
                    rs.getDouble("parameter_validity_score"),
                    rs.getDouble("step_efficiency_score"),
                    rs.getDouble("policy_compliance_score"),
                    rs.getDouble("token_efficiency_score"),
                    rs.getDouble("overall_score"),
                    rs.getInt("actual_steps"),
                    rs.getInt("actual_tokens"),
                    violations,
                    suggestions
            );
        }, traceId);

        if (results.isEmpty()) {
            throw new TraceNotFoundException("轨迹无评估结果: traceId=" + traceId);
        }

        return results.getFirst();
    }

    // ─── 内部方法 ───

    /**
     * 从 trace_steps 表加载步骤列表。
     */
    private List<TraceStep> loadSteps(String traceId) {
        return jdbcTemplate.query("""
                SELECT step_index, step_type, timestamp, duration_ms, detail_json
                FROM trace_steps
                WHERE trace_id = ?
                ORDER BY step_index
                """, (rs, rowNum) -> {
            String stepType = rs.getString("step_type");
            String detailJson = rs.getString("detail_json");
            try {
                return serializer.deserialize(detailJson, stepType);
            } catch (Exception e) {
                log.warn("步骤反序列化失败，跳过: traceId={}, stepIndex={}, error={}",
                        traceId, rs.getInt("step_index"), e.getMessage());
                // 返回一个降级的 StateTransitionStep 以保持轨迹完整性
                return new StateTransitionStep(
                        rs.getInt("step_index"),
                        Instant.parse(rs.getString("timestamp")),
                        Duration.ofMillis(rs.getLong("duration_ms")),
                        "UNKNOWN", "UNKNOWN",
                        "deserialization_error",
                        "反序列化失败: " + e.getMessage()
                );
            }
        }, traceId);
    }

    /**
     * 为步骤生成人类可读摘要。
     */
    private String generateSummary(TraceStep step) {
        return switch (step) {
            case LlmCallStep llm -> "LLM 调用: provider=%s, model=%s, tokens=%d+%d, 耗时=%dms"
                    .formatted(llm.providerId(), llm.modelId(),
                            llm.inputTokens(), llm.outputTokens(),
                            llm.latency().toMillis());
            case ToolCallStep tool -> "工具调用: %s.%s, %s, 耗时=%dms"
                    .formatted(tool.toolId(), tool.toolAction(),
                            tool.success() ? "成功" : "失败: " + tool.errorMessage(),
                            tool.duration().toMillis());
            case GuardrailStep guard -> "护栏检查: policy=%s, %s, 风险=%s"
                    .formatted(guard.policyId(),
                            guard.passed() ? "通过" : "拦截: " + guard.reason(),
                            guard.riskLevel());
            case StateTransitionStep state -> "状态转换: %s → %s, 动作=%s"
                    .formatted(state.phaseBefore(), state.phaseAfter(), state.actionType());
            case EvaluationStep eval -> "轨迹评估: 综合评分=%.2f, 违规=%d项"
                    .formatted(eval.overallScore(), eval.violations().size());
        };
    }
}
