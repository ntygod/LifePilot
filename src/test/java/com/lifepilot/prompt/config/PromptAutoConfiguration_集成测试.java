package com.lifepilot.prompt.config;

import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PromptAutoConfiguration 集成测试 — 验证自动配置和模板注册完整性。
 *
 * <p>使用 {@link ApplicationContextRunner} 隔离测试，不依赖完整 Spring Context。</p>
 *
 * @author zsg
 * @since 2026-03-06
 */
class PromptAutoConfiguration_集成测试 {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PromptAutoConfiguration.class));

    @Test
    void promptRegistry_Bean成功注册() {
        contextRunner.run(context -> {
            assertTrue(context.containsBean("promptRegistry"),
                    "PromptRegistry Bean 应成功注册");
            var registry = context.getBean(PromptRegistry.class);
            assertNotNull(registry);
        });
    }

    @Test
    void 已注册模板数量为20() {
        contextRunner.run(context -> {
            var registry = context.getBean(PromptRegistry.class);
            assertEquals(20, registry.size(),
                    "应注册 20 个模板，实际: " + registry.keys());
        });
    }

    @Test
    void 全部20个模板键均已注册() {
        contextRunner.run(context -> {
            var registry = context.getBean(PromptRegistry.class);
            var expectedKeys = Set.of(
                    "agent/role-definition", "agent/understanding", "agent/planning",
                    "agent/executing", "agent/reflecting", "agent/responding",
                    "skill/todo", "skill/schedule", "skill/habit", "skill/memory", "skill/sync",
                    "memory/compression-summary", "memory/compression-keypoints", "memory/entity-compression",
                    "knowledge/chunk-context", "knowledge/rerank-pointwise", "knowledge/rerank-listwise",
                    "proactive/evaluation", "generation/skill-generation", "semantic/entity-disambiguation"
            );

            var actualKeys = registry.keys();
            for (String key : expectedKeys) {
                assertTrue(actualKeys.contains(key), "模板键应已注册: " + key);
                assertTrue(registry.getTemplate(key).isPresent(), "getTemplate 应返回非空: " + key);
            }
        });
    }

    @Test
    void 无变量模板可直接渲染() {
        contextRunner.run(context -> {
            var registry = context.getBean(PromptRegistry.class);
            String result = registry.render("agent/role-definition");
            assertNotNull(result);
            assertFalse(result.isBlank(), "role-definition 渲染结果不应为空");
        });
    }
}
