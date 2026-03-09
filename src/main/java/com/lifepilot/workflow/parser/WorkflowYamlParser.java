package com.lifepilot.workflow.parser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import com.lifepilot.workflow.model.ErrorStrategy;
import com.lifepilot.workflow.model.Result;
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
import com.lifepilot.workflow.model.WorkflowStep.WaitStep;
import com.lifepilot.workflow.model.WorkflowTrigger;
import com.lifepilot.workflow.model.WorkflowTrigger.CronTrigger;
import com.lifepilot.workflow.model.WorkflowTrigger.EventTrigger;
import com.lifepilot.workflow.model.WorkflowTrigger.ManualTrigger;

import com.lifepilot.workflow.config.WorkflowConfigProperties;

/**
 * YAML 工作流定义解析器。
 *
 * <p>使用 SnakeYAML 将 YAML 字符串解析为 {@link WorkflowDefinition}。
 * 解析过程中收集所有校验错误，通过 {@link Result} 返回成功或失败结果。
 *
 * <p>支持解析全部 10 种步骤类型、3 种触发器类型和 4 种错误策略。
 * 校验规则包括：必填字段检查、未知步骤类型检测、重复步骤 ID 检测（递归检查嵌套步骤）、
 * dependsOn 引用验证。
 *
 * @author zsg
 * @since 2026-02-26
 */
public class WorkflowYamlParser {

    private static final Logger log = LoggerFactory.getLogger(WorkflowYamlParser.class);

    private final WorkflowConfigProperties configProperties;

    /**
     * 构造解析器（无配置注入，使用默认值）。
     */
    public WorkflowYamlParser() {
        this.configProperties = null;
    }

    /**
     * 构造解析器，注入配置属性用于 ApprovalStep 默认值。
     *
     * @param configProperties 工作流配置属性
     */
    public WorkflowYamlParser(WorkflowConfigProperties configProperties) {
        this.configProperties = configProperties;
    }

    private static final Set<String> KNOWN_STEP_TYPES = Set.of(
            "skill", "tool", "llm", "condition", "loop", "parallel",
            "sub-workflow", "noop", "wait", "approval"
    );

    private static final Set<String> KNOWN_TRIGGER_TYPES = Set.of("cron", "event", "manual");

    private static final Set<String> KNOWN_ERROR_STRATEGY_TYPES = Set.of(
            "retry", "skip", "fail", "compensate"
    );

