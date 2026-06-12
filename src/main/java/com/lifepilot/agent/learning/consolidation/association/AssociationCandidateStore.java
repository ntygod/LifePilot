package com.lifepilot.agent.learning.consolidation.association;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * REM 联想候选文件持久化。
 *
 * <p>将 {@link AssociationCandidate} 按日期追加写入 {@code {cacheDir}/{yyyy-MM-dd}.json}。
 * 本地个人助手场景文件数可控；不引入 schema 变更，未来应用到 L3 relations 时再做 replay 工具。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public class AssociationCandidateStore {

    private static final Logger log = LoggerFactory.getLogger(AssociationCandidateStore.class);
    private static final TypeReference<List<AssociationCandidate>> LIST_TYPE = new TypeReference<>() {};

    private final Path cacheDir;
    private final ObjectMapper mapper;

    public AssociationCandidateStore(Path cacheDir) {
        this.cacheDir = Objects.requireNonNull(cacheDir, "cacheDir 不能为空");
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /** 默认目录 {@code target/cache/memory-rem-associations/}。 */
    public static AssociationCandidateStore withDefaultCacheDir() {
        return new AssociationCandidateStore(Paths.get("target", "cache", "memory-rem-associations"));
    }

    /**
     * 追加保存指定日期的候选列表。若文件已存在则合并。
     */
    public void save(LocalDate date, List<AssociationCandidate> candidates) {
        if (date == null || candidates == null || candidates.isEmpty()) return;
        try {
            Files.createDirectories(cacheDir);
            Path file = fileFor(date);
            List<AssociationCandidate> merged = new ArrayList<>(load(date));
            merged.addAll(candidates);
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), merged);
            log.debug("REM 联想: 已保存 date={}, count={}, path={}", date, candidates.size(), file);
        } catch (IOException e) {
            log.warn("REM 联想: 保存失败 date={}, error={}", date, e.getMessage());
        }
    }

    /** 读取指定日期的候选；文件不存在或读取失败返回空列表。 */
    public List<AssociationCandidate> load(LocalDate date) {
        if (date == null) return List.of();
        Path file = fileFor(date);
        if (!Files.exists(file)) return List.of();
        try {
            return mapper.readValue(file.toFile(), LIST_TYPE);
        } catch (IOException e) {
            log.warn("REM 联想: 读取失败 date={}, error={}", date, e.getMessage());
            return List.of();
        }
    }

    public Path fileFor(LocalDate date) {
        return cacheDir.resolve(date.toString() + ".json");
    }

    public Path cacheDir() { return cacheDir; }
}
