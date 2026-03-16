package com.lifepilot.prompt.config;

import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
    void 注册33个模板() {
        contextRunner.run(context -> {
            var registry = context.getBean(PromptRegistry.class);
            assertEquals(33, registry.size(), "unexpected template keys: " + registry.keys());
        });
    }

    @Test
    void 注册预期模板键_不含旧版A2UI提示词() {
        contextRunner.run(context -> {
            var registry = context.getBean(PromptRegistry.class);
            var expectedKeys = Set.of(
                    "agent/role-definition", "agent/understanding",
                    "agent/context-guide",
                    "agent/streaming-constraint", "agent/react-system",
                    "agent/react-user-prompt", "agent/react-user-prompt-basic",
                    "skill/todo", "skill/schedule", "skill/habit", "skill/memory", "skill/sync",
                    "skill/datastore", "skill/gap-analysis",
                    "memory/compression-summary", "memory/compression-keypoints", "memory/entity-compression",
                    "memory/procedural-extraction",
                    "knowledge/chunk-context", "knowledge/rerank-pointwise", "knowledge/rerank-listwise",
                    "knowledge/query-rewrite", "knowledge/hyde-generation",
                    "knowledge/entity-extraction", "knowledge/chunk-context-single",
                    "eval/judge-full", "eval/judge-simplified",
                    "semantic/entity-extraction",
                    "proactive/evaluation", "proactive/high-urgency/deadline_reminder",
                    "proactive/high-urgency/schedule_reminder",
                    "generation/skill-generation", "semantic/entity-disambiguation"
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
