package com.lifepilot.skill.builtin.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.proactive.candidate.CandidateProvider;
import com.lifepilot.agent.proactive.model.*;
import com.lifepilot.agent.proactive.signal.SignalSource;
import com.lifepilot.datastore.DataStoreManager;
import com.lifepilot.datastore.model.Collection;
import com.lifepilot.datastore.model.CollectionType;
import com.lifepilot.datastore.model.Document;
import com.lifepilot.notification.Urgency;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * ScheduleSkillProvider 主动推理能力单元测试 — 验证 signalSources / candidateProviders 实现。
 *
 * @author zsg
 * @since 2026-03-10
 */
@ExtendWith(MockitoExtension.class)
class ScheduleSkillProviderProactiveTest {

    @Mock private DataStoreManager dataStoreManager;
    @Mock private PromptRegistry promptRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ScheduleSkillProvider provider;

    /** 固定的 Collection，供 ensureCollection() 返回。 */
    private static final Collection SCHEDULE_COLLECTION = new Collection(
            "col-schedule", "日程", "日程管理", CollectionType.DOCUMENT,
            null, null, null, Instant.now().toString(), Instant.now().toString());

    @BeforeEach
    void setUp() {
        lenient().when(promptRegistry.render("skill/schedule")).thenReturn("日程提示词");
        // DataStoreCrudAdapter.ensureCollection() 需要 findCollection 返回已有集合
        lenient().when(dataStoreManager.findCollection("日程")).thenReturn(Optional.of(SCHEDULE_COLLECTION));
        provider = new ScheduleSkillProvider(dataStoreManager, objectMapper, promptRegistry, null, null);
    }

    // ---- 辅助方法：将 ScheduleEntity 包装为 DataStore Document ----

