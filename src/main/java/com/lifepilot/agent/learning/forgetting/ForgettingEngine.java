package com.lifepilot.agent.learning.forgetting;

import com.lifepilot.llm.LlmScene;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.governance.lifecycle.ChangeSource;
import com.lifepilot.memory.store.support.SqliteBusyRetry;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.prompt.PromptRegistry;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * MaRS 遗忘引擎 — 定时执行认知遗忘，维持记忆系统健康容量。
 *
 * <p>核心流程：获取当前实体 → 过滤受保护实体 → HybridPolicy 四阶段遗忘 →
 * 执行遗忘动作（归档/压缩/删除）→ 记录遗忘日志。</p>
 *
 * <p>受保护实体（永不遗忘）：由 {@code protectedTypes} 和 {@code protectionThreshold} 配置控制。</p>
 *
 * @author zsg
 * @since 2026-03-01
 */
public class ForgettingEngine {

    private static final Logger log = LoggerFactory.getLogger(ForgettingEngine.class);

    private final SemanticMemory semanticMemory;
    @Nullable
    private final GenerationRouter generationRouter;
    private final JdbcTemplate jdbcTemplate;
    private final AgentLearningProperties properties;
    private final PromptRegistry promptRegistry;
    private final HybridPolicy hybridPolicy;
    private final ForgettingPriority priorityCalculator;

