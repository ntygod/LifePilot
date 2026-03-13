package com.lifepilot.workflow.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import com.lifepilot.workflow.model.ErrorStrategy;
import com.lifepilot.workflow.model.WorkflowDefinition;
import com.lifepilot.workflow.model.WorkflowInputParam;
import com.lifepilot.workflow.model.WorkflowStep;
import com.lifepilot.workflow.model.WorkflowStep.ConditionStep;
import com.lifepilot.workflow.model.WorkflowStep.LlmStep;
import com.lifepilot.workflow.model.WorkflowStep.LoopStep;
import com.lifepilot.workflow.model.WorkflowStep.NoopStep;
import com.lifepilot.workflow.model.WorkflowStep.ParallelStep;
import com.lifepilot.workflow.model.WorkflowStep.SkillStep;
import com.lifepilot.workflow.model.WorkflowStep.SubWorkflowStep;
import com.lifepilot.workflow.model.WorkflowStep.ToolStep;
import com.lifepilot.workflow.model.WorkflowStep.ApprovalStep;
import com.lifepilot.workflow.model.WorkflowStep.NotifyStep;
import com.lifepilot.workflow.model.WorkflowStep.WaitStep;
import com.lifepilot.workflow.model.WorkflowTrigger;

/**
 * YAML 工作流定义打印器。
 *
 * <p>将 {@link WorkflowDefinition} 序列化为 YAML 字符串，
 * 作为 {@link WorkflowYamlParser#parse(String)} 的逆操作。
 * 输出的 YAML 可被 {@code WorkflowYamlParser} 重新解析为等价的 {@code WorkflowDefinition}。
 *
 * <p>序列化规则：
 * <ul>
 *   <li>使用 SnakeYAML BLOCK 流样式，提高可读性</li>
 *   <li>省略 null 或空的可选字段（description、version、triggers、inputs、metadata、outputSchema、errorStrategy）</li>
 *   <li>LlmStep 的 record 字段 {@code promptTemplate} 映射为 YAML key {@code prompt}</li>
 *   <li>ErrorStrategy.Compensate 的 compensationStep 递归序列化</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-26
 */
public class WorkflowYamlPrinter {

    /**
     * 将 WorkflowDefinition 序列化为 YAML 字符串。
     *
     * @param definition 工作流定义
     * @return YAML 格式字符串
     */
    public String print(WorkflowDefinition definition) {
        Map<String, Object> root = new LinkedHashMap<>();

        root.put("id", definition.id());
        root.put("name", definition.name());

        if (definition.description() != null && !definition.description().isEmpty()) {
            root.put("description", definition.description());
        }
        if (definition.version() != null && !definition.version().isEmpty()) {
            root.put("version", definition.version());
        }

        // enabled 默认为 true，仅在 false 时输出
        if (!definition.enabled()) {
            root.put("enabled", false);
        }

        if (!definition.triggers().isEmpty()) {
            root.put("triggers", serializeTriggers(definition.triggers()));
        }
        if (!definition.inputs().isEmpty()) {
            root.put("inputs", serializeInputs(definition.inputs()));
        }

        root.put("steps", serializeSteps(definition.steps()));

        if (!definition.metadata().isEmpty()) {
            root.put("metadata", new LinkedHashMap<>(definition.metadata()));
        }

        var options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        var yaml = new Yaml(options);
        return yaml.dump(root);
    }

    // ==================== 触发器序列化 ====================

