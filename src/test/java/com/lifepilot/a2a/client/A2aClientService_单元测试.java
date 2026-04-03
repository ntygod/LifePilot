package com.lifepilot.a2a.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.a2a.config.A2aProperties;
import com.lifepilot.a2a.model.*;
import com.lifepilot.mcp.protocol.JsonRpcMessage;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * A2aClientService 单元测试。
 *
 * @author zsg
 * @since 2026-04-03
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class A2aClientService_单元测试 {

    @Mock MeterRegistry meterRegistry;
    @Mock Counter counter;
    @Mock Timer timer;
    @Mock A2aCircuitBreakerRegistry circuitBreakerRegistry;

    private A2aClientService clientService;
    private MockRestServiceServer mockServer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(counter);
        when(meterRegistry.timer(anyString())).thenReturn(timer);
        when(timer.record(any(java.util.function.Supplier.class))).thenAnswer(inv -> {
            java.util.function.Supplier<?> supplier = inv.getArgument(0);
            return supplier.get();
        });

        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        var restClientBuilder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();

        var properties = new A2aProperties();
        clientService = new A2aClientService(
                restClientBuilder.build(), properties,
                circuitBreakerRegistry, meterRegistry, objectMapper);
    }

    private A2aMessage 创建测试消息() {
        return new A2aMessage("msg-1", A2aRole.USER,
                List.of(new A2aPart.Text("hello", null)), null, null, null);
    }

    // ── 熔断器 ──

    @Test
    void sendMessage_熔断中返回FAILED状态Task() {
        when(circuitBreakerRegistry.isCallPermitted("http://remote:8080")).thenReturn(false);

        A2aTask task = clientService.sendMessage("http://remote:8080", 创建测试消息());

        assertThat(task.status().state()).isEqualTo(A2aTaskState.FAILED);
    }

    // ── JSON-RPC 调用成功 ──

    @Test
    void sendMessage_JsonRpc成功返回Task() throws Exception {
        when(circuitBreakerRegistry.isCallPermitted("http://remote:8080")).thenReturn(true);

        // 构建 JSON-RPC 响应
        var responseTask = new A2aTask("task-1", "ctx-1",
                new A2aTaskStatus(A2aTaskState.COMPLETED, null, Instant.now().toString()),
                null, null, null);
        var rpcResponse = JsonRpcMessage.response(1L, responseTask);
        String responseJson = objectMapper.writeValueAsString(rpcResponse);

        mockServer.expect(requestTo("http://remote:8080/api/a2a"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        A2aTask task = clientService.sendMessage("http://remote:8080", 创建测试消息());

        assertThat(task.id()).isEqualTo("task-1");
        assertThat(task.status().state()).isEqualTo(A2aTaskState.COMPLETED);
        mockServer.verify();
    }

    // ── JSON-RPC 返回空结果，降级 REST ──

    @Test
    void sendMessage_JsonRpc返回空结果_降级REST成功() throws Exception {
        when(circuitBreakerRegistry.isCallPermitted("http://remote:8080")).thenReturn(true);

        // JSON-RPC 返回空 result
        var rpcResponse = JsonRpcMessage.response(1L, null);
        String rpcJson = objectMapper.writeValueAsString(rpcResponse);

        // REST 返回正常 Task
        var restTask = new A2aTask("task-2", "ctx-2",
                new A2aTaskStatus(A2aTaskState.COMPLETED, null, Instant.now().toString()),
                null, null, null);
        String restJson = objectMapper.writeValueAsString(restTask);

        mockServer.expect(requestTo("http://remote:8080/api/a2a"))
                .andRespond(withSuccess(rpcJson, MediaType.APPLICATION_JSON));
        mockServer.expect(requestTo("http://remote:8080/api/a2a/message/send"))
                .andRespond(withSuccess(restJson, MediaType.APPLICATION_JSON));

        A2aTask task = clientService.sendMessage("http://remote:8080", 创建测试消息());

        assertThat(task.id()).isEqualTo("task-2");
        mockServer.verify();
    }

    // ── Agent 发现 ──

    @Test
    void discoverAgent_成功返回AgentCard() throws Exception {
        var card = new A2aAgentCard("TestAgent", "测试 Agent", "http://remote:8080",
                "1.0.0", "0.2.5", List.of(),
                new A2aAgentCapabilities(true),
                List.of("text"), List.of("text"), null);
        String cardJson = objectMapper.writeValueAsString(card);

        mockServer.expect(requestTo("http://remote:8080/.well-known/agent.json"))
                .andRespond(withSuccess(cardJson, MediaType.APPLICATION_JSON));

        var result = clientService.discoverAgent("http://remote:8080");

        assertThat(result).isPresent();
        assertThat(result.get().name()).isEqualTo("TestAgent");
        mockServer.verify();
    }

    // ── JSON-RPC 响应含多态 A2aPart 的反序列化 ──

    @Test
    void sendMessage_JsonRpc响应含Text_Part正确反序列化() throws Exception {
        when(circuitBreakerRegistry.isCallPermitted("http://remote:8080")).thenReturn(true);

        // 构建包含 A2aPart.Text 的完整 Task 响应
        var statusMsg = new A2aMessage("smsg-1", A2aRole.AGENT,
                List.of(new A2aPart.Text("处理完成", null)), "task-3", "ctx-3", null);
        var artifact = new A2aArtifact("art-1",
                List.of(new A2aPart.Text("结果内容", null)), "结果", null);
        var responseTask = new A2aTask("task-3", "ctx-3",
                new A2aTaskStatus(A2aTaskState.COMPLETED, statusMsg, Instant.now().toString()),
                null, List.of(artifact), null);

        var rpcResponse = JsonRpcMessage.response(1L, responseTask);
        String responseJson = objectMapper.writeValueAsString(rpcResponse);

        mockServer.expect(requestTo("http://remote:8080/api/a2a"))
                .andRespond(withSuccess(responseJson, MediaType.APPLICATION_JSON));

        A2aTask task = clientService.sendMessage("http://remote:8080", 创建测试消息());

        assertThat(task.id()).isEqualTo("task-3");
        assertThat(task.artifacts()).hasSize(1);
        assertThat(task.artifacts().getFirst().parts().getFirst()).isInstanceOf(A2aPart.Text.class);
        mockServer.verify();
    }
}
