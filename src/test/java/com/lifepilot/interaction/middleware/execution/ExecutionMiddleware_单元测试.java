package com.lifepilot.interaction.middleware.execution;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentResponse;
import com.lifepilot.agent.model.AgentTaskMode;
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
    void 会话仅覆盖maxTokens时应继承全局步骤与时长预算() {
        when(chatSessionRepository.getConfig("session-1"))
                .thenReturn(Map.of(SessionConfigKeys.MAX_TOKENS, 4096));
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
        assertThat(budget.maxTokens()).isEqualTo(4096);
        assertThat(budget.maxSteps()).isEqualTo(77);
        assertThat(budget.maxDuration()).isEqualTo(Duration.ofSeconds(444));
    }

    @Test
    void 会话覆盖三维预算时应完整生效() {
        when(chatSessionRepository.getConfig("session-2"))
                .thenReturn(Map.of(
                        SessionConfigKeys.MAX_TOKENS, 8192,
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
        assertThat(budget.maxTokens()).isEqualTo(8192);
        assertThat(budget.maxSteps()).isEqualTo(9);
        assertThat(budget.maxDuration()).isEqualTo(Duration.ofSeconds(42));
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
                new GatewayProperties.RateLimitProperties(100000, 500000, 2000, 30),
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
                        new GatewayProperties.ChannelsProperties.FeishuChannelProperties(false, null, null, null, null, 10000)
                ),
                new GatewayProperties.ReconnectProperties(10, 1000, 60000, 2.0),
                new GatewayProperties.SessionProperties(30, 24, 15),
                new GatewayProperties.WebhookProperties(300, 3, 60)
        );
    }
}
