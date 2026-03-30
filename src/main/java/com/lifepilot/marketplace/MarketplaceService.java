package com.lifepilot.marketplace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.marketplace.index.IndexManager;
import com.lifepilot.marketplace.install.ExtensionInstaller;
import com.lifepilot.marketplace.install.InstalledExtensionRepository;
import com.lifepilot.marketplace.model.ExtensionAssetContent;
import com.lifepilot.marketplace.model.ExtensionPackage;
import com.lifepilot.marketplace.model.ExtensionInstallation;
import com.lifepilot.marketplace.model.ExtensionType;
import com.lifepilot.marketplace.model.InstallResult;
import com.lifepilot.marketplace.model.InstalledExtensionAsset;
import com.lifepilot.marketplace.model.InstalledExtension;
import com.lifepilot.marketplace.version.VersionResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.lang.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 扩展市场服务门面 — 协调 IndexManager、ExtensionInstaller、VersionResolver 的业务逻辑。
 *
 * <p>提供扩展浏览、搜索、安装、卸载、升级、索引刷新和更新检测等统一入口，
 * 支持 SKILL / AGENT / WORKFLOW 三种扩展类型。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class MarketplaceService {

    private static final Logger log = LoggerFactory.getLogger(MarketplaceService.class);
    private static final TypeReference<List<InstalledExtensionAsset>> INSTALLED_ASSET_LIST_TYPE = new TypeReference<>() {};

    private final IndexManager indexManager;
    private final ExtensionInstaller extensionInstaller;
    private final VersionResolver versionResolver;
    private final InstalledExtensionRepository installedExtensionRepository;
    private final ObjectMapper objectMapper;

    public MarketplaceService(IndexManager indexManager,
                              ExtensionInstaller extensionInstaller,
                              VersionResolver versionResolver,
                              InstalledExtensionRepository installedExtensionRepository,
                              ObjectMapper objectMapper) {
        this.indexManager = indexManager;
        this.extensionInstaller = extensionInstaller;
        this.versionResolver = versionResolver;
        this.installedExtensionRepository = installedExtensionRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 分页获取扩展包列表 — 支持类型筛选、关键词搜索和标签筛选。
     *
     * @param type   扩展类型筛选，null 表示不过滤
     * @param search 搜索关键词（匹配名称或描述，大小写不敏感），null 或空字符串表示不过滤
     * @param tag    标签筛选，null 或空字符串表示不过滤
     * @param page   页码（从 0 开始）
     * @param size   每页大小
     * @return 分页结果
     */
    public PagedResult<ExtensionPackage> getExtensions(
            @Nullable ExtensionType type,
            @Nullable String search,
            @Nullable String tag,
            int page, int size) {
        List<ExtensionPackage> packages = indexManager.getPackages();

        // 按扩展类型过滤
        if (type != null) {
            packages = packages.stream()
                    .filter(pkg -> pkg.type() == type)
                    .toList();
        }

        // 按搜索关键词过滤（名称或描述包含关键词，大小写不敏感）
        if (search != null && !search.isBlank()) {
            String lowerSearch = search.toLowerCase(Locale.ROOT);
            packages = packages.stream()
                    .filter(pkg -> containsIgnoreCase(pkg.name(), lowerSearch)
                            || containsIgnoreCase(pkg.description(), lowerSearch))
                    .toList();
        }

        // 按标签过滤
        if (tag != null && !tag.isBlank()) {
            packages = packages.stream()
                    .filter(pkg -> pkg.tags() != null && pkg.tags().contains(tag))
                    .toList();
        }

        // 按下载量降序排序
        packages = packages.stream()
                .sorted(Comparator.comparingInt(ExtensionPackage::downloads).reversed())
                .toList();

        // 分页
        long totalElements = packages.size();
        int totalPages = size > 0 ? (int) Math.ceil((double) totalElements / size) : 0;
        int fromIndex = Math.min(page * size, packages.size());
        int toIndex = Math.min(fromIndex + size, packages.size());
        List<ExtensionPackage> content = packages.subList(fromIndex, toIndex);

        log.debug("扩展列表查询: type={}, search={}, tag={}, page={}, size={}, total={}",
                type, search, tag, page, size, totalElements);
        return new PagedResult<>(List.copyOf(content), page, size, totalElements, totalPages);
    }

    /**
     * 按 ID 获取单个扩展包详情。
     *
     * @param id 包 ID
     * @return ExtensionPackage Optional，不存在时返回 empty
     */
    public Optional<ExtensionPackage> getExtension(String id) {
        return indexManager.getPackage(id);
    }

    /**
     * 按包 ID 获取安装快照。
     *
     * @param id 包 ID
     * @return 安装快照 Optional
     */
    public Optional<ExtensionInstallation> getInstallation(String id) {
        return installedExtensionRepository.findByPackageId(id)
                .map(this::toInstallation);
    }

    /**
     * 按相对路径读取已安装扩展资产内容。
     *
     * @param id           包 ID
     * @param relativePath 相对插件根目录路径
     * @return 资产内容 Optional
     */
    public Optional<ExtensionAssetContent> getInstallationAsset(String id, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return Optional.empty();
        }
        return installedExtensionRepository.findByPackageId(id)
                .flatMap(installed -> findAsset(installed, relativePath)
                        .flatMap(asset -> loadAssetContent(installed, asset)));
    }

    /**
     * 安装扩展。
     *
     * @param packageId       市场包 ID
     * @param confirmHighRisk 是否确认安装 HIGH 风险扩展
     * @return 安装结果
     */
    public InstallResult install(String packageId, boolean confirmHighRisk) {
        log.info("安装扩展: packageId={}, confirmHighRisk={}", packageId, confirmHighRisk);
        return extensionInstaller.install(packageId, confirmHighRisk);
    }

    /**
     * 卸载扩展。
     *
     * @param packageId 市场包 ID
     * @return 卸载结果
     */
    public InstallResult uninstall(String packageId) {
        log.info("卸载扩展: packageId={}", packageId);
        return extensionInstaller.uninstall(packageId);
    }

    /**
     * 升级扩展。
     *
     * @param packageId       市场包 ID
     * @param confirmHighRisk 是否确认安装 HIGH 风险扩展
     * @return 升级结果
     */
    public InstallResult upgrade(String packageId, boolean confirmHighRisk) {
        log.info("升级扩展: packageId={}, confirmHighRisk={}", packageId, confirmHighRisk);
        return extensionInstaller.upgrade(packageId, confirmHighRisk);
    }

    /**
     * 刷新所有索引源。
     *
     * @return 成功刷新的索引源数量
     */
    public int refreshIndex() {
        log.info("刷新索引");
        return indexManager.refreshAll();
    }

    /**
     * 获取有可用更新的扩展包列表。
     *
     * @return 有更新的 ExtensionPackage 列表
     */
    public List<ExtensionPackage> getUpdates() {
        List<InstalledExtension> installed = installedExtensionRepository.findAll();
        List<ExtensionPackage> allPackages = indexManager.getPackages();
        return versionResolver.findUpdates(installed, allPackages);
    }

    /**
     * 大小写不敏感的包含检查。
     */
    private static boolean containsIgnoreCase(String text, String lowerSearch) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(lowerSearch);
    }

    private ExtensionInstallation toInstallation(InstalledExtension installed) {
        return new ExtensionInstallation(
                installed.packageId(),
                installed.type(),
                installed.name(),
                installed.version(),
                installed.entryPath(),
                installed.installRootPath(),
                deserializeAssets(installed.assetsJson())
        );
    }

    private Optional<InstalledExtensionAsset> findAsset(InstalledExtension installed, String relativePath) {
        return deserializeAssets(installed.assetsJson()).stream()
                .filter(asset -> relativePath.equals(asset.relativePath()))
                .findFirst();
    }

    private Optional<ExtensionAssetContent> loadAssetContent(InstalledExtension installed,
                                                             InstalledExtensionAsset asset) {
        try {
            Path localPath = Path.of(asset.localPath());
            // 路径穿越防护：验证 localPath 在安装根目录内
            if (installed.installRootPath() != null && !installed.installRootPath().isBlank()) {
                Path installRoot = Path.of(installed.installRootPath()).toAbsolutePath().normalize();
                if (!localPath.toAbsolutePath().normalize().startsWith(installRoot)) {
                    log.warn("安装资产路径超出安装根目录，拒绝读取: packageId={}, path={}, installRoot={}",
                            installed.packageId(), asset.localPath(), installRoot);
                    return Optional.empty();
                }
            }
            if (!Files.exists(localPath) || !Files.isRegularFile(localPath)) {
                return Optional.empty();
            }
            byte[] content = Files.readAllBytes(localPath);
            String contentType = detectContentType(localPath);
            return Optional.of(new ExtensionAssetContent(
                    installed.packageId(),
                    asset.relativePath(),
                    asset.kind(),
                    contentType,
                    content
            ));
        } catch (Exception e) {
            log.warn("读取安装资产失败: packageId={}, path={}, error={}",
                    installed.packageId(), asset.relativePath(), e.getMessage());
            return Optional.empty();
        }
    }

    private String detectContentType(Path localPath) {
        String fileName = localPath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (fileName.endsWith(".md") || fileName.endsWith(".markdown")) {
            return "text/markdown";
        }
        if (fileName.endsWith(".yaml") || fileName.endsWith(".yml")) {
            return "application/yaml";
        }
        if (fileName.endsWith(".txt")
                || fileName.endsWith(".log")
                || fileName.endsWith(".properties")
                || fileName.endsWith(".env")) {
            return MediaType.TEXT_PLAIN_VALUE;
        }
        return MediaTypeFactory.getMediaType(fileName)
                .map(MediaType::toString)
                .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);
    }

    private List<InstalledExtensionAsset> deserializeAssets(@Nullable String assetsJson) {
        if (assetsJson == null || assetsJson.isBlank()) {
            return List.of();
        }
        try {
            return List.copyOf(objectMapper.readValue(assetsJson, INSTALLED_ASSET_LIST_TYPE));
        } catch (Exception e) {
            log.warn("解析安装资产失败: error={}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 分页结果。
     *
     * @param content       当前页内容
     * @param page          页码（从 0 开始）
     * @param size          每页大小
     * @param totalElements 总元素数
     * @param totalPages    总页数
     * @param <T>           元素类型
     */
    public record PagedResult<T>(
            List<T> content,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {}
}
