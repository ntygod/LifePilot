package com.lifepilot.skill.markdown;

import com.lifepilot.skill.MarkdownSkillParser;
import com.lifepilot.skill.MarkdownSkillParser.ParsedSkill;
import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.registry.SkillRegistry;
import com.lifepilot.skill.validation.SkillBodyValidator;
import com.lifepilot.skill.validation.SkillDescriptionValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Markdown Skill 加载器 — 扫描 Skill 目录并注册到 {@link SkillRegistry}。
 *
 * <p>用 {@link MarkdownSkillParser} 解析 SKILL.md，经 {@link SkillDescriptionValidator}
 * + {@link SkillBodyValidator} 校验后映射为 {@link SkillDefinition} 注册到 {@link SkillRegistry}。</p>
 *
 * <p>本加载器只走"文件系统 → 内存注册表"的扫描路径。持久化（skills 表 upsert）由
 * {@link com.lifepilot.skill.install.SkillInstaller} 在首次安装时完成，热重载阶段不回写 DB
 * 避免与 {@link SkillFileWatcher} 产生循环事件。</p>
 *
 * @author zsg
 * @since 2026-03-07
 */
public class MarkdownSkillLoader {

    private static final Logger log = LoggerFactory.getLogger(MarkdownSkillLoader.class);

    /** references 子目录名。 */
    private static final String REFERENCES_DIR = "references";

    private final SkillRegistry skillRegistry;
    private final SkillConfigProperties config;
    private final MarkdownSkillParser parser;
    private final SkillDescriptionValidator descriptionValidator;
    private final SkillBodyValidator bodyValidator;
    private final Path skillsDirectory;

    public MarkdownSkillLoader(SkillRegistry skillRegistry,
                               SkillConfigProperties config,
                               MarkdownSkillParser parser,
                               SkillDescriptionValidator descriptionValidator,
                               SkillBodyValidator bodyValidator) {
        this.skillRegistry = skillRegistry;
        this.config = config;
        this.parser = parser;
        this.descriptionValidator = descriptionValidator;
        this.bodyValidator = bodyValidator;
        this.skillsDirectory = Path.of(config.getDirectory());
    }

    /**
     * 用于测试的构造器 — 允许指定自定义目录路径。
     */
    MarkdownSkillLoader(SkillRegistry skillRegistry,
                        SkillConfigProperties config,
                        MarkdownSkillParser parser,
                        SkillDescriptionValidator descriptionValidator,
                        SkillBodyValidator bodyValidator,
                        Path skillsDirectory) {
        this.skillRegistry = skillRegistry;
        this.config = config;
        this.parser = parser;
        this.descriptionValidator = descriptionValidator;
        this.bodyValidator = bodyValidator;
        this.skillsDirectory = skillsDirectory;
    }

    /**
     * 扫描 Skill 根目录下所有子文件夹，逐个加载并注册。
     *
     * <p>包含 {@code auto/} 子目录下的自生成 Skill（作为一级目录同级扫描）。
     * 解析或校验失败的文件夹只记 WARN 并跳过，不阻断其他 Skill 加载。</p>
     *
     * @return 成功注册到 {@link SkillRegistry} 的 Skill 数量
     */
    public int loadAll() {
        if (!Files.exists(skillsDirectory) || !Files.isDirectory(skillsDirectory)) {
            log.debug("Skill 目录不存在，跳过加载: path={}", skillsDirectory);
            return 0;
        }

        int loaded = loadFromDirectory(skillsDirectory);

        // 额外扫描 auto/ 子目录（自生成 Skill 的默认安装位置）
        Path autoDir = skillsDirectory.resolve("auto");
        if (Files.exists(autoDir) && Files.isDirectory(autoDir)) {
            loaded += loadFromDirectory(autoDir);
        }

        log.info("MarkdownSkillLoader 扫描完成: loaded={}, directory={}", loaded, skillsDirectory);
        return loaded;
    }

