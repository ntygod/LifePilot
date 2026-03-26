package com.lifepilot.prompt.config;

import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptAutoConfiguration_集成测试 {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PromptAutoConfiguration.class));

    @Test
    void promptRegistry_Bean已注册() {
        contextRunner.run(context -> {
            assertTrue(context.containsBean("promptRegistry"));
            var registry = context.getBean(PromptRegistry.class);
            assertNotNull(registry);
        });
    }

    @Test
    void 注册36个模板() {
        contextRunner.run(context -> {
            var registry = context.getBean(PromptRegistry.class);
            assertEquals(36, registry.size(), "unexpected template keys: " + registry.keys());
        });
    }

    @Test
    void 注册预期模板键且不含旧版A2UI提示词() {
        contextRunner.run(context -> {
            var registry = context.getBean(PromptRegistry.class);
            var expectedKeys = Set.of(
                    "agent/context-guide",
                    "agent/react-system",
                    "agent/react-system-task",
                    "agent/react-user-prompt",
                    "agent/role-definition",
                    "agent/skill-catalog",
                    "agent/streaming-constraint",
                    "agent/understanding",
                    "eval/judge-full",
                    "eval/judge-simplified",
                    "generation/skill-fix",
                    "generation/skill-generation",
                    "generation/skill-generation-enhanced",
                    "knowledge/chunk-context",
                    "knowledge/chunk-context-single",
                    "knowledge/entity-extraction",
                    "knowledge/hyde-generation",
                    "knowledge/query-rewrite",
                    "knowledge/rerank-listwise",
                    "knowledge/rerank-pointwise",
                    "memory/agentic-tool-guide",
                    "memory/compression-keypoints",
                    "memory/compression-summary",
                    "memory/contrastive-learning",
                    "memory/entity-compression",
                    "memory/experience-extraction",
                    "memory/experience-merge",
                    "memory/hyde-generation",
                    "memory/procedural-extraction",
                    "memory/query-rewrite",
                    "memory/subtask-reflection",
                    "semantic/entity-disambiguation",
                    "semantic/entity-extraction",
                    "skill/datastore",
                    "skill/gap-analysis",
                    "skill/memory"
            );

            var actualKeys = registry.keys();
            for (String key : expectedKeys) {
                assertTrue(actualKeys.contains(key), "missing template key: " + key);
                assertTrue(registry.getTemplate(key).isPresent(), "template should exist: " + key);
            }
            assertFalse(actualKeys.contains("agent/a2ui-component-catalog"));
        });
    }

    @Test
    void 无变量模板可正常渲染() {
        contextRunner.run(context -> {
            var registry = context.getBean(PromptRegistry.class);
            String result = registry.render("agent/role-definition");
            assertNotNull(result);
            assertFalse(result.isBlank());
        });
    }
}
