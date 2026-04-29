package com.lifepilot.observability.guardrail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * GuardrailAdvisor 单元测试。
 *
 * <p>覆盖输入被阻断、输出被阻断和需要确认三类护栏决策。</p>
 */
@ExtendWith(MockitoExtension.class)
class GuardrailAdvisor单元测试 {

    @Mock
    GuardrailEngine guardrailEngine;

    @Mock
    ChatClientRequest chatClientRequest;

    @Mock
    CallAdvisorChain callAdvisorChain;

    @Mock
    ChatClientResponse chatClientResponse;

    GuardrailAdvisor guardrailAdvisor;

    @Test
    @DisplayName("输入被阻断_抛出GuardrailBlockedException()")
    void 输入被阻断_抛出GuardrailBlockedException() {
        // 通过深度 stub 直接在调用点 mock prompt.getInstructions() 相关行为
        ChatClientRequest requestStub = mock(ChatClientRequest.class, RETURNS_DEEP_STUBS);
        // 构造带正确 MessageType 和 text 的 mock Message
        var mockMessage = mock(org.springframework.ai.chat.messages.Message.class);
        when(mockMessage.getMessageType()).thenReturn(org.springframework.ai.chat.messages.MessageType.USER);
        when(mockMessage.getText()).thenReturn("用户输入包含违规内容");

        when(requestStub.prompt().getInstructions().stream())
                .thenAnswer(invocation -> java.util.stream.Stream.of(mockMessage));

        // GuardrailEngine 返回 BLOCK 决策
        GuardrailResult.Blocked blocked = new GuardrailResult.Blocked(
                "policy-input-block", "输入违规", RiskLevel.HIGH);
        when(guardrailEngine.checkInput(any(), eq("用户输入包含违规内容"))).thenReturn(blocked);

        guardrailAdvisor = new GuardrailAdvisor(guardrailEngine);

        // 执行 adviseCall，期望抛出 GuardrailBlockedException，且不会调用下游 chain
        assertThatThrownBy(() -> guardrailAdvisor.adviseCall(requestStub, callAdvisorChain))
                .isInstanceOf(GuardrailBlockedException.class)
                .hasMessageContaining("policy-input-block")
                .hasMessageContaining("输入违规");

        verify(callAdvisorChain, never()).nextCall(any());
    }

    @Test
    @DisplayName("输出被阻断_抛出GuardrailBlockedException()")
    void 输出被阻断_抛出GuardrailBlockedException() {
        // 使用深度 stub 构造 ChatClientRequest：prompt.getInstructions() 为空列表
        ChatClientRequest requestStub = mock(ChatClientRequest.class, RETURNS_DEEP_STUBS);
        when(requestStub.prompt().getInstructions()).thenReturn(List.of());

        // 模拟 LLM 输出：使用深度 stub 直接配置 getResult().getOutput().getText()
        ChatClientResponse responseStub = mock(ChatClientResponse.class, RETURNS_DEEP_STUBS);
        when(responseStub.chatResponse().getResult().getOutput().getText())
                .thenReturn("LLM 输出包含违规内容");
        when(callAdvisorChain.nextCall(requestStub)).thenReturn(responseStub);

        // GuardrailEngine 检查输出返回 BLOCK 决策
        GuardrailResult.Blocked blocked = new GuardrailResult.Blocked(
                "policy-output-block", "输出违规", RiskLevel.MEDIUM);
        when(guardrailEngine.checkOutput(any(), eq("LLM 输出包含违规内容"))).thenReturn(blocked);

        guardrailAdvisor = new GuardrailAdvisor(guardrailEngine);

        assertThatThrownBy(() -> guardrailAdvisor.adviseCall(requestStub, callAdvisorChain))
                .isInstanceOf(GuardrailBlockedException.class)
                .hasMessageContaining("policy-output-block")
                .hasMessageContaining("输出违规");
    }

    @Test
    @DisplayName("需要确认场景_抛出GuardrailConfirmationRequiredException()")
    void 需要确认场景_抛出GuardrailConfirmationRequiredException() {
        // 输入阶段：返回空 USER 内容，让 guardrailEngine.checkInput 不被调用
        ChatClientRequest requestStub = mock(ChatClientRequest.class, RETURNS_DEEP_STUBS);
        when(requestStub.prompt().getInstructions()).thenReturn(List.of());

        // 模拟 LLM 输出
        ChatClientResponse responseStub = mock(ChatClientResponse.class, RETURNS_DEEP_STUBS);
        when(responseStub.chatResponse().getResult().getOutput().getText())
                .thenReturn("LLM 输出需要人工确认");
        when(callAdvisorChain.nextCall(requestStub)).thenReturn(responseStub);

        // GuardrailEngine 检查输出返回 NeedsConfirmation 决策
        GuardrailResult.NeedsConfirmation needsConfirmation = new GuardrailResult.NeedsConfirmation(
                "policy-confirm", "请用户确认该内容", ApprovalMode.USER_CONFIRM);
        when(guardrailEngine.checkOutput(any(), eq("LLM 输出需要人工确认"))).thenReturn(needsConfirmation);

        guardrailAdvisor = new GuardrailAdvisor(guardrailEngine);

        assertThatThrownBy(() -> guardrailAdvisor.adviseCall(requestStub, callAdvisorChain))
                .isInstanceOf(GuardrailConfirmationRequiredException.class)
                .hasMessageContaining("policy-confirm")
                .hasMessageContaining("请用户确认该内容");
    }
}

