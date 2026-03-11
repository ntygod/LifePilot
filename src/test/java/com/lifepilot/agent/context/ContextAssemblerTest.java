package com.lifepilot.agent.context;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.model.AgentPhase;
import com.lifepilot.agent.model.AgentState;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.retrieval.RetrievalResult;
import com.lifepilot.memory.retrieval.RetrievalWeights;
import com.lifepilot.memory.working.*;
import com.lifepilot.observability.redactor.DataRedactor;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ContextAssembler 完整版单元测试。
 *
 * @author zsg
 * @since 2026-02-25
 */
@ExtendWith(MockitoExtension.class)
class ContextAssemblerTest {

    @Mock HybridRetriever hybridRetriever;
    @Mock WorkingMemory workingMemory;
    @Mock TokenBudgetAllocator tokenBudgetAllocator;
    @Mock DataRedactor dataRedactor;
    @Mock PromptRegistry promptRegistry;

    private AgentConfigProperties config;
    private ContextAssembler assembler;

    @BeforeEach
    void setUp() {
        config = new AgentConfigProperties();
        config.getContext().setMaxContextTokens(32000);
        var strategy = new DefaultMemoryRetrievalStrategy();
        assembler = new ContextAssembler(config, hybridRetriever,
                workingMemory, tokenBudgetAllocator, strategy, dataRedactor, promptRegistry);
        lenient().when(promptRegistry.render("agent/role-definition")).thenReturn("你是知微");
        lenient().when(promptRegistry.render(argThat(key -> key != null && key.startsWith("agent/")), anyMap()))
                .thenAnswer(invocation -> "阶段提示词: " + invocation.getArgument(0));
    }

    // --- 辅助方法 ---

    private AgentState createState(AgentPhase phase, String goal) {
        return AgentState.builder()
                .traceId("trace-1")
                .sessionId("session-1")
                .goal(goal)
                .phase(phase)
                .channel("cli")
                .steps(List.of())
                .stepCount(0)
                .plan(null)
                .planStepIndex(0)
                .revisionCount(0)
                .shortTermMemory(List.of())
                .mentionedEntities(List.of())
                .budget(Budget.defaultBudget())
                .parentTraceId(null)
                .depth(0)
                .done(false)
                .finalOutput(null)
                .terminationReason(null)
                .build();
    }

    private RetrievalResult createResult(String entityId, String name, float score) {
        var breakdown = new RetrievalResult.ScoreBreakdown(
                0.5f, 0.25f, 0.3f, 0.15f, 0.2f, 0.1f, 0.05f, 0.02f);
        return new RetrievalResult(entityId, "PERSON", name,
                name + " 的描述", score, breakdown, "vector+fts",
                Instant.now(), 0.5f);
    }

    private BudgetAllocation createAllocation() {
        // userProfile=500, currentSession=12000, crossSession=1000,
        // knowledgeEntity=4000, procedural=300, knowledgeBase=200,
        // systemPrompt=3200, userMessage=4800, total=32000
        // 六区域总和=18000, 固定区域=8000, 合计=26000 <= 32000
        return new BudgetAllocation(500, 12000, 1000, 4000, 300, 200, 3200, 4800, 32000);
    }

    private void setupDefaultMocks(AgentState state) {
        var results = List.of(
                createResult("e1", "张总", 0.92f),
                createResult("e2", "Alpha项目", 0.85f));
        when(hybridRetriever.retrieve(eq(state.goal()), anyInt(), any(RetrievalWeights.class)))
                .thenReturn(results);
        when(workingMemory.getContext(state.sessionId()))
                .thenReturn(List.of(
                        ConversationSlot.userMessage("你好", 5),
                        ConversationSlot.assistantMessage("你好，有什么可以帮你？", 15)));
        when(tokenBudgetAllocator.allocate(eq(32000), anyInt(), anyFloat(), anyBoolean()))
                .thenReturn(createAllocation());
    }

    // --- 核心逻辑测试 (6.1) ---

    @Test
    void 使用goal作为检索查询文本() {
        var state = createState(AgentPhase.UNDERSTANDING, "帮我查一下张总的日程");
        setupDefaultMocks(state);

        assembler.assemble(state);

        verify(hybridRetriever).retrieve(eq("帮我查一下张总的日程"), anyInt(), any());
    }

    @Test
    void 使用maxContextTokens作为窗口大小() {
        var state = createState(AgentPhase.UNDERSTANDING, "测试查询");
        setupDefaultMocks(state);

        assembler.assemble(state);

        verify(tokenBudgetAllocator).allocate(eq(32000), anyInt(), anyFloat(), anyBoolean());
    }

    @Test
    void conversationTurns等于ConversationSlot数量() {
        var state = createState(AgentPhase.UNDERSTANDING, "测试查询");
        var results = List.of(createResult("e1", "测试", 0.8f));
        when(hybridRetriever.retrieve(anyString(), anyInt(), any())).thenReturn(results);
        // 3 个 ConversationSlot + 1 个 ToolResultSlot
        when(workingMemory.getContext(anyString())).thenReturn(List.of(
                ConversationSlot.userMessage("a", 5),
                ConversationSlot.assistantMessage("b", 5),
                ConversationSlot.userMessage("c", 5),
                new ToolResultSlot("tool1", "query", "结果", 10, 0.5f, Instant.now())));
        when(tokenBudgetAllocator.allocate(anyInt(), anyInt(), anyFloat(), anyBoolean()))
                .thenReturn(createAllocation());

        assembler.assemble(state);

        // conversationTurns 应为 3（只计 ConversationSlot）
        verify(tokenBudgetAllocator).allocate(eq(32000), eq(3), anyFloat(), anyBoolean());
    }

