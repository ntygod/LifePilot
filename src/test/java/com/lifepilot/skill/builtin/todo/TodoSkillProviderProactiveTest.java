package com.lifepilot.skill.builtin.todo;

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
 * TodoSkillProvider 主动推理能力单元测试 — 验证 signalSources / candidateProviders 实现。
 *
 * @author zsg
 * @since 2026-03-10
 */
@ExtendWith(MockitoExtension.class)
class TodoSkillProviderProactiveTest {

    @Mock private TodoRepository todoRepository;
    @Mock private PromptRegistry promptRegistry;

    private TodoSkillProvider provider;

    @BeforeEach
    void setUp() {
        lenient().when(promptRegistry.render("skill/todo")).thenReturn("待办提示词");
        provider = new TodoSkillProvider(todoRepository, promptRegistry);
    }

    // ---- signalSources / candidateProviders 基础 ----

    @Test
    void signalSources_返回一个TodoSignalSource() {
        List<SignalSource> sources = provider.signalSources();
        assertThat(sources).hasSize(1);
        assertThat(sources.getFirst().id()).isEqualTo("todo-signal");
    }

    @Test
    void candidateProviders_返回一个TodoCandidateProvider() {
        List<CandidateProvider> providers = provider.candidateProviders();
        assertThat(providers).hasSize(1);
        assertThat(providers.getFirst().id()).isEqualTo("todo-candidate");
    }

    // ---- TodoSignalSource.collect() 测试 ----

    @Test
    void collect_无PENDING待办时_返回空列表() {
        when(todoRepository.list("PENDING", null)).thenReturn(List.of());

        List<Signal> signals = provider.signalSources().getFirst().collect();
        assertThat(signals).isEmpty();
    }

    @Test
    void collect_待办无截止日期时_跳过() {
        TodoItem noDue = new TodoItem("id-1", "无截止日期", null,
                TodoItem.Priority.MEDIUM, TodoItem.Status.PENDING,
                null, null, Instant.now().toString(), Instant.now().toString());
        when(todoRepository.list("PENDING", null)).thenReturn(List.of(noDue));

        List<Signal> signals = provider.signalSources().getFirst().collect();
        assertThat(signals).isEmpty();
    }

    @Test
    void collect_截止日期超过24小时_跳过() {
        String farFuture = Instant.now().plus(Duration.ofHours(48)).toString();
        TodoItem farTodo = new TodoItem("id-2", "远期待办", null,
                TodoItem.Priority.HIGH, TodoItem.Status.PENDING,
                farFuture, null, Instant.now().toString(), Instant.now().toString());
        when(todoRepository.list("PENDING", null)).thenReturn(List.of(farTodo));

        List<Signal> signals = provider.signalSources().getFirst().collect();
        assertThat(signals).isEmpty();
    }

    @Test
    void collect_截止日期已过期_跳过() {
        String past = Instant.now().minus(Duration.ofHours(1)).toString();
        TodoItem pastTodo = new TodoItem("id-3", "已过期", null,
                TodoItem.Priority.HIGH, TodoItem.Status.PENDING,
                past, null, Instant.now().toString(), Instant.now().toString());
        when(todoRepository.list("PENDING", null)).thenReturn(List.of(pastTodo));

        List<Signal> signals = provider.signalSources().getFirst().collect();
        assertThat(signals).isEmpty();
    }

    @Test
    void collect_1小时内到期_紧急度为HIGH() {
        String dueDate = Instant.now().plus(Duration.ofMinutes(30)).toString();
        TodoItem urgentTodo = new TodoItem("id-4", "紧急待办", null,
                TodoItem.Priority.HIGH, TodoItem.Status.PENDING,
                dueDate, null, Instant.now().toString(), Instant.now().toString());
        when(todoRepository.list("PENDING", null)).thenReturn(List.of(urgentTodo));

        List<Signal> signals = provider.signalSources().getFirst().collect();
        assertThat(signals).hasSize(1);

        Signal signal = signals.getFirst();
        assertThat(signal.typeId()).isEqualTo("deadline_reminder");
        assertThat(signal.urgency()).isEqualTo(Urgency.HIGH);
        assertThat(signal.sourceId()).isEqualTo("todo-signal");
        assertThat(signal.subjectId()).isEqualTo("id-4");
        assertThat(signal.summary()).contains("紧急待办");
        assertThat(signal.metadata()).containsEntry("todoId", "id-4");
        assertThat(signal.metadata()).containsEntry("title", "紧急待办");
    }

    @Test
    void collect_4小时内到期_紧急度为MEDIUM() {
        String dueDate = Instant.now().plus(Duration.ofHours(4)).toString();
        TodoItem mediumTodo = new TodoItem("id-5", "中等待办", null,
                TodoItem.Priority.MEDIUM, TodoItem.Status.PENDING,
                dueDate, null, Instant.now().toString(), Instant.now().toString());
        when(todoRepository.list("PENDING", null)).thenReturn(List.of(mediumTodo));

        List<Signal> signals = provider.signalSources().getFirst().collect();
        assertThat(signals).hasSize(1);
        assertThat(signals.getFirst().urgency()).isEqualTo(Urgency.MEDIUM);
    }

    @Test
    void collect_12小时内到期_紧急度为LOW() {
        String dueDate = Instant.now().plus(Duration.ofHours(12)).toString();
        TodoItem lowTodo = new TodoItem("id-6", "低优先待办", null,
                TodoItem.Priority.LOW, TodoItem.Status.PENDING,
                dueDate, null, Instant.now().toString(), Instant.now().toString());
        when(todoRepository.list("PENDING", null)).thenReturn(List.of(lowTodo));

        List<Signal> signals = provider.signalSources().getFirst().collect();
        assertThat(signals).hasSize(1);
        assertThat(signals.getFirst().urgency()).isEqualTo(Urgency.LOW);
    }

    // ---- TodoCandidateProvider.evaluate() 测试 ----

    @Test
    void evaluate_匹配deadline_reminder信号_生成NOTIFICATION候选() {
        Signal signal = Signal.builder()
                .typeId("deadline_reminder")
                .urgency(Urgency.HIGH)
                .summary("待办「测试」将于明天到期")
                .sourceId("todo-signal")
                .subjectId("todo-1")
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
        assertThat(candidate.typeId()).isEqualTo("deadline_reminder");
        assertThat(candidate.urgency()).isEqualTo(Urgency.HIGH);
        assertThat(candidate.subjectId()).isEqualTo("todo-1");
        assertThat(candidate.initiativeType()).isEqualTo(InitiativeType.NOTIFICATION);
    }

    @Test
    void evaluate_非deadline_reminder信号_不生成候选() {
        Signal otherSignal = Signal.builder()
                .typeId("habit_reminder")
                .urgency(Urgency.LOW)
                .summary("习惯提醒")
                .sourceId("habit-signal")
                .subjectId("habit-1")
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
    void evaluate_来源非todo_signal的deadline_reminder_不生成候选() {
        Signal wrongSource = Signal.builder()
                .typeId("deadline_reminder")
                .urgency(Urgency.MEDIUM)
                .summary("其他来源的截止提醒")
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
