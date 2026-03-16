package com.lifepilot.tool.yaml;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.SkillTool;
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
 * Skill Tool 加载器 — 从文件系统加载 Skill Tool。
 *
 * <p>解析 YAML 文件并转换为 SkillTool 对象。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
@Component
public class SkillToolLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillToolLoader.class);

    /**
     * 从文件加载 Skill Tool。
     *
     * @param filePath YAML 文件路径
     * @return Skill Tool，解析失败返回 Optional.empty()
     */
    public Optional<SkillTool> loadFile(Path filePath) {
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
            
            // 转换为 SkillTool
            return Optional.of(parseSkillTool(yamlMap));
        } catch (IOException e) {
            log.warn("读取 Skill Tool 文件失败: file={}, error={}", filePath, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("解析 Skill Tool 失败: file={}, error={}", filePath, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 从 YAML Map 解析为 SkillTool。
     *
     * @param yamlMap YAML Map
     * @return SkillTool
     */
    private SkillTool parseSkillTool(Map<String, Object> yamlMap) {
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
        
        return new SkillTool(
                id,
                name,
                description,
                inputSchema,
                outputSchema,
                riskLevel,
                idempotent,
                budget,
                tags != null ? tags : List.of(),
                id
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
