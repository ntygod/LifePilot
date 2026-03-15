package com.lifepilot.multiagent.loader;

import com.lifepilot.multiagent.model.AgentBudget;
import com.lifepilot.multiagent.model.AgentDefinition;

import java.util.List;
import java.util.Map;

/**
 * Agent 定义序列化器 — 将 AgentDefinition 序列化为 YAML Frontmatter + Markdown 正文格式。
 *
 * @author zsg
 * @since 2026-03-15
 */
public class AgentMarkdownSerializer {

    /**
     * 将 AgentDefinition 序列化为 Markdown 文本。
     *
     * @param definition Agent 定义
     * @return YAML Frontmatter + System Prompt 正文
     */
    public String serialize(AgentDefinition definition) {
        var sb = new StringBuilder();
        sb.append("---\n");
        sb.append("id: ").append(definition.id()).append('\n');
        sb.append("name: \"").append(escapeYaml(definition.name())).append("\"\n");
        sb.append("description: \"").append(escapeYaml(definition.description())).append("\"\n");

        // 可选字段
        if (definition.preferredProvider() != null && !definition.preferredProvider().isBlank()) {
            sb.append("preferred-provider: ").append(definition.preferredProvider()).append('\n');
        }
        sb.append("can-delegate: ").append(definition.canDelegate()).append('\n');

        // allowed-tools
        List<String> tools = definition.allowedTools();
        if (tools != null && !tools.isEmpty()) {
            sb.append("allowed-tools:\n");
            for (String tool : tools) {
                sb.append("  - ").append(tool).append('\n');
            }
        }

        // budget
        AgentBudget budget = definition.budget();
        if (budget != null) {
            sb.append("budget:\n");
            sb.append("  max-tokens: ").append(budget.maxTokens()).append('\n');
            sb.append("  max-steps: ").append(budget.maxSteps()).append('\n');
            sb.append("  timeout-seconds: ").append(budget.timeoutSeconds()).append('\n');
        }

        // metadata
        Map<String, String> metadata = definition.metadata();
        if (metadata != null && !metadata.isEmpty()) {
            sb.append("metadata:\n");
            for (var entry : metadata.entrySet()) {
                sb.append("  ").append(entry.getKey()).append(": \"")
                        .append(escapeYaml(entry.getValue())).append("\"\n");
            }
        }

        sb.append("---\n\n");

        // Markdown 正文 = System Prompt
        if (definition.systemPrompt() != null) {
            sb.append(definition.systemPrompt()).append('\n');
        }

        return sb.toString();
    }

    /** 转义 YAML 双引号字符串中的特殊字符。 */
    private String escapeYaml(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
