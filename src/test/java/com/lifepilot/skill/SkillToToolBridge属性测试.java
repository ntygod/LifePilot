package com.lifepilot.skill;

import com.lifepilot.meta.convenience.CapabilityAggregator;
import com.lifepilot.meta.convenience.CapabilityInfo;
import com.lifepilot.skill.activation.SkillActivator;
import com.lifepilot.skill.activation.SkillMetricsTracker;
import com.lifepilot.skill.bridge.SkillToToolBridge;
import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.registry.SkillDefinitionValidator;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.skill.registry.SkillSearchIndex;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import net.jqwik.api.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.lang.NonNull;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property 6: SkillToToolBridge list_skills 与 CapabilityAggregator 一致性 属性测试。
 *
 * <p>验证：对任意 CapabilityAggregator 返回的 Skill 能力列表，
 * 通过 skills 工具调用 list_skills 操作返回的摘要列表
 * 应与 CapabilityAggregator.filterByType("skill") 返回的数据一致。</p>
 *
 * <p><b>Validates: Requirements AC-4.2</b></p>
 *
 * @author zsg
 * @since 2026-03-08
 */
class SkillToToolBridge属性测试 {

    // ── Property 6: list_skills 与 Registry 一致 ──

    @Property(tries = 100)
    @Label("Feature: skill-system-refactor, Property 6: list_skills 与 CapabilityAggregator 一致性")
    @SuppressWarnings("unchecked")
    void listSkills返回的摘要与CapabilityAggregator一致(
            @ForAll("skillSets") List<SkillDefinition> skills) {

        // 构建真实 SkillRegistry（仅用于 SkillActivator 依赖）
        var config = new SkillConfigProperties();
        var toolRegistry = new StubDynamicToolRegistry();
        var validator = new SkillDefinitionValidator(toolRegistry, config);
        var searchIndex = new StubSkillSearchIndex();
        var eventPublisher = new NoOpEventPublisher();
        var registry = new SkillRegistry(validator, searchIndex, eventPublisher, config);

        // 注册所有 Skill
        for (SkillDefinition skill : skills) {
            registry.register(skill);
        }

        // 构建期望的 CapabilityInfo 列表（与注册的 Skill 对应）
        List<CapabilityInfo> expectedCapabilities = skills.stream()
                .collect(java.util.stream.Collectors.toMap(
                        SkillDefinition::id, s -> s, (a, b) -> b))
                .values().stream()
                .map(s -> new CapabilityInfo(s.id(), s.name(), s.description(), "builtin", "active", "skill"))
                .toList();

        // Mock CapabilityAggregator
        var mockCapabilityAggregator = mock(CapabilityAggregator.class);
        when(mockCapabilityAggregator.filterByType("skill")).thenReturn(expectedCapabilities);

        // 构建 SkillToToolBridge
        var metricsTracker = new SkillMetricsTracker();
        var activator = new SkillActivator(registry, metricsTracker, eventPublisher);
        var bridge = new SkillToToolBridge(toolRegistry, activator, mockCapabilityAggregator);

        // 构造 list_skills 请求
        ToolInput listInput = new ToolInput(
                "skills",
                Map.of("action", "list_skills"),
                JsonSchema.empty(),
                null,
                null
        );

        // 注册工具并通过 DynamicToolRegistry 获取执行器
        bridge.registerSkillsTool();
        var skillsTool = toolRegistry.resolve("skills");
        assertThat(skillsTool).isPresent();

        ToolResult result = skillsTool.get().execute(listInput);

        // **Validates: AC-4.2**
        assertThat(result.ok()).isTrue();

        List<String> toolSummaries = (List<String>) result.data().get("skills");
        List<String> expectedSummaries = expectedCapabilities.stream()
                .map(cap -> cap.id() + ": " + cap.description())
                .toList();

        assertThat(toolSummaries)
                .as("list_skills 返回的摘要应与 CapabilityAggregator 数据一致")
                .containsExactlyInAnyOrderElementsOf(expectedSummaries);
    }

    // ── 生成器 ──

    @Provide
    Arbitrary<List<SkillDefinition>> skillSets() {
        var idPool = List.of("alpha", "beta", "gamma", "delta", "epsilon");

        var skillArb = Arbitraries.of(idPool).map(id ->
                SkillDefinition.builder()
                        .id(id)
                        .name(id + "-skill")
                        .description(id + " 的描述")
                        .version("1.0.0")
                        .source(new SkillSource.UserDefined("/tmp/" + id, null))
                        .instructions(id + " 的指令内容")
                        .suggestedTools(List.of())
                        .metadata(Map.of())
                        .build()
        );

        return skillArb.list().ofMinSize(0).ofMaxSize(5)
                .map(list -> list.stream()
                        .collect(java.util.stream.Collectors.toMap(
                                SkillDefinition::id,
                                s -> s,
                                (a, b) -> b  // 去重：保留最后一个
                        ))
                        .values().stream().toList()
                );
    }

    // ── Stub 内部类 ──

    /** Stub DynamicToolRegistry — 支持 registerBuiltinTool 和 resolve。 */
    private static class StubDynamicToolRegistry extends DynamicToolRegistry {

        private final java.util.concurrent.ConcurrentHashMap<String, com.lifepilot.tool.ToolContract> store =
                new java.util.concurrent.ConcurrentHashMap<>();

        StubDynamicToolRegistry() {
            super(new StubGuardrailEngine(), new NoOpEventPublisher());
        }

        @Override
        public void registerBuiltinTool(com.lifepilot.tool.ToolContract tool) {
            store.put(tool.id(), tool);
        }

        @Override
        public Optional<com.lifepilot.tool.ToolContract> resolve(String toolId) {
            return Optional.ofNullable(store.get(toolId));
        }
    }

    /** Stub GuardrailEngine — 空操作。 */
    private static class StubGuardrailEngine extends com.lifepilot.observability.guardrail.GuardrailEngine {
        StubGuardrailEngine() {
            super(null, null, null);
        }

        @Override
        public void addAllowedTools(List<String> toolIds) {}

        @Override
        public void removeAllowedTools(List<String> toolIds) {}
    }

    /** Stub SkillSearchIndex — 空操作。 */
    private static class StubSkillSearchIndex extends SkillSearchIndex {
        StubSkillSearchIndex() {
            super(null);
        }

        @Override
        public void index(SkillDefinition definition) {}

        @Override
        public void remove(String skillId) {}
    }

    /** 空操作事件发布器。 */
    private static class NoOpEventPublisher implements ApplicationEventPublisher {
        @Override
        public void publishEvent(@NonNull Object event) {}
    }
}
