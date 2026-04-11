package com.lifepilot.marketplace.index;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.marketplace.clawhub.ClawHubIndexSource;
import com.lifepilot.marketplace.install.InstalledExtensionRepository;
import com.lifepilot.marketplace.model.ExtensionPackage;
import com.lifepilot.marketplace.model.InstalledExtension;
import com.lifepilot.marketplace.version.VersionResolver;
import com.lifepilot.marketplace.config.MarketplaceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
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
 *   <li>用已安装状态丰富 ExtensionPackage</li>
 * </ul>
 *
 * <p>索引 JSON 格式支持 {@code type} 和 {@code requirements} 字段，
 * 兼容三种扩展类型（SKILL / AGENT / WORKFLOW）。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class IndexManager {

    private static final Logger log = LoggerFactory.getLogger(IndexManager.class);

    private static final TypeReference<List<ExtensionPackage>> PACKAGE_LIST_TYPE = new TypeReference<>() {};

    private final MarketplaceProperties properties;
    private final IndexRepository indexRepository;
    private final InstalledExtensionRepository installedExtensionRepository;
    private final VersionResolver versionResolver;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    @Nullable private final ClawHubIndexSource clawHubIndexSource;

    /**
     * 构造索引管理器。
     *
     * @param properties                  市场配置属性
     * @param indexRepository              索引缓存 DAO
     * @param installedExtensionRepository 已安装扩展 DAO
     * @param versionResolver              版本解析器
     * @param restClientBuilder            RestClient 构建器
     * @param clawHubIndexSource           ClawHub 索引源（未启用时为 null）
     */
    public IndexManager(MarketplaceProperties properties,
                        IndexRepository indexRepository,
                        InstalledExtensionRepository installedExtensionRepository,
                        VersionResolver versionResolver,
                        RestClient.Builder restClientBuilder,
                        @Nullable ClawHubIndexSource clawHubIndexSource) {
        this.properties = properties;
        this.indexRepository = indexRepository;
        this.installedExtensionRepository = installedExtensionRepository;
        this.versionResolver = versionResolver;
        this.restClient = restClientBuilder.build();
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        this.clawHubIndexSource = clawHubIndexSource;
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
                String json = downloadText(sourceUrl);

                if (json == null || json.isBlank()) {
                    log.warn("索引源返回空内容: url={}", sourceUrl);
                    continue;
                }

                // 验证 JSON 可解析为 ExtensionPackage 列表
                objectMapper.readValue(json, PACKAGE_LIST_TYPE);

                indexRepository.save(sourceUrl, json);
                successCount++;
                log.info("索引刷新成功: url={}", sourceUrl);
            } catch (Exception e) {
                log.warn("索引刷新失败: url={}, 原因={}", sourceUrl, e.getMessage());
            }
        }

        // ClawHub 补充索引刷新
        if (clawHubIndexSource != null) {
            boolean clawHubSuccess = clawHubIndexSource.refreshIndex();
            if (clawHubSuccess) {
                successCount++;
            }
            log.info("ClawHub 索引刷新: success={}", clawHubSuccess);
        }

        log.info("索引刷新完成: 成功={}", successCount);
        return successCount;
    }

    private String downloadText(String sourceUrl) throws IOException {
        List<String> candidateUrls = candidateUrls(sourceUrl);
        int maxAttempts = Math.max(1, properties.getHttp().getMaxAttempts());
        long backoffMillis = Math.max(0, properties.getHttp().getRetryBackoffMillis());
        IOException lastError = null;
        for (String candidateUrl : candidateUrls) {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    String json = restClient.get()
                            .uri(candidateUrl)
                            .retrieve()
                            .body(String.class);
                    if (json == null) {
                        throw new IOException("索引响应内容为空");
                    }
                    if (!Objects.equals(candidateUrl, sourceUrl)) {
                        log.info("索引刷新命中回退地址: sourceUrl={}, candidateUrl={}", sourceUrl, candidateUrl);
                    }
                    return json;
                } catch (Exception e) {
                    lastError = new IOException(
                            "拉取索引失败: url=%s, attempt=%d/%d, error=%s"
                                    .formatted(candidateUrl, attempt, maxAttempts, e.getMessage()),
                            e
                    );
                    if (attempt < maxAttempts) {
                        log.warn("索引拉取失败，准备重试: url={}, attempt={}/{}, error={}",
                                candidateUrl, attempt, maxAttempts, e.getMessage());
                        sleepQuietly(backoffMillis);
                    }
                }
            }
        }
        throw lastError != null ? lastError : new IOException("拉取索引失败: " + sourceUrl);
    }

    private List<String> candidateUrls(String sourceUrl) {
        ArrayList<String> candidates = new ArrayList<>();
        candidates.add(sourceUrl);
        if (properties.getHttp().isEnableGithubMirrorFallback()) {
            String jsDelivrUrl = toJsDelivrUrl(sourceUrl);
            if (jsDelivrUrl != null && !candidates.contains(jsDelivrUrl)) {
                candidates.add(jsDelivrUrl);
            }
        }
        return List.copyOf(candidates);
    }

    private String toJsDelivrUrl(String sourceUrl) {
        try {
            URI uri = URI.create(sourceUrl);
            if (!"raw.githubusercontent.com".equalsIgnoreCase(uri.getHost())) {
                return null;
            }
            String path = uri.getPath();
            if (path == null || path.isBlank()) {
                return null;
            }
            String normalized = path.startsWith("/") ? path.substring(1) : path;
            String[] segments = normalized.split("/", 4);
            if (segments.length < 4) {
                return null;
            }
            return properties.getCdnUrlTemplate()
                    .formatted(segments[0], segments[1], segments[2], segments[3]);
        } catch (Exception e) {
            return null;
        }
    }

    private void sleepQuietly(long backoffMillis) throws IOException {
        if (backoffMillis <= 0) {
            return;
        }
        try {
            Thread.sleep(Duration.ofMillis(backoffMillis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("索引重试等待被中断", e);
        }
    }

    /**
     * 获取所有可用 ExtensionPackage — 从缓存读取并合并多源，附加已安装状态。
     *
     * <p>如果缓存过期则触发刷新。相同 id 的包保留版本号更高的。
     * 索引 JSON 支持 {@code type} 和 {@code requirements} 字段。</p>
     *
     * @return 合并后的 ExtensionPackage 列表
     */
    public List<ExtensionPackage> getPackages() {
        List<IndexRepository.IndexCacheEntry> cacheEntries = indexRepository.findAll();

        // 检查缓存是否过期
        if (isCacheExpired(cacheEntries)) {
            log.info("索引缓存已过期，触发刷新");
            refreshAll();
            cacheEntries = indexRepository.findAll();
        }

        // 解析并合并所有索引源的包
        Map<String, ExtensionPackage> mergedPackages = new LinkedHashMap<>();
        for (var entry : cacheEntries) {
            try {
                List<ExtensionPackage> packages = objectMapper.readValue(entry.indexJson(), PACKAGE_LIST_TYPE);
                for (ExtensionPackage pkg : packages) {
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

        // 用已安装状态丰富 ExtensionPackage
        Map<String, InstalledExtension> installedMap = installedExtensionRepository.findAll().stream()
                .collect(Collectors.toMap(InstalledExtension::packageId, Function.identity(), (a, b) -> a));

        return mergedPackages.values().stream()
                .map(pkg -> enrichWithInstalledStatus(pkg, installedMap.get(pkg.id())))
                .toList();
    }

    /**
     * 按 ID 查找单个 ExtensionPackage。
     *
     * @param id 包 ID
     * @return ExtensionPackage Optional，不存在时返回 empty
     */
    public Optional<ExtensionPackage> getPackage(String id) {
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
     * 用已安装状态丰富 ExtensionPackage。
     */
    private ExtensionPackage enrichWithInstalledStatus(ExtensionPackage pkg, @Nullable InstalledExtension installed) {
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
