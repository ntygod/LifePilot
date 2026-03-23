package com.lifepilot.interaction.middleware.execution;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentResponse;
import com.lifepilot.interaction.config.GatewayProperties;
import com.lifepilot.interaction.middleware.MiddlewareChain;
import com.lifepilot.interaction.middleware.MiddlewareContext;
import com.lifepilot.interaction.model.ChannelMetadata;
import com.lifepilot.interaction.model.ChannelType;
import com.lifepilot.interaction.model.GatewayMessage;
import com.lifepilot.interaction.model.MessageContent;
import com.lifepilot.interaction.web.model.SessionConfigKeys;
import com.lifepilot.interaction.web.repository.ChatSessionRepository;
import com.lifepilot.agent.orchestration.AgentOrchestrator;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ExecutionMiddleware 单元测试。
 *
 * @author zsg
 * @since 2026-03-23
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

        executionMiddleware = new ExecutionMiddleware(
                agentOrchestrator,
                agentConfigProperties,
                buildGatewayProperties(),
                chatSessionRepository,
                null
        );
    }

    @Test
    void 会话仅覆盖maxTokens时_步骤和时长继承全局预算() {
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
    void 会话覆盖三维预算时_运行态Budget完整生效() {
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

    private GatewayMessage buildMessage(String sessionId) {
        return GatewayMessage.builder()
                .channelType(ChannelType.WEB)
                .userId("web-user")
                .sessionId(sessionId)
                .content(new MessageContent.TextMessage("测试消息"))
                .channelMetadata(new ChannelMetadata.WebMetadata(
                        "JUnit", "127.0.0.1", null, false, null))
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
