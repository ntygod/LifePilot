package com.lifepilot.multiagent.loader;

import com.lifepilot.multiagent.config.MultiAgentProperties;
import com.lifepilot.multiagent.model.AgentBudget;
import com.lifepilot.multiagent.model.AgentDefinition;
import com.lifepilot.multiagent.model.AgentSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Agent Markdown 定义文件解析器。
 *
 * <p>解析 YAML Frontmatter + Markdown 正文格式的 Agent 定义文件。
 * Frontmatter 映射为结构化字段，Markdown 正文作为 System Prompt。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
public class AgentMarkdownParser {

    private static final Logger log = LoggerFactory.getLogger(AgentMarkdownParser.class);
    private static final String FRONTMATTER_DELIMITER = "---";

    private final MultiAgentProperties.BudgetDefaults budgetDefaults;

    public AgentMarkdownParser(MultiAgentProperties config) {
        this.budgetDefaults = config.getBudget();
    }

    /**
     * 解析 Markdown 文件内容为 AgentDefinition。
     *
     * @param content  文件内容
     * @param filePath 文件路径
     * @return 解析成功返回 AgentDefinition，失败返回 empty
     */
    public Optional<AgentDefinition> parse(String content, Path filePath) {
        return parse(content, filePath, null);
    }

    /**
     * 解析 Markdown 文件内容为 AgentDefinition（指定 lastModified）。
     *
     * @param content      文件内容
     * @param filePath     文件路径
     * @param lastModified 文件最后修改时间（可选）
     * @return 解析成功返回 AgentDefinition，失败返回 empty
     */
    @SuppressWarnings("unchecked")
    public Optional<AgentDefinition> parse(String content, Path filePath,
                                           @Nullable Instant lastModified) {
        if (content == null || content.isBlank()) {
            log.warn("Agent 定义文件内容为空: path={}", filePath);
            return Optional.empty();
        }

        // 分离 YAML Frontmatter 和 Markdown 正文
        var parts = splitFrontmatter(content);
        if (parts == null) {
            log.warn("Agent 定义文件缺少 YAML Frontmatter: path={}", filePath);
            return Optional.empty();
        }

        // 解析 YAML Frontmatter
        Map<String, Object> frontmatter;
        try {
            var yaml = new Yaml();
            frontmatter = yaml.load(parts.yamlContent());
            if (frontmatter == null) {
                log.warn("Agent 定义文件 YAML Frontmatter 为空: path={}", filePath);
                return Optional.empty();
            }
        } catch (YAMLException e) {
            log.warn("Agent 定义文件 YAML 解析失败: path={}, error={}", filePath, e.getMessage());
            return Optional.empty();
        }

        // 提取必填字段
        String id = getStringField(frontmatter, "id");
        String name = getStringField(frontmatter, "name");
        String description = getStringField(frontmatter, "description");
        String systemPrompt = parts.markdownContent().strip();

        if (id == null || id.isBlank()) {
            log.warn("Agent 定义文件缺少 id 字段: path={}", filePath);
            return Optional.empty();
        }
        if (name == null || name.isBlank()) {
            log.warn("Agent 定义文件缺少 name 字段: path={}", filePath);
            return Optional.empty();
        }
        if (description == null || description.isBlank()) {
            log.warn("Agent 定义文件缺少 description 字段: path={}", filePath);
            return Optional.empty();
        }
        if (systemPrompt.isBlank()) {
            log.warn("Agent 定义文件正文为空（System Prompt 缺失）: path={}", filePath);
            return Optional.empty();
        }

        // 提取可选字段
        List<String> allowedTools = frontmatter.containsKey("allowed-tools")
                ? ((List<String>) frontmatter.get("allowed-tools"))
                : List.of();

        AgentBudget budget = parseBudget(frontmatter);

        String preferredProvider = getStringField(frontmatter, "preferred-provider");

        Map<String, String> metadata = frontmatter.containsKey("metadata")
                ? ((Map<String, Object>) frontmatter.get("metadata")).entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey,
                            e -> String.valueOf(e.getValue())))
                : Map.of();

        // 构建 AgentDefinition
        try {
            var definition = AgentDefinition.builder()
                    .id(id)
                    .name(name)
                    .description(description)
                    .systemPrompt(systemPrompt)
                    .allowedTools(allowedTools)
                    .budget(budget)
                    .preferredProvider(preferredProvider)
                    .source(new AgentSource.MarkdownDefined(filePath.toString(), lastModified))
                    .metadata(metadata)
                    .build();
            return Optional.of(definition);
        } catch (IllegalArgumentException e) {
            log.warn("Agent 定义文件校验失败: path={}, error={}", filePath, e.getMessage());
            return Optional.empty();
        }
    }

    /** 分离 YAML Frontmatter 和 Markdown 正文。 */
    @Nullable
    private FrontmatterParts splitFrontmatter(String content) {
        String trimmed = content.strip();
        if (!trimmed.startsWith(FRONTMATTER_DELIMITER)) {
            return null;
        }

        int secondDelimiter = trimmed.indexOf(FRONTMATTER_DELIMITER, FRONTMATTER_DELIMITER.length());
        if (secondDelimiter < 0) {
            return null;
        }

        String yamlContent = trimmed.substring(FRONTMATTER_DELIMITER.length(), secondDelimiter).strip();
        String markdownContent = trimmed.substring(secondDelimiter + FRONTMATTER_DELIMITER.length());
        return new FrontmatterParts(yamlContent, markdownContent);
    }

    /** 从 Map 中安全获取 String 字段。 */
    @Nullable
    private String getStringField(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    /** 解析 budget 配置，缺失时使用 BudgetDefaults 默认值。 */
    @SuppressWarnings("unchecked")
    private AgentBudget parseBudget(Map<String, Object> frontmatter) {
        if (!frontmatter.containsKey("budget")) {
            return new AgentBudget(
                    budgetDefaults.getDefaultMaxTokens(),
                    budgetDefaults.getDefaultMaxSteps(),
                    budgetDefaults.getDefaultTimeoutSeconds());
        }

        Object budgetObj = frontmatter.get("budget");
        if (budgetObj instanceof Map<?, ?> budgetMap) {
            int maxTokens = getIntField((Map<String, Object>) budgetMap, "max-tokens",
                    budgetDefaults.getDefaultMaxTokens());
            int maxSteps = getIntField((Map<String, Object>) budgetMap, "max-steps",
                    budgetDefaults.getDefaultMaxSteps());
            int timeoutSeconds = getIntField((Map<String, Object>) budgetMap, "timeout-seconds",
                    budgetDefaults.getDefaultTimeoutSeconds());
            return new AgentBudget(maxTokens, maxSteps, timeoutSeconds);
        }

        return new AgentBudget(
                budgetDefaults.getDefaultMaxTokens(),
                budgetDefaults.getDefaultMaxSteps(),
                budgetDefaults.getDefaultTimeoutSeconds());
    }

    /** 从 Map 中安全获取 int 字段，缺失时返回默认值。 */
    private int getIntField(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return defaultValue;
    }

    /** Frontmatter 分离结果。 */
    private record FrontmatterParts(String yamlContent, String markdownContent) {}
}
