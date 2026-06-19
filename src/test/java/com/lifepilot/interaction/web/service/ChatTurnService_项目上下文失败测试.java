package com.lifepilot.interaction.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.web.model.ChatRequest;
import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.interaction.web.repository.ChatTurnRepository;
import com.lifepilot.memory.store.scope.ChatTurnMemorySnapshot;
import com.lifepilot.memory.store.scope.ChatTurnMemorySnapshotRepository;
import com.lifepilot.memory.store.scope.MemorySpace;
import com.lifepilot.memory.store.scope.MemorySpaceRepository;
import com.lifepilot.memory.store.scope.MemorySpaceType;
import com.lifepilot.project.context.ProjectContextResolver;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
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
                null);

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
