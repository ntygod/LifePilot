package com.lifepilot.memory.experience;

import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.retrieval.VectorSearcher;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/**
 * 子任务反思器 — 从连续工具调用序列中提取细粒度的工具使用经验。
 *
 * <p>在 asyncPostProcess 的 Virtual Thread 中调用，从 ToolCall → Observation 步骤对中
 * 提取"工具选择是否正确"、"参数是否最优"、"调用顺序是否合理"三个维度的微观经验。</p>
 *
 * @author zsg
 * @since 2026-03-18
 */
public class SubtaskReflector {

    private static final Logger log = LoggerFactory.getLogger(SubtaskReflector.class);
    private static final String PROMPT_KEY = "memory/subtask-reflection";

    private final SemanticMemory semanticMemory;
    private final VectorSearcher vectorSearcher;
    private final GenerationRouter generationRouter;
    private final PromptRegistry promptRegistry;
    private final MemoryProperties.Experience.Subtask config;
    private final float dedupThreshold;

    public SubtaskReflector(SemanticMemory semanticMemory,
                            VectorSearcher vectorSearcher,
                            GenerationRouter generationRouter,
                            PromptRegistry promptRegistry,
                            MemoryProperties memoryProperties) {
        this.semanticMemory = semanticMemory;
        this.vectorSearcher = vectorSearcher;
        this.generationRouter = generationRouter;
        this.promptRegistry = promptRegistry;
        this.config = memoryProperties.getExperience().getSubtask();
        this.dedupThreshold = memoryProperties.getExperience().getDedupSimilarityThreshold();
    }

    /**
     * 从 ReactAgentState 中提取子任务级经验。
     * 在 asyncPostProcess 的 Virtual Thread 中调用。
     *
     * @param state Agent 最终状态
     */
    public void reflect(ReactAgentState state) {
        if (!config.isEnabled()) {
            log.debug("子任务反思: 功能已关闭");
            return;
        }

        try {
            // 提取连续 ToolCall → Observation 步骤对
            var pairs = extractToolObservationPairs(state.steps());

            // 检查序列长度
            if (pairs.size() < config.getMinToolSequence()) {
                log.debug("子任务反思: 工具调用序列长度不足, size={}, min={}",
                        pairs.size(), config.getMinToolSequence());
                return;
            }

            // 检查至少一个成功 Observation
            boolean hasSuccess = pairs.stream()
                    .anyMatch(p -> p.observation().success());
            if (!hasSuccess) {
                log.debug("子任务反思: 无成功 Observation，跳过");
                return;
            }

            // 格式化工具序列并截断
            String formattedSequence = formatToolSequence(pairs);

            // 渲染提示词
            String prompt = promptRegistry.render(PROMPT_KEY, Map.of(
                    "goal", state.goal() != null ? state.goal() : "",
                    "toolSequence", formattedSequence
            ));

            // 调用 LLM
            ExperienceRecord record;
            try {
            record = generationRouter.callEntity(
                        LlmScene.CHAT,
                        prompt,
                        ExperienceRecord.class,
                        null,
                        null,
                        null);
            } catch (Exception e) {
                log.warn("子任务反思: LLM 调用失败, error={}", e.getMessage());
                return;
            }

            if (record == null) {
                log.warn("子任务反思: LLM 返回空结果");
                return;
            }

            // 验证结果
            if (record.scenario() == null || record.scenario().isBlank()
                    || record.strategy() == null || record.strategy().isBlank()) {
                log.debug("子任务反思: scenario 或 strategy 为空，丢弃");
                return;
            }

            // 去重检查
            String textRepresentation = record.scenario() + ": " + record.strategy();
            var similar = vectorSearcher.searchEntities(textRepresentation, 1, dedupThreshold);
            if (!similar.isEmpty()) {
                log.debug("子任务反思: 去重命中，跳过, similarEntityId={}",
                        similar.getFirst().entityId());
                return;
            }

            // 构建并写入 TemporalEntity
            persistSubtaskExperience(record, state);

        } catch (Exception e) {
            log.warn("子任务反思失败: error={}", e.getMessage());
        }
    }

    /**
     * 提取连续的 ToolCall → Observation 步骤对。
     *
     * @param steps 所有步骤列表
     * @return 连续的步骤对列表
     */
    private List<ToolObservationPair> extractToolObservationPairs(List<ReactStep> steps) {
        var pairs = new ArrayList<ToolObservationPair>();
        for (int i = 0; i < steps.size() - 1; i++) {
            if (steps.get(i) instanceof ReactStep.ToolCall tc
                    && steps.get(i + 1) instanceof ReactStep.Observation obs) {
                pairs.add(new ToolObservationPair(tc, obs));
            }
        }
        return pairs;
    }

    /**
     * 格式化工具调用序列为文本，截断到 maxInputTokens。
     *
     * @param pairs 步骤对列表
     * @return 格式化后的文本
     */
    private String formatToolSequence(List<ToolObservationPair> pairs) {
        var sb = new StringBuilder();
        int maxTokens = config.getMaxInputTokens();

        for (var pair : pairs) {
            String line = String.format("ToolCall: %s(%s) → Observation: %s: %s\n",
                    pair.toolCall().toolId(),
                    pair.toolCall().inputJson(),
                    pair.observation().success() ? "成功" : "失败",
                    pair.observation().output());

            // 估算 Token：字符串长度 / 4 作为粗略估计
            int currentTokens = estimateTokens(sb.toString());
            int lineTokens = estimateTokens(line);

            if (currentTokens + lineTokens > maxTokens) {
                // 截断：尝试添加部分内容
                int remainingTokens = maxTokens - currentTokens;
                if (remainingTokens > 0) {
                    int maxChars = remainingTokens * 4;
                    sb.append(line, 0, Math.min(line.length(), maxChars));
                }
                break;
            }
            sb.append(line);
        }

        return sb.toString();
    }

    /**
     * 估算文本 Token 数 — 简单启发式：字符串长度 / 4。
     *
     * @param text 输入文本
     * @return 估算的 Token 数
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, text.length() / 4);
    }

    /** 持久化子任务经验实体。 */
    private void persistSubtaskExperience(ExperienceRecord record, ReactAgentState state) {
        var now = Instant.now();
        String name = record.scenario().length() > 100
                ? record.scenario().substring(0, 100)
                : record.scenario();

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("lessons", record.lessons() != null ? record.lessons() : List.of());
        props.put("applicableConditions", record.applicableConditions() != null
                ? record.applicableConditions() : List.of());
        props.put("toolsUsed", record.toolsUsed() != null ? record.toolsUsed() : List.of());
        props.put("success", record.success());
        props.put("subtask", true);
        props.put("executionContext", ExecutionContext.infer(state).name());
        props.put("effectivenessScore", 0.0f);
        props.put("injectionCount", 0);
        props.put("positiveOutcomes", 0);
        props.put("negativeOutcomes", 0);

        var entity = new TemporalEntity(
                UUID.randomUUID().toString(),
                EntityType.EXPERIENCE,
                name,
                record.strategy(),
                props,
                1,
                true,
                now,
                null,
                null,
                0.8f,
                config.getInitialImportance(),
                0,
                null,
                now,
                now
        );

        semanticMemory.upsertWithConflictDetection(entity, "subtask-reflection");
        vectorSearcher.upsertEntityVector(entity.id(), entity.textRepresentation());

        log.info("子任务反思: 子任务经验已写入, entityId={}, name={}", entity.id(), name);
    }

    /** ToolCall → Observation 步骤对。 */
    private record ToolObservationPair(ReactStep.ToolCall toolCall, ReactStep.Observation observation) {}
}
