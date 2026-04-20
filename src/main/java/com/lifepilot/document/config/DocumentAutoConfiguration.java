package com.lifepilot.document.config;

import com.lifepilot.document.generator.DocumentGenerator;
import com.lifepilot.document.generator.MarkdownToDocxGenerator;
import com.lifepilot.document.repository.DocumentRepository;
import com.lifepilot.document.tool.DocumentCreateDocxToolExecutor;
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
 * 文档工作空间自动配置 —— Phase 2A 重建。
 *
 * <p>Phase 0 曾有同名 AutoConfiguration（文档解析合并到 file.read 后清理），
 * Phase 2A 重建以装配 create_* 工具链条。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
@AutoConfiguration
@ConditionalOnProperty(name = "lifepilot.document.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(DocumentProperties.class)
public class DocumentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DocumentAutoConfiguration.class);

    @Bean
    DocumentGenerator markdownToDocxGenerator() {
        return new MarkdownToDocxGenerator();
    }

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
    @ConditionalOnBean(DocumentCreateDocxToolExecutor.class)
    DocumentToolProvider documentToolProvider(DocumentCreateDocxToolExecutor executor) {
        return new DocumentToolProvider(executor);
    }

    @Bean
    @ConditionalOnBean(DocumentToolProvider.class)
    BuiltinTool documentCreateDocxTool(DocumentToolProvider provider) {
        var tools = provider.buildDocumentTools();
        if (tools.isEmpty()) {
            throw new IllegalStateException("DocumentToolProvider 未返回任何工具");
        }
        if (tools.size() != 1) {
            log.warn("DocumentToolProvider 返回 {} 个工具，Phase 2A 预期 1 个（document.create_docx）", tools.size());
        }
        var tool = tools.get(0);
        log.info("已装配 document 工具：id={}", tool.id());
        return tool;
    }
}
