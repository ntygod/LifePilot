package com.lifepilot.meta.convenience;

import com.lifepilot.meta.config.MetaProperties;
import com.lifepilot.skill.MarkdownSkillParser;
import com.lifepilot.skill.MarkdownSkillParser.ParsedSkill;
import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.install.SkillInstallation;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

/**
 * 内置（BUILTIN）Skill 安装器 —— 启动时扫描 classpath 下 {@code skills/*&#47;SKILL.md}，
 * 通过 {@link SkillInstaller} 完成"校验 → 写文件 → upsert skills 表"三步，并注册到
 * {@link SkillRegistry} 使激活器（SkillActivator / ContextAssembler）可见。
 *
 * <p>Phase B.3 重接：本 Bean 不再"只复制 SKILL.md 到用户目录"，而是完整走
 * {@link SkillInstaller#install} 流水线 —— 解析、校验、落盘、upsert。
 * 解析/校验失败（例如 26 个内置 Skill 尚未迁移到 v2 格式）只记 WARN 并跳过，
 * 不阻断其他 BUILTIN 安装，也不阻塞应用启动。</p>
 *
 * <p>当前 classpath 下所有 Skill 目录仅含 SKILL.md，无 references/scripts/assets
 * 辅助文件，因此暂不处理附属文件复制（TODO：Phase E 冒烟前若出现带资产的 BUILTIN 再补）。</p>
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
    private final MarkdownSkillParser parser;
    private final SkillRegistry skillRegistry;

    public SkillDiscoveryRegistrar(MetaProperties properties,
                                   SkillConfigProperties skillConfig,
                                   SkillInstaller installer,
                                   MarkdownSkillParser parser,
                                   SkillRegistry skillRegistry) {
        this.properties = properties;
        this.skillConfig = skillConfig;
        this.installer = installer;
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

    /**
     * 扫描 classpath 下所有 BUILTIN SKILL.md，逐个走 {@link SkillInstaller} 安装 + {@link SkillRegistry} 注册。
     *
     * <p>每个 Skill 相互独立：任一失败不影响其他继续安装。老格式（{@code id:} / 非 v2 name
     * 正则）由 parser 抛 {@link IllegalArgumentException}，此处只 WARN 跳过，等 Phase D 逐个迁移。</p>
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
        int skipped = 0;
        for (Resource resource : resources) {
            String skillName = extractSkillName(resource);
            if (skillName == null) {
                skipped++;
                continue;
            }
            try {
                String content = resource.getContentAsString(StandardCharsets.UTF_8);
                SkillInstallation install = installer.install(new SkillInstaller.InstallRequest(
                        SkillSourceType.BUILTIN,
                        "classpath:skills/" + skillName,
                        null,
                        content,
                        skillsRoot));
                registerToRegistry(content, Path.of(install.filePath()));
                installed++;
                log.info("BUILTIN Skill 已安装: name={}, path={}", install.name(), install.filePath());
            } catch (IllegalArgumentException e) {
                // 解析/校验失败（多数为 26 个内置 Skill 仍是老 v1 格式），跳过不阻断启动
                log.warn("BUILTIN Skill 跳过（parse/validate 失败，待 Phase D 迁移）: resource={}, error={}",
                        resource.getFilename(), e.getMessage());
                skipped++;
            } catch (Exception e) {
                log.warn("BUILTIN Skill 安装异常: resource={}, error={}",
                        resource.getFilename(), e.getMessage());
                skipped++;
            }
        }
        log.info("BUILTIN Skill 安装完成: installed={}, skipped={}, total={}",
                installed, skipped, resources.length);
    }

    /**
     * 安装成功后，把解析结果映射为 {@link SkillDefinition} 并注册到 {@link SkillRegistry}，
     * 使 SkillActivator / catalog 查找流程可见该 Skill。
     *
     * <p>重复解析一次（install 内部也解析过）以拿到 ParsedSkill 对象 ——
     * SkillInstaller 的 {@link SkillInstallation} 返回值没携带 body/zhiweiMeta，
     * 避免 SkillInstaller 契约膨胀，此处二次 parse 是可接受的代价。</p>
     */
    private void registerToRegistry(String content, Path skillFolder) {
        ParsedSkill parsed = parser.parse(content);
        var fm = parsed.frontmatter();
        var zhiwei = fm.zhiweiMeta();

        Map<String, String> flatMetadata = zhiwei.category() != null
                ? Map.of("category", zhiwei.category())
                : Map.of();

        SkillDefinition definition = SkillDefinition.builder()
                .id(fm.name())
                .name(fm.name())
                .description(fm.description())
                .version(fm.version())
                // BUILTIN 也用 UserDefined 承载路径（SkillSource 暂无 Builtin 变体，保持兼容），
                // 由 SkillInstallationRepository 记录真实 sourceType 为 BUILTIN
                .source(new SkillSource.UserDefined(skillFolder.toString(), Instant.now()))
                .instructions(parsed.body())
                .suggestedTools(zhiwei.suggestedTools())
                .metadata(flatMetadata)
                .zhiweiMeta(zhiwei)
                .build();

        if (!skillRegistry.register(definition)) {
            log.warn("BUILTIN Skill 注册到 Registry 被拒绝: name={}", fm.name());
        }
    }

    /**
     * 从 classpath {@link Resource} URL 中抽取 Skill 目录名（父目录名）。
     *
     * <p>典型 URL 形如 {@code file:/...skills/introspection/SKILL.md} 或
     * {@code jar:file:/...!/skills/introspection/SKILL.md}。</p>
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
