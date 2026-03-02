package com.lifepilot.memory.forgetting;

import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * MaRS 遗忘引擎 — 定时执行认知遗忘，维持记忆系统健康容量。
 *
 * <p>核心流程：获取当前实体 → 过滤受保护实体 → HybridPolicy 四阶段遗忘 →
 * 执行遗忘动作（归档/压缩/删除）→ 记录遗忘日志。</p>
 *
 * <p>受保护实体（永不遗忘）：PREFERENCE/HABIT/GOAL 类型、importanceScore ≥ 0.9。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public class ForgettingEngine {

    private static final Logger log = LoggerFactory.getLogger(ForgettingEngine.class);

    /** 受保护的实体类型 — 这些类型的实体永不被遗忘。 */
    private static final Set<EntityType> PROTECTED_TYPES = Set.of(
            EntityType.PREFERENCE, EntityType.HABIT, EntityType.GOAL);

    /** 受保护的重要度阈值 — importanceScore ≥ 此值的实体永不被遗忘。 */
    private static final float PROTECTION_THRESHOLD = 0.9f;

    private final SemanticMemory semanticMemory;
    @Nullable
    private final LlmRouter llmRouter;
    private final JdbcTemplate jdbcTemplate;
    private final MemoryProperties properties;

    public ForgettingEngine(SemanticMemory semanticMemory,
                            @Nullable LlmRouter llmRouter,
                            JdbcTemplate jdbcTemplate,
                            MemoryProperties properties) {
        this.semanticMemory = semanticMemory;
        this.llmRouter = llmRouter;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    /**
     * 定时遗忘入口 — 由 Spring Scheduler 按 Cron 表达式触发。
     */
    @Scheduled(cron = "${lifepilot.memory.forgetting.cron}")
    public void scheduledForget() {
        log.info("遗忘引擎: 定时遗忘开始");
        try {
            int count = forget();
            log.info("遗忘引擎: 定时遗忘完成, 遗忘实体数={}", count);
        } catch (Exception e) {
            log.error("遗忘引擎: 定时遗忘异常", e);
        }
    }

    /**
     * 执行一次遗忘流程。
     *
     * <p>流程：获取当前实体 → 过滤受保护实体 → HybridPolicy 选择候选 →
     * 对每个候选执行遗忘动作 → 记录日志。</p>
     *
     * @return 实际遗忘的实体数量
     */
    public int forget() {
        var config = properties.getForgetting();

        // 1. 获取所有当前实体（按 importanceScore ASC 排序）
        var allEntities = semanticMemory.findAllCurrent();
        if (allEntities.isEmpty()) {
            log.debug("遗忘引擎: 无当前实体，跳过");
            return 0;
        }

        // 2. 过滤受保护实体
        var candidates = allEntities.stream()
                .filter(entity -> !isProtected(entity))
                .toList();

        if (candidates.isEmpty()) {
            log.debug("遗忘引擎: 所有实体均受保护，跳过");
            return 0;
        }

        // 3. 创建策略实例，执行 HybridPolicy 四阶段遗忘
        var fifo = new FifoPolicy(config);
        var lru = new LruPolicy(config);
        var decay = new PriorityDecayPolicy(config);
        var reflection = new ReflectionSummaryPolicy(llmRouter, config);
        var hybrid = new HybridPolicy(fifo, lru, decay, reflection);

        var selected = hybrid.selectForForgetting(candidates, config.getMaxForgetPerRun());
        if (selected.isEmpty()) {
            log.debug("遗忘引擎: HybridPolicy 未选中任何实体");
            return 0;
        }

        // 4. 对每个选中实体执行遗忘动作
        var priorityCalculator = new ForgettingPriority(config);
        int forgottenCount = 0;

        for (var entity : selected) {
            try {
                var action = executeForgetAction(entity, config);
                var priority = priorityCalculator.calculate(entity);
                logForgetting(entity, hybrid.name(), action, priority);
                forgottenCount++;
            } catch (Exception e) {
                log.warn("遗忘引擎: 实体遗忘失败, id={}, name={}, error={}",
                        entity.id(), entity.name(), e.getMessage());
            }
        }

        log.info("遗忘引擎: 本次遗忘完成, 候选={}, 选中={}, 成功={}",
                candidates.size(), selected.size(), forgottenCount);
        return forgottenCount;
    }

    /**
     * 判断实体是否受保护（永不遗忘）。
     *
     * <p>受保护条件（满足任一即受保护）：
     * <ul>
     *   <li>实体类型为 PREFERENCE / HABIT / GOAL</li>
     *   <li>importanceScore ≥ 0.9</li>
     * </ul></p>
     *
     * @param entity 目标实体
     * @return 是否受保护
     */
    private boolean isProtected(TemporalEntity entity) {
        if (PROTECTED_TYPES.contains(entity.type())) {
            return true;
        }
        return entity.importanceScore() >= PROTECTION_THRESHOLD;
    }

    /**
     * 对单个实体执行遗忘动作。
     *
     * <p>决策逻辑：
     * <ul>
     *   <li>importanceScore 在 [minImportance, maxImportance) 且 LLM 可用 → COMPRESSED（LLM 摘要压缩）</li>
     *   <li>其他情况 → ARCHIVED（归档）</li>
     *   <li>LLM 调用失败 → 降级为 ARCHIVED</li>
     * </ul></p>
     *
     * @param entity 目标实体
     * @param config 遗忘配置
     * @return 执行的动作名称
     */
    private String executeForgetAction(TemporalEntity entity, MemoryProperties.Forgetting config) {
        var minImportance = config.getReflectionSummaryMinImportance();
        var maxImportance = config.getReflectionSummaryMaxImportance();

        // 中等重要度实体 + LLM 可用 → 尝试压缩
        if (entity.importanceScore() >= minImportance
                && entity.importanceScore() < maxImportance
                && llmRouter != null) {
            try {
                var prompt = buildCompressionPrompt(entity);
                var response = llmRouter.call(LlmScene.MEMORY_COMPRESSION, prompt, null);
                var summary = response.content();
                log.debug("遗忘引擎: 实体压缩成功, id={}, name={}, 摘要长度={}",
                        entity.id(), entity.name(), summary.length());
                // 压缩后归档原实体
                semanticMemory.archive(entity);
                return "COMPRESSED";
            } catch (Exception e) {
                log.warn("遗忘引擎: LLM 压缩失败, 降级为归档, id={}, error={}",
                        entity.id(), e.getMessage());
                // 降级为归档
                semanticMemory.archive(entity);
                return "ARCHIVED";
            }
        }

        // 默认：归档
        semanticMemory.archive(entity);
        return "ARCHIVED";
    }

    /**
     * 构建 LLM 记忆压缩提示词。
     *
     * @param entity 目标实体
     * @return 压缩提示词
     */
    private String buildCompressionPrompt(TemporalEntity entity) {
        return """
                任务：将记忆实体压缩为一句话摘要
                
                目标：保留核心信息，去除冗余细节，确保摘要能准确代表原实体
                
                实体信息：
                - 名称：%s
                - 类型：%s
                - 描述：%s
                - 属性：%s
                
                压缩要求：
                1. 输出单句摘要（不超过50字）
                2. 保留：实体名称、核心特征、关键关系
                3. 去除：冗余描述、重复信息、无关细节
                4. 使用简洁、准确的语言
                5. 确保摘要能独立理解，无需上下文
                
                输出格式：直接输出摘要文本，不要添加引号或标记
                """.formatted(
                entity.name(),
                entity.type().name(),
                entity.description() != null ? entity.description() : "无",
                entity.properties().toString());
    }

    /**
     * 记录遗忘日志到 forgetting_log 表。
     *
     * @param entity   被遗忘的实体
     * @param strategy 使用的策略名称
     * @param action   执行的动作
     * @param priority 遗忘优先级
     */
    private void logForgetting(TemporalEntity entity, String strategy,
                                String action, float priority) {
        jdbcTemplate.update(
                "INSERT INTO forgetting_log(id, entity_id, entity_name, strategy, action_taken, forgetting_priority, reason, created_at) VALUES(?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),
                entity.id(),
                entity.name(),
                strategy,
                action,
                priority,
                "MaRS 遗忘引擎自动执行",
                Instant.now().toString());
    }
}
