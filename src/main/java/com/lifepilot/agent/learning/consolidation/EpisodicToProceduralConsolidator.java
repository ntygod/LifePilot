package com.lifepilot.agent.learning.consolidation;

import com.lifepilot.llm.LlmScene;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.embedding.router.EmbeddingUseCase;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.generation.support.JsonOutputParser;
import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.store.procedural.ProcedureTemplate;
import com.lifepilot.memory.store.procedural.ProceduralMemory;
import com.lifepilot.memory.store.procedural.TemplateStep;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
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
 *   <li>通过 EmbeddingRouter 向量化工具调用序列，使用余弦相似度进行贪心聚类</li>
 *   <li>聚类大小 ≥ minClusterSize 后，通过 GenerationRouter 提炼操作模板</li>
 *   <li>与已有模板去重（triggerIntent 向量相似度 ≥ 0.9 视为同一模板）</li>
 *   <li>每次最多提炼 maxTemplatesPerRun 个新模板</li>
 *   <li>记录巩固日志到 memory_consolidation_log</li>
 * </ol>
 *
 * <p>生成与向量依赖为核心依赖，装配失败应在构造期暴露；运行期失败由巩固管线做阶段隔离。</p>
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
    private final GenerationRouter generationRouter;
    private final EmbeddingRouter embeddingRouter;
    private final AgentLearningProperties properties;
    private final PromptRegistry promptRegistry;

    /**
     * 构造情景→程序巩固器。
     *
     * @param jdbcTemplate     JDBC 模板
     * @param proceduralMemory L4 程序记忆服务
     * @param generationRouter 生成路由器
     * @param properties       记忆配置
     * @param promptRegistry   提示词注册表
     */
    public EpisodicToProceduralConsolidator(JdbcTemplate jdbcTemplate,
                                            ProceduralMemory proceduralMemory,
                                            GenerationRouter generationRouter,
                                            EmbeddingRouter embeddingRouter,
                                            AgentLearningProperties properties,
                                            PromptRegistry promptRegistry) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate 不能为空");
        this.proceduralMemory = Objects.requireNonNull(proceduralMemory, "proceduralMemory 不能为空");
        this.generationRouter = Objects.requireNonNull(generationRouter, "generationRouter 不能为空");
        this.embeddingRouter = Objects.requireNonNull(embeddingRouter, "embeddingRouter 不能为空");
        this.properties = Objects.requireNonNull(properties, "properties 不能为空");
        this.promptRegistry = Objects.requireNonNull(promptRegistry, "promptRegistry 不能为空");
        log.info("EpisodicToProceduralConsolidator 初始化完成");
    }

    /**
     * 执行情景→程序巩固。
     *
     * @return 巩固统计结果
     */
    public ConsolidationStats consolidate() {
        // 操作模板聚类开关（可通过配置关闭以节省 LLM 成本）
        if (!properties.getConsolidation().isProceduralTemplateEnabled()) {
            log.debug("程序巩固: 操作模板聚类已关闭，跳过");
            return new ConsolidationStats(CONSOLIDATION_TYPE, 0, 0, 0, 0, 0, 0, 0);
        }
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

            var result = extractAndSaveTemplate(cluster, traceSequences);
            if (result == TemplateResult.CREATED) {
                templatesCreated++;
            } else if (result == TemplateResult.UPDATED) {
                templatesUpdated++;
            }
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
                SELECT t.id, t.user_message, t.created_at
                FROM agent_traces t
                WHERE t.success = 1
                  AND t.created_at > ?
                  AND (SELECT COUNT(*) FROM agent_trace_steps s
                       WHERE s.trace_id = t.id AND s.tool_id IS NOT NULL) >= ?
                """,
                (rs, rowNum) -> new TraceInfo(
                        rs.getString("id"),
                        rs.getString("user_message"),
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
            float[] vector = embeddingRouter.embed(entry.getValue(), EmbeddingUseCase.MEMORY, null, null);
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
        String prompt = promptRegistry.render("memory/procedural-extraction",
                Map.of("combinedSequences", combinedSequences));
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalStateException("程序巩固 Prompt 渲染为空");
        }

        log.debug("程序巩固: 发起 JSON 模板提炼调用, promptChars={}, clusterSize={}",
                prompt.length(), cluster.size());
        LlmResponse response = Objects.requireNonNull(generationRouter.call(
                LlmScene.KNOWLEDGE_EXTRACTION,
                prompt,
                null,
                null,
                null,
                GenerationCapability.CHAT,
                null), "程序巩固 LLM 响应不能为空");
        if (response.content() == null || response.content().isBlank()) {
            throw new IllegalStateException("程序巩固 LLM 响应内容不能为空");
        }
        var extraction = Objects.requireNonNull(
                JsonOutputParser.parse(response.content(), TemplateExtraction.class),
                "程序巩固 LLM JSON 不能为空");
        Boolean createTemplate = requireCreateTemplateDecision(extraction);
        if (!createTemplate) {
            requireRejectedTemplateShape(extraction);
            log.debug("程序巩固: LLM 判定聚类不足以形成稳定模板, clusterSize={}", cluster.size());
            return TemplateResult.SKIPPED;
        }

        var name = requireCanonicalText("name", extraction.name());
        var description = requireCanonicalText("description", extraction.description());
        var triggerIntent = requireCanonicalText("triggerIntent", extraction.triggerIntent());
        var steps = requireSteps(extraction.steps());

        // 去重检查：triggerIntent 向量相似度 ≥ 0.9 视为同一模板
        if (isDuplicateTemplate(triggerIntent)) {
            log.debug("程序巩固: 模板已存在（去重命中）, name={}", name);
            return TemplateResult.UPDATED;
        }

        var sourceTraceIds = cluster.stream().map(TraceInfo::traceId).toList();
        Instant now = Instant.now();

        // 初始可靠性来自源证据：聚类的源轨迹均为成功执行（queryEligibleTraces 已过滤 success=1），
        // 因此模板代表一个已被观察到成功 N 次的模式。以 successRate=1.0、useCount=源轨迹数 初始化，
        // 使其立即满足 isReliable 门槛可被 IntentMatcher 匹配注入；后续真实使用经 recordExecution
        // 按实际成败动态修正。否则模板恒为 reliability=0/useCount=0 → 永不被匹配 → 永不积累使用 →
        // 学习闭环（巩固→匹配注入→执行→反馈）在第二环断裂。
        int initialUseCount = Math.max(sourceTraceIds.size(), 1);
        float initialSuccessRate = 1.0f;

        var template = new ProcedureTemplate(
                UUID.randomUUID().toString(),
                name,
                description,
                triggerIntent,
                steps,
                Map.of(),
                initialSuccessRate,
                initialUseCount,
                null,
                sourceTraceIds,
                now,
                now,
                null, null);

        proceduralMemory.save(template);
        log.info("程序巩固: 新模板已保存, name={}, steps={}, sources={}, initUseCount={}",
                template.name(), steps.size(), sourceTraceIds.size(), initialUseCount);

        return TemplateResult.CREATED;
    }

    /**
     * 去重检查：通过 EmbeddingRouter 计算 triggerIntent 向量，
     * 与已有模板的 triggerIntent 向量比较相似度。
     *
     * @param triggerIntent 新模板的触发意图文本
     * @return 如果已存在相似模板返回 true
     */
    private boolean isDuplicateTemplate(String triggerIntent) {
        triggerIntent = requireCanonicalText("triggerIntent", triggerIntent);
        float[] newVector = embeddingRouter.embed(triggerIntent, EmbeddingUseCase.MEMORY, null, null);
        requireEmbeddingVector("新模板触发意图向量", newVector);

        // 查询所有已有模板的 triggerIntent
        var existingTemplates = jdbcTemplate.query(
                "SELECT template_id, trigger_intent FROM procedure_templates",
                (rs, rowNum) -> new String[]{rs.getString("template_id"), rs.getString("trigger_intent")});

        for (var existing : existingTemplates) {
            var existingId = requireCanonicalText("existing.template_id", existing[0]);
            var existingTriggerIntent = requireCanonicalText("existing.trigger_intent", existing[1]);
            float[] existingVector = embeddingRouter.embed(existingTriggerIntent, EmbeddingUseCase.MEMORY, null, null);
            requireEmbeddingVector("已有模板触发意图向量:" + existingId, existingVector);
            float similarity = cosineSimilarity(newVector, existingVector);
            if (similarity >= DEDUP_SIMILARITY_THRESHOLD) {
                log.debug("程序巩固: 去重命中, existingId={}, similarity={}", existingId, similarity);
                return true;
            }
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
        return Optional.of(Instant.parse(results.getFirst()));
    }

    /**
     * 记录巩固日志到 memory_consolidation_log 表。
     */
    private void logConsolidation(ConsolidationStats stats) {
        int inserted = jdbcTemplate.update(
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
        if (inserted != 1) {
            throw new IllegalStateException("程序巩固日志写入失败, inserted=" + inserted);
        }
    }

    /**
     * 计算两个向量的余弦相似度。
     */
    private float cosineSimilarity(float[] a, float[] b) {
        requireEmbeddingVector("向量A", a);
        requireEmbeddingVector("向量B", b);
        if (a.length != b.length) {
            throw new IllegalStateException("程序巩固: 向量维度不一致, left=" + a.length + ", right=" + b.length);
        }
        float dotProduct = 0.0f, normA = 0.0f, normB = 0.0f;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        float denominator = (float) (Math.sqrt(normA) * Math.sqrt(normB));
        if (denominator == 0.0f) {
            throw new IllegalStateException("程序巩固: 向量范数不能为 0");
        }
        return dotProduct / denominator;
    }

    private static Boolean requireCreateTemplateDecision(TemplateExtraction extraction) {
        if (extraction.createTemplate() == null) {
            throw new IllegalStateException("程序巩固 LLM 响应缺少 createTemplate 字段");
        }
        return extraction.createTemplate();
    }

    private static void requireRejectedTemplateShape(TemplateExtraction extraction) {
        if (extraction.name() != null
                || extraction.description() != null
                || extraction.triggerIntent() != null) {
            throw new IllegalStateException("程序巩固 LLM 拒绝模板时模板字段必须为 null");
        }
        if (extraction.steps() == null) {
            throw new IllegalStateException("程序巩固 LLM 响应缺少 steps 字段");
        }
        if (!extraction.steps().isEmpty()) {
            throw new IllegalStateException("程序巩固 LLM 拒绝模板时 steps 必须为空数组");
        }
    }

    private static String requireCanonicalText(String fieldName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("程序巩固 LLM 响应缺少 " + fieldName + " 字段");
        }
        if (!value.equals(value.strip())) {
            throw new IllegalStateException("程序巩固 LLM 响应字段存在首尾空白: " + fieldName);
        }
        return value;
    }

    private static List<TemplateStep> requireSteps(List<StepExtraction> extractedSteps) {
        if (extractedSteps == null) {
            throw new IllegalStateException("程序巩固 LLM 响应缺少 steps 字段");
        }
        if (extractedSteps.isEmpty()) {
            throw new IllegalStateException("程序巩固 LLM 响应 steps 不能为空");
        }
        var steps = new ArrayList<TemplateStep>(extractedSteps.size());
        for (int i = 0; i < extractedSteps.size(); i++) {
            var step = Objects.requireNonNull(
                    extractedSteps.get(i),
                    "程序巩固 LLM 响应 steps[" + i + "] 不能为空");
            var parameterTemplate = requireParameterTemplate(i, step.parameterTemplate());
            steps.add(new TemplateStep(
                    i + 1,
                    requireCanonicalText("steps[" + i + "].toolId", step.toolId()),
                    requireCanonicalText("steps[" + i + "].action", step.action()),
                    parameterTemplate,
                    requireCanonicalText("steps[" + i + "].description", step.description()),
                    false));
        }
        return List.copyOf(steps);
    }

    private static Map<String, String> requireParameterTemplate(
            int stepIndex,
            Map<String, String> parameterTemplate) {
        if (parameterTemplate == null) {
            throw new IllegalStateException(
                    "程序巩固 LLM 响应缺少 steps[" + stepIndex + "].parameterTemplate 字段");
        }
        var normalized = new LinkedHashMap<String, String>();
        for (var entry : parameterTemplate.entrySet()) {
            var key = requireCanonicalText(
                    "steps[" + stepIndex + "].parameterTemplate.key", entry.getKey());
            var value = requireCanonicalText(
                    "steps[" + stepIndex + "].parameterTemplate." + key, entry.getValue());
            normalized.put(key, value);
        }
        return Map.copyOf(normalized);
    }

    private static void requireEmbeddingVector(String label, float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalStateException("程序巩固: " + label + "不能为空");
        }
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalStateException("程序巩固: " + label + "包含非法数值");
            }
        }
    }

    // ========== 内部 record ==========

    /** 执行轨迹基本信息。 */
    private record TraceInfo(String traceId, String goal, String createdAt) {}

    /** LLM 模板提炼结果。 */
    private record TemplateExtraction(
            Boolean createTemplate,
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
