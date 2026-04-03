package com.lifepilot.marketplace.clawhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.marketplace.config.MarketplaceProperties;
import com.lifepilot.marketplace.index.IndexRepository;
import com.lifepilot.marketplace.model.ExtensionPackage;
import com.lifepilot.marketplace.model.ExtensionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * ClawHub 索引源适配器 — 将 ClawHub API 搜索结果转换为 {@link ExtensionPackage} 并存入索引缓存。
 *
 * <p>作为 {@code IndexManager} 的补充索引源参与索引刷新流程，
 * ClawHub 的包与 ZhiWei 自有索引通过 {@code IndexRepository} 统一合并。</p>
 *
 * @author zsg
 * @since 2026-04-03
 */
public class ClawHubIndexSource {

    private static final Logger log = LoggerFactory.getLogger(ClawHubIndexSource.class);

    /** ClawHub 索引在 IndexRepository 中的虚拟源 URL。 */
    public static final String SOURCE_URL = "clawhub://index";

    /** ClawHub 包标签，用于在安装时识别来源。 */
    public static final String CLAWHUB_TAG = "clawhub";

    private final ClawHubClient clawHubClient;
    private final IndexRepository indexRepository;
    private final MarketplaceProperties properties;
    private final ObjectMapper objectMapper;

    public ClawHubIndexSource(ClawHubClient clawHubClient,
                              IndexRepository indexRepository,
                              MarketplaceProperties properties) {
        this.clawHubClient = clawHubClient;
        this.indexRepository = indexRepository;
        this.properties = properties;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 刷新 ClawHub 索引 — 拉取热门 Skill 列表并存入 IndexRepository 缓存。
     *
     * <p>使用空查询搜索获取热门 Skill，逐个拉取详情后转换为 ExtensionPackage 格式，
     * 序列化为 JSON 存入缓存表。失败时记录警告并返回 false，不影响其他索引源。</p>
     *
     * @return 刷新是否成功
     */
    public boolean refreshIndex() {
        int maxSize = properties.getClawHub().getMaxIndexSize();
        log.info("ClawHub 索引刷新开始: maxSize={}", maxSize);

        try {
            // 搜索热门 Skill（空查询返回按热度排序的结果）
            var searchResults = clawHubClient.search("");
            if (searchResults.isEmpty()) {
                log.info("ClawHub 搜索无结果，跳过索引刷新");
                return true;
            }

            // 限制数量
            var limited = searchResults.stream().limit(maxSize).toList();

            // 逐个获取详情并转换
            List<ExtensionPackage> packages = new ArrayList<>();
            for (var result : limited) {
                try {
                    var detail = clawHubClient.getSkillDetail(result.slug());
                    packages.add(toExtensionPackage(detail));
                } catch (IOException e) {
                    log.debug("ClawHub 获取详情失败，跳过: slug={}, error={}", result.slug(), e.getMessage());
                }
            }

            if (packages.isEmpty()) {
                log.warn("ClawHub 索引刷新: 所有详情获取失败");
                return false;
            }

            // 序列化并存入缓存
            String json = objectMapper.writeValueAsString(packages);
            indexRepository.save(SOURCE_URL, json);

            log.info("ClawHub 索引刷新完成: count={}", packages.size());
            return true;
        } catch (Exception e) {
            log.warn("ClawHub 索引刷新失败: error={}", e.getMessage());
            return false;
        }
    }

    /**
     * 实时搜索 ClawHub 并转换结果 — 不经过缓存，直接返回。
     *
     * @param query 搜索关键词
     * @return 转换后的 ExtensionPackage 列表
     */
    public List<ExtensionPackage> searchAndConvert(String query) {
        try {
            var searchResults = clawHubClient.search(query);
            List<ExtensionPackage> packages = new ArrayList<>();
            for (var result : searchResults) {
                try {
                    var detail = clawHubClient.getSkillDetail(result.slug());
                    packages.add(toExtensionPackage(detail));
                } catch (IOException e) {
                    // 详情获取失败时用搜索结果的简要信息构建
                    packages.add(toExtensionPackageFromSearch(result));
                }
            }
            return packages;
        } catch (IOException e) {
            log.warn("ClawHub 实时搜索失败: query={}, error={}", query, e.getMessage());
            return List.of();
        }
    }

    /**
     * 从 ClawHub Skill 详情转换为 ExtensionPackage。
     */
    private ExtensionPackage toExtensionPackage(ClawHubSkillDetail detail) {
        return ExtensionPackage.builder()
                .id(detail.slug())
                .name(detail.displayName() != null ? detail.displayName() : detail.slug())
                .type(ExtensionType.SKILL)
                .version(detail.version() != null ? detail.version() : "0.0.0")
                .author(detail.ownerHandle() != null ? detail.ownerHandle() : "unknown")
                .description(detail.summary() != null ? detail.summary() : "")
                .repoUrl("https://clawhub.ai")
                .filePath(detail.slug())
                .tags(List.of(CLAWHUB_TAG))
                .requirements(List.of())
                .minLifepilotVersion(null)
                .createdAt(Instant.ofEpochMilli(detail.createdAt()).toString())
                .updatedAt(Instant.ofEpochMilli(detail.updatedAt()).toString())
                .downloads(detail.downloads())
                .verified(false)
                .installed(false)
                .installedVersion(null)
                .build();
    }

    /**
     * 从搜索结果（无详情）构建简要 ExtensionPackage。
     */
    private ExtensionPackage toExtensionPackageFromSearch(ClawHubSearchResult result) {
        return ExtensionPackage.builder()
                .id(result.slug())
                .name(result.displayName() != null ? result.displayName() : result.slug())
                .type(ExtensionType.SKILL)
                .version("0.0.0")
                .author("unknown")
                .description(result.summary() != null ? result.summary() : "")
                .repoUrl("https://clawhub.ai")
                .filePath(result.slug())
                .tags(List.of(CLAWHUB_TAG))
                .requirements(List.of())
                .downloads(0)
                .verified(false)
                .installed(false)
                .build();
    }
}
