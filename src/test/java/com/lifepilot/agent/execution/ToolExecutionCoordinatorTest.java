package com.lifepilot.agent.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.agent.AgentToolProvider;
import com.lifepilot.agent.CancellationToken;
import com.lifepilot.agent.context.AgentLoopContext;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.model.ReactAgentState;
import com.lifepilot.agent.model.ReactStep;
import com.lifepilot.conversation.transcript.TranscriptStore;
import com.lifepilot.memory.procedural.IntentMatcher;
import com.lifepilot.memory.procedural.ProcedureTemplate;
import com.lifepilot.memory.procedural.ProceduralMemory;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.model.ToolSchedulingMode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ToolExecutionCoordinator 单元测试。
 *
 * @author zsg
 * @since 2026-03-25
 */
class ToolExecutionCoordinatorTest {

    @Test
    void 工具返回错误Envelope时应标记为失败() {
        AgentToolProvider agentToolProvider = mock(AgentToolProvider.class);
        TranscriptStore transcriptStore = mock(TranscriptStore.class);
        when(agentToolProvider.resolveToolDisplayName("code.execute")).thenReturn("执行代码");

        var coordinator = new ToolExecutionCoordinator(
                agentToolProvider,
                new ObjectMapper(),
                null,
                transcriptStore,
                null,
                null,
                null
        );

        var budget = Budget.builder()
                .maxTokens(4096)
                .tokensUsed(0)
                .tokensReserved(0)
                .maxSteps(20)
                .stepsUsed(0)
                .maxDuration(Duration.ofMinutes(5))
                .elapsed(Duration.ZERO)
                .build();
        var request = new AgentRequest("执行测试代码", "session-1", "web", null, null,
                budget, null, 0, null, null, null, null);
        ReactAgentState state = ReactAgentState.init(request, budget);

        ToolCallback callback = new ToolCallback() {
            private final ToolDefinition definition = DefaultToolDefinition.builder()
                    .name("code.execute")
                    .description("执行代码")
                    .inputSchema("{}")
                    .build();

            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return "{\"error\":\"代码执行失败: exitCode=9009\",\"status\":\"ERROR\"}";
            }
        };

        var toolCall = new AssistantMessage.ToolCall(
                "call-1",
                "function",
                "code.execute",
                "{\"language\":\"python\",\"code\":\"print('Hello')\"}"
        );

        ReactAgentState result = coordinator.execute(
                state,
                toolCall,
                List.of(callback),
                null,
                new CancellationToken(),
                new AgentLoopContext(),
                (currentState, step, loopContext) -> currentState.appendStep(step)
        );

        assertThat(result.steps()).hasSize(2);
        assertThat(result.steps().get(1)).isInstanceOf(ReactStep.Observation.class);
        var observation = (ReactStep.Observation) result.steps().get(1);
        assertThat(observation.success()).isFalse();
        assertThat(observation.output()).contains("exitCode=9009");