    /**
     * 解析 YAML 字符串为 WorkflowDefinition。
     *
     * @param yaml YAML 格式的工作流定义字符串
     * @return 成功时返回 {@link Result.Ok}，失败时返回 {@link Result.Err} 包含错误列表
     */
    @SuppressWarnings("unchecked")
    public Result<WorkflowDefinition, List<String>> parse(String yaml) {
        List<String> errors = new ArrayList<>();

        // 解析 YAML 为 Map
        Map<String, Object> root;
        try {
            var yamlParser = new Yaml();
            Object parsed = yamlParser.load(yaml);
            if (!(parsed instanceof Map<?, ?> map)) {
                errors.add("YAML 根节点必须是 Map 类型");
                return new Result.Err<>(errors);
            }
            root = (Map<String, Object>) map;
        } catch (Exception e) {
            errors.add("YAML 语法错误: " + e.getMessage());
            return new Result.Err<>(errors);
        }

        // 校验必填字段
        String id = getString(root, "id");
        String name = getString(root, "name");
        if (id == null || id.isBlank()) {
            errors.add("缺失必填字段: id");
        }
        if (name == null || name.isBlank()) {
            errors.add("缺失必填字段: name");
        }
        if (!root.containsKey("steps")) {
            errors.add("缺失必填字段: steps");
        }

        // 解析可选字段
        String description = getString(root, "description");
        String version = getString(root, "version");
        boolean enabled = getBoolean(root, "enabled", true);

        // 解析触发器
        List<WorkflowTrigger> triggers = parseTriggers(root.get("triggers"), errors);

        // 解析输入参数
        Map<String, WorkflowInputParam> inputs = parseInputs(root.get("inputs"), errors);

        // 解析步骤
        Set<String> stepIds = new HashSet<>();
        List<WorkflowStep> steps = parseSteps(root.get("steps"), errors, stepIds);
        if (steps.isEmpty() && root.containsKey("steps")) {
            errors.add("steps 列表不能为空");
        }

        // 后置验证：dependsOn 引用的步骤 ID 必须存在
        for (WorkflowStep step : steps) {
            if (step.dependsOn() != null) {
                for (String dep : step.dependsOn()) {
                    if (!stepIds.contains(dep)) {
                        errors.add("步骤 '%s' 的 dependsOn 引用了不存在的步骤 ID: %s".formatted(step.id(), dep));
                    }
                }
            }
        }

        // 解析元数据
        Map<String, String> metadata = parseMetadata(root.get("metadata"));

        if (!errors.isEmpty()) {
            log.debug("YAML 解析失败，共 {} 个错误", errors.size());
            return new Result.Err<>(errors);
        }

        var definition = WorkflowDefinition.builder()
                .id(id)
                .name(name)
                .description(description)
                .version(version)
                .enabled(enabled)
                .triggers(triggers)
                .inputs(inputs)
                .steps(steps)
                .metadata(metadata)
                .build();

        log.debug("YAML 解析成功: id={}", id);
        return new Result.Ok<>(definition);
    }

    // ==================== 触发器解析 ====================

