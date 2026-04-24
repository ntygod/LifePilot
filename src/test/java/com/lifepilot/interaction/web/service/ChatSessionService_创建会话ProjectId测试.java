package com.lifepilot.interaction.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.web.model.ChatSession;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.interaction.web.repository.SessionDatastoreRepository;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * ChatSessionService 创建会话时 projectId 透传行为测试。
 *
 * @author zsg
 * @since 2026-04-23
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatSessionService 创建会话 projectId 透传")
class ChatSessionService_创建会话ProjectId测试 {

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

    @Test
    void 创建会话_带projectId_保存实体projectId正确() {
        ChatSession created = service.createSession("项目对话", "p-1");

        assertThat(created.projectId()).isEqualTo("p-1");
        assertThat(created.title()).isEqualTo("项目对话");

        ArgumentCaptor<ChatSession> captor = ArgumentCaptor.forClass(ChatSession.class);
        verify(sessionRepository).save(captor.capture());
        assertThat(captor.getValue().projectId()).isEqualTo("p-1");
    }

    @Test
    void 创建会话_不带projectId_保存实体projectId为null() {
        ChatSession created = service.createSession("普通对话", null);

        assertThat(created.projectId()).isNull();

        ArgumentCaptor<ChatSession> captor = ArgumentCaptor.forClass(ChatSession.class);
        verify(sessionRepository).save(captor.capture());
        assertThat(captor.getValue().projectId()).isNull();
    }

    @Test
    void 创建会话_兼容旧签名_projectId默认null() {
        ChatSession created = service.createSession("旧风格");

        assertThat(created.projectId()).isNull();
        assertThat(created.title()).isEqualTo("旧风格");

        ArgumentCaptor<ChatSession> captor = ArgumentCaptor.forClass(ChatSession.class);
        verify(sessionRepository).save(captor.capture());
        assertThat(captor.getValue().projectId()).isNull();
    }
}
