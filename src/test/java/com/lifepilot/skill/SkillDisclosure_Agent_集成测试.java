package com.lifepilot.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.datastore.DataStoreManager;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.observability.guardrail.GuardrailEngine;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.skill.config.SkillAutoConfiguration;
import com.lifepilot.skill.disclosure.SkillDisclosureTool;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.ContextConfiguration;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Skill 渐进式披露 — Agent 集成测试。
 *
 * <p>验证 Spring Context 加载成功、新工具注册到 DynamicToolRegistry、
 * 旧 skills 工具不存在。仅导入 SkillAutoConfiguration，
 * 手动提供 DynamicToolRegistry 避免 ToolAutoConfiguration 的深层依赖。</p>
 *
 * @author zsg
 * @since 2026-03-19
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = SkillDisclosure_Agent_集成测试.TestConfig.class)
class SkillDisclosure_Agent_集成测试 {

    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void configure(@NonNull DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        registry.add("spring.datasource.url",
                () -> "jdbc:sqlite:" + Path.of(tmpDir, "skill-disclosure-it-" + DB_ID + ".db")
                        .toString().replace("\\", "/"));
        registry.add("lifepilot.skills.enabled", () -> "true");
        registry.add("lifepilot.skills.directory",
                () -> Path.of(tmpDir, "skill-disclosure-it-skills-" + DB_ID)
                        .toString().replace("\\", "/"));
    }

    /**
     * 测试配置 — 仅导入 SkillAutoConfiguration，手动提供 DynamicToolRegistry。
     *
     * <p>避免导入 ToolAutoConfiguration 带来的 MetaProperties 等深层依赖，
     * 聚焦验证 Skill 模块的 Bean 注册和工具注册逻辑。</p>
     */
    @Configuration
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            SkillAutoConfiguration.class
    })
    static class TestConfig {

        @Bean
        DynamicToolRegistry dynamicToolRegistry(GuardrailEngine guardrailEngine,
                                                ApplicationEventPublisher eventPublisher) {
            return new DynamicToolRegistry(guardrailEngine, eventPublisher);
        }

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

        @Bean
        DataStoreManager dataStoreManager() {
            return mock(DataStoreManager.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        SharedScheduler sharedScheduler() {
            return mock(SharedScheduler.class, invocation -> {
                if (invocation.getMethod().getName().equals("debounce")
                        || invocation.getMethod().getName().equals("cleanup")
                        || invocation.getMethod().getName().equals("heartbeat")) {
                    return Executors.newSingleThreadScheduledExecutor();
                }
                return null;
            });
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
    void 新Bean_SkillDisclosureTool_存在() {
        assertThat(ctx.containsBean("skillDisclosureTool")).isTrue();
        assertThat(ctx.getBean("skillDisclosureTool")).isInstanceOf(SkillDisclosureTool.class);
    }

    @Test
    void 新Bean_SkillRegistry_存在() {
        assertThat(ctx.containsBean("skillRegistry")).isTrue();
        assertThat(ctx.getBean("skillRegistry")).isInstanceOf(SkillRegistry.class);
    }

    // ─────────────────────────────────────────────
    //  旧 Bean 不存在
    // ─────────────────────────────────────────────

    @Test
    void 旧Bean_SkillToToolBridge_不存在() {
        assertThat(ctx.containsBean("skillToToolBridge")).isFalse();
    }

    // ─────────────────────────────────────────────
    //  DynamicToolRegistry 工具验证
    // ─────────────────────────────────────────────

    @Test
    void DynamicToolRegistry_registerTools后包含load_skill工具() {
        // @ContextConfiguration 不触发 ApplicationReadyEvent，手动调用 registerTools
        var disclosureTool = ctx.getBean(SkillDisclosureTool.class);
        disclosureTool.registerTools();

        var registry = ctx.getBean(DynamicToolRegistry.class);
        assertThat(registry.resolve("load_skill")).isPresent();
    }

    @Test
    void DynamicToolRegistry_不包含旧skills工具() {
        var registry = ctx.getBean(DynamicToolRegistry.class);
        assertThat(registry.resolve("skills")).isEmpty();
    }
}
