package com.lifepilot.skill.markdown;

import com.lifepilot.skill.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SKILL.md 解析器 — 解析 YAML Frontmatter + Markdown Body。
 *
 * <p>解析流程：
 * <ol>
 *   <li>查找第一个 {@code ---} 行（必须是文件首行或首行为空后的第一行）</li>
 *   <li>查找第二个 {@code ---} 行，提取之间的内容为 YAML Frontmatter</li>
 *   <li>使用 SnakeYAML 解析 Frontmatter 为 {@code Map<String, Object>}</li>
 *   <li>提取第二个 {@code ---} 之后的所有内容为 Markdown Body（trim 后作为 systemPrompt）</li>
 *   <li>校验必填字段（id、name、description）</li>
 *   <li>校验 systemPrompt 非空</li>
 *   <li>将 Map 字段映射为 {@link SkillDefinition} record</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-03-07
 */
public class MarkdownSkillParser {

    private static final Logger log = LoggerFactory.getLogger(MarkdownSkillParser.class);

    /** Frontmatter 分隔符。 */
    private static final String FRONTMATTER_DELIMITER = "---";

    /** 必填字段列表。 */
    private static final List<String> REQUIRED_FIELDS = List.of("id", "name", "description");

    /**
     * 解析 SKILL.md 内容为 ParseResult。
     *
     * @param content SKILL.md 文件的完整文本内容
     * @return 解析结果
     */
    public ParseResult parse(String content) {
        if (content == null || content.isBlank()) {
            return ParseResult.failure(List.of("SKILL.md 内容不能为空"));
        }

        // 1. 查找 Frontmatter 分隔符
        var lines = content.lines().toList();
        int firstDelimiter = -1;
        int secondDelimiter = -1;

        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.equals(FRONTMATTER_DELIMITER)) {
                if (firstDelimiter == -1) {
                    // 第一个 --- 必须是首行或首行为空后的第一行
                    if (i == 0 || lines.subList(0, i).stream().allMatch(String::isBlank)) {
                        firstDelimiter = i;
                    } else {
                        return ParseResult.failure(List.of("YAML Frontmatter 分隔符 --- 必须在文件首行或首行为空后的第一行"));
                    }
                } else {
                    secondDelimiter = i;
                    break;
                }
            } else if (firstDelimiter == -1 && !trimmed.isEmpty()) {
                // 第一个非空行不是 ---，说明没有 Frontmatter
                return ParseResult.failure(List.of("缺少 YAML Frontmatter 分隔符 ---"));
            }
        }

        if (firstDelimiter == -1 || secondDelimiter == -1) {
            return ParseResult.failure(List.of("缺少 YAML Frontmatter 分隔符 ---"));
        }

        // 2. 提取 Frontmatter YAML 内容
        var yamlLines = lines.subList(firstDelimiter + 1, secondDelimiter);
        String yamlContent = String.join("\n", yamlLines);

        // 3. 使用 SnakeYAML 解析
        Map<String, Object> frontmatterMap;
        try {
            var yaml = new Yaml();
            Object parsed = yaml.load(yamlContent);
            if (parsed == null) {
                frontmatterMap = Map.of();
            } else if (parsed instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                var typedMap = (Map<String, Object>) map;
                frontmatterMap = typedMap;
            } else {
                return ParseResult.failure(List.of("YAML Frontmatter 格式错误: 期望 Map 结构"));
            }
        } catch (Exception e) {
            log.warn("YAML Frontmatter 解析失败: {}", e.getMessage());
            return ParseResult.failure(List.of("YAML 语法错误: " + e.getMessage()));
        }

        // 4. 提取 Markdown Body
        String markdownBody = "";
        if (secondDelimiter + 1 < lines.size()) {
            markdownBody = String.join("\n", lines.subList(secondDelimiter + 1, lines.size())).trim();
        }

        // 5. 校验必填字段
        var errors = new ArrayList<String>();
        for (String field : REQUIRED_FIELDS) {
            Object value = frontmatterMap.get(field);
            if (value == null || value.toString().isBlank()) {
                errors.add("缺少必填字段: " + field);
            }
        }

        // 6. 校验 systemPrompt 非空
        if (markdownBody.isEmpty()) {
            errors.add("System Prompt 不能为空");
        }

        if (!errors.isEmpty()) {
            return ParseResult.failure(errors, frontmatterMap);
        }

        // 7. 映射字段到 SkillDefinition
        try {
            SkillDefinition definition = mapToDefinition(frontmatterMap, markdownBody);
            return ParseResult.success(definition, frontmatterMap);
        } catch (Exception e) {
            log.warn("SkillDefinition 构建失败: {}", e.getMessage());
            return ParseResult.failure(List.of("SkillDefinition 构建失败: " + e.getMessage()), frontmatterMap);
        }
    }

    // ─────────────────────────────────────────────
    //  字段映射
    // ─────────────────────────────────────────────

    /**
     * 将 Frontmatter Map + Markdown Body 映射为 SkillDefinition。
     */
    private SkillDefinition mapToDefinition(Map<String, Object> fm, String systemPrompt) {
        String id = getString(fm, "id");
        String name = getString(fm, "name");
        String description = getString(fm, "description");
        String version = getStringOrDefault(fm, "version", "1.0.0");
        List<String> allowedTools = getStringList(fm, "allowed-tools");
        ExecutionStrategy execution = parseExecution(fm);
        MemoryAccessPolicy memoryAccess = parseMemoryAccess(fm);
        SkillBudget budget = parseBudget(fm);
        Map<String, String> metadata = parseMetadata(fm);

        return SkillDefinition.builder()
                .id(id)
                .name(name)
                .description(description)
                .version(version)
                .source(new SkillSource.UserDefined("", null))
                .systemPrompt(systemPrompt)
                .allowedTools(allowedTools)
                .execution(execution)
                .memoryAccess(memoryAccess)
                .budget(budget)
                .metadata(metadata)
                .build();
    }

    @SuppressWarnings("unchecked")
    private ExecutionStrategy parseExecution(Map<String, Object> fm) {
        var executionNode = fm.get("execution");
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
    private MemoryAccessPolicy parseMemoryAccess(Map<String, Object> fm) {
        var memoryNode = fm.get("memory-access");
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
    private SkillBudget parseBudget(Map<String, Object> fm) {
        var budgetNode = fm.get("budget");
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

    private Map<String, String> parseMetadata(Map<String, Object> fm) {
        var metadataNode = fm.get("metadata");
        if (!(metadataNode instanceof Map<?, ?> metaMap)) {
            return Map.of();
        }
        var result = new java.util.HashMap<String, String>();
        for (var entry : metaMap.entrySet()) {
            result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        return Map.copyOf(result);
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

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

    // ─────────────────────────────────────────────
    //  解析结果
    // ─────────────────────────────────────────────

    /**
     * 解析结果。
     *
     * @param success        是否成功
     * @param definition     解析出的 SkillDefinition（成功时非 null）
     * @param errors         错误信息列表
     * @param frontmatterMap 解析出的 Frontmatter Map（用于验证管线）
     */
    public record ParseResult(
            boolean success,
            @Nullable SkillDefinition definition,
            List<String> errors,
            @Nullable Map<String, Object> frontmatterMap
    ) {

        /** 紧凑构造器 — 防御性拷贝。 */
        public ParseResult {
            errors = List.copyOf(errors);
        }

        /** 构建成功结果。 */
        static ParseResult success(SkillDefinition definition, Map<String, Object> frontmatterMap) {
            return new ParseResult(true, definition, List.of(), frontmatterMap);
        }

        /** 构建失败结果（无 frontmatterMap）。 */
        static ParseResult failure(List<String> errors) {
            return new ParseResult(false, null, errors, null);
        }

        /** 构建失败结果（含 frontmatterMap）。 */
        static ParseResult failure(List<String> errors, Map<String, Object> frontmatterMap) {
            return new ParseResult(false, null, errors, frontmatterMap);
        }
    }
}
