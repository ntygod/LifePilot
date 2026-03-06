package com.lifepilot.memory.semantic;

import com.lifepilot.llm.LlmRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    public RealtimeExtractor(LlmRouter llmRouter,
                             SemanticMemory semanticMemory) {
        this.llmRouter = llmRouter;
        this.semanticMemory = semanticMemory;
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

        // 3. 逐条执行 AUDN 操作
        int successCount = 0;
        for (var decision : decisions) {
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

    /** 调用 LLM 获取 AUDN 决策列表。 */
    private List<AudnDecision> callLlmForAudnDecisions(String conversationText) {
        String prompt = buildAudnPrompt(conversationText);
        // 使用 List.class 获取结构化输出，LLM 返回 AudnDecision 列表
        var result = llmRouter.callEntity("knowledge_extraction", prompt, AudnDecisionList.class);
        return result != null ? result.decisions() : List.of();
    }

    /** 构建 AUDN 提示词。 */
    private String buildAudnPrompt(String conversationText) {
        return """
                分析以下对话，提取关键实体信息。对每个实体判断应执行的操作：
                - ADD: 新发现的实体信息
                - UPDATE: 已知实体的信息更新
                - DELETE: 不再有效的实体信息
                - NOOP: 无需操作
                
                实体类型包括: PERSON, ORGANIZATION, PLACE, EVENT, PROJECT, TOPIC, PREFERENCE, HABIT, GOAL, SKILL, CUSTOM
                
                对话内容:
                %s
                
                请返回 JSON 格式的决策列表。如果没有需要提取的实体，返回空列表。
                """.formatted(conversationText);
    }

    /** 执行单条 AUDN 决策。 */
    private void executeDecision(AudnDecision decision, String sessionId) {
        switch (decision.operation()) {
            case ADD -> executeAdd(decision, sessionId);
            case UPDATE -> executeUpdate(decision, sessionId);
            case DELETE -> executeDelete(decision);
            case NOOP -> { /* 跳过 */ }
        }
    }

    /** 执行 ADD 操作：创建新实体。 */
    private void executeAdd(AudnDecision decision, String sessionId) {
        var now = Instant.now();
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
                0.8f, 0.5f, 0, null, now, now);
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
        var updated = new TemporalEntity(
                null, decision.entityType(), decision.entityName(),
                decision.description() != null ? decision.description() : old.description(),
                mergedProps,
                old.version(), true, old.validFrom(), null, null,
                old.extractionConfidence(), old.importanceScore(),
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
