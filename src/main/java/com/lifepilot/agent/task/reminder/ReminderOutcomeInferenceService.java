package com.lifepilot.agent.task.reminder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.memory.episodic.ConversationRecord;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.episodic.MessageRecord;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.memory.workspace.SessionWorkspaceService;
import com.lifepilot.memory.workspace.WorkspaceItem;
import com.lifepilot.memory.workspace.WorkspaceStatus;
import com.lifepilot.observability.trace.ToolCallStep;
import com.lifepilot.observability.trace.TraceQuery;
import com.lifepilot.observability.trace.TraceStep;
import com.lifepilot.workflow.model.StepLog;
import com.lifepilot.workflow.model.StepState;
import com.lifepilot.workflow.model.WorkflowInstance;
import com.lifepilot.workflow.model.WorkflowState;
import com.lifepilot.workflow.repository.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import com.lifepilot.memory.semantic.EntityType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 提醒隐式结果推断服务。
 *
 * <p>基于系统内部后续状态变化，推断提醒是否真正促成了事项完成。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
public class ReminderOutcomeInferenceService {

    private static final Logger log = LoggerFactory.getLogger(ReminderOutcomeInferenceService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern COMPLETION_CUE_PATTERN = Pattern.compile(
            "完成了|完成啦|搞定了|办完了|处理好了|解决了|提交了|安排好了|报销了|交了|付了|已经.*(完成|处理|解决|提交|搞定|办完|安排)",
            Pattern.CASE_INSENSITIVE
    );
    private static final List<String> TERMINAL_STATUS_VALUES = List.of(
            "DONE", "COMPLETED", "RESOLVED", "CLOSED", "PAID", "SUBMITTED", "FINISHED"
    );

    /** 反事实样本的 reward 折扣系数，避免合成数据主导真实反馈。 */
    private static final float COUNTERFACTUAL_DISCOUNT = 0.7f;

    /** 可用于反事实推断的实体类型。 */
    private static final List<EntityType> COUNTERFACTUAL_ENTITY_TYPES = List.of(
            EntityType.EVENT, EntityType.GOAL, EntityType.HABIT, EntityType.PROJECT
    );

    /** 动作意图正则 — 复用 DefaultReminderSignalCollector 的模式。 */
    private static final Pattern ACTION_CUE_PATTERN = Pattern.compile(
            "提醒|记得|别忘|待办|安排|准备|处理|提交|缴费|报销|整理|复盘|预约|确认|推进|跟进|打卡|买|出发|会议|计划"
    );

    @Nullable
    private final ReminderExecutionRepository executionRepository;
    @Nullable
    private final ReminderOutcomeRepository outcomeRepository;
    @Nullable
    private final SessionWorkspaceService workspaceService;
    @Nullable
    private final SemanticMemory semanticMemory;
    @Nullable
    private final EpisodicMemory episodicMemory;
    @Nullable
    private final WorkflowRepository workflowRepository;
    @Nullable
    private final TraceQuery traceQuery;
    private final AgentConfigProperties config;

    public ReminderOutcomeInferenceService(@Nullable ReminderExecutionRepository executionRepository,
                                           @Nullable ReminderOutcomeRepository outcomeRepository,
                                           @Nullable SessionWorkspaceService workspaceService,
                                           @Nullable SemanticMemory semanticMemory,
                                           @Nullable EpisodicMemory episodicMemory,
                                           @Nullable WorkflowRepository workflowRepository,
                                           @Nullable TraceQuery traceQuery,
                                           AgentConfigProperties config) {
        this.executionRepository = executionRepository;
        this.outcomeRepository = outcomeRepository;
        this.workspaceService = workspaceService;
        this.semanticMemory = semanticMemory;
        this.episodicMemory = episodicMemory;
        this.workflowRepository = workflowRepository;
        this.traceQuery = traceQuery;
        this.config = config;
    }

    public int inferRecentOutcomes(String userId, Instant now) {
        if (executionRepository == null || outcomeRepository == null) {
            return 0;
        }
        Instant since = now.minusSeconds(
                Math.max(1, config.getTask().getProactiveReminderOutcomeInferenceLookbackDays()) * 24L * 3600L
        );
        List<ReminderOutcomeInferenceCandidate> candidates;
        try {
            candidates = executionRepository.findPendingOutcomeInferenceCandidates(
                    userId, since, config.getTask().getProactiveReminderOutcomeInferenceBatchSize());
        } catch (Exception e) {
            log.debug("提醒隐式结果推断跳过: 读取候选失败, userId={}, error={}", userId, e.getMessage());
            return 0;
        }

        int inferredCount = 0;
        for (ReminderOutcomeInferenceCandidate candidate : candidates) {
            Optional<ReminderInferredOutcomeRecord> inferred = inferCandidate(candidate, now);
            if (inferred.isEmpty()) {
                continue;
            }
            try {
                outcomeRepository.saveOutcome(inferred.get());
                inferredCount++;
            } catch (Exception e) {
                log.warn("提醒隐式结果落库失败: decisionId={}, topicKey={}, error={}",
                        candidate.decisionId(), candidate.topicKey(), e.getMessage());
            }
        }
        if (inferredCount > 0) {
            log.info("主动提醒隐式结果推断完成: userId={}, candidates={}, inferred={}",
                    userId, candidates.size(), inferredCount);
        }
        return inferredCount;
    }

    /**
     * 反事实样本预热 — 从历史记忆中合成"假如当时发了提醒"的训练样本。
     *
     * <p>扫描 L3 中近期的 EVENT/GOAL/HABIT/PROJECT 实体，结合 L2 对话中的完成线索，
     * 推断事项是否被完成（正样本）或可能被遗忘（负样本），生成 Bandit 训练数据。</p>
     *
     * <p>反事实样本的 reward 打折（×{@code COUNTERFACTUAL_DISCOUNT}），
     * 避免合成数据主导真实反馈。</p>
     *
     * @param userId 用户 ID
     * @param since  回溯起始时间
     * @return 合成的训练样本列表
     */
    public List<ReminderActionTrainingExample> inferCounterfactualExamples(String userId, Instant since) {
        if (semanticMemory == null) {
            return List.of();
        }
        Instant now = Instant.now();
        List<ReminderActionTrainingExample> examples = new ArrayList<>();

        for (EntityType type : COUNTERFACTUAL_ENTITY_TYPES) {
            try {
                List<TemporalEntity> entities = semanticMemory.findCurrentByType(type);
                for (TemporalEntity entity : entities) {
                    if (entity.createdAt().isBefore(since)) {
                        continue;
                    }
                    inferCounterfactualFromEntity(entity, now).ifPresent(examples::add);
                }
            } catch (Exception e) {
                log.debug("反事实样本推断跳过: entityType={}, error={}", type, e.getMessage());
            }
        }

        // 从近期对话中补充提取
        if (episodicMemory != null) {
            try {
                var conversations = episodicMemory.getRecent(Duration.between(since, now));
                for (var conversation : conversations) {
                    inferCounterfactualFromConversation(conversation, now).ifPresent(examples::add);
                }
            } catch (Exception e) {
                log.debug("反事实样本推断跳过对话扫描: error={}", e.getMessage());
            }
        }

        int maxExamples = config.getTask().getProactiveReminderBanditMaxExamples();
        if (examples.size() > maxExamples) {
            examples = new ArrayList<>(examples.subList(0, maxExamples));
        }

        if (!examples.isEmpty()) {
            log.info("反事实样本预热完成: userId={}, examples={}", userId, examples.size());
        }
        return examples;
    }

    private Optional<ReminderActionTrainingExample> inferCounterfactualFromEntity(TemporalEntity entity, Instant now) {
        boolean completed = isEntityCompleted(entity);
        boolean expired = entity.isExpired();
        boolean hasRelevantTime = entity.validTo() != null;

        // 跳过：没有时间维度的实体无法做时机推断
        if (!hasRelevantTime && !expired && !completed) {
            return Optional.empty();
        }

        // 计算假设的候选类型和分数
        ReminderCandidateType candidateType = inferCandidateType(entity);
        float urgency = calcCounterfactualUrgency(entity, now);
        float importance = Math.min(1.0f, entity.importanceScore());
        float confidence = Math.min(1.0f, entity.extractionConfidence());

        // 正样本：事项最终完成了 → 如果当时提醒，可能帮助更好地完成
        // 负样本：事项过期且未完成 → 如果当时提醒，可能避免遗忘
        float reward;
        if (completed) {
            // 完成了：中等正向奖励（因为不确定是否因提醒而完成）
            reward = COUNTERFACTUAL_DISCOUNT * 0.72f;
        } else if (expired) {
            // 过期未完成：如果提醒了可能就不会遗忘 → 给高奖励以鼓励提醒
            reward = COUNTERFACTUAL_DISCOUNT * 0.88f;
        } else {
            return Optional.empty();
        }

        return Optional.of(new ReminderActionTrainingExample(
                candidateType.name(),
                urgency >= 0.85f ? ReminderAction.NORMAL_PUSH : ReminderAction.SOFT_PUSH,
                COUNTERFACTUAL_DISCOUNT * (0.30f * confidence + 0.25f * 0.78f + 0.20f * urgency + 0.15f * 0.5f + 0.10f * 0.8f),
                confidence,
                0.78f,
                urgency,
                0.5f,
                0.8f,
                0.0f,
                0.0f,
                0, 0, completed ? 1 : 0, 0, 0, expired ? 1 : 0,
                reward
        ));
    }

    private Optional<ReminderActionTrainingExample> inferCounterfactualFromConversation(ConversationRecord conversation,
                                                                                         Instant now) {
        // 在用户消息中寻找包含时间线索和动作线索的内容
        boolean hasActionCue = false;
        boolean hasCompletionCue = false;

        for (MessageRecord message : conversation.messages()) {
            if (!"user".equalsIgnoreCase(message.role())) {
                continue;
            }
            String content = message.effectiveContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            if (COMPLETION_CUE_PATTERN.matcher(content).find()) {
                hasCompletionCue = true;
            }
            if (ACTION_CUE_PATTERN.matcher(content).find()) {
                hasActionCue = true;
            }
        }

        // 只有同时出现动作意图和完成线索时才生成正样本
        if (!hasActionCue) {
            return Optional.empty();
        }

        float reward = hasCompletionCue
                ? COUNTERFACTUAL_DISCOUNT * 0.68f   // 提到了要做且后来说完成了
                : COUNTERFACTUAL_DISCOUNT * 0.45f;  // 提到了要做但没看到完成信号

        return Optional.of(new ReminderActionTrainingExample(
                ReminderCandidateType.COMMITMENT_GAP.name(),
                ReminderAction.SOFT_PUSH,
                COUNTERFACTUAL_DISCOUNT * 0.60f,
                0.55f, 0.65f, 0.50f, 0.50f, 0.70f,
                0.0f, 0.0f,
                0, 0, hasCompletionCue ? 1 : 0, 0, 0, 0,
                reward
        ));
    }

    private ReminderCandidateType inferCandidateType(TemporalEntity entity) {
        return switch (entity.type()) {
            case EVENT -> ReminderCandidateType.PREPARATION_WINDOW;
            case HABIT -> ReminderCandidateType.HABIT_WINDOW;
            case GOAL, PROJECT -> ReminderCandidateType.COMMITMENT_GAP;
            default -> ReminderCandidateType.DUE_SOON;
        };
    }

    private float calcCounterfactualUrgency(TemporalEntity entity, Instant now) {
        if (entity.validTo() == null) {
            return 0.50f;
        }
        Duration remaining = Duration.between(now, entity.validTo());
        if (remaining.isNegative() || remaining.isZero()) {
            return 1.0f;
        }
        float hours = remaining.toMinutes() / 60.0f;
        return Math.max(0.0f, Math.min(1.0f, 1.0f - hours / 24.0f));
    }

    private Optional<ReminderInferredOutcomeRecord> inferCandidate(ReminderOutcomeInferenceCandidate candidate,
                                                                   Instant now) {
        Optional<WorkspaceItem> relatedWorkspace = loadRelatedWorkspaceItem(candidate);
        return inferFromWorkspace(candidate, relatedWorkspace, now)
                .or(() -> inferFromWorkflow(candidate, relatedWorkspace.orElse(null), now))
                .or(() -> inferFromTraceToolOutput(candidate, relatedWorkspace.orElse(null), now))
                .or(() -> inferFromSemanticEntity(candidate, now))
                .or(() -> inferFromConversation(candidate, now));
    }

    private Optional<ReminderInferredOutcomeRecord> inferFromWorkspace(ReminderOutcomeInferenceCandidate candidate,
                                                                       Optional<WorkspaceItem> relatedWorkspace,
                                                                       Instant now) {
        return relatedWorkspace
                .filter(item -> item.updatedAt().isAfter(candidate.decidedAt()))
                .filter(item -> item.status() == WorkspaceStatus.RESOLVED)
                .map(item -> buildOutcome(
                        candidate,
                        ReminderOutcomeEvidenceSource.WORKSPACE_STATE,
                        0.96f,
                        Map.of(
                                "workspaceItemId", item.id(),
                                "workspaceStatus", item.status().name(),
                                "updatedAt", item.updatedAt().toString(),
                                "title", item.title()
                        ),
                        now
                ));
    }

    private Optional<ReminderInferredOutcomeRecord> inferFromWorkflow(ReminderOutcomeInferenceCandidate candidate,
                                                                      @Nullable WorkspaceItem relatedWorkspace,
                                                                      Instant now) {
        if (workflowRepository == null || !candidate.topicKey().startsWith("workspace:")) {
            return Optional.empty();
        }
        String reference = resolveWorkflowReference(candidate, relatedWorkspace);
        if (reference == null || reference.isBlank()) {
            return Optional.empty();
        }
        Optional<WorkflowInstance> workflowInstance = workflowRepository.findLatestByInstanceIdOrTraceId(reference);
        if (workflowInstance.isEmpty()) {
            return Optional.empty();
        }
        WorkflowInstance instance = workflowInstance.get();
        return inferFromWorkflowInstance(candidate, instance, now)
                .or(() -> inferFromWorkflowStepLogs(candidate, instance, now));
    }

    private Optional<ReminderInferredOutcomeRecord> inferFromTraceToolOutput(ReminderOutcomeInferenceCandidate candidate,
                                                                             @Nullable WorkspaceItem relatedWorkspace,
                                                                             Instant now) {
        if (traceQuery == null || !candidate.topicKey().startsWith("workspace:")) {
            return Optional.empty();
        }
        String traceId = resolveWorkflowReference(candidate, relatedWorkspace);
        if (traceId == null || traceId.isBlank()) {
            return Optional.empty();
        }
        List<String> titleHints = extractTopicHints(candidate.title());
        String normalizedTitle = normalize(candidate.title());
        try {
            return traceQuery.getSteps(traceId).stream()
                    .filter(step -> step instanceof ToolCallStep)
                    .map(step -> (ToolCallStep) step)
                    .filter(ToolCallStep::success)
                    .filter(step -> step.timestamp().isAfter(candidate.decidedAt()))
                    .filter(step -> step.outputJson() != null && !step.outputJson().isBlank())
                    .filter(step -> matchesTraceToolOutput(step, titleHints, normalizedTitle))
                    .reduce((_, current) -> current)
                    .map(step -> buildOutcome(
                            candidate,
                            ReminderOutcomeEvidenceSource.TRACE_TOOL_OUTPUT,
                            0.82f,
                            Map.of(
                                    "traceId", traceId,
                                    "toolId", step.toolId(),
                                    "toolAction", step.toolAction(),
                                    "timestamp", step.timestamp().toString(),
                                    "outputPreview", truncate(step.outputJson(), 160)
                            ),
                            now
                    ));
        } catch (Exception e) {
            log.debug("提醒隐式结果推断跳过 trace 工具输出: traceId={}, error={}", traceId, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<ReminderInferredOutcomeRecord> inferFromWorkflowInstance(ReminderOutcomeInferenceCandidate candidate,
                                                                              WorkflowInstance instance,
                                                                              Instant now) {
        if (instance.state() != WorkflowState.COMPLETED) {
            return Optional.empty();
        }
        Instant completedAt = instance.completedAt() != null ? instance.completedAt() : instance.updatedAt();
        if (completedAt == null || !completedAt.isAfter(candidate.decidedAt())) {
            return Optional.empty();
        }
        return Optional.of(buildOutcome(
                candidate,
                ReminderOutcomeEvidenceSource.WORKFLOW_INSTANCE,
                0.93f,
                Map.of(
                        "workflowInstanceId", instance.id(),
                        "workflowId", instance.workflowId(),
                        "workflowState", instance.state().name(),
                        "traceId", instance.traceId(),
                        "completedAt", completedAt.toString()
                ),
                now
        ));
    }

    private Optional<ReminderInferredOutcomeRecord> inferFromWorkflowStepLogs(ReminderOutcomeInferenceCandidate candidate,
                                                                              WorkflowInstance instance,
                                                                              Instant now) {
        List<StepLog> stepLogs = workflowRepository.findStepLogs(instance.id());
        if (stepLogs.isEmpty()) {
            return Optional.empty();
        }
        List<String> titleHints = extractTopicHints(candidate.title());
        String normalizedTitle = normalize(candidate.title());
        return stepLogs.stream()
                .filter(stepLog -> stepLog.state() == StepState.COMPLETED)
                .filter(stepLog -> completedAt(stepLog).isAfter(candidate.decidedAt()))
                .filter(stepLog -> matchesWorkflowStep(stepLog, titleHints, normalizedTitle))
                .reduce((_, current) -> current)
                .map(stepLog -> buildOutcome(
                        candidate,
                        ReminderOutcomeEvidenceSource.WORKFLOW_STEP_LOG,
                        0.87f,
                        Map.of(
                                "workflowInstanceId", instance.id(),
                                "workflowId", instance.workflowId(),
                                "traceId", instance.traceId(),
                                "stepId", stepLog.stepId(),
                                "stepType", stepLog.stepType(),
                                "completedAt", completedAt(stepLog).toString(),
                                "outputPreview", truncate(stepLog.outputJson(), 160)
                        ),
                        now
                ));
    }

    private Optional<ReminderInferredOutcomeRecord> inferFromSemanticEntity(ReminderOutcomeInferenceCandidate candidate,
                                                                            Instant now) {
        if (semanticMemory == null || !candidate.topicKey().startsWith("entity:")) {
            return Optional.empty();
        }
        String entityId = candidate.topicKey().substring("entity:".length());
        return semanticMemory.findById(entityId)
                .filter(entity -> entity.updatedAt().isAfter(candidate.decidedAt()))
                .filter(this::isEntityCompleted)
                .map(entity -> buildOutcome(
                        candidate,
                        ReminderOutcomeEvidenceSource.SEMANTIC_STATE,
                        entity.isExpired() ? 0.86f : 0.90f,
                        buildSemanticEvidence(entity),
                        now
                ));
    }

    private Optional<ReminderInferredOutcomeRecord> inferFromConversation(ReminderOutcomeInferenceCandidate candidate,
                                                                          Instant now) {
        if (episodicMemory == null || !candidate.topicKey().startsWith("conversation:")) {
            return Optional.empty();
        }
        String sessionId = candidate.topicKey().substring("conversation:".length());
        return episodicMemory.getById(sessionId)
                .flatMap(record -> inferFromConversationMessages(candidate, record, now));
    }

    private Optional<ReminderInferredOutcomeRecord> inferFromConversationMessages(ReminderOutcomeInferenceCandidate candidate,
                                                                                  ConversationRecord record,
                                                                                  Instant now) {
        List<String> titleHints = extractTopicHints(candidate.title());
        for (MessageRecord message : record.messages()) {
            if (!"user".equalsIgnoreCase(message.role())) {
                continue;
            }
            if (!message.createdAt().isAfter(candidate.decidedAt())) {
                continue;
            }
            String content = normalize(message.effectiveContent());
            if (!COMPLETION_CUE_PATTERN.matcher(content).find()) {
                continue;
            }
            if (!containsTopicHint(content, titleHints, normalize(candidate.title()))) {
                continue;
            }
            return Optional.of(buildOutcome(
                    candidate,
                    ReminderOutcomeEvidenceSource.CONVERSATION_MESSAGE,
                    0.76f,
                    Map.of(
                            "sessionId", record.sessionId(),
                            "messageId", message.id(),
                            "messageAt", message.createdAt().toString(),
                            "messagePreview", truncate(message.effectiveContent(), 120)
                    ),
                    now
            ));
        }
        return Optional.empty();
    }

    private ReminderInferredOutcomeRecord buildOutcome(ReminderOutcomeInferenceCandidate candidate,
                                                       ReminderOutcomeEvidenceSource source,
                                                       float confidenceScore,
                                                       Map<String, Object> evidence,
                                                       Instant now) {
        float attributionScore = ReminderRewardModel.inferAttributionScore(
                source,
                confidenceScore,
                candidate.decidedAt(),
                now,
                candidate.candidateType()
        );
        return new ReminderInferredOutcomeRecord(
                UUID.randomUUID().toString(),
                candidate.decisionId(),
                candidate.notificationId(),
                candidate.userId(),
                candidate.topicKey(),
                ReminderOutcomeType.ACTED,
                source,
                confidenceScore,
                attributionScore,
                serializeEvidence(evidence),
                now,
                now,
                now
        );
    }

    private Map<String, Object> buildSemanticEvidence(TemporalEntity entity) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("entityId", entity.id());
        evidence.put("entityType", entity.type().name());
        evidence.put("updatedAt", entity.updatedAt().toString());
        evidence.put("expired", entity.isExpired());
        Object status = entity.properties().get("status");
        if (status != null) {
            evidence.put("status", status.toString());
        }
        for (String key : List.of("completed", "resolved", "done", "finished", "paid", "submitted")) {
            Object value = entity.properties().get(key);
            if (value != null) {
                evidence.put(key, value);
            }
        }
        return Map.copyOf(evidence);
    }

    private boolean isEntityCompleted(TemporalEntity entity) {
        if (entity.isExpired()) {
            return true;
        }
        for (String key : List.of("completed", "resolved", "done", "finished", "paid", "submitted")) {
            if (isTruthy(entity.properties().get(key))) {
                return true;
            }
        }
        Object status = entity.properties().get("status");
        if (status != null) {
            return TERMINAL_STATUS_VALUES.contains(status.toString().trim().toUpperCase(Locale.ROOT));
        }
        return false;
    }

    private Optional<WorkspaceItem> loadRelatedWorkspaceItem(ReminderOutcomeInferenceCandidate candidate) {
        if (workspaceService == null || !candidate.topicKey().startsWith("workspace:")) {
            return Optional.empty();
        }
        String reference = candidate.topicKey().substring("workspace:".length());
        return workspaceService.findLatestByTaskOrItemId(reference);
    }

    @Nullable
    private String resolveWorkflowReference(ReminderOutcomeInferenceCandidate candidate,
                                            @Nullable WorkspaceItem relatedWorkspace) {
        if (relatedWorkspace != null) {
            if (relatedWorkspace.sourceTraceId() != null && !relatedWorkspace.sourceTraceId().isBlank()) {
                return relatedWorkspace.sourceTraceId();
            }
            if (relatedWorkspace.taskId() != null && !relatedWorkspace.taskId().isBlank()) {
                return relatedWorkspace.taskId();
            }
        }
        return candidate.topicKey().startsWith("workspace:")
                ? candidate.topicKey().substring("workspace:".length())
                : null;
    }

    private boolean matchesWorkflowStep(StepLog stepLog, List<String> titleHints, String normalizedTitle) {
        String normalizedStepText = normalize(joinNonBlank(stepLog.stepId(), stepLog.stepType(), stepLog.outputJson()));
        if (normalizedStepText.isBlank()) {
            return false;
        }
        return containsTopicHint(normalizedStepText, titleHints, normalizedTitle);
    }

    private boolean matchesTraceToolOutput(ToolCallStep step, List<String> titleHints, String normalizedTitle) {
        String normalizedText = normalize(joinNonBlank(
                step.toolId(),
                step.toolAction(),
                step.outputJson(),
                step.errorMessage()
        ));
        if (normalizedText.isBlank()) {
            return false;
        }
        return containsTopicHint(normalizedText, titleHints, normalizedTitle)
                && looksCompletedToolOutput(normalizedText);
    }

    private boolean looksCompletedToolOutput(String normalizedText) {
        if (normalizedText == null || normalizedText.isBlank()) {
            return false;
        }
        return normalizedText.contains("完成")
                || normalizedText.contains("成功")
                || normalizedText.contains("提交")
                || normalizedText.contains("已处理")
                || normalizedText.contains("已支付")
                || normalizedText.contains("搞定")
                || normalizedText.contains("done")
                || normalizedText.contains("completed")
                || normalizedText.contains("resolved")
                || normalizedText.contains("submitted")
                || normalizedText.contains("paid")
                || normalizedText.contains("success");
    }

    private Instant completedAt(StepLog stepLog) {
        if (stepLog.completedAt() != null) {
            return stepLog.completedAt();
        }
        if (stepLog.startedAt() != null) {
            return stepLog.startedAt();
        }
        return stepLog.createdAt();
    }

    private String joinNonBlank(@Nullable String... values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(value.strip());
        }
        return builder.toString();
    }

    private boolean isTruthy(@Nullable Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = value.toString().trim().toLowerCase(Locale.ROOT);
        return "true".equals(text) || "1".equals(text) || "yes".equals(text);
    }

    private List<String> extractTopicHints(String title) {
        String normalizedTitle = normalize(title);
        if (normalizedTitle.isBlank()) {
            return List.of();
        }
        List<String> hints = new ArrayList<>();
        hints.add(normalizedTitle);
        if (normalizedTitle.length() <= 8) {
            for (int i = 0; i < normalizedTitle.length() - 1; i++) {
                hints.add(normalizedTitle.substring(i, i + 2));
            }
        }
        return hints.stream().distinct().toList();
    }

    private boolean containsTopicHint(String text, List<String> titleHints, String normalizedTitle) {
        if (!normalizedTitle.isBlank() && text.contains(normalizedTitle)) {
            return true;
        }
        return titleHints.stream()
                .filter(hint -> hint.length() >= 2)
                .anyMatch(text::contains);
    }

    private String normalize(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+", "")
                .toLowerCase(Locale.ROOT);
    }

    private String serializeEvidence(Map<String, Object> evidence) {
        try {
            return MAPPER.writeValueAsString(evidence);
        } catch (Exception e) {
            log.debug("提醒隐式结果证据序列化失败: error={}", e.getMessage());
            return null;
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}