    private Document toDocument(ScheduleEntity entity) {
        try {
            String json = objectMapper.writeValueAsString(entity);
            String id = UUID.randomUUID().toString();
            return new Document(id, "col-schedule", json, null,
                    Instant.now().toString(), Instant.now().toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ---- signalSources / candidateProviders 基础 ----

    @Test
    void signalSources_返回一个ScheduleSignalSource() {
        List<SignalSource> sources = provider.signalSources();
        assertThat(sources).hasSize(1);
        assertThat(sources.getFirst().id()).isEqualTo("schedule-signal");
    }

    @Test
    void candidateProviders_返回一个ScheduleCandidateProvider() {
        List<CandidateProvider> providers = provider.candidateProviders();
        assertThat(providers).hasSize(1);
        assertThat(providers.getFirst().id()).isEqualTo("schedule-candidate");
    }

    // ---- ScheduleSignalSource.collect() 测试 ----

    @Test
    void collect_无日程时_返回空列表() {
        when(dataStoreManager.queryDocuments(any())).thenReturn(List.of());

        List<Signal> signals = provider.signalSources().getFirst().collect();
        assertThat(signals).isEmpty();
    }

    @Test
    void collect_日程开始时间超过60分钟_跳过() {
        String farFuture = Instant.now().plus(Duration.ofMinutes(90)).toString();
        var entity = new ScheduleEntity("远期日程", farFuture,
                Instant.now().plus(Duration.ofMinutes(150)).toString(), null, null, null);
        when(dataStoreManager.queryDocuments(any())).thenReturn(List.of(toDocument(entity)));

        List<Signal> signals = provider.signalSources().getFirst().collect();
        assertThat(signals).isEmpty();
    }

    @Test
    void collect_日程开始时间已过_跳过() {
        String past = Instant.now().minus(Duration.ofMinutes(10)).toString();
        var entity = new ScheduleEntity("已过日程", past,
                Instant.now().plus(Duration.ofMinutes(50)).toString(), null, null, null);
        when(dataStoreManager.queryDocuments(any())).thenReturn(List.of(toDocument(entity)));

        List<Signal> signals = provider.signalSources().getFirst().collect();
        assertThat(signals).isEmpty();
    }

    @Test
    void collect_10分钟内开始_紧急度为HIGH() {
        String startTime = Instant.now().plus(Duration.ofMinutes(10)).toString();
        var entity = new ScheduleEntity("紧急会议", startTime,
                Instant.now().plus(Duration.ofMinutes(70)).toString(), null, "会议室A", null);
        when(dataStoreManager.queryDocuments(any())).thenReturn(List.of(toDocument(entity)));

        List<Signal> signals = provider.signalSources().getFirst().collect();
        assertThat(signals).hasSize(1);

        Signal signal = signals.getFirst();
        assertThat(signal.typeId()).isEqualTo("schedule_reminder");
        assertThat(signal.urgency()).isEqualTo(Urgency.HIGH);
        assertThat(signal.sourceId()).isEqualTo("schedule-signal");
        assertThat(signal.summary()).contains("紧急会议");
        assertThat(signal.metadata()).containsEntry("title", "紧急会议");
        assertThat(signal.metadata()).containsEntry("startTime", startTime);
    }

    @Test
    void collect_25分钟内开始_紧急度为MEDIUM() {
        String startTime = Instant.now().plus(Duration.ofMinutes(25)).toString();
        var entity = new ScheduleEntity("中等日程", startTime,
                Instant.now().plus(Duration.ofMinutes(85)).toString(), null, null, null);
        when(dataStoreManager.queryDocuments(any())).thenReturn(List.of(toDocument(entity)));

        List<Signal> signals = provider.signalSources().getFirst().collect();
        assertThat(signals).hasSize(1);
        assertThat(signals.getFirst().urgency()).isEqualTo(Urgency.MEDIUM);
    }

    @Test
    void collect_45分钟内开始_紧急度为LOW() {
        String startTime = Instant.now().plus(Duration.ofMinutes(45)).toString();
        var entity = new ScheduleEntity("低优先日程", startTime,
                Instant.now().plus(Duration.ofMinutes(105)).toString(), null, null, null);
        when(dataStoreManager.queryDocuments(any())).thenReturn(List.of(toDocument(entity)));

        List<Signal> signals = provider.signalSources().getFirst().collect();
        assertThat(signals).hasSize(1);
        assertThat(signals.getFirst().urgency()).isEqualTo(Urgency.LOW);
    }

    // ---- ScheduleCandidateProvider.evaluate() 测试 ----

    @Test
    void evaluate_匹配schedule_reminder信号_生成NOTIFICATION候选() {
        Signal signal = Signal.builder()
                .typeId("schedule_reminder")
                .urgency(Urgency.HIGH)
                .summary("日程「团队会议」将于明天开始")
                .sourceId("schedule-signal")
                .subjectId("sch-1")
                .build();
        SignalBundle bundle = SignalBundle.builder()
                .currentTime(LocalDateTime.now())
                .dayOfWeek(DayOfWeek.MONDAY)
                .timeSinceLastInteraction(Duration.ofMinutes(10))
                .recentConversationCount(0)
                .signals(List.of(signal))
                .build();

        List<ProactiveCandidate> candidates = provider.candidateProviders().getFirst().evaluate(bundle);
        assertThat(candidates).hasSize(1);

        ProactiveCandidate candidate = candidates.getFirst();
        assertThat(candidate.typeId()).isEqualTo("schedule_reminder");
        assertThat(candidate.urgency()).isEqualTo(Urgency.HIGH);
        assertThat(candidate.subjectId()).isEqualTo("sch-1");
        assertThat(candidate.initiativeType()).isEqualTo(InitiativeType.NOTIFICATION);
    }

    @Test
    void evaluate_非schedule_reminder信号_不生成候选() {
        Signal otherSignal = Signal.builder()
                .typeId("deadline_reminder")
                .urgency(Urgency.LOW)
                .summary("待办提醒")
                .sourceId("todo-signal")
                .subjectId("todo-1")
                .build();
        SignalBundle bundle = SignalBundle.builder()
                .currentTime(LocalDateTime.now())
                .dayOfWeek(DayOfWeek.MONDAY)
                .timeSinceLastInteraction(Duration.ofMinutes(10))
                .recentConversationCount(0)
                .signals(List.of(otherSignal))
                .build();

        List<ProactiveCandidate> candidates = provider.candidateProviders().getFirst().evaluate(bundle);
        assertThat(candidates).isEmpty();
    }

    @Test
    void evaluate_来源非schedule_signal的schedule_reminder_不生成候选() {
        Signal wrongSource = Signal.builder()
                .typeId("schedule_reminder")
                .urgency(Urgency.MEDIUM)
                .summary("其他来源的日程提醒")
                .sourceId("other-source")
                .subjectId("x-1")
                .build();
        SignalBundle bundle = SignalBundle.builder()
                .currentTime(LocalDateTime.now())
                .dayOfWeek(DayOfWeek.MONDAY)
                .timeSinceLastInteraction(Duration.ofMinutes(10))
                .recentConversationCount(0)
                .signals(List.of(wrongSource))
                .build();

        List<ProactiveCandidate> candidates = provider.candidateProviders().getFirst().evaluate(bundle);
        assertThat(candidates).isEmpty();
    }
}
