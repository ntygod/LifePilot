package com.lifepilot.knowledge.parser;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 文档格式检测器 — 根据文件扩展名路由到对应的解析器。
 *
 * @author zsg
 * @since 2026-02-25
 */
public class FormatDetector {

    private final List<DocumentParser> parsers;

    /**
     * 构造格式检测器。
     *
     * @param parsers 可用的解析器列表
     */
    public FormatDetector(List<DocumentParser> parsers) {
        this.parsers = List.copyOf(parsers);
    }

    /**
     * 根据文件扩展名查找匹配的解析器。
     *
     * @param filePath 文件路径
     * @return 匹配的解析器，不支持的扩展名返回 empty
     */
    public Optional<DocumentParser> detect(Path filePath) {
        return parsers.stream()
                .filter(p -> p.canParse(filePath))
                .findFirst();
    }

    /**
     * 返回所有支持的扩展名。
     *
     * @return 扩展名列表
     */
    public List<String> supportedExtensions() {
        return parsers.stream()
                .flatMap(p -> p.supportedExtensions().stream())
                .toList();
    }
}
