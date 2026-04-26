package com.lifepilot.meta.convenience;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.skill.MarkdownSkillParser;
import com.lifepilot.skill.MarkdownSkillParser.ParsedSkill;
import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.install.SkillInstallation;
import com.lifepilot.skill.install.SkillInstallationRepository;
import com.lifepilot.skill.install.SkillInstaller;
import com.lifepilot.skill.install.SkillSourceType;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.registry.SkillRegistry;
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
 * <h3>版本决策（保留用户修改）</h3>
 * 每个 BUILTIN Skill 启动时按 classpath 与 skills 表已记录版本做三态决策：
 * <ul>
 *   <li><b>INSTALL</b>：表中无该 name —— 首次安装，写入文件 + upsert + 复制 aux</li>
 *   <li><b>UPGRADE</b>：classpath version &gt; 表中 version —— 出厂升级，覆盖式重装并复制 aux</li>
 *   <li><b>KEEP</b>：classpath version &le; 表中 version —— 保留用户本地修改，不写文件、不复制 aux，
 *       但仍重新解析本地 SKILL.md 并 register 到 Registry，确保运行时能看到</li>
 * </ul>
 * 用户编辑本地 SKILL.md 不会被重启覆盖；只有出厂升级（升 version 号）才会同步过来。
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
    private final SkillConfigProperties skillConfig;
    private final SkillInstaller installer;
    private final SkillInstallationRepository installationRepository;
    private final MarkdownSkillParser parser;
    private final SkillRegistry skillRegistry;

    public SkillDiscoveryRegistrar(MetaProperties properties,
                                   SkillConfigProperties skillConfig,
                                   SkillInstaller installer,
                                   SkillInstallationRepository installationRepository,
                                   MarkdownSkillParser parser,
                                   SkillRegistry skillRegistry) {
        this.properties = properties;
        this.skillConfig = skillConfig;
        this.installer = installer;
        this.installationRepository = installationRepository;
        this.parser = parser;
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

    /** Skill 同步决策三态。 */
    private enum SyncAction { INSTALL, UPGRADE, KEEP }

    /**
     * 扫描 classpath 下所有 BUILTIN SKILL.md，按版本决策做 INSTALL / UPGRADE / KEEP。
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

        Path skillsRoot = Path.of(skillConfig.getDirectory());
        int installed = 0;
        int upgraded = 0;
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
                String classpathVersion = classpathParsed.frontmatter().version();

                Optional<SkillInstallation> existing = installationRepository.findByName(skillName);
                SyncAction action = decideAction(classpathVersion, existing);

                switch (action) {
                    case KEEP -> {
                        kept++;
                        registerKeptSkill(existing.orElseThrow(), classpathContent, classpathParsed);
                        log.debug("BUILTIN Skill 保留本地修改: name={}, classpathVersion={}, localVersion={}",
                                skillName, classpathVersion, existing.get().version());
                    }
                    case INSTALL, UPGRADE -> {
                        SkillInstallation install = installer.install(new SkillInstaller.InstallRequest(
                                SkillSourceType.BUILTIN,
                                "classpath:skills/" + skillName,
                                null,
                                classpathContent,
                                skillsRoot));
                        Path skillFolder = Path.of(install.filePath());
                        copyAuxiliaryFiles(skillName, skillFolder);
                        registerToRegistry(classpathContent, skillFolder);
                        if (action == SyncAction.INSTALL) {
                            installed++;
                            log.info("BUILTIN Skill 已安装: name={}, version={}",
                                    install.name(), install.version());
                        } else {
                            upgraded++;
                            log.info("BUILTIN Skill 已升级: name={}, oldVersion={}, newVersion={}",
                                    install.name(),
                                    existing.map(SkillInstallation::version).orElse("?"),
                                    install.version());
                        }
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
        log.info("BUILTIN Skill 同步完成: installed={}, upgraded={}, kept={}, skipped={}, total={}",
                installed, upgraded, kept, skipped, resources.length);
    }

    /**
     * 三态决策：表中无 → INSTALL；classpath 版本号严格大于本地 → UPGRADE；否则 → KEEP。
     */
    private SyncAction decideAction(String classpathVersion, Optional<SkillInstallation> existing) {
        if (existing.isEmpty()) return SyncAction.INSTALL;
        return compareSemver(classpathVersion, existing.get().version()) > 0
                ? SyncAction.UPGRADE
                : SyncAction.KEEP;
    }

    /**
     * 简化的 semver 比较：按 {@code .} 切分逐段比对整数；前导段相同时长度更长视为更新。
     * 仅支持纯数字段，碰到非数字段降级为字典序。
     */
    static int compareSemver(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        String[] aParts = a.split("\\.");
        String[] bParts = b.split("\\.");
        int max = Math.max(aParts.length, bParts.length);
        for (int i = 0; i < max; i++) {
            String ax = i < aParts.length ? aParts[i] : "0";
            String bx = i < bParts.length ? bParts[i] : "0";
            try {
                int ai = Integer.parseInt(ax);
                int bi = Integer.parseInt(bx);
                if (ai != bi) return Integer.compare(ai, bi);
            } catch (NumberFormatException e) {
                int cmp = ax.compareTo(bx);
                if (cmp != 0) return cmp;
            }
        }
        return 0;
    }

    /**
     * KEEP 决策：保留本地 SKILL.md 内容（用户可能已修改），重新 parse 并 register 到 Registry。
     * 本地解析失败时降级用 classpath 版本（保证 Registry 至少能看到这个 skill）。
     */
    private void registerKeptSkill(SkillInstallation existing, String classpathContent,
                                    ParsedSkill classpathParsed) {
        Path skillFolder = Path.of(existing.filePath());
        Path localSkillMd = skillFolder.resolve("SKILL.md");
        try {
            if (Files.exists(localSkillMd)) {
                String localContent = Files.readString(localSkillMd, StandardCharsets.UTF_8);
                ParsedSkill localParsed = parser.parse(localContent);
                registerParsed(localParsed, skillFolder);
                return;
            }
        } catch (Exception e) {
            log.warn("BUILTIN Skill 本地 SKILL.md 解析失败，降级用 classpath 注册: name={}, error={}",
                    existing.name(), e.getMessage());
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
