package com.lifepilot.skill.install;

import com.lifepilot.marketplace.clawhub.ClawHubClient;
import com.lifepilot.marketplace.clawhub.ClawHubIndexSource;
import com.lifepilot.marketplace.clawhub.ClawHubZipExtractor;
import com.lifepilot.marketplace.index.IndexManager;
import com.lifepilot.marketplace.model.ExtensionPackage;
import com.lifepilot.marketplace.model.ExtensionType;
import com.lifepilot.skill.config.SkillConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

/**
 * 市场渠道 Skill 安装器 —— 把 {@code /api/marketplace} 索引中的 Skill 包桥接到
 * 新的 {@link SkillInstaller} 统一安装流水线，写入 {@code skills} 表，
 * 使 B.1–B.4 四来源模型在 MARKETPLACE 通道也具备事实源。
 *
 * <p>与既有 {@code com.lifepilot.marketplace.install.SkillInstallStrategy} 的关系：</p>
 * <ul>
 *   <li>{@code SkillInstallStrategy} 属于 {@code ExtensionInstaller} 三件套（下载 / 注册 /
 *       卸载），写入 {@code installed_extension} 表，面向 Skill/Agent/Workflow/Channel 统一生命周期。</li>
 *   <li>{@link SkillMarketplaceInstaller} 只处理 SKILL 类型，复用索引查询与下载通道，
 *       但最终走 {@link SkillInstaller#install} 把元数据写到 Phase B 的 {@code skills} 表。</li>
 * </ul>
 *
 * <p>下载分派规则（与 {@code SkillInstallStrategy} 保持一致，避免两套逻辑漂移）：</p>
 * <ul>
 *   <li>ClawHub 包（tags 含 {@link ClawHubIndexSource#CLAWHUB_TAG}）：zip 下载 → 临时解压 →
 *       读取 SKILL.md → installer.install → 复制 references/scripts/assets 辅助目录。</li>
 *   <li>自有索引包：HTTP GET {@code repoUrl + "/" + filePath} 拿单个 SKILL.md 文本 →
 *       installer.install（目前自有索引只发单文件，没有辅助目录语义）。</li>
 * </ul>
 *
 * <p>TODO 后续：</p>
 * <ul>
 *   <li>签名校验：ZhiWei 官方索引可能未来引入 sha256 / cosign；当前索引 JSON 尚无相关字段。</li>
 *   <li>风险扫描：当前未走 {@code SecurityScanner}，低/中风险 Skill 直接落地；
 *       后续若需要可在此层前置调用 {@code securityScanner.scan} 并根据 HIGH 风险决策拒绝。</li>
 *   <li>与 {@code ExtensionInstaller} 的去重：同一 Skill 从 /api/marketplace 入口安装会写
 *       {@code installed_extension} 表，从此入口安装会写 {@code skills} 表；未来渐进统一。</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-04-24
 */
@Service
@ConditionalOnProperty(name = "lifepilot.marketplace.enabled", havingValue = "true", matchIfMissing = true)
public class SkillMarketplaceInstaller {

    private static final Logger log = LoggerFactory.getLogger(SkillMarketplaceInstaller.class);

    private final SkillInstaller installer;
    private final IndexManager indexManager;
    private final SkillConfigProperties config;
    private final RestClient restClient;
    @Nullable private final ClawHubClient clawHubClient;
    @Nullable private final ClawHubZipExtractor clawHubZipExtractor;

    public SkillMarketplaceInstaller(SkillInstaller installer,
                                     IndexManager indexManager,
                                     SkillConfigProperties config,
                                     RestClient.Builder restClientBuilder,
                                     @Nullable ClawHubClient clawHubClient,
                                     @Nullable ClawHubZipExtractor clawHubZipExtractor) {
        this.installer = installer;
        this.indexManager = indexManager;
        this.config = config;
        this.restClient = restClientBuilder.build();
        this.clawHubClient = clawHubClient;
        this.clawHubZipExtractor = clawHubZipExtractor;
    }

    /**
     * 按 Marketplace 包 ID 下载并安装 Skill。
     *
     * @param marketplaceId 市场包唯一标识，从 {@code /api/marketplace/extensions} 获取
     * @return 最终写入 {@code skills} 表的 {@link SkillInstallation} 记录
     * @throws IllegalArgumentException marketplaceId 为空 / 索引未命中 / 包类型非 SKILL
     * @throws IllegalStateException    ClawHub 包但 ClawHub 组件未启用
     * @throws IOException              下载或解压失败
     */
    public SkillInstallation installById(String marketplaceId) throws IOException {
        if (marketplaceId == null || marketplaceId.isBlank()) {
            throw new IllegalArgumentException("marketplaceId 不能为空");
        }

        ExtensionPackage pkg = indexManager.getPackage(marketplaceId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "市场索引未找到包: marketplaceId=" + marketplaceId));

        if (pkg.type() != ExtensionType.SKILL) {
            throw new IllegalArgumentException(
                    "包类型不是 SKILL: marketplaceId=" + marketplaceId + ", type=" + pkg.type());
        }

        Path skillsRoot = Paths.get(config.getDirectory());
        Files.createDirectories(skillsRoot);

