package com.lifepilot.marketplace.install;

import com.lifepilot.marketplace.model.ExtensionPackage;
import com.lifepilot.marketplace.model.InstalledExtension;
import com.lifepilot.skill.markdown.MarkdownSkillLoader;
import com.lifepilot.skill.registry.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Skill 安装策略 — 下载 SKILL.md 文件夹并注册到 SkillRegistry。
 *
 * <p>复用 {@link MarkdownSkillLoader#loadFolder(Path)} 的加载逻辑，
 * 下载远程 SKILL.md 到 {@code installDir/{packageId}/SKILL.md}，
 * 解析后注册到 {@link SkillRegistry}。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public final class SkillInstallStrategy implements InstallStrategy {

    private static final Logger log = LoggerFactory.getLogger(SkillInstallStrategy.class);

    private final MarkdownSkillLoader markdownSkillLoader;
    private final SkillRegistry skillRegistry;
    private final Path installDir;

    /**
     * 构造 Skill 安装策略。
     *
     * @param markdownSkillLoader Markdown Skill 加载器
     * @param skillRegistry       Skill 注册中心
     * @param installDir          Skill 安装目录
     */
    public SkillInstallStrategy(MarkdownSkillLoader markdownSkillLoader,
                                SkillRegistry skillRegistry,
                                Path installDir) {
        this.markdownSkillLoader = markdownSkillLoader;
        this.skillRegistry = skillRegistry;
        this.installDir = installDir;
    }

    /**
     * 下载 SKILL.md 到本地 {@code installDir/{packageId}/SKILL.md}。
     *
     * @param pkg        扩展包元数据
     * @param restClient HTTP 客户端
     * @return Skill 文件夹路径（包含 SKILL.md）
     * @throws IOException 下载或写入失败时抛出
     */
    @Override
    public Path download(ExtensionPackage pkg, RestClient restClient) throws IOException {
        Path skillFolder = installDir.resolve(pkg.id());
        Path targetFile = skillFolder.resolve("SKILL.md");

        String downloadUrl = pkg.repoUrl() + "/" + pkg.filePath();
        log.info("下载 Skill: packageId={}, url={}", pkg.id(), downloadUrl);

        String content = restClient.get()
                .uri(downloadUrl)
                .retrieve()
                .body(String.class);

        if (content == null || content.isBlank()) {
            throw new IOException("下载的 SKILL.md 文件内容为空: packageId=" + pkg.id());
        }

        Files.createDirectories(skillFolder);
        Files.writeString(targetFile, content);

        log.info("Skill 下载完成: packageId={}, path={}", pkg.id(), skillFolder);
        return skillFolder;
    }

    /**
     * 通过 MarkdownSkillLoader 加载并注册到 SkillRegistry。
     *
     * @param localPath Skill 文件夹路径
     * @param pkg       扩展包元数据
     */
    @Override
    public void register(Path localPath, ExtensionPackage pkg) {
        var definitionOpt = markdownSkillLoader.loadFolder(localPath);
        if (definitionOpt.isEmpty()) {
            log.warn("Skill 加载失败，无法注册: packageId={}, path={}", pkg.id(), localPath);
            return;
        }

        boolean registered = skillRegistry.register(definitionOpt.get());
        if (registered) {
            log.info("Skill 注册成功: packageId={}, skillId={}", pkg.id(), definitionOpt.get().id());
        } else {
            log.warn("Skill 注册被拒绝: packageId={}, skillId={}", pkg.id(), definitionOpt.get().id());
        }
    }

    /**
     * 注销 Skill 并删除本地文件夹。
     *
     * @param installed 已安装扩展记录
     */
    @Override
    public void uninstall(InstalledExtension installed) {
        Path skillFolder = installDir.resolve(installed.packageId());

        // 尝试从文件夹加载获取 skillId 用于注销
        String skillId = resolveSkillId(skillFolder, installed);
        if (skillId != null) {
            skillRegistry.unregister(skillId);
            log.info("Skill 注销成功: packageId={}, skillId={}", installed.packageId(), skillId);
        }

        // 删除文件夹
        deleteFolderQuietly(skillFolder);
    }

    // ─────────────────────────────────────────────
    //  内部辅助方法
    // ─────────────────────────────────────────────

    /**
     * 从 Skill 文件夹或已安装记录中解析 Skill ID。
     */
    private String resolveSkillId(Path skillFolder, InstalledExtension installed) {
        if (Files.isDirectory(skillFolder)) {
            var defOpt = markdownSkillLoader.loadFolder(skillFolder);
            if (defOpt.isPresent()) {
                return defOpt.get().id();
            }
        }
        // 回退：使用 packageId 作为 skillId
        return installed.packageId();
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
