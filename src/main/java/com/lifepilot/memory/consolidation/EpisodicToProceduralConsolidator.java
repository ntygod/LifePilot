package com.lifepilot.memory.consolidation;

import com.lifepilot.llm.LlmRequest;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.procedural.ProcedureTemplate;
import com.lifepilot.memory.procedural.ProceduralMemory;
import com.lifepilot.memory.procedural.TemplateStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 情景→程序巩固器 — 从成功执行轨迹中识别重复模式，通过 LLM 提炼操作模板。
 *
 * <p>巩固流程：
 * <ol>
 *   <li>从 agent_traces + agent_trace_steps 查询增量窗口内的成功执行轨迹</li>
 *   <li>过滤工具调用步数 ≥ minExecutionSteps 的轨迹</li>
 *   <li>LlmRouter.embed() 向量化工具调用序列，余弦相似度贪心聚类</li>
 *   <li>聚类大小 ≥ minClusterSize → LlmRouter.callEntity() 提炼操作模板</li>
 *   <li>与已有模板去重（triggerIntent 向量相似度 ≥ 0.9 视为同一模板）</li>
 *   <li>每次最多提炼 maxTemplatesPerRun 个新模板</li>
 *   <li>记录巩固日志到 memory_consolidation_log</li>
 * </ol>
 *
 * <p>LLM 不可用时跳过模板提炼，返回 0 个新模板。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public class EpisodicToProceduralConsolidator {

    private static final Logger log = LoggerFactory.getLogger(EpisodicToProceduralConsolidator.class);
    private static final String CONSOLIDATION_TYPE = "EPISODIC_TO_PROCEDURAL";
    /** 去重阈值：triggerIntent 向量相似度 ≥ 0.9 视为同一模板。 */
    private static final float DEDUP_SIMILARITY_THRESHOLD = 0.9f;

    private final JdbcTemplate jdbcTemplate;
    private final ProceduralMemory proceduralMemory;
    private final LlmRouter llmRouter;
    private final MemoryProperties properties;

    /**
     * 构造情景→程序巩固器。
     *
     * @param jdbcTemplate     JDBC 模板
     * @param proceduralMemory L4 程序记忆服务
     * @param llmRouter        LLM 路由器
     * @param properties       记忆配置
     */
    public EpisodicToProceduralConsolidator(JdbcTemplate jdbcTemplate,
                                            ProceduralMemory proceduralMemory,
                                            LlmRouter llmRouter,
                                            MemoryProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.proceduralMemory = proceduralMemory;
        this.llmRouter = llmRouter;
        this.properties = properties;
        log.info("EpisodicToProceduralConsolidator 初始化完成");
    }

    /**
     * 执行情景→程序巩固。
     *
     * @return 巩固统计结果
     */
    public ConsolidationStats consolidate() {
        long startMs = System.currentTimeMillis();
        var config = properties.getConsolidation();

        // 1. 获取增量窗口起始时间
        Instant windowStart = getLastConsolidationTime()
                .orElse(Instant.now().minus(Duration.ofDays(config.getLookbackDays())));

        // 2. 查询成功执行轨迹（工具调用步数 ≥ minExecutionSteps）
        var traces = queryEligibleTraces(windowStart, config.getMinExecutionSteps());
        if (traces.isEmpty()) {
            long elapsed = System.currentTimeMillis() - startMs;
            log.info("程序巩固: 无符合条件的执行轨迹, windowStart={}", windowStart);
            var stats = new ConsolidationStats(CONSOLIDATION_TYPE, 0, 0, 0, 0, 0, 0, elapsed);
            logConsolidation(stats);
            return stats;
        }

        log.info("程序巩固: 发现 {} 个符合条件的执行轨迹, windowStart={}", traces.size(), windowStart);

        // 3. 提取每个轨迹的工具调用序列
        var traceSequences = new LinkedHashMap<TraceInfo, String>();
        for (var trace : traces) {
            String toolSequence = extractToolSequence(trace.traceId());
            if (!toolSequence.isBlank()) {
                traceSequences.put(trace, toolSequence);
            }
        }

        if (traceSequences.isEmpty()) {
            long elapsed = System.currentTimeMillis() - startMs;
            log.info("程序巩固: 所有轨迹的工具调用序列为空");
            var stats = new ConsolidationStats(CONSOLIDATION_TYPE, traces.size(), 0, 0, 0, 0, 0, elapsed);
            logConsolidation(stats);
            return stats;
        }

        // 4. 向量化 + 聚类 + 模板提炼（需要 LLM）
        int templatesCreated = 0;
        int templatesUpdated = 0;
        try {
            // 4a. 向量化工具调用序列
            var embeddings = embedTraceSequences(traceSequences);

            // 4b. 余弦相似度贪心聚类
            var clusters = clusterByCosineSimilarity(embeddings, config.getClusterSimilarityThreshold());

            // 4c. 过滤满足最小聚类大小的聚类
            var eligibleClusters = clusters.stream()
                    .filter(c -> c.size() >= config.getMinClusterSize())
                    .toList();

            log.info("程序巩固: 聚类完成, 总聚类={}, 符合条件聚类={}", clusters.size(), eligibleClusters.size());

            // 4d. 对每个符合条件的聚类提炼模板（限制最大数量）
            for (var cluster : eligibleClusters) {
                if (templatesCreated >= config.getMaxTemplatesPerRun()) {
                    log.info("程序巩固: 已达单次最大模板数限制, max={}", config.getMaxTemplatesPerRun());
                    break;
                }

                try {
                    var result = extractAndSaveTemplate(cluster, traceSequences);
                    if (result == TemplateResult.CREATED) {
                        templatesCreated++;
                    } else if (result == TemplateResult.UPDATED) {
                        templatesUpdated++;
                    }
                } catch (LlmUnavailableException e) {
                    log.warn("程序巩固: LLM 不可用, 跳过剩余模板提炼, error={}", e.getMessage());
                    break;
                } catch (Exception e) {
                    log.warn("程序巩固: 模板提炼失败, clusterSize={}, error={}", cluster.size(), e.getMessage());
                }
            }
        } catch (LlmUnavailableException e) {
            log.warn("程序巩固: LLM 不可用, 跳过模板提炼, error={}", e.getMessage());
        }

        // 5. 记录巩固日志
        long elapsed = System.currentTimeMillis() - startMs;
        var stats = new ConsolidationStats(
                CONSOLIDATION_TYPE,
                traceSequences.size(),
                0, 0, 0,
                templatesCreated,
                templatesUpdated,
                elapsed);
        logConsolidation(stats);

        log.info("程序巩固完成: traces={}, created={}, updated={}, elapsed={}ms",
                traceSequences.size(), templatesCreated, templatesUpdated, elapsed);

        return stats;
    }

    // ========== 轨迹查询 ==========

    /**
     * 查询增量窗口内的成功执行轨迹（工具调用步数 ≥ minExecutionSteps）。
     */
    private List<TraceInfo> queryEligibleTraces(Instant windowStart, int minExecutionSteps) {
        return jdbcTemplate.query(
                """
                SELECT t.id, t.goal, t.created_at
                FROM agent_traces t
                WHERE t.success = 1
                  AND t.created_at > ?
                  AND (SELECT COUNT(*) FROM agent_trace_steps s
                       WHERE s.trace_id = t.id AND s.tool_id IS NOT NULL) >= ?
                """,
                (rs, rowNum) -> new TraceInfo(
                        rs.getString("id"),
                        rs.getString("goal"),
                        rs.getString("created_at")),
                windowStart.toString(), minExecutionSteps);
    }

    /**
     * 提取指定轨迹的工具调用序列文本。
     *
     * <p>格式：{@code toolId:action(params) → toolId:action(params) → ...}</p>
     */
    private String extractToolSequence(String traceId) {
        var steps = jdbcTemplate.query(
                """
                SELECT tool_id, tool_input_json
                FROM agent_trace_steps
                WHERE trace_id = ? AND tool_id IS NOT NULL
                ORDER BY step_index
                """,
                (rs, rowNum) -> {
                    String toolId = rs.getString("tool_id");
                    String inputJson = rs.getString("tool_input_json");
                    // 简化表示：toolId(inputJson 前 100 字符)
                    String truncatedInput = inputJson != null && inputJson.length() > 100
                            ? inputJson.substring(0, 100) : (inputJson != null ? inputJson : "");
                    return toolId + "(" + truncatedInput + ")";
                },
                traceId);

        return String.join(" → ", steps);
    }

    // ========== 向量化与聚类 ==========

    /**
     * 向量化所有轨迹的工具调用序列。
     *
     * @return 轨迹信息 → 嵌入向量的有序映射
     */
    private LinkedHashMap<TraceInfo, float[]> embedTraceSequences(
            LinkedHashMap<TraceInfo, String> traceSequences) {
        var embeddings = new LinkedHashMap<TraceInfo, float[]>();
        for (var entry : traceSequences.entrySet()) {
            float[] vector = llmRouter.embed(entry.getValue());
            embeddings.put(entry.getKey(), vector);
        }
        return embeddings;
    }

    /**
     * 贪心聚类：对每个未分配的轨迹，找到所有余弦相似度 ≥ 阈值的轨迹归入同一聚类。
     *
     * @param embeddings 轨迹嵌入向量
     * @param threshold  余弦相似度阈值
     * @return 聚类列表，每个聚类包含一组轨迹信息
     */
    private List<List<TraceInfo>> clusterByCosineSimilarity(
            LinkedHashMap<TraceInfo, float[]> embeddings, float threshold) {
        var traceList = new ArrayList<>(embeddings.keySet());
        var vectorList = new ArrayList<>(embeddings.values());
        var assigned = new boolean[traceList.size()];
        var clusters = new ArrayList<List<TraceInfo>>();

        for (int i = 0; i < traceList.size(); i++) {
            if (assigned[i]) continue;
            assigned[i] = true;
            var cluster = new ArrayList<TraceInfo>();
            cluster.add(traceList.get(i));

            for (int j = i + 1; j < traceList.size(); j++) {
                if (assigned[j]) continue;
                float similarity = cosineSimilarity(vectorList.get(i), vectorList.get(j));
                if (similarity >= threshold) {
                    assigned[j] = true;
                    cluster.add(traceList.get(j));
                }
            }
            clusters.add(cluster);
        }
        return clusters;
    }

    // ========== 模板提炼 ==========

    /**
     * 对一个聚类提炼操作模板并保存。
     *
     * @param cluster         聚类中的轨迹列表
     * @param traceSequences  轨迹 → 工具调用序列映射
     * @return 模板操作结果
     */
    private TemplateResult extractAndSaveTemplate(List<TraceInfo> cluster,
                                                   LinkedHashMap<TraceInfo, String> traceSequences) {
        // 拼接聚类中所有轨迹的工具调用序列
        String combinedSequences = cluster.stream()
                .map(t -> "轨迹 [" + t.goal() + "]:\n" + traceSequences.get(t))
                .collect(Collectors.joining("\n\n"));

        // LLM 提炼模板
        String prompt = """
                任务：从重复出现的工具调用序列中提炼可复用的操作模板
                
                输入数据：
                %s
                
                要求：
                1. 识别序列中的共同模式和变化点
                2. 将变化点抽象为变量（使用 ${variable} 占位符）
                3. 提取触发意图（triggerIntent）：用户表达此操作的自然语言模式
                4. 为每个步骤提供清晰的描述
                
                输出格式（JSON）：
                {
                  "name": "模板名称（简洁，如'创建待办并设置提醒'）",
                  "description": "模板描述（说明此模板的用途和适用场景）",
                  "triggerIntent": "触发意图（用户可能使用的自然语言表达，如'创建一个待办事项并设置提醒'）",
                  "steps": [
                    {
                      "toolId": "工具ID",
                      "action": "操作名称",
                      "parameterTemplate": {
                        "param1": "${variable1}",
                        "param2": "固定值或${variable2}"
                      },
                      "description": "步骤描述"
                    }
                  ]
                }
                
                注意：
                - parameterTemplate 中的变量名应具有语义（如 ${title}, ${priority}）
                - 固定值不要使用占位符
                - triggerIntent 应覆盖用户可能的表达方式
                """.formatted(combinedSequences);

        var extraction = llmRouter.callEntity(
                LlmRequest.of(LlmScene.KNOWLEDGE_EXTRACTION, prompt), TemplateExtraction.class);

        if (extraction == null || extraction.name() == null || extraction.name().isBlank()) {
            log.warn("程序巩固: LLM 返回空模板, clusterSize={}", cluster.size());
            return TemplateResult.SKIPPED;
        }

        // 去重检查：triggerIntent 向量相似度 ≥ 0.9 视为同一模板
        if (isDuplicateTemplate(extraction.triggerIntent())) {
            log.debug("程序巩固: 模板已存在（去重命中）, name={}", extraction.name());
            return TemplateResult.UPDATED;
        }

        // 构建并保存新模板
        var steps = extraction.steps() != null
                ? extraction.steps().stream()
                    .map(s -> new TemplateStep(
                            extraction.steps().indexOf(s) + 1,
                            s.toolId() != null ? s.toolId() : "",
                            s.action() != null ? s.action() : "",
                            s.parameterTemplate() != null ? s.parameterTemplate() : Map.of(),
                            s.description() != null ? s.description() : "",
                            false))
                    .toList()
                : List.<TemplateStep>of();

        var sourceTraceIds = cluster.stream().map(TraceInfo::traceId).toList();
        Instant now = Instant.now();

        var template = new ProcedureTemplate(
                UUID.randomUUID().toString(),
                extraction.name(),
                extraction.description() != null ? extraction.description() : "",
                extraction.triggerIntent() != null ? extraction.triggerIntent() : extraction.name(),
                steps,
                Map.of(),
                0.0f,
                0,
                null,
                sourceTraceIds,
                now,
                now);

        proceduralMemory.save(template);
        log.info("程序巩固: 新模板已保存, name={}, steps={}, sources={}",
                template.name(), steps.size(), sourceTraceIds.size());

        return TemplateResult.CREATED;
    }

    /**
     * 去重检查：通过 LlmRouter.embed() 计算 triggerIntent 向量，
     * 与已有模板的 triggerIntent 向量比较相似度。
     *
     * @param triggerIntent 新模板的触发意图文本
     * @return 如果已存在相似模板返回 true
     */
    private boolean isDuplicateTemplate(String triggerIntent) {
        if (triggerIntent == null || triggerIntent.isBlank()) {
            return false;
        }
        try {
            float[] newVector = llmRouter.embed(triggerIntent);

            // 查询所有已有模板的 triggerIntent
            var existingTemplates = jdbcTemplate.query(
                    "SELECT template_id, trigger_intent FROM procedure_templates",
                    (rs, rowNum) -> new String[]{rs.getString("template_id"), rs.getString("trigger_intent")});

            for (var existing : existingTemplates) {
                try {
                    float[] existingVector = llmRouter.embed(existing[1]);
                    float similarity = cosineSimilarity(newVector, existingVector);
                    if (similarity >= DEDUP_SIMILARITY_THRESHOLD) {
                        log.debug("程序巩固: 去重命中, existingId={}, similarity={}", existing[0], similarity);
                        return true;
                    }
                } catch (LlmUnavailableException e) {
                    // LLM 不可用时无法去重，保守跳过
                    log.warn("程序巩固: 去重时 LLM 不可用, 跳过去重检查");
                    return false;
                }
            }
        } catch (LlmUnavailableException e) {
            log.warn("程序巩固: 去重时 LLM 不可用, 跳过去重检查");
            return false;
        }
        return false;
    }

    // ========== 日志与工具方法 ==========

    /**
     * 获取上次巩固的时间戳。
     *
     * @return 上次巩固时间，无记录时返回 Optional.empty()
     */
    private Optional<Instant> getLastConsolidationTime() {
        var results = jdbcTemplate.queryForList(
                "SELECT created_at FROM memory_consolidation_log WHERE consolidation_type = ? ORDER BY created_at DESC LIMIT 1",
                String.class, CONSOLIDATION_TYPE);
        if (results.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(results.getFirst()));
        } catch (Exception e) {
            log.warn("程序巩固: 解析上次巩固时间失败, raw={}", results.getFirst());
            return Optional.empty();
        }
    }

    /**
     * 记录巩固日志到 memory_consolidation_log 表。
     */
    private void logConsolidation(ConsolidationStats stats) {
        jdbcTemplate.update(
                "INSERT INTO memory_consolidation_log (id, consolidation_type, conversations_analyzed, entities_found, entities_boosted, extractions_triggered, templates_created, templates_updated, elapsed_ms, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(),
                stats.consolidationType(),
                stats.conversationsAnalyzed(),
                stats.entitiesFound(),
                stats.entitiesBoosted(),
                stats.extractionsTriggered(),
                stats.templatesCreated(),
                stats.templatesUpdated(),
                stats.elapsedMs(),
                Instant.now().toString());
    }

    /**
     * 计算两个向量的余弦相似度。
     */
    private float cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0.0f;
        float dotProduct = 0.0f, normA = 0.0f, normB = 0.0f;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        float denominator = (float) (Math.sqrt(normA) * Math.sqrt(normB));
        return denominator == 0.0f ? 0.0f : dotProduct / denominator;
    }

    // ========== 内部 record ==========

    /** 执行轨迹基本信息。 */
    private record TraceInfo(String traceId, String goal, String createdAt) {}

    /** LLM 模板提炼结果。 */
    private record TemplateExtraction(
            String name,
            String description,
            String triggerIntent,
            List<StepExtraction> steps
    ) {}

    /** LLM 提炼的步骤信息。 */
    private record StepExtraction(
            String toolId,
            String action,
            Map<String, String> parameterTemplate,
            String description
    ) {}

    /** 模板操作结果枚举。 */
    private enum TemplateResult {
        CREATED, UPDATED, SKIPPED
    }
}
