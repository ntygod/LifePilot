package com.lifepilot.skill.builtin.schedule;

import com.lifepilot.agent.proactive.candidate.CandidateProvider;
import com.lifepilot.agent.proactive.model.*;
import com.lifepilot.agent.proactive.signal.SignalSource;
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

import static org.assertj.core.api.Assertions.assertThat;
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

    @Mock private ScheduleRepository scheduleRepository;
    @Mock private PromptRegistry promptRegistry;

    private ScheduleSkillProvider provider;

    @BeforeEach
    void setUp() {
        lenient().when(promptRegistry.render("skill/schedule")).thenReturn("日程提示词");
        provider = new ScheduleSkillProvider(scheduleRepository, promptRegistry);
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
        when(scheduleRepository.list()).thenReturn(List.of());

        List<Signal> signals = provider.signalSources().getFirst().collect();
        assertThat(signals).isEmpty();
    }

    @Test
    void collect_日程开始时间超过60分钟_跳过() {
        String farFuture = Instant.now().plus(Duration.ofMinutes(90)).toString();
        ScheduleItem farSchedule = new ScheduleItem("id-1", "远期日程", farFuture,
                Instant.now().plus(Duration.ofMinutes(150)).toString(),
                null, null, Instant.now().toString(), Instant.now().toString());
        when(scheduleRepository.list()).thenReturn(List.of(farSchedule));

        List<Signal> signals = provider.signalSources().getFirst().collect();
        assertThat(signals).isEmpty();
    }

    @Test
    void collect_日程开始时间已过_跳过() {
        String past = Instant.now().minus(Duration.ofMinutes(10)).toString();
        ScheduleItem pastSchedule = new ScheduleItem("id-2", "已过日程", past,
                Instant.now().plus(Duration.ofMinutes(50)).toString(),
                null, null, Instant.now().toString(), Instant.now().toString());
        when(scheduleRepository.list()).thenReturn(List.of(pastSchedule));

        List<Signal> signals = provider.signalSources().getFirst().collect();
        assertThat(signals).isEmpty();
    }

    @Test
    void collect_10分钟内开始_紧急度为HIGH() {
        String startTime = Instant.now().plus(Duration.ofMinutes(10)).toString();
        ScheduleItem urgentSchedule = new ScheduleItem("id-3", "紧急会议", startTime,
                Instant.now().plus(Duration.ofMinutes(70)).toString(),
                "会议室A", null, Instant.now().toString(), Instant.now().toString());
        when(scheduleRepository.list()).thenReturn(List.of(urgentSchedule));

        List<Signal> signals = provider.signalSources().getFirst().collect();
        assertThat(signals).hasSize(1);

        Signal signal = signals.getFirst();
        assertThat(signal.typeId()).isEqualTo("schedule_reminder");
        assertThat(signal.urgency()).isEqualTo(Urgency.HIGH);
        assertThat(signal.sourceId()).isEqualTo("schedule-signal");
        assertThat(signal.subjectId()).isEqualTo("id-3");
        assertThat(signal.summary()).contains("紧急会议");
        assertThat(signal.metadata()).containsEntry("scheduleId", "id-3");
        assertThat(signal.metadata()).containsEntry("title", "紧急会议");
        assertThat(signal.metadata()).containsEntry("startTime", startTime);
    }

    @Test
    void collect_25分钟内开始_紧急度为MEDIUM() {
        String startTime = Instant.now().plus(Duration.ofMinutes(25)).toString();
        ScheduleItem mediumSchedule = new ScheduleItem("id-4", "中等日程", startTime,
                Instant.now().plus(Duration.ofMinutes(85)).toString(),
                null, null, Instant.now().toString(), Instant.now().toString());
        when(scheduleRepository.list()).thenReturn(List.of(mediumSchedule));

        List<Signal> signals = provider.signalSources().getFirst().collect();
        assertThat(signals).hasSize(1);
        assertThat(signals.getFirst().urgency()).isEqualTo(Urgency.MEDIUM);
    }

    @Test
    void collect_45分钟内开始_紧急度为LOW() {
        String startTime = Instant.now().plus(Duration.ofMinutes(45)).toString();
        ScheduleItem lowSchedule = new ScheduleItem("id-5", "低优先日程", startTime,
                Instant.now().plus(Duration.ofMinutes(105)).toString(),
                null, null, Instant.now().toString(), Instant.now().toString());
        when(scheduleRepository.list()).thenReturn(List.of(lowSchedule));

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
