package com.lifepilot.memory.semantic;

import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.generation.support.JsonOutputParser;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.governance.MemoryAccessPolicy;
import com.lifepilot.memory.lifecycle.ChangeSource;
import com.lifepilot.memory.lifecycle.LifecycleState;
import com.lifepilot.memory.lifecycle.Temporality;
import com.lifepilot.memory.quality.MemoryEvidenceKind;
import com.lifepilot.memory.quality.MemoryQualityPolicy;
import com.lifepilot.memory.quality.MemoryTrustLevel;
import com.lifepilot.memory.scope.ChatTurnMemorySnapshot;
import com.lifepilot.memory.support.SqliteBusyRetry;
import com.lifepilot.memory.scope.ChatTurnMemorySnapshotRepository;
import com.lifepilot.memory.scope.MemoryReadFilter;
import com.lifepilot.memory.scope.MemoryScope;
import com.lifepilot.memory.scope.MemoryWriteContext;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.time.Duration;
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

    @Nullable
    private final GenerationRouter generationRouter;
    private final SemanticMemory semanticMemory;
    private final ExtractionValidator extractionValidator;
    private final int extractionTimeoutSeconds;
    private final int existingEntitySummaryLimit;
    private final JdbcTemplate jdbcTemplate;
    private final PromptRegistry promptRegistry;
    @Nullable
    private final ChatTurnMemorySnapshotRepository snapshotRepository;
    /** Task 23：时钟注入 — 用于非持久性实体自动推导 {@code expires_at}，便于单测注入固定时钟。 */
    private final Clock clock;
    private final MemoryAccessPolicy memoryAccessPolicy;
    @Nullable
    private final MemoryExtractionCandidateRepository candidateRepository;

    public RealtimeExtractor(@Nullable GenerationRouter generationRouter,
                             SemanticMemory semanticMemory,
                             MemoryProperties properties,
                             ExtractionValidator extractionValidator,
                             JdbcTemplate jdbcTemplate,
                             PromptRegistry promptRegistry,
                             @Nullable ChatTurnMemorySnapshotRepository snapshotRepository) {
        this(generationRouter, semanticMemory, properties, extractionValidator,
                jdbcTemplate, promptRegistry, snapshotRepository, Clock.systemUTC());
    }

    /**
     * 带 {@link Clock} 的扩展构造器 — 测试可注入 {@code Clock.fixed(...)} 以确定
     * 自动推导的 {@code expires_at}。生产路径走 7 参构造器默认 {@code systemUTC}。
     */
    public RealtimeExtractor(@Nullable GenerationRouter generationRouter,
                             SemanticMemory semanticMemory,
                             MemoryProperties properties,
                             ExtractionValidator extractionValidator,
                             JdbcTemplate jdbcTemplate,
                             PromptRegistry promptRegistry,
                             @Nullable ChatTurnMemorySnapshotRepository snapshotRepository,
                             Clock clock) {
        this(generationRouter, semanticMemory, properties, extractionValidator,
                jdbcTemplate, promptRegistry, snapshotRepository, clock, null);
    }

    public RealtimeExtractor(@Nullable GenerationRouter generationRouter,
                             SemanticMemory semanticMemory,
                             MemoryProperties properties,
                             ExtractionValidator extractionValidator,
                             JdbcTemplate jdbcTemplate,
                             PromptRegistry promptRegistry,
                             @Nullable ChatTurnMemorySnapshotRepository snapshotRepository,
                             Clock clock,
                             @Nullable MemoryAccessPolicy memoryAccessPolicy) {
        this(generationRouter, semanticMemory, properties, extractionValidator,
                jdbcTemplate, promptRegistry, snapshotRepository, clock, memoryAccessPolicy, null);
    }

    public RealtimeExtractor(@Nullable GenerationRouter generationRouter,
                             SemanticMemory semanticMemory,
                             MemoryProperties properties,
                             ExtractionValidator extractionValidator,
                             JdbcTemplate jdbcTemplate,
                             PromptRegistry promptRegistry,
                             @Nullable ChatTurnMemorySnapshotRepository snapshotRepository,
                             Clock clock,
                             @Nullable MemoryAccessPolicy memoryAccessPolicy,
                             @Nullable MemoryExtractionCandidateRepository candidateRepository) {
        this.generationRouter = generationRouter;
        this.semanticMemory = semanticMemory;
        this.extractionValidator = extractionValidator;
        this.extractionTimeoutSeconds = properties.getExtraction().getTimeoutSeconds();
        this.existingEntitySummaryLimit = properties.getExtraction().getExistingEntitySummaryLimit();
        this.jdbcTemplate = jdbcTemplate;
        this.promptRegistry = promptRegistry;
        this.snapshotRepository = snapshotRepository;
        this.clock = clock;
        this.memoryAccessPolicy = memoryAccessPolicy != null ? memoryAccessPolicy : new MemoryAccessPolicy();
        this.candidateRepository = candidateRepository;
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
        if (generationRouter == null) {
            log.debug("实时实体提取: GenerationRouter 不可用，跳过");
            return;
        }
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
        for (var decision : validDecisions) {
            String candidateId = candidateRepository != null
                    ? candidateRepository.recordValidated(sessionId, writeContext, decision)
                    : null;
            try {
                var result = executeDecision(decision, sessionId, writeContext, summaryReadFilter);
                if (candidateRepository != null) {
                    candidateRepository.markApplied(candidateId, result.persistedEntityId(), result.baseEntityId());
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

    /** 解析 LLM 返回的 AUDN 决策，兼容数组 [...] 和对象 {"decisions":[...]} 两种格式。 */
    private List<AudnDecision> parseAudnResponse(String content) {
        if (content == null || content.isBlank()) return List.of();
        String repaired = JsonOutputParser.repairJson(content);
        if (repaired.stripLeading().startsWith("[")) {
            // LLM 直接返回数组格式
            try {
                return MAPPER.readValue(repaired,
                        MAPPER.getTypeFactory().constructCollectionType(List.class, AudnDecision.class));
            } catch (Exception e) {
                log.warn("AUDN 数组格式解析失败，尝试对象格式: {}", e.getMessage());
            }
        }
        // 尝试对象格式 {"decisions": [...]}
        var result = JsonOutputParser.parse(repaired, AudnDecisionList.class);
        return result != null ? result.decisions() : List.of();
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
        // Task 23：从 LLM 响应解析 temporality / expires_at，非持久类自动推导过期时间
        Temporality temporality = resolveTemporality(decision);
        Instant expiresAt = resolveExpiresAt(decision, temporality);
        var entity = withDecisionQuality(new TemporalEntity(
                UUID.randomUUID().toString(),
                decision.entityType(),
                decision.entityName(),
                decision.description(),
                decision.properties() != null ? decision.properties() : Map.of(),
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
        // 使用 LLM 输出的 scores：confidence 取新值，importance 取较大值
        float newConfidence = safeFloat(decision.extractionConfidence(), 0.5f);
        float newImportance = safeFloat(decision.importanceScore(), 0.5f);

        // Task 23：UPDATE 也要携带 temporality / expires_at，LLM 未给则保留 old 的持久度
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

    /**
     * 将 LLM 输出的 {@code temporality} 字符串解析为枚举。
     *
     * <p>规则：</p>
     * <ul>
     *   <li>null / 空串 / 非法值 → {@link Temporality#PERSISTENT}（兜底）；</li>
     *   <li>大小写不敏感，允许 {@code "ephemeral"} / {@code "Short_Term"} 等变体。</li>
     * </ul>
     */
    private Temporality resolveTemporality(AudnDecision decision) {
        String raw = decision.temporalityRaw();
        if (raw == null || raw.isBlank()) {
            return Temporality.PERSISTENT;
        }
        try {
            return Temporality.valueOf(raw.toUpperCase().trim());
        } catch (IllegalArgumentException ex) {
            log.warn("AUDN: 非法 temporality 值={} entity={}，降级为 PERSISTENT",
                    raw, decision.entityName());
            return Temporality.PERSISTENT;
        }
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

    private record DecisionExecutionResult(@Nullable String persistedEntityId,
                                           @Nullable String baseEntityId) {
        private static DecisionExecutionResult empty() {
            return new DecisionExecutionResult(null, null);
        }
    }
}
