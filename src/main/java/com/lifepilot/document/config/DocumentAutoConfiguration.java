package com.lifepilot.document.config;

import com.lifepilot.document.generator.DocumentGenerator;
import com.lifepilot.document.generator.ExcelGenerator;
import com.lifepilot.document.generator.MarkdownToDocxGenerator;
import com.lifepilot.document.generator.OutlineToPptxGenerator;
import com.lifepilot.document.generator.PowerpointGenerator;
import com.lifepilot.document.generator.StructuredDataToXlsxGenerator;
import com.lifepilot.document.repository.DocumentRepository;
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
 * 文档工作空间自动配置 —— Phase 2B 装配 3 个 create_* 工具。
 *
 * <p>Phase 0 曾有同名 AutoConfiguration（文档解析合并到 file.read 后清理），
 * Phase 2A 重建以装配 docx 工具，Phase 2B 扩展 xlsx / pptx。</p>
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
    @ConditionalOnBean({DocumentRepository.class, AttachmentRepository.class})
    DocumentCreateDocxToolExecutor documentCreateDocxToolExecutor(
            DocumentGenerator markdownToDocxGenerator,
            DocumentRepository documentRepository,
            AttachmentRepository attachmentRepository,
            DocumentProperties properties) {
        return new DocumentCreateDocxToolExecutor(
                markdownToDocxGenerator,
                documentRepository,
                attachmentRepository,
                properties.getStorageDir());
    }

    @Bean
    @ConditionalOnBean({DocumentRepository.class, AttachmentRepository.class})
    DocumentCreateXlsxToolExecutor documentCreateXlsxToolExecutor(
            ExcelGenerator structuredDataToXlsxGenerator,
            DocumentRepository documentRepository,
            AttachmentRepository attachmentRepository,
            DocumentProperties properties) {
        return new DocumentCreateXlsxToolExecutor(
                structuredDataToXlsxGenerator,
                documentRepository,
                attachmentRepository,
                properties.getStorageDir());
    }

    @Bean
    @ConditionalOnBean({DocumentRepository.class, AttachmentRepository.class})
    DocumentCreatePptxToolExecutor documentCreatePptxToolExecutor(
            PowerpointGenerator outlineToPptxGenerator,
            DocumentRepository documentRepository,
            AttachmentRepository attachmentRepository,
            DocumentProperties properties) {
        return new DocumentCreatePptxToolExecutor(
                outlineToPptxGenerator,
                documentRepository,
                attachmentRepository,
                properties.getStorageDir());
    }

    // ===== 工具提供者 + 3 个 BuiltinTool Bean =====

    @Bean
    @ConditionalOnBean({
            DocumentCreateDocxToolExecutor.class,
            DocumentCreateXlsxToolExecutor.class,
            DocumentCreatePptxToolExecutor.class
    })
    DocumentToolProvider documentToolProvider(
            DocumentCreateDocxToolExecutor docxExecutor,
            DocumentCreateXlsxToolExecutor xlsxExecutor,
            DocumentCreatePptxToolExecutor pptxExecutor) {
        return new DocumentToolProvider(docxExecutor, xlsxExecutor, pptxExecutor);
    }

    @Bean
    @ConditionalOnBean(DocumentToolProvider.class)
    BuiltinTool documentCreateDocxTool(DocumentToolProvider provider) {
        return selectTool(provider, "document.create_docx");
    }

    @Bean
    @ConditionalOnBean(DocumentToolProvider.class)
    BuiltinTool documentCreateXlsxTool(DocumentToolProvider provider) {
        return selectTool(provider, "document.create_xlsx");
    }

    @Bean
    @ConditionalOnBean(DocumentToolProvider.class)
    BuiltinTool documentCreatePptxTool(DocumentToolProvider provider) {
        return selectTool(provider, "document.create_pptx");
    }

    /**
     * 按 tool id 从 provider 的缓存中选出对应 BuiltinTool。
     *
     * <p>provider 在构造时一次性构建 3 个工具并缓存到 {@code toolsById}，
     * 此处每个 Bean 仅做一次 O(1) 查询，消除 Phase 2A 的 O(3×3) 重复构造开销。
     * 取代 Phase 2A 的 "tools.size() != 1 warn"（Phase 2B 3 tools 会误报）。
     * 找不到即抛异常，说明 provider 装配不完整。</p>
     */
    private BuiltinTool selectTool(DocumentToolProvider provider, String toolId) {
        var tool = provider.getTool(toolId);
        if (tool == null) {
            throw new IllegalStateException("DocumentToolProvider 未返回 " + toolId + " 工具");
        }
        log.info("已装配 document 工具：id={}", tool.id());
        return tool;
    }
}
