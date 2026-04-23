package com.lifepilot.interaction.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.web.model.ChatRequest;
import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.interaction.web.model.ChatTurnAction;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.interaction.web.repository.ChatTurnRepository;
import com.lifepilot.interaction.web.repository.SessionDatastoreRepository;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.memory.scope.ChatTurnMemorySnapshot;
import com.lifepilot.memory.scope.ChatTurnMemorySnapshotRepository;
import com.lifepilot.memory.scope.MemorySpace;
import com.lifepilot.memory.scope.MemorySpaceRepository;
import com.lifepilot.memory.scope.MemorySpaceType;
import com.lifepilot.project.context.ProjectContext;
import com.lifepilot.project.context.ProjectContextResolver;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ChatTurnService 项目隔离快照字段测试 —— 覆盖 persistTurnMemorySnapshot
 * 按 ProjectContext 填充 projectSpaceId 的四类路径：
 * ISOLATED → 非空 / SHARED → null / 主账户 → null / resolver 缺失 fallback → null。
 *
 * <p>采用 mock 依赖 + in-memory ChatTurnMemorySnapshotRepository 捕获
 * 实际写入的 snapshot，避免 @SpringBootTest 上下文开销。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
class ChatTurnService_项目隔离快照字段测试 {

    private ChatTurnService service;
    private AtomicReference<ChatTurnMemorySnapshot> captured;
    private ChatSessionRepository chatSessionRepository;
    private ProjectContextResolver projectContextResolver;
    private MemorySpace personalSpace;
    private MemorySpace experienceSpace;

    @BeforeEach
    void setUp() {
        ChatTurnRepository chatTurnRepository = mock(ChatTurnRepository.class);
        when(chatTurnRepository.findBySessionIdAndTurnId(any(), any())).thenReturn(Optional.empty());

        SessionTranscriptRepository transcriptRepository = mock(SessionTranscriptRepository.class);
        SessionKnowledgeBaseRepository sessionKbRepo = mock(SessionKnowledgeBaseRepository.class);
        when(sessionKbRepo.findKnowledgeBaseIdsBySessionId(any())).thenReturn(List.of());
        SessionDatastoreRepository sessionDatastoreRepo = mock(SessionDatastoreRepository.class);
        when(sessionDatastoreRepo.findDatastoreIdsBySessionId(any())).thenReturn(List.of());

        Instant now = Instant.parse("2026-04-23T00:00:00Z");
        personalSpace = new MemorySpace("personal-1", "personal", MemorySpaceType.PERSONAL,
                "personal", null, null, Map.of(), now, now);
        experienceSpace = new MemorySpace("experience-1", "experience", MemorySpaceType.EXPERIENCE,
                "experience", null, null, Map.of(), now, now);
        MemorySpaceRepository memorySpaceRepository = mock(MemorySpaceRepository.class);
        when(memorySpaceRepository.ensureDefaultPersonalSpace()).thenReturn(personalSpace);
        when(memorySpaceRepository.ensureDefaultExperienceSpace()).thenReturn(experienceSpace);

        captured = new AtomicReference<>();
        ChatTurnMemorySnapshotRepository snapshotRepo = mock(ChatTurnMemorySnapshotRepository.class);
        org.mockito.Mockito.doAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return null;
        }).when(snapshotRepo).save(any());

        chatSessionRepository = mock(ChatSessionRepository.class);
        projectContextResolver = mock(ProjectContextResolver.class);

        service = new ChatTurnService(
                chatTurnRepository,
                transcriptRepository,
                new ObjectMapper(),
                event -> {},
                null,
                sessionKbRepo,
                sessionDatastoreRepo,
                snapshotRepo,
                memorySpaceRepository,
                chatSessionRepository,
                projectContextResolver
        );
    }

    @Test
    void 隔离项目对话_快照填入项目空间id() {
        String sessionId = "session-isolated";
        String projectId = "project-x";
        String projectSpaceId = "space-project-x";
        when(chatSessionRepository.findById(sessionId))
                .thenReturn(Optional.of(new ChatSession(sessionId, "标题", null, 0,
                        false, false, null, Instant.now(), Instant.now(), projectId)));
        when(projectContextResolver.resolve(projectId))
                .thenReturn(new ProjectContext(projectId, projectSpaceId,
                        personalSpace.id(), experienceSpace.id(), true));

        service.prepare(sessionId, new ChatRequest(
                "turn-iso", ChatTurnAction.SEND, "hello", null, null, null));

        ChatTurnMemorySnapshot saved = captured.get();
        assertThat(saved).isNotNull();
        assertThat(saved.projectSpaceId()).isEqualTo(projectSpaceId);
        assertThat(saved.resolutionSource()).containsEntry("resolvedProjectSpaceId", projectSpaceId);
    }

    @Test
    void 共享项目对话_不填项目空间id() {
        String sessionId = "session-shared";
        String projectId = "project-shared";
        when(chatSessionRepository.findById(sessionId))
                .thenReturn(Optional.of(new ChatSession(sessionId, "标题", null, 0,
                        false, false, null, Instant.now(), Instant.now(), projectId)));
        // SHARED 项目的 ProjectContext.isolated = false
        when(projectContextResolver.resolve(projectId))
                .thenReturn(new ProjectContext(projectId, "space-project-shared",
                        personalSpace.id(), experienceSpace.id(), false));

        service.prepare(sessionId, new ChatRequest(
                "turn-shared", ChatTurnAction.SEND, "hello", null, null, null));

        assertThat(captured.get().projectSpaceId()).isNull();
    }

    @Test
    void 主账户对话_projectId为null_不触发resolver() {
        String sessionId = "session-main";
        when(chatSessionRepository.findById(sessionId))
                .thenReturn(Optional.of(new ChatSession(sessionId, "标题", null, 0,
                        false, false, null, Instant.now(), Instant.now(), null)));

        service.prepare(sessionId, new ChatRequest(
                "turn-main", ChatTurnAction.SEND, "hello", null, null, null));

        assertThat(captured.get().projectSpaceId()).isNull();
        org.mockito.Mockito.verifyNoInteractions(projectContextResolver);
    }

    @Test
    void 会话查询异常_走fallback填null() {
        String sessionId = "session-err";
        when(chatSessionRepository.findById(sessionId))
                .thenThrow(new RuntimeException("db down"));

        service.prepare(sessionId, new ChatRequest(
                "turn-err", ChatTurnAction.SEND, "hello", null, null, null));

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().projectSpaceId()).isNull();
    }
}
