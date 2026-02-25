package com.lifepilot.skill.builtin;

import com.lifepilot.skill.model.*;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * {@link BuiltinSkillRegistrar} 单元测试。
 *
 * @author zsg
 * @since 2026-02-25
 */
class BuiltinSkillRegistrarTest {

    private SkillRegistry skillRegistry;
    private DynamicToolRegistry toolRegistry;

    /** 记录注册顺序的列表。 */
    private final List<String> registrationOrder = new ArrayList<>();

    @BeforeEach
    void setUp() {
        skillRegistry = mock(SkillRegistry.class);
        toolRegistry = mock(DynamicToolRegistry.class);
        when(skillRegistry.register(any())).thenReturn(true);
        registrationOrder.clear();
    }

    @Test
    void 按order升序注册所有Provider() {
        var provider30 = new Order30Provider(registrationOrder);
        var provider10 = new Order10Provider(registrationOrder);

        var registrar = new BuiltinSkillRegistrar(
                List.of(provider30, provider10), skillRegistry, toolRegistry);

        registrar.registerAll();

        // provider10（order=10）应先于 provider30（order=30）注册
        assertThat(registrationOrder).containsExactly("order10", "order30");
        verify(skillRegistry, times(2)).register(any());
    }

    @Test
    void 单个注册失败不影响其他() {
        var failProvider = new FailingProvider();
        var goodProvider = new Order10Provider(registrationOrder);

        var registrar = new BuiltinSkillRegistrar(
                List.of(failProvider, goodProvider), skillRegistry, toolRegistry);

        registrar.registerAll();

        // 好的 provider 仍然应该被注册
        assertThat(registrationOrder).containsExactly("order10");
        verify(skillRegistry, times(1)).register(any());
    }

    @Test
    void 空Provider列表不报错() {
        var registrar = new BuiltinSkillRegistrar(
                List.of(), skillRegistry, toolRegistry);

        registrar.registerAll();

        verify(skillRegistry, never()).register(any());
    }

    @Test
    void 无注解Provider排在最后() {
        var annotatedProvider = new Order10Provider(registrationOrder);
        var plainProvider = new PlainProvider(registrationOrder);

        var registrar = new BuiltinSkillRegistrar(
                List.of(plainProvider, annotatedProvider), skillRegistry, toolRegistry);

        registrar.registerAll();

        // 有注解的（order=10）应先于无注解的（Integer.MAX_VALUE）
        assertThat(registrationOrder).containsExactly("order10", "plain");
    }

    @Test
    void registerTools失败时不调用register() {
        var failToolsProvider = new FailOnRegisterToolsProvider();

        var registrar = new BuiltinSkillRegistrar(
                List.of(failToolsProvider), skillRegistry, toolRegistry);

        registrar.registerAll();

        // registerTools 失败后不应调用 skillRegistry.register
        verify(skillRegistry, never()).register(any());
    }

    // ─────────────────────────────────────────────
    //  测试用 Provider 实现
    // ─────────────────────────────────────────────

    private static SkillDefinition buildDefinition(String id) {
        return SkillDefinition.builder()
                .id(id)
                .name("测试 Skill " + id)
                .description("测试用 Skill")
                .version("1.0.0")
                .source(new SkillSource.Builtin())
                .systemPrompt("你是测试助手")
                .allowedTools(List.of("builtin.test.tool"))
                .execution(ExecutionStrategy.DEFAULT)
                .memoryAccess(MemoryAccessPolicy.none())
                .budget(SkillBudget.DEFAULT)
                .metadata(Map.of())
                .build();
    }

    @BuiltinSkill(id = "order10", order = 10)
    static class Order10Provider implements BuiltinSkillProvider {
        private final List<String> order;

        Order10Provider(List<String> order) { this.order = order; }

        @Override
        public SkillDefinition provide() { return buildDefinition("order10"); }

        @Override
        public void registerTools(DynamicToolRegistry toolRegistry) {
            order.add("order10");
        }
    }

    @BuiltinSkill(id = "order30", order = 30)
    static class Order30Provider implements BuiltinSkillProvider {
        private final List<String> order;

        Order30Provider(List<String> order) { this.order = order; }

        @Override
        public SkillDefinition provide() { return buildDefinition("order30"); }

        @Override
        public void registerTools(DynamicToolRegistry toolRegistry) {
            order.add("order30");
        }
    }

    /** 无 @BuiltinSkill 注解的 Provider。 */
    static class PlainProvider implements BuiltinSkillProvider {
        private final List<String> order;

        PlainProvider(List<String> order) { this.order = order; }

        @Override
        public SkillDefinition provide() { return buildDefinition("plain"); }

        @Override
        public void registerTools(DynamicToolRegistry toolRegistry) {
            order.add("plain");
        }
    }

    /** provide() 抛异常的 Provider。 */
    @BuiltinSkill(id = "failing", order = 5)
    static class FailingProvider implements BuiltinSkillProvider {
        @Override
        public SkillDefinition provide() {
            throw new RuntimeException("模拟 provide 失败");
        }

        @Override
        public void registerTools(DynamicToolRegistry toolRegistry) {
            // registerTools 成功，但 provide 会失败
        }
    }

    /** registerTools() 抛异常的 Provider。 */
    @BuiltinSkill(id = "fail-tools", order = 5)
    static class FailOnRegisterToolsProvider implements BuiltinSkillProvider {
        @Override
        public SkillDefinition provide() { return buildDefinition("fail-tools"); }

        @Override
        public void registerTools(DynamicToolRegistry toolRegistry) {
            throw new RuntimeException("模拟 registerTools 失败");
        }
    }
}
