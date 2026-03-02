package com.lifepilot.observability.trace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * TraceAdvisor 单元测试。
 *
 * <p>验证正常 LLM 调用和异常场景下，能够正确构建 {@link LlmCallStep}
 * 并通过 {@link TraceRecorder#recordStep(TraceContext, TraceStep)} 记录。</p>
 */
@ExtendWith(MockitoExtension.class)
class TraceAdvisor单元测试 {

    @Mock
    TraceRecorder traceRecorder;

    @Mock
    TraceContext traceContext;

    @Mock
    ChatClientRequest chatClientRequest;

    TraceAdvisor traceAdvisor;

    @Test
    @DisplayName("正常LLM调用_记录完整LlmCallStep()")
    void 正常LLM调用_记录完整LlmCallStep() {
        // 准备 Trace 上下文
        when(traceRecorder.currentContext()).thenReturn(Optional.of(traceContext));
        when(traceContext.steps()).thenReturn(List.of());

        // 构造带 usage 和 metadata 的 ChatResponse（全部使用 Mockito mock，避免依赖具体实现）
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(10);
        when(usage.getCompletionTokens()).thenReturn(20);

        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        when(metadata.getModel()).thenReturn("gpt-test-model");
        when(metadata.getUsage()).thenReturn(usage);

        ChatResponse chatResponse = mock(ChatResponse.class);
        when(chatResponse.getMetadata()).thenReturn(metadata);
        when(chatResponse.getResult()).thenReturn(null); // 不关心 finishReason，由库内部填充

        ChatClientResponse clientResponse = mock(ChatClientResponse.class);
        when(clientResponse.chatResponse()).thenReturn(chatResponse);

        // 使用简单的 CallAdvisorChain 实现，仅实现 nextCall，其他方法返回默认值
        var callAdvisorChain = new SimpleCallAdvisorChain(clientResponse);

        traceAdvisor = new TraceAdvisor(traceRecorder);

        // 调用 adviseCall
        Instant before = Instant.now();
        ChatClientResponse result = traceAdvisor.adviseCall(chatClientRequest, callAdvisorChain);
        Instant after = Instant.now();

        // 验证返回值透传
        assertThat(result).isSameAs(clientResponse);

        // 捕获记录的步骤
        ArgumentCaptor<LlmCallStep> stepCaptor = ArgumentCaptor.forClass(LlmCallStep.class);
        verify(traceRecorder).recordStep(eq(traceContext), stepCaptor.capture());

        LlmCallStep step = stepCaptor.getValue();
        assertThat(step).isNotNull();
        assertThat(step.stepIndex()).isZero();
        assertThat(step.timestamp()).isBetween(before.minusSeconds(1), after.plusSeconds(1));
        assertThat(step.duration()).isGreaterThanOrEqualTo(Duration.ZERO);
        assertThat(step.latency()).isGreaterThanOrEqualTo(Duration.ZERO);

        assertThat(step.modelId()).isEqualTo("gpt-test-model");
        assertThat(step.inputTokens()).isEqualTo(10);
        assertThat(step.outputTokens()).isEqualTo(20);
        assertThat(step.finishReason()).isEqualTo("stop");
    }

    @Test
    @DisplayName("LLM调用异常_记录错误信息并重新抛出()")
    void LLM调用异常_记录错误信息并重新抛出() {
        when(traceRecorder.currentContext()).thenReturn(Optional.of(traceContext));
        when(traceContext.steps()).thenReturn(List.of());

        RuntimeException failure = new RuntimeException("boom");

        var failingChain = new SimpleCallAdvisorChain(failure);

        traceAdvisor = new TraceAdvisor(traceRecorder);

        assertThatThrownBy(() -> traceAdvisor.adviseCall(chatClientRequest, failingChain))
                .isSameAs(failure);

        ArgumentCaptor<LlmCallStep> stepCaptor = ArgumentCaptor.forClass(LlmCallStep.class);
        verify(traceRecorder).recordStep(eq(traceContext), stepCaptor.capture());

        LlmCallStep step = stepCaptor.getValue();
        assertThat(step).isNotNull();
        assertThat(step.finishReason()).isEqualTo("error: boom");
    }

    /**
     * 简单的 CallAdvisorChain 实现，用于测试。
     */
    static class SimpleCallAdvisorChain implements org.springframework.ai.chat.client.advisor.api.CallAdvisorChain {

        private final ChatClientResponse fixedResponse;
        private final RuntimeException toThrow;

        SimpleCallAdvisorChain(ChatClientResponse fixedResponse) {
            this.fixedResponse = fixedResponse;
            this.toThrow = null;
        }

        SimpleCallAdvisorChain(RuntimeException toThrow) {
            this.fixedResponse = null;
            this.toThrow = toThrow;
        }

        @Override
        public ChatClientResponse nextCall(ChatClientRequest request) {
            if (toThrow != null) {
                throw toThrow;
            }
            return fixedResponse;
        }

        @Override
        public java.util.List<org.springframework.ai.chat.client.advisor.api.CallAdvisor> getCallAdvisors() {
            return java.util.List.of();
        }

        @Override
        public org.springframework.ai.chat.client.advisor.api.CallAdvisorChain copy(org.springframework.ai.chat.client.advisor.api.CallAdvisor advisor) {
            return this;
        }
    }
}


