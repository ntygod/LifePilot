package com.lifepilot.interaction.web.controller;

import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.interaction.model.DeliveryMode;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.GatewayResponse;
import com.lifepilot.interaction.model.ResponseContent;
import com.lifepilot.interaction.service.ChannelIngressService;
import com.lifepilot.interaction.web.model.A2uiComponent;
import com.lifepilot.interaction.web.model.A2uiSignal;
import com.lifepilot.interaction.web.model.MessageInfo;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatController A2UI 合同测试")
class ChatController_A2uiContract_测试 {

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
    void 非流式消息接口_返回标准ChatResponse并包含A2ui组件和恢复摘要() throws Exception {
        var components = List.of(
                new A2uiComponent("card-1", "Card", Map.of("title", "待办面板"), List.of("btn-1"), null),
                new A2uiComponent("btn-1", "Button", Map.of("label", "刷新"), List.of(),
                        new A2uiSignal("panel.refresh", Map.of("section", "todos")))
        );
        var toolsSummary = List.<Map<String, Object>>of(Map.of(
                "toolId", "skill.load",
                "toolName", "加载 Skill",
                "executionKind", "SKILL",
                "status", "FAILED",
                "failureCategory", "SKILL"));
        var taskRecovery = Map.<String, Object>of(
                "status", "DEGRADED",
                "title", "技能加载没有完成",
                "detail", "可以检查技能名称或依赖后继续。",
                "canResume", true,
                "canRestart", true,
                "resumeMode", "manual");
        when(browserIngressService.buildChatMessage(any(), any(), eq(DeliveryMode.SYNC)))
                .thenReturn(mock(GatewayMessage.class));
        when(channelIngressService.submitSync(any())).thenReturn(
                GatewayResponse.builder()
                        .responseId("assistant-1")
                        .content(new ResponseContent.TextContent("这是当前面板"))
                        .metadata(Map.of(
                                "traceId", "trace-1",
                                "turnId", "turn-1",
                                "completionMode", CompletionMode.NORMAL.name(),
                                "a2uiComponents", components,
                                "toolsSummary", toolsSummary,
                                "taskRecovery", taskRecovery
                        ))
                        .latency(Duration.ofMillis(8))
                        .statusCode(200)
                        .build()
        );

        mockMvc.perform(post("/api/chat/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content": "展示今天的待办",
                                  "sessionId": "session-1"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entryId").value("assistant-1"))
                .andExpect(jsonPath("$.content").value("这是当前面板"))
                .andExpect(jsonPath("$.traceId").value("trace-1"))
                .andExpect(jsonPath("$.a2uiComponents[0].type").value("Card"))
                .andExpect(jsonPath("$.a2uiComponents[1].signal.name").value("panel.refresh"))
                .andExpect(jsonPath("$.toolsSummary[0].toolId").value("skill.load"))
                .andExpect(jsonPath("$.toolsSummary[0].executionKind").value("SKILL"))
                .andExpect(jsonPath("$.taskRecovery.status").value("DEGRADED"))
                .andExpect(jsonPath("$.taskRecovery.title").value("技能加载没有完成"));
    }

    @Test
    void 非流式消息接口_文本和附件都为空时返回400() throws Exception {
        mockMvc.perform(post("/api/chat/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "content": "   ",
                                  "sessionId": "session-1",
                                  "attachmentIds": []
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("消息内容和附件不能同时为空"));

        verify(browserIngressService, never()).buildChatMessage(any(), any(), any());
        verify(channelIngressService, never()).submitSync(any());
    }

    @Test
    void signal接口_返回标准ChatResponse并包含A2ui组件() throws Exception {
        var components = List.of(
                new A2uiComponent("card-1", "Card", Map.of("title", "待办面板"), List.of("btn-1"), null),
                new A2uiComponent("btn-1", "Button", Map.of("label", "刷新"), List.of(),
                        new A2uiSignal("panel.refresh", Map.of("section", "todos")))
        );
        when(browserIngressService.buildSignalMessage(any(), any()))
                .thenReturn(mock(GatewayMessage.class));
        when(channelIngressService.submitSync(any())).thenReturn(
                GatewayResponse.builder()
                        .responseId("assistant-2")
                        .content(new ResponseContent.TextContent("好的，已刷新面板"))
                        .metadata(Map.of(
                                "traceId", "trace-2",
                                "turnId", "turn-2",
                                "completionMode", CompletionMode.NORMAL.name(),
                                "a2uiComponents", components
                        ))
                        .latency(Duration.ofMillis(5))
                        .statusCode(200)
                        .build()
        );

        mockMvc.perform(post("/api/chat/signals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "panel.refresh",
                                  "payload": {"section": "todos"},
                                  "sessionId": "session-1"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entryId").value("assistant-2"))
                .andExpect(jsonPath("$.content").value("好的，已刷新面板"))
                .andExpect(jsonPath("$.traceId").value("trace-2"))
                .andExpect(jsonPath("$.a2uiComponents[0].id").value("card-1"))
                .andExpect(jsonPath("$.a2uiComponents[1].signal.name").value("panel.refresh"));
    }

    @Test
    void 历史消息接口_返回A2ui组件列表供前端回放() throws Exception {
        when(chatSessionService.getSessionMessages("session-1")).thenReturn(List.of(
                new MessageInfo(
                        "assistant-1",
                        "assistant",
                        "这是当前面板",
                        List.of(
                                new A2uiComponent("card-1", "Card", Map.of("title", "待办面板"), List.of("btn-1"), null),
                                new A2uiComponent("btn-1", "Button", Map.of("label", "刷新"), List.of(),
                                        new A2uiSignal("panel.refresh", Map.of("section", "todos")))
                        ),
                        Instant.parse("2026-03-11T08:00:00Z"),
                        "已生成结构化面板",
                        "trace-1",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                )
        ));

        mockMvc.perform(get("/api/chat/sessions/session-1/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("assistant-1"))
                .andExpect(jsonPath("$[0].traceId").value("trace-1"))
                .andExpect(jsonPath("$[0].a2uiComponents[0].type").value("Card"))
                .andExpect(jsonPath("$[0].a2uiComponents[1].signal.name").value("panel.refresh"));
    }

    @Test
    void 资料库沉淀接口_记录消息已存入目标资料库() throws Exception {
        mockMvc.perform(post("/api/chat/entries/assistant-1/knowledge-settlements")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "knowledgeBaseId": "kb-product",
                                  "knowledgeBaseName": "产品资料",
                                  "sourceType": "ARTIFACT",
                                  "artifactId": "artifact-report",
                                  "fileName": "report.md"
                                }
                                """))
                .andExpect(status().isNoContent());

        verify(chatSessionService).recordKnowledgeSettlement(eq("assistant-1"), argThat(request ->
                "kb-product".equals(request.knowledgeBaseId())
                        && "产品资料".equals(request.knowledgeBaseName())
                        && "ARTIFACT".equals(request.sourceType())
                        && "artifact-report".equals(request.artifactId())
                        && "report.md".equals(request.fileName())
        ));
    }
}
