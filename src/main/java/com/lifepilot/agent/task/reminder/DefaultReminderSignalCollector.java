package com.lifepilot.agent.task.reminder;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.memory.episodic.ConversationRecord;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.episodic.MessageRecord;
import com.lifepilot.memory.procedural.PreferenceRule;
import com.lifepilot.memory.procedural.ProceduralMemory;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.memory.workspace.SessionWorkspaceService;
import com.lifepilot.memory.workspace.WorkspaceItem;
import com.lifepilot.memory.workspace.WorkspaceItemKind;
import com.lifepilot.notification.NotificationRecord;
import com.lifepilot.notification.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 默认提醒信号采集器。
 *
 * <p>从 L2/L3/L4 和记忆运行态历史生成提醒主题。
 * 当前采集源包括：语义记忆、偏好规则、近期对话、工作区状态和经验反思。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
public class DefaultReminderSignalCollector implements ReminderSignalCollector {

    private static final Logger log = LoggerFactory.getLogger(DefaultReminderSignalCollector.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String REMINDER_TYPE = "proactive_reminder";
    private static final Duration RECENT_CONVERSATION_LOOKBACK = Duration.ofDays(7);
    private static final int MAX_RECENT_CONVERSATIONS = 12;
    private static final Pattern ACTION_CUE_PATTERN = Pattern.compile(
            "提醒|记得|别忘|待办|安排|准备|处理|提交|缴费|报销|整理|复盘|预约|确认|推进|跟进|打卡|买|出发|会议|计划");
    private static final Pattern TIME_CUE_PATTERN = Pattern.compile(
            "今天|今晚|明天|后天|本周|下周|周[一二三四五六日天]|周末|月底|月初|每天|每周|每月|\\d{1,2}:\\d{2}|\\d{1,2}点|\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}|\\d{1,2}月\\d{1,2}日");
    private static final Pattern HABIT_CUE_PATTERN = Pattern.compile(
            "每天|每周|每月|通常|习惯|例行|复盘|整理|打卡|健身|喝水|吃药|睡觉|起床");
    private static final Pattern EVENT_CUE_PATTERN = Pattern.compile(
            "会议|面试|预约|活动|出发|航班|火车|聚会|上课|复诊|周会|晨会|晚会");
    private static final Pattern ISO_DATE_TIME_PATTERN = Pattern.compile(
            "(20\\d{2})[-/](\\d{1,2})[-/](\\d{1,2})(?:[ T](\\d{1,2})(?::(\\d{2}))?)?");
    private static final Pattern CN_DATE_TIME_PATTERN = Pattern.compile(
            "(\\d{1,2})月(\\d{1,2})日(?:[日号\\s]*(上午|下午|晚上|中午|凌晨)?\\s*(\\d{1,2})(?::(\\d{2}))?)?");
    private static final Pattern TIME_IN_TEXT_PATTERN = Pattern.compile(
            "(上午|下午|晚上|中午|凌晨)?\\s*(\\d{1,2})(?::(\\d{2}))?\\s*(?:点|时)?");
    private static final Pattern LEAD_HOURS_PATTERN = Pattern.compile("提前\\s*(\\d{1,2})\\s*小时");
    private static final Pattern LEAD_MINUTES_PATTERN = Pattern.compile("提前\\s*(\\d{1,3})\\s*分钟");

    @Nullable
    private final SemanticMemory semanticMemory;
    @Nullable
    private final ProceduralMemory proceduralMemory;
    @Nullable
    private final EpisodicMemory episodicMemory;
    @Nullable
    private final SessionWorkspaceService workspaceService;
    private final NotificationRepository notificationRepository;
    @Nullable
    private final ReminderFeedbackRepository feedbackRepository;
    @Nullable
    private final ReminderOutcomeRepository outcomeRepository;
    @Nullable
    private final ReminderTopicAliasRepository topicAliasRepository;

    public DefaultReminderSignalCollector(@Nullable SemanticMemory semanticMemory,
                                          @Nullable ProceduralMemory proceduralMemory,
                                          NotificationRepository notificationRepository) {
        this(semanticMemory, proceduralMemory, null, null, notificationRepository, null, null, null);
    }

    public DefaultReminderSignalCollector(@Nullable SemanticMemory semanticMemory,
                                          @Nullable ProceduralMemory proceduralMemory,
                                          @Nullable EpisodicMemory episodicMemory,
                                          @Nullable SessionWorkspaceService workspaceService,
                                          NotificationRepository notificationRepository) {
        this(semanticMemory, proceduralMemory, episodicMemory, workspaceService,
                notificationRepository, null, null, null);
    }

    public DefaultReminderSignalCollector(@Nullable SemanticMemory semanticMemory,
                                          @Nullable ProceduralMemory proceduralMemory,
                                          @Nullable EpisodicMemory episodicMemory,
                                          @Nullable SessionWorkspaceService workspaceService,
                                          NotificationRepository notificationRepository,
                                          @Nullable ReminderFeedbackRepository feedbackRepository) {
        this(semanticMemory, proceduralMemory, episodicMemory, workspaceService,
                notificationRepository, feedbackRepository, null, null);
    }

    public DefaultReminderSignalCollector(@Nullable SemanticMemory semanticMemory,
                                          @Nullable ProceduralMemory proceduralMemory,
                                          @Nullable EpisodicMemory episodicMemory,
                                          @Nullable SessionWorkspaceService workspaceService,
                                          NotificationRepository notificationRepository,
                                          @Nullable ReminderFeedbackRepository feedbackRepository,
                                          @Nullable ReminderOutcomeRepository outcomeRepository) {
        this(semanticMemory, proceduralMemory, episodicMemory, workspaceService,
                notificationRepository, feedbackRepository, outcomeRepository, null);
    }

    public DefaultReminderSignalCollector(@Nullable SemanticMemory semanticMemory,
                                          @Nullable ProceduralMemory proceduralMemory,
                                          @Nullable EpisodicMemory episodicMemory,
                                          @Nullable SessionWorkspaceService workspaceService,
                                          NotificationRepository notificationRepository,
                                          @Nullable ReminderFeedbackRepository feedbackRepository,
                                          @Nullable ReminderOutcomeRepository outcomeRepository,
                                          @Nullable ReminderTopicAliasRepository topicAliasRepository) {
        this.semanticMemory = semanticMemory;
        this.proceduralMemory = proceduralMemory;
        this.episodicMemory = episodicMemory;
        this.workspaceService = workspaceService;
        this.notificationRepository = notificationRepository;
        this.feedbackRepository = feedbackRepository;
        this.outcomeRepository = outcomeRepository;
        this.topicAliasRepository = topicAliasRepository;
    }

    @Override
    public List<ReminderTopicSnapshot> collect(String userId, ReminderRuntimeContext context) {
        Map<String, ReminderTopicSnapshot> topics = new LinkedHashMap<>();
        Map<String, String> aliasMap = loadAliasMap(userId);
        Map<String, List<NotificationRecord>> historyByTopic = canonicalizeHistoryByTopic(
                loadHistoryByTopic(userId, context),
                aliasMap
        );
        Map<String, ReminderTopicFeedbackStats> feedbackStatsByTopic = canonicalizeFeedbackStatsByTopic(
                loadFeedbackStatsByTopic(userId, context),
                aliasMap
        );
        Map<String, Integer> inferredOutcomeCountsByTopic = canonicalizeOutcomeCountsByTopic(
                loadInferredOutcomeCountsByTopic(userId, context),
                aliasMap
        );
        Set<String> recentSessionIds = new LinkedHashSet<>();

        collectSemanticEntities(historyByTopic, feedbackStatsByTopic, inferredOutcomeCountsByTopic, topics, context);
        collectPreferenceRules(historyByTopic, feedbackStatsByTopic, inferredOutcomeCountsByTopic, topics, context);
        collectExperienceEntities(userId, aliasMap, historyByTopic, feedbackStatsByTopic,
                inferredOutcomeCountsByTopic, topics, context);
        collectRecentConversations(historyByTopic, feedbackStatsByTopic, inferredOutcomeCountsByTopic,
                topics, recentSessionIds, userId, aliasMap, context);
        collectWorkspaceItems(historyByTopic, feedbackStatsByTopic, inferredOutcomeCountsByTopic,
                topics, recentSessionIds, userId, aliasMap, context);

        return topics.values().stream()
                .sorted(Comparator.comparing(ReminderTopicSnapshot::title))
                .toList();
    }

    private void collectSemanticEntities(Map<String, List<NotificationRecord>> historyByTopic,
                                         Map<String, ReminderTopicFeedbackStats> feedbackStatsByTopic,
                                         Map<String, Integer> inferredOutcomeCountsByTopic,
                                         Map<String, ReminderTopicSnapshot> topics,
                                         ReminderRuntimeContext context) {
        if (semanticMemory == null) {
            return;
        }
        collectEntitiesOfType(EntityType.EVENT, historyByTopic, feedbackStatsByTopic, inferredOutcomeCountsByTopic,
                topics, context);
        collectEntitiesOfType(EntityType.HABIT, historyByTopic, feedbackStatsByTopic, inferredOutcomeCountsByTopic,
                topics, context);
        collectEntitiesOfType(EntityType.GOAL, historyByTopic, feedbackStatsByTopic, inferredOutcomeCountsByTopic,
                topics, context);
        collectEntitiesOfType(EntityType.PROJECT, historyByTopic, feedbackStatsByTopic, inferredOutcomeCountsByTopic,
                topics, context);
    }

    private void collectEntitiesOfType(EntityType type,
                                       Map<String, List<NotificationRecord>> historyByTopic,
                                       Map<String, ReminderTopicFeedbackStats> feedbackStatsByTopic,
                                       Map<String, Integer> inferredOutcomeCountsByTopic,
                                       Map<String, ReminderTopicSnapshot> topics,
                                       ReminderRuntimeContext context) {
        List<TemporalEntity> entities = semanticMemory.findCurrentByType(type);
        for (TemporalEntity entity : entities) {
            ReminderSignal signal = toSignal(entity);
            if (signal == null) {
                continue;
            }
            String topicKey = "entity:" + entity.id();
            upsertTopic(topics, topicKey, entity.name(), signal,
                    resolveTopicState(historyByTopic.get(topicKey), feedbackStatsByTopic.get(topicKey),
                            inferredOutcomeCountsByTopic.getOrDefault(topicKey, 0), context));
        }
    }

    private void collectPreferenceRules(Map<String, List<NotificationRecord>> historyByTopic,
                                        Map<String, ReminderTopicFeedbackStats> feedbackStatsByTopic,
                                        Map<String, Integer> inferredOutcomeCountsByTopic,
                                        Map<String, ReminderTopicSnapshot> topics,
                                        ReminderRuntimeContext context) {
        if (proceduralMemory == null) {
            return;
        }
        for (PreferenceRule rule : proceduralMemory.listAllPreferences()) {
            ReminderSignal signal = toSignal(rule);
            if (signal == null) {
                continue;
            }
            String topicKey = "preference:" + rule.ruleId();
            upsertTopic(topics, topicKey, rule.key(), signal,
                    resolveTopicState(historyByTopic.get(topicKey), feedbackStatsByTopic.get(topicKey),
                            inferredOutcomeCountsByTopic.getOrDefault(topicKey, 0), context));
        }
    }

    private void collectExperienceEntities(String userId,
                                           Map<String, String> aliasMap,
                                           Map<String, List<NotificationRecord>> historyByTopic,
                                           Map<String, ReminderTopicFeedbackStats> feedbackStatsByTopic,
                                           Map<String, Integer> inferredOutcomeCountsByTopic,
                                           Map<String, ReminderTopicSnapshot> topics,
                                           ReminderRuntimeContext context) {
        if (semanticMemory == null) {
            return;
        }
        for (TemporalEntity entity : semanticMemory.findCurrentByType(EntityType.EXPERIENCE)) {
            ReminderSignal signal = toExperienceSignal(entity, context);
            if (signal == null) {
                continue;
            }
            String rawTopicKey = "experience:" + entity.id();
            ResolvedTopicKey resolvedTopicKey = resolveTopicKey(
                    userId, aliasMap, rawTopicKey, entity.name(), signal, true, context.now());
            upsertTopic(topics, resolvedTopicKey.topicKey(), entity.name(), signal,
                    resolveTopicState(resolvedTopicKey.stateKeys(), historyByTopic, feedbackStatsByTopic,
                            inferredOutcomeCountsByTopic, context));
        }
    }

    private void collectRecentConversations(Map<String, List<NotificationRecord>> historyByTopic,
                                            Map<String, ReminderTopicFeedbackStats> feedbackStatsByTopic,
                                            Map<String, Integer> inferredOutcomeCountsByTopic,
                                            Map<String, ReminderTopicSnapshot> topics,
                                            Set<String> recentSessionIds,
                                            String userId,
                                            Map<String, String> aliasMap,
                                            ReminderRuntimeContext context) {
        if (episodicMemory == null) {
            return;
        }
        List<ConversationRecord> conversations = episodicMemory.getRecent(RECENT_CONVERSATION_LOOKBACK).stream()
                .limit(MAX_RECENT_CONVERSATIONS)
                .toList();
        for (ConversationRecord conversation : conversations) {
            recentSessionIds.add(conversation.sessionId());
            ReminderSignal signal = toConversationSignal(conversation, context);
            if (signal == null) {
                continue;
            }
            String rawTopicKey = "conversation:" + conversation.sessionId();
            ResolvedTopicKey resolvedTopicKey = resolveTopicKey(
                    userId, aliasMap, rawTopicKey, buildConversationTitle(conversation), signal, true, context.now());
            upsertTopic(topics, resolvedTopicKey.topicKey(), buildConversationTitle(conversation), signal,
                    resolveTopicState(resolvedTopicKey.stateKeys(), historyByTopic, feedbackStatsByTopic,
                            inferredOutcomeCountsByTopic, context));
        }
    }

    private void collectWorkspaceItems(Map<String, List<NotificationRecord>> historyByTopic,
                                       Map<String, ReminderTopicFeedbackStats> feedbackStatsByTopic,
                                       Map<String, Integer> inferredOutcomeCountsByTopic,
                                       Map<String, ReminderTopicSnapshot> topics,
                                       Set<String> recentSessionIds,
                                       String userId,
                                       Map<String, String> aliasMap,
                                       ReminderRuntimeContext context) {
        if (workspaceService == null || recentSessionIds.isEmpty()) {
            return;
        }
        for (String sessionId : recentSessionIds) {
            List<WorkspaceItem> items;
            try {
                items = workspaceService.listActive(sessionId);
            } catch (Exception e) {
                log.debug("提醒采集: 读取工作区失败, sessionId={}, error={}", sessionId, e.getMessage());
                continue;
            }
            for (WorkspaceItem item : items) {
                ReminderSignal signal = toWorkspaceSignal(item);
                if (signal == null) {
                    continue;
                }
                String rawTopicKey = item.taskId() != null && !item.taskId().isBlank()
                        ? "workspace:" + item.taskId()
                        : "workspace:" + item.id();
                ResolvedTopicKey resolvedTopicKey = resolveTopicKey(
                        userId, aliasMap, rawTopicKey, item.title(), signal,
                        item.taskId() == null || item.taskId().isBlank(), context.now());
                upsertTopic(topics, resolvedTopicKey.topicKey(), item.title(), signal,
                        resolveTopicState(resolvedTopicKey.stateKeys(), historyByTopic, feedbackStatsByTopic,
                                inferredOutcomeCountsByTopic, context));
            }
        }
    }

    private void upsertTopic(Map<String, ReminderTopicSnapshot> topics,
                             String topicKey,
                             String title,
                             ReminderSignal signal,
                             ReminderTopicState state) {
        ReminderTopicSnapshot existing = topics.get(topicKey);
        if (existing == null) {
            topics.put(topicKey, new ReminderTopicSnapshot(
                    topicKey,
                    title,
                    List.of(signal),
                    state
            ));
            return;
        }
        List<ReminderSignal> mergedSignals = new ArrayList<>(existing.signals());
        mergedSignals.add(signal);
        topics.put(topicKey, new ReminderTopicSnapshot(
                topicKey,
                firstNonBlank(existing.title(), title),
                mergedSignals,
                mergeTopicState(existing.state(), state)
        ));
    }

    @Nullable
    private ReminderSignal toSignal(TemporalEntity entity) {
        Map<String, Object> properties = entity.properties();
        Instant relevantAt = extractInstant(properties, "deadline", "dueAt", "due_at", "startAt",
                "start_at", "endAt", "end_at", "scheduledAt", "scheduled_at", "time");
        Integer startHour = extractHour(properties, "preferredHour", "preferredStartHour", "startHour",
                "preferredTime", "remindAt", "time");
        Integer endHour = startHour != null
                ? Integer.valueOf((startHour + 2) % 24)
                : extractHour(properties, "preferredEndHour", "endHour");

        ReminderSignalKind kind = switch (entity.type()) {
            case EVENT -> ReminderSignalKind.EVENT;
            case HABIT -> ReminderSignalKind.HABIT;
            case GOAL, PROJECT -> relevantAt != null ? ReminderSignalKind.DEADLINE : ReminderSignalKind.COMMITMENT;
            default -> null;
        };
        if (kind == null) {
            return null;
        }

        int evidenceCount = Math.max(1, (entity.sourceConversationId() != null ? 1 : 0) + Math.min(2, entity.accessCount()));
        boolean actionable = !Boolean.FALSE.equals(properties.get("actionable"));

        return new ReminderSignal(
                entity.id(),
                kind,
                entity.extractionConfidence() > 0.0f ? entity.extractionConfidence() : 0.65f,
                entity.importanceScore() > 0.0f ? entity.importanceScore() : 0.60f,
                evidenceCount,
                entity.updatedAt(),
                relevantAt,
                extractDuration(properties, "preparationLeadMinutes", "leadMinutes"),
                startHour,
                endHour,
                extractFloat(properties, "anomalyScore"),
                actionable,
                entity.isExpired(),
                entity.description()
        );
    }

    @Nullable
    private ReminderSignal toSignal(PreferenceRule rule) {
        Integer hour = extractHour(rule.value(), rule.key());
        if (hour == null) {
            return null;
        }
        return new ReminderSignal(
                rule.ruleId(),
                ReminderSignalKind.HABIT,
                rule.confidence(),
                Math.min(1.0f, 0.40f + rule.observationCount() * 0.08f),
                Math.max(1, rule.observationCount()),
                rule.updatedAt(),
                null,
                null,
                hour,
                (hour + 2) % 24,
                0.0f,
                true,
                false,
                rule.value()
        );
    }

    @Nullable
    private ReminderSignal toExperienceSignal(TemporalEntity entity, ReminderRuntimeContext context) {
        Map<String, Object> properties = entity.properties();
        if (Boolean.TRUE.equals(properties.get("subtask"))) {
            return null;
        }
        String text = joinNonBlank(
                entity.name(),
                entity.description(),
                stringify(properties.get("applicableConditions")),
                stringify(properties.get("lessons"))
        );
        if (!looksReminderRelevant(text)) {
            return null;
        }

        Instant relevantAt = extractInstant(properties, "deadline", "dueAt", "scheduledAt");
        if (relevantAt == null) {
            relevantAt = extractInstantFromText(text, context);
        }
        Integer startHour = extractHour(properties, "preferredHour", "preferredTime", "time");
        if (startHour == null) {
            startHour = extractHour(text);
        }
        Integer endHour = startHour != null ? Integer.valueOf((startHour + 2) % 24) : null;
        float effectivenessScore = extractFloat(properties, "effectivenessScore");
        int positiveOutcomes = extractInt(properties, "positiveOutcomes");
        int injectionCount = extractInt(properties, "injectionCount");

        return new ReminderSignal(
                entity.id(),
                classifySignalKind(text, relevantAt, startHour),
                clamp(Math.max(entity.extractionConfidence(), 0.55f)
                        + Math.min(0.15f, effectivenessScore * 0.20f + positiveOutcomes * 0.03f)),
                clamp(Math.max(entity.importanceScore(), 0.50f)
                        + Math.min(0.18f, effectivenessScore * 0.12f + injectionCount * 0.02f)),
                Math.max(2, Math.min(4, 1 + positiveOutcomes + (injectionCount > 0 ? 1 : 0))),
                entity.updatedAt(),
                relevantAt,
                inferPreparationLeadTime(text),
                startHour,
                endHour,
                0.0f,
                true,
                entity.isExpired(),
                truncate(text, 180)
        );
    }

    @Nullable
    private ReminderSignal toConversationSignal(ConversationRecord conversation, ReminderRuntimeContext context) {
        String text = buildConversationText(conversation);
        if (!looksReminderRelevant(text)) {
            return null;
        }
        Instant relevantAt = extractInstantFromText(text, context);
        Integer startHour = extractHour(text);
        Integer endHour = startHour != null ? Integer.valueOf((startHour + 2) % 24) : null;
        int userEvidence = countRelevantUserMessages(conversation.messages());
        int evidenceCount = Math.max(2, userEvidence + (conversation.summary() != null ? 1 : 0) + (relevantAt != null ? 1 : 0));
        float confidence = clamp(0.45f
                + (conversation.summary() != null && !conversation.summary().isBlank() ? 0.08f : 0.0f)
                + Math.min(0.16f, userEvidence * 0.04f)
                + (relevantAt != null ? 0.08f : 0.0f));
        float importance = clamp(0.50f
                + Math.min(0.12f, conversation.messageCount() * 0.02f)
                + (Duration.between(conversation.updatedAt(), context.now()).toHours() <= 24 ? 0.10f : 0.0f));

        return new ReminderSignal(
                "conversation:" + conversation.sessionId(),
                classifySignalKind(text, relevantAt, startHour),
                confidence,
                importance,
                evidenceCount,
                conversation.updatedAt(),
                relevantAt,
                inferPreparationLeadTime(text),
                startHour,
                endHour,
                0.0f,
                ACTION_CUE_PATTERN.matcher(text).find() || relevantAt != null,
                false,
                truncate(firstNonBlank(conversation.summary(),
                        latestRelevantUserMessage(conversation.messages()),
                        conversation.goal()), 180)
        );
    }

    @Nullable
    private ReminderSignal toWorkspaceSignal(WorkspaceItem item) {
        Map<String, Object> payload = parseJsonMap(item.payloadJson());
        String payloadType = stringValue(payload.get("type"));
        Instant relevantAt = item.expiresAt();
        if (relevantAt == null) {
            relevantAt = parseInstant(stringValue(payload.get("wakeupAt")));
        }
        String summary = joinNonBlank(
                item.summary(),
                stringValue(payload.get("description")),
                stringValue(payload.get("workflowName")),
                stringValue(payload.get("delegatedGoal")),
                stringValue(payload.get("reason"))
        );
        if (summary.isBlank() && relevantAt == null) {
            return null;
        }

        ReminderSignalKind kind = classifyWorkspaceSignal(item.kind(), payloadType, summary, relevantAt);
        float importance = clamp(0.55f + Math.min(0.30f, item.priority() / 200.0f) + (relevantAt != null ? 0.05f : 0.0f));

        return new ReminderSignal(
                "workspace:" + item.id(),
                kind,
                0.85f,
                importance,
                summary.isBlank() ? 2 : 3,
                item.updatedAt(),
                relevantAt,
                null,
                null,
                null,
                item.kind() == WorkspaceItemKind.TASK_STATE ? 0.20f : 0.0f,
                true,
                false,
                truncate(joinNonBlank(item.title(), summary), 180)
        );
    }

    private Map<String, List<NotificationRecord>> loadHistoryByTopic(String userId,
                                                                     ReminderRuntimeContext context) {
        Instant since = context.now().minusSeconds(30L * 24 * 3600);
        List<NotificationRecord> records = notificationRepository.findByUserIdAndTypeSince(
                userId, REMINDER_TYPE, since, 200);
        Map<String, List<NotificationRecord>> historyByTopic = new LinkedHashMap<>();
        for (NotificationRecord record : records) {
            extractTopicKey(record.metadataJson()).ifPresent(topicKey ->
                    historyByTopic.computeIfAbsent(topicKey, key -> new ArrayList<>()).add(record));
        }
        return historyByTopic;
    }

    private Map<String, String> loadAliasMap(String userId) {
        if (topicAliasRepository == null) {
            return Map.of();
        }
        try {
            return new LinkedHashMap<>(topicAliasRepository.findAliasMapByUserId(userId));
        } catch (Exception e) {
            log.debug("提醒采集: 读取主题别名失败, userId={}, error={}", userId, e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    private Map<String, List<NotificationRecord>> canonicalizeHistoryByTopic(Map<String, List<NotificationRecord>> raw,
                                                                              Map<String, String> aliasMap) {
        if (raw.isEmpty() || aliasMap.isEmpty()) {
            return raw;
        }
        Map<String, List<NotificationRecord>> result = new LinkedHashMap<>();
        raw.forEach((topicKey, records) -> result.computeIfAbsent(
                resolveCanonicalTopicKey(aliasMap, topicKey),
                ignored -> new ArrayList<>()).addAll(records));
        return result;
    }

    private Map<String, ReminderTopicFeedbackStats> loadFeedbackStatsByTopic(String userId,
                                                                             ReminderRuntimeContext context) {
        if (feedbackRepository == null) {
            return Map.of();
        }
        Instant since = context.now().minusSeconds(30L * 24 * 3600);
        try {
            return feedbackRepository.summarizeTopicStatsByUserIdSince(userId, since);
        } catch (Exception e) {
            log.debug("提醒采集: 读取反馈统计失败, userId={}, error={}", userId, e.getMessage());
            return Map.of();
        }
    }

    private Map<String, ReminderTopicFeedbackStats> canonicalizeFeedbackStatsByTopic(
            Map<String, ReminderTopicFeedbackStats> raw,
            Map<String, String> aliasMap) {
        if (raw.isEmpty() || aliasMap.isEmpty()) {
            return raw;
        }
        Map<String, ReminderTopicFeedbackStats> result = new LinkedHashMap<>();
        raw.forEach((topicKey, stats) -> result.merge(
                resolveCanonicalTopicKey(aliasMap, topicKey),
                stats,
                this::mergeFeedbackStats
        ));
        return result;
    }

    private Map<String, Integer> loadInferredOutcomeCountsByTopic(String userId,
                                                                  ReminderRuntimeContext context) {
        if (outcomeRepository == null) {
            return Map.of();
        }
        Instant since = context.now().minusSeconds(30L * 24 * 3600);
        try {
            return outcomeRepository.summarizeActedCountByTopicSince(userId, since);
        } catch (Exception e) {
            log.debug("提醒采集: 读取隐式结果统计失败, userId={}, error={}", userId, e.getMessage());
            return Map.of();
        }
    }

    private Map<String, Integer> canonicalizeOutcomeCountsByTopic(Map<String, Integer> raw,
                                                                  Map<String, String> aliasMap) {
        if (raw.isEmpty() || aliasMap.isEmpty()) {
            return raw;
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        raw.forEach((topicKey, count) -> result.merge(
                resolveCanonicalTopicKey(aliasMap, topicKey),
                Math.max(0, count),
                Integer::sum
        ));
        return result;
    }

    private ReminderTopicFeedbackStats mergeFeedbackStats(ReminderTopicFeedbackStats left,
                                                          ReminderTopicFeedbackStats right) {
        return new ReminderTopicFeedbackStats(
                left.actedCount30d() + right.actedCount30d(),
                left.snoozedCount30d() + right.snoozedCount30d(),
                left.dismissedCount30d() + right.dismissedCount30d(),
                left.notRelevantCount30d() + right.notRelevantCount30d(),
                left.muted() || right.muted()
        );
    }

    private ReminderTopicState resolveTopicState(@Nullable List<NotificationRecord> records,
                                                 @Nullable ReminderTopicFeedbackStats feedbackStats,
                                                 int inferredActedCount,
                                                 ReminderRuntimeContext context) {
        if ((records == null || records.isEmpty()) && feedbackStats == null && inferredActedCount <= 0) {
            return ReminderTopicState.empty();
        }
        List<NotificationRecord> safeRecords = records != null ? records : List.of();
        ReminderTopicFeedbackStats safeFeedbackStats = feedbackStats != null
                ? feedbackStats
                : ReminderTopicFeedbackStats.empty();
        Instant lastRemindedAt = safeRecords.stream()
                .map(NotificationRecord::sentAt)
                .max(Comparator.naturalOrder())
                .orElse(null);
        int remindersSentToday = 0;
        int readCount = 0;
        LocalDate today = context.now().atZone(context.zoneId()).toLocalDate();
        for (NotificationRecord record : safeRecords) {
            if ("READ".equalsIgnoreCase(record.readStatus())) {
                readCount++;
            }
            if (record.sentAt().atZone(context.zoneId()).toLocalDate().equals(today)) {
                remindersSentToday++;
            }
        }
        return new ReminderTopicState(
                lastRemindedAt,
                remindersSentToday,
                readCount,
                safeFeedbackStats.actedCount30d() + Math.max(0, inferredActedCount),
                safeFeedbackStats.snoozedCount30d(),
                safeFeedbackStats.dismissedCount30d(),
                safeFeedbackStats.notRelevantCount30d(),
                safeFeedbackStats.muted()
        );
    }

    private ReminderTopicState resolveTopicState(List<String> topicKeys,
                                                 Map<String, List<NotificationRecord>> historyByTopic,
                                                 Map<String, ReminderTopicFeedbackStats> feedbackStatsByTopic,
                                                 Map<String, Integer> inferredOutcomeCountsByTopic,
                                                 ReminderRuntimeContext context) {
        ReminderTopicState merged = ReminderTopicState.empty();
        Set<String> deduplicatedKeys = new LinkedHashSet<>(topicKeys);
        for (String topicKey : deduplicatedKeys) {
            merged = mergeTopicState(
                    merged,
                    resolveTopicState(
                            historyByTopic.get(topicKey),
                            feedbackStatsByTopic.get(topicKey),
                            inferredOutcomeCountsByTopic.getOrDefault(topicKey, 0),
                            context
                    )
            );
        }
        return merged;
    }

    private ReminderTopicState mergeTopicState(ReminderTopicState left, ReminderTopicState right) {
        if (left == null) {
            return right != null ? right : ReminderTopicState.empty();
        }
        if (right == null) {
            return left;
        }
        Instant lastRemindedAt = left.lastRemindedAt();
        if (right.lastRemindedAt() != null
                && (lastRemindedAt == null || right.lastRemindedAt().isAfter(lastRemindedAt))) {
            lastRemindedAt = right.lastRemindedAt();
        }
        return new ReminderTopicState(
                lastRemindedAt,
                left.remindersSentToday() + right.remindersSentToday(),
                left.readCount30d() + right.readCount30d(),
                left.actedCount30d() + right.actedCount30d(),
                left.snoozedCount30d() + right.snoozedCount30d(),
                left.dismissedCount30d() + right.dismissedCount30d(),
                left.notRelevantCount30d() + right.notRelevantCount30d(),
                left.muted() || right.muted()
        );
    }

    private ResolvedTopicKey resolveTopicKey(String userId,
                                             Map<String, String> aliasMap,
                                             String rawTopicKey,
                                             String title,
                                             ReminderSignal signal,
                                             boolean allowSemanticMerge,
                                             Instant now) {
        String canonicalRawTopicKey = resolveCanonicalTopicKey(aliasMap, rawTopicKey);
        if (!canonicalRawTopicKey.equals(rawTopicKey)) {
            return new ResolvedTopicKey(canonicalRawTopicKey, List.of(rawTopicKey, canonicalRawTopicKey));
        }
        if (!allowSemanticMerge) {
            return new ResolvedTopicKey(rawTopicKey, List.of(rawTopicKey));
        }
        String semanticTopicKey = buildSemanticTopicKey(title, signal);
        if (semanticTopicKey == null || semanticTopicKey.isBlank() || semanticTopicKey.equals(rawTopicKey)) {
            return new ResolvedTopicKey(rawTopicKey, List.of(rawTopicKey));
        }
        String canonicalTopicKey = resolveCanonicalTopicKey(aliasMap, semanticTopicKey);
        rememberTopicAlias(userId, aliasMap, rawTopicKey, canonicalTopicKey, signal, now);
        return new ResolvedTopicKey(canonicalTopicKey, List.of(rawTopicKey, semanticTopicKey, canonicalTopicKey));
    }

    private String resolveCanonicalTopicKey(Map<String, String> aliasMap, String topicKey) {
        String current = topicKey;
        Set<String> visited = new LinkedHashSet<>();
        while (current != null && !current.isBlank() && visited.add(current)) {
            String next = aliasMap.get(current);
            if (next == null || next.isBlank() || next.equals(current)) {
                return current;
            }
            current = next;
        }
        return topicKey;
    }

    private void rememberTopicAlias(String userId,
                                    Map<String, String> aliasMap,
                                    String aliasTopicKey,
                                    String canonicalTopicKey,
                                    ReminderSignal signal,
                                    Instant now) {
        if (topicAliasRepository == null
                || userId == null
                || userId.isBlank()
                || aliasTopicKey == null
                || aliasTopicKey.isBlank()
                || canonicalTopicKey == null
                || canonicalTopicKey.isBlank()
                || aliasTopicKey.equals(canonicalTopicKey)) {
            return;
        }
        try {
            topicAliasRepository.upsert(new ReminderTopicAliasRecord(
                    userId,
                    aliasTopicKey,
                    canonicalTopicKey,
                    topicFamily(signal),
                    now,
                    now
            ));
            aliasMap.put(aliasTopicKey, canonicalTopicKey);
        } catch (Exception e) {
            log.debug("提醒采集: 保存主题别名失败, alias={}, canonical={}, error={}",
                    aliasTopicKey, canonicalTopicKey, e.getMessage());
        }
    }

    private String topicFamily(ReminderSignal signal) {
        return switch (signal.kind()) {
            case DEADLINE, COMMITMENT -> "task";
            case HABIT -> "habit";
            case EVENT -> "event";
            case ANOMALY -> "anomaly";
        };
    }

    @Nullable
    private String buildSemanticTopicKey(String title, ReminderSignal signal) {
        String semanticText = normalizeTopicText(title);
        if (semanticText.isBlank() || semanticText.length() < 2) {
            semanticText = normalizeTopicText(firstNonBlank(signal.summary(), title));
        }
        if (semanticText.isBlank() || semanticText.length() < 2) {
            return null;
        }
        String family = switch (signal.kind()) {
            case DEADLINE, COMMITMENT -> "task";
            case HABIT -> "habit";
            case EVENT -> "event";
            case ANOMALY -> "anomaly";
        };
        String temporalMarker = signal.relevantAt() != null
                && signal.kind() == ReminderSignalKind.EVENT
                ? ":" + signal.relevantAt().toString().substring(0, 10)
                : "";
        return "topic:" + family + ":" + shortHash(family + "|" + semanticText + temporalMarker);
    }

    private String normalizeTopicText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = value
                .replaceAll("[\\p{Punct}\\p{IsPunctuation}]+", " ")
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase();
        return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }

    private String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                builder.append(String.format("%02x", bytes[i]));
            }
            return builder.toString();
        } catch (Exception e) {
            log.debug("提醒采集: 主题键哈希失败, error={}", e.getMessage());
            return Integer.toHexString(value.hashCode());
        }
    }

    private Optional<String> extractTopicKey(@Nullable String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Optional.empty();
        }
        try {
            Map<String, String> metadata = MAPPER.readValue(metadataJson, new TypeReference<>() {});
            String topicKey = metadata.get("topicKey");
            if (topicKey == null || topicKey.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(topicKey);
        } catch (Exception e) {
            log.debug("提醒采集: 解析通知元数据失败, error={}", e.getMessage());
            return Optional.empty();
        }
    }

    @Nullable
    private Instant extractInstant(Map<String, Object> properties, String... keys) {
        for (String key : keys) {
            Object raw = properties.get(key);
            if (raw == null) {
                continue;
            }
            Instant parsed = parseInstant(raw.toString());
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    @Nullable
    private Instant extractInstantFromText(String text, ReminderRuntimeContext context) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Instant parsed = parseExplicitDate(text, context.zoneId());
        if (parsed != null) {
            return parsed;
        }
        LocalDate relativeDate = resolveRelativeDate(text, context.now(), context.zoneId());
        if (relativeDate == null) {
            return null;
        }
        int hour = Optional.ofNullable(extractHour(text)).orElse(defaultHourForText(text));
        int minute = extractMinute(text);
        return ZonedDateTime.of(relativeDate, LocalTime.of(hour, minute), context.zoneId()).toInstant();
    }

    @Nullable
    private Duration extractDuration(Map<String, Object> properties, String... keys) {
        for (String key : keys) {
            Object raw = properties.get(key);
            if (raw == null) {
                continue;
            }
            try {
                return Duration.ofMinutes(Long.parseLong(raw.toString()));
            } catch (NumberFormatException ignored) {
                // ignore
            }
        }
        return null;
    }

    @Nullable
    private Duration inferPreparationLeadTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher hoursMatcher = LEAD_HOURS_PATTERN.matcher(text);
        if (hoursMatcher.find()) {
            return Duration.ofHours(Long.parseLong(hoursMatcher.group(1)));
        }
        Matcher minutesMatcher = LEAD_MINUTES_PATTERN.matcher(text);
        if (minutesMatcher.find()) {
            return Duration.ofMinutes(Long.parseLong(minutesMatcher.group(1)));
        }
        return null;
    }

    private float extractFloat(Map<String, Object> properties, String key) {
        Object raw = properties.get(key);
        if (raw == null) {
            return 0.0f;
        }
        try {
            return Float.parseFloat(raw.toString());
        } catch (NumberFormatException ignored) {
            return 0.0f;
        }
    }

    private int extractInt(Map<String, Object> properties, String key) {
        Object raw = properties.get(key);
        if (raw == null) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.toString());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    @Nullable
    private Integer extractHour(Map<String, Object> properties, String... keys) {
        for (String key : keys) {
            Object raw = properties.get(key);
            if (raw == null) {
                continue;
            }
            Integer parsed = extractHour(raw.toString());
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    @Nullable
    private Integer extractHour(String... values) {
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                return LocalTime.parse(normalizeTime(value), DateTimeFormatter.ofPattern("H:mm")).getHour();
            } catch (DateTimeParseException ignored) {
                // ignore
            }
            if (value.matches("^\\d{1,2}$")) {
                int hour = Integer.parseInt(value);
                if (hour >= 0 && hour <= 23) {
                    return hour;
                }
            }
            Matcher matcher = TIME_IN_TEXT_PATTERN.matcher(value);
            while (matcher.find()) {
                Integer parsed = adjustHour(matcher.group(1), matcher.group(2));
                if (parsed != null) {
                    return parsed;
                }
            }
        }
        return null;
    }

    private int extractMinute(String text) {
        Matcher matcher = TIME_IN_TEXT_PATTERN.matcher(text);
        if (!matcher.find()) {
            return 0;
        }
        String minute = matcher.group(3);
        if (minute == null || minute.isBlank()) {
            return 0;
        }
        return Integer.parseInt(minute);
    }

    @Nullable
    private Integer adjustHour(@Nullable String meridiem, @Nullable String hourText) {
        if (hourText == null || hourText.isBlank()) {
            return null;
        }
        int hour = Integer.parseInt(hourText);
        if (hour < 0 || hour > 23) {
            return null;
        }
        if (meridiem == null || meridiem.isBlank()) {
            return hour;
        }
        return switch (meridiem) {
            case "下午", "晚上" -> hour < 12 ? hour + 12 : hour;
            case "中午" -> hour == 12 ? 12 : Math.min(hour + 12, 23);
            case "凌晨" -> hour == 12 ? 0 : hour;
            default -> hour;
        };
    }

    @Nullable
    private Instant parseInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // ignore
        }
        try {
            return LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException ignored) {
            // ignore
        }
        try {
            return LocalDate.parse(value).atStartOfDay(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    @Nullable
    private Instant parseExplicitDate(String text, ZoneId zoneId) {
        Matcher isoMatcher = ISO_DATE_TIME_PATTERN.matcher(text);
        if (isoMatcher.find()) {
            int year = Integer.parseInt(isoMatcher.group(1));
            int month = Integer.parseInt(isoMatcher.group(2));
            int day = Integer.parseInt(isoMatcher.group(3));
            int hour = isoMatcher.group(4) != null ? Integer.parseInt(isoMatcher.group(4)) : defaultHourForText(text);
            int minute = isoMatcher.group(5) != null ? Integer.parseInt(isoMatcher.group(5)) : 0;
            return ZonedDateTime.of(LocalDate.of(year, month, day), LocalTime.of(hour, minute), zoneId).toInstant();
        }
        Matcher cnMatcher = CN_DATE_TIME_PATTERN.matcher(text);
        if (cnMatcher.find()) {
            LocalDate nowDate = LocalDate.now(zoneId);
            int month = Integer.parseInt(cnMatcher.group(1));
            int day = Integer.parseInt(cnMatcher.group(2));
            int year = nowDate.getYear();
            LocalDate date = LocalDate.of(year, month, day);
            if (date.isBefore(nowDate.minusDays(1))) {
                date = date.plusYears(1);
            }
            int hour = cnMatcher.group(4) != null
                    ? Optional.ofNullable(adjustHour(cnMatcher.group(3), cnMatcher.group(4))).orElse(defaultHourForText(text))
                    : defaultHourForText(text);
            int minute = cnMatcher.group(5) != null ? Integer.parseInt(cnMatcher.group(5)) : 0;
            return ZonedDateTime.of(date, LocalTime.of(hour, minute), zoneId).toInstant();
        }
        return null;
    }

    @Nullable
    private LocalDate resolveRelativeDate(String text, Instant now, ZoneId zoneId) {
        LocalDate today = now.atZone(zoneId).toLocalDate();
        if (text.contains("后天")) {
            return today.plusDays(2);
        }
        if (text.contains("明天")) {
            return today.plusDays(1);
        }
        if (text.contains("今晚") || text.contains("今天")) {
            return today;
        }
        if (text.contains("周末")) {
            return nextWeekday(today, 6);
        }
        for (Map.Entry<String, Integer> entry : weekdayMapping().entrySet()) {
            if (text.contains(entry.getKey())) {
                return nextWeekday(today, entry.getValue());
            }
        }
        return null;
    }

    private LocalDate nextWeekday(LocalDate today, int targetDayOfWeek) {
        int todayValue = today.getDayOfWeek().getValue();
        int delta = targetDayOfWeek - todayValue;
        if (delta <= 0) {
            delta += 7;
        }
        return today.plusDays(delta);
    }

    private Map<String, Integer> weekdayMapping() {
        return Map.of(
                "周一", 1,
                "周二", 2,
                "周三", 3,
                "周四", 4,
                "周五", 5,
                "周六", 6,
                "周日", 7,
                "周天", 7
        );
    }

    private int defaultHourForText(String text) {
        if (text.contains("今晚")) {
            return 20;
        }
        if (text.contains("中午")) {
            return 12;
        }
        if (text.contains("下午")) {
            return 15;
        }
        if (text.contains("晚上")) {
            return 20;
        }
        if (text.contains("凌晨")) {
            return 1;
        }
        return 9;
    }

    private ReminderSignalKind classifySignalKind(String text,
                                                  @Nullable Instant relevantAt,
                                                  @Nullable Integer startHour) {
        if (startHour != null && HABIT_CUE_PATTERN.matcher(text).find()) {
            return ReminderSignalKind.HABIT;
        }
        if (relevantAt != null && EVENT_CUE_PATTERN.matcher(text).find()) {
            return ReminderSignalKind.EVENT;
        }
        if (relevantAt != null) {
            return ReminderSignalKind.DEADLINE;
        }
        if (startHour != null) {
            return ReminderSignalKind.HABIT;
        }
        return ReminderSignalKind.COMMITMENT;
    }

    private ReminderSignalKind classifyWorkspaceSignal(WorkspaceItemKind kind,
                                                       @Nullable String payloadType,
                                                       String summary,
                                                       @Nullable Instant relevantAt) {
        if ("SCHEDULED_WAKEUP".equals(payloadType) && relevantAt != null) {
            return ReminderSignalKind.EVENT;
        }
        if (kind == WorkspaceItemKind.PENDING_DECISION) {
            return ReminderSignalKind.COMMITMENT;
        }
        if (relevantAt != null) {
            return ReminderSignalKind.DEADLINE;
        }
        if (EVENT_CUE_PATTERN.matcher(summary).find()) {
            return ReminderSignalKind.EVENT;
        }
        return ReminderSignalKind.COMMITMENT;
    }

    private boolean looksReminderRelevant(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        boolean hasActionCue = ACTION_CUE_PATTERN.matcher(text).find();
        boolean hasTimeCue = TIME_CUE_PATTERN.matcher(text).find();
        boolean hasHabitCue = HABIT_CUE_PATTERN.matcher(text).find();
        return text.contains("提醒")
                || text.contains("别忘")
                || text.contains("待办")
                || (hasActionCue && (hasTimeCue || hasHabitCue))
                || (hasTimeCue && hasHabitCue);
    }

    private String buildConversationText(ConversationRecord conversation) {
        List<String> recentUserMessages = conversation.messages().stream()
                .filter(message -> "user".equalsIgnoreCase(message.role()))
                .sorted(Comparator.comparing(MessageRecord::createdAt).reversed())
                .limit(4)
                .map(MessageRecord::effectiveContent)
                .toList();
        return joinNonBlank(
                conversation.goal(),
                conversation.summary(),
                String.join("\n", recentUserMessages)
        );
    }

    private String buildConversationTitle(ConversationRecord conversation) {
        return truncate(firstNonBlank(conversation.summary(), conversation.goal(), conversation.sessionId()), 48);
    }

    private int countRelevantUserMessages(List<MessageRecord> messages) {
        return (int) messages.stream()
                .filter(message -> "user".equalsIgnoreCase(message.role()))
                .map(MessageRecord::effectiveContent)
                .filter(this::looksReminderRelevant)
                .count();
    }

    @Nullable
    private String latestRelevantUserMessage(List<MessageRecord> messages) {
        return messages.stream()
                .filter(message -> "user".equalsIgnoreCase(message.role()))
                .sorted(Comparator.comparing(MessageRecord::createdAt).reversed())
                .map(MessageRecord::effectiveContent)
                .filter(this::looksReminderRelevant)
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> parseJsonMap(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.debug("提醒采集: 解析工作区 payload 失败, error={}", e.getMessage());
            return Map.of();
        }
    }

    private String joinNonBlank(String... values) {
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

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    private String stringify(@Nullable Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(item -> item != null ? item.toString() : "")
                    .filter(item -> !item.isBlank())
                    .reduce((left, right) -> left + "\n" + right)
                    .orElse("");
        }
        return value.toString();
    }

    private String stringValue(@Nullable Object value) {
        return value != null ? value.toString() : "";
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private String normalizeTime(String value) {
        String normalized = value.trim();
        if (normalized.matches("^\\d{1,2}:\\d{2}$")) {
            return normalized;
        }
        if (normalized.matches("^\\d{1,2}点$")) {
            return normalized.substring(0, normalized.length() - 1) + ":00";
        }
        return normalized;
    }

    private record ResolvedTopicKey(String topicKey, List<String> stateKeys) {
    }
}
