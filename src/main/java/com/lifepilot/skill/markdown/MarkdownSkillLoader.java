package com.lifepilot.skill.markdown;

import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.model.SkillDefinition;
import com.lifepilot.skill.model.SkillSource;
import com.lifepilot.skill.registry.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Markdown Skill 加载器 — 扫描 Skill 目录，加载所有 Skill 文件夹。
 *
 * <p>每个 Skill 以文件夹形式存在，文件夹内包含 {@code SKILL.md} 主定义文件
 * 和可选的 {@code references/} 子目录。加载流程：
 * <ol>
 *   <li>扫描 Skill 根目录下所有子目录</li>
 *   <li>过滤包含 SKILL.md 的子目录（忽略旧格式 .yml 文件）</li>
 *   <li>解析 SKILL.md 并注册到 {@link SkillRegistry}</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-03-07
 */
public class MarkdownSkillLoader {

    private static final Logger log = LoggerFactory.getLogger(MarkdownSkillLoader.class);

    private final MarkdownSkillParser parser;
    private final SkillRegistry skillRegistry;
    private final SkillConfigProperties config;
    private final Path skillsDirectory;

    public MarkdownSkillLoader(MarkdownSkillParser parser,
                               SkillRegistry skillRegistry,
                               SkillConfigProperties config) {
        this.parser = parser;
        this.skillRegistry = skillRegistry;
        this.config = config;
        this.skillsDirectory = Path.of(config.getDirectory());
    }

    /**
     * 用于测试的构造器 — 允许指定自定义目录路径。
     */
    MarkdownSkillLoader(MarkdownSkillParser parser,
                        SkillRegistry skillRegistry,
                        SkillConfigProperties config,
                        Path skillsDirectory) {
        this.parser = parser;
        this.skillRegistry = skillRegistry;
        this.config = config;
        this.skillsDirectory = skillsDirectory;
    }

    /**
     * 扫描 Skill 目录，加载所有包含 SKILL.md 的子文件夹。
     *
     * <p>目录不存在时自动创建。旧格式 .yml 文件被忽略，仅加载文件夹结构的 Skill。
     * 单个文件夹解析失败不影响其他文件夹加载。</p>
     *
     * @return 成功加载的 Skill 数量
     */
    public int loadAll() {
        // 目录不存在时自动创建
        if (!Files.exists(skillsDirectory)) {
            try {
                Files.createDirectories(skillsDirectory);
                log.info("Skill 目录不存在，已自动创建: path={}", skillsDirectory);
            } catch (IOException e) {
                log.warn("创建 Skill 目录失败: path={}, error={}", skillsDirectory, e.getMessage());
                return 0;
            }
        }

        int count = 0;
        String skillFilename = config.getSkillFilename();

        try (Stream<Path> entries = Files.list(skillsDirectory)) {
            var skillFolders = entries
                    .filter(Files::isDirectory)
                    .filter(dir -> !dir.getFileName().toString().equals("auto"))
                    .filter(dir -> Files.exists(dir.resolve(skillFilename)))
                    .toList();

            for (Path folder : skillFolders) {
                Optional<SkillDefinition> result = loadFolder(folder);
                if (result.isPresent()) {
                    boolean registered = skillRegistry.register(result.get());
                    if (registered) {
                        count++;
                    }
                }
            }
        } catch (IOException e) {
            log.warn("扫描 Skill 目录失败: path={}, error={}", skillsDirectory, e.getMessage());
        }

        // 扫描 auto/ 子目录（自生成 Skill）
        Path autoDir = skillsDirectory.resolve("auto");
        if (Files.exists(autoDir) && Files.isDirectory(autoDir)) {
            count += loadSubdirectorySkills(autoDir, "自生成");
        }

        log.info("Skill 加载完成: 成功={}, 目录={}", count, skillsDirectory);
        return count;
    }