    public ForgettingEngine(SemanticMemory semanticMemory,
                            @Nullable GenerationRouter generationRouter,
                            JdbcTemplate jdbcTemplate,
                            AgentLearningProperties properties,
                            PromptRegistry promptRegistry) {
        this.semanticMemory = semanticMemory;
        this.generationRouter = generationRouter;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.promptRegistry = promptRegistry;

        // 预创建策略实例，forget() 中复用
        var forgettingConfig = properties.getForgetting();
        var fifo = new FifoPolicy(forgettingConfig);
        var lru = new LruPolicy(forgettingConfig);
        var decay = new PriorityDecayPolicy(forgettingConfig);
        var reflection = new ReflectionSummaryPolicy(generationRouter, forgettingConfig);
        this.hybridPolicy = new HybridPolicy(fifo, lru, decay, reflection);
        this.priorityCalculator = new ForgettingPriority(forgettingConfig);
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

        // 3. 使用预创建的策略实例执行 HybridPolicy 四阶段遗忘
        var selected = hybridPolicy.selectForForgetting(candidates, config.getMaxForgetPerRun());
        if (selected.isEmpty()) {
            log.debug("遗忘引擎: HybridPolicy 未选中任何实体");
            return 0;
        }

        // 4. 对每个选中实体执行遗忘动作
        int forgottenCount = 0;

        for (var entity : selected) {
            try {
                var result = executeForgetAction(entity, config);
                var priority = priorityCalculator.calculate(entity);
                logForgetting(entity, hybridPolicy.name(), result.action(), priority, result.compressionSummary());
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
     *   <li>实体类型在 {@code protectedTypes} 配置列表中（默认 PREFERENCE / HABIT / GOAL）</li>
     *   <li>importanceScore ≥ {@code protectionThreshold} 配置值（默认 0.9）</li>
     *   <li>accessCount ≥ {@code highAccessCountProtection} 配置值（默认 10）— 高频访问保护</li>
     *   <li>最近 {@code recentAccessProtectionDays} 天内被访问过（默认 7 天）— 近期访问保护</li>
     * </ul></p>
     *
     * @param entity 目标实体
     * @return 是否受保护
     */
    private boolean isProtected(TemporalEntity entity) {
        var config = properties.getForgetting();
        if (config.getProtectedTypes().contains(entity.type().name())) {
            return true;
        }
        if (entity.importanceScore() >= config.getProtectionThreshold()) {
            return true;
        }
        // 高频访问保护：访问次数达到阈值的实体受保护
        if (entity.accessCount() >= config.getHighAccessCountProtection()) {
            return true;
        }
        // 近期访问保护：在保护窗口内被访问过的实体受保护
        if (entity.lastAccessedAt() != null) {
            var protectionWindow = Instant.now().minus(config.getRecentAccessProtectionDays(), ChronoUnit.DAYS);
            if (entity.lastAccessedAt().isAfter(protectionWindow)) {
                return true;
            }
        }
        return false;
    }

    /** 遗忘动作执行结果 — 携带动作名称和可选的压缩摘要。 */
    private record ForgetActionResult(String action, @Nullable String compressionSummary) {}

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
     * @return 遗忘动作结果（含动作名称和可选压缩摘要）
     */
    private ForgetActionResult executeForgetAction(TemporalEntity entity, AgentLearningProperties.Forgetting config) {
        var minImportance = config.getReflectionSummaryMinImportance();
        var maxImportance = config.getReflectionSummaryMaxImportance();

        // 中等重要度实体 + LLM 可用 → 尝试压缩
        if (entity.importanceScore() >= minImportance
                && entity.importanceScore() < maxImportance
                && generationRouter != null) {
            try {
                var prompt = buildCompressionPrompt(entity);
                // skipCache=true：每个实体压缩 prompt 仅 name/description 差异，
                // 语义缓存会按相似度张冠李戴，把首条摘要返回给后续所有实体。
                var response = generationRouter.call(
                        LlmScene.MEMORY_COMPRESSION,
                        prompt,
                        null,
                        null,
                        null,
                        GenerationCapability.CHAT,
                        null,
                        true);
                var summary = response.content();
                log.debug("遗忘引擎: 实体压缩成功, id={}, name={}, 摘要长度={}",
                        entity.id(), entity.name(), summary.length());
                // 压缩后归档原实体 — 遗忘引擎定时触发，归档来源为 CRON_EXPIRE
                SqliteBusyRetry.run(() -> semanticMemory.archive(entity, ChangeSource.CRON_EXPIRE));
                return new ForgetActionResult("COMPRESSED", summary);
            } catch (Exception e) {
                log.warn("遗忘引擎: LLM 压缩失败, 降级为归档, id={}, error={}",
                        entity.id(), e.getMessage());
                // 降级为归档 — 同样归属定时遗忘
                SqliteBusyRetry.run(() -> semanticMemory.archive(entity, ChangeSource.CRON_EXPIRE));
                return new ForgetActionResult("ARCHIVED", null);
            }
        }

        // 默认：归档 — 定时遗忘
        SqliteBusyRetry.run(() -> semanticMemory.archive(entity, ChangeSource.CRON_EXPIRE));
        return new ForgetActionResult("ARCHIVED", null);
    }

    /**
     * 构建 LLM 记忆压缩提示词。
     *
     * @param entity 目标实体
     * @return 压缩提示词
     */
    private String buildCompressionPrompt(TemporalEntity entity) {
        return promptRegistry.render("memory/entity-compression", Map.of(
                "name", entity.name(),
                "type", entity.type().name(),
                "description", entity.description() != null ? entity.description() : "无",
                "properties", entity.properties().toString()));
    }

    /**
     * 记录遗忘日志到 forgetting_log 表。
     *
     * @param entity              被遗忘的实体
     * @param strategy            使用的策略名称
     * @param action              执行的动作
     * @param priority            遗忘优先级
     * @param compressionSummary  LLM 压缩摘要（COMPRESSED 时非空，ARCHIVED 时为 null）
     */
    private void logForgetting(TemporalEntity entity, String strategy,
                                String action, float priority,
                                @Nullable String compressionSummary) {
        jdbcTemplate.update(
                "INSERT INTO forgetting_log(id, entity_id, entity_name, strategy, action_taken, forgetting_priority, reason, compression_summary, created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),
                entity.id(),
                entity.name(),
                strategy,
                action,
                priority,
                "MaRS 遗忘引擎自动执行",
                compressionSummary,
                Instant.now().toString());
    }
}