    @SuppressWarnings("unchecked")
    private List<WorkflowTrigger> parseTriggers(Object triggersObj, List<String> errors) {
        if (triggersObj == null) {
            return List.of();
        }
        if (!(triggersObj instanceof List<?> list)) {
            errors.add("triggers 必须是列表类型");
            return List.of();
        }
        List<WorkflowTrigger> triggers = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof Map<?, ?> map)) {
                errors.add("triggers[%d] 必须是 Map 类型".formatted(i));
                continue;
            }
            var triggerMap = (Map<String, Object>) map;
            String type = getString(triggerMap, "type");
            if (type == null || type.isBlank()) {
                errors.add("triggers[%d] 缺失必填字段: type".formatted(i));
                continue;
            }
            if (!KNOWN_TRIGGER_TYPES.contains(type)) {
                errors.add("triggers[%d] 未知触发器类型: %s".formatted(i, type));
                continue;
            }
            switch (type) {
                case "cron" -> {
                    String cron = getString(triggerMap, "cron");
                    if (cron == null || cron.isBlank()) {
                        errors.add("triggers[%d] cron 触发器缺失必填字段: cron".formatted(i));
                    } else {
                        triggers.add(new CronTrigger(cron));
                    }
                }
                case "event" -> {
                    String eventType = getString(triggerMap, "eventType");
                    if (eventType == null || eventType.isBlank()) {
                        errors.add("triggers[%d] event 触发器缺失必填字段: eventType".formatted(i));
                    } else {
                        triggers.add(new EventTrigger(eventType));
                    }
                }
                case "manual" -> triggers.add(new ManualTrigger());
                default -> errors.add("triggers[%d] 未知触发器类型: %s".formatted(i, type));
            }
        }
        return triggers;
    }

    // ==================== 输入参数解析 ====================

    @SuppressWarnings("unchecked")
    private Map<String, WorkflowInputParam> parseInputs(Object inputsObj, List<String> errors) {
        if (inputsObj == null) {
            return Map.of();
        }
        if (!(inputsObj instanceof Map<?, ?> map)) {
            errors.add("inputs 必须是 Map 类型");
            return Map.of();
        }
        Map<String, WorkflowInputParam> inputs = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            String paramName = String.valueOf(entry.getKey());
            Object paramValue = entry.getValue();
            if (!(paramValue instanceof Map<?, ?> paramMap)) {
                errors.add("inputs.%s 必须是 Map 类型".formatted(paramName));
                continue;
            }
            var pm = (Map<String, Object>) paramMap;
            String type = getString(pm, "type");
            if (type == null) {
                type = "string";
            }
            boolean required = getBoolean(pm, "required", false);
            Object defaultValue = pm.get("defaultValue");
            if (defaultValue == null) {
                defaultValue = pm.get("default");
            }
            String description = getString(pm, "description");
            inputs.put(paramName, new WorkflowInputParam(paramName, type, required, defaultValue, description));
        }
        return inputs;
    }

    // ==================== 元数据解析 ====================

    private Map<String, String> parseMetadata(Object metadataObj) {
        if (metadataObj == null) {
            return Map.of();
        }
        if (!(metadataObj instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            metadata.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        return metadata;
    }

    // ==================== 步骤解析 ====================

    @SuppressWarnings("unchecked")
    private List<WorkflowStep> parseSteps(Object stepsObj, List<String> errors, Set<String> stepIds) {
        if (stepsObj == null) {
            return List.of();
        }
        if (!(stepsObj instanceof List<?> list)) {
            errors.add("steps 必须是列表类型");
            return List.of();
        }
        List<WorkflowStep> steps = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof Map<?, ?> map)) {
                errors.add("steps[%d] 必须是 Map 类型".formatted(i));
                continue;
            }
            WorkflowStep step = parseSingleStep((Map<String, Object>) map, errors, stepIds);
            if (step != null) {
                steps.add(step);
            }
        }
        return steps;
    }

    private WorkflowStep parseSingleStep(Map<String, Object> map, List<String> errors, Set<String> stepIds) {
        String id = getString(map, "id");
        String name = getString(map, "name");
        String type = getString(map, "type");

        if (id == null || id.isBlank()) {
            errors.add("步骤缺失必填字段: id");
            return null;
        }
        if (name == null || name.isBlank()) {
            errors.add("步骤 '%s' 缺失必填字段: name".formatted(id));
            return null;
        }
        if (type == null || type.isBlank()) {
            errors.add("步骤 '%s' 缺失必填字段: type".formatted(id));
            return null;
        }

        // 重复步骤 ID 检测
        if (!stepIds.add(id)) {
            errors.add("重复的步骤 ID: %s".formatted(id));
            return null;
        }

        // 未知步骤类型检测
        if (!KNOWN_STEP_TYPES.contains(type)) {
            errors.add("步骤 '%s' 未知步骤类型: %s".formatted(id, type));
            return null;
        }

        // 解析错误策略
        ErrorStrategy errorStrategy = parseErrorStrategy(map.get("errorStrategy"), id, errors, stepIds);

        // 解析 dependsOn（可选）
        List<String> dependsOn = parseDependsOn(map.get("dependsOn"));

        return switch (type) {
            case "skill" -> parseSkillStep(id, name, map, dependsOn, errorStrategy, errors);
            case "tool" -> parseToolStep(id, name, map, dependsOn, errorStrategy, errors);
            case "llm" -> parseLlmStep(id, name, map, dependsOn, errorStrategy, errors);
            case "condition" -> parseConditionStep(id, name, map, dependsOn, errorStrategy, errors, stepIds);
            case "loop" -> parseLoopStep(id, name, map, dependsOn, errorStrategy, errors, stepIds);
            case "parallel" -> parseParallelStep(id, name, map, dependsOn, errorStrategy, errors, stepIds);
            case "sub-workflow" -> parseSubWorkflowStep(id, name, map, dependsOn, errorStrategy, errors);
            case "noop" -> new NoopStep(id, name, dependsOn, errorStrategy);
            case "wait" -> parseWaitStep(id, name, map, dependsOn, errorStrategy, errors);
            case "approval" -> parseApprovalStep(id, name, map, dependsOn, errorStrategy, errors);
            default -> {
                errors.add("步骤 '%s' 未知步骤类型: %s".formatted(id, type));
                yield null;
            }
        };
    }

    // ==================== 各步骤类型解析 ====================

    private SkillStep parseSkillStep(String id, String name, Map<String, Object> map,
                                     List<String> dependsOn,
                                     ErrorStrategy errorStrategy, List<String> errors) {
        String skillId = getString(map, "skillId");
        if (skillId == null || skillId.isBlank()) {
            errors.add("步骤 '%s' (skill) 缺失必填字段: skillId".formatted(id));
            return null;
        }
        Map<String, String> params = parseStringMap(map.get("params"));
        return new SkillStep(id, name, skillId, params, dependsOn, errorStrategy);
    }

    private ToolStep parseToolStep(String id, String name, Map<String, Object> map,
                                   List<String> dependsOn,
                                   ErrorStrategy errorStrategy, List<String> errors) {
        String toolId = getString(map, "toolId");
        if (toolId == null || toolId.isBlank()) {
            errors.add("步骤 '%s' (tool) 缺失必填字段: toolId".formatted(id));
            return null;
        }
        Map<String, String> params = parseStringMap(map.get("params"));
        return new ToolStep(id, name, toolId, params, dependsOn, errorStrategy);
    }

    private LlmStep parseLlmStep(String id, String name, Map<String, Object> map,
                                  List<String> dependsOn,
                                  ErrorStrategy errorStrategy, List<String> errors) {
        String scene = getString(map, "scene");
        if (scene == null || scene.isBlank()) {
            errors.add("步骤 '%s' (llm) 缺失必填字段: scene".formatted(id));
            return null;
        }
        // YAML 中 key 为 "prompt"，映射到 record 字段 "promptTemplate"
        String promptTemplate = getString(map, "prompt");
        if (promptTemplate == null || promptTemplate.isBlank()) {
            errors.add("步骤 '%s' (llm) 缺失必填字段: prompt".formatted(id));
            return null;
        }
        String outputSchema = getString(map, "outputSchema");
        return new LlmStep(id, name, scene, promptTemplate, outputSchema, dependsOn, errorStrategy);
    }

    private ConditionStep parseConditionStep(String id, String name, Map<String, Object> map,
                                             List<String> dependsOn,
                                             ErrorStrategy errorStrategy, List<String> errors,
                                             Set<String> stepIds) {
        String condition = getString(map, "condition");
        if (condition == null || condition.isBlank()) {
            errors.add("步骤 '%s' (condition) 缺失必填字段: condition".formatted(id));
            return null;
        }
        List<WorkflowStep> thenSteps = parseSteps(map.get("then"), errors, stepIds);
        List<WorkflowStep> elseSteps = parseSteps(map.get("else"), errors, stepIds);
        return new ConditionStep(id, name, condition, thenSteps, elseSteps, dependsOn, errorStrategy);
    }

    private LoopStep parseLoopStep(String id, String name, Map<String, Object> map,
                                   List<String> dependsOn,
                                   ErrorStrategy errorStrategy, List<String> errors,
                                   Set<String> stepIds) {
        String items = getString(map, "items");
        if (items == null || items.isBlank()) {
            errors.add("步骤 '%s' (loop) 缺失必填字段: items".formatted(id));
            return null;
        }
        String loopVar = getString(map, "loopVar");
        if (loopVar == null || loopVar.isBlank()) {
            errors.add("步骤 '%s' (loop) 缺失必填字段: loopVar".formatted(id));
            return null;
        }
        List<WorkflowStep> body = parseSteps(map.get("body"), errors, stepIds);
        return new LoopStep(id, name, items, loopVar, body, dependsOn, errorStrategy);
    }

    @SuppressWarnings("unchecked")
    private ParallelStep parseParallelStep(String id, String name, Map<String, Object> map,
                                           List<String> dependsOn,
                                           ErrorStrategy errorStrategy, List<String> errors,
                                           Set<String> stepIds) {
        Object branchesObj = map.get("branches");
        if (branchesObj == null) {
            errors.add("步骤 '%s' (parallel) 缺失必填字段: branches".formatted(id));
            return null;
        }
        if (!(branchesObj instanceof List<?> branchesList)) {
            errors.add("步骤 '%s' (parallel) branches 必须是列表类型".formatted(id));
            return null;
        }
        List<List<WorkflowStep>> branches = new ArrayList<>();
        for (int i = 0; i < branchesList.size(); i++) {
            Object branch = branchesList.get(i);
            if (!(branch instanceof List<?> branchStepsList)) {
                errors.add("步骤 '%s' (parallel) branches[%d] 必须是列表类型".formatted(id, i));
                continue;
            }
            // 每个分支是一组步骤
            List<WorkflowStep> branchSteps = new ArrayList<>();
            for (int j = 0; j < branchStepsList.size(); j++) {
                Object stepObj = branchStepsList.get(j);
                if (!(stepObj instanceof Map<?, ?> stepMap)) {
                    errors.add("步骤 '%s' (parallel) branches[%d][%d] 必须是 Map 类型".formatted(id, i, j));
                    continue;
                }
                WorkflowStep step = parseSingleStep((Map<String, Object>) stepMap, errors, stepIds);
                if (step != null) {
                    branchSteps.add(step);
                }
            }
            branches.add(branchSteps);
        }
        return new ParallelStep(id, name, branches, dependsOn, errorStrategy);
    }

    private SubWorkflowStep parseSubWorkflowStep(String id, String name, Map<String, Object> map,
                                                  List<String> dependsOn,
                                                  ErrorStrategy errorStrategy, List<String> errors) {
        String workflowId = getString(map, "workflowId");
        if (workflowId == null || workflowId.isBlank()) {
            errors.add("步骤 '%s' (sub-workflow) 缺失必填字段: workflowId".formatted(id));
            return null;
        }
        Map<String, String> params = parseStringMap(map.get("params"));
        return new SubWorkflowStep(id, name, workflowId, params, dependsOn, errorStrategy);
    }

    private WaitStep parseWaitStep(String id, String name, Map<String, Object> map,
                                   List<String> dependsOn,
                                   ErrorStrategy errorStrategy, List<String> errors) {
        Object durationObj = map.get("durationSeconds");
        if (durationObj == null) {
            errors.add("步骤 '%s' (wait) 缺失必填字段: durationSeconds".formatted(id));
            return null;
        }
        long durationSeconds;
        try {
            durationSeconds = ((Number) durationObj).longValue();
        } catch (ClassCastException e) {
            errors.add("步骤 '%s' (wait) durationSeconds 必须是数字类型".formatted(id));
            return null;
        }
        return new WaitStep(id, name, durationSeconds, dependsOn, errorStrategy);
    }

    /**
     * 解析 ApprovalStep。
     */
    private ApprovalStep parseApprovalStep(String id, String name, Map<String, Object> map,
                                           List<String> dependsOn,
                                           ErrorStrategy errorStrategy, List<String> errors) {
        String message = getString(map, "message");
        if (message == null || message.isBlank()) {
            errors.add("步骤 '%s' (approval) 缺失必填字段: message".formatted(id));
            return null;
        }
        List<String> approvers = parseStringList(map.get("approvers"));
        if (approvers.isEmpty()) {
            approvers = List.of("owner");
        }
        // 默认值从配置读取，配置不存在时使用硬编码默认值
        int defaultTimeout = configProperties != null
                ? configProperties.getApproval().getDefaultTimeoutSeconds() : 86400;
        boolean defaultAutoApprove = configProperties != null
                && configProperties.getApproval().isAutoApproveOnTimeout();

        int timeoutSeconds = getInt(map, "timeoutSeconds", defaultTimeout);
        boolean autoApproveOnTimeout = getBoolean(map, "autoApproveOnTimeout", defaultAutoApprove);

        return new ApprovalStep(id, name, message, approvers, timeoutSeconds,
                autoApproveOnTimeout, dependsOn, errorStrategy);
    }

    /**
     * 解析 dependsOn 字段为 List&lt;String&gt;。
     */
    private static List<String> parseDependsOn(Object dependsOnObj) {
        if (dependsOnObj == null) {
            return List.of();
        }
        if (dependsOnObj instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return List.copyOf(result);
        }
        // 单个字符串也支持
        return List.of(String.valueOf(dependsOnObj));
    }

    /**
     * 解析字符串列表。
     */
    private static List<String> parseStringList(Object obj) {
        if (obj == null) {
            return List.of();
        }
        if (obj instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return List.copyOf(result);
        }
        return List.of(String.valueOf(obj));
    }

    // ==================== 错误策略解析 ====================

    @SuppressWarnings("unchecked")
    private ErrorStrategy parseErrorStrategy(Object strategyObj, String stepId,
                                             List<String> errors, Set<String> stepIds) {
        if (strategyObj == null) {
            return null;
        }
        if (!(strategyObj instanceof Map<?, ?> map)) {
            errors.add("步骤 '%s' errorStrategy 必须是 Map 类型".formatted(stepId));
            return null;
        }
        var strategyMap = (Map<String, Object>) map;
        String type = getString(strategyMap, "type");
        if (type == null || type.isBlank()) {
            errors.add("步骤 '%s' errorStrategy 缺失必填字段: type".formatted(stepId));
            return null;
        }
        if (!KNOWN_ERROR_STRATEGY_TYPES.contains(type)) {
            errors.add("步骤 '%s' errorStrategy 未知策略类型: %s".formatted(stepId, type));
            return null;
        }
        return switch (type) {
            case "retry" -> {
                int maxAttempts = getInt(strategyMap, "maxAttempts", 3);
                long initialDelayMs = getLong(strategyMap, "initialDelayMs", 500L);
                long maxDelayMs = getLong(strategyMap, "maxDelayMs", 5000L);
                yield new ErrorStrategy.Retry(maxAttempts, initialDelayMs, maxDelayMs);
            }
            case "skip" -> {
                String reason = getString(strategyMap, "reason");
                yield new ErrorStrategy.Skip(reason != null ? reason : "");
            }
            case "fail" -> new ErrorStrategy.Fail();
            case "compensate" -> {
                Object compStepObj = strategyMap.get("compensationStep");
                if (compStepObj == null) {
                    errors.add("步骤 '%s' compensate 策略缺失必填字段: compensationStep".formatted(stepId));
                    yield null;
                }
                if (!(compStepObj instanceof Map<?, ?> compMap)) {
                    errors.add("步骤 '%s' compensate 策略 compensationStep 必须是 Map 类型".formatted(stepId));
                    yield null;
                }
                WorkflowStep compStep = parseSingleStep((Map<String, Object>) compMap, errors, stepIds);
                if (compStep == null) {
                    yield null;
                }
                yield new ErrorStrategy.Compensate(compStep);
            }
            default -> {
                errors.add("步骤 '%s' errorStrategy 未知策略类型: %s".formatted(stepId, type));
                yield null;
            }
        };
    }

    // ==================== 工具方法 ====================

    private static String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? String.valueOf(value) : null;
    }

    private static boolean getBoolean(Map<String, Object> map, String key, boolean defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static int getInt(Map<String, Object> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long getLong(Map<String, Object> map, String key, long defaultValue) {
        Object value = map.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static Map<String, String> parseStringMap(Object obj) {
        if (obj == null) {
            return Map.of();
        }
        if (!(obj instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        return result;
    }
}
