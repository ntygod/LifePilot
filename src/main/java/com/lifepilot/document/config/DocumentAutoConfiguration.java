package com.lifepilot.document.config;

import com.lifepilot.document.generator.DocumentGenerator;
import com.lifepilot.document.generator.ExcelGenerator;
import com.lifepilot.document.generator.MarkdownToDocxGenerator;
import com.lifepilot.document.generator.OutlineToPptxGenerator;
import com.lifepilot.document.generator.PowerpointGenerator;
import com.lifepilot.document.generator.StructuredDataToXlsxGenerator;
import com.lifepilot.document.repository.SessionDocumentRepository;
import com.lifepilot.document.tool.DocumentCreateActionDispatchExecutor;
import com.lifepilot.document.tool.DocumentCreateDocxToolExecutor;
import com.lifepilot.document.tool.DocumentCreatePptxToolExecutor;
import com.lifepilot.document.tool.DocumentCreateXlsxToolExecutor;
import com.lifepilot.document.tool.DocumentToolProvider;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.tool.BuiltinTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 文档工作空间自动配置 —— Phase 2B refactor：3 个 create_* 工具合并为 document.create + action 路由。
 *
 * <p>装配顺序：3 个生成器 → 3 个底层 executor → dispatcher → provider → 单个 BuiltinTool。
 * 对齐 {@code git.mutate} 模式，减少 LLM 侧 schema 噪声（3 份 → 1 份扁平 schema）。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
@AutoConfiguration
@ConditionalOnProperty(name = "lifepilot.document.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(DocumentProperties.class)
public class DocumentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DocumentAutoConfiguration.class);

    // ===== 生成器 Bean（无依赖，无条件）=====

    @Bean
    DocumentGenerator markdownToDocxGenerator() {
        return new MarkdownToDocxGenerator();
    }

    @Bean
    ExcelGenerator structuredDataToXlsxGenerator() {
        return new StructuredDataToXlsxGenerator();
    }

    @Bean
    PowerpointGenerator outlineToPptxGenerator() {
        return new OutlineToPptxGenerator();
    }

    // ===== 工具执行体 Bean（依赖 Repository）=====

    @Bean
    @ConditionalOnBean({SessionDocumentRepository.class, AttachmentRepository.class})
    DocumentCreateDocxToolExecutor documentCreateDocxToolExecutor(
            DocumentGenerator markdownToDocxGenerator,
            SessionDocumentRepository sessionDocumentRepository,
            AttachmentRepository attachmentRepository,
            DocumentProperties properties) {
        return new DocumentCreateDocxToolExecutor(
                markdownToDocxGenerator,
                sessionDocumentRepository,
                attachmentRepository,
                properties.getStorageDir());
    }

    @Bean
    @ConditionalOnBean({SessionDocumentRepository.class, AttachmentRepository.class})
    DocumentCreateXlsxToolExecutor documentCreateXlsxToolExecutor(
            ExcelGenerator structuredDataToXlsxGenerator,
            SessionDocumentRepository sessionDocumentRepository,
            AttachmentRepository attachmentRepository,
            DocumentProperties properties) {
        return new DocumentCreateXlsxToolExecutor(
                structuredDataToXlsxGenerator,
                sessionDocumentRepository,
                attachmentRepository,
                properties.getStorageDir());
    }

    @Bean
    @ConditionalOnBean({SessionDocumentRepository.class, AttachmentRepository.class})
    DocumentCreatePptxToolExecutor documentCreatePptxToolExecutor(
            PowerpointGenerator outlineToPptxGenerator,
            SessionDocumentRepository sessionDocumentRepository,
            AttachmentRepository attachmentRepository,
            DocumentProperties properties) {
        return new DocumentCreatePptxToolExecutor(
                outlineToPptxGenerator,
                sessionDocumentRepository,
                attachmentRepository,
                properties.getStorageDir());
    }

    // ===== action 路由 dispatcher + 工具提供者 + 单 BuiltinTool =====

    @Bean
    @ConditionalOnBean({
            DocumentCreateDocxToolExecutor.class,
            DocumentCreateXlsxToolExecutor.class,
            DocumentCreatePptxToolExecutor.class
    })
    DocumentCreateActionDispatchExecutor documentCreateActionDispatchExecutor(
            DocumentCreateDocxToolExecutor docxExecutor,
            DocumentCreateXlsxToolExecutor xlsxExecutor,
            DocumentCreatePptxToolExecutor pptxExecutor) {
        return new DocumentCreateActionDispatchExecutor(docxExecutor, xlsxExecutor, pptxExecutor);
    }

    @Bean
    @ConditionalOnBean(DocumentCreateActionDispatchExecutor.class)
    DocumentToolProvider documentToolProvider(DocumentCreateActionDispatchExecutor dispatcher) {
        return new DocumentToolProvider(dispatcher);
    }

    @Bean
    @ConditionalOnBean(DocumentToolProvider.class)
    BuiltinTool documentCreateTool(DocumentToolProvider provider) {
        var tool = provider.buildDocumentTools().get(0);
        log.info("已装配 document 工具：id={}", tool.id());
        return tool;
    }
}