    /**
     * 扫描指定父目录下的所有 Skill 子文件夹并加载注册。
     *
     * @param parentDir 父目录路径
     * @param label     日志标签（如 "自生成"）
     * @return 成功加载的 Skill 数量
     */
    private int loadSubdirectorySkills(Path parentDir, String label) {
        int count = 0;
        try (Stream<Path> entries = Files.list(parentDir)) {
            var skillFolders = entries
                    .filter(Files::isDirectory)
                    .filter(dir -> Files.exists(dir.resolve(config.getSkillFilename())))
                    .toList();
            for (Path folder : skillFolders) {
                Optional<SkillDefinition> result = loadFolder(folder);
                if (result.isPresent()) {
                    if (skillRegistry.register(result.get())) count++;
                    else log.warn("{} Skill 注册失败: folder={}", label, folder);
                }
            }
        } catch (IOException e) {
            log.warn("扫描{} Skill 目录失败: path={}, error={}", label, parentDir, e.getMessage());
        }
        log.info("{} Skill 加载完成: 成功={}, 目录={}", label, count, parentDir);
        return count;
    }

    /**
     * 加载单个 Skill 文件夹。
     *
     * <p>读取文件夹中的 SKILL.md 文件，解析为 SkillDefinition，
     * 并构建 {@link SkillSource.UserDefined}（folderPath 指向文件夹路径）。</p>
     *
     * @param skillFolder Skill 文件夹路径
     * @return 解析出的 SkillDefinition，失败返回 Optional.empty()
     */
    public Optional<SkillDefinition> loadFolder(Path skillFolder) {
        Path skillFile = skillFolder.resolve(config.getSkillFilename());

        if (!Files.exists(skillFile)) {
            log.warn("Skill 文件夹中缺少 {}: folder={}", config.getSkillFilename(), skillFolder);
            return Optional.empty();
        }

        try {
            String content = Files.readString(skillFile, StandardCharsets.UTF_8);
            var parseResult = parser.parse(content);

            if (!parseResult.success() || parseResult.definition() == null) {
                log.warn("SKILL.md 解析失败: folder={}, errors={}", skillFolder, parseResult.errors());
                return Optional.empty();
            }

            // 构建 SkillSource.UserDefined，folderPath 指向文件夹路径
            Instant lastModified = getLastModified(skillFile);
            var source = new SkillSource.UserDefined(skillFolder.toString(), lastModified);

            // 用正确的 source 替换解析器中的占位 source
            SkillDefinition definition = parseResult.definition().toBuilder()
                    .source(source)
                    .build();

            return Optional.of(definition);
        } catch (IOException e) {
            log.error("读取 SKILL.md 文件失败: folder={}, error={}", skillFolder, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("加载 Skill 文件夹失败: folder={}, error={}", skillFolder, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 加载 Skill 文件夹中的 references 目录内容。
     *
     * <p>如果 {@code {skillFolder}/references/} 目录存在，读取其中所有文件
     * 为 {@code Map<String, String>}（文件名 → 内容）。不存在时返回空 Map。</p>
     *
     * @param skillFolder Skill 文件夹路径
     * @return references 文件内容的不可变 Map（文件名 → 内容），无 references 目录时返回空 Map
     */
    public Map<String, String> loadReferences(Path skillFolder) {
        Path referencesDir = skillFolder.resolve("references");

        if (!Files.exists(referencesDir) || !Files.isDirectory(referencesDir)) {
            return Map.of();
        }

        var result = new HashMap<String, String>();
        try (Stream<Path> files = Files.list(referencesDir)) {
            var refFiles = files.filter(Files::isRegularFile).toList();
            for (Path file : refFiles) {
                try {
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    result.put(file.getFileName().toString(), content);
                } catch (IOException e) {
                    log.warn("读取 reference 文件失败: file={}, error={}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("扫描 references 目录失败: dir={}, error={}", referencesDir, e.getMessage());
        }

        return Map.copyOf(result);
    }

    /**
     * 获取 Skill 根目录路径。
     *
     * @return Skill 根目录
     */
    public Path getSkillsDirectory() {
        return skillsDirectory;
    }

    // ─────────────────────────────────────────────
    //  内部辅助方法
    // ─────────────────────────────────────────────

    private Instant getLastModified(Path filePath) {
        try {
            return Files.getLastModifiedTime(filePath).toInstant();
        } catch (IOException e) {
            return null;
        }
    }
}