    /**
     * 扫描指定目录下所有包含 {@code SKILL.md} 的子目录并加载。
     */
    private int loadFromDirectory(Path dir) {
        int count = 0;
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path sub : (Iterable<Path>) entries::iterator) {
                if (!Files.isDirectory(sub)) continue;
                if (!Files.exists(sub.resolve(config.getSkillFilename()))) continue;
                // 避免将 auto/ 当做 skill 自身加载
                if (sub.getFileName().toString().equals("auto")) continue;
                Optional<SkillDefinition> def = loadFolder(sub);
                if (def.isPresent() && skillRegistry.register(def.get())) {
                    count++;
                }
            }
        } catch (IOException e) {
            log.warn("扫描 Skill 目录失败: dir={}, error={}", dir, e.getMessage());
        }
        return count;
    }

    /**
     * 加载单个 Skill 文件夹（解析 + 校验 + 构造 {@link SkillDefinition}）。
     *
     * <p>解析/校验失败时记 WARN 并返回空，不抛异常（热加载保留上一个有效版本）。</p>
     *
     * @param skillFolder Skill 文件夹路径（需包含 {@code SKILL.md}）
     * @return 解析出的 {@link SkillDefinition}，失败返回 {@link Optional#empty()}
     */
    public Optional<SkillDefinition> loadFolder(Path skillFolder) {
        Path skillFile = skillFolder.resolve(config.getSkillFilename());
        if (!Files.exists(skillFile)) {
            log.debug("文件夹中不存在 {}: folder={}", config.getSkillFilename(), skillFolder);
            return Optional.empty();
        }
        try {
            String content = Files.readString(skillFile, StandardCharsets.UTF_8);
            ParsedSkill parsed = parser.parse(content);
            descriptionValidator.validate(parsed.frontmatter().description());
            bodyValidator.validate(parsed.body());
            return Optional.of(toDefinition(parsed, skillFolder));
        } catch (IllegalArgumentException e) {
            // 解析/校验失败：老格式或结构不符合 v2 规范
            log.warn("Skill 解析/校验失败，跳过: folder={}, error={}", skillFolder, e.getMessage());
            return Optional.empty();
        } catch (IOException e) {
            log.warn("读取 SKILL.md 失败: folder={}, error={}", skillFolder, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Skill 加载异常，跳过: folder={}, error={}", skillFolder, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 读取 Skill 文件夹下 {@code references/} 目录的全部文件内容。
     *
     * @param skillFolder Skill 文件夹路径
     * @return 文件名 → 内容映射；无 references 目录或读取失败时返回空 Map
     */
    public Map<String, String> loadReferences(Path skillFolder) {
        Path referencesDir = skillFolder.resolve(REFERENCES_DIR);
        if (!Files.exists(referencesDir) || !Files.isDirectory(referencesDir)) {
            return Map.of();
        }
        var result = new LinkedHashMap<String, String>();
        try (Stream<Path> files = Files.list(referencesDir)) {
            files.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(file -> {
                        try {
                            result.put(file.getFileName().toString(),
                                    Files.readString(file, StandardCharsets.UTF_8));
                        } catch (IOException e) {
                            log.warn("读取 reference 文件失败: file={}, error={}", file, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("扫描 references 目录失败: dir={}, error={}", referencesDir, e.getMessage());
            return Map.of();
        }
        return Map.copyOf(result);
    }

    /**
     * 获取 Skill 根目录路径。
     */
    public Path getSkillsDirectory() {
        return skillsDirectory;
    }

    /**
     * 将 {@link ParsedSkill} 映射为运行期 {@link SkillDefinition}。
     *
     * <p>约定：
     * <ul>
     *   <li>{@code id} 取自 frontmatter.name（v2 规范 name 已取代老 id）</li>
     *   <li>{@code instructions} 取自 body 原文（含标题和工作流段落）</li>
     *   <li>{@code source} 用 {@link SkillSource.UserDefined} 承载</li>
     *   <li>{@code suggestedTools} / {@code zhiweiMeta} 直接转自 frontmatter 的 metadata.zhiwei 块</li>
     * </ul>
     */
    private SkillDefinition toDefinition(ParsedSkill parsed, Path skillFolder) {
        var fm = parsed.frontmatter();
        var zhiwei = fm.zhiweiMeta();

        return SkillDefinition.builder()
                .id(fm.name())
                .name(fm.name())
                .description(fm.description())
                .version(fm.version())
                .source(new SkillSource.UserDefined(skillFolder.toString(),
                        safeLastModified(skillFolder.resolve(config.getSkillFilename()))))
                .instructions(parsed.body())
                .suggestedTools(zhiwei.suggestedTools())
                .metadata(Map.of())
                .zhiweiMeta(zhiwei)
                .build();
    }

    /**
     * 读取文件最后修改时间，失败返回 null（UserDefined.lastModified 允许为空）。
     */
    private static Instant safeLastModified(Path file) {
        try {
            return Files.exists(file) ? Files.getLastModifiedTime(file).toInstant() : null;
        } catch (IOException e) {
            return null;
        }
    }
}
