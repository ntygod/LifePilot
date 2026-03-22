package com.lifepilot.meta.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MetaProperties 配置绑定测试。
 *
 * <p>验证默认值绑定正确，以及自定义配置覆盖生效。</p>
 *
 * @author zsg
 * @since 2026-03-10
 */
class MetaPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MetaProperties.class)
    static class TestConfig {
    }

    @Test
    void 默认值绑定正确_Infra_WebSearch() {
        contextRunner.run(context -> {
            var props = context.getBean(MetaProperties.class);
            var ws = props.getInfra().getWebSearch();
            assertThat(ws.getProvider()).isEqualTo("tavily");
            assertThat(ws.getApiKey()).isEmpty();
            assertThat(ws.getMaxResults()).isEqualTo(5);
            assertThat(ws.getSearchDepth()).isEqualTo("basic");
            assertThat(ws.getTopic()).isEqualTo("general");
            assertThat(ws.isIncludeAnswer()).isTrue();
        });
    }

    @Test
    void 默认值绑定正确_Infra_WebFetch() {
        contextRunner.run(context -> {
            var props = context.getBean(MetaProperties.class);
            var wf = props.getInfra().getWebFetch();
            assertThat(wf.getMaxContentLength()).isEqualTo(50000);
            assertThat(wf.getTimeoutSeconds()).isEqualTo(10);
        });
    }

    @Test
    void 默认值绑定正确_Infra_UserProfile() {
        contextRunner.run(context -> {
            var props = context.getBean(MetaProperties.class);
            var up = props.getInfra().getUserProfile();
            assertThat(up.getTimezone()).isEmpty();
            assertThat(up.getCacheTtlSeconds()).isEqualTo(300);
        });
    }

    @Test
    void 默认值绑定正确_Infra_Shell() {
        contextRunner.run(context -> {
            var props = context.getBean(MetaProperties.class);
            var shell = props.getInfra().getShell();
            assertThat(shell.getCommandBlacklist()).hasSize(6);
            assertThat(shell.getCommandBlacklist()).contains("shutdown", "reboot", "mkfs");
            assertThat(shell.getTimeoutSeconds()).isEqualTo(30);
            assertThat(shell.getMaxOutputLength()).isEqualTo(50000);
        });
    }

    @Test
    void 默认值绑定正确_Infra_Browser() {
        contextRunner.run(context -> {
            var props = context.getBean(MetaProperties.class);
            var browser = props.getInfra().getBrowser();
            assertThat(browser.isEnabled()).isTrue();
            assertThat(browser.isHeadless()).isTrue();
            assertThat(browser.getIdleTimeoutSeconds()).isEqualTo(300);
        });
    }

    @Test
    void 默认值绑定正确_Infra_CodeExecute() {
        contextRunner.run(context -> {
            var props = context.getBean(MetaProperties.class);
            var ce = props.getInfra().getCodeExecute();
            assertThat(ce.isEnabled()).isTrue();
            assertThat(ce.getDefaultLanguage()).isEqualTo("python");
        });
    }

    @Test
    void 默认值绑定正确_Infra_FileAccess() {
        contextRunner.run(context -> {
            var props = context.getBean(MetaProperties.class);
            var file = props.getInfra().getFile();
            assertThat(file.getMaxReadSize()).isEqualTo(1048576);
            assertThat(file.getAllowedDirectories()).isEmpty();
            assertThat(file.getDeniedDirectories()).containsExactly("/etc", "/var", "C:\\Windows");
        });
    }

    @Test
    void 默认值绑定正确_Infra_Interaction() {
        contextRunner.run(context -> {
            var props = context.getBean(MetaProperties.class);
            var interaction = props.getInfra().getInteraction();
            assertThat(interaction.getResponseTimeoutSeconds()).isEqualTo(120);
        });
    }

    @Test
    void 默认值绑定正确_Introspection() {
        contextRunner.run(context -> {
            var props = context.getBean(MetaProperties.class);
            assertThat(props.getIntrospection().getCacheTtlSeconds()).isEqualTo(60);
        });
    }

    @Test
    void 默认值绑定正确_SkillDiscovery() {
        contextRunner.run(context -> {
            var props = context.getBean(MetaProperties.class);
            var sd = props.getSkillDiscovery();
            assertThat(sd.isEnabled()).isTrue();
        });
    }

    @Test
    void 默认值绑定正确_Onboarding() {
        contextRunner.run(context -> {
            var props = context.getBean(MetaProperties.class);
            var ob = props.getOnboarding();
            assertThat(ob.isAutoTrigger()).isTrue();
            assertThat(ob.getAgentDefinition()).isEqualTo("preset-agents/onboarding-guide.md");
        });
    }

    @Test
    void 自定义配置覆盖默认值() {
        contextRunner
                .withPropertyValues(
                        "lifepilot.meta.infra.web-search.provider=tavily",
                        "lifepilot.meta.infra.web-search.max-results=10",
                        "lifepilot.meta.infra.web-search.search-depth=advanced",
                        "lifepilot.meta.infra.web-search.topic=news",
                        "lifepilot.meta.infra.web-search.include-answer=false",
                        "lifepilot.meta.infra.shell.timeout-seconds=60",
                        "lifepilot.meta.infra.interaction.response-timeout-seconds=300",
                        "lifepilot.meta.introspection.cache-ttl-seconds=120",
                        "lifepilot.meta.skill-discovery.enabled=false",
                        "lifepilot.meta.onboarding.auto-trigger=false"
                )
                .run(context -> {
                    var props = context.getBean(MetaProperties.class);
                    assertThat(props.getInfra().getWebSearch().getProvider()).isEqualTo("tavily");
                    assertThat(props.getInfra().getWebSearch().getMaxResults()).isEqualTo(10);
                    assertThat(props.getInfra().getWebSearch().getSearchDepth()).isEqualTo("advanced");
                    assertThat(props.getInfra().getWebSearch().getTopic()).isEqualTo("news");
                    assertThat(props.getInfra().getWebSearch().isIncludeAnswer()).isFalse();
                    assertThat(props.getInfra().getShell().getTimeoutSeconds()).isEqualTo(60);
                    assertThat(props.getInfra().getInteraction().getResponseTimeoutSeconds()).isEqualTo(300);
                    assertThat(props.getIntrospection().getCacheTtlSeconds()).isEqualTo(120);
                    assertThat(props.getSkillDiscovery().isEnabled()).isFalse();
                    assertThat(props.getOnboarding().isAutoTrigger()).isFalse();
                });
    }
}
