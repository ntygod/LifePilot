package com.lifepilot.skill.yaml;

import com.lifepilot.skill.model.*;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.*;

/**
 * Skill 定义序列化器 — 将 SkillDefinition 序列化为 YAML 字符串。
 *
 * <p>用于自生成 Skill 的持久化。省略与默认值相同的可选字段。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class YamlSkillSerializer {

    /**
     * 将 SkillDefinition 序列化为 YAML 字符串。
     *
     * @param definition Skill 定义
     * @return 符合 YAML Skill Schema 的字符串
     */
    public String serialize(SkillDefinition definition) {
        var skillMap = buildSkillMap(definition);
        var root = new LinkedHashMap<String, Object>();
        root.put("skill", skillMap);

        var options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
        options.setIndicatorIndent(0);
        options.setIndent(2);

        var yaml = new Yaml(options);
        return yaml.dump(root);
    }

    /**
     * 构建 skill 节点的 Map 表示。
     *
     * <p>字段映射规则：
     * <ul>
     *   <li>systemPrompt → system-prompt</li>
     *   <li>allowedTools → allowed-tools</li>
     *   <li>省略与默认值相同的 execution、budget、memory-access、metadata</li>
     *   <li>省略 null 的 preferredProviderId</li>
     * </ul></p>
     */
    private LinkedHashMap<String, Object> buildSkillMap(SkillDefinition definition) {
        var map = new LinkedHashMap<String, Object>();

        // 必填字段（始终输出）
        map.put("id", definition.id());
        map.put("name", definition.name());
        map.put("description", definition.description());
        map.put("version", definition.version());
        map.put("system-prompt", definition.systemPrompt());
        map.put("allowed-tools", new ArrayList<>(definition.allowedTools()));

        // 可选：provider-id
        if (definition.preferredProviderId() != null) {
            map.put("provider-id", definition.preferredProviderId());
        }

        // 可选：execution（省略与 DEFAULT 相同的值）
        if (!definition.execution().equals(ExecutionStrategy.DEFAULT)) {
            map.put("execution", buildExecutionMap(definition.execution()));
        }

        // 可选：memory-access（省略空策略）
        if (!definition.memoryAccess().read().isEmpty() || !definition.memoryAccess().write().isEmpty()) {
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
