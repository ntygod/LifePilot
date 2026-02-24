package com.lifepilot.knowledge.parser;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * 文档元数据 record，包含标题、作者、创建时间、页数、字数等信息。
 *
 * @author zsg
 * @since 2026-02-25
 */
public record DocumentMetadata(
        Optional<String> title,
        Optional<String> author,
        Optional<Instant> createdAt,
        Optional<Instant> modifiedAt,
        int pageCount,
        long wordCount,
        Optional<String> language,
        Map<String, String> extraProperties
) {

    /**
     * 创建全空默认值的元数据。
     */
    public static DocumentMetadata empty() {
        return new DocumentMetadata(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                0,
                Optional.empty(),
                Map.of()
        );
    }

    /**
     * 从文件路径创建元数据，使用文件名（不含扩展名）作为标题。
     *
     * @param filePath  文件路径
     * @param wordCount 字数
     */
    public static DocumentMetadata fromFile(Path filePath, long wordCount) {
        String fileName = filePath.getFileName().toString();
        // 去除扩展名
        int dotIndex = fileName.lastIndexOf('.');
        String title = (dotIndex > 0) ? fileName.substring(0, dotIndex) : fileName;

        return new DocumentMetadata(
                Optional.of(title),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                wordCount,
                Optional.empty(),
                Map.of()
        );
    }
}
