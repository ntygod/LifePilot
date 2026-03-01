package com.lifepilot.tool.yaml;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.YamlTool;
import com.lifepilot.tool.model.ToolBudget;
import com.lifepilot.tool.schema.JsonSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;

/**
 * YAML Tool 加载器 — 从文件系统加载 YAML Tool。
 *
 * <p>解析 YAML 文件并转换为 YamlTool 对象。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
@Component
public class YamlToolLoader {

    private static final Logger log = LoggerFactory.getLogger(YamlToolLoader.class);

    /**
     * 从文件加载 YAML Tool。
     *
     * @param filePath YAML 文件路径
     * @return YAML Tool，解析失败返回 Optional.empty()
     */
    public Optional<YamlTool> loadFile(Path filePath) {
        try {
            // 读取文件内容
            String content = Files.readString(filePath);
            
            // 解析 YAML
            var yaml = new Yaml();
            Object parsed = yaml.load(content);
            if (!(parsed instanceof Map<?, ?> map)) {
                log.warn("YAML 文件解析结果不是 Map 类型: file={}", filePath);
                return Optional.empty();
            }
            
            @SuppressWarnings("unchecked")
            Map<String, Object> yamlMap = (Map<String, Object>) map;
            
            // 转换为 YamlTool
            return Optional.of(parseYamlTool(yamlMap));
        } catch (IOException e) {
            log.warn("读取 YAML Tool 文件失败: file={}, error={}", filePath, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("解析 YAML Tool 失败: file={}, error={}", filePath, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 从 YAML Map 解析为 YamlTool。
     *
     * @param yamlMap YAML Map
     * @return YamlTool
     */
    private YamlTool parseYamlTool(Map<String, Object> yamlMap) {
        // 必填字段
        String id = getString(yamlMap, "id");
        String name = getString(yamlMap, "name");
        
        // 可选字段
        String description = getStringOrDefault(yamlMap, "description", "");
        
        // Input Schema
        @SuppressWarnings("unchecked")
        Map<String, Object> inputSchemaMap = (Map<String, Object>) yamlMap.getOrDefault("inputSchema", Map.of());
        JsonSchema inputSchema = JsonSchema.of(inputSchemaMap);
        
        // Output Schema
        @SuppressWarnings("unchecked")
        Map<String, Object> outputSchemaMap = (Map<String, Object>) yamlMap.getOrDefault("outputSchema", Map.of());
        JsonSchema outputSchema = JsonSchema.of(outputSchemaMap);
        
        // Budget
        @SuppressWarnings("unchecked")
        Map<String, Object> budgetMap = (Map<String, Object>) yamlMap.getOrDefault("budget", Map.of());
        long timeoutSeconds = getLongOrDefault(budgetMap, "timeoutSeconds", 30L);
        int maxRetries = getIntOrDefault(budgetMap, "maxRetries", 2);
        int maxCostCents = getIntOrDefault(budgetMap, "maxCostCents", Integer.MAX_VALUE);
        ToolBudget budget = ToolBudget.of(Duration.ofSeconds(timeoutSeconds), maxRetries, maxCostCents);
        
        // Risk Level
        String riskLevelStr = getStringOrDefault(yamlMap, "riskLevel", "MEDIUM");
        RiskLevel riskLevel = parseRiskLevel(riskLevelStr);
        
        // Idempotent
        boolean idempotent = getBooleanOrDefault(yamlMap, "idempotent", false);
        
        // Tags
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) yamlMap.getOrDefault("tags", List.of());
        
        return new YamlTool(
                id,
                name,
                description,
                inputSchema,
                outputSchema,
                riskLevel,
                idempotent,
                budget,
                tags != null ? tags : List.of()
        );
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException("缺少必填字段: " + key);
        }
        return value.toString();
    }

    private String getStringOrDefault(Map<String, Object> map, String key, String defaultValue) {
        Object value = map.get(key);
        return value != null ? value.toString() : defaultValue;
    }

    private long getLongOrDefault(Map<String, Object> map, String key, long defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private int getIntOrDefault(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private boolean getBooleanOrDefault(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private RiskLevel parseRiskLevel(String riskLevelStr) {
        try {
            return RiskLevel.valueOf(riskLevelStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("无效的风险等级: {}, 使用默认值 MEDIUM", riskLevelStr);
            return RiskLevel.MEDIUM;
        }
    }
}
