package com.lifepilot.memory.eval.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;

/**
 * 生成 JSON 报告。
 *
 * <p>字段使用 {@code snake_case} 与社区基准工具对齐。输出到
 * {@code target/memory-eval/${timestamp}.json}。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public final class JsonReporter {

    private static final Logger log = LoggerFactory.getLogger(JsonReporter.class);
    private static final DateTimeFormatter FILENAME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(java.time.ZoneOffset.UTC);

    private final Path outputDir;
    private final ObjectMapper objectMapper;

    public JsonReporter(Path outputDir) {
        this.outputDir = outputDir;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT)
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    public Path write(EvalReport report) {
        try {
            if (!Files.exists(outputDir)) {
                Files.createDirectories(outputDir);
            }
            String filename = FILENAME_FORMAT.format(report.timestamp()) + ".json";
            Path target = outputDir.resolve(filename);
            objectMapper.writeValue(target.toFile(), report);
            log.info("Memory eval JSON 报告写入: {}", target);
            return target;
        } catch (IOException e) {
            log.error("Memory eval JSON 报告写入失败: dir={}", outputDir, e);
            return null;
        }
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }
}
