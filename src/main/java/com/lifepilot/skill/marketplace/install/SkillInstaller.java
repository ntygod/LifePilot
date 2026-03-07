package com.lifepilot.skill.marketplace.install;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.markdown.MarkdownSkillLoader;
import com.lifepilot.skill.markdown.MarkdownSkillParser;
import com.lifepilot.skill.marketplace.config.MarketplaceProperties;
import com.lifepilot.skill.marketplace.index.IndexManager;
import com.lifepilot.skill.marketplace.model.*;
import com.lifepilot.skill.marketplace.security.SkillSecurityScanner;
import com.lifepilot.skill.marketplace.version.VersionResolver;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.registry.SkillRegistry;
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
 * Skill 安装器 — 负责从远程仓库下载、校验、安全扫描、写入本地并注册 Skill。
 *
 * <p>核心流程：
 * <ol>
 *   <li>从 IndexManager 获取 SkillPackage 元数据</li>
 *   <li>版本兼容性检查</li>
 *   <li>HTTP 下载 SKILL.md 文件</li>
 *   <li>MarkdownSkillParser 解析 + 安全扫描</li>
 *   <li>写入本地 skills 目录（{packageId}/SKILL.md）</li>
 *   <li>通过 MarkdownSkillLoader 加载并替换 source 为 Marketplace</li>
 *   <li>注册到 SkillRegistry 并持久化安装记录</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-03-05
 */
public class SkillInstaller {

    private static final Logger log = LoggerFactory.getLogger(SkillInstaller.class);

    /** 当前应用版本，用于兼容性检查。 */
    private static final String APP_VERSION = "0.1.0";

    private final IndexManager indexManager;
    private final VersionResolver versionResolver;
    private final SkillSecurityScanner securityScanner;
    private final MarkdownSkillParser markdownParser;
    private final MarkdownSkillLoader markdownSkillLoader;
    private final SkillRegistry skillRegistry;
    private final InstalledSkillRepository installedSkillRepository;
    private final MarketplaceProperties marketplaceProperties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final Path skillsDirectory;

    /**
     * 构造 Skill 安装器。
     *
     * @param indexManager            索引管理器
     * @param versionResolver         版本解析器
     * @param securityScanner         安全扫描器
     * @param markdownParser          Markdown Skill 解析器
     * @param markdownSkillLoader     Markdown Skill 加载器
     * @param skillRegistry           Skill 注册中心
     * @param installedSkillRepository 已安装记录 DAO
     * @param marketplaceProperties   市场配置属性
     * @param skillConfigProperties   Skill 系统配置属性
     * @param restClientBuilder       RestClient 构建器
     */
    public SkillInstaller(IndexManager indexManager,
                          VersionResolver versionResolver,
                          SkillSecurityScanner securityScanner,
                          MarkdownSkillParser markdownParser,
                          MarkdownSkillLoader markdownSkillLoader,
                          SkillRegistry skillRegistry,
                          InstalledSkillRepository installedSkillRepository,
                          MarketplaceProperties marketplaceProperties,
                          SkillConfigProperties skillConfigProperties,
                          RestClient.Builder restClientBuilder) {
        this.indexManager = indexManager;
        this.versionResolver = versionResolver;
        this.securityScanner = securityScanner;
        this.markdownParser = markdownParser;
        this.markdownSkillLoader = markdownSkillLoader;
        this.skillRegistry = skillRegistry;
        this.installedSkillRepository = installedSkillRepository;
        this.marketplaceProperties = marketplaceProperties;
        this.restClient = restClientBuilder.build();
        this.objectMapper = new ObjectMapper();
        this.skillsDirectory = Path.of(skillConfigProperties.getDirectory());
    }

