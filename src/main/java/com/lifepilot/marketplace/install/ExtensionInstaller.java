package com.lifepilot.marketplace.install;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.marketplace.index.IndexManager;
import com.lifepilot.marketplace.model.*;
import com.lifepilot.marketplace.security.SecurityScanner;
import com.lifepilot.marketplace.version.VersionResolver;
import com.lifepilot.marketplace.config.MarketplaceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 扩展安装协调器 — 按 {@link ExtensionType} 分派到对应 {@link InstallStrategy}，
 * 协调安装/卸载/升级的完整流程。
 *
 * <p>安装流程：
 * <ol>
 *   <li>从 {@link IndexManager} 获取 {@link ExtensionPackage}</li>
 *   <li>版本兼容性检查（{@code minLifepilotVersion}）</li>
 *   <li>通过 {@link InstallStrategy#download} 下载到本地</li>
 *   <li>读取文件内容，{@link SecurityScanner} 安全扫描</li>
 *   <li>风险评估（HIGH 风险需确认或阻止）</li>
 *   <li>通过 {@link InstallStrategy#register} 注册到对应 Registry</li>
 *   <li>保存 {@link InstalledExtension} 记录</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class ExtensionInstaller {

    private static final Logger log = LoggerFactory.getLogger(ExtensionInstaller.class);

    /** 当前应用版本，用于兼容性检查。 */
    private static final String APP_VERSION = "0.1.0";

    private final IndexManager indexManager;
    private final VersionResolver versionResolver;
    private final SecurityScanner securityScanner;
    private final InstalledExtensionRepository repository;
    private final MarketplaceProperties properties;
    private final Map<ExtensionType, InstallStrategy> strategies;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    /**
     * 构造扩展安装协调器。
     *
     * @param indexManager      索引管理器
     * @param versionResolver   版本解析器
     * @param securityScanner   安全扫描器
     * @param repository        已安装扩展 DAO
     * @param properties        市场配置属性
     * @param strategies        按扩展类型分派的安装策略 Map
     * @param restClientBuilder RestClient 构建器
     */
    public ExtensionInstaller(IndexManager indexManager,
                              VersionResolver versionResolver,
                              SecurityScanner securityScanner,
                              InstalledExtensionRepository repository,
                              MarketplaceProperties properties,
                              Map<ExtensionType, InstallStrategy> strategies,
                              RestClient.Builder restClientBuilder) {
        this.indexManager = indexManager;
        this.versionResolver = versionResolver;
        this.securityScanner = securityScanner;
        this.repository = repository;
        this.properties = properties;
        this.strategies = Map.copyOf(strategies);
        this.restClient = restClientBuilder.build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 安装扩展 — 获取包 → 版本兼容性检查 → 下载 → 安全扫描 → 风险评估 → 注册 → 保存记录。
     *
     * @param packageId       市场包 ID
     * @param confirmHighRisk 是否确认安装 HIGH 风险扩展
     * @return 安装结果
     */
    public InstallResult install(String packageId, boolean confirmHighRisk) {
        // 1. 从索引获取包元数据
        var packageOpt = indexManager.getPackage(packageId);
        if (packageOpt.isEmpty()) {
            log.warn("安装失败，未找到包: packageId={}", packageId);
            return failure("未找到包: " + packageId);
        }
        var pkg = packageOpt.get();

        // 2. 版本兼容性检查
        if (!versionResolver.checkCompatibility(APP_VERSION, pkg.minLifepilotVersion())) {
            log.warn("安装失败，版本不兼容: packageId={}, minVersion={}, currentVersion={}",
                    packageId, pkg.minLifepilotVersion(), APP_VERSION);
            return failure("版本不兼容，当前版本 %s 低于最低要求 %s"
                    .formatted(APP_VERSION, pkg.minLifepilotVersion()));
        }

        // 3. 获取对应的安装策略
        var strategy = strategies.get(pkg.type());
        if (strategy == null) {
            log.error("安装失败，未找到类型 {} 的安装策略", pkg.type());
            return failure("不支持的扩展类型: " + pkg.type());
        }

        // 4. 下载到本地
        Path localPath;
        try {
            localPath = strategy.download(pkg, restClient);
        } catch (IOException e) {
            log.warn("安装失败，下载异常: packageId={}, error={}", packageId, e.getMessage());
            return failure("下载失败: " + e.getMessage());
        }

        // 5. 读取文件内容进行安全扫描
        String fileContent;
        try {
            fileContent = readFileContent(localPath);
        } catch (IOException e) {
            log.warn("安装失败，读取下载文件异常: packageId={}, path={}, error={}",
                    packageId, localPath, e.getMessage());
            deletePathQuietly(localPath);
            return failure("读取下载文件失败: " + e.getMessage());
        }

        SecurityReport securityReport = securityScanner.scan(pkg, fileContent);

        // 6. HIGH 风险处理
        if (securityReport.overallRisk() == RiskLevel.HIGH) {
            if (properties.getSecurity().isBlockHighRisk()) {
                log.warn("安装被阻止，HIGH 风险且配置禁止安装: packageId={}", packageId);
                deletePathQuietly(localPath);
                return new InstallResult(false, null, pkg.type(), securityReport,
                        pkg.requirements(), "安全策略禁止安装 HIGH 风险扩展", false);
            }
            if (!confirmHighRisk) {
                log.info("安装需要用户确认，HIGH 风险: packageId={}", packageId);
                deletePathQuietly(localPath);
                return new InstallResult(false, null, pkg.type(), securityReport,
                        pkg.requirements(), null, true);
            }
        }

        // 7. 注册到对应 Registry
        try {
            strategy.register(localPath, pkg);
        } catch (Exception e) {
            log.warn("安装失败，注册异常: packageId={}, error={}", packageId, e.getMessage());
            deletePathQuietly(localPath);
            return new InstallResult(false, null, pkg.type(), securityReport,
                    pkg.requirements(), "注册失败: " + e.getMessage(), false);
        }

        // 8. 保存 InstalledExtension 记录
        Instant now = Instant.now();
        String securityReportJson = serializeJson(securityReport);
        String requirementsJson = serializeJson(pkg.requirements());

        var installed = new InstalledExtension(
                UUID.randomUUID().toString(),
                packageId,
                pkg.type(),
                pkg.name(),
                pkg.version(),
                resolveIndexSourceUrl(),
                pkg.repoUrl(),
                localPath.toString(),
                requirementsJson,
                securityReportJson,
                now,
                now
        );
        repository.save(installed);

        log.info("扩展安装成功: packageId={}, type={}, version={}", packageId, pkg.type(), pkg.version());
        return new InstallResult(true, packageId, pkg.type(), securityReport,
                pkg.requirements(), null, false);
    }

    /**
     * 卸载扩展 — 查找记录 → 分派卸载 → 清理记录。
     *
     * @param packageId 市场包 ID
     * @return 卸载结果
     */
    public InstallResult uninstall(String packageId) {
        // 1. 查找已安装记录
        var installedOpt = repository.findByPackageId(packageId);
        if (installedOpt.isEmpty()) {
            log.warn("卸载失败，未找到已安装记录: packageId={}", packageId);
            return failure("未找到已安装扩展: " + packageId);
        }
        var installed = installedOpt.get();

        // 2. 获取对应的安装策略
        var strategy = strategies.get(installed.type());
        if (strategy == null) {
            log.error("卸载失败，未找到类型 {} 的安装策略", installed.type());
            return failure("不支持的扩展类型: " + installed.type());
        }

        // 3. 分派卸载（注销 Registry + 删除文件）
        try {
            strategy.uninstall(installed);
        } catch (Exception e) {
            log.warn("卸载过程中出现异常，继续清理记录: packageId={}, error={}", packageId, e.getMessage());
        }

        // 4. 删除安装记录
        repository.deleteByPackageId(packageId);

        log.info("扩展卸载成功: packageId={}, type={}", packageId, installed.type());
        return new InstallResult(true, packageId, installed.type(), null,
                null, null, false);
    }

    /**
     * 升级扩展 — 先卸载旧版本再安装新版本。
     *
     * @param packageId       市场包 ID
     * @param confirmHighRisk 是否确认安装 HIGH 风险扩展
     * @return 升级结果
     */
    public InstallResult upgrade(String packageId, boolean confirmHighRisk) {
        // 1. 检查是否已安装
        var installedOpt = repository.findByPackageId(packageId);
        if (installedOpt.isEmpty()) {
            log.warn("升级失败，未找到已安装记录: packageId={}", packageId);
            return failure("未找到已安装扩展: " + packageId);
        }

        // 2. 卸载旧版本
        var uninstallResult = uninstall(packageId);
        if (!uninstallResult.success()) {
            log.warn("升级失败，卸载旧版本失败: packageId={}", packageId);
            return failure("卸载旧版本失败: " + uninstallResult.errorMessage());
        }

        // 3. 安装新版本
        return install(packageId, confirmHighRisk);
    }

    // ─────────────────────────────────────────────
    //  内部辅助方法
    // ─────────────────────────────────────────────

    /**
     * 读取本地路径的文件内容 — 如果是目录则读取第一个文件。
     */
    private String readFileContent(Path localPath) throws IOException {
        if (Files.isDirectory(localPath)) {
            // 目录场景（如 Skill 文件夹），读取目录下第一个文件
            try (Stream<Path> files = Files.list(localPath)) {
                Path firstFile = files
                        .filter(Files::isRegularFile)
                        .findFirst()
                        .orElseThrow(() -> new IOException("目录为空: " + localPath));
                return Files.readString(firstFile);
            }
        }
        return Files.readString(localPath);
    }

    /**
     * 解析索引源 URL — 取配置的第一个索引源。
     */
    private String resolveIndexSourceUrl() {
        var sources = properties.getIndexSources();
        return (sources != null && !sources.isEmpty()) ? sources.getFirst() : "";
    }

    /**
     * 序列化对象为 JSON 字符串，失败时返回 null。
     */
    private String serializeJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("JSON 序列化失败: error={}", e.getMessage());
            return null;
        }
    }

    /**
     * 静默删除路径（文件或文件夹），忽略异常。
     */
    private void deletePathQuietly(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.sorted(Comparator.reverseOrder())
                            .forEach(p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException e) {
                                    log.warn("删除文件失败: path={}, error={}", p, e.getMessage());
                                }
                            });
                }
            } else {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            log.warn("删除路径失败: path={}, error={}", path, e.getMessage());
        }
    }

    /**
     * 构造失败结果的快捷方法。
     */
    private static InstallResult failure(String errorMessage) {
        return new InstallResult(false, null, null, null,
                null, errorMessage, false);
    }
}
