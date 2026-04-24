package com.lifepilot.skill.markdown;

import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.registry.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Markdown Skill 加载器 — 扫描 Skill 目录并注册 Skill。
 *
 * <p>TODO Phase B.3: 原基于老 {@code com.lifepilot.skill.markdown.MarkdownSkillParser}
 * （产出 {@link SkillDefinition}）的实现已随 v2 Parser 重写被拆除。
 * 本类当前为临时骨架 — 所有加载方法抛出 {@link UnsupportedOperationException}，
 * 等待 Phase B.3（SkillDiscoveryRegistrar）接入新 {@code com.lifepilot.skill.MarkdownSkillParser}
 * + {@code SkillInstaller}，将 ParsedSkill → SkillInstallation → SkillDefinition 串起来。</p>
 *
 * @author zsg
 * @since 2026-03-07
 */
public class MarkdownSkillLoader {

    private static final Logger log = LoggerFactory.getLogger(MarkdownSkillLoader.class);

    private final SkillRegistry skillRegistry;
    private final SkillConfigProperties config;
    private final Path skillsDirectory;

    public MarkdownSkillLoader(SkillRegistry skillRegistry,
                               SkillConfigProperties config) {
        this.skillRegistry = skillRegistry;
        this.config = config;
        this.skillsDirectory = Path.of(config.getDirectory());
    }

    /**
     * 用于测试的构造器 — 允许指定自定义目录路径。
     */
    MarkdownSkillLoader(SkillRegistry skillRegistry,
                        SkillConfigProperties config,
                        Path skillsDirectory) {
        this.skillRegistry = skillRegistry;
        this.config = config;
        this.skillsDirectory = skillsDirectory;
    }

    /**
     * 扫描 Skill 目录，加载所有 Skill。
     *
     * @return 成功加载的 Skill 数量
     */
    public int loadAll() {
        // TODO Phase B.3: 接入新 MarkdownSkillParser + SkillInstaller
        log.warn("MarkdownSkillLoader.loadAll 暂未实现（等待 Phase B.3 重接新 parser + SkillInstaller）");
        return 0;
    }

    /**
     * 加载单个 Skill 文件夹。
     *
     * @param skillFolder Skill 文件夹路径
     * @return 解析出的 SkillDefinition，失败返回 Optional.empty()
     */
    public Optional<SkillDefinition> loadFolder(Path skillFolder) {
        // TODO Phase B.3: 接入新 MarkdownSkillParser + SkillInstaller
        throw new UnsupportedOperationException(
                "MarkdownSkillLoader.loadFolder 待 Phase B.3 重接新 parser + SkillInstaller");
    }

    /**
     * 加载 Skill 文件夹中的 references 目录内容。
     *
     * @param skillFolder Skill 文件夹路径
     * @return references 文件内容的不可变 Map（文件名 → 内容），无 references 目录时返回空 Map
     */
    public Map<String, String> loadReferences(Path skillFolder) {
        // TODO Phase B.3: 随 loadFolder 一起重接
        throw new UnsupportedOperationException(
                "MarkdownSkillLoader.loadReferences 待 Phase B.3 重接");
    }

    /**
     * 获取 Skill 根目录路径。
     *
     * @return Skill 根目录
     */
    public Path getSkillsDirectory() {
        return skillsDirectory;
    }
}
