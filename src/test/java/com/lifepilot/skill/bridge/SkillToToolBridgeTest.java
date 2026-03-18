package com.lifepilot.skill.bridge;

import com.lifepilot.meta.convenience.CapabilityAggregator;
import com.lifepilot.meta.convenience.CapabilityInfo;
import com.lifepilot.observability.guardrail.GuardrailEngine;
import com.lifepilot.skill.activation.SkillActivationException;
import com.lifepilot.skill.activation.SkillActivator;
import com.lifepilot.skill.model.SkillActivation;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.NonNull;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SkillToToolBridge} 单元测试。
 *
 * <p>覆盖场景：list_skills 返回摘要、activate_skill 激活成功、
 * activate_skill 激活失败（SkillActivationException）、未知 action 返回错误、
 * 缺少 skill_id 返回错误、generate_skill 工具注册验证。</p>
 *
 * @author zsg
 * @since 2026-03-07
 */
class SkillToToolBridgeTest {

    private StubSkillActivator stubActivator;
    private CapturingToolRegistry capturingToolRegistry;
    private CapabilityAggregator mockCapabilityAggregator;
    private SkillToToolBridge bridge;

    /** 注册后捕获的 skills 工具。 */
    private BuiltinTool skillsTool;
    /** 注册后捕获的 generate_skill 工具。 */
    private BuiltinTool generateSkillTool;

    @BeforeEach
    void setUp() {
        stubActivator = new StubSkillActivator();
        capturingToolRegistry = new CapturingToolRegistry();
        mockCapabilityAggregator = mock(CapabilityAggregator.class);
        bridge = new SkillToToolBridge(capturingToolRegistry, stubActivator, mockCapabilityAggregator, null, null);

        // 注册工具并捕获
        bridge.registerSkillsTool();
        skillsTool = capturingToolRegistry.getCapturedTool("skills");
        generateSkillTool = capturingToolRegistry.getCapturedTool("generate_skill");
        assertThat(skillsTool).isNotNull();
        assertThat(generateSkillTool).isNotNull();
    }

    // ── list_skills 返回摘要 ──

    @Test
    void list_skills_返回所有已注册Skill摘要() {
        when(mockCapabilityAggregator.filterByType("skill")).thenReturn(List.of(
                new CapabilityInfo("todo", "待办管理", "管理待办事项", "builtin", "active", "skill"),
                new CapabilityInfo("schedule", "日程管理", "管理日程安排", "builtin", "active", "skill")
        ));

        ToolResult result = executeSkillsAction("list_skills", Map.of());

        assertThat(result.ok()).isTrue();
        @SuppressWarnings("unchecked")
        List<String> skills = (List<String>) result.data().get("skills");
        assertThat(skills).containsExactly("todo: 管理待办事项", "schedule: 管理日程安排");
    }

    @Test
    void list_skills_无Skill时返回空列表() {
        when(mockCapabilityAggregator.filterByType("skill")).thenReturn(List.of());

        ToolResult result = executeSkillsAction("list_skills", Map.of());

        assertThat(result.ok()).isTrue();
        @SuppressWarnings("unchecked")
        List<String> skills = (List<String>) result.data().get("skills");
        assertThat(skills).isEmpty();
    }

    // ── activate_skill 激活成功 ──

    @Test
    void activate_skill_激活成功返回指令和建议工具() {
        stubActivator.setActivation(new SkillActivation(
                "writing", "你是一个写作助手。", List.of("search", "web_browse")));

        ToolResult result = executeSkillsAction("activate_skill", Map.of("skill_id", "writing"));

        assertThat(result.ok()).isTrue();
        assertThat(result.data().get("skill_id")).isEqualTo("writing");
        assertThat(result.data().get("instructions")).isEqualTo("你是一个写作助手。");
        @SuppressWarnings("unchecked")
        List<String> tools = (List<String>) result.data().get("suggested_tools");
        assertThat(tools).containsExactly("search", "web_browse");
    }

    // ── activate_skill 激活失败 ──

