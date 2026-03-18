package com.lifepilot.memory.semantic;

import com.lifepilot.llm.LlmRequest;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 实时实体提取器 — 每轮对话后异步提取关键实体写入 L3。
 *
 * <p>采用 Mem0 AUDN 模式：通过 LLM 结构化输出判断每条信息的操作类型
 * （Add/Update/Delete/Noop），然后执行对应的 L3 写入操作。</p>
 *
 * <p>在 Virtual Thread 中异步执行，不阻塞 AgentLoop 的响应返回。
 * 任何异常均静默跳过，不影响主流程。</p>
 *
 * @author zsg
 * @since 2026-03-05
 */
public class RealtimeExtractor {

    private static final Logger log = LoggerFactory.getLogger(RealtimeExtractor.class);

    private final LlmRouter llmRouter;
    private final SemanticMemory semanticMemory;
    private final ExtractionValidator extractionValidator;
    private final int extractionTimeoutSeconds;
    private final int existingEntitySummaryLimit;
    private final JdbcTemplate jdbcTemplate;
    private final PromptRegistry promptRegistry;

    public RealtimeExtractor(LlmRouter llmRouter,
                             SemanticMemory semanticMemory,
                             MemoryProperties properties,
                             ExtractionValidator extractionValidator,
                             JdbcTemplate jdbcTemplate,
                             PromptRegistry promptRegistry) {
        this.llmRouter = llmRouter;
        this.semanticMemory = semanticMemory;
        this.extractionValidator = extractionValidator;
        this.extractionTimeoutSeconds = properties.getExtraction().getTimeoutSeconds();
        this.existingEntitySummaryLimit = properties.getExtraction().getExistingEntitySummaryLimit();
        this.jdbcTemplate = jdbcTemplate;
        this.promptRegistry = promptRegistry;
    }

    /**
     * 异步提取对话中的关键实体并写入 L3。
     *
     * @param sessionId   会话 ID
     * @param userMessage 用户消息
     * @param aiResponse  AI 响应
     */
    public void extractAsync(String sessionId, String userMessage, String aiResponse) {
        Thread.startVirtualThread(() -> {
            try {
                extract(sessionId, userMessage, aiResponse);
            } catch (Exception e) {
                log.warn("实时实体提取失败，静默跳过: sessionId={}, error={}",
                        sessionId, e.getMessage());
            }
        });
    }

    /**
     * 同步提取逻辑（供测试调用）。
     *
     * @param sessionId   会话 ID
     * @param userMessage 用户消息
     * @param aiResponse  AI 响应
     */
    void extract(String sessionId, String userMessage, String aiResponse) {
        if (userMessage == null || userMessage.isBlank()) return;

        // 1. 拼接对话文本
        String conversationText = buildConversationText(userMessage, aiResponse);

        // 2. 调用 LLM 获取 AUDN 决策列表
        var decisions = callLlmForAudnDecisions(conversationText);
        if (decisions == null || decisions.isEmpty()) {
            log.debug("实时实体提取: 无需操作, sessionId={}", sessionId);
            return;
        }

        // 3. 提取后验证
        var validDecisions = extractionValidator.validate(decisions);
        if (validDecisions.isEmpty()) {
            log.debug("实时实体提取: 验证后无有效决策, sessionId={}", sessionId);
            return;
        }

        // 4. 逐条执行 AUDN 操作
        int successCount = 0;
        for (var decision : validDecisions) {
            try {
                executeDecision(decision, sessionId);
                successCount++;
            } catch (Exception e) {
                log.warn("AUDN 单条决策执行失败，跳过: operation={}, entityName={}, error={}",
                        decision.operation(), decision.entityName(), e.getMessage());
            }
        }
        log.debug("实时实体提取完成: sessionId={}, total={}, success={}",
                sessionId, decisions.size(), successCount);
    }

