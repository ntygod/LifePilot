package com.lifepilot.agent.task.reminder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.memory.episodic.CompressionLevel;
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
import com.lifepilot.memory.workspace.WorkspaceStatus;
import com.lifepilot.notification.NotificationRecord;
import com.lifepilot.notification.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DefaultReminderSignalCollector 单元测试。
 *
 * <p>验证 L3/L4 到提醒主题快照的映射逻辑，以及通知历史状态回填。</p>
 *
 * @author zsg
 * @since 2026-03-28
 */
class DefaultReminderSignalCollector_单元测试 {

    private SemanticMemory semanticMemory;
    private ProceduralMemory proceduralMemory;
    private EpisodicMemory episodicMemory;
    private SessionWorkspaceService workspaceService;
    private NotificationRepository notificationRepository;
    private ReminderFeedbackRepository feedbackRepository;
    private ReminderOutcomeRepository outcomeRepository;
    private ReminderTopicAliasRepository topicAliasRepository;
    private DefaultReminderSignalCollector collector;

    @BeforeEach
    void setUp() {
        semanticMemory = mock(SemanticMemory.class);
        proceduralMemory = mock(ProceduralMemory.class);
        episodicMemory = mock(EpisodicMemory.class);
        workspaceService = mock(SessionWorkspaceService.class);
        notificationRepository = mock(NotificationRepository.class);
        feedbackRepository = mock(ReminderFeedbackRepository.class);
        outcomeRepository = mock(ReminderOutcomeRepository.class);
        topicAliasRepository = mock(ReminderTopicAliasRepository.class);
        collector = new DefaultReminderSignalCollector(
                semanticMemory, proceduralMemory, episodicMemory, workspaceService,
                notificationRepository, feedbackRepository, outcomeRepository, topicAliasRepository, null);

        when(semanticMemory.findCurrentByType(EntityType.EVENT)).thenReturn(List.of());
        when(semanticMemory.findCurrentByType(EntityType.HABIT)).thenReturn(List.of());
        when(semanticMemory.findCurrentByType(EntityType.GOAL)).thenReturn(List.of());
        when(semanticMemory.findCurrentByType(EntityType.PROJECT)).thenReturn(List.of());
        when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE)).thenReturn(List.of());
        when(proceduralMemory.listAllPreferences()).thenReturn(List.of());
        when(episodicMemory.getRecent(any())).thenReturn(List.of());
        when(feedbackRepository.summarizeTopicStatsByUserIdSince(eq("default"), any())).thenReturn(Map.of());
        when(outcomeRepository.summarizeActedCountByTopicSince(eq("default"), any())).thenReturn(Map.of());
        when(topicAliasRepository.findAliasMapByUserId("default")).thenReturn(Map.of());
    }

    @Test
    void collect_L3事件和L4时间偏好_生成主题快照() throws Exception {
        Instant now = Instant.parse("2026-03-28T02:00:00Z");
        TemporalEntity event = new TemporalEntity(
                "evt-1",
                EntityType.EVENT,
                "缴电费",
                "今晚前完成",
                Map.of("deadline", "2026-03-28T08:00:00Z"),
                1,
                true,
                now.minusSeconds(3600),
                null,
                "conv-1",
                0.85f,
                0.92f,
                2,
                now.minusSeconds(600),
                now.minusSeconds(7200),
                now.minusSeconds(600)
        );
        PreferenceRule preferenceRule = new PreferenceRule(
                "pref-1",
                "user-preference",
                "周计划整理",
                "21:00",
                0.80f,
                "memory",
                4,
                now.minusSeconds(7200),
                now.minusSeconds(300)
        );
        String metadataJson = new ObjectMapper().writeValueAsString(Map.of("topicKey", "entity:evt-1"));
        NotificationRecord history = new NotificationRecord(
                "n-1", "default", "proactive_reminder", "{}", "WEB", "READ", "SENT", metadataJson,
                now.minusSeconds(1200), now.minusSeconds(1200), now.minusSeconds(1200)
        );
        ReminderTopicFeedbackStats feedbackStats = new ReminderTopicFeedbackStats(2, 1, 0, 0, false);

        when(semanticMemory.findCurrentByType(EntityType.EVENT)).thenReturn(List.of(event));
        when(proceduralMemory.listAllPreferences()).thenReturn(List.of(preferenceRule));
        when(notificationRepository.findByUserIdAndTypeSince(eq("default"), eq("proactive_reminder"), any(), eq(200)))
                .thenReturn(List.of(history));
        when(feedbackRepository.summarizeTopicStatsByUserIdSince(eq("default"), any()))
                .thenReturn(Map.of("entity:evt-1", feedbackStats));

        ReminderRuntimeContext context = new ReminderRuntimeContext(
                now, ZoneId.of("Asia/Shanghai"), LocalTime.of(23, 0), LocalTime.of(8, 0), 0);

        List<ReminderTopicSnapshot> topics = collector.collect("default", context);

        assertThat(topics).hasSize(2);
        ReminderTopicSnapshot eventTopic = topics.stream()
                .filter(topic -> topic.topicKey().equals("entity:evt-1"))
                .findFirst()
                .orElseThrow();
        ReminderTopicSnapshot preferenceTopic = topics.stream()
                .filter(topic -> topic.topicKey().equals("preference:pref-1"))
                .findFirst()
                .orElseThrow();

        assertThat(eventTopic.signals().getFirst().kind()).isEqualTo(ReminderSignalKind.EVENT);
        assertThat(eventTopic.signals().getFirst().relevantAt()).isEqualTo(Instant.parse("2026-03-28T08:00:00Z"));
        assertThat(eventTopic.state().readCount30d()).isEqualTo(1);
        assertThat(eventTopic.state().actedCount30d()).isEqualTo(2);
        assertThat(eventTopic.state().snoozedCount30d()).isEqualTo(1);
        assertThat(preferenceTopic.signals().getFirst().kind()).isEqualTo(ReminderSignalKind.HABIT);
        assertThat(preferenceTopic.signals().getFirst().preferredWindowStartHour()).isEqualTo(21);
    }

    @Test
    void collect_近期对话和工作区_生成提醒主题() {
        Instant now = Instant.parse("2026-03-28T02:00:00Z");
        ConversationRecord conversation = new ConversationRecord(
                "conv-1",
                "web:conv-1",
                "周日晚提醒我整理下周计划",
                "用户提到周日晚 21:00 复盘并整理下周计划",
                List.of(
                        new MessageRecord("m-1", "web:conv-1", "user", "这周日晚上9点提醒我整理下周计划",
                                null, CompressionLevel.ORIGINAL, false, null, 18, now.minusSeconds(7200)),
                        new MessageRecord("m-2", "web:conv-1", "assistant", "好的，先记住这个节奏",
                                null, CompressionLevel.ORIGINAL, false, null, 12, now.minusSeconds(7100))
                ),
                now.minusSeconds(7200),
                now.minusSeconds(3600)
        );
        WorkspaceItem pendingDecision = new WorkspaceItem(
                "ws-1",
                "web:conv-1",
                WorkspaceItemKind.PENDING_DECISION,
                "等待确认",
                "需要确认是否提交报销",
                "{\"type\":\"USER_CONFIRMATION\"}",
                WorkspaceStatus.ACTIVE,
                90,
                "trace-1",
                "trace-1",
                null,
                now.minusSeconds(1800),
                now.minusSeconds(1200)
        );

        when(episodicMemory.getRecent(any())).thenReturn(List.of(conversation));
        when(workspaceService.listActive("web:conv-1")).thenReturn(List.of(pendingDecision));
        when(notificationRepository.findByUserIdAndTypeSince(eq("default"), eq("proactive_reminder"), any(), eq(200)))
                .thenReturn(List.of());

        ReminderRuntimeContext context = new ReminderRuntimeContext(
                now, ZoneId.of("Asia/Shanghai"), LocalTime.of(23, 0), LocalTime.of(8, 0), 0);

        List<ReminderTopicSnapshot> topics = collector.collect("default", context);

        ReminderTopicSnapshot conversationTopic = topics.stream()
                .filter(topic -> topic.signals().stream().anyMatch(signal -> signal.signalId().equals("conversation:web:conv-1")))
                .findFirst()
                .orElseThrow();
        ReminderTopicSnapshot workspaceTopic = topics.stream()
                .filter(topic -> topic.topicKey().equals("workspace:trace-1"))
                .findFirst()
                .orElseThrow();

        assertThat(conversationTopic.topicKey()).startsWith("topic:habit:");
        assertThat(conversationTopic.signals().getFirst().kind()).isEqualTo(ReminderSignalKind.HABIT);
        assertThat(conversationTopic.signals().getFirst().preferredWindowStartHour()).isEqualTo(21);
        assertThat(workspaceTopic.signals().getFirst().kind()).isEqualTo(ReminderSignalKind.COMMITMENT);
        assertThat(workspaceTopic.signals().getFirst().actionable()).isTrue();
    }

    @Test
    void collect_经验反思包含稳定时间线索_生成经验主题() {
        Instant now = Instant.parse("2026-03-28T02:00:00Z");
        TemporalEntity experience = new TemporalEntity(
                "exp-1",
                EntityType.EXPERIENCE,
                "周日晚复盘",
                "每周日晚上9点整理本周复盘并准备下周计划",
                Map.of(
                        "applicableConditions", List.of("每周日晚上9点", "周计划整理"),
                        "effectivenessScore", 0.8f,
                        "positiveOutcomes", 2,
                        "injectionCount", 3
                ),
                1,
                true,
                now.minusSeconds(7200),
                null,
                "conv-2",
                0.82f,
                0.76f,
                2,
                now.minusSeconds(1200),
                now.minusSeconds(7200),
                now.minusSeconds(1200)
        );

        when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE)).thenReturn(List.of(experience));
        when(notificationRepository.findByUserIdAndTypeSince(eq("default"), eq("proactive_reminder"), any(), eq(200)))
                .thenReturn(List.of());

        ReminderRuntimeContext context = new ReminderRuntimeContext(
                now, ZoneId.of("Asia/Shanghai"), LocalTime.of(23, 0), LocalTime.of(8, 0), 0);

        List<ReminderTopicSnapshot> topics = collector.collect("default", context);

        ReminderTopicSnapshot experienceTopic = topics.stream()
                .filter(topic -> topic.signals().stream().anyMatch(signal -> signal.signalId().equals("exp-1")))
                .findFirst()
                .orElseThrow();

        assertThat(experienceTopic.topicKey()).startsWith("topic:habit:");
        assertThat(experienceTopic.signals().getFirst().kind()).isEqualTo(ReminderSignalKind.HABIT);
        assertThat(experienceTopic.signals().getFirst().preferredWindowStartHour()).isEqualTo(21);
    }

    @Test
    void collect_弱来源标题一致_会归并到同一主题() {
        Instant now = Instant.parse("2026-03-28T02:00:00Z");
        ConversationRecord conversation = new ConversationRecord(
                "conv-merge",
                "web:conv-merge",
                "报销处理",
                "报销处理",
                List.of(
                        new MessageRecord("m-1", "web:conv-merge", "user", "明天提醒我处理报销",
                                null, CompressionLevel.ORIGINAL, false, null, 10, now.minusSeconds(5400))
                ),
                now.minusSeconds(7200),
                now.minusSeconds(3600)
        );
        TemporalEntity experience = new TemporalEntity(
                "exp-merge",
                EntityType.EXPERIENCE,
                "报销处理",
                "明天上午处理报销时最好及时跟进",
                Map.of("effectivenessScore", 0.7f, "positiveOutcomes", 2),
                1,
                true,
                now.minusSeconds(7200),
                null,
                "conv-merge",
                0.80f,
                0.72f,
                2,
                now.minusSeconds(900),
                now.minusSeconds(7200),
                now.minusSeconds(900)
        );

        when(episodicMemory.getRecent(any())).thenReturn(List.of(conversation));
        when(semanticMemory.findCurrentByType(EntityType.EXPERIENCE)).thenReturn(List.of(experience));
        when(notificationRepository.findByUserIdAndTypeSince(eq("default"), eq("proactive_reminder"), any(), eq(200)))
                .thenReturn(List.of());

        ReminderRuntimeContext context = new ReminderRuntimeContext(
                now, ZoneId.of("Asia/Shanghai"), LocalTime.of(23, 0), LocalTime.of(8, 0), 0);

        List<ReminderTopicSnapshot> topics = collector.collect("default", context);

        ReminderTopicSnapshot mergedTopic = topics.stream()
                .filter(topic -> topic.signals().stream().map(ReminderSignal::signalId).toList()
                        .containsAll(List.of("conversation:web:conv-merge", "exp-merge")))
                .findFirst()
                .orElseThrow();

        assertThat(mergedTopic.topicKey()).startsWith("topic:task:");
        assertThat(mergedTopic.signals()).hasSize(2);
    }

    @Test
    void collect_仅有反馈静默偏好_仍回填主题状态() {
        Instant now = Instant.parse("2026-03-28T02:00:00Z");
        PreferenceRule preferenceRule = new PreferenceRule(
                "pref-1",
                "user-preference",
                "吃药提醒",
                "08:00",
                0.82f,
                "memory",
                5,
                now.minusSeconds(7200),
                now.minusSeconds(600)
        );

        when(proceduralMemory.listAllPreferences()).thenReturn(List.of(preferenceRule));
        when(notificationRepository.findByUserIdAndTypeSince(eq("default"), eq("proactive_reminder"), any(), eq(200)))
                .thenReturn(List.of());
        when(feedbackRepository.summarizeTopicStatsByUserIdSince(eq("default"), any()))
                .thenReturn(Map.of(
                        "preference:pref-1",
                        new ReminderTopicFeedbackStats(1, 0, 2, 1, true)
                ));

        ReminderRuntimeContext context = new ReminderRuntimeContext(
                now, ZoneId.of("Asia/Shanghai"), LocalTime.of(23, 0), LocalTime.of(8, 0), 0);

        List<ReminderTopicSnapshot> topics = collector.collect("default", context);

        ReminderTopicSnapshot preferenceTopic = topics.stream()
                .filter(topic -> topic.topicKey().equals("preference:pref-1"))
                .findFirst()
                .orElseThrow();

        assertThat(preferenceTopic.state().actedCount30d()).isEqualTo(1);
        assertThat(preferenceTopic.state().dismissedCount30d()).isEqualTo(2);
        assertThat(preferenceTopic.state().notRelevantCount30d()).isEqualTo(1);
        assertThat(preferenceTopic.state().muted()).isTrue();
    }

    @Test
    void collect_隐式完成统计_会并入处理次数() {
        Instant now = Instant.parse("2026-03-28T02:00:00Z");
        PreferenceRule preferenceRule = new PreferenceRule(
                "pref-2",
                "user-preference",
                "报销处理",
                "20:00",
                0.76f,
                "memory",
                3,
                now.minusSeconds(7200),
                now.minusSeconds(600)
        );

        when(proceduralMemory.listAllPreferences()).thenReturn(List.of(preferenceRule));
        when(notificationRepository.findByUserIdAndTypeSince(eq("default"), eq("proactive_reminder"), any(), eq(200)))
                .thenReturn(List.of());
        when(feedbackRepository.summarizeTopicStatsByUserIdSince(eq("default"), any()))
                .thenReturn(Map.of(
                        "preference:pref-2",
                        new ReminderTopicFeedbackStats(1, 0, 0, 0, false)
                ));
        when(outcomeRepository.summarizeActedCountByTopicSince(eq("default"), any()))
                .thenReturn(Map.of("preference:pref-2", 2));

        ReminderRuntimeContext context = new ReminderRuntimeContext(
                now, ZoneId.of("Asia/Shanghai"), LocalTime.of(23, 0), LocalTime.of(8, 0), 0);

        List<ReminderTopicSnapshot> topics = collector.collect("default", context);

        ReminderTopicSnapshot preferenceTopic = topics.stream()
                .filter(topic -> topic.topicKey().equals("preference:pref-2"))
                .findFirst()
                .orElseThrow();

        assertThat(preferenceTopic.state().actedCount30d()).isEqualTo(3);
    }

    @Test
    void collect_已存在主题别名_会归并到持久化canonicalTopic() {
        Instant now = Instant.parse("2026-03-28T02:00:00Z");
        ConversationRecord conversation = new ConversationRecord(
                "conv-alias",
                "web:conv-alias",
                "报销处理",
                "报销处理",
                List.of(
                        new MessageRecord("m-1", "web:conv-alias", "user", "明天提醒我处理报销",
                                null, CompressionLevel.ORIGINAL, false, null, 10, now.minusSeconds(5400))
                ),
                now.minusSeconds(7200),
                now.minusSeconds(3600)
        );

        when(episodicMemory.getRecent(any())).thenReturn(List.of(conversation));
        when(notificationRepository.findByUserIdAndTypeSince(eq("default"), eq("proactive_reminder"), any(), eq(200)))
                .thenReturn(List.of());
        when(topicAliasRepository.findAliasMapByUserId("default"))
                .thenReturn(Map.of("conversation:web:conv-alias", "topic:task:alias-fixed"));

        ReminderRuntimeContext context = new ReminderRuntimeContext(
                now, ZoneId.of("Asia/Shanghai"), LocalTime.of(23, 0), LocalTime.of(8, 0), 0);

        List<ReminderTopicSnapshot> topics = collector.collect("default", context);

        ReminderTopicSnapshot topic = topics.stream()
                .filter(snapshot -> snapshot.signals().stream()
                        .anyMatch(signal -> signal.signalId().equals("conversation:web:conv-alias")))
                .findFirst()
                .orElseThrow();

        assertThat(topic.topicKey()).isEqualTo("topic:task:alias-fixed");
    }

    @Test
    void collect_弱来源首次归并_会持久化主题别名() {
        Instant now = Instant.parse("2026-03-28T02:00:00Z");
        ConversationRecord conversation = new ConversationRecord(
                "conv-save",
                "web:conv-save",
                "周计划整理",
                "周计划整理",
                List.of(
                        new MessageRecord("m-1", "web:conv-save", "user", "周日晚上提醒我整理下周计划",
                                null, CompressionLevel.ORIGINAL, false, null, 10, now.minusSeconds(5400))
                ),
                now.minusSeconds(7200),
                now.minusSeconds(3600)
        );

        when(episodicMemory.getRecent(any())).thenReturn(List.of(conversation));
        when(notificationRepository.findByUserIdAndTypeSince(eq("default"), eq("proactive_reminder"), any(), eq(200)))
                .thenReturn(List.of());

        ReminderRuntimeContext context = new ReminderRuntimeContext(
                now, ZoneId.of("Asia/Shanghai"), LocalTime.of(23, 0), LocalTime.of(8, 0), 0);

        List<ReminderTopicSnapshot> topics = collector.collect("default", context);

        ReminderTopicSnapshot topic = topics.stream()
                .filter(snapshot -> snapshot.signals().stream()
                        .anyMatch(signal -> signal.signalId().equals("conversation:web:conv-save")))
                .findFirst()
                .orElseThrow();

        assertThat(topic.topicKey()).startsWith("topic:task:");
        verify(topicAliasRepository).upsert(argThat(record ->
                "default".equals(record.userId())
                        && "conversation:web:conv-save".equals(record.aliasTopicKey())
                        && topic.topicKey().equals(record.canonicalTopicKey())));
    }
}