    private List<Map<String, Object>> serializeTriggers(List<WorkflowTrigger> triggers) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var trigger : triggers) {
            result.add(serializeTrigger(trigger));
        }
        return result;
    }

    private Map<String, Object> serializeTrigger(WorkflowTrigger trigger) {
        Map<String, Object> map = new LinkedHashMap<>();
        switch (trigger) {
            case WorkflowTrigger.CronTrigger cron -> {
                map.put("type", "cron");
                map.put("cron", cron.cron());
            }
            case WorkflowTrigger.EventTrigger event -> {
                map.put("type", "event");
                map.put("eventType", event.eventType());
            }
            case WorkflowTrigger.ManualTrigger _ -> map.put("type", "manual");
        }
        return map;
    }

    // ==================== 输入参数序列化 ====================

    private Map<String, Object> serializeInputs(Map<String, WorkflowInputParam> inputs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : inputs.entrySet()) {
            result.put(entry.getKey(), serializeInputParam(entry.getValue()));
        }
        return result;
    }

    private Map<String, Object> serializeInputParam(WorkflowInputParam param) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", param.type());
        map.put("required", param.required());
        if (param.defaultValue() != null) {
            map.put("defaultValue", param.defaultValue());
        }
        if (param.description() != null && !param.description().isEmpty()) {
            map.put("description", param.description());
        }
        return map;
    }

    // ==================== 步骤序列化 ====================

    private List<Map<String, Object>> serializeSteps(List<WorkflowStep> steps) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (var step : steps) {
            result.add(serializeStep(step));
        }
        return result;
    }

    private Map<String, Object> serializeStep(WorkflowStep step) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", step.id());
        map.put("name", step.name());

        switch (step) {
            case SkillStep s -> {
                map.put("type", "skill");
                map.put("skillId", s.skillId());
                if (!s.params().isEmpty()) {
                    map.put("params", new LinkedHashMap<>(s.params()));
                }
            }
            case ToolStep s -> {
                map.put("type", "tool");
                map.put("toolId", s.toolId());
                if (!s.params().isEmpty()) {
                    map.put("params", new LinkedHashMap<>(s.params()));
                }
            }
            case LlmStep s -> {
                map.put("type", "llm");
                map.put("scene", s.scene());
                map.put("capability", s.capability().name());
                // record 字段 promptTemplate 映射为 YAML key "prompt"
                map.put("prompt", s.promptTemplate());
                if (s.outputSchema() != null && !s.outputSchema().isEmpty()) {
                    map.put("outputSchema", s.outputSchema());
                }
                if (s.modelName() != null && !s.modelName().isEmpty()) {
                    map.put("modelName", s.modelName());
                }
                if (s.preferredProviderId() != null && !s.preferredProviderId().isEmpty()) {
                    map.put("preferredProviderId", s.preferredProviderId());
                }
                if (s.media() != null && !s.media().isEmpty()) {
                    List<Map<String, Object>> media = new ArrayList<>();
                    for (var mediaRef : s.media()) {
                        Map<String, Object> mediaMap = new LinkedHashMap<>();
                        mediaMap.put("source", mediaRef.source());
                        if (mediaRef.mimeType() != null && !mediaRef.mimeType().isEmpty()) {
                            mediaMap.put("mimeType", mediaRef.mimeType());
                        }
                        if (mediaRef.fileName() != null && !mediaRef.fileName().isEmpty()) {
                            mediaMap.put("fileName", mediaRef.fileName());
                        }
                        media.add(mediaMap);
                    }
                    map.put("media", media);
                }
            }
            case ConditionStep s -> {
                map.put("type", "condition");
                map.put("condition", s.condition());
                if (!s.thenSteps().isEmpty()) {
                    map.put("then", serializeSteps(s.thenSteps()));
                }
                if (!s.elseSteps().isEmpty()) {
                    map.put("else", serializeSteps(s.elseSteps()));
                }
            }
            case LoopStep s -> {
                map.put("type", "loop");
                map.put("items", s.items());
                map.put("loopVar", s.loopVar());
                if (!s.body().isEmpty()) {
                    map.put("body", serializeSteps(s.body()));
                }
            }
            case ParallelStep s -> {
                map.put("type", "parallel");
                List<List<Map<String, Object>>> branches = new ArrayList<>();
                for (var branch : s.branches()) {
                    branches.add(serializeSteps(branch));
                }
                map.put("branches", branches);
            }
            case SubWorkflowStep s -> {
                map.put("type", "sub-workflow");
                map.put("workflowId", s.workflowId());
                if (!s.params().isEmpty()) {
                    map.put("params", new LinkedHashMap<>(s.params()));
                }
            }
            case NoopStep _ -> map.put("type", "noop");
            case WaitStep s -> {
                map.put("type", "wait");
                map.put("durationSeconds", s.durationSeconds());
            }
            case ApprovalStep s -> {
                map.put("type", "approval");
                map.put("message", s.message());
                if (!s.approvers().isEmpty()) {
                    map.put("approvers", new ArrayList<>(s.approvers()));
                }
                map.put("timeoutSeconds", s.approvalTimeoutSeconds());
                map.put("autoApproveOnTimeout", s.autoApproveOnTimeout());
            }
            case NotifyStep s -> {
                map.put("type", "notify");
                map.put("targetUserId", s.targetUserId());
                map.put("content", s.content());
                map.put("contentType", s.contentType());
                map.put("urgency", s.urgency().name());
            }
        }

        // dependsOn（非空时输出）
        if (step.dependsOn() != null && !step.dependsOn().isEmpty()) {
            map.put("dependsOn", new ArrayList<>(step.dependsOn()));
        }

        // 错误策略（仅在非 null 时输出）
        if (step.errorStrategy() != null) {
            map.put("errorStrategy", serializeErrorStrategy(step.errorStrategy()));
        }

        return map;
    }

    // ==================== 错误策略序列化 ====================

    private Map<String, Object> serializeErrorStrategy(ErrorStrategy strategy) {
        Map<String, Object> map = new LinkedHashMap<>();
        switch (strategy) {
            case ErrorStrategy.Retry r -> {
                map.put("type", "retry");
                map.put("maxAttempts", r.maxAttempts());
                map.put("initialDelayMs", r.initialDelayMs());
                map.put("maxDelayMs", r.maxDelayMs());
            }
            case ErrorStrategy.Skip s -> {
                map.put("type", "skip");
                map.put("reason", s.reason());
            }
            case ErrorStrategy.Fail _ -> map.put("type", "fail");
            case ErrorStrategy.Compensate c -> {
                map.put("type", "compensate");
                map.put("compensationStep", serializeStep(c.compensationStep()));
            }
        }
        return map;
    }
}
