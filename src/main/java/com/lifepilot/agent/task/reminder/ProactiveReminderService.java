package com.lifepilot.agent.task.reminder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.interaction.model.ResponseContent;
import com.lifepilot.notification.NotificationRequest;
import com.lifepilot.notification.NotificationRepository;
import com.lifepilot.notification.NotificationService;
import com.lifepilot.notification.config.NotificationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 主动提醒服务。
 *
 * <p>负责把采集、决策和通知投递串联为一条完整执行链路。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
public class ProactiveReminderService {

    private static final Logger log = LoggerFactory.getLogger(ProactiveReminderService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REMINDER_TYPE = "proactive_reminder";

    private final ReminderSignalCollector signalCollector;
    private final ReminderDecisionEngine decisionEngine;
    private final NotificationService notificationService;
    private final NotificationRepository notificationRepository;
    @Nullable
    private final ReminderExecutionRepository executionRepository;
    @Nullable
    private final ReminderFeedbackRepository feedbackRepository;
    @Nullable
    private final ReminderOutcomeInferenceService outcomeInferenceService;
    @Nullable
    private final ReminderReplayService replayService;
    @Nullable
    private final ReminderPolicyVersionService policyVersionService;
    private final ReminderPolicyTuner policyTuner;
    private final ReminderOpportunityPolicySelector opportunityPolicySelector;
    private final ReminderActionPolicySelector actionPolicySelector;
    private final ReminderMessageGenerator messageGenerator;
    @Nullable
    private final ReminderSituationSynthesizer situationSynthesizer;
    @Nullable
    private final ReminderFocusStateHolder focusStateHolder;
    private final AgentConfigProperties config;
    private final NotificationProperties notificationProperties;

    /**
     * 供单元测试快速构造的便捷构造器。
     */
    public ProactiveReminderService(ReminderSignalCollector signalCollector,
                                    ReminderDecisionEngine decisionEngine,
                                    NotificationService notificationService,
                                    NotificationRepository notificationRepository,
                                    @Nullable ReminderExecutionRepository executionRepository,
                                    @Nullable ReminderFeedbackRepository feedbackRepository,
                                    ReminderMessageGenerator messageGenerator,
                                    @Nullable ReminderOutcomeInferenceService outcomeInferenceService,
                                    AgentConfigProperties config,
                                    NotificationProperties notificationProperties) {
        this(signalCollector, decisionEngine, notificationService, notificationRepository,
                executionRepository, feedbackRepository, new ReminderPolicyTuner(),
                new ReminderOpportunityPolicySelector(config), new ReminderActionPolicySelector(),
                messageGenerator, outcomeInferenceService, null, null, null, null, config, notificationProperties);
    }

    public ProactiveReminderService(ReminderSignalCollector signalCollector,
                                    ReminderDecisionEngine decisionEngine,
                                    NotificationService notificationService,
                                    NotificationRepository notificationRepository,
                                    @Nullable ReminderExecutionRepository executionRepository,
                                    @Nullable ReminderFeedbackRepository feedbackRepository,
                                    ReminderPolicyTuner policyTuner,
                                    ReminderOpportunityPolicySelector opportunityPolicySelector,
                                    ReminderActionPolicySelector actionPolicySelector,
                                    ReminderMessageGenerator messageGenerator,
                                    @Nullable ReminderOutcomeInferenceService outcomeInferenceService,
                                    @Nullable ReminderReplayService replayService,
                                    @Nullable ReminderPolicyVersionService policyVersionService,
                                    @Nullable ReminderSituationSynthesizer situationSynthesizer,
                                    @Nullable ReminderFocusStateHolder focusStateHolder,
                                    AgentConfigProperties config,
                                    NotificationProperties notificationProperties) {
        this.signalCollector = signalCollector;
        this.decisionEngine = decisionEngine;
        this.notificationService = notificationService;
        this.notificationRepository = notificationRepository;
        this.executionRepository = executionRepository;
        this.feedbackRepository = feedbackRepository;
        this.outcomeInferenceService = outcomeInferenceService;
        this.replayService = replayService;
        this.policyVersionService = policyVersionService;
        this.policyTuner = policyTuner;
        this.opportunityPolicySelector = opportunityPolicySelector;
        this.actionPolicySelector = actionPolicySelector;
        this.messageGenerator = messageGenerator;
        this.situationSynthesizer = situationSynthesizer;
        this.focusStateHolder = focusStateHolder;
        this.config = config;
        this.notificationProperties = notificationProperties;
    }

    /**
     * 执行一次主动提醒评估。
     */
    public ProactiveReminderRunResult runOnce() {
        if (!config.getTask().isProactiveReminderEnabled()) {
            return new ProactiveReminderRunResult(0, 0, 0);
        }
        return execute(notificationProperties.getDefaultUserId(), "periodic", null, null);
    }

    /**
     * 反事实样本预热 — 从历史记忆合成训练样本以缓解冷启动。
     *
     * <p>在系统启动或 Bandit 样本不足时调用。</p>
     *
     * @return 合成的训练样本数量
     */
    public int warmupCounterfactualExamples() {
        if (!config.getTask().isProactiveReminderEnabled() || outcomeInferenceService == null) {
            return 0;
        }
        try {
            String userId = notificationProperties.getDefaultUserId();
            Instant since = Instant.now().minusSeconds(30L * 24 * 3600);
            List<ReminderActionTrainingExample> examples =
                    outcomeInferenceService.inferCounterfactualExamples(userId, since);
            if (examples.isEmpty()) {
                log.debug("反事实样本预热: 无可用样本");
                return 0;
            }
            // 将合成样本持久化到执行仓储，供 Bandit 后续读取
            if (executionRepository != null) {
                executionRepository.saveCounterfactualExamples(userId, examples);
            }
            log.info("反事实样本预热完成: userId={}, examples={}", userId, examples.size());
            return examples.size();
        } catch (Exception e) {
            log.warn("反事实样本预热失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 执行一批延后唤醒的主题重评估。
     */
    public ProactiveReminderRunResult runDeferredWakeups(List<ReminderDeferredWakeup> wakeups) {
        if (!config.getTask().isProactiveReminderEnabled() || wakeups == null || wakeups.isEmpty()) {
            return new ProactiveReminderRunResult(0, 0, 0);
        }

        int topicsCollected = 0;
        int decisionsEvaluated = 0;
        int remindersSent = 0;
        Map<String, Map<String, ReminderDeferredWakeup>> wakeupsByUser = groupWakeupsByUser(wakeups);
        for (Map.Entry<String, Map<String, ReminderDeferredWakeup>> entry : wakeupsByUser.entrySet()) {
            String userId = entry.getKey();
            ReminderRuntimeContext context = buildRuntimeContext(userId);
            if (context.isWithinQuietHours()) {
                log.debug("延后提醒重评估跳过: 当前处于静默时段, userId={}, topics={}",
                        userId, entry.getValue().keySet());
                continue;
            }
            ProactiveReminderRunResult result = execute(userId, "deferred_wakeup",
                    entry.getValue().keySet(), entry.getValue(), context);
            topicsCollected += result.topicsCollected();
            decisionsEvaluated += result.decisionsEvaluated();
            remindersSent += result.remindersSent();
        }
        return new ProactiveReminderRunResult(topicsCollected, decisionsEvaluated, remindersSent);
    }

    private ProactiveReminderRunResult execute(String userId,
                                               String triggerSource,
                                               @Nullable Collection<String> includedTopicKeys,
                                               @Nullable Map<String, ReminderDeferredWakeup> deferredWakeups) {
        return execute(userId, triggerSource, includedTopicKeys, deferredWakeups, null);
    }

    private ProactiveReminderRunResult execute(String userId,
                                               String triggerSource,
                                               @Nullable Collection<String> includedTopicKeys,
                                               @Nullable Map<String, ReminderDeferredWakeup> deferredWakeups,
                                               @Nullable ReminderRuntimeContext existingContext) {
        ReminderRuntimeContext context = existingContext != null ? existingContext : buildRuntimeContext(userId);
        inferOutcomesBeforeEvaluation(userId, context);
        ReminderResolvedPolicy resolvedPolicy = buildResolvedPolicy(userId, context);
        ReminderPolicyConfig policyConfig = resolvedPolicy.config();
        String runId = UUID.randomUUID().toString();
        Set<String> topicFilter = normalizeTopicKeys(includedTopicKeys);
        persistRunStarted(runId, userId, context, resolvedPolicy, triggerSource, topicFilter);

        List<ReminderTopicSnapshot> topics = signalCollector.collect(userId, context);
        if (!topicFilter.isEmpty()) {
            topics = topics.stream()
                    .filter(topic -> topicFilter.contains(topic.topicKey()))
                    .toList();
        }
        List<ReminderDecision> decisions = topics.isEmpty()
                ? List.of()
                : decisionEngine.evaluate(topics, context, policyConfig);
        Map<String, ReminderTopicSnapshot> topicIndex = indexTopics(topics);
        List<ReminderDecisionOutcome> outcomes = applyLearningPolicies(
                userId, decisions, topicIndex, context, policyConfig);

        // 情境合成：将多个相关主题合并为情境化建议
        List<ReminderSituation> situations = synthesizeSituations(outcomes, topicIndex, context);

        int sent = 0;
        for (ReminderDecisionOutcome outcome : outcomes) {
            ReminderDecision decision = outcome.decision();
            String notificationId = null;
            if (decision.action() != ReminderAction.SOFT_PUSH
                    && decision.action() != ReminderAction.NORMAL_PUSH
                    && decision.action() != ReminderAction.PREPARE
                    && decision.action() != ReminderAction.AUTO_EXECUTE) {
                persistDecision(runId, decision, topicIndex.get(decision.candidate().topicKey()),
                        null, outcome.policyTrace(), resolvedPolicy);
                continue;
            }
            ReminderTopicSnapshot snapshot = topicIndex.get(decision.candidate().topicKey());
            // 如果该决策属于某个合成情境，使用情境文案替代
            String situationMessage = findSituationMessage(decision.candidate().topicKey(), situations);
            notificationId = sendReminder(userId, decision, snapshot != null ? snapshot :
                    new ReminderTopicSnapshot(
                            decision.candidate().topicKey(),
                            decision.candidate().title(),
                            List.of(),
                            ReminderTopicState.empty()
                    ), context, resolvedPolicy, situationMessage);
            persistDecision(runId, decision, snapshot, notificationId, outcome.policyTrace(), resolvedPolicy);
            if (notificationId != null) {
                sent++;
            }
        }
        int missingDeferredCount = 0;
        if (deferredWakeups != null && !deferredWakeups.isEmpty()) {
            missingDeferredCount = persistMissingDeferredTopicDecisions(runId, deferredWakeups, topicIndex, resolvedPolicy);
        }
        int totalDecisionsEvaluated = outcomes.size() + missingDeferredCount;
        persistRunFinished(runId, userId, context, topics.size(), totalDecisionsEvaluated,
                sent, resolvedPolicy, triggerSource, topicFilter);

        log.info("主动提醒执行完成: triggerSource={}, topics={}, decisions={}, sent={}",
                triggerSource, topics.size(), totalDecisionsEvaluated, sent);
        return new ProactiveReminderRunResult(topics.size(), totalDecisionsEvaluated, sent);
    }

    private void inferOutcomesBeforeEvaluation(String userId, ReminderRuntimeContext context) {
        if (outcomeInferenceService == null) {
            return;
        }
        try {
            outcomeInferenceService.inferRecentOutcomes(userId, context.now());
        } catch (Exception e) {
            log.debug("主动提醒隐式结果推断跳过: userId={}, error={}", userId, e.getMessage());
        }
    }

    private ReminderRuntimeContext buildRuntimeContext(String userId) {
        Instant now = Instant.now();
        ZoneId zoneId = ZoneId.systemDefault();
        Instant startOfDay = LocalDate.now(zoneId).atStartOfDay(zoneId).toInstant();
        long remindersSentToday = notificationRepository.countSentByUserIdAndTypeSince(
                userId, REMINDER_TYPE, startOfDay);
        ReminderFocusState focusState = focusStateHolder != null ? focusStateHolder.get() : null;
        return new ReminderRuntimeContext(
                now,
                zoneId,
                parseTime(config.getTask().getProactiveReminderQuietHoursStart()),
                parseTime(config.getTask().getProactiveReminderQuietHoursEnd()),
                (int) remindersSentToday,
                focusState
        );
    }

    private ReminderResolvedPolicy buildResolvedPolicy(String userId, ReminderRuntimeContext context) {
        ReminderPolicyConfig baseConfig = new ReminderPolicyConfig(
                24,
                18,
                config.getTask().getProactiveReminderDailyMaxReminders(),
                config.getTask().getProactiveReminderCooldownHours(),
                60,
                0.55f,
                0.63f,
                0.78f,
                0.65f
        );
        ReminderUserFeedbackSummary summary = ReminderUserFeedbackSummary.empty();
        if (feedbackRepository != null) {
            try {
                summary = feedbackRepository.summarizeUserFeedbackByUserIdSince(
                        userId, context.now().minusSeconds(30L * 24 * 3600));
            } catch (Exception e) {
                log.debug("主动提醒策略调优跳过: 读取反馈汇总失败, userId={}, error={}", userId, e.getMessage());
            }
        }
        ReminderReplayReport replayReport = null;
        if (replayService != null && config.getTask().isProactiveReminderReplayTuningEnabled()) {
            Instant since = context.now().minusSeconds(
                    Math.max(1, config.getTask().getProactiveReminderBanditLookbackDays()) * 24L * 3600L
            );
            try {
                ReminderReplayReport loadedReport = replayService.replay(
                        userId,
                        since,
                        config.getTask().getProactiveReminderBanditMaxExamples()
                );
                if (loadedReport.sampleCount() >= config.getTask().getProactiveReminderReplayMinSamples()) {
                    replayReport = loadedReport;
                }
            } catch (Exception e) {
                log.debug("主动提醒策略调优跳过: 离线回放失败, userId={}, error={}", userId, e.getMessage());
            }
        }
        ReminderPolicyConfig tunedConfig = policyTuner.tune(baseConfig, summary, replayReport);
        ReminderPolicyConfig latestPolicyConfig = null;
        ReminderPolicyVersionInsight latestPolicyInsight = ReminderPolicyVersionInsight.empty();
        if (policyVersionService != null) {
            try {
                var latestVersion = policyVersionService.findLatestByUserId(userId);
                if (latestVersion != null && latestVersion.isPresent()) {
                    latestPolicyConfig = policyVersionService.parseConfig(latestVersion.get());
                    latestPolicyInsight = policyVersionService.parseInsight(latestVersion.get());
                }
            } catch (Exception e) {
                log.debug("主动提醒策略护栏跳过: 读取历史版本失败, userId={}, error={}", userId, e.getMessage());
            }
        }
        ReminderPolicyGuardrailResult guardrailResult = policyTuner.applyGuardrail(
                baseConfig,
                tunedConfig,
                latestPolicyConfig,
                latestPolicyInsight,
                summary,
                replayReport,
                config.getTask()
        );
        ReminderPolicyConfig policyConfig = guardrailResult.config();
        ReminderPolicyVersionRecord policyVersion = null;
        if (policyVersionService != null) {
            try {
                policyVersion = policyVersionService.resolve(
                        userId, policyConfig, summary, replayReport, guardrailResult, context.now());
            } catch (Exception e) {
                log.debug("主动提醒策略版本化跳过: userId={}, error={}", userId, e.getMessage());
            }
        }
        return new ReminderResolvedPolicy(policyConfig, policyVersion, guardrailResult);
    }

    private List<ReminderDecisionOutcome> applyLearningPolicies(String userId,
                                                                List<ReminderDecision> decisions,
                                                                Map<String, ReminderTopicSnapshot> topicIndex,
                                                                ReminderRuntimeContext context,
                                                                ReminderPolicyConfig config) {
        if (executionRepository == null || decisions.isEmpty()) {
            return decisions.stream()
                    .map(decision -> new ReminderDecisionOutcome(
                            decision,
                            new ReminderPolicyTrace(
                                    decision.action(),
                                    decision.action(),
                                    decision.action(),
                                    false,
                                    false,
                                    0,
                                    0,
                                    "未启用学习策略或无执行仓储"
                            )
                    ))
                    .toList();
        }
        Instant since = context.now().minusSeconds(
                Math.max(1, this.config.getTask().getProactiveReminderBanditLookbackDays()) * 24L * 3600L
        );
        try {
            List<ReminderActionPerformanceStats> stats = executionRepository.summarizeActionPerformanceByUserIdSince(
                    userId, since);
            ReminderActionPolicyProfile profile = new ReminderActionPolicyProfile(stats);
            List<ReminderActionTrainingExample> trainingExamples;
            if (this.config.getTask().isProactiveReminderBanditEnabled()) {
                List<ReminderActionTrainingExample> loadedExamples =
                        executionRepository.findActionTrainingExamplesByUserIdSince(
                                userId,
                                since,
                                this.config.getTask().getProactiveReminderBanditMaxExamples());
                trainingExamples = loadedExamples != null ? loadedExamples : List.of();
            } else {
                trainingExamples = List.of();
            }
            if (!profile.hasLearningSignal() && trainingExamples.isEmpty()) {
                return decisions.stream()
                        .map(decision -> new ReminderDecisionOutcome(
                                decision,
                                buildPolicyTrace(
                                        decision.action(),
                                        decision.action(),
                                        decision.action(),
                                        false,
                                        false,
                                        trainingExamples.size(),
                                        profile.totalSampleCount(),
                                        "无足够学习信号，沿用规则结果"
                                )
                        ))
                        .toList();
            }
            return decisions.stream()
                    .map(decision -> {
                        ReminderTopicState topicState = topicIndex.containsKey(decision.candidate().topicKey())
                                ? topicIndex.get(decision.candidate().topicKey()).state()
                                : ReminderTopicState.empty();
                        ReminderDecision opportunityDecision = decision;
                        if (this.config.getTask().isProactiveReminderOpportunityLearningEnabled()) {
                            opportunityDecision = opportunityPolicySelector.refine(
                                    decision, topicState, config, trainingExamples);
                        }
                        ReminderDecision finalDecision = actionPolicySelector.refine(
                                opportunityDecision,
                                topicState,
                                config,
                                profile,
                                trainingExamples
                        );
                        return new ReminderDecisionOutcome(
                                finalDecision,
                                buildPolicyTrace(
                                        decision.action(),
                                        opportunityDecision.action(),
                                        finalDecision.action(),
                                        opportunityDecision.action() != decision.action(),
                                        finalDecision.action() != opportunityDecision.action(),
                                        trainingExamples.size(),
                                        profile.totalSampleCount(),
                                        "base=%s, opportunity=%s, final=%s".formatted(
                                                decision.action(),
                                                opportunityDecision.action(),
                                                finalDecision.action()
                                        )
                                )
                        );
                    })
                    .toList();
        } catch (Exception e) {
            log.debug("主动提醒动作策略调整跳过: 读取动作效果失败, userId={}, error={}", userId, e.getMessage());
            return decisions.stream()
                    .map(decision -> new ReminderDecisionOutcome(
                            decision,
                            buildPolicyTrace(
                                    decision.action(),
                                    decision.action(),
                                    decision.action(),
                                    false,
                                    false,
                                    0,
                                    0,
                                    "学习策略异常，回退规则结果"
                            )
                    ))
                    .toList();
        }
    }

    private String sendReminder(String userId,
                                ReminderDecision decision,
                                ReminderTopicSnapshot snapshot,
                                ReminderRuntimeContext context,
                                ReminderResolvedPolicy resolvedPolicy,
                                @Nullable String situationMessage) {
        ReminderCandidate candidate = decision.candidate();
        ReminderMessage message = messageGenerator.generate(userId, decision, snapshot, context);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("topicKey", candidate.topicKey());
        metadata.put("candidateType", candidate.type().name());
        metadata.put("action", decision.action().name());
        metadata.put("finalScore", String.format("%.3f", decision.finalScore()));
        metadata.put("deliveryKey", UUID.randomUUID().toString());
        metadata.put("messageMode", situationMessage != null ? "situation" : message.mode());
        if (resolvedPolicy.policyVersionId() != null) {
            metadata.put("policyVersionId", resolvedPolicy.policyVersionId());
        }
        if (resolvedPolicy.policyVersion() != null) {
            metadata.put("policyVersion", String.valueOf(resolvedPolicy.policyVersion()));
        }
        if (message.providerId() != null && !message.providerId().isBlank()) {
            metadata.put("messageProvider", message.providerId());
        }
        if (message.modelName() != null && !message.modelName().isBlank()) {
            metadata.put("messageModel", message.modelName());
        }

        String title = switch (decision.action()) {
            case NORMAL_PUSH -> "【主动提醒】";
            case PREPARE -> "【预备执行】";
            case AUTO_EXECUTE -> "【自动执行】";
            default -> "【轻提醒】";
        };
        // 情境合成文案优先于单条文案
        String body = situationMessage != null ? situationMessage : message.body();
        if (body.isBlank()) {
            String reason = candidate.rationale().isBlank() ? decision.reason() : candidate.rationale();
            body = candidate.title() + "\n" + "原因：" + reason;
        }
        String text = title + "\n" + body;

        List<String> notificationIds = notificationService.send(new NotificationRequest(
                userId,
                new ResponseContent.TextContent(text),
                "WEB",
                REMINDER_TYPE,
                metadata
        ));
        return notificationIds.isEmpty() ? null : notificationIds.getFirst();
    }

    private List<ReminderSituation> synthesizeSituations(List<ReminderDecisionOutcome> outcomes,
                                                         Map<String, ReminderTopicSnapshot> topicIndex,
                                                         ReminderRuntimeContext context) {
        if (situationSynthesizer == null) {
            return List.of();
        }
        try {
            return situationSynthesizer.synthesize(outcomes, topicIndex, context);
        } catch (Exception e) {
            log.debug("情境合成跳过: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 查找某个 topicKey 所属的情境合成文案（仅当该情境包含多个主题时有效）。
     * 单主题情境返回 null，沿用原有的 MessageGenerator 路径。
     */
    @Nullable
    private String findSituationMessage(String topicKey, List<ReminderSituation> situations) {
        for (ReminderSituation situation : situations) {
            if (situation.topicKeys().contains(topicKey)
                    && situation.topicKeys().size() > 1
                    && situation.message() != null
                    && !situation.message().isBlank()) {
                return situation.message();
            }
        }
        return null;
    }

    private Map<String, ReminderTopicSnapshot> indexTopics(List<ReminderTopicSnapshot> topics) {
        Map<String, ReminderTopicSnapshot> indexed = new LinkedHashMap<>();
        for (ReminderTopicSnapshot topic : topics) {
            indexed.put(topic.topicKey(), topic);
        }
        return indexed;
    }

    private void persistRunStarted(String runId,
                                   String userId,
                                   ReminderRuntimeContext context,
                                   ReminderResolvedPolicy resolvedPolicy,
                                   String triggerSource,
                                   Set<String> topicFilter) {
        if (executionRepository == null) {
            return;
        }
        Instant now = context.now();
        try {
            executionRepository.saveRun(new ReminderRunRecord(
                    runId,
                    userId,
                    now,
                    null,
                    0,
                    0,
                    0,
                    resolvedPolicy.policyVersionId(),
                    resolvedPolicy.policyVersion(),
                    buildContextJson(context, resolvedPolicy, triggerSource, topicFilter),
                    now,
                    now
            ));
        } catch (Exception e) {
            log.warn("主动提醒落库失败: 保存执行轮次失败, runId={}, error={}", runId, e.getMessage());
        }
    }

    private void persistRunFinished(String runId,
                                    String userId,
                                    ReminderRuntimeContext context,
                                    int topicsCollected,
                                    int decisionsEvaluated,
                                    int remindersSent,
                                    ReminderResolvedPolicy resolvedPolicy,
                                    String triggerSource,
                                    Set<String> topicFilter) {
        if (executionRepository == null) {
            return;
        }
        Instant finishedAt = Instant.now();
        try {
            executionRepository.updateRun(new ReminderRunRecord(
                    runId,
                    userId,
                    context.now(),
                    finishedAt,
                    topicsCollected,
                    decisionsEvaluated,
                    remindersSent,
                    resolvedPolicy.policyVersionId(),
                    resolvedPolicy.policyVersion(),
                    buildContextJson(context, resolvedPolicy, triggerSource, topicFilter),
                    context.now(),
                    finishedAt
            ));
        } catch (Exception e) {
            log.warn("主动提醒落库失败: 更新执行轮次失败, runId={}, error={}", runId, e.getMessage());
        }
    }

    private int persistMissingDeferredTopicDecisions(String runId,
                                                     Map<String, ReminderDeferredWakeup> deferredWakeups,
                                                     Map<String, ReminderTopicSnapshot> topicIndex,
                                                     ReminderResolvedPolicy resolvedPolicy) {
        if (executionRepository == null) {
            return 0;
        }
        Instant now = Instant.now();
        int persisted = 0;
        for (ReminderDeferredWakeup wakeup : deferredWakeups.values()) {
            if (topicIndex.containsKey(wakeup.topicKey())) {
                continue;
            }
            try {
                executionRepository.saveDecision(new ReminderDecisionRecord(
                        UUID.randomUUID().toString(),
                        runId,
                        wakeup.topicKey(),
                        wakeup.title(),
                        wakeup.signalId(),
                        wakeup.candidateType(),
                        ReminderAction.SKIP.name(),
                        "延后提醒到期后未再找到对应主题",
                        "该主题已从当前提醒候选中移除",
                        0.0f,
                        0.0f,
                        0.0f,
                        0.0f,
                        0.5f,
                        0.0f,
                        0.0f,
                        0.0f,
                        wakeup.nextEvaluationAt(),
                        null,
                        false,
                        null,
                        null,
                        0,
                        0,
                        0,
                        0,
                        0,
                        0,
                        false,
                        resolvedPolicy.policyVersionId(),
                        resolvedPolicy.policyVersion(),
                        now,
                        now
                ));
                persisted++;
            } catch (Exception e) {
                log.warn("主动提醒落库失败: 记录缺失主题决策失败, runId={}, topicKey={}, error={}",
                        runId, wakeup.topicKey(), e.getMessage());
            }
        }
        return persisted;
    }

    private void persistDecision(String runId,
                                 ReminderDecision decision,
                                 @Nullable ReminderTopicSnapshot snapshot,
                                 @Nullable String notificationId,
                                 @Nullable ReminderPolicyTrace policyTrace,
                                 ReminderResolvedPolicy resolvedPolicy) {
        if (executionRepository == null || snapshot == null) {
            return;
        }
        Instant now = Instant.now();
        String decisionId = UUID.randomUUID().toString();
        ReminderCandidate candidate = decision.candidate();
        ReminderTopicState state = snapshot.state();
        try {
            executionRepository.saveDecision(new ReminderDecisionRecord(
                    decisionId,
                    runId,
                    candidate.topicKey(),
                    candidate.title(),
                    candidate.signalId(),
                    candidate.type().name(),
                    decision.action().name(),
                    decision.reason(),
                    candidate.rationale(),
                    candidate.finalScore(),
                    candidate.evidenceScore(),
                    candidate.timingScore(),
                    candidate.urgencyScore(),
                    candidate.userFitScore(),
                    candidate.actionabilityScore(),
                    candidate.duplicatePenalty(),
                    candidate.fatiguePenalty(),
                    candidate.suggestedAt(),
                    decision.nextEvaluationAt(),
                    notificationId != null,
                    notificationId,
                    state.lastRemindedAt(),
                    state.remindersSentToday(),
                    state.readCount30d(),
                    state.actedCount30d(),
                    state.dismissedCount30d(),
                    state.snoozedCount30d(),
                    state.notRelevantCount30d(),
                    state.muted(),
                    resolvedPolicy.policyVersionId(),
                    resolvedPolicy.policyVersion(),
                    now,
                    now
            ));
            executionRepository.saveEvidenceBatch(snapshot.signals().stream()
                    .map(signal -> toEvidenceRecord(decisionId, signal, now))
                    .toList());
            if (policyTrace != null) {
                executionRepository.savePolicyTrace(new ReminderPolicyTraceRecord(
                        decisionId,
                        policyTrace.baseAction().name(),
                        policyTrace.opportunityAction().name(),
                        policyTrace.finalAction().name(),
                        policyTrace.opportunityAdjusted(),
                        policyTrace.actionAdjusted(),
                        policyTrace.trainingExampleCount(),
                        policyTrace.actionFeedbackSampleCount(),
                        buildPolicyTraceJson(policyTrace),
                        now
                ));
            }
        } catch (Exception e) {
            log.warn("主动提醒落库失败: 保存决策记录失败, runId={}, topicKey={}, error={}",
                    runId, candidate.topicKey(), e.getMessage());
        }
    }

    private ReminderPolicyTrace buildPolicyTrace(ReminderAction baseAction,
                                                 ReminderAction opportunityAction,
                                                 ReminderAction finalAction,
                                                 boolean opportunityAdjusted,
                                                 boolean actionAdjusted,
                                                 int trainingExampleCount,
                                                 int actionFeedbackSampleCount,
                                                 String summary) {
        return new ReminderPolicyTrace(
                baseAction,
                opportunityAction,
                finalAction,
                opportunityAdjusted,
                actionAdjusted,
                trainingExampleCount,
                actionFeedbackSampleCount,
                summary
        );
    }

    private String buildPolicyTraceJson(ReminderPolicyTrace policyTrace) {
        try {
            return MAPPER.writeValueAsString(Map.of(
                    "baseAction", policyTrace.baseAction().name(),
                    "opportunityAction", policyTrace.opportunityAction().name(),
                    "finalAction", policyTrace.finalAction().name(),
                    "opportunityAdjusted", policyTrace.opportunityAdjusted(),
                    "actionAdjusted", policyTrace.actionAdjusted(),
                    "trainingExampleCount", policyTrace.trainingExampleCount(),
                    "actionFeedbackSampleCount", policyTrace.actionFeedbackSampleCount(),
                    "summary", policyTrace.summary()
            ));
        } catch (Exception e) {
            log.debug("主动提醒策略追踪序列化失败: error={}", e.getMessage());
            return null;
        }
    }

    private ReminderEvidenceRecord toEvidenceRecord(String decisionId,
                                                    ReminderSignal signal,
                                                    Instant createdAt) {
        Integer leadMinutes = signal.preparationLeadTime() != null
                ? Math.toIntExact(signal.preparationLeadTime().toMinutes())
                : null;
        return new ReminderEvidenceRecord(
                UUID.randomUUID().toString(),
                decisionId,
                signal.signalId(),
                signal.kind().name(),
                signal.confidenceScore(),
                signal.importanceScore(),
                signal.evidenceCount(),
                signal.observedAt(),
                signal.relevantAt(),
                leadMinutes,
                signal.preferredWindowStartHour(),
                signal.preferredWindowEndHour(),
                signal.anomalyScore(),
                signal.actionable(),
                signal.resolved(),
                signal.summary(),
                createdAt
        );
    }

    private String buildContextJson(ReminderRuntimeContext context,
                                    ReminderResolvedPolicy resolvedPolicy,
                                    String triggerSource,
                                    Set<String> topicFilter) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("now", context.now().toString());
            payload.put("zoneId", context.zoneId().getId());
            payload.put("quietHoursStart", context.quietHoursStart() != null ? context.quietHoursStart().toString() : null);
            payload.put("quietHoursEnd", context.quietHoursEnd() != null ? context.quietHoursEnd().toString() : null);
            payload.put("remindersSentToday", context.remindersSentToday());
            payload.put("triggerSource", triggerSource);
            if (!topicFilter.isEmpty()) {
                payload.put("includedTopics", topicFilter);
            }
            if (resolvedPolicy.policyVersionId() != null) {
                payload.put("policyVersionId", resolvedPolicy.policyVersionId());
            }
            if (resolvedPolicy.policyVersion() != null) {
                payload.put("policyVersion", resolvedPolicy.policyVersion());
            }
            if (resolvedPolicy.policySource() != null) {
                payload.put("policySource", resolvedPolicy.policySource());
            }
            payload.put("policyGuardrail", Map.of(
                    "adjusted", resolvedPolicy.guardrailAdjusted(),
                    "rolledBack", resolvedPolicy.guardrailRolledBack(),
                    "summary", resolvedPolicy.guardrailSummary()
            ));
            ReminderPolicyConfig policyConfig = resolvedPolicy.config();
            payload.put("policy", Map.of(
                    "dueSoonThresholdHours", policyConfig.dueSoonThresholdHours(),
                    "commitmentGapThresholdHours", policyConfig.commitmentGapThresholdHours(),
                    "dailyMaxReminders", policyConfig.dailyMaxReminders(),
                    "defaultCooldownHours", policyConfig.defaultCooldownHours(),
                    "preferredWindowLookaheadMinutes", policyConfig.preferredWindowLookaheadMinutes(),
                    "minFinalScore", policyConfig.minFinalScore(),
                    "softPushThreshold", policyConfig.softPushThreshold(),
                    "strongPushThreshold", policyConfig.strongPushThreshold(),
                    "anomalyThreshold", policyConfig.anomalyThreshold()
            ));
            return MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            log.debug("主动提醒落库: 上下文序列化失败, error={}", e.getMessage());
            return null;
        }
    }

    private record ReminderResolvedPolicy(ReminderPolicyConfig config,
                                          @Nullable ReminderPolicyVersionRecord versionRecord,
                                          ReminderPolicyGuardrailResult guardrailResult) {

        private ReminderResolvedPolicy {
            config = config != null ? config : new ReminderPolicyConfig();
            guardrailResult = guardrailResult != null
                    ? guardrailResult
                    : ReminderPolicyGuardrailResult.passthrough(config);
        }

        @Nullable
        private String policyVersionId() {
            return versionRecord != null ? versionRecord.id() : null;
        }

        @Nullable
        private Integer policyVersion() {
            return versionRecord != null ? versionRecord.version() : null;
        }

        @Nullable
        private String policySource() {
            return versionRecord != null ? versionRecord.source() : null;
        }

        private boolean guardrailAdjusted() {
            return guardrailResult.adjusted();
        }

        private boolean guardrailRolledBack() {
            return guardrailResult.rolledBack();
        }

        private String guardrailSummary() {
            return guardrailResult.summary();
        }
    }

    private Set<String> normalizeTopicKeys(@Nullable Collection<String> includedTopicKeys) {
        if (includedTopicKeys == null || includedTopicKeys.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String topicKey : includedTopicKeys) {
            if (topicKey != null && !topicKey.isBlank()) {
                normalized.add(topicKey);
            }
        }
        return Set.copyOf(normalized);
    }

    private Map<String, Map<String, ReminderDeferredWakeup>> groupWakeupsByUser(List<ReminderDeferredWakeup> wakeups) {
        Map<String, Map<String, ReminderDeferredWakeup>> grouped = new LinkedHashMap<>();
        for (ReminderDeferredWakeup wakeup : wakeups) {
            grouped.computeIfAbsent(wakeup.userId(), _ -> new LinkedHashMap<>())
                    .put(wakeup.topicKey(), wakeup);
        }
        return grouped;
    }

    private LocalTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return LocalTime.parse(value);
    }
}
