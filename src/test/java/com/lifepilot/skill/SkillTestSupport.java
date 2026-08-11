package com.lifepilot.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.config.path.ZhiweiPaths;
import com.lifepilot.config.threadpool.SharedScheduler;
import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.memory.retrieval.HybridRetriever;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.observability.guardrail.GuardrailEngine;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.skill.config.SkillAutoConfiguration;
import com.lifepilot.skill.install.SkillInstallationRepository;
import com.lifepilot.skill.install.SkillInstaller;
import com.lifepilot.skill.validation.SkillBodyValidator;
import com.lifepilot.skill.validation.SkillDescriptionValidator;
import com.lifepilot.skill.validation.SkillValidator;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.concurrent.Executors;

import static org.mockito.Mockito.mock;

/**
 * Skill 模块集成测试共享配置。
 *
 * <p>提供 SkillAutoConfiguration 所需的外部依赖 Mock，
 * 避免多个测试类各自定义同名 Bean 导致 {@code BeanDefinitionOverrideException}。</p>
 *
 * @author zsg
 * @since 2026-03-20
 */
@TestConfiguration
@ImportAutoConfiguration({
        DataSourceAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        SkillAutoConfiguration.class
})
public class SkillTestSupport {

    @Bean
    DynamicToolRegistry dynamicToolRegistry(ApplicationEventPublisher eventPublisher) {
        return new DynamicToolRegistry(eventPublisher);
    }

    @Bean
    GenerationRouter generationRouter() {
        return mock(GenerationRouter.class);
    }

    @Bean
    EmbeddingRouter embeddingRouter() {
        return mock(EmbeddingRouter.class);
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
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    ZhiweiPaths zhiweiPaths() {
        // 返回 mock ZhiweiPaths，home("skills") 指向临时目录
        ZhiweiPaths paths = mock(ZhiweiPaths.class);
        Path tmpSkills = Path.of(System.getProperty("java.io.tmpdir"), "skill-test-support-skills");
        org.mockito.Mockito.lenient().when(paths.home("skills")).thenReturn(tmpSkills);
        org.mockito.Mockito.lenient().when(paths.home()).thenReturn(tmpSkills.getParent());
        org.mockito.Mockito.lenient().when(paths.workspace()).thenReturn(tmpSkills.getParent().resolve("workspace"));
        return paths;
    }

    @Bean
    SkillInstallationRepository skillInstallationRepository(JdbcTemplate jdbcTemplate) {
        return new SkillInstallationRepository(jdbcTemplate);
    }

    @Bean
    SkillDescriptionValidator skillDescriptionValidator() {
        return new SkillDescriptionValidator();
    }

    @Bean
    SkillBodyValidator skillBodyValidator() {
        return new SkillBodyValidator();
    }

    @Bean
    SkillValidator skillValidator(SkillDescriptionValidator descriptionValidator,
                                  SkillBodyValidator bodyValidator,
                                  DynamicToolRegistry toolRegistry) {
        return new SkillValidator(descriptionValidator, bodyValidator, toolRegistry);
    }

    @Bean
    MarkdownSkillParser markdownSkillParser() {
        return new MarkdownSkillParser();
    }

    @Bean
    SkillInstaller skillInstaller(MarkdownSkillParser parser,
                                  SkillValidator validator,
                                  SkillInstallationRepository repository) {
        return new SkillInstaller(parser, validator, repository);
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
