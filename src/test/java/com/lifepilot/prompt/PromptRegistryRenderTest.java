package com.lifepilot.prompt;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PromptRegistryRenderTest {

    @Test
    void understanding模板可以正常渲染() {
        var registry = new PromptRegistry();
        registry.register("agent/role-definition",
                new ClassPathResource("prompts/agent/role-definition.st"));
        registry.register("agent/context-guide",
                new ClassPathResource("prompts/agent/context-guide.st"));
        registry.register("agent/understanding",
                new ClassPathResource("prompts/agent/understanding.st"));

        String roleDefinition = registry.render("agent/role-definition");
        String contextGuide = registry.render("agent/context-guide");
        String rendered = registry.render("agent/understanding", Map.of(
                "roleDefinition", roleDefinition,
                "contextGuide", contextGuide,
                "currentDateTime", "2026-03-11T17:00:00+08:00",
                "timezone", "Asia/Shanghai",
                "locale", "zh-CN"));

        assertThat(rendered).contains("\"summary\"");
        assertThat(rendered).contains("当前时间：2026-03-11T17:00:00+08:00");
        assertThat(rendered).contains("实际输出时请使用标准 JSON 对象");
    }
}
