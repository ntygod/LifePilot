package com.lifepilot.memory.eval.baseline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lifepilot.memory.eval.report.EvalReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 历史基线读写。
 *
 * <p>基线 JSON 文件路径默认 {@code docs/memory-eval/baseline.json}，纳入 git，
 * 每次 merge 到 develop 前由开发者手动更新。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public final class BaselineStore {

    private static final Logger log = LoggerFactory.getLogger(BaselineStore.class);

    private final Path baselinePath;
    private final ObjectMapper objectMapper;

    public BaselineStore(Path baselinePath) {
        this.baselinePath = baselinePath;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT)
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }

    /**
     * 读取基线；不存在或解析失败时返回 null。
     */
    public EvalReport loadOrNull() {
        if (!Files.exists(baselinePath)) {
            log.info("基线文件不存在，视为 NO_BASELINE: path={}", baselinePath);
            return null;
        }
        try {
            return objectMapper.readValue(baselinePath.toFile(), EvalReport.class);
        } catch (IOException e) {
            log.warn("基线读取失败，视为 NO_BASELINE: path={}, error={}",
                    baselinePath, e.getMessage());
            return null;
        }
    }

    /**
     * 写入基线。
     */
    public void save(EvalReport report) {
        try {
            Path parent = baselinePath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            objectMapper.writeValue(baselinePath.toFile(), report);
            log.info("基线已写入: path={}", baselinePath);
        } catch (IOException e) {
            log.error("基线写入失败: path={}", baselinePath, e);
        }
    }

    public Path baselinePath() {
        return baselinePath;
    }
}
