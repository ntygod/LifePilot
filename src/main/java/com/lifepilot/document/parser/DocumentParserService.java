package com.lifepilot.document.parser;

import com.lifepilot.knowledge.parser.DocumentParseException;
import com.lifepilot.knowledge.parser.DocumentParser;
import com.lifepilot.knowledge.parser.ExcelParser;
import com.lifepilot.knowledge.parser.MarkdownParser;
import com.lifepilot.knowledge.parser.ParseResult;
import com.lifepilot.knowledge.parser.PdfParser;
import com.lifepilot.knowledge.parser.PlainTextParser;
import com.lifepilot.knowledge.parser.PowerpointParser;
import com.lifepilot.knowledge.parser.WordParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 文档解析路由 facade —— 按文件扩展名分发到对应的 {@link DocumentParser}。
 *
 * <p>持有所有可用 parser（由 Spring 注入），对外提供统一的 {@link #parse(Path)} 入口。
 * Knowledge 模块的 parser 实现保持原位，本 service 仅做路由,
 * 命中策略为 {@link DocumentParser#canParse(Path)} 顺序匹配,首个命中即停止。
 *
 * @author zsg
 * @since 2026-04-20
 */
public class DocumentParserService {

    private static final Logger log = LoggerFactory.getLogger(DocumentParserService.class);

    private final List<DocumentParser> parsers;

    public DocumentParserService(List<DocumentParser> parsers) {
        this.parsers = List.copyOf(parsers);
        log.info("文档解析服务已就绪：parsers={}", this.parsers.size());
    }

    /**
     * 使用默认 parser 集合构建服务。
     *
     * <p>parser 实现均无状态、无参构造, 直接 new 即可, 避免跨模块 Bean 依赖顺序问题。
     * 顺序决定 {@link DocumentParser#canParse} 的命中先后, 目前为
     * Markdown → PlainText → Word → Pdf → Excel → Powerpoint。</p>
     *
     * <p>生产 (FileToolProvider) 与测试 (FileReadToolExecutor 便捷构造器) 共用本入口,
     * 避免 parser 列表漂移。</p>
     */
    public static DocumentParserService buildDefault() {
        return new DocumentParserService(List.of(
                new MarkdownParser(),
                new PlainTextParser(),
                new WordParser(),
                new PdfParser(),
                new ExcelParser(),
                new PowerpointParser()
        ));
    }

    /**
     * 解析指定文件。按 parser 注入顺序匹配 {@link DocumentParser#canParse(Path)},命中第一个即停止。
     *
     * @param filePath 待解析文件路径
     * @return 解析结果
     * @throws DocumentParseException 无可用 parser 或解析失败
     */
    public ParseResult parse(Path filePath) throws DocumentParseException {
        return findParser(filePath)
                .orElseThrow(() -> new DocumentParseException(
                        "不支持的文档类型：" + filePath.getFileName(),
                        DocumentParseException.Phase.FORMAT_DECODE,
                        filePath.toString()))
                .parse(filePath);
    }

    /**
     * 检查文件是否有可用的解析器。
     *
     * @param filePath 待检查文件路径
     * @return 存在能处理的 parser 时返回 true
     */
    public boolean supports(Path filePath) {
        return findParser(filePath).isPresent();
    }

    private Optional<DocumentParser> findParser(Path filePath) {
        return parsers.stream().filter(p -> p.canParse(filePath)).findFirst();
    }
}
