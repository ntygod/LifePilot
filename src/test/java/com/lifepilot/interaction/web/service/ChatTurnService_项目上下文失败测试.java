package com.lifepilot.interaction.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.conversation.event.ConversationCompletedEvent;
import com.lifepilot.interaction.web.model.ChatRequest;
import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.interaction.web.model.ChatTurnStatus;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.interaction.web.repository.ChatTurnRepository;
import com.lifepilot.memory.governance.policy.MemoryAccessPolicy;
import com.lifepilot.memory.store.scope.ChatTurnMemorySnapshot;
import com.lifepilot.memory.store.scope.ChatTurnMemorySnapshotRepository;
import com.lifepilot.memory.store.scope.MemorySpace;
import com.lifepilot.memory.store.scope.MemorySpaceRepository;
import com.lifepilot.memory.store.scope.MemorySpaceType;
import com.lifepilot.project.context.ProjectContext;
import com.lifepilot.project.context.ProjectContextResolver;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatTurnService 项目上下文失败治理测试。
 *
 * @author zsg
 * @since 2026-06-18
 */
class ChatTurnService_项目上下文失败测试 {

    @Test
    void 项目上下文解析失败_快照关闭自动学习且不落项目空间() {
        var turnRepository = mock(ChatTurnRepository.class);
        var transcriptRepository = mock(com.lifepilot.conversation.transcript.SessionTranscriptRepository.class);
        var snapshotRepository = mock(ChatTurnMemorySnapshotRepository.class);
        var memorySpaceRepository = mock(MemorySpaceRepository.class);
        var chatSessionRepository = mock(ChatSessionRepository.class);
        var projectContextResolver = mock(ProjectContextResolver.class);

        when(turnRepository.findBySessionIdAndTurnId(anyString(), anyString())).thenReturn(Optional.empty());
        when(memorySpaceRepository.ensureDefaultPersonalSpace()).thenReturn(space("space-personal"));
        when(memorySpaceRepository.ensureDefaultExperienceSpace()).thenReturn(space("space-experience"));
        when(chatSessionRepository.findById("session-1")).thenReturn(Optional.of(session("session-1", "project-1")));
        when(projectContextResolver.resolve("project-1")).thenThrow(new IllegalStateException("项目缺失"));

        var service = new ChatTurnService(
                turnRepository,
                transcriptRepository,
                new ObjectMapper(),
                mock(ApplicationEventPublisher.class),
                null,
                null,
                snapshotRepository,
                memorySpaceRepository,
                chatSessionRepository,
                projectContextResolver,
                new MemoryAccessPolicy());

        service.prepare("session-1", new ChatRequest(
                "turn-1",
                null,
                "你好",
                "session-1",
                null,
                null));

        var captor = org.mockito.ArgumentCaptor.forClass(ChatTurnMemorySnapshot.class);
        verify(snapshotRepository).save(captor.capture());
        var snapshot = captor.getValue();
        assertThat(snapshot.projectSpaceId()).isNull();
        assertThat(snapshot.personalLearningEnabled()).isFalse();
        assertThat(snapshot.domainLearningEnabled()).isFalse();
        assertThat(snapshot.experienceLearningEnabled()).isFalse();
        assertThat(snapshot.resolutionSource())
                .containsEntry("projectResolutionFailed", true)
                .containsEntry("projectResolutionReason", "project_context_resolution_failed");
    }

