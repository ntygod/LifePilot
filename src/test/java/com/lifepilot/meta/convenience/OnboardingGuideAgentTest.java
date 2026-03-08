package com.lifepilot.meta.convenience;

import com.lifepilot.multiagent.config.MultiAgentProperties;
import com.lifepilot.multiagent.loader.AgentMarkdownParser;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 引导 Agent（onboarding-guide.md）加载验证测试。
 *
 * <p>验证 AgentMarkdownParser 能正确解析 onboarding-guide.md 文件，
 * 确保 YAML Frontmatter 字段和 System Prompt 正文均符合预期。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
class OnboardingGuideAgentTest {

    @Test
    void onboardingGuide_解析成功() throws Exception {
        var config = new MultiAgentProperties();
        var parser = new AgentMarkdownParser(config);

        var resource = new ClassPathResource("preset-agents/onboarding-guide.md");
        assertThat(resource.exists()).as("onboarding-guide.md 文件应存在于 classpath").isTrue();

        String content = resource.getContentAsString(StandardCharsets.UTF_8);
        var result = parser.parse(content, Path.of("preset-agents/onboarding-guide.md"));

        assertThat(result).isPresent();

        var def = result.get();
        assertThat(def.id()).isEqualTo("onboarding-guide");
        assertThat(def.name()).isEqualTo("引导助手");
        assertThat(def.description()).isNotBlank();
        assertThat(def.canDelegate()).isFalse();
        assertThat(def.allowedTools()).containsExactlyInAnyOrder(
                "system.list-capabilities",
                "system.explain",
                "system.suggest",
                "system.status",
                "builtin.interact.choose"
        );
        assertThat(def.systemPrompt()).isNotBlank();
        assertThat(def.systemPrompt()).contains("引导助手");
    }
}
