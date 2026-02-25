package com.lifepilot.skill.bridge;

import com.lifepilot.agent.model.AgentState;
import com.lifepilot.skill.activation.SkillLifecycleManager;
import com.lifepilot.skill.event.SkillRegistryEvent;
import com.lifepilot.skill.model.*;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * SkillToToolBridge 单元测试。
 *
 * @author zsg
 * @since 2026-07-28
 */
class SkillToToolBridgeTest {

    private DynamicToolRegistry toolRegistry;
    private SkillLifecycleManager lifecycleManager;
    private SkillToToolBridge bridge;

    @BeforeEach
    void setUp() {
        toolRegistry = mock(DynamicToolRegistry.class);
        lifecycleManager = mock(SkillLifecycleManager.class);
        bridge = new SkillToToolBridge(toolRegistry, lifecycleManager);
    }

    @Test
    void onSkillRegistered_注册BuiltinTool到DynamicToolRegistry() {
        SkillDefinition definition = createTestDefinition("todo");

        bridge.onSkillRegistered(new SkillRegistryEvent.SkillRegistered(definition));

        ArgumentCaptor<ToolContract> captor = ArgumentCaptor.forClass(ToolContract.class);
        verify(toolRegistry).registerBuiltinTool(captor.capture());

        ToolContract registered = captor.getValue();
        assertThat(registered.id()).isEqualTo("skill.todo");
        assertThat(registered.name()).isEqualTo("待办管理");
        assertThat(registered.description()).isEqualTo("管理待办事项");
    }

    @Test
    void onSkillRegistered_工具执行成功时返回ToolResult_success() {
        SkillDefinition definition = createTestDefinition("todo");
        SubAgentResult successResult = SubAgentResult.builder()
                .skillId("todo")
                .success(true)
                .output("待办已创建")
                .tokensUsed(100)
                .stepsExecuted(3)
                .durationMs(500)
                .traceId("trace-123")
                .build();

        when(lifecycleManager.activate(eq("todo"), eq("创建待办"), any(AgentState.class)))
                .thenReturn(successResult);

        bridge.onSkillRegistered(new SkillRegistryEvent.SkillRegistered(definition));

        ArgumentCaptor<ToolContract> captor = ArgumentCaptor.forClass(ToolContract.class);
        verify(toolRegistry).registerBuiltinTool(captor.capture());

        BuiltinTool tool = (BuiltinTool) captor.getValue();
        ToolInput input = new ToolInput("skill.todo",
                Map.of("input", "创建待办"), JsonSchema.empty(), null);
        ToolResult result = tool.execute(input);

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).containsEntry("output", "待办已创建");
        assertThat(result.data()).containsEntry("tokensUsed", 100);
    }

    @Test
    void onSkillRegistered_工具执行失败时返回ToolResult_error() {
        SkillDefinition definition = createTestDefinition("todo");
        SubAgentResult failResult = SubAgentResult.builder()
                .skillId("todo")
                .success(false)
                .output("Skill 并发激活超限")
                .tokensUsed(0)
                .stepsExecuted(0)
                .durationMs(0)
                .traceId("trace-456")
                .build();

        when(lifecycleManager.activate(eq("todo"), eq("创建待办"), any(AgentState.class)))
                .thenReturn(failResult);

        bridge.onSkillRegistered(new SkillRegistryEvent.SkillRegistered(definition));

        ArgumentCaptor<ToolContract> captor = ArgumentCaptor.forClass(ToolContract.class);
        verify(toolRegistry).registerBuiltinTool(captor.capture());

        BuiltinTool tool = (BuiltinTool) captor.getValue();
        ToolInput input = new ToolInput("skill.todo",
                Map.of("input", "创建待办"), JsonSchema.empty(), null);
        ToolResult result = tool.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo("Skill 并发激活超限");
    }

    @Test
    void onSkillRegistered_工具执行异常时返回ToolResult_error() {
        SkillDefinition definition = createTestDefinition("todo");

        when(lifecycleManager.activate(eq("todo"), eq(""), any(AgentState.class)))
                .thenThrow(new RuntimeException("意外错误"));

        bridge.onSkillRegistered(new SkillRegistryEvent.SkillRegistered(definition));

        ArgumentCaptor<ToolContract> captor = ArgumentCaptor.forClass(ToolContract.class);
        verify(toolRegistry).registerBuiltinTool(captor.capture());

        BuiltinTool tool = (BuiltinTool) captor.getValue();
        // 不传 input 参数，默认为空字符串
        ToolInput input = new ToolInput("skill.todo",
                Map.of(), JsonSchema.empty(), null);
        ToolResult result = tool.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("Skill 执行失败");
    }

    @Test
    void onSkillUnregistered_记录WARN日志不抛异常() {
        // 由于 DynamicToolRegistry 不支持 unregisterBuiltinTool，仅记录日志
        bridge.onSkillUnregistered(new SkillRegistryEvent.SkillUnregistered("todo"));

        // 不应调用任何注册中心方法
        verifyNoInteractions(toolRegistry);
    }

    @Test
    void onSkillUpdated_先注销再注册() {
        SkillDefinition oldDef = createTestDefinition("todo");
        SkillDefinition newDef = createTestDefinition("todo");

        bridge.onSkillUpdated(new SkillRegistryEvent.SkillUpdated(oldDef, newDef));

        // 应注册新工具（注销只记录日志）
        verify(toolRegistry).registerBuiltinTool(any(ToolContract.class));
    }

    /**
     * 创建测试用 SkillDefinition。
     */
    private SkillDefinition createTestDefinition(String id) {
        return SkillDefinition.builder()
                .id(id)
                .name("待办管理")
                .description("管理待办事项")
                .version("1.0.0")
                .source(new SkillSource.Builtin())
                .systemPrompt("你是待办管理助手")
                .allowedTools(List.of("builtin.todo.create"))
                .execution(ExecutionStrategy.DEFAULT)
                .memoryAccess(MemoryAccessPolicy.none())
                .budget(SkillBudget.DEFAULT)
                .metadata(Map.of())
                .build();
    }
}
