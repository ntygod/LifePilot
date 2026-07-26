package com.lifepilot.interaction.middleware.execution;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentResponse;
import com.lifepilot.agent.model.AgentTaskMode;
import com.lifepilot.agent.model.CompletionMode;
import com.lifepilot.agent.model.CompletionReason;
import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.interaction.config.GatewayProperties;
import com.lifepilot.interaction.middleware.MiddlewareChain;
import com.lifepilot.interaction.middleware.MiddlewareContext;
import com.lifepilot.interaction.model.ChannelMetadata;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.MessageContent;
import com.lifepilot.interaction.web.model.ChatTurnAction;
import com.lifepilot.interaction.web.model.SessionConfigKeys;
import com.lifepilot.interaction.web.model.SessionConfigOverride;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ExecutionMiddleware 单元测试。
 *
 * @author zsg
 * @since 2026-03-25
 */
@ExtendWith(MockitoExtension.class)
class ExecutionMiddleware_单元测试 {

    @Mock
    private AgentOrchestrator agentOrchestrator;

    @Mock
    private ChatSessionRepository chatSessionRepository;

    private AgentConfigProperties agentConfigProperties;
    private ExecutionMiddleware executionMiddleware;

    @BeforeEach
    void setUp() {
        agentConfigProperties = new AgentConfigProperties();
        agentConfigProperties.getBudget().setDefaultMaxTokens(32000);
        agentConfigProperties.getBudget().setDefaultMaxSteps(77);
        agentConfigProperties.getBudget().setDefaultMaxDurationSeconds(444);
        agentConfigProperties.getExecutionRetry().setEnabled(true);
        agentConfigProperties.getExecutionRetry().setMaxAttempts(2);
        agentConfigProperties.getExecutionRetry().setInitialDelayMs(0);

        executionMiddleware = new ExecutionMiddleware(
                agentOrchestrator,
                agentConfigProperties,
                buildGatewayProperties(),
                chatSessionRepository,
                null
        );
    }

