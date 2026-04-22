package com.lifepilot.document.config;

import com.lifepilot.document.repository.DocumentVersionRepository;
import com.lifepilot.document.repository.SessionDocumentRepository;
import com.lifepilot.document.patch.xlsx.XlsxDiffBuilder;
import com.lifepilot.document.patch.xlsx.XlsxPatchEngine;
import com.lifepilot.document.tool.DocumentEditActionDispatchExecutor;
import com.lifepilot.document.tool.DocumentEditToolProvider;
import com.lifepilot.document.tool.DocumentToolProvider;
import com.lifepilot.document.version.DocumentVersionService;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.tool.BuiltinTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DocumentAutoConfiguration 装配契约测试 —— Phase 3A 扩展版。
 *
 * <p>断言：P3 Bean（DocumentVersionService / DocxPatchEngine / DocumentEditToolProvider 等）
 * 都能装配，且 {@code document.edit} 作为独立 BuiltinTool 出现在上下文中，
 * 同时 Phase 2B {@code document.create} 不受影响。</p>
 *
 * <p>通过 {@link ApplicationContextRunner} + 真实 DataSource/Flyway 启 SQLite 内存库，
 * 手动注入 {@code @Repository} 标注类（因 runner 无组件扫描）。</p>
 *
 * @author zsg
 * @since 2026-04-21
 */
class DocumentAutoConfiguration_装配测试 {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    DataSourceTransactionManagerAutoConfiguration.class,
                    JdbcTemplateAutoConfiguration.class,
                    FlywayAutoConfiguration.class,
                    DocumentAutoConfiguration.class))
            .withUserConfiguration(TestRepositoryConfig.class)
            .withPropertyValues(
                    "spring.datasource.url=jdbc:sqlite::memory:",
                    "spring.datasource.driver-class-name=org.sqlite.JDBC",
                    "spring.flyway.locations=classpath:db/migration",
                    "lifepilot.document.enabled=true",
                    "lifepilot.document.storage-dir=${java.io.tmpdir}/zhiwei-test-doc"
            );

    @Test
    @DisplayName("P3 Bean 全装配 + document.edit 工具注册")
    void 全装配P3链() {
        runner.run(ctx -> {
            // P3 核心 Bean 链
            assertThat(ctx).hasSingleBean(DocumentVersionService.class);
            assertThat(ctx).hasSingleBean(DocumentEditActionDispatchExecutor.class);
            assertThat(ctx).hasSingleBean(DocumentEditToolProvider.class);
            // Phase 2B create provider 仍在
            assertThat(ctx).hasSingleBean(DocumentToolProvider.class);

            // BuiltinTool 工具列表应同时含 document.create 与 document.edit
            var tools = ctx.getBeansOfType(BuiltinTool.class);
            assertThat(tools.values())
                    .extracting(BuiltinTool::id)
                    .contains("document.create", "document.edit");
        });
    }

    @Test
    @DisplayName("禁用开关 lifepilot.document.enabled=false 整模块不装配")
    void 禁用开关不装配P3链() {
        runner.withPropertyValues("lifepilot.document.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(DocumentVersionService.class);
            assertThat(ctx).doesNotHaveBean(DocumentEditToolProvider.class);
        });
    }

    @Test
    @DisplayName("Phase 3B：xlsx patchEngine 和 diffBuilder 装配")
    void xlsx_engine_和_diffBuilder_装配() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(XlsxPatchEngine.class);
            assertThat(ctx).hasSingleBean(XlsxDiffBuilder.class);
        });
    }

    @Test
    @DisplayName("Phase 3B：DocumentVersionService 在 xlsx Bean 存在前提下仍单例装配")
    void documentVersionService_包含xlsx依赖() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(DocumentVersionService.class);
        });
    }

    /**
     * 补齐 @Repository 标注类 Bean —— runner 默认无组件扫描，需手动注入。
     *
     * <p>DocumentVersionService 依赖 SessionDocumentRepository / DocumentVersionRepository /
     * AttachmentRepository；三者均由 JdbcTemplate 单参构造。</p>
     *
     * <p>P3A Critical 修复后 documentVersionService 还需要 MetaProperties 以构造
     * PathSecurityChecker，这里通过 {@link EnableConfigurationProperties} 暴露默认值。</p>
     */
    @Configuration
    @EnableConfigurationProperties(MetaProperties.class)
    static class TestRepositoryConfig {
        @Bean
        AttachmentRepository attachmentRepository(JdbcTemplate jdbc) {
            return new AttachmentRepository(jdbc);
        }

        @Bean
        SessionDocumentRepository sessionDocumentRepository(JdbcTemplate jdbc) {
            return new SessionDocumentRepository(jdbc);
        }

        @Bean
        DocumentVersionRepository documentVersionRepository(JdbcTemplate jdbc) {
            return new DocumentVersionRepository(jdbc);
        }
    }
}