    @Test
    void topRetrievalScore等于最高fusedScore() {
        var state = createState(AgentPhase.UNDERSTANDING, "测试查询");
        var results = List.of(
                createResult("e1", "高分", 0.95f),
                createResult("e2", "低分", 0.60f));
        when(hybridRetriever.retrieve(anyString(), anyInt(), any())).thenReturn(results);
        when(workingMemory.getContext(anyString())).thenReturn(List.of());
        when(tokenBudgetAllocator.allocate(anyInt(), anyInt(), anyFloat(), anyBoolean()))
                .thenReturn(createAllocation());

        var context = assembler.assemble(state);

        assertEquals(0.95f, context.topRetrievalScore(), 0.001f);
    }

    // --- 降级容错测试 (6.2) ---

    @Test
    void 不存在的sessionId使用空上下文() {
        var state = createState(AgentPhase.UNDERSTANDING, "测试查询");
        when(hybridRetriever.retrieve(anyString(), anyInt(), any()))
                .thenReturn(List.of(createResult("e1", "测试", 0.8f)));
        when(workingMemory.getContext("session-1")).thenReturn(List.of());
        when(tokenBudgetAllocator.allocate(anyInt(), anyInt(), anyFloat(), anyBoolean()))
                .thenReturn(createAllocation());

        var context = assembler.assemble(state);

        assertNotNull(context);
        assertEquals(0, context.workingMemoryTokens());
    }

    @Test
    void HybridRetriever异常时降级为空记忆() {
        var state = createState(AgentPhase.UNDERSTANDING, "测试查询");
        when(hybridRetriever.retrieve(anyString(), anyInt(), any()))
                .thenThrow(new RuntimeException("检索服务不可用"));
        when(workingMemory.getContext(anyString())).thenReturn(List.of());
        when(tokenBudgetAllocator.allocate(anyInt(), anyInt(), anyFloat(), anyBoolean()))
                .thenReturn(createAllocation());

        var context = assembler.assemble(state);

        assertNotNull(context);
        assertTrue(context.retrievedMemories().isEmpty());
        assertEquals(0, context.retrievalCount());
    }

    @Test
    void WorkingMemory异常时降级为空槽位() {
        var state = createState(AgentPhase.UNDERSTANDING, "测试查询");
        when(hybridRetriever.retrieve(anyString(), anyInt(), any()))
                .thenReturn(List.of(createResult("e1", "测试", 0.8f)));
        when(workingMemory.getContext(anyString()))
                .thenThrow(new RuntimeException("工作记忆不可用"));
        when(tokenBudgetAllocator.allocate(anyInt(), anyInt(), anyFloat(), anyBoolean()))
                .thenReturn(createAllocation());

        var context = assembler.assemble(state);

        assertNotNull(context);
        assertEquals(0, context.workingMemoryTokens());
    }

    @Test
    void TokenBudgetAllocator异常时使用静态分配() {
        var state = createState(AgentPhase.UNDERSTANDING, "测试查询");
        when(hybridRetriever.retrieve(anyString(), anyInt(), any()))
                .thenReturn(List.of(createResult("e1", "测试", 0.8f)));
        when(workingMemory.getContext(anyString())).thenReturn(List.of());
        when(tokenBudgetAllocator.allocate(anyInt(), anyInt(), anyFloat(), anyBoolean()))
                .thenThrow(new RuntimeException("分配器不可用"));

        var context = assembler.assemble(state);

        // 应使用静态降级分配，不抛异常
        assertNotNull(context);
        assertNotNull(context.tokenBudget());
    }

    @Test
    void 系统提示词模板异常时使用紧急兜底提示词() {
        var state = createState(AgentPhase.UNDERSTANDING, "测试查询");
        when(hybridRetriever.retrieve(anyString(), anyInt(), any())).thenReturn(List.of());
        when(workingMemory.getContext(anyString())).thenReturn(List.of());
        when(tokenBudgetAllocator.allocate(anyInt(), anyInt(), anyFloat(), anyBoolean()))
                .thenReturn(createAllocation());
        when(promptRegistry.render("agent/role-definition"))
                .thenThrow(new IllegalArgumentException("The template string is not valid."));

        var context = assembler.assemble(state);

        assertNotNull(context);
        assertTrue(context.degraded());
        assertThat(context.systemPrompt()).contains("阶段：意图理解");
        assertThat(context.systemPrompt()).contains("请只输出 JSON 对象");
    }

    @Test
    void buildUserPrompt包含当前时间与时区() {
        var state = createState(AgentPhase.UNDERSTANDING, "测试查询");

        var prompt = assembler.buildUserPrompt(state);

        assertThat(prompt).contains("当前时间:");
        assertThat(prompt).contains(ZoneId.systemDefault().getId());
    }

    @Test
    void buildEnhancedUserPrompt包含当前时间与时区() {
        var state = createState(AgentPhase.UNDERSTANDING, "测试查询");

        var prompt = assembler.buildEnhancedUserPrompt(
                state, List.of(), List.of(), List.of(), List.of(), null);

        assertThat(prompt).contains("当前时间:");
        assertThat(prompt).contains(ZoneId.systemDefault().getId());
    }

    @Test
    void TERMINATED阶段返回空上下文() {
        var state = createState(AgentPhase.TERMINATED, "测试");

        var context = assembler.assemble(state);

        assertNotNull(context);
        assertTrue(context.retrievedMemories().isEmpty());
        assertEquals("", context.systemPrompt());
        // 不应调用任何记忆组件
        verifyNoInteractions(hybridRetriever, workingMemory, tokenBudgetAllocator);
    }
}
