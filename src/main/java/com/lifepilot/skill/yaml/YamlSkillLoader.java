package com.lifepilot.skill.yaml;

import com.lifepilot.skill.config.SkillConfigProperties;
import com.lifepilot.skill.model.*;
import com.lifepilot.skill.registry.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * YAML Skill 加载器 — 扫描目录并解析 .yml/.yaml 文件为 SkillDefinition。
 *
 * <p>单个文件解析失败不影响其他文件加载，记录 WARN 日志后跳过。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class YamlSkillLoader {

    private static final Logger log = LoggerFactory.getLogger(YamlSkillLoader.class);

    /** 时间范围格式：数字 + 后缀（d/h/m）。 */
    private static final Pattern TIME_RANGE_PATTERN = Pattern.compile("^(\\d+)([dhm])$");

    private final YamlSchemaValidator schemaValidator;
    private final SkillRegistry skillRegistry;
    private final SkillConfigProperties config;
    private final Path skillsDirectory;

    public YamlSkillLoader(YamlSchemaValidator schemaValidator,
                           SkillRegistry skillRegistry,
                           SkillConfigProperties config) {
        this.schemaValidator = schemaValidator;
        this.skillRegistry = skillRegistry;
        this.config = config;
        this.skillsDirectory = Path.of(config.getDirectory());
    }

    /**
     * 用于测试的构造器 — 允许指定自定义目录路径。
     */
    YamlSkillLoader(YamlSchemaValidator schemaValidator,
                    SkillRegistry skillRegistry,
                    SkillConfigProperties config,
                    Path skillsDirectory) {
        this.schemaValidator = schemaValidator;
        this.skillRegistry = skillRegistry;
        this.config = config;
        this.skillsDirectory = skillsDirectory;
    }

    /**
     * 扫描目录下所有 .yml/.yaml 文件并加载。
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
        try (Stream<Path> files = Files.list(skillsDirectory)) {
            var yamlFiles = files
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".yml") || name.endsWith(".yaml");
                    })
                    .toList();

            for (Path file : yamlFiles) {
                Optional<SkillDefinition> result = loadFile(file);
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

        log.info("Skill 加载完成: 成功={}, 目录={}", count, skillsDirectory);
        return count;
    }

    /**
     * 加载单个 YAML 文件。
     *
     * @param filePath YAML 文件路径
     * @return 解析后的 SkillDefinition，失败返回 Optional.empty()
     */
    public Optional<SkillDefinition> loadFile(Path filePath) {
        try {
            // 1. 读取并解析 YAML
            String content = Files.readString(filePath);
            var yaml = new Yaml();
            Map<String, Object> yamlMap;
            try {
                Object parsed = yaml.load(content);
                if (!(parsed instanceof Map<?, ?> map)) {
                    log.warn("YAML 文件解析结果不是 Map 类型: file={}", filePath);
                    return Optional.empty();
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> castMap = (Map<String, Object>) map;
                yamlMap = castMap;
            } catch (Exception e) {
                log.warn("YAML 文件语法错误: file={}, error={}", filePath, e.getMessage());
                return Optional.empty();
            }

            // 2. Schema 校验
            var validationResult = schemaValidator.validate(yamlMap);
            if (!validationResult.valid()) {
                log.warn("YAML Schema 校验失败: file={}, errors={}", filePath, validationResult.errors());
                return Optional.empty();
            }

            // 3. 转换为 SkillDefinition
            return Optional.of(convertToDefinition(yamlMap, filePath));
        } catch (IOException e) {
            log.warn("读取 YAML 文件失败: file={}, error={}", filePath, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("加载 YAML Skill 失败: file={}, error={}", filePath, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 从 YAML Map 转换为 SkillDefinition。
     *
     * <p>处理字段映射：
     * <ul>
     *   <li>system-prompt → systemPrompt</li>
     *   <li>allowed-tools → allowedTools</li>
     *   <li>memory-access.read[].time-range → Duration 转换</li>
     *   <li>execution/budget 缺省时使用 DEFAULT</li>
     * </ul></p>
     */
    @SuppressWarnings("unchecked")
    SkillDefinition convertToDefinition(Map<String, Object> yamlMap, Path filePath) {
        var skill = (Map<String, Object>) yamlMap.get("skill");

        String id = getString(skill, "id");
        String name = getString(skill, "name");
        String description = getString(skill, "description");
        String version = getStringOrDefault(skill, "version", "1.0.0");
        String systemPrompt = getString(skill, "system-prompt");
        List<String> allowedTools = getStringList(skill, "allowed-tools");

        // 解析 execution（缺省使用 DEFAULT）
        ExecutionStrategy execution = parseExecution(skill);

        // 解析 memory-access（缺省使用 none）
        MemoryAccessPolicy memoryAccess = parseMemoryAccess(skill);

        // 解析 budget（缺省使用 DEFAULT）
        SkillBudget budget = parseBudget(skill);

        // 解析 metadata
        Map<String, String> metadata = parseMetadata(skill);

        // 构建 source
        Instant lastModified = getLastModified(filePath);
        var source = new SkillSource.UserDefined(filePath.toString(), lastModified);

        return SkillDefinition.builder()
                .id(id)
                .name(name)
                .description(description)
                .version(version)
                .source(source)
                .systemPrompt(systemPrompt)
                .allowedTools(allowedTools)
                .execution(execution)
                .memoryAccess(memoryAccess)
                .budget(budget)
                .metadata(metadata)
                .build();
    }

    /**
     * 解析时间范围字符串为 Duration。
     *
     * <p>支持格式：7d（天）、24h（小时）、30m（分钟）。</p>
     *
     * @param timeRange 时间范围字符串
     * @return 对应的 Duration
     * @throws IllegalArgumentException 格式不合法时抛出
     */
    static Duration parseTimeRange(String timeRange) {
        if (timeRange == null || timeRange.isBlank()) {
            throw new IllegalArgumentException("时间范围不能为空");
        }
        var matcher = TIME_RANGE_PATTERN.matcher(timeRange.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("时间范围格式不合法，支持 Nd/Nh/Nm: " + timeRange);
        }
        long value = Long.parseLong(matcher.group(1));
        String unit = matcher.group(2);
        return switch (unit) {
            case "d" -> Duration.ofDays(value);
            case "h" -> Duration.ofHours(value);
            case "m" -> Duration.ofMinutes(value);
            default -> throw new IllegalArgumentException("不支持的时间单位: " + unit);
        };
    }

    // ─────────────────────────────────────────────
    //  内部辅助方法
    // ─────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private ExecutionStrategy parseExecution(Map<String, Object> skill) {
        var executionNode = skill.get("execution");
        if (!(executionNode instanceof Map<?, ?> execMap)) {
            return ExecutionStrategy.DEFAULT;
        }
        var exec = (Map<String, Object>) execMap;

        int maxSteps = getIntOrDefault(exec, "max-steps", ExecutionStrategy.DEFAULT.maxSteps());
        int timeoutSeconds = getIntOrDefault(exec, "timeout-seconds", ExecutionStrategy.DEFAULT.timeoutSeconds());
        boolean requireConfirmation = getBooleanOrDefault(exec, "require-confirmation", ExecutionStrategy.DEFAULT.requireConfirmation());

        // 解析 retry 策略
        ExecutionStrategy.RetryPolicy retryPolicy = ExecutionStrategy.DEFAULT.retryPolicy();
        var retryNode = exec.get("retry");
        if (retryNode instanceof String retryStr) {
            retryPolicy = switch (retryStr.toUpperCase()) {
                case "NONE" -> ExecutionStrategy.RetryPolicy.NONE;
                case "DEFAULT" -> ExecutionStrategy.RetryPolicy.DEFAULT;
                default -> ExecutionStrategy.DEFAULT.retryPolicy();
            };
        }

        // 解析确认模式
        ExecutionStrategy.ConfirmationMode confirmationMode = ExecutionStrategy.DEFAULT.confirmationMode();
        var confirmNode = exec.get("confirmation-mode");
        if (confirmNode instanceof String confirmStr) {
            confirmationMode = switch (confirmStr.toUpperCase()) {
                case "NONE" -> ExecutionStrategy.ConfirmationMode.NONE;
                case "FIRST_RUN" -> ExecutionStrategy.ConfirmationMode.FIRST_RUN;
                case "ALWAYS" -> ExecutionStrategy.ConfirmationMode.ALWAYS;
                default -> ExecutionStrategy.DEFAULT.confirmationMode();
            };
        }

        return new ExecutionStrategy(maxSteps, timeoutSeconds, requireConfirmation, retryPolicy, confirmationMode);
    }

    @SuppressWarnings("unchecked")
    private MemoryAccessPolicy parseMemoryAccess(Map<String, Object> skill) {
        var memoryNode = skill.get("memory-access");
        if (!(memoryNode instanceof Map<?, ?> memMap)) {
            return MemoryAccessPolicy.none();
        }
        var memory = (Map<String, Object>) memMap;

        // 解析 read 权限
        List<MemoryReadPermission> readPermissions = new ArrayList<>();
        var readNode = memory.get("read");
        if (readNode instanceof List<?> readList) {
            for (Object item : readList) {
                if (item instanceof Map<?, ?> readItem) {
                    var readMap = (Map<String, Object>) readItem;
                    String layer = getString(readMap, "layer");
                    List<String> entityTypes = getStringList(readMap, "entity-types");
                    String timeRange = getStringOrNull(readMap, "time-range");
                    readPermissions.add(new MemoryReadPermission(layer, entityTypes, timeRange));
                }
            }
        }

        // 解析 write 权限
        List<MemoryWritePermission> writePermissions = new ArrayList<>();
        var writeNode = memory.get("write");
        if (writeNode instanceof List<?> writeList) {
            for (Object item : writeList) {
                if (item instanceof Map<?, ?> writeItem) {
                    var writeMap = (Map<String, Object>) writeItem;
                    String layer = getString(writeMap, "layer");
                    List<String> entityTypes = getStringList(writeMap, "entity-types");
                    boolean requireApproval = getBooleanOrDefault(writeMap, "require-approval", false);
                    writePermissions.add(new MemoryWritePermission(layer, entityTypes, requireApproval));
                }
            }
        }

        return new MemoryAccessPolicy(readPermissions, writePermissions);
    }

    @SuppressWarnings("unchecked")
    private SkillBudget parseBudget(Map<String, Object> skill) {
        var budgetNode = skill.get("budget");
        if (!(budgetNode instanceof Map<?, ?> budgetMap)) {
            return SkillBudget.DEFAULT;
        }
        var budget = (Map<String, Object>) budgetMap;

        int maxTokens = getIntOrDefault(budget, "max-tokens", SkillBudget.DEFAULT.maxTokens());
        int maxSteps = getIntOrDefault(budget, "max-steps", SkillBudget.DEFAULT.maxSteps());
        int timeoutSeconds = getIntOrDefault(budget, "timeout-seconds", SkillBudget.DEFAULT.timeoutSeconds());
        int maxCostCents = getIntOrDefault(budget, "max-cost-cents", SkillBudget.DEFAULT.maxCostCents());

        return new SkillBudget(maxTokens, maxSteps, timeoutSeconds, maxCostCents);
    }

    private Map<String, String> parseMetadata(Map<String, Object> skill) {
        var metadataNode = skill.get("metadata");
        if (!(metadataNode instanceof Map<?, ?> metaMap)) {
            return Map.of();
        }
        var result = new HashMap<String, String>();
        for (var entry : metaMap.entrySet()) {
            result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        return result;
    }

    @Nullable
    private Instant getLastModified(Path filePath) {
        try {
            return Files.getLastModifiedTime(filePath).toInstant();
        } catch (IOException e) {
            return null;
        }
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : "";
    }

    private String getStringOrDefault(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    @Nullable
    private String getStringOrNull(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private List<String> getStringList(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }

    private int getIntOrDefault(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return defaultValue;
    }

    private boolean getBooleanOrDefault(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return defaultValue;
    }
}