    @Test
    void 会话仅覆盖maxSteps时应继承全局Token与时长预算() {
        when(chatSessionRepository.getConfig("session-1"))
                .thenReturn(Map.of(SessionConfigKeys.MAX_STEPS, 11));
        when(agentOrchestrator.run(any()))
                .thenReturn(new AgentResponse("trace-1", "session-1", "ok", 12, 1, null));

        var response = executionMiddleware.process(
                buildMessage("session-1"),
                new MiddlewareChain(List.of(), new MiddlewareContext())
        );

        assertThat(response.isSuccess()).isTrue();

        var requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentOrchestrator).run(requestCaptor.capture());
        var budget = requestCaptor.getValue().budget();
        assertThat(budget).isNotNull();
        assertThat(budget.maxTokens()).isEqualTo(32000);
        assertThat(budget.maxSteps()).isEqualTo(11);
        assertThat(budget.maxDuration()).isEqualTo(Duration.ofSeconds(444));
    }

    @Test
    void 会话覆盖步骤与时长预算时应完整生效() {
        when(chatSessionRepository.getConfig("session-2"))
                .thenReturn(Map.of(
                        SessionConfigKeys.MAX_STEPS, 9,
                        SessionConfigKeys.MAX_DURATION_SECONDS, 42
                ));
        when(agentOrchestrator.run(any()))
                .thenReturn(new AgentResponse("trace-2", "session-2", "ok", 20, 2, null));

        executionMiddleware.process(
                buildMessage("session-2"),
                new MiddlewareChain(List.of(), new MiddlewareContext())
        );

        var requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentOrchestrator).run(requestCaptor.capture());
        var budget = requestCaptor.getValue().budget();
        assertThat(budget).isNotNull();
        assertThat(budget.maxTokens()).isEqualTo(32000);
        assertThat(budget.maxSteps()).isEqualTo(9);
        assertThat(budget.maxDuration()).isEqualTo(Duration.ofSeconds(42));
    }

    @Test
    @SuppressWarnings("unchecked")
    void Web恢复上下文应透传到AgentRequest() {
        when(chatSessionRepository.getConfig("session-recovery")).thenReturn(Map.of());
        when(agentOrchestrator.run(any()))
                .thenReturn(new AgentResponse("trace-recovery", "session-recovery", "ok", 8, 1, null));
        Map<String, Object> recoveryContext = Map.of(
                "action", "RESUME",
                "sourceTraceId", "trace-failed-1",
                "title", "修正后继续",
                "nextActions", List.of("查看命令输出并修正报错原因", "从失败命令后继续执行验证")
        );
        var message = GatewayMessage.builder()
                .messageId("turn-recovery")
                .channelType(ChannelType.WEB)
                .userId("web-user")
                .sessionId("session-recovery")
                .content(new MessageContent.TextMessage("继续执行"))
                .channelMetadata(new ChannelMetadata.WebMetadata(
                        "JUnit", "127.0.0.1", null, false, null,
                        "turn-recovery", ChatTurnAction.RESUME, null, recoveryContext))
                .build();

        var response = executionMiddleware.process(
                message,
                new MiddlewareChain(List.of(), new MiddlewareContext())
        );

        assertThat(response.isSuccess()).isTrue();
        var requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentOrchestrator).run(requestCaptor.capture());
        assertThat(requestCaptor.getValue().turnRecoveryContext())
                .containsEntry("action", "RESUME")
                .containsEntry("sourceTraceId", "trace-failed-1")
                .containsEntry("title", "修正后继续");
        assertThat((List<String>) requestCaptor.getValue().turnRecoveryContext().get("nextActions"))
                .containsExactly("查看命令输出并修正报错原因", "从失败命令后继续执行验证");
    }

    @Test
    void Web单轮记忆上下文模式应透传到AgentRequest() {
        when(chatSessionRepository.getConfig("session-memory")).thenReturn(Map.of());
        when(agentOrchestrator.run(any()))
                .thenReturn(new AgentResponse("trace-memory", "session-memory", "ok", 8, 1, null));
        var message = GatewayMessage.builder()
                .messageId("turn-memory")
                .channelType(ChannelType.WEB)
                .userId("web-user")
                .sessionId("session-memory")
                .content(new MessageContent.TextMessage("按我的偏好整理这段文字"))
                .channelMetadata(new ChannelMetadata.WebMetadata(
                        "JUnit", "127.0.0.1", null, false, null,
                        "turn-memory", ChatTurnAction.SEND,
                        new SessionConfigOverride(null, null, null, null, null, "focused"),
                        null))
                .build();

        var response = executionMiddleware.process(
                message,
                new MiddlewareChain(List.of(), new MiddlewareContext())
        );

        assertThat(response.isSuccess()).isTrue();
        var requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentOrchestrator).run(requestCaptor.capture());
        assertThat(requestCaptor.getValue().memoryContextMode()).isEqualTo("focused");
    }

    @Test
    void 同步瞬时失败时主执行链路会自动重试一次() {
        when(chatSessionRepository.getConfig("session-3")).thenReturn(Map.of());
        when(agentOrchestrator.run(any()))
                .thenReturn(new AgentResponse("trace-fail", "session-3", "LLM 调用超时: 30s", 0, 0, "异常终止"))
                .thenReturn(new AgentResponse("trace-ok", "session-3", "最终回答", 18, 1, null));

        var response = executionMiddleware.process(
                buildMessage("session-3"),
                new MiddlewareChain(List.of(), new MiddlewareContext())
        );

        assertThat(response.isSuccess()).isTrue();
        verify(agentOrchestrator, times(2)).run(any());
    }

    @Test
    void 普通发送请求不应在网关层预判任务模式() {
        when(chatSessionRepository.getConfig("session-4")).thenReturn(Map.of());
        when(agentOrchestrator.run(any()))
                .thenReturn(new AgentResponse("trace-4", "session-4", "ok", 8, 1, null));

        executionMiddleware.process(
                buildMessage("session-4", "请读取目录需求并自动写代码后提交 GitHub", ChatTurnAction.SEND),
                new MiddlewareChain(List.of(), new MiddlewareContext())
        );

        var requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentOrchestrator).run(requestCaptor.capture());
        assertThat(requestCaptor.getValue().taskMode()).isEqualTo(AgentTaskMode.AUTO);
    }

    @Test
    void 普通发送开头明确不用工具时应切换为纯回答模式() {
        when(chatSessionRepository.getConfig("session-answer-mode")).thenReturn(Map.of());
        when(agentOrchestrator.run(any()))
                .thenReturn(new AgentResponse("trace-answer", "session-answer-mode", "ok", 8, 1, null));

        executionMiddleware.process(
                buildMessage("session-answer-mode", "请不要调用工具，直接回答：帮我解释这段话", ChatTurnAction.SEND),
                new MiddlewareChain(List.of(), new MiddlewareContext())
        );

        var requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentOrchestrator).run(requestCaptor.capture());
        assertThat(requestCaptor.getValue().taskMode()).isEqualTo(AgentTaskMode.ANSWER);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 普通发送开头明确不要联网时应禁用网页与浏览器工具() {
        when(chatSessionRepository.getConfig("session-no-web")).thenReturn(Map.of());
        when(agentOrchestrator.run(any()))
                .thenReturn(new AgentResponse("trace-no-web", "session-no-web", "ok", 8, 1, null));

        var response = executionMiddleware.process(
                buildMessage("session-no-web", "不要联网，帮我整理本地资料并写成清单", ChatTurnAction.SEND),
                new MiddlewareChain(List.of(), new MiddlewareContext())
        );

        var requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentOrchestrator).run(requestCaptor.capture());
        assertThat(requestCaptor.getValue().taskMode()).isEqualTo(AgentTaskMode.AUTO);
        assertThat(requestCaptor.getValue().disabledToolIds()).containsExactly("web", "browser");

        var executionConstraints = (Map<String, Object>) response.metadata().get("executionConstraints");
        assertThat(executionConstraints).isNotNull();
        var disabledTools = (List<Map<String, Object>>) executionConstraints.get("disabledTools");
        assertThat(disabledTools)
                .extracting(item -> item.get("id"))
                .containsExactly("web", "browser");
        assertThat(disabledTools)
                .extracting(item -> item.get("label"))
                .containsExactly("联网搜索", "浏览器操作");
    }

    @Test
    void 正文里提到直接回答偏好不应误切为纯回答模式() {
        when(chatSessionRepository.getConfig("session-auto-mode")).thenReturn(Map.of());
        when(agentOrchestrator.run(any()))
                .thenReturn(new AgentResponse("trace-auto", "session-auto-mode", "ok", 8, 1, null));

        executionMiddleware.process(
                buildMessage("session-auto-mode", "请总结这段话：用户希望助手直接回答但必要时仍可整理资料", ChatTurnAction.SEND),
                new MiddlewareChain(List.of(), new MiddlewareContext())
        );

        var requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentOrchestrator).run(requestCaptor.capture());
        assertThat(requestCaptor.getValue().taskMode()).isEqualTo(AgentTaskMode.AUTO);
    }

    @Test
    void 正文里提到不要联网不应误禁用网页工具() {
        when(chatSessionRepository.getConfig("session-no-web-body")).thenReturn(Map.of());
        when(agentOrchestrator.run(any()))
                .thenReturn(new AgentResponse("trace-no-web-body", "session-no-web-body", "ok", 8, 1, null));

        executionMiddleware.process(
                buildMessage("session-no-web-body", "请总结这段话：用户说不要联网但这里是原文内容", ChatTurnAction.SEND),
                new MiddlewareChain(List.of(), new MiddlewareContext())
        );

        var requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentOrchestrator).run(requestCaptor.capture());
        assertThat(requestCaptor.getValue().disabledToolIds()).isNull();
    }

    @Test
    void 恢复重试类系统动作也不应在网关层硬编码任务模式() {
        when(chatSessionRepository.getConfig("session-5")).thenReturn(Map.of());
        when(agentOrchestrator.run(any()))
                .thenReturn(new AgentResponse("trace-5", "session-5", "ok", 8, 1, null));

        executionMiddleware.process(
                buildMessage("session-5", "继续刚才那轮执行", ChatTurnAction.RETRY),
                new MiddlewareChain(List.of(), new MiddlewareContext())
        );

        var requestCaptor = ArgumentCaptor.forClass(AgentRequest.class);
        verify(agentOrchestrator).run(requestCaptor.capture());
        assertThat(requestCaptor.getValue().taskMode()).isEqualTo(AgentTaskMode.AUTO);
    }

    @Test
    void 显式Blocked终态在同步链路不应映射为Http500() {
        when(chatSessionRepository.getConfig("session-6")).thenReturn(Map.of());
        when(agentOrchestrator.run(any()))
                .thenReturn(new AgentResponse(
                        "trace-6",
                        "session-6",
                        "turn-6",
                        AgentTaskMode.AUTO,
                        "当前请求无法继续执行，需要额外授权",
                        15,
                        2,
                        "需要额外授权",
                        CompletionReason.EXPLICIT_BLOCKED,
                        "assistant-6",
                        null,
                        null,
                        CompletionMode.NORMAL,
                        null,
                        com.lifepilot.interaction.web.model.ChatTurnStatus.DEGRADED,
                        java.util.List.of()
                ));

        var response = executionMiddleware.process(
                buildMessage("session-6", "继续执行受限操作", ChatTurnAction.SEND),
                new MiddlewareChain(List.of(), new MiddlewareContext())
        );

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.errorMessage()).isNull();
        assertThat(response.metadata())
                .containsEntry("completionReason", "EXPLICIT_BLOCKED")
                .containsEntry("completionMode", "NORMAL");
    }

    @Test
    void 同步响应应透传工具摘要和任务恢复摘要() {
        when(chatSessionRepository.getConfig("session-recovery-summary")).thenReturn(Map.of());
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
        when(agentOrchestrator.run(any()))
                .thenReturn(new AgentResponse(
                        "trace-recovery-summary",
                        "session-recovery-summary",
                        "turn-recovery-summary",
                        AgentTaskMode.AUTO,
                        "技能加载失败，可以检查后继续。",
                        18,
                        2,
                        "技能加载失败",
                        CompletionReason.UNEXPECTED_EXCEPTION,
                        "assistant-recovery-summary",
                        null,
                        null,
                        CompletionMode.DEGRADED,
                        null,
                        com.lifepilot.interaction.web.model.ChatTurnStatus.DEGRADED,
                        java.util.List.of(),
                        toolsSummary,
                        taskRecovery
                ));

        var response = executionMiddleware.process(
                buildMessage("session-recovery-summary", "加载调研技能", ChatTurnAction.SEND),
                new MiddlewareChain(List.of(), new MiddlewareContext())
        );

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.metadata())
                .containsEntry("toolsSummary", toolsSummary)
                .containsEntry("taskRecovery", taskRecovery)
                .containsEntry("turnStatus", "DEGRADED");
    }

    private GatewayMessage buildMessage(String sessionId) {
        return buildMessage(sessionId, "测试消息", ChatTurnAction.SEND);
    }

    private GatewayMessage buildMessage(String sessionId, String text, ChatTurnAction action) {
        return GatewayMessage.builder()
                .messageId("turn-" + sessionId)
                .channelType(ChannelType.WEB)
                .userId("web-user")
                .sessionId(sessionId)
                .content(new MessageContent.TextMessage(text))
                .channelMetadata(new ChannelMetadata.WebMetadata(
                        "JUnit", "127.0.0.1", null, false, null, null, action))
                .build();
    }

    private GatewayProperties buildGatewayProperties() {
        var middleware = new GatewayProperties.MiddlewareProperties(
                new GatewayProperties.MiddlewareProperties.AuthMiddlewareProperties(true, 100),
                new GatewayProperties.MiddlewareProperties.RateLimitMiddlewareProperties(true, 200),
                new GatewayProperties.MiddlewareProperties.SecurityMiddlewareProperties(true, 300),
                new GatewayProperties.MiddlewareProperties.RouterMiddlewareProperties(true, 400),
                new GatewayProperties.MiddlewareProperties.ExecutionMiddlewareProperties(true, 500),
                new GatewayProperties.MiddlewareProperties.AuditMiddlewareProperties(true, 600)
        );
        return new GatewayProperties(
                true,
                middleware,
                new GatewayProperties.RateLimitProperties(30),
                new GatewayProperties.SecurityProperties(
                        new GatewayProperties.SecurityProperties.PromptInjectionProperties(true),
                        new GatewayProperties.SecurityProperties.SensitiveDataProperties(true),
                        new GatewayProperties.SecurityProperties.TrustScoreProperties(true, 30)
                ),
                new GatewayProperties.AuthProperties(
                        new GatewayProperties.AuthProperties.WebAuthProperties(
                                new GatewayProperties.AuthProperties.WebAuthProperties.JwtProperties(false, 24),
                                new GatewayProperties.AuthProperties.WebAuthProperties.SessionAuthProperties(true, 30)
                        )
                ),
                new GatewayProperties.RouterProperties(List.of("todo", "schedule", "habit")),
                new GatewayProperties.ExecutionProperties(120, true),
                new GatewayProperties.AuditProperties(true, 200, 200, 90),
                new GatewayProperties.ChannelsProperties(
                        new GatewayProperties.ChannelsProperties.CliChannelProperties(true),
                        new GatewayProperties.ChannelsProperties.WebChannelProperties(true),
                        new GatewayProperties.ChannelsProperties.WecomChannelProperties(false, null, null, null, null, null),
                        new GatewayProperties.ChannelsProperties.DingtalkChannelProperties(false, null, null, null),
                        new GatewayProperties.ChannelsProperties.FeishuChannelProperties(false, null, null, null, null, 10000),
                        new GatewayProperties.ChannelsProperties.QqChannelProperties(false, null, null)
                ),
                new GatewayProperties.ReconnectProperties(10, 1000, 60000, 2.0),
                new GatewayProperties.SessionProperties(30, 24, 15),
                new GatewayProperties.WebhookProperties(300, 3, 60)
        );
    }
}