    @Test
    void 用户明确不要写入长期记忆_快照关闭本轮自动学习() {
        var turnRepository = mock(ChatTurnRepository.class);
        var transcriptRepository = mock(com.lifepilot.conversation.transcript.SessionTranscriptRepository.class);
        var snapshotRepository = mock(ChatTurnMemorySnapshotRepository.class);
        var memorySpaceRepository = mock(MemorySpaceRepository.class);
        var chatSessionRepository = mock(ChatSessionRepository.class);
        var projectContextResolver = mock(ProjectContextResolver.class);

        when(turnRepository.findBySessionIdAndTurnId(anyString(), anyString())).thenReturn(Optional.empty());
        when(memorySpaceRepository.ensureDefaultPersonalSpace()).thenReturn(space("space-personal"));
        when(memorySpaceRepository.ensureDefaultExperienceSpace()).thenReturn(space("space-experience"));
        when(chatSessionRepository.findById("session-1")).thenReturn(Optional.of(session("session-1", null)));
        when(projectContextResolver.resolve(null))
                .thenReturn(ProjectContext.personal("space-personal", "space-experience"));

        var service = new ChatTurnService(
                turnRepository,
                transcriptRepository,
                new ObjectMapper(),
                mock(ApplicationEventPublisher.class),
                null,
                null,
                snapshotRepository,
                memorySpaceRepository,
                chatSessionRepository,
                projectContextResolver,
                new MemoryAccessPolicy());

        service.prepare("session-1", new ChatRequest(
                "turn-no-memory-write",
                null,
                "请直接回答，不要写入长期记忆。",
                "session-1",
                null,
                null));

        var captor = org.mockito.ArgumentCaptor.forClass(ChatTurnMemorySnapshot.class);
        verify(snapshotRepository).save(captor.capture());
        var snapshot = captor.getValue();
        assertThat(snapshot.personalLearningEnabled()).isFalse();
        assertThat(snapshot.domainLearningEnabled()).isFalse();
        assertThat(snapshot.experienceLearningEnabled()).isFalse();
        assertThat(snapshot.resolutionSource())
                .containsEntry("autoLearningSkippedByUser", true)
                .containsEntry("autoLearningSkipReason", "user_memory_write_denied");
    }

    @Test
    void 完成事件应携带本轮记忆学习边界() {
        var turnRepository = mock(ChatTurnRepository.class);
        var transcriptRepository = mock(com.lifepilot.conversation.transcript.SessionTranscriptRepository.class);
        var snapshotRepository = mock(ChatTurnMemorySnapshotRepository.class);
        var publisher = mock(ApplicationEventPublisher.class);
        when(snapshotRepository.findByTurnId("turn-no-memory-write"))
                .thenReturn(Optional.of(new ChatTurnMemorySnapshot(
                        "turn-no-memory-write",
                        "session-1",
                        "space-personal",
                        "space-experience",
                        null,
                        null,
                        java.util.List.of("space-personal", "space-experience"),
                        java.util.List.of(),
                        false,
                        false,
                        false,
                        Map.of(
                                "autoLearningSkippedByUser", true,
                                "autoLearningSkipReason", "user_memory_write_denied"),
                        Instant.now())));

        var service = new ChatTurnService(
                turnRepository,
                transcriptRepository,
                new ObjectMapper(),
                publisher,
                null,
                null,
                snapshotRepository,
                null,
                null,
                null,
                new MemoryAccessPolicy());

        service.markCompleted(
                "session-1",
                "turn-no-memory-write",
                ChatTurnStatus.SUCCESS,
                null,
                "trace-1",
                null,
                null);

        var captor = org.mockito.ArgumentCaptor.forClass(ConversationCompletedEvent.class);
        verify(publisher).publishEvent(captor.capture());
        var event = captor.getValue();
        assertThat(event.getTurnId()).isEqualTo("turn-no-memory-write");
        assertThat(event.isMemoryLearningEnabled()).isFalse();
        assertThat(event.getMemoryLearningSkipReason()).isEqualTo("user_memory_write_denied");
    }

    private MemorySpace space(String id) {
        return new MemorySpace(
                id,
                id,
                MemorySpaceType.PERSONAL,
                id,
                null,
                null,
                java.util.Map.of(),
                Instant.now(),
                Instant.now());
    }

    private ChatSession session(String id, String projectId) {
        Instant now = Instant.now();
        return new ChatSession(id, "title", null, 0, false, false, null, now, now, projectId);
    }
}
