package com.lifepilot.meta.convenience;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.skill.markdown.MarkdownSkillParser;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.registry.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 内置 find-skills Skill 注册器 — 启动时读取 SKILL.md 并注册到 SkillRegistry。
 *
 * <p>从 classpath 读取 {@code builtin-skills/find-skills/SKILL.md}，
 * 通过 {@link MarkdownSkillParser} 解析为 {@link SkillDefinition}，
 * 将 source 覆盖为 {@link SkillSource.Builtin}，注册到 {@link SkillRegistry}。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class SkillDiscoveryRegistrar implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SkillDiscoveryRegistrar.class);

    /** SKILL.md 文件名。 */
    private static final String SKILL_MD_FILENAME = "SKILL.md";

    private final SkillRegistry skillRegistry;
    private final MarkdownSkillParser markdownSkillParser;
    private final MetaProperties properties;

    public SkillDiscoveryRegistrar(SkillRegistry skillRegistry,
                                   MarkdownSkillParser markdownSkillParser,
                                   MetaProperties properties) {
        this.skillRegistry = skillRegistry;
        this.markdownSkillParser = markdownSkillParser;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        registerFindSkills();
    }

    /**
     * 读取 SKILL.md 并注册 find-skills Skill。
     */
    void registerFindSkills() {
        var skillDiscovery = properties.getSkillDiscovery();
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

        // 2. 解析 SKILL.md
        var parseResult = markdownSkillParser.parse(content);
        if (!parseResult.success()) {
            log.warn("find-skills SKILL.md 解析失败: errors={}", parseResult.errors());
            return;
        }

        // 3. 覆盖 source 为 Builtin
        var parsed = parseResult.definition();
        if (parsed == null) {
            log.warn("find-skills SKILL.md 解析结果为空");
            return;
        }
        var definition = parsed.toBuilder()
                .source(new SkillSource.Builtin())
                .build();

        // 4. 注册到 SkillRegistry
        boolean registered = skillRegistry.register(definition);
        if (registered) {
            log.info("find-skills Skill 注册成功: id={}", definition.id());
        } else {
            log.warn("find-skills Skill 注册失败: id={}", definition.id());
        }
    }
}