    /**
     * 安装 Skill — 下载、校验、扫描、写入、注册、持久化。
     *
     * @param packageId       市场包 ID
     * @param confirmHighRisk 是否确认安装 HIGH 风险 Skill
     * @return 安装结果
     */
    public InstallResult install(String packageId, boolean confirmHighRisk) {
        // 1. 从索引获取包元数据
        var packageOpt = indexManager.getPackage(packageId);
        if (packageOpt.isEmpty()) {
            log.warn("安装失败，未找到包: packageId={}", packageId);
            return new InstallResult(false, null, null, "未找到包: " + packageId, false);
        }
        var skillPackage = packageOpt.get();

        // 2. 版本兼容性检查
        if (!versionResolver.checkCompatibility(APP_VERSION, skillPackage.minLifepilotVersion())) {
            log.warn("安装失败，版本不兼容: packageId={}, minVersion={}, currentVersion={}",
                    packageId, skillPackage.minLifepilotVersion(), APP_VERSION);
            return new InstallResult(false, null, null,
                    "版本不兼容，当前版本 %s 低于最低要求 %s".formatted(APP_VERSION, skillPackage.minLifepilotVersion()),
                    false);
        }

        // 3. HTTP 下载 SKILL.md 文件
        String markdownContent;
        try {
            String downloadUrl = skillPackage.repoUrl() + "/" + skillPackage.filePath();
            markdownContent = restClient.get()
                    .uri(downloadUrl)
                    .retrieve()
                    .body(String.class);
            if (markdownContent == null || markdownContent.isBlank()) {
                log.warn("安装失败，下载内容为空: packageId={}, url={}", packageId, downloadUrl);
                return new InstallResult(false, null, null, "下载的 SKILL.md 文件内容为空", false);
            }
        } catch (Exception e) {
            log.warn("安装失败，SKILL.md 下载异常: packageId={}, error={}", packageId, e.getMessage());
            return new InstallResult(false, null, null, "SKILL.md 文件下载失败: " + e.getMessage(), false);
        }

        // 4. 使用 MarkdownSkillParser 解析并校验
        var parseResult = markdownParser.parse(markdownContent);
        if (!parseResult.success()) {
            log.warn("安装失败，SKILL.md 解析校验不通过: packageId={}, errors={}", packageId, parseResult.errors());
            return new InstallResult(false, null, null,
                    "SKILL.md 解析校验失败: " + String.join("; ", parseResult.errors()), false);
        }

        // 5. 安全扫描（扫描器期望 skill 节点内容，包装为 {"skill": frontmatterMap} 后取 skill 节点）
        var frontmatterMap = parseResult.frontmatterMap();
        SecurityReport securityReport = securityScanner.scan(frontmatterMap != null ? frontmatterMap : Map.of());

        // 6. HIGH 风险处理
        if (securityReport.overallRisk() == RiskLevel.HIGH) {
            if (marketplaceProperties.getSecurity().isBlockHighRisk()) {
                log.warn("安装被阻止，HIGH 风险且配置禁止安装: packageId={}", packageId);
                return new InstallResult(false, null, securityReport,
                        "安全策略禁止安装 HIGH 风险 Skill", false);
            }
            if (!confirmHighRisk) {
                log.info("安装需要用户确认，HIGH 风险: packageId={}", packageId);
                return new InstallResult(false, null, securityReport, null, true);
            }
        }

        // 7. 写入 SKILL.md 文件到本地 skills 目录（{packageId}/SKILL.md）
        Path skillFolder = skillsDirectory.resolve(packageId);
        Path targetFile = skillFolder.resolve("SKILL.md");
        try {
            Files.createDirectories(skillFolder);
            Files.writeString(targetFile, markdownContent);
        } catch (IOException e) {
            log.error("安装失败，写入文件异常: packageId={}, path={}, error={}",
                    packageId, targetFile, e.getMessage());
            return new InstallResult(false, null, securityReport, "写入 SKILL.md 文件失败: " + e.getMessage(), false);
        }

        // 8. 通过 MarkdownSkillLoader 加载并替换 source 为 Marketplace
        var definitionOpt = markdownSkillLoader.loadFolder(skillFolder);
        if (definitionOpt.isEmpty()) {
            // 加载失败，清理已写入的文件夹
            deleteFolderQuietly(skillFolder);
            log.warn("安装失败，MarkdownSkillLoader 加载失败: packageId={}", packageId);
            return new InstallResult(false, null, securityReport, "SKILL.md 加载失败", false);
        }

        // 替换 source 为 Marketplace
        Instant now = Instant.now();
        SkillDefinition definition = definitionOpt.get().toBuilder()
                .source(new SkillSource.Marketplace(packageId, resolveIndexSourceUrl(skillPackage), now))
                .build();

        // 9. 注册到 SkillRegistry
        boolean registered = skillRegistry.register(definition);
        if (!registered) {
            deleteFolderQuietly(skillFolder);
            log.warn("安装失败，SkillRegistry 注册被拒绝: packageId={}, skillId={}", packageId, definition.id());
            return new InstallResult(false, null, securityReport, "Skill 注册被拒绝", false);
        }

        // 10. 持久化安装记录
        String securityReportJson = serializeSecurityReport(securityReport);
        var installedSkill = new InstalledSkill(
                UUID.randomUUID().toString(),
                packageId,
                skillPackage.name(),
                skillPackage.version(),
                resolveIndexSourceUrl(skillPackage),
                skillPackage.repoUrl(),
                skillPackage.filePath(),
                securityReportJson,
                now,
                now
        );
        installedSkillRepository.save(installedSkill);

        log.info("Skill 安装成功: packageId={}, skillId={}, version={}",
                packageId, definition.id(), skillPackage.version());
        return new InstallResult(true, definition.id(), securityReport, null, false);
    }

