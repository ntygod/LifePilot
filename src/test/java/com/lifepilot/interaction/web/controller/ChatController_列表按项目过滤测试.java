package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.service.ChannelIngressService;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.interaction.web.repository.MessageFeedbackRepository;
import com.lifepilot.interaction.web.service.BrowserIngressService;
import com.lifepilot.interaction.web.service.ChatSessionService;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.media.config.MediaProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ChatController 会话列表按 projectId 过滤的透传行为测试。
 *
 * <p>Plan 1 Task 13：GET /api/chat/sessions 将 projectId 请求参数透传到 Service。</p>
 *
 * @author zsg
 * @since 2026-04-23
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatController 列表按项目过滤透传")
class ChatController_列表按项目过滤测试 {

    private MockMvc mockMvc;

    @Mock
    BrowserIngressService browserIngressService;
    @Mock
    ChannelIngressService channelIngressService;
    @Mock
    SseSessionManager sseSessionManager;
    @Mock
    ChatSessionService chatSessionService;
    @Mock
    MessageFeedbackRepository messageFeedbackRepository;
    @Mock
    AttachmentRepository attachmentRepository;
    @Mock
    KnowledgeBaseProperties knowledgeBaseProperties;

    @BeforeEach
    void setUp() {
        var mediaProperties = new MediaProperties();
        var controller = new ChatController(
                browserIngressService,
                channelIngressService,
                sseSessionManager,
                chatSessionService,
                messageFeedbackRepository,
                attachmentRepository,
                knowledgeBaseProperties,
                null,  // ZhiweiPaths — 本测试不涉及附件上传
                null,
                null,
                mediaProperties
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void 列表_带projectId参数_透传到service() throws Exception {
        when(chatSessionService.listSessions(any(), any(), any(), any(), any(), any(), eq("p-1")))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/chat/sessions").param("projectId", "p-1"))
                .andExpect(status().isOk());

        verify(chatSessionService).listSessions(
                isNull(), isNull(), isNull(), isNull(),
                eq("updatedAt"), eq("desc"), eq("p-1")
        );
    }

    @Test
    void 列表_不带projectId参数_透传null() throws Exception {
        when(chatSessionService.listSessions(any(), any(), any(), any(), any(), any(), isNull()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/chat/sessions"))
                .andExpect(status().isOk());

        verify(chatSessionService).listSessions(
                isNull(), isNull(), isNull(), isNull(),
                eq("updatedAt"), eq("desc"), isNull()
        );
    }
}
