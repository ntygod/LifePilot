package com.lifepilot.skill;

import com.lifepilot.llm.LlmRouter;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.observability.guardrail.GuardrailEngine;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.skill.activation.SkillActivator;
import com.lifepilot.skill.activation.SkillMetricsTracker;
import com.lifepilot.skill.disclosure.SkillDisclosureTool;
import com.lifepilot.skill.config.SkillAutoConfiguration;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.config.ToolAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.ContextConfiguration;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Skill 系统重构 — AutoConfiguration 集成测试。
 *
 * <p>验证 SkillAutoConfiguration 加载成功、新 Bean 注入正确、旧 Bean 不存在。
 * 使用 {@code @ContextConfiguration} 精确加载 Skill 和 Tool AutoConfiguration，
 * Mock 外部依赖（LlmRouter、HybridRetriever 等），避免全量应用上下文加载。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = SkillRefactor_AutoConfiguration_集成测试.TestConfig.class)
class SkillRefactor_AutoConfiguration_集成测试 {

    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void configure(@NonNull DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + Path.of(tmpDir, "skill-autoconf-it-" + DB_ID + ".db")
                        .toString().replace("\\", "/"));

        // 启用 Skill 和 Tool 模块
        registry.add("lifepilot.skills.enabled", () -> "true");
        registry.add("lifepilot.tool.enabled", () -> "true");

        // Skill 目录指向临时目录
        registry.add("lifepilot.skills.directory",
                () -> Path.of(tmpDir, "skill-autoconf-it-skills-" + DB_ID)
                        .toString().replace("\\", "/"));
    }

    /**
     * 测试配置 — 仅导入 Skill 和 Tool AutoConfiguration，Mock 外部依赖。
     */
    @Configuration
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            ToolAutoConfiguration.class,
            SkillAutoConfiguration.class
    })
    static class TestConfig {

        @Bean
        LlmRouter llmRouter() {
            return mock(LlmRouter.class);
        }

        @Bean
        GuardrailEngine guardrailEngine() {
            return mock(GuardrailEngine.class);
        }

        @Bean
        HybridRetriever hybridRetriever() {
            return mock(HybridRetriever.class);
        }

        @Bean
        SemanticMemory semanticMemory() {
            return mock(SemanticMemory.class);
        }

        @Bean
        PromptRegistry promptRegistry() {
            return mock(PromptRegistry.class);
        }
    }

    @Autowired
    private ApplicationContext ctx;

    // ─────────────────────────────────────────────
    //  Spring Context 加载
    // ─────────────────────────────────────────────

    @Test
    void SpringContext_加载成功() {
        assertThat(ctx).isNotNull();
    }

    // ─────────────────────────────────────────────
    //  新 Bean 存在
    // ─────────────────────────────────────────────

    @Test
    void 新Bean_SkillActivator_存在且类型正确() {
        assertThat(ctx.containsBean("skillActivator")).isTrue();
        assertThat(ctx.getBean("skillActivator")).isInstanceOf(SkillActivator.class);
    }

    @Test
    void 新Bean_SkillMetricsTracker_存在且类型正确() {
        assertThat(ctx.containsBean("skillMetricsTracker")).isTrue();
        assertThat(ctx.getBean("skillMetricsTracker")).isInstanceOf(SkillMetricsTracker.class);
    }

    @Test
    void 新Bean_SkillDisclosureTool_存在且类型正确() {
        assertThat(ctx.containsBean("skillDisclosureTool")).isTrue();
        assertThat(ctx.getBean("skillDisclosureTool")).isInstanceOf(SkillDisclosureTool.class);
    }

    @Test
    void 新Bean_SkillRegistry_存在且类型正确() {
        assertThat(ctx.containsBean("skillRegistry")).isTrue();
        assertThat(ctx.getBean("skillRegistry")).isInstanceOf(SkillRegistry.class);
    }

    // ─────────────────────────────────────────────
    //  旧 Bean 不存在（已删除的类，Bean 名称不应存在）
    // ─────────────────────────────────────────────

    @Test
    void 旧Bean_SkillLifecycleManager_不存在() {
        assertThat(ctx.containsBean("skillLifecycleManager")).isFalse();
    }

    @Test
    void 旧Bean_MemoryAccessEnforcer_不存在() {
        assertThat(ctx.containsBean("memoryAccessEnforcer")).isFalse();
    }

    @Test
    void 旧Bean_SkillActionDispatcher_不存在() {
        assertThat(ctx.containsBean("skillActionDispatcher")).isFalse();
    }

    @Test
    void 旧Bean_VariableResolver_不存在() {
        assertThat(ctx.containsBean("variableResolver")).isFalse();
    }

    @Test
    void 旧Bean_DangerousCommandDetector_不存在() {
        assertThat(ctx.containsBean("dangerousCommandDetector")).isFalse();
    }

    @Test
    void 旧Bean_HttpActionExecutor_不存在() {
        assertThat(ctx.containsBean("httpActionExecutor")).isFalse();
    }

    @Test
    void 旧Bean_ShellActionExecutor_不存在() {
        assertThat(ctx.containsBean("shellActionExecutor")).isFalse();
    }

    @Test
    void 旧Bean_ChainActionExecutor_不存在() {
        assertThat(ctx.containsBean("chainActionExecutor")).isFalse();
    }

    @Test
    void 旧Bean_TemplateActionExecutor_不存在() {
        assertThat(ctx.containsBean("templateActionExecutor")).isFalse();
    }
}