        if (isClawHubPackage(pkg)) {
            return installFromClawHub(pkg, skillsRoot);
        }
        return installFromIndex(pkg, skillsRoot);
    }

    /**
     * ClawHub 通道：zip 下载到临时目录 → 读取 SKILL.md → 走统一 installer →
     * 复制 references/scripts/assets 辅助目录到最终安装路径。
     */
    private SkillInstallation installFromClawHub(ExtensionPackage pkg, Path skillsRoot) throws IOException {
        if (clawHubClient == null || clawHubZipExtractor == null) {
            throw new IllegalStateException(
                    "ClawHub 组件未启用（lifepilot.marketplace.claw-hub.enabled=false），"
                            + "无法下载: marketplaceId=" + pkg.id());
        }

        Path tempDir = Files.createTempDirectory("skill-market-clawhub-");
        try {
            byte[] zipBytes = clawHubClient.downloadZip(pkg.id());
            clawHubZipExtractor.extract(zipBytes, tempDir);

            Path skillMd = tempDir.resolve("SKILL.md");
            if (!Files.exists(skillMd)) {
                throw new IllegalArgumentException(
                        "ClawHub 包根目录缺少 SKILL.md: marketplaceId=" + pkg.id());
            }
            String content = Files.readString(skillMd);

            SkillInstallation install = installer.install(new SkillInstaller.InstallRequest(
                    SkillSourceType.MARKETPLACE,
                    sourceUri(pkg),
                    pkg.id(),
                    content,
                    skillsRoot));

            copyAuxFiles(tempDir, Path.of(install.filePath()));
            log.info("市场 Skill 安装成功（ClawHub）: marketplaceId={}, name={}", pkg.id(), install.name());
            return install;
        } finally {
            deleteDirRecursive(tempDir);
        }
    }

    /**
     * 自有索引通道：HTTP GET {@code repoUrl + "/" + filePath} 拉取单个 SKILL.md →
     * 走统一 installer。自有索引目前只发单文件，无辅助目录语义。
     */
    private SkillInstallation installFromIndex(ExtensionPackage pkg, Path skillsRoot) throws IOException {
        if (pkg.repoUrl() == null || pkg.repoUrl().isBlank()
                || pkg.filePath() == null || pkg.filePath().isBlank()) {
            throw new IllegalArgumentException(
                    "市场包 repoUrl / filePath 不完整，无法下载: marketplaceId=" + pkg.id());
        }
        String downloadUrl = pkg.repoUrl() + "/" + pkg.filePath();
        log.debug("从市场索引下载 Skill: marketplaceId={}, url={}", pkg.id(), downloadUrl);

        String content;
        try {
            content = restClient.get()
                    .uri(downloadUrl)
                    .retrieve()
                    .body(String.class);
        } catch (Exception e) {
            throw new IOException("下载市场 Skill 失败: marketplaceId=" + pkg.id()
                    + ", url=" + downloadUrl + ", error=" + e.getMessage(), e);
        }
        if (content == null || content.isBlank()) {
            throw new IOException("下载的 SKILL.md 内容为空: marketplaceId=" + pkg.id());
        }

        SkillInstallation install = installer.install(new SkillInstaller.InstallRequest(
                SkillSourceType.MARKETPLACE,
                sourceUri(pkg),
                pkg.id(),
                content,
                skillsRoot));

        log.info("市场 Skill 安装成功（索引）: marketplaceId={}, name={}", pkg.id(), install.name());
        return install;
    }

    /**
     * 优先使用 {@code repoUrl} 作为溯源 URI，空则退化为 {@code marketplace://<id>} 形式，
     * 避免 sourceUri 为 null 影响后续按来源筛选。
     */
    private static String sourceUri(ExtensionPackage pkg) {
        if (pkg.repoUrl() != null && !pkg.repoUrl().isBlank()) {
            return pkg.repoUrl();
        }
        return "marketplace://" + pkg.id();
    }

    private static boolean isClawHubPackage(ExtensionPackage pkg) {
        return pkg.tags() != null && pkg.tags().contains(ClawHubIndexSource.CLAWHUB_TAG);
    }

    /**
     * 复制 references / scripts / assets 三个辅助目录到安装目录。
     *
     * <p>与 {@code SkillImportService#copyAuxFiles} 行为一致但实现独立：该方法是 import
     * 服务的私有工具，当前阶段不抽取公共类（避免触碰 B.4 冻结代码）。</p>
     */
    private static void copyAuxFiles(Path src, Path dst) throws IOException {
        for (String sub : new String[]{"references", "scripts", "assets"}) {
            Path subSrc = src.resolve(sub);
            if (Files.isDirectory(subSrc)) {
                Path subDst = dst.resolve(sub);
                Files.createDirectories(subDst);
                try (var stream = Files.walk(subSrc)) {
                    stream.filter(p -> !Files.isDirectory(p)).forEach(p -> {
                        try {
                            Path rel = subSrc.relativize(p);
                            Path targetFile = subDst.resolve(rel.toString());
                            Path parent = targetFile.getParent();
                            if (parent != null) {
                                Files.createDirectories(parent);
                            }
                            Files.copy(p, targetFile, StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
                }
            }
        }
    }

    /**
     * 递归删除临时目录，失败只记 WARN，不掩盖上游异常。
     */
    private void deleteDirRecursive(Path dir) {
        if (!Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            log.warn("清理市场下载临时目录失败: {}", dir, e);
        }
    }
}
