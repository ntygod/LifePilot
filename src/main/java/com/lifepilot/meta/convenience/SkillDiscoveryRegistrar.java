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

/**
 * 内置 find-skills Skill 提取器 — 启动时将 SKILL.md 从 classpath 提取到用户 Skill 目录。
 *
 * <p>从 classpath 读取 {@code builtin-skills/find-skills/SKILL.md}，
 * 提取到用户 Skill 目录（{@code ~/.zhiwei/skills/builtin.find-skills/SKILL.md}）。
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

    /** 提取到用户目录时的文件夹名，与 SKILL.md 中的 id 一致。 */
    private static final String FIND_SKILLS_FOLDER = "builtin.find-skills";

    private final MetaProperties properties;
    private final SkillConfigProperties skillConfig;

    public SkillDiscoveryRegistrar(MetaProperties properties,
                                   SkillConfigProperties skillConfig) {
        this.properties = properties;
        this.skillConfig = skillConfig;
    }

    @Override
    public void afterPropertiesSet() {
        extractFindSkillsToUserDirectory();
    }

    /**
     * 将 find-skills SKILL.md 从 classpath 提取到用户 Skill 目录。
     *
     * <p>提取目标：{@code {skillsDirectory}/builtin.find-skills/SKILL.md}。
     * 如果目标文件已存在，跳过提取以保留用户自定义内容。
     * 后续由 MarkdownSkillLoader 在 ApplicationReadyEvent 时扫描加载。</p>
     */
    void extractFindSkillsToUserDirectory() {
        var skillDiscovery = properties.getSkillDiscovery();
        if (!skillDiscovery.isEnabled()) {
            log.debug("find-skills 发现功能已禁用，跳过提取");
            return;
        }

        var resourcePath = skillDiscovery.getBuiltinSkillPath() + "/" + SKILL_MD_FILENAME;

        // 1. 从 classpath 读取 SKILL.md
        String content;
        try {
            var resource = new ClassPathResource(resourcePath);
            if (!resource.exists()) {
                log.warn("find-skills SKILL.md 未找到: path={}", resourcePath);
                return;
            }
            content = resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("find-skills SKILL.md 读取失败: path={}, error={}", resourcePath, e.getMessage());
            return;
        }

        // 2. 确定目标路径
        Path targetFolder = Path.of(skillConfig.getDirectory(), FIND_SKILLS_FOLDER);
        Path targetFile = targetFolder.resolve(SKILL_MD_FILENAME);

        // 3. 如果目标文件已存在，跳过（不覆盖用户自定义内容）
        if (Files.exists(targetFile)) {
            log.debug("find-skills SKILL.md 已存在于用户目录，跳过提取: path={}", targetFile);
            return;
        }

        // 4. 创建目录并写入文件
        try {
            Files.createDirectories(targetFolder);
            Files.writeString(targetFile, content, StandardCharsets.UTF_8);
            log.info("find-skills SKILL.md 已提取到用户目录: path={}", targetFile);
        } catch (IOException e) {
            log.warn("find-skills SKILL.md 提取失败: path={}, error={}", targetFile, e.getMessage());
        }
    }
}
