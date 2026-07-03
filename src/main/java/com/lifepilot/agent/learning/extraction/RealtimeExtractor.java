package com.lifepilot.agent.learning.extraction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.agent.learning.config.AgentLearningProperties;
import com.lifepilot.llm.LlmResponse;
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
import com.lifepilot.memory.semantic.DueAtFormat;
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
 * <p>在 Virtual Thread 中异步执行；调用方可等待返回的 Future，把记忆写入结果纳入
 * 对话后处理完成语义。</p>
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
    private final ChatTurnMemorySnapshotRepository snapshotRepository;
    /** 时钟注入 — 用于非持久性实体自动推导 {@code expires_at}，便于单测注入固定时钟。 */
    private final Clock clock;
    private final MemoryAccessPolicy memoryAccessPolicy;
    private final MemoryExtractionCandidateRepository candidateRepository;
    private final MemoryInjectionDetector injectionDetector;
    /** 关系抽取步骤。是否执行由配置开关控制。 */
    private final RelationExtractionStep relationExtractionStep;
    /** 对话期关系抽取开关，来自配置。 */
    private final boolean relationExtractionEnabled;

    /**
     * 唯一构造器 —— 注入全部协作依赖。
     */
    public RealtimeExtractor(GenerationRouter generationRouter,
                             SemanticMemory semanticMemory,
                             AgentLearningProperties properties,
                             ExtractionValidator extractionValidator,
                             JdbcTemplate jdbcTemplate,
                             PromptRegistry promptRegistry,
                             ChatTurnMemorySnapshotRepository snapshotRepository,
                             Clock clock,
                             MemoryAccessPolicy memoryAccessPolicy,
                             MemoryExtractionCandidateRepository candidateRepository,
                             MemoryInjectionDetector injectionDetector,
                             RelationExtractionStep relationExtractionStep) {
        this.generationRouter = Objects.requireNonNull(generationRouter, "generationRouter 不能为空");
        this.semanticMemory = Objects.requireNonNull(semanticMemory, "semanticMemory 不能为空");
        AgentLearningProperties checkedProperties = Objects.requireNonNull(properties, "properties 不能为空");
        this.extractionValidator = Objects.requireNonNull(extractionValidator, "extractionValidator 不能为空");
        this.extractionTimeoutSeconds = checkedProperties.getExtraction().getTimeoutSeconds();
        this.existingEntitySummaryLimit = checkedProperties.getExtraction().getExistingEntitySummaryLimit();
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate 不能为空");
        this.promptRegistry = Objects.requireNonNull(promptRegistry, "promptRegistry 不能为空");
        this.snapshotRepository = Objects.requireNonNull(snapshotRepository, "snapshotRepository 不能为空");
        this.clock = Objects.requireNonNull(clock, "clock 不能为空");
        this.memoryAccessPolicy = Objects.requireNonNull(memoryAccessPolicy, "memoryAccessPolicy 不能为空");
        this.candidateRepository = Objects.requireNonNull(candidateRepository, "candidateRepository 不能为空");
        this.injectionDetector = Objects.requireNonNull(injectionDetector, "injectionDetector 不能为空");
        this.relationExtractionStep = Objects.requireNonNull(relationExtractionStep, "relationExtractionStep 不能为空");
        this.relationExtractionEnabled = checkedProperties.getExtraction().isRelationExtractionEnabled();
    }

    public CompletableFuture<Void> extractAsync(String sessionId, String turnId, String userMessage, String aiResponse) {
        return CompletableFuture.runAsync(
                () -> extract(sessionId, turnId, userMessage, aiResponse),
                VIRTUAL_EXECUTOR);
    }

    void extract(String sessionId, String turnId, String userMessage, String aiResponse) {
        requireText(sessionId, "实时实体提取 sessionId 不能为空");
        requireText(turnId, "实时实体提取 turnId 不能为空");
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
        if (decisions.isEmpty()) {
            log.debug("实时实体提取: 无需操作, sessionId={}", sessionId);
            return;
        }

        // 3. 提取后验证，并记录 rejected 候选，避免质量门控阶段静默丢失审计线索。
        var validationResult = extractionValidator.validateWithResult(decisions);
        for (var rejected : validationResult.rejectedDecisions()) {
            candidateRepository.recordRejected(
                    sessionId,
                    writeContext,
                    rejected.decision(),
                    rejected.reason());
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
            String candidateId = requireText(
                    candidateRepository.recordValidated(sessionId, writeContext, decision),
                    "记忆提取候选 id 不能为空");
            // 4.1 注入检测
            float trustScore = requiredScore(decision.extractionConfidence(), "extractionConfidence", decision);
            var detectionResult = injectionDetector.detect(
                    writeContext.spaceId(),
                    decision.description(),
                    trustScore);
            if (detectionResult.isBlocked()) {
                log.info("注入检测拦截: entity={}, reason={}", decision.entityName(), detectionResult.details());
                String reason = "injection_blocked: " + detectionResult.details();
                candidateRepository.markFailed(candidateId, reason);
                logExtractionEvent(sessionId, writeContext, decision, false, reason);
                continue;
            }
            try {
                var result = executeDecision(
                        decision,
                        sessionId,
                        writeContext.withEvidenceExcerpt(decision.evidenceExcerpt()),
                        summaryReadFilter);
                candidateRepository.markApplied(candidateId, result.persistedEntityId(), result.baseEntityId());
                // 仅 ADD/UPDATE 产出的当前有效实体可作为关系端点；DELETE/NOOP 不纳入
                if (result.persistedEntityId() != null
                        && (decision.operation() == AudnOperation.ADD
                            || decision.operation() == AudnOperation.UPDATE)) {
                    turnEntityIds.put(decision.entityName(), result.persistedEntityId());
                    turnEntityDisplay.put(decision.entityName(),
                            decision.entityName() + " [" + decision.entityType().name() + "]");
                }
                logExtractionEvent(sessionId, writeContext, decision, true, null);
                successCount++;
            } catch (Exception e) {
                try {
                    candidateRepository.markFailed(candidateId, e.getMessage());
                } catch (Exception markFailure) {
                    if (markFailure != e) {
                        e.addSuppressed(markFailure);
                    }
                }
                try {
                    logExtractionEvent(sessionId, writeContext, decision, false, e.getMessage());
                } catch (Exception logFailure) {
                    if (logFailure != e) {
                        e.addSuppressed(logFailure);
                    }
                }
                throw e;
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
     */
    private void extractAndPersistRelations(String conversationText,
                                            Map<String, String> turnEntityIds,
                                            Map<String, String> turnEntityDisplay,
                                            MemoryWriteContext writeContext,
                                            String sessionId,
                                            MemoryReadFilter readFilter) {
        if (!relationExtractionEnabled) {
            return;
        }
        // 构建 name → id 解析表（本轮实体优先）与端点显示清单（带类型）
        var nameToId = new LinkedHashMap<String, String>(turnEntityIds);
        var displayList = new ArrayList<String>(turnEntityDisplay.values());
        var existingEntities = Objects.requireNonNull(
                semanticMemory.findAllCurrent(readFilter),
                "关系抽取前查询已有实体返回为空");
        var existing = existingEntities.stream()
                .sorted(Comparator.comparing(TemporalEntity::importanceScore).reversed())
                .limit(existingEntitySummaryLimit)
                .toList();
        for (var entity : existing) {
            if (nameToId.putIfAbsent(entity.name(), entity.id()) == null) {
                displayList.add(entity.name() + " [" + entity.type().name() + "]");
            }
        }
        if (nameToId.size() < 2) {
            return;
        }

        var relations = Objects.requireNonNull(
                relationExtractionStep.extract(conversationText, displayList),
                "关系抽取结果不能为空");
        if (relations.isEmpty()) {
            return;
        }
        int persisted = 0;
        int skipped = 0;
        for (var r : relations) {
            String srcId = nameToId.get(r.sourceName());
            String tgtId = nameToId.get(r.targetName());
            String type = r.relationType();
            if (srcId == null || tgtId == null) {
                throw new IllegalStateException(
                        "关系抽取端点无法解析: source=%s, target=%s"
                                .formatted(r.sourceName(), r.targetName()));
            }
            if (srcId.equals(tgtId)) {
                throw new IllegalStateException(
                        "关系抽取端点不能相同: source=%s, target=%s"
                                .formatted(r.sourceName(), r.targetName()));
            }
            if (type == null || type.isBlank()) {
                throw new IllegalStateException(
                        "关系抽取类型不能为空: source=%s, target=%s"
                                .formatted(r.sourceName(), r.targetName()));
            }
            if (semanticMemory.relationExists(srcId, tgtId, type)) {
                skipped++;
                continue;
            }
            var now = Instant.now();
            float relTrust = MemoryQualityPolicy.trustScoreFor(
                    MemoryEvidenceKind.CHAT_INFERRED, r.strength());
            var relation = new TemporalRelation(
                    UUID.randomUUID().toString(),
                    srcId, tgtId, type,
                    r.strength(),
                    null, now, null, sessionId, now,
                    MemoryEvidenceKind.CHAT_INFERRED,
                    MemoryQualityPolicy.trustLevelFor(MemoryEvidenceKind.CHAT_INFERRED, relTrust),
                    relTrust);
            SqliteBusyRetry.run(() -> semanticMemory.addRelation(relation, writeContext));
            persisted++;
        }
        log.debug("关系抽取完成: sessionId={}, candidates={}, persisted={}, skipped={}",
                sessionId, relations.size(), persisted, skipped);
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
        requireText(prompt, "AUDN 实体提取 Prompt 渲染为空");
        var future = CompletableFuture.supplyAsync(() ->
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
                .orTimeout(extractionTimeoutSeconds, TimeUnit.SECONDS);
        var response = Objects.requireNonNull(awaitAudnResponse(future), "AUDN 实体提取返回为空");
        return parseAudnResponse(response.content());
    }

    private LlmResponse awaitAudnResponse(CompletableFuture<LlmResponse> future) {
        try {
            return future.join();
        } catch (Exception e) {
            // CompletableFuture.join() 包装为 CompletionException，解包判断是否超时
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof TimeoutException) {
                throw new IllegalStateException("AUDN 实体提取超时: timeoutSeconds=" + extractionTimeoutSeconds, cause);
            }
            throw new IllegalStateException("AUDN 实体提取 LLM 调用失败: " + cause.getMessage(), cause);
        }
    }

    /** 解析 LLM 返回的 AUDN 决策数组。 */
    private List<AudnDecision> parseAudnResponse(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("AUDN 实体提取 LLM 返回空内容");
        }
        try {
            List<AudnDecision> decisions = MAPPER.readValue(content,
                    MAPPER.getTypeFactory().constructCollectionType(List.class, AudnDecision.class));
            Objects.requireNonNull(decisions, "AUDN 实体提取 LLM 返回内容必须是数组");
            for (AudnDecision decision : decisions) {
                Objects.requireNonNull(decision, "AUDN 实体提取 LLM 返回数组不能包含 null 决策");
            }
            return List.copyOf(decisions);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("AUDN 数组解析失败: " + e.getOriginalMessage(), e);
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
        var allCurrent = Objects.requireNonNull(
                semanticMemory.findAllCurrent(readFilter),
                "AUDN 实体提取查询已有实体返回为空");
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
    }

    /** 执行单条 AUDN 决策。 */
    private DecisionExecutionResult executeDecision(AudnDecision decision,
                                                    String sessionId,
                                                    MemoryWriteContext writeContext,
                                                    MemoryReadFilter inheritedReadFilter) {
        return switch (Objects.requireNonNull(decision.operation(), "AUDN 操作不能为空")) {
            case ADD -> executeAdd(decision, sessionId, writeContext);
            case UPDATE -> executeUpdate(decision, sessionId, writeContext, inheritedReadFilter);
            case DELETE -> executeDelete(decision, writeContext);
            case NOOP -> DecisionExecutionResult.empty();
        };
    }

    /** 执行 ADD 操作：创建新实体。 */
    private DecisionExecutionResult executeAdd(AudnDecision decision, String sessionId, MemoryWriteContext writeContext) {
        var now = Instant.now();
        float confidence = requiredScore(decision.extractionConfidence(), "extractionConfidence", decision);
        float importance = requiredScore(decision.importanceScore(), "importanceScore", decision);
        Temporality temporality = resolveTemporality(decision);
        Instant expiresAt = resolveExpiresAt(decision, temporality);
        var quality = decisionQuality(decision, confidence);
        var entity = new TemporalEntity(
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
                temporality, null, false, List.of(),
                quality.evidenceKind(), quality.trustLevel(), quality.trustScore(), 1, null);
        var persisted = SqliteBusyRetry.execute(() ->
                semanticMemory.upsertWithConflictDetection(entity, sessionId, writeContext));
        log.debug("AUDN ADD: name={}, type={}, temporality={}, expiresAt={}",
                decision.entityName(), decision.entityType(), temporality, expiresAt);
        return new DecisionExecutionResult(requirePersistedEntityId(persisted, "AUDN ADD"), null);
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
            throw new IllegalStateException("AUDN UPDATE 未找到已有实体: name=%s, type=%s"
                    .formatted(decision.entityName(), decision.entityType()));
        }
        // 构建更新后的实体，通过 upsertWithConflictDetection 版本化更新
        var old = existing.get();
        var mergedProps = new java.util.HashMap<>(old.properties());
        if (decision.properties() != null) {
            mergedProps.putAll(decision.properties());
        }
        deriveDueAtInto(mergedProps, decision);
        // 使用 LLM 输出的 scores：confidence 取新值，importance 取较大值
        float newConfidence = requiredScore(decision.extractionConfidence(), "extractionConfidence", decision);
        float newImportance = requiredScore(decision.importanceScore(), "importanceScore", decision);

        Temporality temporality = resolveTemporality(decision);
        Instant expiresAt = resolveExpiresAtForUpdate(decision, temporality, old);

        var quality = decisionQuality(decision, newConfidence);
        var updated = new TemporalEntity(
                null, decision.entityType(), decision.entityName(),
                decision.description() != null ? decision.description() : old.description(),
                mergedProps,
                old.version(), true, old.validFrom(), null, sessionId,
                newConfidence,
                Math.max(old.importanceScore(), newImportance),
                old.accessCount(), old.lastAccessedAt(),
                old.createdAt(), Instant.now(),
                old.lifecycleState(), old.lifecycleReason(), expiresAt,
                temporality, old.succeededBy(), old.isDerived(), old.derivationSources(),
                quality.evidenceKind(), quality.trustLevel(), quality.trustScore(), 1, null);
        var persisted = SqliteBusyRetry.execute(() ->
                semanticMemory.upsertWithConflictDetection(updated, sessionId, writeContext));
        log.debug("AUDN UPDATE: name={}, type={}, temporality={}, expiresAt={}",
                decision.entityName(), decision.entityType(), temporality, expiresAt);
        return new DecisionExecutionResult(requirePersistedEntityId(persisted, "AUDN UPDATE"), null);
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
        float newConfidence = requiredScore(decision.extractionConfidence(), "extractionConfidence", decision);
        float newImportance = requiredScore(decision.importanceScore(), "importanceScore", decision);
        Temporality temporality = resolveTemporality(decision);
        Instant expiresAt = resolveExpiresAtForUpdate(decision, temporality, baseEntity);
        var now = Instant.now();
        var quality = decisionQuality(decision, newConfidence);
        var overlay = new TemporalEntity(
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
                baseEntity.derivationSources(),
                quality.evidenceKind(), quality.trustLevel(), quality.trustScore(), 1, null);
        var persisted = SqliteBusyRetry.execute(() ->
                semanticMemory.upsertProjectOverlay(overlay, baseEntity, sessionId, writeContext));
        String persistedId = requirePersistedEntityId(persisted, "AUDN UPDATE 项目 overlay");
        log.debug("AUDN UPDATE 创建项目 overlay: baseId={}, overlayId={}, name={}",
                baseEntity.id(), persistedId, decision.entityName());
        return new DecisionExecutionResult(persistedId, baseEntity.id());
    }

    private DecisionQuality decisionQuality(AudnDecision decision, float extractionConfidence) {
        MemoryEvidenceKind evidenceKind = MemoryQualityPolicy.evidenceKindFromDecision(decision);
        float trustScore = MemoryQualityPolicy.trustScoreFor(evidenceKind, extractionConfidence);
        MemoryTrustLevel trustLevel = MemoryQualityPolicy.trustLevelFor(evidenceKind, trustScore);
        return new DecisionQuality(evidenceKind, trustLevel, trustScore);
    }

    private record DecisionQuality(MemoryEvidenceKind evidenceKind, MemoryTrustLevel trustLevel, float trustScore) {
    }

    /** 执行 DELETE 操作：将匹配实体标记为非当前。 */
    private DecisionExecutionResult executeDelete(AudnDecision decision, MemoryWriteContext writeContext) {
        requiredScore(decision.extractionConfidence(), "extractionConfidence", decision);
        requiredScore(decision.importanceScore(), "importanceScore", decision);
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
            requiredScore(decision.extractionConfidence(), "extractionConfidence", decision),
            requiredScore(decision.importanceScore(), "importanceScore", decision),
            success ? 1 : 0,
            errorMessage,
            Instant.now().toString());
    }

    /** 已通过质量门控的决策必须包含完整评分。 */
    private static float requiredScore(@Nullable Float value, String fieldName, AudnDecision decision) {
        if (value == null) {
            throw new IllegalStateException("已通过质量门控的 AUDN 决策缺少评分: field=%s, entity=%s"
                    .formatted(fieldName, decision.entityName()));
        }
        if (!(value >= 0.0f && value <= 1.0f)) {
            throw new IllegalStateException("已通过质量门控的 AUDN 决策评分越界: field=%s, value=%s, entity=%s"
                    .formatted(fieldName, value, decision.entityName()));
        }
        return value;
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
     * 若 LLM 已给 dueAt，必须是 yyyy-MM-dd；其他类型不允许携带 dueAt。
     * 不依赖 LLM 是否主动输出 dueAt；relative 日期不在范围内。
     */
    private void deriveDueAtInto(Map<String, Object> props, AudnDecision decision) {
        if (props.containsKey("dueAt")) {
            if (decision.entityType() == null || !isDeadlineType(decision.entityType())) {
                throw new IllegalStateException(
                        "AUDN dueAt 只允许用于 GOAL/EVENT/PROJECT: entity=%s, type=%s"
                                .formatted(decision.entityName(), decision.entityType()));
            }
            DueAtFormat.requireCanonicalDate(
                    props.get("dueAt"),
                    "AUDN dueAt",
                    "entity=%s, type=%s".formatted(decision.entityName(), decision.entityType()));
            return;
        }
        if (decision.entityType() == null || !isDeadlineType(decision.entityType())) {
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
     * <p>值必须已通过 {@link ExtractionValidator} 校验。</p>
     */
    private Temporality resolveTemporality(AudnDecision decision) {
        String raw = decision.temporalityRaw();
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("AUDN 决策 temporality 不能为空: entity=" + decision.entityName());
        }
        if (!raw.equals(raw.trim())) {
            throw new IllegalStateException(
                    "AUDN 决策 temporality 不能包含首尾空白: entity=%s, temporality=%s"
                            .formatted(decision.entityName(), raw));
        }
        return Temporality.valueOf(raw);
    }

    /**
     * 解析或推导 ADD 操作实体的 {@code expires_at}：
     *
     * <ol>
     *   <li>若 LLM 给了合法 ISO 8601 字符串，直接采用；</li>
     *   <li>LLM 未给时，且 {@code temporality} 非持久 → 按 TTL 自动推导；</li>
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

    /** 解析 ISO 8601 时间戳；null / 空串表示缺省，非法格式直接暴露。 */
    @Nullable
    private Instant parseIsoInstantOrNull(@Nullable String raw, String entityName) {
        if (raw == null) {
            return null;
        }
        if (raw.isBlank()) {
            throw new IllegalStateException(
                    "AUDN expires_at 不能为空白字符串: entity=%s".formatted(entityName));
        }
        if (!raw.equals(raw.trim())) {
            throw new IllegalStateException(
                    "AUDN expires_at 不能包含首尾空白: entity=%s, expires_at=%s".formatted(entityName, raw));
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException ex) {
            throw new IllegalStateException(
                    "AUDN expires_at 格式非法: entity=%s, expires_at=%s".formatted(entityName, raw),
                    ex);
        }
    }

    private ChatTurnMemorySnapshot resolveSnapshot(String sessionId, String turnId) {
        var snapshot = Objects.requireNonNull(
                snapshotRepository.findByTurnId(turnId),
                "轮次作用域快照查询结果不能为空");
        var found = snapshot.orElseThrow(() -> new IllegalStateException(
                "实时实体提取缺少轮次作用域快照: sessionId=%s, turnId=%s".formatted(sessionId, turnId)));
        if (!sessionId.equals(found.sessionId())) {
            throw new IllegalStateException(
                    "轮次作用域快照会话不匹配: expectedSessionId=%s, actualSessionId=%s, turnId=%s"
                            .formatted(sessionId, found.sessionId(), turnId));
        }
        return found;
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

    private static String requirePersistedEntityId(@Nullable TemporalEntity entity, String operation) {
        TemporalEntity persisted = Objects.requireNonNull(entity, operation + " 持久化返回为空");
        return requireText(persisted.id(), operation + " 持久化实体 id 不能为空");
    }

    private static String requireText(@Nullable String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
