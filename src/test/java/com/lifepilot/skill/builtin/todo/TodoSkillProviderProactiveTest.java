package com.lifepilot.skill.builtin.todo;

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
 * TodoSkillProvider 主动推理能力单元测试 — 验证 signalSources / candidateProviders 实现。
 *
 * @author zsg
 * @since 2026-03-10
 */
@ExtendWith(MockitoExtension.class)
class TodoSkillProviderProactiveTest {

    @Mock private DataStoreManager dataStoreManager;
    @Mock private PromptRegistry promptRegistry;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TodoSkillProvider provider;

    /** 固定的 Collection，供 ensureCollection() 返回。 */
    private static final Collection TODO_COLLECTION = new Collection(
            "col-todo", "待办事项", "待办事项管理", CollectionType.DOCUMENT,
            null, null, null, Instant.now().toString(), Instant.now().toString());

    @BeforeEach
    void setUp() {
        lenient().when(promptRegistry.render("skill/todo")).thenReturn("待办提示词");
        // DataStoreCrudAdapter.ensureCollection() 需要 findCollection 返回已有集合
        lenient().when(dataStoreManager.findCollection("待办事项")).thenReturn(Optional.of(TODO_COLLECTION));
        provider = new TodoSkillProvider(dataStoreManager, objectMapper, promptRegistry);
    }

    // ---- 辅助方法：将 TodoEntity 包装为 DataStore Document ----

    private Document toDocument(TodoEntity entity) {
        try {
            String json = objectMapper.writeValueAsString(entity);
            String id = UUID.randomUUID().toString();
            return new Document(id, "col-todo", json, null,
                    Instant.now().toString(), Instant.now().toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
        when(dataStoreManager.queryDocuments(any())).thenReturn(List.of());

        List<Signal> signals = provider.signalSources().getFirst().collect();
        assertThat(signals).isEmpty();
    }

    @Test
    void collect_待办无截止日期时_跳过() {
        var entity = new TodoEntity("无截止日期", "PENDING", "MEDIUM", null, null, null);
        when(dataStoreManager.queryDocuments(any())).thenReturn(List.of(toDocument(entity)));

        List<Signal> signals = provider.signalSources().getFirst().collect();
        assertThat(signals).isEmpty();
    }

    @Test
    void collect_截止日期超过24小时_跳过() {
        String farFuture = Instant.now().plus(Duration.ofHours(48)).toString();
        var entity = new TodoEntity("远期待办", "PENDING", "HIGH", farFuture, null, null);
        when(dataStoreManager.queryDocuments(any())).thenReturn(List.of(toDocument(entity)));

        List<Signal> signals = provider.signalSources().getFirst().collect();
        assertThat(signals).isEmpty();
    }

    @Test
    void collect_截止日期已过期_跳过() {
        String past = Instant.now().minus(Duration.ofHours(1)).toString();
        var entity = new TodoEntity("已过期", "PENDING", "HIGH", past, null, null);
        when(dataStoreManager.queryDocuments(any())).thenReturn(List.of(toDocument(entity)));

        List<Signal> signals = provider.signalSources().getFirst().collect();
        assertThat(signals).isEmpty();
    }

    @Test
    void collect_1小时内到期_紧急度为HIGH() {
        String dueDate = Instant.now().plus(Duration.ofMinutes(30)).toString();
        var entity = new TodoEntity("紧急待办", "PENDING", "HIGH", dueDate, null, null);
        when(dataStoreManager.queryDocuments(any())).thenReturn(List.of(toDocument(entity)));

        List<Signal> signals = provider.signalSources().getFirst().collect();
        assertThat(signals).hasSize(1);

        Signal signal = signals.getFirst();
        assertThat(signal.typeId()).isEqualTo("deadline_reminder");
        assertThat(signal.urgency()).isEqualTo(Urgency.HIGH);
        assertThat(signal.sourceId()).isEqualTo("todo-signal");
        assertThat(signal.summary()).contains("紧急待办");
        assertThat(signal.metadata()).containsEntry("title", "紧急待办");
    }

    @Test
    void collect_4小时内到期_紧急度为MEDIUM() {
        String dueDate = Instant.now().plus(Duration.ofHours(4)).toString();
        var entity = new TodoEntity("中等待办", "PENDING", "MEDIUM", dueDate, null, null);
        when(dataStoreManager.queryDocuments(any())).thenReturn(List.of(toDocument(entity)));

        List<Signal> signals = provider.signalSources().getFirst().collect();
        assertThat(signals).hasSize(1);
        assertThat(signals.getFirst().urgency()).isEqualTo(Urgency.MEDIUM);
    }

    @Test
    void collect_12小时内到期_紧急度为LOW() {
        String dueDate = Instant.now().plus(Duration.ofHours(12)).toString();
        var entity = new TodoEntity("低优先待办", "PENDING", "LOW", dueDate, null, null);
        when(dataStoreManager.queryDocuments(any())).thenReturn(List.of(toDocument(entity)));

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
