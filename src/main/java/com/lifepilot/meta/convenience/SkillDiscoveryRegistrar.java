package com.lifepilot.meta.convenience;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.skill.config.SkillConfigProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 种子 Skill 提取器 — 启动时将 classpath 下 skills/ 目录的所有 SKILL.md 提取到用户目录。
 *
 * <p>自动扫描 classpath 下 {@code skills/&#42;/SKILL.md}，
 * 提取到用户 Skill 目录（{@code ~/.zhiwei/skills/{skill-id}/SKILL.md}）。
 * 如果用户目录中已存在该文件，跳过提取（不覆盖用户自定义内容）。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class SkillDiscoveryRegistrar implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SkillDiscoveryRegistrar.class);

    private static final String SKILL_MD_FILENAME = "SKILL.md";
    private static final String SKILLS_RESOURCE_PATTERN = "classpath:skills/*/SKILL.md";

    private final MetaProperties properties;
    private final SkillConfigProperties skillConfig;

    public SkillDiscoveryRegistrar(MetaProperties properties,
                                   SkillConfigProperties skillConfig) {
        this.properties = properties;
        this.skillConfig = skillConfig;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.getSkillDiscovery().isEnabled()) {
            log.debug("Skill 发现功能已禁用，跳过提取");
            return;
        }
        extractAllSkillsFromClasspath();
    }

    /**
     * 扫描 classpath 下 skills/ 目录的所有 SKILL.md，逐个提取到用户目录。
     */
    void extractAllSkillsFromClasspath() {
        var resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources;
        try {
            resources = resolver.getResources(SKILLS_RESOURCE_PATTERN);
        } catch (IOException e) {
            log.warn("扫描 classpath skills 目录失败: error={}", e.getMessage());
            return;
        }

        int count = 0;
        for (Resource resource : resources) {
            try {
                String skillId = extractSkillId(resource);
                if (skillId == null) continue;

                Path targetFolder = Path.of(skillConfig.getDirectory(), skillId);
                Path targetFile = targetFolder.resolve(SKILL_MD_FILENAME);

                // 已存在则跳过，不覆盖用户自定义内容
                if (Files.exists(targetFile)) {
                    log.debug("种子 Skill 已存在，跳过: skillId={}", skillId);
                    continue;
                }

                String content = resource.getContentAsString(StandardCharsets.UTF_8);
                Files.createDirectories(targetFolder);
                Files.writeString(targetFile, content, StandardCharsets.UTF_8);
                log.info("种子 Skill SKILL.md 已提取到用户目录: skillId={}, path={}", skillId, targetFile);
                count++;
            } catch (IOException e) {
                log.warn("种子 Skill 提取失败: resource={}, error={}", resource.getFilename(), e.getMessage());
            }
        }
        log.info("种子 Skill 提取完成: 新增={}, 总扫描={}", count, resources.length);
    }

    /**
     * 从 Resource 路径中提取 Skill ID（父目录名）。
     */
    private String extractSkillId(Resource resource) {
        try {
            // resource URL 格式: ...skills/skill-id/SKILL.md
            String url = resource.getURL().toString();
            String[] parts = url.split("/");
            if (parts.length >= 2) {
                return parts[parts.length - 2]; // SKILL.md 的父目录名
            }
        } catch (IOException e) {
            log.debug("无法解析 Skill ID: resource={}", resource.getFilename());
        }
        return null;
    }
}
