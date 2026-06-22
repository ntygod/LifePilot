package com.lifepilot.agent.learning.extraction;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.generation.support.JsonOutputParser;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.memory.consumption.quality.MemoryEvidenceKind;
import com.lifepilot.memory.consumption.quality.MemoryQualityPolicy;
import com.lifepilot.memory.consumption.quality.MemoryTrustLevel;
import com.lifepilot.memory.governance.lifecycle.ChangeSource;
import com.lifepilot.memory.governance.lifecycle.LifecycleState;
import com.lifepilot.memory.governance.lifecycle.Temporality;
import com.lifepilot.memory.governance.policy.MemoryAccessPolicy;
import com.lifepilot.memory.governance.security.MemoryInjectionDetector;
import com.lifepilot.memory.semantic.AudnDecision;
import com.lifepilot.memory.semantic.AudnOperation;
import com.lifepilot.memory.semantic.TemporalRelation;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.memory.store.scope.*;
import com.lifepilot.memory.store.support.SqliteBusyRetry;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
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
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final java.util.concurrent.ExecutorService VIRTUAL_EXECUTOR = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    /** EPHEMERAL 类记忆的默认 TTL — 7 天后 expires_at 触发 Cron 回收。 */
    private static final Duration EPHEMERAL_TTL = Duration.ofDays(7);
    /** SHORT_TERM 类记忆的默认 TTL — 30 天后 expires_at 触发 Cron 回收。 */
    private static final Duration SHORT_TERM_TTL = Duration.ofDays(30);

    private final GenerationRouter generationRouter;
    private final SemanticMemory semanticMemory;
    private final ExtractionValidator extractionValidator;
    private final int extractionTimeoutSeconds;
    private final int existingEntitySummaryLimit;
    private final JdbcTemplate jdbcTemplate;
    private final PromptRegistry promptRegistry;
    @Nullable
    private final ChatTurnMemorySnapshotRepository snapshotRepository;
    /** 时钟注入 — 用于非持久性实体自动推导 {@code expires_at}，便于单测注入固定时钟。 */
    private final Clock clock;
    private final MemoryAccessPolicy memoryAccessPolicy;
    @Nullable
    private final MemoryExtractionCandidateRepository candidateRepository;
    @Nullable
    private final MemoryInjectionDetector injectionDetector;
    /** 关系抽取步骤 — null 时跳过对话期关系抽取。 */
    @Nullable
    private final RelationExtractionStep relationExtractionStep;
    /** 对话期关系抽取开关，来自配置。 */
    private final boolean relationExtractionEnabled;

    /**
     * 唯一构造器 —— 注入全部协作依赖。
     *
     * <p>{@code candidateRepository}/{@code injectionDetector}/{@code relationExtractionStep}
     * 为 null 时对应增强能力关闭。</p>
     */
    public RealtimeExtractor(GenerationRouter generationRouter,
                             SemanticMemory semanticMemory,
                             AgentLearningProperties properties,
                             ExtractionValidator extractionValidator,
                             JdbcTemplate jdbcTemplate,
                             PromptRegistry promptRegistry,
                             @Nullable ChatTurnMemorySnapshotRepository snapshotRepository,
                             Clock clock,
                             MemoryAccessPolicy memoryAccessPolicy,
                             @Nullable MemoryExtractionCandidateRepository candidateRepository,
                             @Nullable MemoryInjectionDetector injectionDetector,
                             @Nullable RelationExtractionStep relationExtractionStep) {
        this.generationRouter = generationRouter;
        this.semanticMemory = semanticMemory;
        this.extractionValidator = extractionValidator;
        this.extractionTimeoutSeconds = properties.getExtraction().getTimeoutSeconds();
        this.existingEntitySummaryLimit = properties.getExtraction().getExistingEntitySummaryLimit();
        this.jdbcTemplate = jdbcTemplate;
        this.promptRegistry = promptRegistry;
        this.snapshotRepository = snapshotRepository;
        this.clock = clock;
        this.memoryAccessPolicy = memoryAccessPolicy;
        this.candidateRepository = candidateRepository;
        this.injectionDetector = injectionDetector;
        this.relationExtractionStep = relationExtractionStep;
        this.relationExtractionEnabled = properties.getExtraction().isRelationExtractionEnabled();
    }

    /**
     * 异步提取对话中的关键实体并写入 L3。
     *
     * @param sessionId   会话 ID
     * @param userMessage 用户消息
     * @param aiResponse  AI 响应
     */
    public void extractAsync(String sessionId, String userMessage, String aiResponse) {
        extractAsync(sessionId, null, userMessage, aiResponse);
    }

    public void extractAsync(String sessionId, @Nullable String turnId, String userMessage, String aiResponse) {
        Thread.startVirtualThread(() -> {
            try {
                extract(sessionId, turnId, userMessage, aiResponse);
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
        extract(sessionId, null, userMessage, aiResponse);
    }

    void extract(String sessionId, @Nullable String turnId, String userMessage, String aiResponse) {
        if (userMessage == null || userMessage.isBlank()) return;
        ChatTurnMemorySnapshot snapshot = resolveSnapshot(sessionId, turnId);
        MemoryWriteContext writeContext = memoryAccessPolicy.resolveAutoLearningWriteContext(snapshot, sessionId, turnId);
        if (writeContext == null) {
            log.debug("实时实体提取: 当前轮次已禁止自动学习, sessionId={}, turnId={}", sessionId, turnId);
            return;
        }

        // 1. 拼接对话文本
        String conversationText = buildConversationText(userMessage, aiResponse);

        // 2. 调用 LLM 获取 AUDN 决策列表
        MemoryReadFilter summaryReadFilter = memoryAccessPolicy.buildSummaryReadFilter(writeContext, snapshot);
        var decisions = callLlmForAudnDecisions(conversationText, summaryReadFilter);
        if (decisions == null || decisions.isEmpty()) {
            log.debug("实时实体提取: 无需操作, sessionId={}", sessionId);
            return;
        }

        // 3. 提取后验证，并记录 rejected 候选，避免质量门控阶段静默丢失审计线索。
        var validationResult = extractionValidator.validateWithResult(decisions);
        if (validationResult == null) {
            validationResult = new ExtractionValidator.ValidationResult(
                    extractionValidator.validate(decisions),
                    List.of());
        }
        if (candidateRepository != null) {
            for (var rejected : validationResult.rejectedDecisions()) {
                candidateRepository.recordRejected(
                        sessionId,
                        writeContext,
                        rejected.decision(),
                        rejected.reason());
            }
        }
        var validDecisions = validationResult.validDecisions();
        if (validDecisions.isEmpty()) {
            log.debug("实时实体提取: 验证后无有效决策, sessionId={}", sessionId);
            return;
        }

        // 4. 逐条执行 AUDN 操作
        int successCount = 0;
        // 累积本轮成功写入的实体（name → id / name → 显示串），供关系抽取阶段做名称解析与端点约束
        var turnEntityIds = new LinkedHashMap<String, String>();
        var turnEntityDisplay = new LinkedHashMap<String, String>();
        for (var decision : validDecisions) {
            String candidateId = candidateRepository != null
                    ? candidateRepository.recordValidated(sessionId, writeContext, decision)
                    : null;
            // 4.1 注入检测
            if (injectionDetector != null) {
                float trustScore = safeFloat(decision.extractionConfidence(), 0.5f);
                var detectionResult = injectionDetector.detect(
                        writeContext.spaceId(),
                        decision.description(),
                        trustScore);
                if (detectionResult.isBlocked()) {
                    log.info("注入检测拦截: entity={}, reason={}", decision.entityName(), detectionResult.details());
                    if (candidateRepository != null) {
                        candidateRepository.markFailed(candidateId, "injection_blocked: " + detectionResult.details());
                    }
                    continue;
                }
            }
            try {
                var result = executeDecision(
                        decision,
                        sessionId,
                        writeContext.withEvidenceExcerpt(decision.evidenceExcerpt()),
                        summaryReadFilter);
                if (candidateRepository != null) {
                    candidateRepository.markApplied(candidateId, result.persistedEntityId(), result.baseEntityId());
                }
                // 仅 ADD/UPDATE 产出的当前有效实体可作为关系端点；DELETE/NOOP 不纳入
                if (result.persistedEntityId() != null
                        && (decision.operation() == AudnOperation.ADD
                            || decision.operation() == AudnOperation.UPDATE)) {
                    turnEntityIds.put(decision.entityName(), result.persistedEntityId());
                    turnEntityDisplay.put(decision.entityName(),
                            decision.entityName() + " [" + decision.entityType().name() + "]");
                }
                successCount++;
            } catch (Exception e) {
                if (candidateRepository != null) {
                    candidateRepository.markFailed(candidateId, e.getMessage());
                }
                log.warn("AUDN 单条决策执行失败，跳过: operation={}, entityName={}, error={}",
                        decision.operation(), decision.entityName(), e.getMessage());
            }
        }
        log.debug("实时实体提取完成: sessionId={}, total={}, success={}",
                sessionId, decisions.size(), successCount);

        // 5. 关系抽取阶段 — 实体已持久化、ID 已知后，抽取实体间关系写入 memory_relations
        extractAndPersistRelations(conversationText, turnEntityIds, turnEntityDisplay,
                writeContext, sessionId, summaryReadFilter);
    }

    /**
     * 关系抽取与写入 — 给定本轮已知实体，调用 LLM 抽取实体间关系并经唯一入口写入主库。
     *
     * <p>任何异常静默吞掉，不影响实体写入结果（关系抽取是增量增强，不是主流程）。</p>
     */
    private void extractAndPersistRelations(String conversationText,
                                            Map<String, String> turnEntityIds,
                                            Map<String, String> turnEntityDisplay,
                                            MemoryWriteContext writeContext,
                                            String sessionId,
                                            MemoryReadFilter readFilter) {
        if (relationExtractionStep == null || !relationExtractionEnabled) {
            return;
        }
        try {
            // 构建 name → id 解析表（本轮实体优先）与端点显示清单（带类型）
            var nameToId = new LinkedHashMap<String, String>(turnEntityIds);
            var displayList = new ArrayList<String>(turnEntityDisplay.values());
            try {
                var existing = semanticMemory.findAllCurrent(readFilter).stream()
                        .sorted(Comparator.comparing(TemporalEntity::importanceScore).reversed())
                        .limit(existingEntitySummaryLimit)
                        .toList();
                for (var entity : existing) {
                    if (nameToId.putIfAbsent(entity.name(), entity.id()) == null) {
                        displayList.add(entity.name() + " [" + entity.type().name() + "]");
                    }
                }
            } catch (Exception e) {
                log.debug("关系抽取: 已有实体清单查询失败，仅用本轮实体, error={}", e.getMessage());
            }
            if (nameToId.size() < 2) {
                return;
            }

            var relations = relationExtractionStep.extract(conversationText, displayList);
            if (relations.isEmpty()) {
                return;
            }
            int persisted = 0;
            int skipped = 0;
            for (var r : relations) {
                String srcId = nameToId.get(r.sourceName());
                String tgtId = nameToId.get(r.targetName());
                String type = r.relationType();
                if (srcId == null || tgtId == null || srcId.equals(tgtId)
                        || type == null || type.isBlank()) {
                    skipped++;
                    log.debug("关系跳过: 无法解析端点或非法, source={}, target={}, type={}",
                            r.sourceName(), r.targetName(), type);
                    continue;
                }
                if (semanticMemory.relationExists(srcId, tgtId, type)) {
                    skipped++;
                    continue;
                }
                var now = Instant.now();
                float relTrust = MemoryQualityPolicy.trustScoreFor(
                        MemoryEvidenceKind.CHAT_INFERRED, Math.max(0.0f, Math.min(1.0f, r.strength())));
                var relation = new TemporalRelation(
                        UUID.randomUUID().toString(),
                        srcId, tgtId, type.trim(),
                        Math.max(0.0f, Math.min(1.0f, r.strength())),
                        null, now, null, sessionId, now)
                        .withQuality(MemoryEvidenceKind.CHAT_INFERRED,
                                MemoryQualityPolicy.trustLevelFor(MemoryEvidenceKind.CHAT_INFERRED, relTrust),
                                relTrust);
                try {
                    SqliteBusyRetry.run(() -> semanticMemory.addRelation(relation, writeContext));
                    persisted++;
                } catch (Exception e) {
                    skipped++;
                    log.warn("关系写入失败，跳过: type={}, error={}", type, e.getMessage());
                }
            }
            log.debug("关系抽取完成: sessionId={}, candidates={}, persisted={}, skipped={}",
                    sessionId, relations.size(), persisted, skipped);
        } catch (Exception e) {
            log.warn("关系抽取阶段异常，静默跳过: sessionId={}, error={}", sessionId, e.getMessage());
        }
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
    private List<AudnDecision> callLlmForAudnDecisions(String conversationText, MemoryReadFilter readFilter) {
        String prompt = buildAudnPrompt(conversationText, readFilter);
        try {
            var result = CompletableFuture.supplyAsync(() ->
                            generationRouter.call(
                                    LlmScene.KNOWLEDGE_EXTRACTION,
                                    prompt,
                                    null,
                                    null,
                                    null,
                                    GenerationCapability.CHAT,
                                    Duration.ofSeconds(extractionTimeoutSeconds),
                                    true),  // skipCache: 每次对话内容不同，语义缓存会张冠李戴
                            VIRTUAL_EXECUTOR)
                    .orTimeout(extractionTimeoutSeconds, TimeUnit.SECONDS)
                    .thenApply(response -> parseAudnResponse(response.content()))
                    .join();
            return result != null ? result : List.of();
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

    /** 解析 LLM 返回的 AUDN 决策数组。 */
    private List<AudnDecision> parseAudnResponse(String content) {
        if (content == null || content.isBlank()) return List.of();
        String repaired = JsonOutputParser.repairJson(content);
        try {
            return MAPPER.readValue(repaired,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, AudnDecision.class));
        } catch (Exception e) {
            log.warn("AUDN 数组解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 构建增强版 AUDN 提示词：注入已有实体上下文 + 提取标准 + 评分要求。 */
    private String buildAudnPrompt(String conversationText, MemoryReadFilter readFilter) {
        String existingSummary = buildExistingEntitySummary(readFilter);
        return promptRegistry.render("semantic/entity-extraction", Map.of(
                "existingSummary", existingSummary,
                "conversationText", conversationText));
    }

    /** 构建已有实体摘要，按 importanceScore 降序截取前 N 条。 */
    private String buildExistingEntitySummary(MemoryReadFilter readFilter) {
        try {
            var allCurrent = semanticMemory.findAllCurrent(readFilter);
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
    private DecisionExecutionResult executeDecision(AudnDecision decision,
                                                    String sessionId,
                                                    MemoryWriteContext writeContext,
                                                    MemoryReadFilter inheritedReadFilter) {
        switch (decision.operation()) {
            case ADD -> {
                try {
                    var result = executeAdd(decision, sessionId, writeContext);
                    logExtractionEvent(sessionId, writeContext, decision, true, null);
                    return result;
                } catch (Exception e) {
                    logExtractionEvent(sessionId, writeContext, decision, false, e.getMessage());
                    throw e;
                }
            }
            case UPDATE -> {
                try {
                    var result = executeUpdate(decision, sessionId, writeContext, inheritedReadFilter);
                    logExtractionEvent(sessionId, writeContext, decision, true, null);
                    return result;
                } catch (Exception e) {
                    logExtractionEvent(sessionId, writeContext, decision, false, e.getMessage());
                    throw e;
                }
            }
            case DELETE -> {
                try {
                    var result = executeDelete(decision, writeContext);
                    logExtractionEvent(sessionId, writeContext, decision, true, null);
                    return result;
                } catch (Exception e) {
                    logExtractionEvent(sessionId, writeContext, decision, false, e.getMessage());
                    throw e;
                }
            }
            case NOOP -> {
                return DecisionExecutionResult.empty();
            }
        }
        return DecisionExecutionResult.empty();
    }

    /** 执行 ADD 操作：创建新实体。 */
    private DecisionExecutionResult executeAdd(AudnDecision decision, String sessionId, MemoryWriteContext writeContext) {
        var now = Instant.now();
        float confidence = safeFloat(decision.extractionConfidence(), 0.5f);
        float importance = safeFloat(decision.importanceScore(), 0.5f);
        Temporality temporality = resolveTemporality(decision);
        Instant expiresAt = resolveExpiresAt(decision, temporality);
        var entity = withDecisionQuality(new TemporalEntity(
                UUID.randomUUID().toString(),
                decision.entityType(),
                decision.entityName(),
                decision.description(),
                resolveProperties(decision),
                1, true, now, null, sessionId,
                confidence,
                importance,
                0, null, now, now,
                LifecycleState.ACTIVE, null, expiresAt,
                temporality, null, false, List.of()), decision, confidence);
        var persisted = SqliteBusyRetry.execute(() ->
                semanticMemory.upsertWithConflictDetection(entity, sessionId, writeContext));
        log.debug("AUDN ADD: name={}, type={}, temporality={}, expiresAt={}",
                decision.entityName(), decision.entityType(), temporality, expiresAt);
        return new DecisionExecutionResult(persisted != null ? persisted.id() : entity.id(), null);
    }

    /** 执行 UPDATE 操作：查找已有实体并更新。 */
    private DecisionExecutionResult executeUpdate(AudnDecision decision,
                                                  String sessionId,
                                                  MemoryWriteContext writeContext,
                                                  MemoryReadFilter inheritedReadFilter) {
        MemoryReadFilter readFilter = buildEntityReadFilter(writeContext, decision.entityType());
        var existing = semanticMemory.findCurrentByNameAndType(
                decision.entityName(), decision.entityType(), readFilter);
        if (existing.isEmpty()) {
            var inherited = writeContext.spaceId() != null
                    ? semanticMemory.findCurrentByNameAndType(
                            decision.entityName(), decision.entityType(), inheritedReadFilter)
                    : Optional.<TemporalEntity>empty();
            if (inherited.isPresent()) {
                return executeOverlayUpdate(decision, sessionId, writeContext, inherited.get());
            }
            // 找不到已有实体，降级为 ADD
            log.debug("AUDN UPDATE 降级为 ADD: 未找到已有实体, name={}", decision.entityName());
            return executeAdd(decision, sessionId, writeContext);
        }
        // 构建更新后的实体，通过 upsertWithConflictDetection 版本化更新
        var old = existing.get();
        var mergedProps = new java.util.HashMap<>(old.properties());
        if (decision.properties() != null) {
            mergedProps.putAll(decision.properties());
        }
        deriveDueAtInto(mergedProps, decision);
        // 使用 LLM 输出的 scores：confidence 取新值，importance 取较大值
        float newConfidence = safeFloat(decision.extractionConfidence(), 0.5f);
        float newImportance = safeFloat(decision.importanceScore(), 0.5f);

        Temporality temporality = decision.temporalityRaw() != null && !decision.temporalityRaw().isBlank()
                ? resolveTemporality(decision)
                : old.temporality();
        Instant expiresAt = resolveExpiresAtForUpdate(decision, temporality, old);

        var updated = withDecisionQuality(new TemporalEntity(
                null, decision.entityType(), decision.entityName(),
                decision.description() != null ? decision.description() : old.description(),
                mergedProps,
                old.version(), true, old.validFrom(), null, sessionId,
                newConfidence,
                Math.max(old.importanceScore(), newImportance),
                old.accessCount(), old.lastAccessedAt(),
                old.createdAt(), Instant.now(),
                old.lifecycleState(), old.lifecycleReason(), expiresAt,
                temporality, old.succeededBy(), old.isDerived(), old.derivationSources()), decision, newConfidence);
        var persisted = SqliteBusyRetry.execute(() ->
                semanticMemory.upsertWithConflictDetection(updated, sessionId, writeContext));
        log.debug("AUDN UPDATE: name={}, type={}, temporality={}, expiresAt={}",
                decision.entityName(), decision.entityType(), temporality, expiresAt);
        return new DecisionExecutionResult(persisted != null ? persisted.id() : old.id(), null);
    }

    private DecisionExecutionResult executeOverlayUpdate(AudnDecision decision,
                                                         String sessionId,
                                                         MemoryWriteContext writeContext,
                                                         TemporalEntity baseEntity) {
        var mergedProps = new java.util.HashMap<>(baseEntity.properties());
        if (decision.properties() != null) {
            mergedProps.putAll(decision.properties());
        }
        deriveDueAtInto(mergedProps, decision);
        float newConfidence = safeFloat(decision.extractionConfidence(), 0.5f);
        float newImportance = safeFloat(decision.importanceScore(), 0.5f);
        Temporality temporality = decision.temporalityRaw() != null && !decision.temporalityRaw().isBlank()
                ? resolveTemporality(decision)
                : baseEntity.temporality();
        Instant expiresAt = resolveExpiresAtForUpdate(decision, temporality, baseEntity);
        var now = Instant.now();
        var overlay = withDecisionQuality(new TemporalEntity(
                null,
                decision.entityType(),
                decision.entityName(),
                decision.description() != null ? decision.description() : baseEntity.description(),
                mergedProps,
                1,
                true,
                now,
                null,
                sessionId,
                newConfidence,
                Math.max(baseEntity.importanceScore(), newImportance),
                0,
                null,
                now,
                now,
                baseEntity.lifecycleState(),
                baseEntity.lifecycleReason(),
                expiresAt,
                temporality,
                baseEntity.succeededBy(),
                baseEntity.isDerived(),
                baseEntity.derivationSources()), decision, newConfidence);
        var persisted = SqliteBusyRetry.execute(() ->
                semanticMemory.upsertProjectOverlay(overlay, baseEntity, sessionId, writeContext));
        log.debug("AUDN UPDATE 创建项目 overlay: baseId={}, overlayId={}, name={}",
                baseEntity.id(), persisted != null ? persisted.id() : null, decision.entityName());
        return new DecisionExecutionResult(persisted != null ? persisted.id() : overlay.id(), baseEntity.id());
    }

    private TemporalEntity withDecisionQuality(TemporalEntity entity,
                                               AudnDecision decision,
                                               float extractionConfidence) {
        MemoryEvidenceKind evidenceKind = MemoryQualityPolicy.evidenceKindFromDecision(decision);
        float trustScore = MemoryQualityPolicy.trustScoreFor(evidenceKind, extractionConfidence);
        MemoryTrustLevel trustLevel = MemoryQualityPolicy.trustLevelFor(evidenceKind, trustScore);
        return entity.withQuality(evidenceKind, trustLevel, trustScore, 1, null);
    }

    /** 执行 DELETE 操作：将匹配实体标记为非当前。 */
    private DecisionExecutionResult executeDelete(AudnDecision decision, MemoryWriteContext writeContext) {
        MemoryReadFilter readFilter = buildEntityReadFilter(writeContext, decision.entityType());
        var existing = semanticMemory.findCurrentByNameAndType(
                decision.entityName(), decision.entityType(), readFilter);
        if (existing.isEmpty()) {
            log.debug("AUDN DELETE 跳过: 未找到匹配实体, name={}", decision.entityName());
            return DecisionExecutionResult.empty();
        }
        SqliteBusyRetry.run(() -> semanticMemory.archive(existing.get(), ChangeSource.LLM_SEMANTIC));
        log.debug("AUDN DELETE: name={}, type={}", decision.entityName(), decision.entityType());
        return new DecisionExecutionResult(existing.get().id(), null);
    }

    /** 记录提取事件日志到 extraction_event_log 表。 */
    private void logExtractionEvent(String sessionId,
                                     MemoryWriteContext writeContext,
                                     AudnDecision decision,
                                     boolean success, @Nullable String errorMessage) {
        try {
            jdbcTemplate.update(
                """
                INSERT INTO extraction_event_log(
                    id, session_id, turn_id, space_id, source_entry_id,
                    operation, entity_name, entity_type, extraction_confidence,
                    importance_score, success, error_message, created_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                UUID.randomUUID().toString(),
                sessionId,
                writeContext.sourceTurnId(),
                writeContext.spaceId(),
                writeContext.sourceEntryId(),
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

    /** 截止日期可承载的实体类型。 */
    private static boolean isDeadlineType(EntityType type) {
        return type == EntityType.GOAL || type == EntityType.EVENT || type == EntityType.PROJECT;
    }

    /** ADD 路径：在 decision.properties 基础上确定性补 dueAt。 */
    private Map<String, Object> resolveProperties(AudnDecision decision) {
        var base = new java.util.HashMap<String, Object>(
                decision.properties() != null ? decision.properties() : Map.of());
        deriveDueAtInto(base, decision);
        return base;
    }

    /**
     * 确定性补全 dueAt（memory-deadline-awareness）—— 对 GOAL/EVENT/PROJECT，
     * 若 properties 尚无 dueAt，则从实体名/描述/证据中提取绝对日期写入。
     * 不依赖 LLM 是否主动输出 dueAt；relative 日期不在范围内。
     */
    private void deriveDueAtInto(Map<String, Object> props, AudnDecision decision) {
        if (decision.entityType() == null || !isDeadlineType(decision.entityType())) {
            return;
        }
        Object existing = props.get("dueAt");
        if (existing != null && !existing.toString().isBlank()) {
            return;
        }
        String text = String.join(" ",
                decision.entityName() != null ? decision.entityName() : "",
                decision.description() != null ? decision.description() : "",
                decision.evidenceExcerpt() != null ? decision.evidenceExcerpt() : "");
        int refYear = LocalDate.now(clock).getYear();
        DueDateExtractor.extractIsoDate(text, refYear).ifPresent(iso -> {
            props.put("dueAt", iso);
            log.debug("AUDN: 确定性补全 dueAt={}, entity={}", iso, decision.entityName());
        });
    }

    /**
     * 将 LLM 输出的 {@code temporality} 字符串解析为枚举。
     *
     * <p>规则：</p>
     * <ul>
     *   <li>null / 空串 → {@link Temporality#PERSISTENT}；</li>
     *   <li>其他值必须已通过 {@link ExtractionValidator} 校验。</li>
     * </ul>
     */
    private Temporality resolveTemporality(AudnDecision decision) {
        String raw = decision.temporalityRaw();
        if (raw == null || raw.isBlank()) {
            return Temporality.PERSISTENT;
        }
        return Temporality.valueOf(raw.trim());
    }

    /**
     * 解析或推导 ADD 操作实体的 {@code expires_at}：
     *
     * <ol>
     *   <li>若 LLM 给了合法 ISO 8601 字符串，直接采用；</li>
     *   <li>LLM 未给 / 解析失败，且 {@code temporality} 非持久 → 按 TTL 自动推导；</li>
     *   <li>PERSISTENT 类永不自动过期，保持 null。</li>
     * </ol>
     */
    @Nullable
    private Instant resolveExpiresAt(AudnDecision decision, Temporality temporality) {
        Instant parsed = parseIsoInstantOrNull(decision.expiresAtRaw(), decision.entityName());
        if (parsed != null) {
            return parsed;
        }
        return autoExpiresAt(temporality);
    }

    /**
     * 解析或推导 UPDATE 操作实体的 {@code expires_at}：优先走 LLM 新值，其次
     * 按当前 temporality 推导（与 ADD 规则一致）；若 LLM 未给且 temporality 未变，
     * 保留 old 的 expiresAt 避免把手工设置的过期时间抹掉。
     */
    @Nullable
    private Instant resolveExpiresAtForUpdate(AudnDecision decision,
                                              Temporality temporality,
                                              TemporalEntity old) {
        Instant parsed = parseIsoInstantOrNull(decision.expiresAtRaw(), decision.entityName());
        if (parsed != null) {
            return parsed;
        }
        // temporality 未变化时，尊重 old 的 expires_at（可能已被用户手工延长/缩短）
        if (old.temporality() == temporality) {
            return old.expiresAt() != null ? old.expiresAt() : autoExpiresAt(temporality);
        }
        return autoExpiresAt(temporality);
    }

    /** 按 temporality 推导 expires_at：PERSISTENT 返回 null，其他类 clock.instant() + TTL。 */
    @Nullable
    private Instant autoExpiresAt(Temporality temporality) {
        return switch (temporality) {
            case EPHEMERAL -> clock.instant().plus(EPHEMERAL_TTL);
            case SHORT_TERM -> clock.instant().plus(SHORT_TERM_TTL);
            case PERSISTENT -> null;
        };
    }

    /** 解析 ISO 8601 时间戳；null / 空串 / 非法格式都返回 null，不抛异常。 */
    @Nullable
    private Instant parseIsoInstantOrNull(@Nullable String raw, String entityName) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException ex) {
            log.warn("AUDN: 非法 expires_at={} entity={}，将按 temporality 自动计算",
                    raw, entityName);
            return null;
        }
    }

    @Nullable
    private ChatTurnMemorySnapshot resolveSnapshot(String sessionId, @Nullable String turnId) {
        if (snapshotRepository == null || turnId == null || turnId.isBlank()) {
            log.debug("实时实体提取: 缺少轮次作用域快照能力，跳过自动学习, sessionId={}, turnId={}",
                    sessionId, turnId);
            return null;
        }
        var snapshot = snapshotRepository.findByTurnId(turnId);
        if (snapshot.isEmpty()) {
            log.debug("实时实体提取: 未找到轮次作用域快照，跳过自动学习, sessionId={}, turnId={}",
                    sessionId, turnId);
            return null;
        }
        return snapshot.get();
    }

    private MemoryReadFilter buildEntityReadFilter(MemoryWriteContext writeContext, EntityType entityType) {
        MemoryScope scope = writeContext.memoryScope() != null
                ? writeContext.memoryScope()
                : defaultScopeFor(entityType);
        return MemoryReadFilter.of(
                writeContext.spaceId() != null ? List.of(writeContext.spaceId()) : List.of(),
                List.of(scope)
        );
    }

    private MemoryScope defaultScopeFor(EntityType entityType) {
        return switch (entityType) {
            case EXPERIENCE -> MemoryScope.AGENT_EXPERIENCE;
            case PREFERENCE, HABIT, GOAL, SKILL -> MemoryScope.USER_PROFILE;
            default -> MemoryScope.USER_FACT;
        };
    }

    private record DecisionExecutionResult(@Nullable String persistedEntityId,
                                           @Nullable String baseEntityId) {
        private static DecisionExecutionResult empty() {
            return new DecisionExecutionResult(null, null);
        }
    }
}
