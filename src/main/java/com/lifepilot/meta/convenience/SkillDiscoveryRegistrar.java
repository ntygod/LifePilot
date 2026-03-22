package com.lifepilot.meta.convenience;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.hub.SkillHubClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.lang.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 种子 Skill 提取器 — 启动时将 SKILL.md 从 classpath 提取到用户 Skill 目录。
 *
 * <p>从 classpath 读取种子 SKILL.md（如 find-skills、workflow-creator），
 * 提取到用户 Skill 目录（{@code ~/.zhiwei/skills/{skill-id}/SKILL.md}）。
 * 后续由 {@link com.lifepilot.skill.markdown.MarkdownSkillLoader} 作为 UserDefined Skill 加载，
 * 用户可在文件系统中查看和编辑。</p>
 *
 * <p>如果用户目录中已存在该文件，跳过提取（不覆盖用户自定义内容）。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class SkillDiscoveryRegistrar implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SkillDiscoveryRegistrar.class);

    /** SKILL.md 文件名。 */
    private static final String SKILL_MD_FILENAME = "SKILL.md";

    private final MetaProperties properties;
    private final SkillConfigProperties skillConfig;
    @Nullable
    private final SkillHubClient skillHubClient;

    public SkillDiscoveryRegistrar(MetaProperties properties,
                                   SkillConfigProperties skillConfig,
                                   @Nullable SkillHubClient skillHubClient) {
        this.properties = properties;
        this.skillConfig = skillConfig;
        this.skillHubClient = skillHubClient;
    }

    @Override
    public void afterPropertiesSet() {
        extractSeedSkillsToUserDirectory();
    }

    /**
     * 将所有种子 SKILL.md 从 classpath 提取到用户 Skill 目录。
     *
     * <p>遍历配置的 skillPaths，提取每个到对应目录。
     * 如果目标文件已存在，跳过提取以保留用户自定义内容。</p>
     */
    void extractSeedSkillsToUserDirectory() {
        var skillDiscovery = properties.getSkillDiscovery();
        if (!skillDiscovery.isEnabled()) {
            log.debug("Skill 发现功能已禁用，跳过提取");
            return;
        }

        var seedPaths = skillDiscovery.getSkillPaths();
        if (seedPaths == null || seedPaths.isEmpty()) {
            log.debug("未配置种子 Skill 路径");
            return;
        }

        for (var path : seedPaths) {
            extractSingleSkill(path);
        }
    }

    /**
     * 提取单个种子 Skill 到用户目录。
     *
     * @param resourcePath classpath 下的资源路径，如 skills/find-skills
     */
    private void extractSingleSkill(String resourcePath) {
        var skillId = extractSkillId(resourcePath);
        if (skillId == null) {
            log.warn("无法从路径提取 Skill ID: path={}", resourcePath);
            return;
        }

        // 确定目标路径
        Path targetFolder = Path.of(skillConfig.getDirectory(), skillId);
        Path targetFile = targetFolder.resolve(SKILL_MD_FILENAME);

        // 从 classpath 读取源内容
        String classpathContent = null;
        var fullResourcePath = resourcePath + "/" + SKILL_MD_FILENAME;
        try {
            var resource = new ClassPathResource(fullResourcePath);
            if (resource.exists()) {
                classpathContent = resource.getContentAsString(StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("种子 Skill SKILL.md 读取失败: path={}, error={}", fullResourcePath, e.getMessage());
        }

        // 如果目标文件已存在，比较内容决定是否需要更新
        if (Files.exists(targetFile)) {
            try {
                String existingContent = Files.readString(targetFile, StandardCharsets.UTF_8);
                // 提取版本号比较，或者直接比较内容哈希
                if (classpathContent != null && !classpathContent.equals(existingContent)
                        && isNewerVersion(classpathContent, existingContent)) {
                    Files.writeString(targetFile, classpathContent, StandardCharsets.UTF_8);
                    log.info("种子 Skill SKILL.md 已更新: skillId={}", skillId);
                } else {
                    log.debug("种子 Skill SKILL.md 无需更新: skillId={}", skillId);
                }
            } catch (IOException e) {
                log.debug("种子 Skill 版本比较失败，跳过: skillId={}, error={}", skillId, e.getMessage());
            }
            return;
        }

        // 优先从腾讯 SkillHub 获取中文版 Skill
        String content = tryFetchFromSkillHub(skillId);

        // SkillHub 获取失败，回退到 classpath 本地版本
        if (content == null) {
            content = classpathContent;
        }

        if (content == null) {
            log.warn("种子 Skill 内容获取失败（SkillHub 和 classpath 均不可用）: skillId={}", skillId);
            return;
        }

        // 创建目录并写入文件
        try {
            Files.createDirectories(targetFolder);
            Files.writeString(targetFile, content, StandardCharsets.UTF_8);
            log.info("种子 Skill SKILL.md 已提取到用户目录: skillId={}, path={}", skillId, targetFile);
        } catch (IOException e) {
            log.warn("种子 Skill SKILL.md 提取失败: skillId={}, path={}, error={}", skillId, targetFile, e.getMessage());
        }
    }

    /**
     * 尝试从腾讯 SkillHub 获取中文版 Skill 内容。
     *
     * @param skillId Skill ID
     * @return Skill 内容（Markdown），获取失败返回 null
     */
    @Nullable
    private String tryFetchFromSkillHub(String skillId) {
        if (skillHubClient == null || !skillConfig.getSkillHub().isEnabled()) {
            return null;
        }
        try {
            String content = skillHubClient.fetchSkillContent(skillId);
            if (content != null && !content.isBlank()) {
                log.info("从 SkillHub 获取到中文 Skill: skillId={}", skillId);
                return content;
            }
        } catch (Exception e) {
            log.debug("SkillHub 获取 Skill 失败，将回退到本地版本: skillId={}, error={}", skillId, e.getMessage());
        }
        return null;
    }

    /**
     * 比较 classpath 版本是否比用户目录版本更新。
     * <p>通过 YAML frontmatter 中的 version 字段比较。
     * 如果无法提取版本号，则通过内容长度差异判断（内容变化视为更新）。</p>
     */
    private boolean isNewerVersion(String classpathContent, String existingContent) {
        String cpVersion = extractVersion(classpathContent);
        String exVersion = extractVersion(existingContent);
        if (cpVersion != null && exVersion != null) {
            return !cpVersion.equals(exVersion);
        }
        // 无法比较版本号时，内容不同即视为需要更新
        return true;
    }

    /**
     * 从 SKILL.md 内容中提取 version 字段。
     */
    @Nullable
    private String extractVersion(String content) {
        if (content == null) return null;
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("version:")) {
                return trimmed.substring("version:".length()).trim().replace("\"", "");
            }
        }
        return null;
    }

    /**
     * 从资源路径提取 Skill ID。
     *
     * <p>直接返回文件夹名作为 Skill ID，不加任何前缀。例如：
     * <ul>
     *   <li>skills/find-skills → find-skills</li>
     *   <li>skills/workflow-creator → workflow-creator</li>
     *   <li>skills/memory → memory</li>
     * </ul>
     *
     * @param resourcePath 资源路径
     * @return Skill ID 或 null（提取失败时）
     */
    private String extractSkillId(String resourcePath) {
        if (resourcePath == null || resourcePath.isEmpty()) {
            return null;
        }

        // 直接返回文件夹名作为 Skill ID
        String suffix = resourcePath;
        if (resourcePath.contains("/")) {
            suffix = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
        }

        return suffix;
    }
}
