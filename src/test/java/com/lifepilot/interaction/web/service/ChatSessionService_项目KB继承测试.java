package com.lifepilot.interaction.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.conversation.transcript.SessionTranscriptRepository;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.interaction.web.repository.SessionDatastoreRepository;
import com.lifepilot.interaction.web.repository.SessionKnowledgeBaseRepository;
import com.lifepilot.project.service.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ChatSessionService 创建项目会话时自动继承项目默认 KB 的行为测试。
 *
 * <p>产品决策：Agent 应用里"主动关联知识库"是 ChatBot 时代的残留。
 * 正确姿态是作用域默认 + Agent 自主。这里落地第一步——项目里创建的对话，
 * 自动把项目默认 KB 关联到 session_knowledge_bases，让 RAG 检索默认命中项目文档。</p>
 *
 * @author zsg
 * @since 2026-04-24
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatSessionService 创建项目会话自动继承项目 KB")
class ChatSessionService_项目KB继承测试 {

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
    @Mock
    ProjectService projectService;

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
                projectService
        );
    }

    @Test
    void 创建项目会话_自动把项目默认KB关联到session() {
        String projectId = "p-1";
        when(projectService.findKnowledgeBaseIds(projectId))
                .thenReturn(List.of("kb-proj-default"));

        var session = service.createSession("项目对话", projectId);

        verify(sessionKnowledgeBaseRepository)
                .addAssociation(session.id(), "kb-proj-default");
    }

    @Test
    void 创建项目会话_多个KB_全部关联() {
        String projectId = "p-1";
        when(projectService.findKnowledgeBaseIds(projectId))
                .thenReturn(List.of("kb-a", "kb-b", "kb-c"));

        var session = service.createSession("多 KB 项目", projectId);

        verify(sessionKnowledgeBaseRepository).addAssociation(session.id(), "kb-a");
        verify(sessionKnowledgeBaseRepository).addAssociation(session.id(), "kb-b");
        verify(sessionKnowledgeBaseRepository).addAssociation(session.id(), "kb-c");
    }

    @Test
    void 创建项目会话_项目没有默认KB_不做关联() {
        String projectId = "p-1";
        when(projectService.findKnowledgeBaseIds(projectId))
                .thenReturn(List.of());

        service.createSession("空 KB 项目", projectId);

        verify(sessionKnowledgeBaseRepository, never())
                .addAssociation(anyString(), anyString());
    }

    @Test
    void 创建主账户会话_不查项目KB不做关联() {
        service.createSession("主账户对话", null);

        verifyNoInteractions(projectService);
        verify(sessionKnowledgeBaseRepository, never())
                .addAssociation(anyString(), anyString());
    }

    @Test
    void 创建项目会话_projectId空白_降级为主账户行为() {
        service.createSession("空白 projectId", "   ");

        verifyNoInteractions(projectService);
        verify(sessionKnowledgeBaseRepository, never())
                .addAssociation(anyString(), anyString());
    }

    @Test
    void 创建项目会话_KB查询抛异常_会话仍创建只记警告() {
        String projectId = "p-1";
        when(projectService.findKnowledgeBaseIds(projectId))
                .thenThrow(new RuntimeException("KB 服务临时故障"));

        // 不抛异常 —— 会话创建不应被 KB 故障阻断
        var session = service.createSession("故障恢复", projectId);

        verify(sessionRepository).save(session);
        verify(sessionKnowledgeBaseRepository, never())
                .addAssociation(anyString(), anyString());
    }

}