    /**
     * 卸载 Skill — 注销、删除文件夹、清理记录。
     *
     * @param packageId 市场包 ID
     * @return 安装结果（复用 InstallResult 表示卸载结果）
     */
    public InstallResult uninstall(String packageId) {
        // 1. 查找已安装记录
        var installedOpt = installedSkillRepository.findByPackageId(packageId);
        if (installedOpt.isEmpty()) {
            log.warn("卸载失败，未找到已安装记录: packageId={}", packageId);
            return new InstallResult(false, null, null, "未找到已安装的 Skill: " + packageId, false);
        }
        var installed = installedOpt.get();

        // 2. 从 SkillRegistry 注销（通过加载文件夹获取 skillId）
        Path skillFolder = skillsDirectory.resolve(packageId);
        String skillId = resolveSkillId(skillFolder, installed);
        if (skillId != null) {
            skillRegistry.unregister(skillId);
        }

        // 3. 删除 Skill 文件夹
        deleteFolderQuietly(skillFolder);

        // 4. 删除安装记录
        installedSkillRepository.deleteByPackageId(packageId);

        log.info("Skill 卸载成功: packageId={}, skillId={}", packageId, skillId);
        return new InstallResult(true, skillId, null, null, false);
    }

    /**
     * 升级 Skill — 卸载旧版本后安装新版本。
     *
     * @param packageId       市场包 ID
     * @param confirmHighRisk 是否确认安装 HIGH 风险 Skill
     * @return 安装结果
     */
    public InstallResult upgrade(String packageId, boolean confirmHighRisk) {
        // 1. 检查是否已安装
        var installedOpt = installedSkillRepository.findByPackageId(packageId);
        if (installedOpt.isEmpty()) {
            log.warn("升级失败，未找到已安装记录: packageId={}", packageId);
            return new InstallResult(false, null, null, "未找到已安装的 Skill: " + packageId, false);
        }

        // 2. 卸载旧版本
        var uninstallResult = uninstall(packageId);
        if (!uninstallResult.success()) {
            log.warn("升级失败，卸载旧版本失败: packageId={}", packageId);
            return new InstallResult(false, null, null,
                    "卸载旧版本失败: " + uninstallResult.errorMessage(), false);
        }

        // 3. 安装新版本
        return install(packageId, confirmHighRisk);
    }

    // ─────────────────────────────────────────────
    //  内部辅助方法
    // ─────────────────────────────────────────────

    /**
     * 解析索引源 URL — 取配置的第一个索引源。
     */
    private String resolveIndexSourceUrl(SkillPackage skillPackage) {
        var sources = marketplaceProperties.getIndexSources();
        return (sources != null && !sources.isEmpty()) ? sources.getFirst() : "";
    }

    /**
     * 从 Skill 文件夹或已安装记录中解析 Skill ID。
     */
    private String resolveSkillId(Path skillFolder, InstalledSkill installed) {
        // 尝试从文件夹加载获取 skillId
        if (Files.isDirectory(skillFolder)) {
            var defOpt = markdownSkillLoader.loadFolder(skillFolder);
            if (defOpt.isPresent()) {
                return defOpt.get().id();
            }
        }
        // 回退：使用 packageId 作为 skillId（通常 SKILL.md 中的 id 与 packageId 一致）
        return installed.packageId();
    }

    /**
     * 序列化 SecurityReport 为 JSON 字符串。
     */
    private String serializeSecurityReport(SecurityReport report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (Exception e) {
            log.warn("SecurityReport 序列化失败: error={}", e.getMessage());
            return null;
        }
    }

    /**
     * 静默删除文件夹及其内容，忽略异常。
     */
    private void deleteFolderQuietly(Path folder) {
        try {
            if (Files.exists(folder)) {
                try (Stream<Path> walk = Files.walk(folder)) {
                    walk.sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException e) {
                                    log.warn("删除文件失败: path={}, error={}", path, e.getMessage());
                                }
                            });
                }
            }
        } catch (IOException e) {
            log.warn("删除文件夹失败: folder={}, error={}", folder, e.getMessage());
        }
    }
}
