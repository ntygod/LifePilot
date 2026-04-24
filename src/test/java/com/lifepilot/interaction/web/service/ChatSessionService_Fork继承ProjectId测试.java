package com.lifepilot.interaction.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.interaction.web.repository.SessionDatastoreRepository;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatSessionService fork 会话继承源会话 projectId 的行为测试。
 *
 * <p>Plan 1 Task 12 follow-up：修复 fork 时 projectId 永远为 null 导致
 * "项目下会话被 fork 到主账户"的归属逃逸 bug。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatSessionService Fork 继承 projectId")
class ChatSessionService_Fork继承ProjectId测试 {

    @Mock
    ChatSessionRepository sessionRepository;
    @Mock
    SessionKnowledgeBaseRepository sessionKnowledgeBaseRepository;
    @Mock
    SessionDatastoreRepository sessionDatastoreRepository;
    @Mock
    AttachmentRepository attachmentRepository;
    @Mock
    SessionTranscriptRepository transcriptRepository;

    private ChatSessionService service;

    @BeforeEach
    void setUp() {
        service = new ChatSessionService(
                sessionRepository,
                sessionKnowledgeBaseRepository,
                sessionDatastoreRepository,
                attachmentRepository,
                new ObjectMapper(),
                transcriptRepository,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private void stubForkTranscript(String originalSessionId, String entryId) {
        Instant now = Instant.parse("2026-04-23T00:00:00Z");
        var viewRow = new SessionTranscriptRepository.TranscriptMessageViewRow(
                entryId, originalSessionId, "user_message", "user", "你好",
                null, null, null, null, null, null, null,
                true, true, now
        );
        when(transcriptRepository.findUserConversationRowsBySessionId(originalSessionId))
                .thenReturn(List.of(viewRow));

        var entryRow = new SessionTranscriptRepository.SessionTranscriptEntryRow(
                entryId, originalSessionId, "main", "user_message", "user",
                null, null, true, true, "{\"content\":\"你好\"}", 10, now
        );
        when(transcriptRepository.findById(entryId)).thenReturn(Optional.of(entryRow));
        when(transcriptRepository.copyEntry(anyString(), any())).thenReturn("copied-entry");
    }

    @Test
    void fork_源会话归属项目_新会话继承相同projectId() {
        String sourceId = "source-session";
        String projectId = "p-1";
        Instant now = Instant.parse("2026-04-23T00:00:00Z");
        var sourceSession = new ChatSession(
                sourceId, "项目下的对话", null, 1, false, false, null, now, now, projectId
        );
        when(sessionRepository.findById(sourceId)).thenReturn(Optional.of(sourceSession));
        stubForkTranscript(sourceId, "entry-1");

        service.forkSession(sourceId, "entry-1", "分叉");

        ArgumentCaptor<ChatSession> saveCaptor = ArgumentCaptor.forClass(ChatSession.class);
        verify(sessionRepository).save(saveCaptor.capture());
        assertThat(saveCaptor.getValue().projectId())
                .as("fork 出的新会话必须继承源会话的 projectId")
                .isEqualTo(projectId);
        assertThat(saveCaptor.getValue().title()).isEqualTo("分叉");
    }

    @Test
    void fork_源会话归属主账户_新会话projectId也为null() {
        String sourceId = "source-session";
        Instant now = Instant.parse("2026-04-23T00:00:00Z");
        var sourceSession = new ChatSession(
                sourceId, "主账户对话", null, 1, false, false, null, now, now, null
        );
        when(sessionRepository.findById(sourceId)).thenReturn(Optional.of(sourceSession));
        stubForkTranscript(sourceId, "entry-1");

        service.forkSession(sourceId, "entry-1", null);

        ArgumentCaptor<ChatSession> saveCaptor = ArgumentCaptor.forClass(ChatSession.class);
        verify(sessionRepository).save(saveCaptor.capture());
        assertThat(saveCaptor.getValue().projectId()).isNull();
        assertThat(saveCaptor.getValue().title()).isEqualTo("主账户对话 (fork)");
    }

    @Test
    void fork_空title_使用源标题后缀fork并继承projectId() {
        String sourceId = "source-session";
        String projectId = "p-2";
        Instant now = Instant.parse("2026-04-23T00:00:00Z");
        var sourceSession = new ChatSession(
                sourceId, "原始标题", null, 1, false, false, null, now, now, projectId
        );
        when(sessionRepository.findById(sourceId)).thenReturn(Optional.of(sourceSession));
        stubForkTranscript(sourceId, "entry-1");

        service.forkSession(sourceId, "entry-1", "   ");

        ArgumentCaptor<ChatSession> saveCaptor = ArgumentCaptor.forClass(ChatSession.class);
        verify(sessionRepository).save(saveCaptor.capture());
        assertThat(saveCaptor.getValue().title()).isEqualTo("原始标题 (fork)");
        assertThat(saveCaptor.getValue().projectId()).isEqualTo(projectId);

        // 再验证 findById 被正确调用于加载新会话元数据（forkFromTranscript 末尾会再查一次）
        verify(sessionRepository).findById(eq(sourceId));
    }
}
