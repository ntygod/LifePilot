package com.lifepilot.meta.convenience;

import com.lifepilot.config.path.ZhiweiPaths;
import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.skill.MarkdownSkillParser;
import com.lifepilot.skill.MarkdownSkillParser.ParsedSkill;
import com.lifepilot.skill.install.SkillInstallation;
import com.lifepilot.skill.install.SkillInstallationRepository;
import com.lifepilot.skill.install.SkillInstaller;
import com.lifepilot.skill.install.SkillSourceType;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.skill.validation.SkillValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * 内置（BUILTIN）Skill 安装器 —— 启动时扫描 classpath 下 {@code skills/*&#47;SKILL.md}，
 * 通过 {@link SkillInstaller} 完成"校验 → 写文件 → upsert skills 表"三步，并注册到
 * {@link SkillRegistry} 使激活器（SkillActivator / ContextAssembler）可见。
 *
 * <h3>启动决策（本地优先，永不覆盖）</h3>
 * 每个 BUILTIN Skill 启动时只做二态决策，且<strong>不看版本号</strong>：
 * <ul>
 *   <li><b>INSTALL</b>：skills 表无该 name <em>且</em>本地无 {@code <name>/SKILL.md} —— 首次安装，
 *       写入文件 + upsert + 复制 aux</li>
 *   <li><b>KEEP</b>：skills 表已有该 name <em>或</em>本地已存在 {@code <name>/SKILL.md} —— 一律保留本地，
 *       不写文件、不复制 aux，只重新解析本地 SKILL.md 并 register 到 Registry，确保运行时能看到</li>
 * </ul>
 * 只要本地已经装过（表里有记录，或磁盘上已有文件），启动就绝不覆盖 —— 用户对 SKILL.md /
 * references 的任何手改都不会被重启抹掉。出厂升级（升 version 号）不再在启动时自动同步，
 * 改由用户显式触发的升级入口处理（后续 PR）。
 *
 * <p>解析/校验失败（罕见，多为用户手改坏了 SKILL.md）只记 WARN 跳过，不阻断启动。</p>
 *
 * @author zsg
 * @since 2026-03-08
 */
public class SkillDiscoveryRegistrar implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SkillDiscoveryRegistrar.class);

    private static final String SKILLS_RESOURCE_PATTERN = "classpath:skills/*/SKILL.md";

    private final MetaProperties properties;
    private final ZhiweiPaths zhiweiPaths;
    private final SkillInstaller installer;
    private final SkillInstallationRepository installationRepository;
    private final MarkdownSkillParser parser;
    private final SkillValidator validator;
    private final SkillRegistry skillRegistry;

    public SkillDiscoveryRegistrar(MetaProperties properties,
                                   ZhiweiPaths zhiweiPaths,
                                   SkillInstaller installer,
                                   SkillInstallationRepository installationRepository,
                                   MarkdownSkillParser parser,
                                   SkillValidator validator,
                                   SkillRegistry skillRegistry) {
        this.properties = properties;
        this.zhiweiPaths = zhiweiPaths;
        this.installer = installer;
        this.installationRepository = installationRepository;
        this.parser = parser;
        this.validator = validator;
        this.skillRegistry = skillRegistry;
    }

    @Override
    public void afterPropertiesSet() {
        if (!properties.getSkillDiscovery().isEnabled()) {
            log.debug("Skill 发现功能已禁用，跳过 BUILTIN 安装");
            return;
        }
        installAllBuiltinSkills();
    }

    /** Skill 同步决策二态。 */
    private enum SyncAction { INSTALL, KEEP }

    /**
     * 扫描 classpath 下所有 BUILTIN SKILL.md，按本地是否已存在做 INSTALL / KEEP。
     */
    void installAllBuiltinSkills() {
        var resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources;
        try {
            resources = resolver.getResources(SKILLS_RESOURCE_PATTERN);
        } catch (IOException e) {
            log.warn("扫描 classpath skills 目录失败: error={}", e.getMessage());
            return;
        }

        Path skillsRoot = zhiweiPaths.home("skills");
        int installed = 0;
        int kept = 0;
        int skipped = 0;
        for (Resource resource : resources) {
            String skillName = extractSkillName(resource);
            if (skillName == null) {
                skipped++;
                continue;
            }
            try {
                String classpathContent = resource.getContentAsString(StandardCharsets.UTF_8);
                ParsedSkill classpathParsed = parser.parse(classpathContent);
                validator.validate(classpathParsed);

                Optional<SkillInstallation> existing = installationRepository.findByName(skillName);
                // 本地落盘目录：表里有记录以记录为准，否则回退到 skillsRoot/<name>
                Path skillFolder = existing.map(i -> Path.of(i.filePath()))
                        .orElseGet(() -> skillsRoot.resolve(skillName));
                SyncAction action = decideAction(skillName, skillsRoot, existing);

                switch (action) {
                    case KEEP -> {
                        kept++;
                        registerKeptSkill(skillFolder, classpathContent, classpathParsed);
                        log.debug("BUILTIN Skill 保留本地: name={}, folder={}", skillName, skillFolder);
                    }
                    case INSTALL -> {
                        SkillInstallation install = installer.install(new SkillInstaller.InstallRequest(
                                SkillSourceType.BUILTIN,
                                "classpath:skills/" + skillName,
                                null,
                                classpathContent,
                                skillsRoot));
                        Path installedFolder = Path.of(install.filePath());
                        copyAuxiliaryFiles(skillName, installedFolder);
                        registerToRegistry(classpathContent, installedFolder);
                        installed++;
                        log.info("BUILTIN Skill 已安装: name={}, version={}",
                                install.name(), install.version());
                    }
                }
            } catch (IllegalArgumentException e) {
                log.warn("BUILTIN Skill 跳过（parse/validate 失败）: resource={}, error={}",
                        resource.getFilename(), e.getMessage());
                skipped++;
            } catch (Exception e) {
                log.warn("BUILTIN Skill 安装异常: resource={}, error={}",
                        resource.getFilename(), e.getMessage());
                skipped++;
            }
        }
        log.info("BUILTIN Skill 同步完成: installed={}, kept={}, skipped={}, total={}",
                installed, kept, skipped, resources.length);
    }

    /**
     * 二态决策：本地已装（skills 表有记录，<em>或</em>磁盘上已有 {@code <name>/SKILL.md}）→ KEEP；
     * 否则 → INSTALL。启动阶段绝不因版本号差异覆盖本地文件。
     */
    private SyncAction decideAction(String skillName, Path skillsRoot,
                                    Optional<SkillInstallation> existing) {
        if (existing.isPresent()) return SyncAction.KEEP;
        // 表无记录但本地文件已存在（如 DB 被重置 / 用户手动放入）→ 同样视为已装，绝不覆盖
        if (Files.exists(skillsRoot.resolve(skillName).resolve("SKILL.md"))) {
            return SyncAction.KEEP;
        }
        return SyncAction.INSTALL;
    }

    /**
     * KEEP 决策：保留本地 SKILL.md 内容（用户可能已修改），重新 parse 并 register 到 Registry。
     * 本地文件缺失或解析失败时降级用 classpath 版本（保证 Registry 至少能看到这个 skill）。
     */
    private void registerKeptSkill(Path skillFolder, String classpathContent,
                                    ParsedSkill classpathParsed) {
        Path localSkillMd = skillFolder.resolve("SKILL.md");
        try {
            if (Files.exists(localSkillMd)) {
                String localContent = Files.readString(localSkillMd, StandardCharsets.UTF_8);
                ParsedSkill localParsed = parser.parse(localContent);
                validator.validate(localParsed);
                registerParsed(localParsed, skillFolder);
                return;
            }
        } catch (Exception e) {
            log.warn("BUILTIN Skill 本地 SKILL.md 解析失败，降级用 classpath 注册: folder={}, error={}",
                    skillFolder, e.getMessage());
        }
        registerParsed(classpathParsed, skillFolder);
    }

    /**
     * 把 classpath 下 {@code skills/<name>/{references,scripts,assets}/**} 复制到本地安装目录。
     * 仅 INSTALL / UPGRADE 时调用 —— KEEP 决策保留用户对 references 等的修改。
     */
    private void copyAuxiliaryFiles(String skillName, Path skillFolder) {
        var resolver = new PathMatchingResourcePatternResolver();
        for (String sub : new String[]{"references", "scripts", "assets"}) {
            String pattern = "classpath:skills/" + skillName + "/" + sub + "/**";
            Resource[] resources;
            try {
                resources = resolver.getResources(pattern);
            } catch (IOException e) {
                log.debug("BUILTIN Skill 子目录扫描失败: name={}, sub={}, error={}",
                        skillName, sub, e.getMessage());
                continue;
            }
            String prefix = "skills/" + skillName + "/" + sub + "/";
            for (Resource resource : resources) {
                String url;
                try {
                    url = resource.getURL().toString();
                } catch (IOException e) {
                    continue;
                }
                int idx = url.indexOf(prefix);
                if (idx < 0) continue;
                String relative = url.substring(idx + prefix.length());
                if (relative.isEmpty() || relative.endsWith("/")) continue;
                Path target = skillFolder.resolve(sub).resolve(relative).normalize();
                if (!target.startsWith(skillFolder)) continue;  // 防 path traversal
                try {
                    Files.createDirectories(target.getParent());
                    try (InputStream in = resource.getInputStream()) {
                        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    log.warn("BUILTIN Skill 辅助文件复制失败: name={}, file={}, error={}",
                            skillName, relative, e.getMessage());
                }
            }
        }
    }

    /**
     * 安装/升级成功后从 classpath content 重新 parse 注册到 Registry。
     */
    private void registerToRegistry(String content, Path skillFolder) {
        registerParsed(parser.parse(content), skillFolder);
    }

    /**
     * 把 ParsedSkill 映射为 {@link SkillDefinition} 注册到 {@link SkillRegistry}。
     */
    private void registerParsed(ParsedSkill parsed, Path skillFolder) {
        var fm = parsed.frontmatter();
        var zhiwei = fm.zhiweiMeta();

        SkillDefinition definition = SkillDefinition.builder()
                .id(fm.name())
                .name(fm.name())
                .description(fm.description())
                .version(fm.version())
                .source(new SkillSource.UserDefined(skillFolder.toString(), Instant.now()))
                .instructions(parsed.body())
                .suggestedTools(zhiwei.suggestedTools())
                .metadata(Map.of())
                .zhiweiMeta(zhiwei)
                .build();

        if (!skillRegistry.register(definition)) {
            log.warn("BUILTIN Skill 注册到 Registry 被拒绝: name={}", fm.name());
        }
    }

    /**
     * 从 classpath {@link Resource} URL 中抽取 Skill 目录名（父目录名）。
     */
    private String extractSkillName(Resource resource) {
        try {
            String url = resource.getURL().toString();
            String[] parts = url.split("/");
            if (parts.length >= 2) {
                return parts[parts.length - 2];
            }
        } catch (IOException e) {
            log.debug("无法解析 Skill name: resource={}", resource.getFilename());
        }
        return null;
    }
}
