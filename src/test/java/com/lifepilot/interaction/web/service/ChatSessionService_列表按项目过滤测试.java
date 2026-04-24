package com.lifepilot.interaction.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.conversation.transcript.SessionStoreRepository;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatSessionService 会话列表按 projectId 过滤的行为测试。
 *
 * <p>Plan 1 Task 13：Service 层正确将 projectId 参数映射为 ProjectScope 并下传 Repository。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatSessionService 列表按项目过滤")
class ChatSessionService_列表按项目过滤测试 {

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
                null
        );
    }

    @Test
    void 列表_不传projectId_使用MainAccount作用域() {
        when(sessionRepository.findByConditions(any(), any(), any(), any(), any(), any(),
                any(SessionStoreRepository.ProjectScope.class)))
                .thenReturn(List.of());

        service.listSessions(null, null, null, null, "updatedAt", "desc", null);

        ArgumentCaptor<SessionStoreRepository.ProjectScope> captor =
                ArgumentCaptor.forClass(SessionStoreRepository.ProjectScope.class);
        verify(sessionRepository).findByConditions(any(), any(), any(), any(), any(), any(), captor.capture());
        assertThat(captor.getValue()).isInstanceOf(SessionStoreRepository.ProjectScope.MainAccount.class);
    }

    @Test
    void 列表_传projectId_使用OfProject作用域() {
        when(sessionRepository.findByConditions(any(), any(), any(), any(), any(), any(),
                any(SessionStoreRepository.ProjectScope.class)))
                .thenReturn(List.of());

        service.listSessions(null, null, null, null, "updatedAt", "desc", "p-1");

        ArgumentCaptor<SessionStoreRepository.ProjectScope> captor =
                ArgumentCaptor.forClass(SessionStoreRepository.ProjectScope.class);
        verify(sessionRepository).findByConditions(any(), any(), any(), any(), any(), any(), captor.capture());
        assertThat(captor.getValue())
                .isInstanceOfSatisfying(
                        SessionStoreRepository.ProjectScope.OfProject.class,
                        of -> assertThat(of.projectId()).isEqualTo("p-1")
                );
    }

    @Test
    void 列表_projectId空白字符串_退化为MainAccount作用域() {
        when(sessionRepository.findByConditions(any(), any(), any(), any(), any(), any(),
                any(SessionStoreRepository.ProjectScope.class)))
                .thenReturn(List.of());

        service.listSessions(null, null, null, null, "updatedAt", "desc", "   ");

        ArgumentCaptor<SessionStoreRepository.ProjectScope> captor =
                ArgumentCaptor.forClass(SessionStoreRepository.ProjectScope.class);
        verify(sessionRepository).findByConditions(any(), any(), any(), any(), any(), any(), captor.capture());
        assertThat(captor.getValue()).isInstanceOf(SessionStoreRepository.ProjectScope.MainAccount.class);
    }

    @Test
    void 列表_旧签名无projectId参数_等价于MainAccount作用域() {
        when(sessionRepository.findByConditions(any(), any(), any(), any(), any(), any(),
                any(SessionStoreRepository.ProjectScope.class)))
                .thenReturn(List.of());

        service.listSessions(null, null, null, null, "updatedAt", "desc");

        ArgumentCaptor<SessionStoreRepository.ProjectScope> captor =
                ArgumentCaptor.forClass(SessionStoreRepository.ProjectScope.class);
        verify(sessionRepository).findByConditions(any(), any(), any(), any(), any(), any(), captor.capture());
        assertThat(captor.getValue()).isInstanceOf(SessionStoreRepository.ProjectScope.MainAccount.class);
    }
}