        verify(transcriptStore).appendToolResult(
                eq("session-1"),
                nullable(String.class),
                eq(result.traceId()),
                eq("code.execute"),
                eq("call-1"),
                eq(false),
                eq("{\"error\":\"代码执行失败: exitCode=9009\",\"status\":\"ERROR\"}"),
                nullable(String.class),
                eq(true),
                eq(false),
                nullable(java.time.Instant.class)
        );
    }

    @Test
    void 记录程序记忆时不应把大段JSON正文直接送入意图匹配() throws InterruptedException {
        AgentToolProvider agentToolProvider = mock(AgentToolProvider.class);
        when(agentToolProvider.resolveToolDisplayName("file.write")).thenReturn("写入文件");
        ProceduralMemory proceduralMemory = mock(ProceduralMemory.class);
        IntentMatcher intentMatcher = mock(IntentMatcher.class);
        when(intentMatcher.match(argThat(query ->
                query.contains("file write")
                        && query.contains("path")
                        && query.contains("D:\\WorkSpace\\Project\\News\\AI_News_2026-03-24.md")
                        && !query.contains("AI 资讯汇总")
                        && !query.contains("```")
                        && !query.contains("`D:\\WorkSpace"))))
                .thenReturn(Optional.of(new IntentMatcher.TemplateMatch(
                        new ProcedureTemplate(
                                "tpl-file-write",
                                "写入 Markdown 文件",
                                "写文件",
                                "写入 Markdown 文件",
                                List.of(),
                                java.util.Map.of(),
                                0.95f,
                                3,
                                Instant.now(),
                                List.of("trace-1"),
                                Instant.now(),
                                Instant.now()
                        ),
                        0.92f
                )));

        var coordinator = new ToolExecutionCoordinator(
                agentToolProvider,
                new ObjectMapper(),
                null,
                null,
                null,
                proceduralMemory,
                intentMatcher
        );

        ToolCallback callback = new ToolCallback() {
            private final ToolDefinition definition = DefaultToolDefinition.builder()
                    .name("file.write")
                    .description("写入文件")
                    .inputSchema("{}")
                    .build();

            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return "{\"path\":\"D:\\\\WorkSpace\\\\Project\\\\News\\\\AI_News_2026-03-24.md\",\"mode\":\"write\"}";
            }
        };

        var budget = Budget.builder()
                .maxTokens(4096)
                .tokensUsed(0)
                .tokensReserved(0)
                .maxSteps(20)
                .stepsUsed(0)
                .maxDuration(Duration.ofMinutes(5))
                .elapsed(Duration.ZERO)
                .build();
        var request = new AgentRequest("创建 Markdown 文件", "session-1", "web", null, null,
                budget, null, 0, null, null, null, null);
        ReactAgentState state = ReactAgentState.init(request, budget);

        var toolCall = new AssistantMessage.ToolCall(
                "call-2",
                "function",
                "file.write",
                """
                {"content":"# AI 资讯汇总 - 2026 年 3 月 24 日\\n`D:\\\\WorkSpace\\\\Project\\\\News`","path":"D:\\\\WorkSpace\\\\Project\\\\News\\\\AI_News_2026-03-24.md"}
                """
        );

        coordinator.execute(
                state,
                toolCall,
                List.of(callback),
                null,
                new CancellationToken(),
                new AgentLoopContext(),
                (currentState, step, loopContext) -> currentState.appendStep(step)
        );

        // IntentMatcher 已异步化，等待虚拟线程完成
        Thread.sleep(200);
        verify(intentMatcher).match(argThat(query ->
                query.contains("file write")
                        && query.contains("path")
                        && query.contains("D:\\WorkSpace\\Project\\News\\AI_News_2026-03-24.md")
                        && !query.contains("AI 资讯汇总")
                        && !query.contains("`")));
        verify(proceduralMemory).recordExecution("tpl-file-write", true);
        verify(proceduralMemory, never()).recordExecution("tpl-file-write", false);
    }

    @Test
    void 模型返回原始点号工具ID时也应命中下划线别名回调() {
        AgentToolProvider agentToolProvider = mock(AgentToolProvider.class);
        when(agentToolProvider.resolveCanonicalToolId("web_search")).thenReturn("web.search");
        when(agentToolProvider.resolveCanonicalToolId("web.search")).thenReturn("web.search");
        when(agentToolProvider.resolveToolDisplayName("web.search")).thenReturn("Web 搜索");
        when(agentToolProvider.resolveToolRiskLevel("web.search")).thenReturn(RiskLevel.LOW);
        when(agentToolProvider.resolveSchedulingHint(eq("web.search"), anyString()))
                .thenReturn(AgentToolProvider.ToolSchedulingHint.parallelSafe());

        var coordinator = new ToolExecutionCoordinator(
                agentToolProvider,
                new ObjectMapper(),
                null,
                null,
                null,
                null,
                null
        );

        ToolCallback callback = new ToolCallback() {
            private final ToolDefinition definition = DefaultToolDefinition.builder()
                    .name("web_search")
                    .description("Web 搜索")
                    .inputSchema("{}")
                    .build();

            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return "{\"status\":\"SUCCESS\",\"data\":{\"hits\":1}}";
            }
        };

        ReactAgentState result = coordinator.execute(
                baseState(),
                new AssistantMessage.ToolCall(
                        "call-search",
                        "function",
                        "web.search",
                        "{\"query\":\"昨天的AI资讯\"}"
                ),
                List.of(callback),
                null,
                new CancellationToken(),
                new AgentLoopContext(),
                (currentState, step, loopContext) -> currentState.appendStep(step)
        );

        assertThat(result.steps()).hasSize(2);
        assertThat(result.steps().get(0)).isEqualTo(
                new ReactStep.ToolCall("web.search", "Web 搜索", "{\"query\":\"昨天的AI资讯\"}", 0, "call-search")
        );
        assertThat(result.steps().get(1)).isInstanceOf(ReactStep.Observation.class);
        var observation = (ReactStep.Observation) result.steps().get(1);
        assertThat(observation.toolId()).isEqualTo("web.search");
        assertThat(observation.toolName()).isEqualTo("Web 搜索");
        assertThat(observation.success()).isTrue();
        assertThat(observation.output()).isEqualTo("{\"status\":\"SUCCESS\",\"data\":{\"hits\":1}}");
        assertThat(observation.callId()).isEqualTo("call-search");
    }

    @Test
    void 并行安全工具应并发执行且按原始顺序回放() {
        AgentToolProvider agentToolProvider = mock(AgentToolProvider.class);
        when(agentToolProvider.resolveToolRiskLevel(anyString())).thenReturn(RiskLevel.LOW);
        when(agentToolProvider.resolveSchedulingHint(eq("tool.alpha"), anyString()))
                .thenReturn(AgentToolProvider.ToolSchedulingHint.parallelSafe());
        when(agentToolProvider.resolveSchedulingHint(eq("tool.beta"), anyString()))
                .thenReturn(AgentToolProvider.ToolSchedulingHint.parallelSafe());

        var coordinator = new ToolExecutionCoordinator(
                agentToolProvider,
                new ObjectMapper(),
                null,
                null,
                null,
                null,
                null,
                4
        );

        AtomicInteger activeCalls = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        ToolCallback alpha = callback("tool.alpha", 250, activeCalls, maxConcurrent, "{\"tool\":\"alpha\"}");
        ToolCallback beta = callback("tool.beta", 80, activeCalls, maxConcurrent, "{\"tool\":\"beta\"}");

        ReactAgentState result = coordinator.executeBatch(
                baseState(),
                List.of(
                        new AssistantMessage.ToolCall("call-a", "function", "tool.alpha", "{\"q\":1}"),
                        new AssistantMessage.ToolCall("call-b", "function", "tool.beta", "{\"q\":2}")
                ),
                List.of(alpha, beta),
                null,
                new CancellationToken(),
                new AgentLoopContext(),
                (currentState, step, loopContext) -> currentState.appendStep(step)
        );

        assertThat(maxConcurrent.get()).isGreaterThanOrEqualTo(2);
        assertThat(result.steps()).hasSize(4);
        assertThat(result.steps().get(0)).isEqualTo(new ReactStep.ToolCall("tool.alpha", null, "{\"q\":1}", 0, "call-a"));
        assertThat(result.steps().get(1)).isEqualTo(new ReactStep.ToolCall("tool.beta", null, "{\"q\":2}", 0, "call-b"));
        assertThat(result.steps().get(2)).isInstanceOf(ReactStep.Observation.class);
        assertThat(result.steps().get(3)).isInstanceOf(ReactStep.Observation.class);
        var firstObservation = (ReactStep.Observation) result.steps().get(2);
        var secondObservation = (ReactStep.Observation) result.steps().get(3);
        assertThat(firstObservation.toolId()).isEqualTo("tool.alpha");
        assertThat(firstObservation.callId()).isEqualTo("call-a");
        assertThat(firstObservation.success()).isTrue();
        assertThat(firstObservation.output()).isEqualTo("{\"tool\":\"alpha\"}");
        assertThat(secondObservation.toolId()).isEqualTo("tool.beta");
        assertThat(secondObservation.callId()).isEqualTo("call-b");
        assertThat(secondObservation.success()).isTrue();
        assertThat(secondObservation.output()).isEqualTo("{\"tool\":\"beta\"}");
    }

    @Test
    void 同资源ResourceSerialized工具应串行执行() {
        AgentToolProvider agentToolProvider = mock(AgentToolProvider.class);
        when(agentToolProvider.resolveToolRiskLevel(anyString())).thenReturn(RiskLevel.LOW);
        when(agentToolProvider.resolveSchedulingHint(anyString(), anyString()))
                .thenReturn(AgentToolProvider.ToolSchedulingHint.resourceSerialized(
                        List.of("tree:/tmp/shared.txt")
                ));

        var coordinator = new ToolExecutionCoordinator(
                agentToolProvider,
                new ObjectMapper(),
                null,
                null,
                null,
                null,
                null,
                4
        );

        AtomicInteger activeCalls = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        ToolCallback first = callback("tool.write.one", 120, activeCalls, maxConcurrent, "{\"ok\":1}");
        ToolCallback second = callback("tool.write.two", 120, activeCalls, maxConcurrent, "{\"ok\":2}");

        coordinator.executeBatch(
                baseState(),
                List.of(
                        new AssistantMessage.ToolCall("call-1", "function", "tool.write.one", "{\"path\":\"a\"}"),
                        new AssistantMessage.ToolCall("call-2", "function", "tool.write.two", "{\"path\":\"b\"}")
                ),
                List.of(first, second),
                null,
                new CancellationToken(),
                new AgentLoopContext(),
                (currentState, step, loopContext) -> currentState.appendStep(step)
        );

        assertThat(maxConcurrent.get()).isEqualTo(1);
    }

    @Test
    void 不同资源ResourceSerialized工具应允许并发执行() {
        AgentToolProvider agentToolProvider = mock(AgentToolProvider.class);
        when(agentToolProvider.resolveToolRiskLevel(anyString())).thenReturn(RiskLevel.LOW);
        when(agentToolProvider.resolveSchedulingHint(eq("tool.write.left"), anyString()))
                .thenReturn(AgentToolProvider.ToolSchedulingHint.resourceSerialized(
                        List.of("tree:/tmp/left.txt")
                ));
        when(agentToolProvider.resolveSchedulingHint(eq("tool.write.right"), anyString()))
                .thenReturn(AgentToolProvider.ToolSchedulingHint.resourceSerialized(
                        List.of("tree:/tmp/right.txt")
                ));

        var coordinator = new ToolExecutionCoordinator(
                agentToolProvider,
                new ObjectMapper(),
                null,
                null,
                null,
                null,
                null,
                4
        );

        AtomicInteger activeCalls = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        ToolCallback left = callback("tool.write.left", 120, activeCalls, maxConcurrent, "{\"ok\":\"left\"}");
        ToolCallback right = callback("tool.write.right", 120, activeCalls, maxConcurrent, "{\"ok\":\"right\"}");

        coordinator.executeBatch(
                baseState(),
                List.of(
                        new AssistantMessage.ToolCall("call-l", "function", "tool.write.left", "{\"path\":\"left\"}"),
                        new AssistantMessage.ToolCall("call-r", "function", "tool.write.right", "{\"path\":\"right\"}")
                ),
                List.of(left, right),
                null,
                new CancellationToken(),
                new AgentLoopContext(),
                (currentState, step, loopContext) -> currentState.appendStep(step)
        );

        assertThat(maxConcurrent.get()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void 同文件读写工具应串行执行() {
        AgentToolProvider agentToolProvider = mock(AgentToolProvider.class);
        when(agentToolProvider.resolveToolRiskLevel(anyString())).thenReturn(RiskLevel.LOW);
        when(agentToolProvider.resolveSchedulingHint(eq("tool.file.read"), anyString()))
                .thenReturn(AgentToolProvider.ToolSchedulingHint.resourceSerialized(
                        List.of("tree:/tmp/project/readme.md")
                ));
        when(agentToolProvider.resolveSchedulingHint(eq("tool.file.write"), anyString()))
                .thenReturn(AgentToolProvider.ToolSchedulingHint.resourceSerialized(
                        List.of("tree:/tmp/project/readme.md")
                ));

        var coordinator = new ToolExecutionCoordinator(
                agentToolProvider,
                new ObjectMapper(),
                null,
                null,
                null,
                null,
                null,
                4
        );

        AtomicInteger activeCalls = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        ToolCallback read = callback("tool.file.read", 120, activeCalls, maxConcurrent, "{\"ok\":\"read\"}");
        ToolCallback write = callback("tool.file.write", 120, activeCalls, maxConcurrent, "{\"ok\":\"write\"}");

        coordinator.executeBatch(
                baseState(),
                List.of(
                        new AssistantMessage.ToolCall("call-read", "function", "tool.file.read", "{\"path\":\"/tmp/project/readme.md\"}"),
                        new AssistantMessage.ToolCall("call-write", "function", "tool.file.write", "{\"path\":\"/tmp/project/readme.md\"}")
                ),
                List.of(read, write),
                null,
                new CancellationToken(),
                new AgentLoopContext(),
                (currentState, step, loopContext) -> currentState.appendStep(step)
        );

        assertThat(maxConcurrent.get()).isEqualTo(1);
    }

    @Test
    void 目录树扫描与子文件写入应串行执行() {
        AgentToolProvider agentToolProvider = mock(AgentToolProvider.class);
        when(agentToolProvider.resolveToolRiskLevel(anyString())).thenReturn(RiskLevel.LOW);
        when(agentToolProvider.resolveSchedulingHint(eq("tool.file.search"), anyString()))
                .thenReturn(AgentToolProvider.ToolSchedulingHint.resourceSerialized(
                        List.of("tree:/tmp/project")
                ));
        when(agentToolProvider.resolveSchedulingHint(eq("tool.file.write"), anyString()))
                .thenReturn(AgentToolProvider.ToolSchedulingHint.resourceSerialized(
                        List.of("tree:/tmp/project/src/App.java")
                ));

        var coordinator = new ToolExecutionCoordinator(
                agentToolProvider,
                new ObjectMapper(),
                null,
                null,
                null,
                null,
                null,
                4
        );

        AtomicInteger activeCalls = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        ToolCallback search = callback("tool.file.search", 120, activeCalls, maxConcurrent, "{\"ok\":\"search\"}");
        ToolCallback write = callback("tool.file.write", 120, activeCalls, maxConcurrent, "{\"ok\":\"write\"}");

        coordinator.executeBatch(
                baseState(),
                List.of(
                        new AssistantMessage.ToolCall("call-search", "function", "tool.file.search", "{\"path\":\"/tmp/project\"}"),
                        new AssistantMessage.ToolCall("call-write", "function", "tool.file.write", "{\"path\":\"/tmp/project/src/App.java\"}")
                ),
                List.of(search, write),
                null,
                new CancellationToken(),
                new AgentLoopContext(),
                (currentState, step, loopContext) -> currentState.appendStep(step)
        );

        assertThat(maxConcurrent.get()).isEqualTo(1);
    }

    @Test
    void 不同文件资源应允许并发执行() {
        AgentToolProvider agentToolProvider = mock(AgentToolProvider.class);
        when(agentToolProvider.resolveToolRiskLevel(anyString())).thenReturn(RiskLevel.LOW);
        when(agentToolProvider.resolveSchedulingHint(eq("tool.file.read"), anyString()))
                .thenReturn(AgentToolProvider.ToolSchedulingHint.resourceSerialized(
                        List.of("tree:/tmp/project/a.txt")
                ));
        when(agentToolProvider.resolveSchedulingHint(eq("tool.file.write"), anyString()))
                .thenReturn(AgentToolProvider.ToolSchedulingHint.resourceSerialized(
                        List.of("tree:/tmp/project/b.txt")
                ));

        var coordinator = new ToolExecutionCoordinator(
                agentToolProvider,
                new ObjectMapper(),
                null,
                null,
                null,
                null,
                null,
                4
        );

        AtomicInteger activeCalls = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        ToolCallback read = callback("tool.file.read", 120, activeCalls, maxConcurrent, "{\"ok\":\"read\"}");
        ToolCallback write = callback("tool.file.write", 120, activeCalls, maxConcurrent, "{\"ok\":\"write\"}");

        coordinator.executeBatch(
                baseState(),
                List.of(
                        new AssistantMessage.ToolCall("call-read", "function", "tool.file.read", "{\"path\":\"/tmp/project/a.txt\"}"),
                        new AssistantMessage.ToolCall("call-write", "function", "tool.file.write", "{\"path\":\"/tmp/project/b.txt\"}")
                ),
                List.of(read, write),
                null,
                new CancellationToken(),
                new AgentLoopContext(),
                (currentState, step, loopContext) -> currentState.appendStep(step)
        );

        assertThat(maxConcurrent.get()).isGreaterThanOrEqualTo(2);
    }

    private ReactAgentState baseState() {
        var budget = Budget.builder()
                .maxTokens(4096)
                .tokensUsed(0)
                .tokensReserved(0)
                .maxSteps(20)
                .stepsUsed(0)
                .maxDuration(Duration.ofMinutes(5))
                .elapsed(Duration.ZERO)
                .build();
        var request = new AgentRequest("测试工具批次执行", "session-batch", "web", null, null,
                budget, null, 0, null, null, null, null);
        return ReactAgentState.init(request, budget);
    }

    private ToolCallback callback(String toolName,
                                  long sleepMillis,
                                  AtomicInteger activeCalls,
                                  AtomicInteger maxConcurrent,
                                  String output) {
        return new ToolCallback() {
            private final ToolDefinition definition = DefaultToolDefinition.builder()
                    .name(toolName)
                    .description(toolName)
                    .inputSchema("{}")
                    .build();

            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                int current = activeCalls.incrementAndGet();
                maxConcurrent.accumulateAndGet(current, Math::max);
                try {
                    Thread.sleep(sleepMillis);
                    return output;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "{\"error\":\"interrupted\",\"status\":\"ERROR\"}";
                } finally {
                    activeCalls.decrementAndGet();
                }
            }
        };
    }

    @Test
    void executeBatch_reasoningContent_透传到单条_ToolCall_step() {
        AgentToolProvider agentToolProvider = mock(AgentToolProvider.class);
        when(agentToolProvider.resolveToolRiskLevel(anyString())).thenReturn(RiskLevel.LOW);
        when(agentToolProvider.resolveSchedulingHint(anyString(), anyString()))
                .thenReturn(AgentToolProvider.ToolSchedulingHint.parallelSafe());

        var coordinator = new ToolExecutionCoordinator(
                agentToolProvider,
                new ObjectMapper(),
                null, null, null, null, null,
                4
        );

        AtomicInteger activeCalls = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        ToolCallback cb = callback("tool.search", 10, activeCalls, maxConcurrent, "{\"ok\":1}");

        ReactAgentState result = coordinator.executeBatch(
                baseState(),
                List.of(new AssistantMessage.ToolCall("call-x", "function", "tool.search", "{}")),
                List.of(cb),
                null,
                new CancellationToken(),
                new AgentLoopContext(),
                (currentState, step, loopContext) -> currentState.appendStep(step),
                "我先调一次搜索"
        );

        assertThat(result.steps()).hasSize(2);
        var toolCallStep = (ReactStep.ToolCall) result.steps().get(0);
        assertThat(toolCallStep.toolId()).isEqualTo("tool.search");
        assertThat(toolCallStep.callId()).isEqualTo("call-x");
        assertThat(toolCallStep.reasoningContent()).isEqualTo("我先调一次搜索");
    }

    @Test
    void executeBatch_reasoningContent_并行_ToolCall_共享同一段_reasoning() {
        AgentToolProvider agentToolProvider = mock(AgentToolProvider.class);
        when(agentToolProvider.resolveToolRiskLevel(anyString())).thenReturn(RiskLevel.LOW);
        when(agentToolProvider.resolveSchedulingHint(anyString(), anyString()))
                .thenReturn(AgentToolProvider.ToolSchedulingHint.parallelSafe());

        var coordinator = new ToolExecutionCoordinator(
                agentToolProvider,
                new ObjectMapper(),
                null, null, null, null, null,
                4
        );

        AtomicInteger activeCalls = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        ToolCallback alpha = callback("tool.alpha", 10, activeCalls, maxConcurrent, "{\"ok\":1}");
        ToolCallback beta = callback("tool.beta", 10, activeCalls, maxConcurrent, "{\"ok\":2}");

        ReactAgentState result = coordinator.executeBatch(
                baseState(),
                List.of(
                        new AssistantMessage.ToolCall("call-a", "function", "tool.alpha", "{}"),
                        new AssistantMessage.ToolCall("call-b", "function", "tool.beta", "{}")
                ),
                List.of(alpha, beta),
                null,
                new CancellationToken(),
                new AgentLoopContext(),
                (currentState, step, loopContext) -> currentState.appendStep(step),
                "并行调两个工具"
        );

        // 同一组并行 tool_calls 来自单次 LLM 响应，共享同一段 reasoning_content
        var first = (ReactStep.ToolCall) result.steps().get(0);
        var second = (ReactStep.ToolCall) result.steps().get(1);
        assertThat(first.reasoningContent()).isEqualTo("并行调两个工具");
        assertThat(second.reasoningContent()).isEqualTo("并行调两个工具");
    }

    @Test
    void executeBatch_reasoningContent_为_null_时_ToolCall_step_reasoning_也为_null() {
        AgentToolProvider agentToolProvider = mock(AgentToolProvider.class);
        when(agentToolProvider.resolveToolRiskLevel(anyString())).thenReturn(RiskLevel.LOW);
        when(agentToolProvider.resolveSchedulingHint(anyString(), anyString()))
                .thenReturn(AgentToolProvider.ToolSchedulingHint.parallelSafe());

        var coordinator = new ToolExecutionCoordinator(
                agentToolProvider,
                new ObjectMapper(),
                null, null, null, null, null,
                4
        );

        AtomicInteger activeCalls = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        ToolCallback cb = callback("tool.x", 10, activeCalls, maxConcurrent, "{}");

        ReactAgentState result = coordinator.executeBatch(
                baseState(),
                List.of(new AssistantMessage.ToolCall("c1", "function", "tool.x", "{}")),
                List.of(cb),
                null,
                new CancellationToken(),
                new AgentLoopContext(),
                (currentState, step, loopContext) -> currentState.appendStep(step),
                null
        );

        var toolCallStep = (ReactStep.ToolCall) result.steps().get(0);
        // record canonical constructor 把空串规整为 null；显式传 null 也是 null
        assertThat(toolCallStep.reasoningContent()).isNull();
    }
}