    @Test
    void activate_skill_Skill不存在返回错误() {
        stubActivator.setException(new SkillActivationException("Skill 不存在: unknown"));

        ToolResult result = executeSkillsAction("activate_skill", Map.of("skill_id", "unknown"));

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("Skill 不存在或无法激活");
        assertThat(result.error()).contains("unknown");
    }

    // ── 未知 action 返回错误 ──

    @Test
    void 未知action_返回错误信息() {
        ToolResult result = executeSkillsAction("delete_skill", Map.of());

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("未知操作");
        assertThat(result.error()).contains("delete_skill");
    }

    // ── 缺少 skill_id 返回错误 ──

    @Test
    void activate_skill_缺少skill_id_抛出IllegalArgumentException() {
        assertThatThrownBy(() -> executeSkillsAction("activate_skill", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skill_id");
    }

    // ── generate_skill 工具注册验证 ──

    @Test
    void generate_skill_工具风险等级为HIGH() {
        assertThat(generateSkillTool.riskLevel())
                .isEqualTo(com.lifepilot.observability.guardrail.RiskLevel.HIGH);
    }

    @Test
    void generate_skill_自扩展未启用时返回错误() {
        // bridge 构造时 gapDetector 和 skillGenerator 都为 null
        ToolInput input = new ToolInput("generate_skill",
                Map.of("description", "帮我管理日程"),
                JsonSchema.empty(), null, null);

        ToolResult result = generateSkillTool.execute(input);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("自扩展功能未启用");
    }

    // ── 辅助方法 ──

    /**
     * 构造 ToolInput 并通过捕获的 skills 工具执行。
     */
    private ToolResult executeSkillsAction(String action, Map<String, Object> extraParams) {
        var params = new java.util.HashMap<>(extraParams);
        params.put("action", action);
        ToolInput input = new ToolInput("skills", params, JsonSchema.empty(), null, null);
        return skillsTool.execute(input);
    }

    // ── Stub / Capture 内部类 ──

    /**
     * 捕获注册工具的 DynamicToolRegistry — 记录所有 registerBuiltinTool 调用。
     */
    private static class CapturingToolRegistry extends DynamicToolRegistry {

        private final java.util.Map<String, BuiltinTool> capturedTools = new java.util.HashMap<>();

        CapturingToolRegistry() {
            super(new NoOpGuardrailEngine(), new NoOpEventPublisher());
        }

        @Override
        public void registerBuiltinTool(ToolContract tool) {
            if (tool instanceof BuiltinTool bt) {
                capturedTools.put(bt.id(), bt);
            }
        }

        BuiltinTool getCapturedTool(String id) {
            return capturedTools.get(id);
        }
    }

    /**
     * 简易 SkillActivator — 可配置返回激活结果或抛出异常。
     */
    private static class StubSkillActivator extends SkillActivator {

        private SkillActivation activation;
        private SkillActivationException exception;

        StubSkillActivator() {
            super(null, null, null);
        }

        void setActivation(SkillActivation activation) {
            this.activation = activation;
            this.exception = null;
        }

        void setException(SkillActivationException exception) {
            this.exception = exception;
            this.activation = null;
        }

        @Override
        public SkillActivation activate(String skillId) {
            if (exception != null) {
                throw exception;
            }
            return activation;
        }
    }

    /**
     * 空操作 GuardrailEngine — 满足 DynamicToolRegistry 构造依赖。
     */
    private static class NoOpGuardrailEngine extends GuardrailEngine {

        NoOpGuardrailEngine() {
            super(null, null, null);
        }

        @Override
        public void addAllowedTools(List<String> toolIds) {
            // 空操作
        }

        @Override
        public void removeAllowedTools(List<String> toolIds) {
            // 空操作
        }
    }

    /**
     * 空操作事件发布器。
     */
    private static class NoOpEventPublisher implements ApplicationEventPublisher {

        @Override
        public void publishEvent(@NonNull Object event) {
            // 空操作
        }
    }
}
