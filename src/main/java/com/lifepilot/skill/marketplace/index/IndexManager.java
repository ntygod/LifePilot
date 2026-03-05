package com.lifepilot.skill.marketplace.index;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.skill.marketplace.config.MarketplaceProperties;
import com.lifepilot.skill.marketplace.install.InstalledSkillRepository;
import com.lifepilot.skill.marketplace.model.InstalledSkill;
import com.lifepilot.skill.marketplace.model.SkillPackage;
import com.lifepilot.skill.marketplace.version.VersionResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 索引管理器 — 负责从远程索引源获取 JSON 索引并缓存到本地。
 *
 * <p>核心职责：
 * <ul>
 *   <li>从配置的索引源 URL 获取 JSON 索引文件</li>
 *   <li>解析并持久化到 marketplace_index_cache 表</li>
 *   <li>多索引源合并（相同 id 保留版本更高的）</li>
 *   <li>缓存过期判断与后台刷新</li>
 *   <li>用已安装状态丰富 SkillPackage</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-03-05
 */
public class IndexManager {

    private static final Logger log = LoggerFactory.getLogger(IndexManager.class);

    private static final TypeReference<List<SkillPackage>> PACKAGE_LIST_TYPE = new TypeReference<>() {};

    private final MarketplaceProperties properties;
    private final IndexRepository indexRepository;
    private final InstalledSkillRepository installedSkillRepository;
    private final VersionResolver versionResolver;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    /**
     * 构造索引管理器。
     *
     * @param properties              市场配置属性
     * @param indexRepository          索引缓存 DAO
     * @param installedSkillRepository 已安装 Skill DAO
     * @param versionResolver          版本解析器
     * @param restClientBuilder        RestClient 构建器
     */
    public IndexManager(MarketplaceProperties properties,
                        IndexRepository indexRepository,
                        InstalledSkillRepository installedSkillRepository,
                        VersionResolver versionResolver,
                        RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.indexRepository = indexRepository;
        this.installedSkillRepository = installedSkillRepository;
        this.versionResolver = versionResolver;
        this.restClient = restClientBuilder.build();
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * 刷新所有索引源 — 遍历配置的索引源 URL，HTTP GET 获取 JSON 并持久化。
     *
     * <p>单个索引源获取失败时记录警告日志，不影响其他索引源的刷新。</p>
     *
     * @return 成功刷新的索引源数量
     */
    public int refreshAll() {
        List<String> sources = properties.getIndexSources();
        if (sources == null || sources.isEmpty()) {
            log.warn("未配置索引源，跳过刷新");
            return 0;
        }

        int successCount = 0;
        for (String sourceUrl : sources) {
            try {
                String json = restClient.get()
                        .uri(sourceUrl)
                        .retrieve()
                        .body(String.class);

                if (json == null || json.isBlank()) {
                    log.warn("索引源返回空内容: url={}", sourceUrl);
                    continue;
                }

                // 验证 JSON 可解析为 SkillPackage 列表
                objectMapper.readValue(json, PACKAGE_LIST_TYPE);

                indexRepository.save(sourceUrl, json);
                successCount++;
                log.info("索引刷新成功: url={}", sourceUrl);
            } catch (Exception e) {
                log.warn("索引刷新失败: url={}, 原因={}", sourceUrl, e.getMessage());
            }
        }

        log.info("索引刷新完成: 成功={}/{}", successCount, sources.size());
        return successCount;
    }

    /**
     * 获取所有可用 SkillPackage — 从缓存读取并合并多源，附加已安装状态。
     *
     * <p>如果缓存过期则触发后台刷新。相同 id 的包保留版本号更高的。</p>
     *
     * @return 合并后的 SkillPackage 列表
     */
    public List<SkillPackage> getPackages() {
        List<IndexRepository.IndexCacheEntry> cacheEntries = indexRepository.findAll();

        // 检查缓存是否过期
        if (isCacheExpired(cacheEntries)) {
            log.info("索引缓存已过期，触发后台刷新");
            refreshAll();
            cacheEntries = indexRepository.findAll();
        }

        // 解析并合并所有索引源的包
        Map<String, SkillPackage> mergedPackages = new LinkedHashMap<>();
        for (var entry : cacheEntries) {
            try {
                List<SkillPackage> packages = objectMapper.readValue(entry.indexJson(), PACKAGE_LIST_TYPE);
                for (SkillPackage pkg : packages) {
                    mergedPackages.merge(pkg.id(), pkg, (existing, incoming) -> {
                        try {
                            return versionResolver.compareSemVer(incoming.version(), existing.version()) > 0
                                    ? incoming : existing;
                        } catch (IllegalArgumentException e) {
                            // 版本格式不合法，保留已有的
                            return existing;
                        }
                    });
                }
            } catch (Exception e) {
                log.warn("解析索引缓存失败: sourceUrl={}, 原因={}", entry.sourceUrl(), e.getMessage());
            }
        }

        // 用已安装状态丰富 SkillPackage
        Map<String, InstalledSkill> installedMap = installedSkillRepository.findAll().stream()
                .collect(Collectors.toMap(InstalledSkill::packageId, Function.identity(), (a, b) -> a));

        return mergedPackages.values().stream()
                .map(pkg -> enrichWithInstalledStatus(pkg, installedMap.get(pkg.id())))
                .toList();
    }

    /**
     * 按 ID 查找单个 SkillPackage。
     *
     * @param id 包 ID
     * @return SkillPackage Optional，不存在时返回 empty
     */
    public Optional<SkillPackage> getPackage(String id) {
        return getPackages().stream()
                .filter(pkg -> pkg.id().equals(id))
                .findFirst();
    }

    /**
     * 判断缓存是否过期。
     *
     * <p>如果任一缓存条目的 fetchedAt + cacheTtlHours 早于当前时间，则视为过期。
     * 如果没有缓存条目，也视为过期。</p>
     */
    private boolean isCacheExpired(List<IndexRepository.IndexCacheEntry> entries) {
        if (entries.isEmpty()) {
            return true;
        }

        Duration ttl = Duration.ofHours(properties.getCacheTtlHours());
        Instant now = Instant.now();

        return entries.stream().anyMatch(entry -> {
            try {
                Instant fetchedAt = Instant.parse(entry.fetchedAt());
                return fetchedAt.plus(ttl).isBefore(now);
            } catch (Exception e) {
                log.warn("解析 fetchedAt 失败: value={}", entry.fetchedAt());
                return true;
            }
        });
    }

    /**
     * 用已安装状态丰富 SkillPackage。
     */
    private SkillPackage enrichWithInstalledStatus(SkillPackage pkg, @Nullable InstalledSkill installed) {
        if (installed == null) {
            return pkg.toBuilder()
                    .installed(false)
                    .installedVersion(null)
                    .build();
        }
        return pkg.toBuilder()
                .installed(true)
                .installedVersion(installed.version())
                .build();
    }
}
