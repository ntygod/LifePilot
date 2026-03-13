package com.lifepilot.meta.convenience;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.skill.config.SkillConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 内置 Skill 提取器 — 启动时将 SKILL.md 从 classpath 提取到用户 Skill 目录。
 *
 * <p>从 classpath 读取内置 SKILL.md（如 find-skills、workflow-creator），
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

    public SkillDiscoveryRegistrar(MetaProperties properties,
                                   SkillConfigProperties skillConfig) {
        this.properties = properties;
        this.skillConfig = skillConfig;
    }

    @Override
    public void afterPropertiesSet() {
        extractBuiltinSkillsToUserDirectory();
    }

    /**
     * 将所有内置 SKILL.md 从 classpath 提取到用户 Skill 目录。
     *
     * <p>遍历配置的 builtinSkillPaths，提取每个到对应目录。
     * 如果目标文件已存在，跳过提取以保留用户自定义内容。</p>
     */
    void extractBuiltinSkillsToUserDirectory() {
        var skillDiscovery = properties.getSkillDiscovery();
        if (!skillDiscovery.isEnabled()) {
            log.debug("Skill 发现功能已禁用，跳过提取");
            return;
        }

        var builtinPaths = skillDiscovery.getBuiltinSkillPaths();
        if (builtinPaths == null || builtinPaths.isEmpty()) {
            log.debug("未配置内置 Skill 路径");
            return;
        }

        for (var path : builtinPaths) {
            extractSingleSkill(path);
        }
    }

    /**
     * 提取单个内置 Skill 到用户目录。
     *
     * @param resourcePath classpath 下的资源路径，如 builtin-skills/find-skills
     */
    private void extractSingleSkill(String resourcePath) {
        var skillId = extractSkillId(resourcePath);
        if (skillId == null) {
            log.warn("无法从路径提取 Skill ID: path={}", resourcePath);
            return;
        }

        // 1. 从 classpath 读取 SKILL.md
        String content;
        var fullResourcePath = resourcePath + "/" + SKILL_MD_FILENAME;
        try {
            var resource = new ClassPathResource(fullResourcePath);
            if (!resource.exists()) {
                log.warn("内置 Skill SKILL.md 未找到: path={}", fullResourcePath);
                return;
            }
            content = resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("内置 Skill SKILL.md 读取失败: path={}, error={}", fullResourcePath, e.getMessage());
            return;
        }

        // 2. 确定目标路径
        Path targetFolder = Path.of(skillConfig.getDirectory(), skillId);
        Path targetFile = targetFolder.resolve(SKILL_MD_FILENAME);

        // 3. 如果目标文件已存在，跳过（不覆盖用户自定义内容）
        if (Files.exists(targetFile)) {
            log.debug("内置 Skill SKILL.md 已存在于用户目录，跳过提取: skillId={}, path={}", skillId, targetFile);
            return;
        }

        // 4. 创建目录并写入文件
        try {
            Files.createDirectories(targetFolder);
            Files.writeString(targetFile, content, StandardCharsets.UTF_8);
            log.info("内置 Skill SKILL.md 已提取到用户目录: skillId={}, path={}", skillId, targetFile);
        } catch (IOException e) {
            log.warn("内置 Skill SKILL.md 提取失败: skillId={}, path={}, error={}", skillId, targetFile, e.getMessage());
        }
    }

    /**
     * 从资源路径提取 Skill ID。
     *
     * <p>例如：
     * - builtin-skills/find-skills → builtin.find-skills
     * - builtin-skills/workflow-creator → builtin.workflow-creator
     *
     * @param resourcePath 资源路径
     * @return Skill ID 或 null（提取失败时）
     */
    private String extractSkillId(String resourcePath) {
        if (resourcePath == null || resourcePath.isEmpty()) {
            return null;
        }

        // 去掉前缀 builtin-skills/ 或类似的
        String suffix = resourcePath;
        if (resourcePath.contains("/")) {
            suffix = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
        }

        // 转换为 Skill ID 格式（用点号分隔）
        return "builtin." + suffix;
    }
}
