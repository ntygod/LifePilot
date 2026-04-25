package com.lifepilot.meta.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置外部化完整性测试。
 *
 * <p>验证 MetaProperties 所有字段有默认值（嵌套对象已初始化），
 * 且具体默认值与架构文档 §10 / application.yml 声明一致。</p>
 *
 * <p><b>Validates: Requirements 17.1, 17.2</b></p>
 *
 * @author zsg
 * @since 2026-03-08
 */
class ConfigExternalizationCompletenessTest {

    private MetaProperties props;

    @BeforeEach
    void setUp() {
        props = new MetaProperties();
    }

    // ── 嵌套对象非空 ──────────────────────────────────────────

    @Test
    void 所有顶层嵌套对象已初始化() {
        assertThat(props.getInfra()).isNotNull();
        assertThat(props.getIntrospection()).isNotNull();
        assertThat(props.getSkillDiscovery()).isNotNull();
        assertThat(props.getOnboarding()).isNotNull();
    }

    @Test
    void Infra所有子对象已初始化() {
        var infra = props.getInfra();
        assertThat(infra.getWebSearch()).isNotNull();
        assertThat(infra.getWebFetch()).isNotNull();
        assertThat(infra.getShell()).isNotNull();
        assertThat(infra.getBrowser()).isNotNull();
        assertThat(infra.getCodeExecute()).isNotNull();
        assertThat(infra.getFile()).isNotNull();
        assertThat(infra.getInteraction()).isNotNull();
    }

    // ── WebSearch 默认值 ──────────────────────────────────────

    @Test
    void WebSearch默认值正确() {
        var ws = props.getInfra().getWebSearch();
        assertThat(ws.getProvider()).isEqualTo("tavily");
        assertThat(ws.getApiKey()).isEqualTo("");
        assertThat(ws.getMaxResults()).isEqualTo(5);
        assertThat(ws.getSearchDepth()).isEqualTo("basic");
        assertThat(ws.getTopic()).isEqualTo("general");
        assertThat(ws.isIncludeAnswer()).isTrue();
    }

    // ── WebFetch 默认值 ──────────────────────────────────────

    @Test
    void WebFetch默认值正确() {
        var wf = props.getInfra().getWebFetch();
        assertThat(wf.getMaxContentLength()).isEqualTo(50000);
        assertThat(wf.getTimeoutSeconds()).isEqualTo(10);
    }

    // ── Shell 默认值 ─────────────────────────────────────────

    @Test
    void Shell默认值正确() {
        var shell = props.getInfra().getShell();
        assertThat(shell.getTimeoutSeconds()).isEqualTo(120);
        assertThat(shell.getMaxOutputLength()).isEqualTo(50000);
        assertThat(shell.getCommandBlacklist()).hasSize(8);
        assertThat(shell.getCommandBlacklist()).containsExactly(
                "rm\\s+-rf\\s+/(?!\\S)", "\\bformat\\s+[a-zA-Z]:",
                "(?:^|[;&|])\\s*shutdown\\b",
                "(?:^|[;&|])\\s*reboot\\b",
                "\\bmkfs\\b",
                "\\bdd\\s+if=",
                ":\\(\\)\\{\\s*:|:&\\s*\\};:",
                "\\bchmod\\s+-R\\s+777\\s+/"
        );
    }

    // ── Browser 默认值 ───────────────────────────────────────

    @Test
    void Browser默认值正确() {
        var browser = props.getInfra().getBrowser();
        assertThat(browser.isEnabled()).isTrue();
        assertThat(browser.isHeadless()).isFalse();  // 本地/桌面默认可见
        assertThat(browser.getIdleTimeoutSeconds()).isEqualTo(300);
    }

    // ── CodeExecute 默认值 ───────────────────────────────────

    @Test
    void CodeExecute默认值正确() {
        var ce = props.getInfra().getCodeExecute();
        assertThat(ce.isEnabled()).isTrue();
        assertThat(ce.getDefaultLanguage()).isEqualTo("python");
    }

    // ── FileAccess 默认值 ────────────────────────────────────

    @Test
    void FileAccess默认值正确() {
        var file = props.getInfra().getFile();
        assertThat(file.getMaxReadSize()).isEqualTo(1048576);
        assertThat(file.getAllowedDirectories()).isEmpty();
        assertThat(file.getDeniedDirectories())
                .hasSize(3)
                .containsExactly("/etc", "/var", "C:\\Windows");
    }

    // ── Interaction 默认值 ───────────────────────────────────

    @Test
    void Interaction默认值正确() {
        var interaction = props.getInfra().getInteraction();
        assertThat(interaction.getResponseTimeoutSeconds()).isEqualTo(120);
    }

    // ── Introspection 默认值 ─────────────────────────────────

    @Test
    void Introspection默认值正确() {
        assertThat(props.getIntrospection().getCacheTtlSeconds()).isEqualTo(60);
    }

    // ── SkillDiscovery 默认值 ────────────────────────────────

    @Test
    void SkillDiscovery默认值正确() {
        var sd = props.getSkillDiscovery();
        assertThat(sd.isEnabled()).isTrue();
    }

    // ── Onboarding 默认值 ────────────────────────────────────

    @Test
    void Onboarding默认值正确() {
        var ob = props.getOnboarding();
        assertThat(ob.isAutoTrigger()).isTrue();
        assertThat(ob.getAgentDefinition()).isEqualTo("preset-agents/onboarding-guide.md");
    }

    // ── 集合字段不可变性 ─────────────────────────────────────

    @Test
    void 集合默认值非null() {
        assertThat(props.getInfra().getShell().getCommandBlacklist()).isNotNull();
        assertThat(props.getInfra().getFile().getAllowedDirectories()).isNotNull();
        assertThat(props.getInfra().getFile().getDeniedDirectories()).isNotNull();
    }
}
