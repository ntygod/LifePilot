package com.lifepilot.a2a.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.a2a.config.A2aProperties;
import com.lifepilot.a2a.model.*;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentResponse;
import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.multiagent.execution.AgentExecutor;
import com.lifepilot.multiagent.execution.SubAgentResult;
import com.lifepilot.multiagent.model.AgentDefinition;
import com.lifepilot.multiagent.registry.AgentRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * A2aAgentExecutor 单元测试。
 *
 * @author zsg
 * @since 2026-04-03
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class A2aAgentExecutor_单元测试 {

    @Mock AgentRegistry agentRegistry;
    @Mock AgentExecutor agentExecutor;
    @Mock AgentOrchestrator agentOrchestrator;
    @Mock MeterRegistry meterRegistry;
    @Mock Counter counter;
    @Mock Timer timer;

    private A2aAgentExecutor executor;
    private A2aTaskStore taskStore;

    @BeforeEach
    void setUp() {
        when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(counter);
        when(meterRegistry.timer(anyString())).thenReturn(timer);
        when(timer.record(any(java.util.function.Supplier.class))).thenAnswer(inv -> {
            java.util.function.Supplier<?> supplier = inv.getArgument(0);
            return supplier.get();
        });

        var properties = new A2aProperties();
        taskStore = new A2aTaskStore(properties);
        executor = new A2aAgentExecutor(agentRegistry, agentExecutor, agentOrchestrator,
                taskStore, meterRegistry, new ObjectMapper());
    }

    // ── extractText 测试 ──

    @Test
    void extractText_纯文本消息() {
        var message = new A2aMessage("msg-1", A2aRole.USER,
                List.of(new A2aPart.Text("你好世界", null)), null, null, null);
        assertThat(executor.extractText(message)).isEqualTo("你好世界");
    }

    @Test
    void extractText_多个文本用换行连接() {
        var message = new A2aMessage("msg-1", A2aRole.USER,
                List.of(
                        new A2aPart.Text("第一段", null),
                        new A2aPart.Text("第二段", null)
                ), null, null, null);
        assertThat(executor.extractText(message)).isEqualTo("第一段\n第二段");
    }

    @Test
    void extractText_File部分格式化为描述() {
        var fileContent = new A2aFileContent("report.pdf", "application/pdf", null, "https://example.com/report.pdf");
        var message = new A2aMessage("msg-1", A2aRole.USER,
                List.of(new A2aPart.File(fileContent, null)), null, null, null);

        String result = executor.extractText(message);
        assertThat(result).contains("文件").contains("report.pdf").contains("application/pdf").contains("https://example.com/report.pdf");
    }

    @Test
    void extractText_File部分仅有name() {
        var fileContent = new A2aFileContent("data.csv", null, "base64data", null);
        var message = new A2aMessage("msg-1", A2aRole.USER,
                List.of(new A2aPart.File(fileContent, null)), null, null, null);

        String result = executor.extractText(message);
        assertThat(result).contains("文件").contains("data.csv");
    }

    @Test
    void extractText_File部分file为null_返回空() {
        var message = new A2aMessage("msg-1", A2aRole.USER,
                List.of(new A2aPart.File(null, null)), null, null, null);
        assertThat(executor.extractText(message)).isEmpty();
    }

    @Test
    void extractText_Data部分格式化为JSON() {
        var message = new A2aMessage("msg-1", A2aRole.USER,
                List.of(new A2aPart.Data(Map.of("key", "value"), null)), null, null, null);

        String result = executor.extractText(message);
        assertThat(result).contains("结构化数据").contains("\"key\"").contains("\"value\"");
    }

    @Test
    void extractText_Data部分data为null_返回空() {
        var message = new A2aMessage("msg-1", A2aRole.USER,
                List.of(new A2aPart.Data(null, null)), null, null, null);
        assertThat(executor.extractText(message)).isEmpty();
    }

    @Test
    void extractText_混合类型消息() {
        var fileContent = new A2aFileContent("img.png", "image/png", "base64==", null);
        var message = new A2aMessage("msg-1", A2aRole.USER,
                List.of(
                        new A2aPart.Text("请分析", null),
                        new A2aPart.File(fileContent, null),
                        new A2aPart.Data(Map.of("format", "png"), null)
                ), null, null, null);

        String result = executor.extractText(message);
        assertThat(result).contains("请分析").contains("img.png").contains("结构化数据");
    }

    // ── execute 流程测试 ──

    @Test
    void execute_无skillId_调用Orchestrator返回COMPLETED() {
        var message = new A2aMessage("msg-1", A2aRole.USER,
                List.of(new A2aPart.Text("hello", null)), null, null, null);

        var response = mock(AgentResponse.class);
        when(response.content()).thenReturn("执行结果");
        when(agentOrchestrator.run(any(AgentRequest.class))).thenReturn(response);

        A2aTask task = executor.execute(message, null);

        assertThat(task.status().state()).isEqualTo(A2aTaskState.COMPLETED);
        assertThat(task.artifacts()).isNotEmpty();
        assertThat(task.artifacts().getFirst().parts().getFirst())
                .isInstanceOf(A2aPart.Text.class);
    }

    @Test
    void execute_Orchestrator返回null_状态为FAILED() {
        var message = new A2aMessage("msg-1", A2aRole.USER,
                List.of(new A2aPart.Text("hello", null)), null, null, null);

        var response = mock(AgentResponse.class);
        when(response.content()).thenReturn(null);
        when(agentOrchestrator.run(any(AgentRequest.class))).thenReturn(response);

        A2aTask task = executor.execute(message, null);

        assertThat(task.status().state()).isEqualTo(A2aTaskState.FAILED);
    }

    @Test
    void execute_有skillId_未找到Skill_状态为FAILED() {
        var message = new A2aMessage("msg-1", A2aRole.USER,
                List.of(new A2aPart.Text("hello", null)), null, null, null);

        when(agentRegistry.find("nonexistent")).thenReturn(Optional.empty());

        A2aTask task = executor.execute(message, "nonexistent");

        assertThat(task.status().state()).isEqualTo(A2aTaskState.FAILED);
        verify(agentOrchestrator, never()).run(any());
    }

    @Test
    void execute_Orchestrator抛异常_状态为FAILED() {
        var message = new A2aMessage("msg-1", A2aRole.USER,
                List.of(new A2aPart.Text("hello", null)), null, null, null);

        when(agentOrchestrator.run(any(AgentRequest.class))).thenThrow(new RuntimeException("模拟异常"));

        A2aTask task = executor.execute(message, null);

        assertThat(task.status().state()).isEqualTo(A2aTaskState.FAILED);
    }

    // ── executeStreaming 流程测试 ──

    @Test
    void executeStreaming_成功完成_回调收到多次通知() throws InterruptedException {
        var message = new A2aMessage("msg-1", A2aRole.USER,
                List.of(new A2aPart.Text("hello", null)), null, null, null);

        var response = mock(AgentResponse.class);
        when(response.content()).thenReturn("流式结果");
        when(agentOrchestrator.run(any(AgentRequest.class))).thenReturn(response);

        var latch = new java.util.concurrent.CountDownLatch(1);
        var notifications = new java.util.concurrent.CopyOnWriteArrayList<A2aTask>();

        executor.executeStreaming(message, null, task -> {
            notifications.add(task);
            if (task.status().state().isTerminal()) {
                latch.countDown();
            }
        });

        assertThat(latch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        // 至少收到：SUBMITTED, WORKING, ARTIFACT, COMPLETED 四次通知
        assertThat(notifications).hasSizeGreaterThanOrEqualTo(4);
        assertThat(notifications.getLast().status().state()).isEqualTo(A2aTaskState.COMPLETED);
    }
}
