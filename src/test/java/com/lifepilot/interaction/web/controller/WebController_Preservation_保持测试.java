package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.service.ChannelIngressService;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.interaction.web.repository.MessageFeedbackRepository;
import com.lifepilot.interaction.web.service.BrowserIngressService;
import com.lifepilot.interaction.web.service.ChatSessionService;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.knowledge.KnowledgeBaseManager;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web Controller 保持测试（Preservation Property）。
 *
 * <p>验证现有正常工作的 Web API 端点在修复前后保持正常：
 * <ul>
 *   <li>{@code GET /api/chat/sessions} — 会话列表端点返回 200</li>
 *   <li>{@code GET /api/knowledge-bases} — 知识库列表端点返回 200</li>
 *   <li>Controller 端点路由注册正确</li>
 * </ul>
 *
 * <p>使用 Standalone MockMvc 直接构建 Controller 实例，Mock 服务依赖，
 * 聚焦验证端点注册和路由可达性，不依赖 Spring Boot 上下文加载。</p>
 *
 * <p><b>Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7</b></p>
 *
 * @author zsg
 * @since 2026-03-07
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Web Controller 保持测试")
class WebController_Preservation_保持测试 {

    private MockMvc mockMvc;

    // ── ChatController 依赖 ──────────────────────────────────
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

    // ── KnowledgeBaseController 依赖 ─────────────────────────
    @Mock
    KnowledgeBaseManager knowledgeBaseManager;

    @BeforeEach
    void setUp() {
        var mediaProperties = new MediaProperties();
        var chatController = new ChatController(
                browserIngressService, channelIngressService, sseSessionManager, chatSessionService,
                messageFeedbackRepository, attachmentRepository, knowledgeBaseProperties,
                null, null, null, mediaProperties);
        var kbController = new KnowledgeBaseController(
                knowledgeBaseManager, knowledgeBaseProperties, null, null, null);

        mockMvc = MockMvcBuilders.standaloneSetup(chatController, kbController).build();
    }

    /**
     * Controller 端点路由注册正确。
     *
     * <p>验证 ChatController 和 KnowledgeBaseController 的 {@code @RequestMapping}
     * 注解正确声明了 {@code /api/chat} 和 {@code /api/knowledge-bases} 路径。
     * 这是最基础的保持性验证 — 修复不应改变已有端点的路由。</p>
     *
     * <p><b>Validates: Requirements 3.5, 3.6</b></p>
     */
    @Test
    @DisplayName("Controller端点路由注册正确")
    void controller端点路由注册正确() {
        // ChatController 应映射到 /api/chat
        var chatMapping = ChatController.class.getAnnotation(
                org.springframework.web.bind.annotation.RequestMapping.class);
        assertThat(chatMapping).isNotNull();
        assertThat(chatMapping.value()).contains("/api/chat");

        // KnowledgeBaseController 应映射到 /api/knowledge-bases
        var kbMapping = KnowledgeBaseController.class.getAnnotation(
                org.springframework.web.bind.annotation.RequestMapping.class);
        assertThat(kbMapping).isNotNull();
        assertThat(kbMapping.value()).contains("/api/knowledge-bases");
    }

    /**
     * 会话列表端点返回 200。
     *
     * <p>{@code GET /api/chat/sessions} 是聊天界面的核心端点，
     * 用于获取用户的会话列表。此端点在未修复代码上已正常工作，
     * 修复后必须继续返回 200。</p>
     *
     * <p><b>Validates: Requirements 3.1, 3.6</b></p>
     */
    @Test
    @DisplayName("GET_api_chat_sessions_返回200")
    void get_api_chat_sessions_返回200() throws Exception {
        when(chatSessionService.listSessions(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/chat/sessions"))
                .andExpect(status().isOk());
    }

    /**
     * 知识库列表端点返回 200。
     *
     * <p>{@code GET /api/knowledge-bases} 是知识库管理页面的核心端点，
     * 用于获取所有知识库列表。此端点在未修复代码上已正常工作，
     * 修复后必须继续返回 200。</p>
     *
     * <p><b>Validates: Requirements 3.4, 3.6</b></p>
     */
    @Test
    @DisplayName("GET_api_knowledge_bases_返回200")
    void get_api_knowledge_bases_返回200() throws Exception {
        when(knowledgeBaseManager.listKnowledgeBases()).thenReturn(List.of());

        mockMvc.perform(get("/api/knowledge-bases"))
                .andExpect(status().isOk());
    }
}
