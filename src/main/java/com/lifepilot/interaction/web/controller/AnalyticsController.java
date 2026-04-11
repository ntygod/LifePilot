package com.lifepilot.interaction.web.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.web.model.AgentStats;
import com.lifepilot.interaction.web.model.ErrorTrendDaily;
import com.lifepilot.interaction.web.model.KnowledgeBaseStats;
import com.lifepilot.interaction.web.model.ToolAnalyticsResponse;
import com.lifepilot.interaction.web.model.UsageStats;
import com.lifepilot.interaction.web.repository.AnalyticsRepository;
import com.lifepilot.knowledge.KnowledgeBaseManager;
import com.lifepilot.knowledge.model.KnowledgeBase;
import com.lifepilot.multiagent.registry.AgentRegistry;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.lifepilot.interaction.web.model.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Analytics REST Controller。
 *
 * <p>提供用量统计、Agent 统计和知识库统计端点。</p>
 *
 * <p>需要 traces 和 trace_steps 表存在（通过 trace 追踪功能创建）。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
@RestController
@RequestMapping("/api/analytics")
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
public class AnalyticsController {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsController.class);

    private final AnalyticsRepository analyticsRepository;
    private final KnowledgeBaseManager knowledgeBaseManager;
    private final ObjectMapper objectMapper;
    private final DynamicToolRegistry toolRegistry;
    private final AgentRegistry agentRegistry;

    // Token 成本估算：约 $2 / 1M tokens = $0.000002 per token
    private static final double COST_PER_TOKEN = 0.000002;

    public AnalyticsController(AnalyticsRepository analyticsRepository,
                                KnowledgeBaseManager knowledgeBaseManager,
                                ObjectMapper objectMapper,
                                DynamicToolRegistry toolRegistry,
                                AgentRegistry agentRegistry) {
        this.analyticsRepository = analyticsRepository;
        this.knowledgeBaseManager = knowledgeBaseManager;
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.agentRegistry = agentRegistry;
    }

    /**
     * 用量统计接口。
     *
     * @param from 开始时间（ISO 8601）
     * @param to   结束时间（ISO 8601）
     * @return 用量统计
     */
    @GetMapping("/usage")
    public ApiResponse<UsageStats> getUsageStats(
            @RequestParam String from,
            @RequestParam String to) {
        log.debug("查询用量统计: from={}, to={}", from, to);

        Instant startTime = Instant.parse(from);
        Instant endTime = Instant.parse(to);

        // 查询总体统计
        long[] overallStats = analyticsRepository.queryOverallStats(startTime.toString(), endTime.toString());
        long totalRequests = overallStats[0];
        long totalTokens = overallStats[1];
        long inputTokens = overallStats[2];
        long outputTokens = overallStats[3];
        double estimatedCost = totalTokens * COST_PER_TOKEN;

        // 查询每日统计，转换为 DailyStat（加入 cost 计算）
        List<UsageStats.DailyStat> dailyStats = analyticsRepository
                .queryDailyTokenStats(startTime.toString(), endTime.toString())
                .stream()
                .map(row -> new UsageStats.DailyStat(
                        row.date(),
                        row.requests(),
                        row.tokens(),
                        row.inputTokens(),
                        row.outputTokens(),
                        row.tokens() * COST_PER_TOKEN
                ))
                .toList();

        var stats = new UsageStats(
                totalRequests,
                totalTokens,
                inputTokens,
                outputTokens,
                estimatedCost,
                new UsageStats.TimeRange(from, to),
                dailyStats
        );

        return ApiResponse.ok(stats);
    }

    /**
     * Agent 统计接口。
     *
     * @param from 开始时间（可选）
     * @param to   结束时间（可选）
     * @return Agent 统计列表
     */
    @GetMapping("/agents")
    public ApiResponse<List<AgentStats>> getAgentStats(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        log.debug("查询 Agent 统计: from={}, to={}", from, to);

        // 查询按 metadata_json 分组的统计
        List<AnalyticsRepository.AgentGroupRow> rows = analyticsRepository.queryAgentGroupedStats(from, to);
        Map<String, AgentStatsData> agentDataMap = new HashMap<>();

        for (var row : rows) {
            long callCount = row.callCount();
            long avgResponseTime = Math.round(row.avgResponseTime());
            long failureCount = row.failureCount();
            long totalTokens = row.totalTokens();

            // 从 metadata_json 中提取 agent_id
            String agentId = extractAgentId(row.metadataJson());
            agentId = (agentId != null && !agentId.isBlank()) ? agentId : "unknown";

            agentDataMap.compute(agentId, (key, existing) -> {
                if (existing == null) {
                    return new AgentStatsData(callCount, avgResponseTime, failureCount, totalTokens);
                } else {
                    long totalCalls = existing.callCount + callCount;
                    double weightedAvg = totalCalls > 0
                            ? (existing.avgResponseTime * existing.callCount + avgResponseTime * callCount) / totalCalls
                            : 0.0;
                    return new AgentStatsData(
                            totalCalls,
                            Math.round(weightedAvg),
                            existing.failureCount + failureCount,
                            existing.totalTokens + totalTokens
                    );
                }
            });
        }

        // 获取 Agent 名称
        List<AgentStats> stats = agentDataMap.entrySet().stream()
                .map(entry -> {
                    String agentId = entry.getKey();
                    AgentStatsData data = entry.getValue();
                    double failureRate = data.callCount > 0 ? (double) data.failureCount / data.callCount : 0.0;

                    // 尝试从 Agent 定义中获取名称，如果不存在则使用默认名称
                    String agentName = getAgentName(agentId);

                    return new AgentStats(
                            agentId,
                            agentName,
                            data.callCount,
                            Math.round(data.avgResponseTime),
                            failureRate,
                            data.totalTokens
                    );
                })
                .sorted((a, b) -> Long.compare(b.callCount(), a.callCount())) // 按调用次数降序
                .collect(Collectors.toList());

        return ApiResponse.ok(stats);
    }

    /**
     * 知识库统计接口。
     *
     * @param from 开始时间（可选）
     * @param to   结束时间（可选）
     * @return 知识库统计列表
     */
    @GetMapping("/knowledge-bases")
    public ApiResponse<List<KnowledgeBaseStats>> getKnowledgeBaseStats(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        log.debug("查询知识库统计: from={}, to={}", from, to);

        // 查询知识库检索统计
        List<AnalyticsRepository.KbGroupRow> kbRows = analyticsRepository.queryKbGroupedStats(from, to);
        Map<String, KbStatsData> kbDataMap = new HashMap<>();

        for (var row : kbRows) {
            long retrievalCount = row.retrievalCount();
            long avgRetrievalTime = Math.round(row.avgRetrievalTime());

            // 从 detail_json 中提取 toolId
            String toolId = extractToolIdFromJson(row.detailJson());
            if (toolId == null || toolId.isBlank()) {
                continue;
            }

            // 从 tool_id 中提取知识库 ID（例如：knowledge_base.retrieve.kb-id）
            String kbId = extractKbIdFromToolId(toolId);
            kbId = (kbId != null && !kbId.isBlank()) ? kbId : "unknown";

            // 从 detail_json 中提取 success 状态
            boolean success = extractSuccessFromJson(row.detailJson());
            long successCount = success ? retrievalCount : 0;

            kbDataMap.compute(kbId, (key, existing) -> {
                if (existing == null) {
                    return new KbStatsData(retrievalCount, avgRetrievalTime, successCount);
                } else {
                    long totalRetrievals = existing.retrievalCount + retrievalCount;
                    double weightedAvg = totalRetrievals > 0
                            ? (existing.avgRetrievalTime * existing.retrievalCount + avgRetrievalTime * retrievalCount) / totalRetrievals
                            : 0.0;
                    return new KbStatsData(
                            totalRetrievals,
                            Math.round(weightedAvg),
                            existing.successCount + successCount
                    );
                }
            });
        }

        // 获取知识库名称
        List<KnowledgeBaseStats> stats = kbDataMap.entrySet().stream()
                .map(entry -> {
                    String kbId = entry.getKey();
                    KbStatsData data = entry.getValue();
                    double hitRate = data.retrievalCount > 0 
                            ? (double) data.successCount / data.retrievalCount 
                            : 0.0;

                    // 从知识库管理器中获取名称
                    String kbName = getKnowledgeBaseName(kbId);

                    return new KnowledgeBaseStats(
                            kbId,
                            kbName,
                            data.retrievalCount,
                            hitRate,
                            Math.round(data.avgRetrievalTime)
                    );
                })
                .sorted((a, b) -> Long.compare(b.retrievalCount(), a.retrievalCount())) // 按检索次数降序
                .collect(Collectors.toList());

        return ApiResponse.ok(stats);
    }

    // ─── Tool 调用统计 ───

    /**
     * Tool 调用统计接口。
     *
     * <p>查询 trace_steps 表中 step_type='tool_call' 的记录，
     * 按 toolId 聚合统计调用次数、成功/失败次数、平均耗时，
     * 并按天分组生成趋势数据。</p>
     *
     * @param from 开始时间（ISO 8601，可选，缺失时默认最近 30 天）
     * @param to   结束时间（ISO 8601，可选，缺失时默认当前时间）
     * @return Tool 调用统计响应
     */
    @GetMapping("/tools")
    public ApiResponse<ToolAnalyticsResponse> getToolAnalytics(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        log.debug("查询 Tool 调用统计: from={}, to={}", from, to);

        // 默认时间范围：最近 30 天
        Instant endTime = (to != null && !to.isBlank()) ? Instant.parse(to) : Instant.now();
        Instant startTime = (from != null && !from.isBlank()) ? Instant.parse(from) : endTime.minus(30, ChronoUnit.DAYS);

        // 查询所有 tool_call 类型的步骤
        List<AnalyticsRepository.ToolCallStepRow> toolRows =
                analyticsRepository.queryToolCallSteps(startTime.toString(), endTime.toString());

        // 按 toolId 聚合统计
        Map<String, ToolAggregation> toolAggMap = new HashMap<>();
        // 按天分组聚合
        Map<String, DailyAggregation> dailyAggMap = new TreeMap<>();

        for (var row : toolRows) {
            String detailJson = row.detailJson();
            long durationMs = row.durationMs();
            String timestamp = row.timestamp();

            String toolId = extractToolIdFromJson(detailJson);
            if (toolId == null || toolId.isBlank()) {
                continue;
            }
            boolean success = extractSuccessFromJson(detailJson);

            // 工具维度聚合
            toolAggMap.computeIfAbsent(toolId, k -> new ToolAggregation())
                    .add(success, durationMs);

            // 日期维度聚合（从 timestamp 提取日期部分）
            String date = extractDate(timestamp);
            if (date != null) {
                dailyAggMap.computeIfAbsent(date, k -> new DailyAggregation())
                        .add(success);
            }
        }

        // 构建 toolStats 列表
        List<ToolAnalyticsResponse.ToolStatItem> toolStats = toolAggMap.entrySet().stream()
                .map(entry -> {
                    String toolId = entry.getKey();
                    ToolAggregation agg = entry.getValue();
                    // 解析工具显示名称，未找到时回退到 toolId
                    String displayName = toolRegistry.resolve(toolId)
                            .map(t -> t.name())
                            .orElse(toolId);
                    return new ToolAnalyticsResponse.ToolStatItem(
                            toolId,
                            displayName,
                            agg.callCount,
                            agg.successCount,
                            agg.callCount - agg.successCount,
                            agg.callCount > 0 ? agg.totalDurationMs / agg.callCount : 0
                    );
                })
                .sorted((a, b) -> Integer.compare(b.callCount(), a.callCount()))
                .collect(Collectors.toList());

        // 构建 dailyTrend 列表
        List<ToolAnalyticsResponse.DailyTrend> dailyTrend = dailyAggMap.entrySet().stream()
                .map(entry -> {
                    DailyAggregation agg = entry.getValue();
                    return new ToolAnalyticsResponse.DailyTrend(
                            entry.getKey(),
                            agg.callCount,
                            agg.successCount,
                            agg.callCount - agg.successCount
                    );
                })
                .collect(Collectors.toList());

        return ApiResponse.ok(new ToolAnalyticsResponse(toolStats, dailyTrend));
    }

    // ─── 错误趋势统计 ───

    /**
     * 错误趋势统计接口。
     *
     * <p>按天统计失败的 Trace 数量，区分 Agent 错误和工具错误。
     * Agent 错误：失败 Trace 中不包含 tool_call 步骤的错误。
     * 工具错误：失败 Trace 中包含失败 tool_call 步骤的错误。</p>
     *
     * @param from 开始时间（ISO 8601）
     * @param to   结束时间（ISO 8601）
     * @return 每日错误趋势列表
     */
    @GetMapping("/error-trend")
    public ApiResponse<List<ErrorTrendDaily>> getErrorTrend(
            @RequestParam String from,
            @RequestParam String to) {
        log.debug("查询错误趋势: from={}, to={}", from, to);

        Instant startTime = Instant.parse(from);
        Instant endTime = Instant.parse(to);

        // 查询每日失败 Trace 总数及工具错误数
        List<ErrorTrendDaily> trend = analyticsRepository.queryErrorTrend(
                startTime.toString(), endTime.toString());

        return ApiResponse.ok(trend);
    }

    // ─── 内部辅助方法 ───

    /**
     * 从 metadata_json 中提取 agent_id。
     */
    private String extractAgentId(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> metadata = objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {});
            Object agentId = metadata.get("agentId");
            if (agentId != null) {
                return agentId.toString();
            }
            // 也尝试从 tags 中获取
            Object tags = metadata.get("tags");
            if (tags instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> tagsMap = (Map<String, Object>) tags;
                Object tagAgentId = tagsMap.get("agentId");
                if (tagAgentId != null) {
                    return tagAgentId.toString();
                }
            }
        } catch (Exception e) {
            log.debug("解析 metadata_json 失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 获取 Agent 名称。
     */
    private String getAgentName(String agentId) {
        if ("unknown".equals(agentId)) {
            return "未知 Agent";
        }
        return agentRegistry.find(agentId)
                .map(agent -> agent.name() != null && !agent.name().isBlank() ? agent.name() : agent.id())
                .orElse("Agent " + agentId);
    }

    /**
     * 从 detail_json 中提取 toolId。
     */
    private String extractToolIdFromJson(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> detail = objectMapper.readValue(detailJson, new TypeReference<Map<String, Object>>() {});
            Object toolId = detail.get("toolId");
            if (toolId != null) {
                return toolId.toString();
            }
        } catch (Exception e) {
            log.debug("解析 detail_json 提取 toolId 失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 从 detail_json 中提取 success 状态。
     */
    private boolean extractSuccessFromJson(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) {
            return false;
        }
        try {
            Map<String, Object> detail = objectMapper.readValue(detailJson, new TypeReference<Map<String, Object>>() {});
            Object success = detail.get("success");
            if (success instanceof Boolean) {
                return (Boolean) success;
            }
        } catch (Exception e) {
            log.debug("解析 detail_json 提取 success 失败: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 从 ISO 8601 时间戳中提取日期部分（yyyy-MM-dd）。
     */
    private String extractDate(String timestamp) {
        if (timestamp == null || timestamp.length() < 10) {
            return null;
        }
        // ISO 8601 格式：2026-03-15T10:23:45Z 或 2026-03-15T10:23:45.123Z
        return timestamp.substring(0, 10);
    }

    /**
     * 从 tool_id 中提取知识库 ID。
     */
    private String extractKbIdFromToolId(String toolId) {
        if (toolId == null || toolId.isBlank()) {
            return null;
        }
        // tool_id 格式可能是：knowledge_base.retrieve.kb-id 或 kb.retrieve.kb-id
        String[] parts = toolId.split("\\.");
        if (parts.length >= 3) {
            return parts[2];
        }
        // 也可能是：knowledge_base.kb-id 或 kb.kb-id
        if (parts.length >= 2) {
            return parts[1];
        }
        return null;
    }

    /**
     * 获取知识库名称。
     */
    private String getKnowledgeBaseName(String kbId) {
        if ("unknown".equals(kbId)) {
            return "未知知识库";
        }
        Optional<KnowledgeBase> kb = knowledgeBaseManager.getKnowledgeBase(kbId);
        return kb.map(KnowledgeBase::name).orElse("知识库 " + kbId);
    }

    /**
     * Agent 统计数据临时类。
     */
    private static class AgentStatsData {
        final long callCount;
        final double avgResponseTime;
        final long failureCount;
        final long totalTokens;

        AgentStatsData(long callCount, double avgResponseTime, long failureCount, long totalTokens) {
            this.callCount = callCount;
            this.avgResponseTime = avgResponseTime;
            this.failureCount = failureCount;
            this.totalTokens = totalTokens;
        }
    }

    /**
     * 知识库统计数据临时类。
     */
    private static class KbStatsData {
        final long retrievalCount;
        final double avgRetrievalTime;
        final long successCount;

        KbStatsData(long retrievalCount, double avgRetrievalTime, long successCount) {
            this.retrievalCount = retrievalCount;
            this.avgRetrievalTime = avgRetrievalTime;
            this.successCount = successCount;
        }
    }

    /**
     * Tool 调用聚合临时类。
     */
    private static class ToolAggregation {
        int callCount;
        int successCount;
        long totalDurationMs;

        void add(boolean success, long durationMs) {
            callCount++;
            if (success) {
                successCount++;
            }
            totalDurationMs += durationMs;
        }
    }

    /**
     * 每日调用聚合临时类。
     */
    private static class DailyAggregation {
        int callCount;
        int successCount;

        void add(boolean success) {
            callCount++;
            if (success) {
                successCount++;
            }
        }
    }
}
