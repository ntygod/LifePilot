package com.lifepilot.skill.markdown;

import com.lifepilot.skill.model.*;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.*;

/**
 * SKILL.md 序列化器 — 将 SkillDefinition 序列化为 SKILL.md 格式。
 *
 * <p>序列化规则：
 * <ol>
 *   <li>输出 {@code ---} 开头</li>
 *   <li>使用 SnakeYAML 将元数据字段序列化为 YAML（保持字段顺序：id → name → description → version → allowed-tools → ...）</li>
 *   <li>省略与默认值相同的可选字段（execution、memory-access、budget、metadata）</li>
 *   <li>输出 {@code ---} 结尾</li>
 *   <li>输出空行</li>
 *   <li>输出 systemPrompt 作为 Markdown Body</li>
 * </ol>
 *
 * @author zsg
 * @since 2026-03-07
 */
public class MarkdownSkillSerializer {

    /**
     * 将 SkillDefinition 序列化为 SKILL.md 格式字符串。
     *
     * @param definition Skill 定义
     * @return SKILL.md 格式的完整文本
     */
    public String serialize(SkillDefinition definition) {
        var frontmatterMap = buildFrontmatterMap(definition);

        // 配置 SnakeYAML — BLOCK 风格、2 空格缩进
        var options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
        options.setIndicatorIndent(0);
        options.setIndent(2);

        var yaml = new Yaml(options);
        String yamlContent = yaml.dump(frontmatterMap);

        // 组装 SKILL.md：--- + YAML + --- + 空行 + Markdown Body
        var sb = new StringBuilder();
        sb.append("---\n");
        sb.append(yamlContent);
        sb.append("---\n");
        sb.append("\n");
        sb.append(definition.systemPrompt());
        sb.append("\n");

        return sb.toString();
    }

    // ─────────────────────────────────────────────
    //  Frontmatter Map 构建
    // ─────────────────────────────────────────────

    /**
     * 构建 Frontmatter 的有序 Map 表示。
     *
     * <p>字段顺序：id → name → description → version → allowed-tools →
     * execution → memory-access → budget → metadata。
     * 等于默认值的可选字段不输出。</p>
     */
    private LinkedHashMap<String, Object> buildFrontmatterMap(SkillDefinition definition) {
        var map = new LinkedHashMap<String, Object>();

        // 必填字段（始终输出）
        map.put("id", definition.id());
        map.put("name", definition.name());
        map.put("description", definition.description());
        map.put("version", definition.version());
        map.put("allowed-tools", new ArrayList<>(definition.allowedTools()));

        // 可选：execution（省略与 DEFAULT 相同的值）
        if (!definition.execution().equals(ExecutionStrategy.DEFAULT)) {
            map.put("execution", buildExecutionMap(definition.execution()));
        }

        // 可选：memory-access（省略空策略）
        if (!definition.memoryAccess().equals(MemoryAccessPolicy.none())) {
            map.put("memory-access", buildMemoryAccessMap(definition.memoryAccess()));
        }

        // 可选：budget（省略与 DEFAULT 相同的值）
        if (!definition.budget().equals(SkillBudget.DEFAULT)) {
            map.put("budget", buildBudgetMap(definition.budget()));
        }

        // 可选：metadata（省略空 Map）
        if (!definition.metadata().isEmpty()) {
            map.put("metadata", new LinkedHashMap<>(definition.metadata()));
        }

        return map;
    }

    // ─────────────────────────────────────────────
    //  子节点构建
    // ─────────────────────────────────────────────

    /**
     * 构建 execution 节点。
     */
    private LinkedHashMap<String, Object> buildExecutionMap(ExecutionStrategy execution) {
        var map = new LinkedHashMap<String, Object>();
        map.put("max-steps", execution.maxSteps());
        map.put("timeout-seconds", execution.timeoutSeconds());

        if (execution.requireConfirmation()) {
            map.put("require-confirmation", true);
        }

        if (execution.retryPolicy() != ExecutionStrategy.RetryPolicy.DEFAULT) {
            map.put("retry", execution.retryPolicy().name());
        }

        if (execution.confirmationMode() != ExecutionStrategy.ConfirmationMode.NONE) {
            map.put("confirmation-mode", execution.confirmationMode().name());
        }

        return map;
    }

    /**
     * 构建 memory-access 节点。
     */
    private LinkedHashMap<String, Object> buildMemoryAccessMap(MemoryAccessPolicy memoryAccess) {
        var map = new LinkedHashMap<String, Object>();

        if (!memoryAccess.read().isEmpty()) {
            var readList = new ArrayList<Map<String, Object>>();
            for (var perm : memoryAccess.read()) {
                var readMap = new LinkedHashMap<String, Object>();
                readMap.put("layer", perm.layer());
                readMap.put("entity-types", new ArrayList<>(perm.entityTypes()));
                if (perm.timeRange() != null) {
                    readMap.put("time-range", perm.timeRange());
                }
                readList.add(readMap);
            }
            map.put("read", readList);
        }

        if (!memoryAccess.write().isEmpty()) {
            var writeList = new ArrayList<Map<String, Object>>();
            for (var perm : memoryAccess.write()) {
                var writeMap = new LinkedHashMap<String, Object>();
                writeMap.put("layer", perm.layer());
                writeMap.put("entity-types", new ArrayList<>(perm.entityTypes()));
                writeMap.put("require-approval", perm.requireApproval());
                writeList.add(writeMap);
            }
            map.put("write", writeList);
        }

        return map;
    }

    /**
     * 构建 budget 节点。
     */
    private LinkedHashMap<String, Object> buildBudgetMap(SkillBudget budget) {
        var map = new LinkedHashMap<String, Object>();
        map.put("max-tokens", budget.maxTokens());
        map.put("max-steps", budget.maxSteps());
        map.put("timeout-seconds", budget.timeoutSeconds());
        map.put("max-cost-cents", budget.maxCostCents());
        return map;
    }
}
