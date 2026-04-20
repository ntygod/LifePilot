package com.lifepilot.document.config;

import com.lifepilot.document.parser.DocumentParserService;
import com.lifepilot.document.tool.DocumentParseToolExecutor;
import com.lifepilot.document.tool.DocumentToolProvider;
import com.lifepilot.interaction.web.repository.AttachmentRepository;
import com.lifepilot.knowledge.parser.DocumentParser;
import com.lifepilot.knowledge.parser.MarkdownParser;
import com.lifepilot.knowledge.parser.PdfParser;
import com.lifepilot.knowledge.parser.PlainTextParser;
import com.lifepilot.knowledge.parser.WordParser;
import com.lifepilot.tool.BuiltinTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * 文档工作空间自动配置。
 *
 * <p>装配文档领域的工具链：</p>
 * <ol>
 *   <li>复用 knowledge 模块的 4 个 {@link DocumentParser} 实现（全部无状态、无参构造），
 *       组装 {@link DocumentParserService} 作为路由 facade。</li>
 *   <li>构造 {@link DocumentParseToolExecutor}，依赖 {@link AttachmentRepository}。</li>
 *   <li>通过 {@link DocumentToolProvider} 构建 {@link BuiltinTool}（当前仅 document.parse），
 *       暴露为 Bean，由 {@code BuiltinToolRegistrar} 在 {@code ApplicationReadyEvent} 时
 *       统一注册到 {@code DynamicToolRegistry}。</li>
 * </ol>
 *
 * <p>通过 {@code lifepilot.document.enabled} 启停（默认开启）。
 * 注意：不依赖 knowledge 模块的 parser Bean（某些测试 profile 下
 * {@code lifepilot.knowledge.enabled=false} 时 knowledge parser Bean 不存在，
 * 注入 {@code List<DocumentParser>} 会得到空列表），直接 new 各 parser 实现。</p>
 *
 * @author zsg
 * @since 2026-04-20
 */
@AutoConfiguration
@ConditionalOnProperty(name = "lifepilot.document.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(DocumentProperties.class)
public class DocumentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DocumentAutoConfiguration.class);

    /**
     * 文档解析路由 facade —— 组合 knowledge 模块的 4 个 parser 实现。
     *
     * <p>parser 实现均为无状态、无依赖，直接 new 以避免跨模块 Bean 依赖顺序问题。
     * 顺序：MarkdownParser → PlainTextParser → WordParser → PdfParser，
     * DocumentParserService 内部按顺序命中 {@link DocumentParser#canParse}。</p>
     */
    @Bean
    DocumentParserService documentParserService() {
        List<DocumentParser> parsers = List.of(
                new MarkdownParser(),
                new PlainTextParser(),
                new WordParser(),
                new PdfParser());
        return new DocumentParserService(parsers);
    }

    /**
     * document.parse 工具的执行器 —— 依赖 AttachmentRepository 按附件 ID 解析路径。
     *
     * <p>AttachmentRepository 来自 interaction/web 模块，Web 未启用时本 Bean 不注册,
     * DocumentToolProvider 随之不注册，从而整条文档工具链按需启停。</p>
     *
     * <p>默认最大字符数取自 {@link DocumentProperties#getDefaultMaxChars()}，
     * 可通过 {@code lifepilot.document.default-max-chars} 覆盖。</p>
     */
    @Bean
    @ConditionalOnBean(AttachmentRepository.class)
    DocumentParseToolExecutor documentParseToolExecutor(DocumentParserService parserService,
                                                        AttachmentRepository attachmentRepository,
                                                        DocumentProperties properties) {
        return new DocumentParseToolExecutor(parserService, attachmentRepository, properties.getDefaultMaxChars());
    }

    /** 文档工具提供者。 */
    @Bean
    @ConditionalOnBean(DocumentParseToolExecutor.class)
    DocumentToolProvider documentToolProvider(DocumentParseToolExecutor parseExecutor) {
        return new DocumentToolProvider(parseExecutor);
    }

    /**
     * 暴露 document.parse 为 {@link BuiltinTool} Bean。
     *
     * <p>Spring 会将所有 BuiltinTool Bean 自动收集到 {@code List<BuiltinTool>}，
     * 注入到 {@code BuiltinToolRegistrar} 并在 ApplicationReadyEvent 时批量注册到
     * {@code DynamicToolRegistry}。参考 {@code MultiAgentAutoConfiguration.spawnWorkersTool}
     * 的注册路径。</p>
     *
     * <p>当 DocumentToolProvider 新增更多工具时，追加同类型 @Bean 方法即可。</p>
     */
    @Bean
    @ConditionalOnBean(DocumentToolProvider.class)
    BuiltinTool documentParseTool(DocumentToolProvider provider) {
        var tools = provider.buildDocumentTools();
        if (tools.isEmpty()) {
            throw new IllegalStateException("DocumentToolProvider 未返回任何工具");
        }
        if (tools.size() != 1) {
            log.warn("DocumentToolProvider 目前预期返回 1 个工具，实际 count={}；仅暴露首个为 Bean，其余需补充 @Bean 方法",
                    tools.size());
        }
        var tool = tools.get(0);
        log.info("已装配 document 工具：id={}", tool.id());
        return tool;
    }
}
