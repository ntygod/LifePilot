package com.lifepilot.document.config;

import com.lifepilot.document.generator.DocumentGenerator;
import com.lifepilot.document.generator.ExcelGenerator;
import com.lifepilot.document.generator.MarkdownToDocxGenerator;
import com.lifepilot.document.generator.OutlineToPptxGenerator;
import com.lifepilot.document.generator.PowerpointGenerator;
import com.lifepilot.document.generator.StructuredDataToXlsxGenerator;
import com.lifepilot.document.patch.docx.DocxDiffBuilder;
import com.lifepilot.document.patch.docx.DocxPatchEngine;
import com.lifepilot.document.patch.docx.TextAnchorLocator;
import com.lifepilot.document.patch.xlsx.XlsxDiffBuilder;
import com.lifepilot.document.patch.xlsx.XlsxPatchEngine;
import com.lifepilot.document.repository.DocumentVersionRepository;
import com.lifepilot.document.repository.SessionDocumentRepository;
import com.lifepilot.document.version.DocumentVersionService;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.meta.config.MetaAutoConfiguration;
import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.meta.infra.file.PathSecurityChecker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 文档工作空间自动配置 —— 仅装配底层服务（生成器 / patch 引擎 / 版本服务 / GC）
 * 供 DocumentController（前端 docx 编辑页）和孤儿扫描器使用。
 *
 * <p>{@code document.create} / {@code document.edit} BuiltinTool 已下架（2026-04-25），
 * 工具层 Office 操作改为引导 LLM 走 {@code code} + python 库
 * （python-docx / openpyxl / python-pptx / pypdf）。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
@AutoConfiguration(after = MetaAutoConfiguration.class)
@ConditionalOnProperty(name = "lifepilot.document.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(DocumentProperties.class)
public class DocumentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DocumentAutoConfiguration.class);

    // ===== 生成器 Bean（DocumentVersionService 用）=====

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

    // ===== docx / xlsx 编辑引擎（DocumentVersionService 用）=====

    @Bean
    TextAnchorLocator textAnchorLocator() {
        return new TextAnchorLocator();
    }

    @Bean
    @ConditionalOnBean(TextAnchorLocator.class)
    DocxPatchEngine docxPatchEngine(TextAnchorLocator locator) {
        return new DocxPatchEngine(locator);
    }

    @Bean
    DocxDiffBuilder docxDiffBuilder() {
        return new DocxDiffBuilder();
    }

    @Bean
    XlsxPatchEngine xlsxPatchEngine() {
        return new XlsxPatchEngine();
    }

    @Bean
    XlsxDiffBuilder xlsxDiffBuilder() {
        return new XlsxDiffBuilder();
    }

    // ===== 版本服务（DocumentController 与孤儿扫描器用）=====

    @Bean
    @ConditionalOnProperty(name = "lifepilot.meta.enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnBean({
            SessionDocumentRepository.class,
            DocumentVersionRepository.class,
            AttachmentRepository.class
    })
    DocumentVersionService documentVersionService(
            SessionDocumentRepository documentRepository,
            DocumentVersionRepository versionRepository,
            AttachmentRepository attachmentRepository,
            DocxPatchEngine docxPatchEngine,
            DocxDiffBuilder docxDiffBuilder,
            XlsxPatchEngine xlsxPatchEngine,
            XlsxDiffBuilder xlsxDiffBuilder,
            DocumentProperties properties,
            MetaProperties metaProperties,
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        var pathSecurityChecker = new PathSecurityChecker(metaProperties.getInfra().getFile());
        var repositories = new DocumentVersionService.DocumentRepositories(
                documentRepository, versionRepository, attachmentRepository);
        var engines = new DocumentVersionService.PatchEngines(
                docxPatchEngine, docxDiffBuilder, xlsxPatchEngine, xlsxDiffBuilder);
        var service = new DocumentVersionService(
                repositories, engines, properties.getStorageDir(), pathSecurityChecker,
                transactionManager);
        service.setMaxFileSize(properties.getMaxFileSize());
        return service;
    }

    @Bean
    @ConditionalOnBean({DocumentVersionService.class, io.micrometer.core.instrument.MeterRegistry.class})
    org.springframework.boot.ApplicationRunner documentVersionServiceMetricsBinder(
            DocumentVersionService service,
            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        return args -> {
            service.setMeterRegistry(meterRegistry);
            log.info("DocumentVersionService 指标已挂载：document.patch.count / document.patch.duration");
        };
    }

    /**
     * 文档 GC：定时扫描 storageDir 删除 DB 未引用的孤儿文件。
     * gcIntervalMinutes ≤ 0 时禁用调度。
     */
    @Bean
    @ConditionalOnBean({SessionDocumentRepository.class, DocumentVersionRepository.class,
            com.lifepilot.config.threadpool.SharedScheduler.class})
    com.lifepilot.document.version.DocumentGarbageCollector documentGarbageCollector(
            SessionDocumentRepository documentRepository,
            DocumentVersionRepository versionRepository,
            DocumentProperties properties,
            com.lifepilot.config.threadpool.SharedScheduler sharedScheduler) {
        var gc = new com.lifepilot.document.version.DocumentGarbageCollector(
                documentRepository, versionRepository,
                properties.getStorageDir(), properties.getWorkingRetentionDays());
        int intervalMin = properties.getGcIntervalMinutes();
        if (intervalMin > 0) {
            sharedScheduler.cleanup().scheduleAtFixedRate(
                    () -> {
                        try { gc.runOnce(); }
                        catch (RuntimeException e) { log.warn("文档 GC 任务异常", e); }
                    },
                    intervalMin, intervalMin, java.util.concurrent.TimeUnit.MINUTES);
            log.info("文档 GC 定时任务已启动，间隔 {} 分钟", intervalMin);
        }
        return gc;
    }
}
