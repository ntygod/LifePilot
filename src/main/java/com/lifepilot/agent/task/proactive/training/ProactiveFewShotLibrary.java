package com.lifepilot.agent.task.proactive.training;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 主动引擎 few-shot 样例库 — 本地文件持久化。
 *
 * <p>设计决策：不新建数据库表。few-shot 库是**开发期工具属性**的中间产物，
 * 单个文件（{@code {cacheDir}/{userId}.json}）足够，避免引入迁移/索引/权限等复杂度。
 * 本地个人助手场景 99% 是单用户 → 文件数量可控。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public class ProactiveFewShotLibrary {

    private static final Logger log = LoggerFactory.getLogger(ProactiveFewShotLibrary.class);
    private static final TypeReference<List<ProactiveFewShotSample>> SAMPLE_LIST_TYPE =
            new TypeReference<>() {};

    private final Path cacheDir;
    private final ObjectMapper mapper;

    public ProactiveFewShotLibrary(Path cacheDir) {
        this.cacheDir = Objects.requireNonNull(cacheDir, "cacheDir 不能为空");
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    /** 保存该用户的全部样例到 {@code {cacheDir}/{userId}.json}。 */
    public void save(String userId, List<ProactiveFewShotSample> samples) {
        if (userId == null || userId.isBlank()) {
            log.debug("few-shot 库: userId 为空，跳过保存");
            return;
        }
        try {
            Files.createDirectories(cacheDir);
            Path file = userFile(userId);
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), samples);
            log.debug("few-shot 库: 已保存 user={}, samples={}, path={}",
                    userId, samples.size(), file);
        } catch (IOException e) {
            log.warn("few-shot 库: 保存失败 user={}, error={}", userId, e.getMessage());
        }
    }

    /**
     * 读取指定用户的样例。
     *
     * @param userId        用户 ID
     * @param candidateType 可选过滤（null 则返回全部类型）
     * @param limit         最多返回多少条
     * @return 读取结果；文件不存在或读取异常返回空列表
     */
    public List<ProactiveFewShotSample> getSamples(String userId,
                                                    @Nullable String candidateType,
                                                    int limit) {
        if (userId == null || userId.isBlank() || limit <= 0) return List.of();
        Path file = userFile(userId);
        if (!Files.exists(file)) return List.of();
        try {
            List<ProactiveFewShotSample> all = mapper.readValue(file.toFile(), SAMPLE_LIST_TYPE);
            Stream<ProactiveFewShotSample> stream = all.stream();
            if (candidateType != null && !candidateType.isBlank()) {
                stream = stream.filter(s -> candidateType.equalsIgnoreCase(s.candidateType()));
            }
            return stream.limit(limit).toList();
        } catch (IOException e) {
            log.warn("few-shot 库: 读取失败 user={}, error={}", userId, e.getMessage());
            return List.of();
        }
    }

    /** 仅供测试/诊断使用：返回文件路径。 */
    public Path userFile(String userId) {
        // 防御：userId 可能含非法文件名字符，简单转义
        String safe = userId.replaceAll("[^a-zA-Z0-9_.\\-]", "_");
        return cacheDir.resolve(safe + ".json");
    }

    /** 获取缓存根目录（供诊断使用）。 */
    public Path cacheDir() { return cacheDir; }

    /**
     * 便捷构造：默认缓存目录 {@code target/cache/proactive-few-shot/}。
     */
    public static ProactiveFewShotLibrary withDefaultCacheDir() {
        return new ProactiveFewShotLibrary(Paths.get("target", "cache", "proactive-few-shot"));
    }
}