    /** 拼接用户消息和 AI 响应为对话文本。 */
    private String buildConversationText(String userMessage, String aiResponse) {
        var sb = new StringBuilder();
        sb.append("用户: ").append(userMessage);
        if (aiResponse != null && !aiResponse.isBlank()) {
            sb.append("\nAI: ").append(aiResponse);
        }
        return sb.toString();
    }

    /** 调用 LLM 获取 AUDN 决策列表（带独立超时控制）。 */
    private List<AudnDecision> callLlmForAudnDecisions(String conversationText) {
        String prompt = buildAudnPrompt(conversationText);
        try {
            // 使用 Virtual Thread 执行器避免阻塞 ForkJoinPool.commonPool()
            var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
            var result = CompletableFuture.supplyAsync(() ->
                            llmRouter.callEntity(LlmRequest.of("knowledge_extraction", prompt), AudnDecisionList.class),
                            executor)
                    .orTimeout(extractionTimeoutSeconds, TimeUnit.SECONDS)
                    .join();
            return result != null ? result.decisions() : List.of();
        } catch (Exception e) {
            // CompletableFuture.join() 包装为 CompletionException，解包判断是否超时
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof TimeoutException) {
                log.warn("AUDN 实体提取超时: timeoutSeconds={}", extractionTimeoutSeconds);
            } else {
                log.warn("AUDN 实体提取 LLM 调用失败: error={}", cause.getMessage());
            }
            return List.of();
        }
    }

    /** 构建增强版 AUDN 提示词：注入已有实体上下文 + 提取标准 + 评分要求。 */
    private String buildAudnPrompt(String conversationText) {
        String existingSummary = buildExistingEntitySummary();
        return promptRegistry.render("semantic/entity-extraction", Map.of(
                "existingSummary", existingSummary,
                "conversationText", conversationText));
    }

    /** 构建已有实体摘要，按 importanceScore 降序截取前 N 条。 */
    private String buildExistingEntitySummary() {
        try {
            var allCurrent = semanticMemory.findAllCurrent();
            if (allCurrent.isEmpty()) {
                return "（暂无已有实体）";
            }
            var sorted = allCurrent.stream()
                    .sorted(Comparator.comparing(TemporalEntity::importanceScore).reversed())
                    .limit(existingEntitySummaryLimit)
                    .toList();
            var sb = new StringBuilder();
            for (var entity : sorted) {
                sb.append("- ").append(entity.name())
                  .append(" [").append(entity.type().name()).append("]");
                if (entity.description() != null && !entity.description().isBlank()) {
                    sb.append(" — ").append(entity.description());
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("AUDN 提示词: 已有实体列表查询失败，降级为不注入, error={}", e.getMessage());
            return "（已有实体列表暂不可用）";
        }
    }

    /** 执行单条 AUDN 决策。 */
    private void executeDecision(AudnDecision decision, String sessionId) {
        switch (decision.operation()) {
            case ADD -> {
                try {
                    executeAdd(decision, sessionId);
                    logExtractionEvent(sessionId, decision, true, null);
                } catch (Exception e) {
                    logExtractionEvent(sessionId, decision, false, e.getMessage());
                    throw e;
                }
            }
            case UPDATE -> {
                try {
                    executeUpdate(decision, sessionId);
                    logExtractionEvent(sessionId, decision, true, null);
                } catch (Exception e) {
                    logExtractionEvent(sessionId, decision, false, e.getMessage());
                    throw e;
                }
            }
            case DELETE -> {
                try {
                    executeDelete(decision);
                    logExtractionEvent(sessionId, decision, true, null);
                } catch (Exception e) {
                    logExtractionEvent(sessionId, decision, false, e.getMessage());
                    throw e;
                }
            }
            case NOOP -> { /* 跳过 */ }
        }
    }

    /** 执行 ADD 操作：创建新实体。 */
    private void executeAdd(AudnDecision decision, String sessionId) {
        var now = Instant.now();
        float confidence = safeFloat(decision.extractionConfidence(), 0.5f);
        float importance = safeFloat(decision.importanceScore(), 0.5f);
        // source_conversation_id 传 null：实时提取在每轮对话后立即执行，
        // 此时 conversations 记录尚未创建（L1→L2 flush 在会话结束时才触发），
        // 传 sessionId 会违反 FK 约束。该列允许 NULL。
        var entity = new TemporalEntity(
                UUID.randomUUID().toString(),
                decision.entityType(),
                decision.entityName(),
                decision.description(),
                decision.properties() != null ? decision.properties() : Map.of(),
                1, true, now, null, null,
                confidence,
                importance,
                0, null, now, now);
        semanticMemory.upsertWithConflictDetection(entity, null);
        log.debug("AUDN ADD: name={}, type={}", decision.entityName(), decision.entityType());
    }

    /** 执行 UPDATE 操作：查找已有实体并更新。 */
    private void executeUpdate(AudnDecision decision, String sessionId) {
        var existing = semanticMemory.findCurrentByNameAndType(
                decision.entityName(), decision.entityType());
        if (existing.isEmpty()) {
            // 找不到已有实体，降级为 ADD
            log.debug("AUDN UPDATE 降级为 ADD: 未找到已有实体, name={}", decision.entityName());
            executeAdd(decision, sessionId);
            return;
        }
        // 构建更新后的实体，通过 upsertWithConflictDetection 版本化更新
        var old = existing.get();
        var mergedProps = new java.util.HashMap<>(old.properties());
        if (decision.properties() != null) {
            mergedProps.putAll(decision.properties());
        }
        // 使用 LLM 输出的 scores：confidence 取新值，importance 取较大值
        float newConfidence = safeFloat(decision.extractionConfidence(), 0.5f);
        float newImportance = safeFloat(decision.importanceScore(), 0.5f);

        var updated = new TemporalEntity(
                null, decision.entityType(), decision.entityName(),
                decision.description() != null ? decision.description() : old.description(),
                mergedProps,
                old.version(), true, old.validFrom(), null, null,
                newConfidence,
                Math.max(old.importanceScore(), newImportance),
                old.accessCount(), old.lastAccessedAt(),
                old.createdAt(), Instant.now());
        semanticMemory.upsertWithConflictDetection(updated, null);
        log.debug("AUDN UPDATE: name={}, type={}", decision.entityName(), decision.entityType());
    }

    /** 执行 DELETE 操作：将匹配实体标记为非当前。 */
    private void executeDelete(AudnDecision decision) {
        var existing = semanticMemory.findCurrentByNameAndType(
                decision.entityName(), decision.entityType());
        if (existing.isEmpty()) {
            log.debug("AUDN DELETE 跳过: 未找到匹配实体, name={}", decision.entityName());
            return;
        }
        semanticMemory.archive(existing.get());
        log.debug("AUDN DELETE: name={}, type={}", decision.entityName(), decision.entityType());
    }

    /** 记录提取事件日志到 extraction_event_log 表。 */
    private void logExtractionEvent(String sessionId, AudnDecision decision,
                                     boolean success, @Nullable String errorMessage) {
        try {
            jdbcTemplate.update(
                "INSERT INTO extraction_event_log(id, session_id, operation, entity_name, entity_type, extraction_confidence, importance_score, success, error_message, created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),
                sessionId,
                decision.operation().name(),
                decision.entityName(),
                decision.entityType().name(),
                safeFloat(decision.extractionConfidence(), 0.0f),
                safeFloat(decision.importanceScore(), 0.0f),
                success ? 1 : 0,
                errorMessage,
                Instant.now().toString());
        } catch (Exception e) {
            log.warn("提取事件日志写入失败: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    /** @Nullable Float 安全拆箱，null 时返回默认值。 */
    private static float safeFloat(@Nullable Float value, float defaultValue) {
        return value != null ? value : defaultValue;
    }

    /**
     * AUDN 决策列表包装 — 用于 LLM 结构化输出反序列化。
     *
     * @param decisions 决策列表
     */
    public record AudnDecisionList(List<AudnDecision> decisions) {
        public AudnDecisionList {
            decisions = decisions != null ? List.copyOf(decisions) : List.of();
        }
    }
}
