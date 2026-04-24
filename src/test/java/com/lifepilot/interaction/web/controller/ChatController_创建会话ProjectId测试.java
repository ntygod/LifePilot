package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.service.ChannelIngressService;
import com.lifepilot.interaction.web.model.ChatSession;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ChatController 创建会话接口对 projectId 的透传行为测试。
 *
 * @author zsg
 * @since 2026-04-23
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatController 创建会话 projectId 透传")
class ChatController_创建会话ProjectId测试 {

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
                null,
                null,
                mediaProperties
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void 创建会话_请求体带projectId_透传到service() throws Exception {
        var now = Instant.parse("2026-04-23T00:00:00Z");
        when(chatSessionService.createSession(eq("我的项目对话"), eq("p-1")))
                .thenReturn(new ChatSession(
                        "session-1",
                        "我的项目对话",
                        null,
                        0,
                        false,
                        false,
                        null,
                        now,
                        now,
                        "p-1"
                ));

        mockMvc.perform(post("/api/chat/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "我的项目对话",
                                  "projectId": "p-1"
                                }
                                """))
                .andExpect(status().isCreated());

        verify(chatSessionService).createSession(eq("我的项目对话"), eq("p-1"));
    }

    @Test
    void 创建会话_请求体不带projectId_透传null() throws Exception {
        var now = Instant.parse("2026-04-23T00:00:00Z");
        when(chatSessionService.createSession(any(), isNull()))
                .thenReturn(new ChatSession(
                        "session-2",
                        "普通对话",
                        null,
                        0,
                        false,
                        false,
                        null,
                        now,
                        now,
                        null
                ));

        mockMvc.perform(post("/api/chat/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "普通对话"
                                }
                                """))
                .andExpect(status().isCreated());

        verify(chatSessionService).createSession(eq("普通对话"), isNull());
    }

    @Test
    void 创建会话_projectId显式为null_透传null() throws Exception {
        var now = Instant.parse("2026-04-23T00:00:00Z");
        when(chatSessionService.createSession(any(), isNull()))
                .thenReturn(new ChatSession(
                        "session-3",
                        "新对话",
                        null,
                        0,
                        false,
                        false,
                        null,
                        now,
                        now,
                        null
                ));

        mockMvc.perform(post("/api/chat/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "新对话",
                                  "projectId": null
                                }
                                """))
                .andExpect(status().isCreated());

        verify(chatSessionService).createSession(eq("新对话"), isNull());
    }
}
