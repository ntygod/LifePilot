package com.lifepilot.tool.yaml;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.YamlTool;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * YAML Tool 序列化器 — 将 YamlTool 序列化为 YAML 字符串。
 *
 * <p>用于 YAML Tool 的持久化。输出的 YAML 格式符合工具定义规范。</p>
 *
 * @author zsg
 * @since 2026-02-28
 */
public class YamlToolSerializer {

    /**
     * 将 YamlTool 序列化为 YAML 字符串。
     *
     * @param tool YAML Tool
     * @return YAML 格式字符串
     */
    public String serialize(YamlTool tool) {
        Map<String, Object> root = new LinkedHashMap<>();
        
        root.put("id", tool.id());
        root.put("name", tool.name());
        
        if (tool.description() != null && !tool.description().isEmpty()) {
            root.put("description", tool.description());
        }
        
        // Input Schema
        if (tool.inputSchema() != null) {
            root.put("inputSchema", tool.inputSchema().toMap());
        }
        
        // Output Schema
        if (tool.outputSchema() != null) {
            root.put("outputSchema", tool.outputSchema().toMap());
        }
        
        // Budget
        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("timeoutSeconds", tool.budget().timeout().getSeconds());
        budget.put("maxRetries", tool.budget().maxRetries());
        budget.put("maxCostCents", tool.budget().maxCostCents());
        root.put("budget", budget);
        
        // Risk Level（仅在非 MEDIUM 时输出）
        if (tool.riskLevel() != RiskLevel.MEDIUM) {
            root.put("riskLevel", tool.riskLevel().name());
        }
        
        // Idempotent（仅在 true 时输出）
        if (tool.idempotent()) {
            root.put("idempotent", true);
        }
        
        // Tags（仅在非空时输出）
        if (tool.tags() != null && !tool.tags().isEmpty()) {
            root.put("tags", tool.tags());
        }
        
        var options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
        options.setIndicatorIndent(0);
        options.setIndent(2);
        
        var yaml = new Yaml(options);
        return yaml.dump(root);
    }
}
